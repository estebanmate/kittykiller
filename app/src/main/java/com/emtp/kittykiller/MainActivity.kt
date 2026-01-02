package com.emtp.kittykiller

// --- IMPORTACIONES NECESARIAS (Copia todo este bloque) ---
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emtp.kittykiller.databinding.ActivityMenuBinding // <--- OJO AQUÍ: Debe coincidir con tu XML
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min // Para minOf

// ---------------------------------------------------------

class MainActivity : AppCompatActivity() {

    // Usamos el Binding del menú. Si tu XML es activity_main.xml, cambia esto a ActivityMainBinding
    private lateinit var binding: ActivityMenuBinding
    private lateinit var procesador: ProcesadorDocumentos

    // Modos: "TEORIA", "TEST", "AUDIO"
    private var modoApp = "TEORIA"

    // Lanzador para seleccionar archivos (PDF o Word)
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { procesarDocumento(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inflar la vista usando ViewBinding
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar clases auxiliares
        procesador = ProcesadorDocumentos(this)
        QuizRepository.inicializar(this)

        // Cargar motor IA en segundo plano
        lifecycleScope.launch {
            MotorIA.inicializar(this@MainActivity)
        }

        // --- CONFIGURACIÓN DE LISTENERS (BOTONES) ---

        // Botón Cargar Archivo
        binding.btnSeleccionarArchivo.setOnClickListener {
            // Permitir PDF y Word (docx/doc)
            filePickerLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/msword"
                )
            )
        }

        // Cambio de Modo (RadioGroup)
        binding.radioGroupModo.setOnCheckedChangeListener { _, checkedId ->
            modoApp = when (checkedId) {
                binding.rbModoTest.id -> "TEST"
                binding.rbModoAudio.id -> "AUDIO"
                else -> "TEORIA"
            }
            actualizarUIModo()
        }

        // Botón Retomar
        binding.btnRetomar.setOnClickListener {
            mostrarListaTestsGuardados()
        }

