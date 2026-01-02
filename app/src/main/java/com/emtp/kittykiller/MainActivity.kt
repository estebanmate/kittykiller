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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvEstado: TextView
    private val procesador by lazy { ProcesadorDocumentos(this) }
    private var modoApp: String = "TEST"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar librería PDF
        PDFBoxResourceLoader.init(applicationContext)

        tvEstado = findViewById(R.id.tvEstadoAnalisis)

        // Recuperar datos del Intent
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
        val nombreReal = obtenerNombre(uri)
        val nombreVisual = nombreReal.substringBeforeLast(".")

        QuizRepository.nombreArchivoOriginal = nombreVisual

        tvEstado.text = "Analizando: $nombreVisual..."

        lifecycleScope.launch {
            try {
                // Procesar archivo (extraer texto)
                val (_, texto) = procesador.procesarArchivo(uri, nombreReal) { progreso ->
                    runOnUiThread { tvEstado.text = progreso }
                }

                if (texto.startsWith("Formato no soportado") || texto.startsWith("Error") || texto.isBlank()) {
                    val mensaje = if (texto.isBlank()) "Documento vacío o ilegible." else texto
                    Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                // LÓGICA SEGÚN MODO
                when (modoApp) {
                    "TEST" -> procesarModoTest(texto)
                    "AUDIO" -> procesarModoAudio(texto, nombreVisual)
                    else -> mostrarDialogoCantidad(texto) // MODO TEORÍA (IA)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // --- MODO TEST ---
    private suspend fun procesarModoTest(texto: String) {
        tvEstado.text = "Analizando estructura de preguntas..."

        // Limpieza OCR básica
        val textoLimpio = texto.replace(Regex("(?<![.:])\\n"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(\\d+)[.|-]\\s"), "\n$1. ")
            .replace(Regex("([a-d])[.|)]\\s"), "\n$1) ")

        // Intentar parsear
        var preguntas = ParseadorExamenes.parsearTexto(textoLimpio)

        // Reintento con texto raw si falla
        if (preguntas.isEmpty()) {
            Log.d("MainActivity", "Parseo limpio falló, intentando raw...")
            preguntas = ParseadorExamenes.parsearTexto(texto)
        }

        if (preguntas.isNotEmpty()) {
            tvEstado.text = "¡Éxito! ${preguntas.size} preguntas encontradas."
            delay(800)
            iniciarExamen(preguntas)
        } else {
            tvEstado.text = "No se encontraron preguntas."
            Toast.makeText(this, "Formato de test no reconocido.", Toast.LENGTH_LONG).show()
            delay(2000)
            finish()
        }
    }

    // --- MODO AUDIO ---
    private fun procesarModoAudio(texto: String, nombreArchivo: String) {
        tvEstado.text = "Preparando audio..."

        if (texto.length > 50) {
            // Guardamos el texto en el Repositorio (asegúrate de haber añadido la variable textoTeoriaParaAudio en QuizRepository)
            // Si no has añadido la variable aún, añádela a QuizRepository.kt: var textoTeoriaParaAudio: String = ""
            try {
                // Usamos reflexión o acceso directo si ya añadiste la variable.
                // Asumimos que la variable existe como 'textoTeoriaParaAudio'.
                // Si no existe, puedes usar una variable estática temporal aquí o pasarla por archivo.
                // Aquí asumimos que seguiste el paso anterior de actualizar QuizRepository.

                // Opción A: Si actualizaste QuizRepository
                // QuizRepository.textoTeoriaParaAudio = texto

                // Opción B (Provisional si no actualizaste el Repo): Usar SharedPreferences temporalmente para pasar el texto
                val prefs = getSharedPreferences("AudioTemp", MODE_PRIVATE)
                prefs.edit().putString("TEXTO_AUDIO", texto).apply()

                val intent = Intent(this, AudioActivity::class.java)
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al preparar audio", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            Toast.makeText(this, "El texto es muy corto para leer.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // --- MODO TEORÍA (IA) ---
    private fun mostrarDialogoCantidad(textoTeoria: String) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText("20")
        input.hint = "Número de preguntas"

        AlertDialog.Builder(this)
            .setTitle("Generador IA")
            .setMessage("¿Cuántas preguntas quieres generar?")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Generar") { _, _ ->
                val cantidadSolicitada = input.text.toString().toIntOrNull() ?: 20
                val cantidadFinal = if (cantidadSolicitada > 0) cantidadSolicitada else 20
                generarPreguntasIA(textoTeoria, cantidadFinal)
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .show()
    }

    private fun generarPreguntasIA(textoCompleto: String, cantidadTotal: Int) {
        tvEstado.text = "Generando preguntas con IA..."

        lifecycleScope.launch {
            val preguntasAcumuladas = mutableListOf<Pregunta>()
            val tamanoLote = 4
            val longitudTotal = textoCompleto.length
            val avancePorRonda = 1500
            var cursorTexto = 0
            var preguntasRestantes = cantidadTotal
            var ronda = 1

            while (preguntasRestantes > 0) {
                val finBloque = minOf(cursorTexto + 2500, longitudTotal)
                val textoRonda = if (cursorTexto >= longitudTotal) {
                    cursorTexto = 0
                    textoCompleto.take(2500)
                } else {
                    textoCompleto.substring(cursorTexto, finBloque)
                }

                val pedirAhora =
                    if (preguntasRestantes > tamanoLote) tamanoLote else preguntasRestantes

                runOnUiThread {
                    tvEstado.text =
                        "Generando lote $ronda... (${preguntasAcumuladas.size}/$cantidadTotal)"
                }

                val respuestaRaw = MotorIA.generarPreguntas(textoRonda, pedirAhora)
                val nuevasPreguntas = ParseadorExamenes.parsearSalidaIA(respuestaRaw)

                if (nuevasPreguntas.isNotEmpty()) {
                    val validas =
                        nuevasPreguntas.filter { it.opcionA.isNotBlank() && it.opcionB.isNotBlank() }
                    preguntasAcumuladas.addAll(validas)
                    preguntasRestantes -= validas.size
                    cursorTexto += avancePorRonda
                } else {
                    cursorTexto += 500
                    preguntasRestantes--
                }
                ronda++
            }

            if (preguntasAcumuladas.isNotEmpty()) {
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
        QuizRepository.preguntas = preguntas
        QuizRepository.reiniciar()

        val intent = Intent(this, PreguntaActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
        startActivity(intent)
        finish()
    }

    private fun obtenerNombre(uri: Uri): String {
        var nombre = "documento_desconocido.pdf"
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
}