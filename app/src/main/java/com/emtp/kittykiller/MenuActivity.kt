package com.emtp.kittykiller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MenuActivity : AppCompatActivity() {

    // Variable para saber qué botón se pulsó antes de elegir archivo
    // Modos: "TEST", "TEORIA", "AUDIO"
    private var modoSeleccionado = "TEST"

    private lateinit var progressBar: ProgressBar
    private lateinit var tvEstado: TextView
    private lateinit var gestorDocumentos: GestorDocumentos

    // Almacenar texto completo temporalmente para selección
    private var textoTeoriaCompleto: String = ""
    private var cantidadPreguntasSeleccionada: Int = 20

    // Launcher para TextSelectionActivity
    private val textSelectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val textoSeleccionado = result.data?.getStringExtra("TEXTO_SELECCIONADO")
                if (!textoSeleccionado.isNullOrEmpty()) {
                    generarPreguntasConIA(textoSeleccionado, cantidadPreguntasSeleccionada)
                } else {
                    // No se seleccionó texto, volver al diálogo de alcance
                    Toast.makeText(this, "No se seleccionó texto", Toast.LENGTH_SHORT).show()
                    solicitarAlcanceTexto(textoTeoriaCompleto, cantidadPreguntasSeleccionada)
                }
            } else {
                // Usuario canceló, volver al diálogo de alcance
                solicitarAlcanceTexto(textoTeoriaCompleto, cantidadPreguntasSeleccionada)
            }
        }

    // Selector de archivos - ahora procesa directamente
    private val selectorArchivo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                when (modoSeleccionado) {
                    "TEST", "TEORIA", "AUDIO" -> procesarDocumento(uri)
                }
            } else {
                tvEstado.text = "Selección cancelada"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // Inicializar vistas
        progressBar = findViewById(R.id.progressBar)
        tvEstado = findViewById(R.id.tvEstado)
        
        val btnCargarTest = findViewById<Button>(R.id.btnCargarTest)
        val btnCargarTeoria = findViewById<Button>(R.id.btnCargarTeoria)
        val btnAudioTeoria = findViewById<Button>(R.id.btnAudioTeoria)
        val btnRetomar = findViewById<Button>(R.id.btnRetomar)

        // Inicializar Gestor
        gestorDocumentos = GestorDocumentos(this)

        // Inicializar Motor IA en segundo plano
        lifecycleScope.launch {
            val exito = MotorIA.inicializar(applicationContext)
            if (exito) {
                tvEstado.text = "Sistema IA listo"
            } else {
                tvEstado.text = "IA no disponible (modelo no encontrado)"
            }
        }

        // MODO TEST
        btnCargarTest.setOnClickListener {
            modoSeleccionado = "TEST"
            abrirSelector()
        }

        // MODO TEORÍA (IA)
        btnCargarTeoria.setOnClickListener {
            modoSeleccionado = "TEORIA"
            abrirSelector()
        }

        // MODO AUDIO
        btnAudioTeoria.setOnClickListener {
            modoSeleccionado = "AUDIO"
            abrirSelector()
        }

        // RETOMAR EXAMEN
        btnRetomar.setOnClickListener {
            mostrarDialogoRetomar()
        }
    }

    private fun abrirSelector() {
        selectorArchivo.launch(
            arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
    }

    private fun procesarDocumento(uri: Uri) {
        val nombreArchivo = obtenerNombreArchivo(uri)
        tvEstado.text = "Procesando: $nombreArchivo..."
        progressBar.visibility = View.VISIBLE
        deshabilitarBotones(true)

        // Guardar el nombre del archivo para persistencia
        QuizRepository.nombreArchivoOriginal = nombreArchivo

        // Determinar tipo de documento según el modo seleccionado
        val tipoDocForzado = when (modoSeleccionado) {
            "TEST" -> TipoDoc.TEST
            "TEORIA", "AUDIO" -> TipoDoc.TEORIA // Audio se trata como Teoría para limpieza
            else -> null
        }

        gestorDocumentos.clasificarYProcesar(
            uri,
            nombreArchivo,
            onResult = { textoExtraido, tipoDoc ->
                // Usar el tipo forzado si existe, sino el detectado
                val tipoFinal = tipoDocForzado ?: tipoDoc
                manejarResultadoProcesamiento(textoExtraido, tipoFinal)
            },
            onError = { mensajeError ->
                progressBar.visibility = View.GONE
                deshabilitarBotones(false)
                tvEstado.text = "Error: $mensajeError"
                Toast.makeText(this, mensajeError, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun manejarResultadoProcesamiento(texto: String, tipo: TipoDoc) {
        lifecycleScope.launch {
            try {
                QuizRepository.reiniciar() // Limpiar preguntas anteriores

                if (tipo == TipoDoc.TEST) {
                    // CASO TEST: Parsear directamente
                    tvEstado.text = "Analizando estructura del test..."
                    val preguntasGeneradas = ParseadorExamenes.parsearTexto(texto)

                    progressBar.visibility = View.GONE
                    deshabilitarBotones(false)

                    if (preguntasGeneradas.isNotEmpty()) {
                        QuizRepository.preguntas = preguntasGeneradas
                        tvEstado.text = "¡Listo! ${preguntasGeneradas.size} preguntas cargadas."
                        
                        // Ir directamente a PreguntaActivity
                        val intent = Intent(this@MenuActivity, PreguntaActivity::class.java)
                        startActivity(intent)
                    } else {
                        tvEstado.text = "No se pudieron extraer preguntas válidas."
                        Toast.makeText(
                            this@MenuActivity,
                            "El documento no parece contener preguntas válidas.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    // CASO TEORÍA o AUDIO
                    if (modoSeleccionado == "AUDIO") {
                         tvEstado.text = "Preparando lectura..."
                         QuizRepository.textoTeoriaParaAudio = texto
                         
                         // Ir a AudioActivity
                         val intent = Intent(this@MenuActivity, AudioActivity::class.java)
                         // No pasamos URI porque ya tenemos el texto procesado en el Repo
                         startActivity(intent)
                         
                         progressBar.visibility = View.GONE
                         deshabilitarBotones(false)
                    } else {
                        // CASO TEORIA IA
                        solicitarCantidadPreguntas(texto)
                    }
                }

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                deshabilitarBotones(false)
                tvEstado.text = "Error interno: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    private fun solicitarCantidadPreguntas(textoTeoria: String) {
        // Guardar texto completo para posible selección
        textoTeoriaCompleto = textoTeoria
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Generar Preguntas")
        builder.setMessage("¿Cuántas preguntas deseas generar?")

        // Crear EditText para input
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = "Ejemplo: 60"
        input.setText("20") // Valor por defecto

        // Agregar padding al EditText
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        builder.setView(input)

        builder.setPositiveButton("Continuar") { dialog, _ ->
            val cantidadTexto = input.text.toString()
            val cantidad = cantidadTexto.toIntOrNull() ?: 20

            // Validar rango razonable
            val cantidadFinal = when {
                cantidad < 5 -> 5
                cantidad > 200 -> 200
                else -> cantidad
            }

            if (cantidadFinal != cantidad) {
                Toast.makeText(
                    this,
                    "Cantidad ajustada a $cantidadFinal (rango: 5-200)",
                    Toast.LENGTH_SHORT
                ).show()
            }

            cantidadPreguntasSeleccionada = cantidadFinal
            dialog.dismiss()
            
            // Preguntar sobre qué texto generar
            solicitarAlcanceTexto(textoTeoria, cantidadFinal)
        }

        builder.setNegativeButton("Cancelar") { dialog, _ ->
            dialog.dismiss()
            progressBar.visibility = View.GONE
            deshabilitarBotones(false)
            tvEstado.text = "Generación cancelada"
        }

        builder.setCancelable(false)
        builder.show()
    }

    private fun solicitarAlcanceTexto(textoTeoria: String, cantidadPreguntas: Int) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("¿Sobre qué texto?")
        builder.setMessage("Elige el alcance para generar las preguntas:")

        builder.setPositiveButton("📄 Documento completo") { dialog, _ ->
            dialog.dismiss()
            generarPreguntasConIA(textoTeoria, cantidadPreguntas)
        }

        builder.setNegativeButton("✂️ Selección de texto") { dialog, _ ->
            dialog.dismiss()
            abrirSeleccionTexto()
        }

        builder.setNeutralButton("Cancelar") { dialog, _ ->
            dialog.dismiss()
            progressBar.visibility = View.GONE
            deshabilitarBotones(false)
            tvEstado.text = "Generación cancelada"
        }

        builder.setCancelable(false)
        builder.show()
    }

    private fun abrirSeleccionTexto() {
        val intent = Intent(this, TextSelectionActivity::class.java)
        intent.putExtra("TEXTO_COMPLETO", textoTeoriaCompleto)
        intent.putExtra("CANTIDAD_PREGUNTAS", cantidadPreguntasSeleccionada)
        textSelectionLauncher.launch(intent)
    }

    private fun generarPreguntasConIA(textoTeoria: String, cantidadPreguntas: Int) {
        lifecycleScope.launch {
            try {
                tvEstado.text = "Generando $cantidadPreguntas preguntas con IA..."
                progressBar.visibility = View.VISIBLE

                val promptSalida = MotorIA.generarPreguntas(textoTeoria, cantidadPreguntas)
                val preguntas = MotorIA.parsearRespuestaIA(promptSalida)

                progressBar.visibility = View.GONE
                deshabilitarBotones(false)

                if (preguntas.isNotEmpty()) {
                    QuizRepository.preguntas = preguntas
                    tvEstado.text = "¡Listo! ${preguntas.size} preguntas generadas."

                    // Ir directamente a PreguntaActivity
                    val intent = Intent(this@MenuActivity, PreguntaActivity::class.java)
                    startActivity(intent)
                } else {
                    tvEstado.text = "No se pudieron generar preguntas válidas."
                    Toast.makeText(
                        this@MenuActivity,
                        "Error al generar preguntas. Intenta con un texto más largo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                deshabilitarBotones(false)
                tvEstado.text = "Error: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    private fun deshabilitarBotones(deshabilitar: Boolean) {
        findViewById<Button>(R.id.btnCargarTest).isEnabled = !deshabilitar
        findViewById<Button>(R.id.btnCargarTeoria).isEnabled = !deshabilitar
        findViewById<Button>(R.id.btnAudioTeoria).isEnabled = !deshabilitar
        findViewById<Button>(R.id.btnRetomar).isEnabled = !deshabilitar
    }

    private fun obtenerNombreArchivo(uri: Uri): String {
        var nombre = "desconocido"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    nombre = it.getString(index)
                }
            }
        }
        return nombre
    }

    private fun mostrarDialogoRetomar() {
        // Accedemos a las preferencias para buscar los exámenes guardados
        val sharedPreferences = getSharedPreferences("OposicionesPrefs", MODE_PRIVATE)
        val todos = sharedPreferences.all

        // Filtramos las claves que empiezan por "EXAMEN_"
        val listaNombres = todos.keys
            .filter { it.startsWith("EXAMEN_") }
            .map { it.removePrefix("EXAMEN_") }
            .sorted()

        if (listaNombres.isEmpty()) {
            Toast.makeText(this, "No hay exámenes guardados", Toast.LENGTH_SHORT).show()
            return
        }

        // Mostramos la lista en un diálogo
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Selecciona un test para retomar")
        builder.setItems(listaNombres.toTypedArray()) { _, which ->
            val nombreSeleccionado = listaNombres[which]

            // Cargamos el progreso usando el nombre seleccionado
            if (GestorPersistencia.cargarProgreso(this, nombreSeleccionado)) {
                Toast.makeText(this, "Retomando: $nombreSeleccionado", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, PreguntaActivity::class.java))
            } else {
                Toast.makeText(this, "Error al cargar el examen", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }
}