        // Configurar UI inicial
        actualizarUIModo()
    }

    override fun onResume() {
        super.onResume()
        // Mostrar botón de retomar solo si hay tests guardados
        val hayPendientes = QuizRepository.obtenerSesionesGuardadas().isNotEmpty()
        binding.btnRetomar.visibility = if (hayPendientes) View.VISIBLE else View.GONE
    }

    // Actualiza los textos según el modo seleccionado
    private fun actualizarUIModo() {
        when (modoApp) {
            "TEST" -> {
                binding.tvInstrucciones.text = "Modo Test: Sube un PDF de examen para resolverlo."
                binding.btnSeleccionarArchivo.text = "CARGAR EXAMEN"
            }

            "AUDIO" -> {
                binding.tvInstrucciones.text = "Modo Audio: Escucha tu temario."
                binding.btnSeleccionarArchivo.text = "ESCUCHAR TEMARIO"
            }

            else -> { // TEORIA
                binding.tvInstrucciones.text = "Modo IA: Generar preguntas desde un tema."
                binding.btnSeleccionarArchivo.text = "GENERAR PREGUNTAS"
            }
        }
    }

    // --- LÓGICA DE PROCESAMIENTO DE DOCUMENTOS ---

    private fun procesarDocumento(uri: Uri) {
        val nombreReal = obtenerNombre(uri)
        // Guardamos el nombre sin extensión para mostrarlo luego
        val nombreVisual = nombreReal.substringBeforeLast(".")
        QuizRepository.nombreArchivoOriginal = nombreVisual

        binding.tvEstado.text = "Leyendo archivo: $nombreVisual..."

        lifecycleScope.launch {
            // 1. Procesar el archivo (Extraer texto limpio)
            val (_, texto) = procesador.procesarArchivo(uri, nombreReal) { msg ->
                runOnUiThread { binding.tvEstado.text = msg }
            }

            // Validación básica
            if (texto.length < 20 || texto.startsWith("Error")) {
                Toast.makeText(
                    this@MainActivity,
                    "Error leyendo documento o archivo vacío.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            // 2. Derivar según el modo elegido
            when (modoApp) {
                "TEST" -> procesarModoTest(texto)
                "AUDIO" -> abrirLectorAudio(texto)
                else -> procesarModoTeoria(texto)
            }
        }
    }

    // --- MODO 1: TEST (PDF EXISTENTE) ---
    private suspend fun procesarModoTest(texto: String) {
        binding.tvEstado.text = "Analizando estructura..."

        // Limpieza OCR: Unir líneas rotas ("pregun- \n ta")
        val textoLimpio = texto.replace(Regex("(?<![.:])\\n"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(\\d+)[.|-]\\s"), "\n$1. ") // Fuerza salto en "1. "
            .replace(Regex("([a-d])[.|)]\\s"), "\n$1) ") // Fuerza salto en "a) "

        // Parseo
        var preguntas = ParseadorExamenes.parsearTexto(textoLimpio)

        // Fallback: Si la limpieza rompió algo, prueba con el texto original
        if (preguntas.isEmpty()) {
            Log.d("MainActivity", "Reintentando con texto raw...")
            preguntas = ParseadorExamenes.parsearTexto(texto)
        }

        if (preguntas.isNotEmpty()) {
            binding.tvEstado.text = "¡Éxito! ${preguntas.size} preguntas encontradas."
            delay(800) // Pequeña pausa visual
            iniciarExamen(preguntas)
        } else {
            binding.tvEstado.text = "No se detectaron preguntas válidas."
            Toast.makeText(this, "El formato del PDF no parece un test válido.", Toast.LENGTH_LONG)
                .show()
        }
    }

    // --- MODO 2: TEORÍA (GENERACIÓN IA) ---
    private fun procesarModoTeoria(texto: String) {
        // Diálogo para pedir cantidad
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText("20")

        AlertDialog.Builder(this)
            .setTitle("Configurar IA")
            .setMessage("¿Cuántas preguntas quieres generar?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Generar") { _, _ ->
                val cantidad = input.text.toString().toIntOrNull() ?: 20
                generarPreguntasIA(texto, cantidad)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun generarPreguntasIA(textoCompleto: String, cantidadTotalSolicitada: Int) {
        binding.tvEstado.text = "Planificando generación..."

        lifecycleScope.launch {
            val preguntasAcumuladas = mutableListOf<Pregunta>()
            val tamanoLote = 4 // Gemma 2B es estable generando bloques de 4

            val longitudTotal = textoCompleto.length
            val avance = 1500 // Ventana deslizante para no repetir texto
            var cursor = 0

            var preguntasFaltantes = cantidadTotalSolicitada
            var intentosSinExito = 0

            // BUCLE: Sigue pidiendo hasta completar la cantidad deseada
            while (preguntasFaltantes > 0 && intentosSinExito < 5) {

                // 1. Seleccionar trozo de texto
                val fin = min(cursor + 2500, longitudTotal)
                val textoRonda = if (cursor >= longitudTotal) {
                    cursor = 0 // Reiniciar texto si se acaba (Loop)
                    textoCompleto.take(2500)
                } else {
                    textoCompleto.substring(cursor, fin)
                }

                // 2. Calcular lote actual
                val pedirAhora =
                    if (preguntasFaltantes > tamanoLote) tamanoLote else preguntasFaltantes

                runOnUiThread {
                    binding.tvEstado.text =
                        "Generando... (${preguntasAcumuladas.size} / $cantidadTotalSolicitada)"
                }

                // 3. Llamada a IA
                val respuestaRaw = MotorIA.generarPreguntas(textoRonda, pedirAhora)

                // 4. Parseo de respuesta IA
                val nuevas = ParseadorExamenes.parsearSalidaIA(respuestaRaw)

                // Filtramos preguntas mal formadas
                val validas = nuevas.filter { it.opcionA.isNotBlank() && it.enunciado.isNotBlank() }

                if (validas.isNotEmpty()) {
                    preguntasAcumuladas.addAll(validas)
                    preguntasFaltantes -= validas.size

                    // Avanzamos cursor solo si hubo éxito
                    cursor += avance
                    intentosSinExito = 0
                } else {
                    // Si falló, avanzamos un poco para salir de zona conflictiva
                    cursor += 500
                    intentosSinExito++
                }
            }

            if (preguntasAcumuladas.isNotEmpty()) {
                binding.tvEstado.text = "¡Finalizado! Total: ${preguntasAcumuladas.size}"
                // Recortamos si nos pasamos de la cantidad pedida
                iniciarExamen(preguntasAcumuladas.take(cantidadTotalSolicitada))
            } else {
                binding.tvEstado.text = "Error: La IA no pudo generar preguntas."
            }
        }
    }

    // --- MODO 3: AUDIO (LECTOR TTS) ---
    private fun abrirLectorAudio(texto: String) {
        if (texto.length < 50) {
            Toast.makeText(this, "El texto es demasiado corto.", Toast.LENGTH_SHORT).show()
            return
        }
        // Guardamos texto en Singleton y abrimos LectorActivity
        QuizRepository.textoTeoriaActual = texto
        val intent = Intent(this, LectorActivity::class.java)
        startActivity(intent)
    }

    // --- UTILIDADES ---

    private fun iniciarExamen(preguntas: List<Pregunta>) {
        QuizRepository.preguntasActuales = preguntas

        val intent = Intent(this, PreguntaActivity::class.java).apply {
            putExtra("INDICE_INICIAL", 0)
            putExtra("ACIERTOS", 0)
            putExtra("FALLOS", 0)
            putExtra("NOMBRE_TEST", QuizRepository.nombreArchivoOriginal)
        }
        startActivity(intent)
    }

    private fun mostrarListaTestsGuardados() {
        val sesiones = QuizRepository.obtenerSesionesGuardadas()

        if (sesiones.isEmpty()) {
            Toast.makeText(this, "No hay tests guardados.", Toast.LENGTH_SHORT).show()
            return
        }

        val nombres = sesiones.map {
            "${it.nombreArchivo} (${it.indice + 1}/${it.preguntas.size})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Retomar Test")
            .setItems(nombres) { _, i ->
                val s = sesiones[i]
                // Restaurar estado
                QuizRepository.preguntasActuales = s.preguntas
                QuizRepository.nombreArchivoOriginal = s.nombreArchivo

                val intent = Intent(this, PreguntaActivity::class.java).apply {
                    putExtra("INDICE_INICIAL", s.indice)
                    putExtra("ACIERTOS", s.aciertos)
                    putExtra("FALLOS", s.fallos)
                    putExtra("NOMBRE_TEST", s.nombreArchivo)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun obtenerNombre(uri: Uri): String {
        var res = "documento.pdf"
        try {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) res = it.getString(index)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return res
    }
}