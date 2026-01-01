package com.emtp.kittykiller

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvEstado: TextView
    private val procesador by lazy { ProcesadorDocumentos(this) }
    private var modoApp: String = "TEST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar librería PDF (por si acaso)
        PDFBoxResourceLoader.init(applicationContext)

        tvEstado = findViewById(R.id.tvEstadoAnalisis)

        // Recuperar datos del Intent (enviados desde el Menú)
        val uri = intent.data
        modoApp = intent.getStringExtra("MODO") ?: "TEST"

        if (uri != null) {
            procesarDocumento(uri)
        } else {
            Toast.makeText(this, "Error al recibir archivo", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun procesarDocumento(uri: Uri) {
        // 1. OBTENER NOMBRES
        // Nombre REAL (con extensión) para que el sistema sepa cómo leerlo (ej: "Examen.pdf")
        val nombreReal = obtenerNombre(uri)
        // Nombre VISUAL (sin extensión) para mostrar al usuario (ej: "Examen")
        val nombreVisual = nombreReal.substringBeforeLast(".")

        QuizRepository.nombreArchivoOriginal = nombreVisual

        tvEstado.text = "Analizando: $nombreVisual..."

        lifecycleScope.launch {
            try {
                // 2. PROCESAMIENTO DEL ARCHIVO
                // Pasamos 'nombreReal' para que detecte si es PDF, DOC, etc.
                val (_, texto) = procesador.procesarArchivo(
                    uri,
                    nombreReal
                ) { progreso ->
                    // Callback de progreso: Actualiza la UI (ej: "Escaneando pág 5...")
                    runOnUiThread { tvEstado.text = progreso }
                }

                // 3. VALIDACIÓN DE ERRORES
                if (texto.startsWith("Formato no soportado") || texto.startsWith("Error") || texto.isBlank()) {
                    val mensaje =
                        if (texto.isBlank()) "El documento parece vacío o ilegible." else texto
                    Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                // 4. LÓGICA SEGÚN MODO (TEST vs TEORÍA)
                if (modoApp == "TEST") {
                    tvEstado.text = "Analizando estructura de preguntas..."

                    // --- LIMPIEZA CRÍTICA PARA TEST (OCR) ---
                    // Muchos PDFs escaneados cortan las líneas: "pregun\nta".
                    // Esto une las líneas que no terminan en punto (.) o dos puntos (:)
                    val textoLimpio = texto.replace(Regex("(?<![.:])\\n"), " ")
                        .replace(Regex("\\s+"), " ") // Elimina dobles espacios
                        // Aseguramos que "1." y "a)" tengan un salto de línea antes
                        .replace(Regex("(\\d+)[.|-]\\s"), "\n$1. ")
                        .replace(Regex("([a-d])[.|)]\\s"), "\n$1) ")

                    // Intentamos parsear con el texto limpio
                    var preguntas = ParseadorExamenes.parsearTexto(textoLimpio)

                    // Si falla, intento de rescate con el texto original (por si acaso)
                    if (preguntas.isEmpty()) {
                        Log.d("MainActivity", "Parseo limpio falló, intentando raw...")
                        preguntas = ParseadorExamenes.parsearTexto(texto)
                    }

                    if (preguntas.isNotEmpty()) {
                        tvEstado.text = "¡Éxito! ${preguntas.size} preguntas encontradas."
                        // Pequeña pausa para que el usuario lea el mensaje de éxito
                        kotlinx.coroutines.delay(800)
                        iniciarExamen(preguntas)
                    } else {
                        tvEstado.text = "No se encontraron preguntas."
                        Toast.makeText(
                            this@MainActivity,
                            "El documento no tiene un formato de test reconocible (1. Pregunta... a)...)",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } else {
                    // MODO TEORÍA (IA)
                    // El texto ya viene "recortado" desde el Procesador (sin índice/portada)
                    mostrarDialogoCantidad(texto)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@MainActivity,
                    "Error inesperado: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    // --- FUNCIÓN AUXILIAR OBLIGATORIA ---
    // Debe devolver el nombre CON extensión (ej: "Tema.pdf")
    private fun obtenerNombre(uri: Uri): String {
        var nombre = "documento_desconocido.pdf" // Default con extensión segura
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) {
                    nombre = cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return nombre
    }

    private fun mostrarDialogoCantidad(textoTeoria: String) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        // Ponemos 20 por defecto en la caja de texto
        input.setText("20")
        input.hint = "Número de preguntas"

        AlertDialog.Builder(this)
            .setTitle("Generador IA")
            .setMessage("¿Cuántas preguntas quieres generar?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Generar") { _, _ ->
                // Si el usuario lo borra o pone 0, usamos 20 por defecto
                val cantidadSolicitada = input.text.toString().toIntOrNull() ?: 20

                // Validamos que sea al menos 1
                val cantidadFinal = if (cantidadSolicitada > 0) cantidadSolicitada else 20

                generarPreguntasIA(textoTeoria, cantidadFinal)
            }
            .setNegativeButton("Cancelar") { _, _ ->
                finish()
            }
            .show()
    }

    private fun generarPreguntasIA(textoCompleto: String, cantidadTotal: Int) {
        tvEstado.text = "Planificando generación de $cantidadTotal preguntas..."

        lifecycleScope.launch {
            val preguntasAcumuladas = mutableListOf<Pregunta>()
            val tamanoLote = 4

            // Calculamos longitud del texto y tamaño de la ventana
            val longitudTotal = textoCompleto.length
            // Si el texto es corto, no podemos avanzar mucho. Si es largo, avanzamos 1500 chars por ronda.
            val avancePorRonda = 1500
            var cursorTexto = 0

            var preguntasRestantes = cantidadTotal
            var ronda = 1

            while (preguntasRestantes > 0) {
                // 1. Calcular el trozo de texto para esta ronda (Ventana Deslizante)
                // Cogemos un bloque de 2500 caracteres a partir del cursor
                val finBloque = minOf(cursorTexto + 2500, longitudTotal)

                // Si llegamos al final del documento, volvemos al principio (Loop)
                // para seguir sacando preguntas si el usuario pidió muchas.
                val textoRonda = if (cursorTexto >= longitudTotal) {
                    cursorTexto = 0 // Reiniciar
                    textoCompleto.take(2500)
                } else {
                    textoCompleto.substring(cursorTexto, finBloque)
                }

                // 2. Pedir a la IA
                val pedirAhora =
                    if (preguntasRestantes > tamanoLote) tamanoLote else preguntasRestantes

                runOnUiThread {
                    tvEstado.text =
                        "Generando lote $ronda... (${preguntasAcumuladas.size}/$cantidadTotal)"
                }

                // Pasamos el TROZO DE ESTA RONDA, no el texto del principio siempre
                val respuestaRaw = MotorIA.generarPreguntas(textoRonda, pedirAhora)
                val nuevasPreguntas = ParseadorExamenes.parsearSalidaIA(respuestaRaw)

                if (nuevasPreguntas.isNotEmpty()) {
                    // Validar que no tengan opciones vacías antes de añadir
                    val preguntasValidas = nuevasPreguntas.filter {
                        it.opcionA.isNotBlank() && it.opcionB.isNotBlank()
                    }

                    preguntasAcumuladas.addAll(preguntasValidas)
                    preguntasRestantes -= preguntasValidas.size

                    // AVANZAMOS EL CURSOR para la siguiente ronda
                    cursorTexto += avancePorRonda
                } else {
                    // Si falla, avanzamos un poco por si era un trozo de texto malo
                    cursorTexto += 500
                    // Freno de emergencia para no buclear infinito
                    preguntasRestantes--
                }
                ronda++
            }

            if (preguntasAcumuladas.isNotEmpty()) {
                runOnUiThread { tvEstado.text = "¡Finalizado! Preparando test..." }
                iniciarExamen(preguntasAcumuladas)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "La IA no pudo generar preguntas.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun iniciarExamen(preguntas: List<Pregunta>) {
        if (preguntas.isEmpty()) {
            Toast.makeText(this, "No se han encontrado preguntas válidas.", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        // Cargar en repositorio
        QuizRepository.preguntas = preguntas
        QuizRepository.reiniciar()

        // IR AL TEST DIRECTAMENTE
        val intent = Intent(this, PreguntaActivity::class.java)
        // Limpiamos flags para que al dar atrás desde el test vuelva al menú, no aquí
        intent.flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
        startActivity(intent)
        finish() // Cerramos la pantalla de "Analizando"
    }
}