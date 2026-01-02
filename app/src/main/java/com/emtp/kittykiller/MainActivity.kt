package com.emtp.kittykiller

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
import com.emtp.kittykiller.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var procesador: ProcesadorDocumentos

    // Default mode if none provided
    private var modoApp = "TEORIA"

    // Launcher for file picker if accessed directly (less likely if MenuActivity works)
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { procesarDocumento(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Using the correct layout binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        procesador = ProcesadorDocumentos(this)
        QuizRepository.inicializar(this)

        // Ensure IA Engine is loading
        lifecycleScope.launch {
            MotorIA.inicializar(this@MainActivity)
        }

        // Handle Intent from MenuActivity
        if (intent != null && intent.data != null) {
            val uri = intent.data
            val modo = intent.getStringExtra("MODO")
            if (modo != null) {
                modoApp = modo
            }
            if (uri != null) {
                procesarDocumento(uri)
            }
        } else {
            // Fallback if started without data (e.g. testing)
            binding.tvEstadoAnalisis.text = "Seleccione un archivo..."
            // Launch picker immediately
             filePickerLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/msword"
                )
            )
        }
    }

    // Processing Logic
    private fun procesarDocumento(uri: Uri) {
        val nombreReal = obtenerNombre(uri)
        val nombreVisual = nombreReal.substringBeforeLast(".")
        QuizRepository.nombreArchivoOriginal = nombreVisual

        binding.tvEstadoAnalisis.text = "Leyendo archivo: $nombreVisual..."

        lifecycleScope.launch {
            // 1. Process File
            val (_, texto) = procesador.procesarArchivo(uri, nombreReal) { msg ->
                runOnUiThread { binding.tvEstadoAnalisis.text = msg }
            }

            // Validation
            if (texto.length < 20 || texto.startsWith("Error")) {
                Toast.makeText(
                    this@MainActivity,
                    "Error leyendo documento o archivo vacío.",
                    Toast.LENGTH_SHORT
                ).show()
                finish() // Exit if failed
                return@launch
            }

            // 2. Dispatch based on mode
            when (modoApp) {
                "TEST" -> procesarModoTest(texto)
                "AUDIO" -> abrirLectorAudio(texto)
                else -> procesarModoTeoria(texto)
            }
        }
    }

    // MODE 1: TEST (Existing PDF)
    private suspend fun procesarModoTest(texto: String) {
        binding.tvEstadoAnalisis.text = "Analizando estructura..."

        val textoLimpio = texto.replace(Regex("(?<![.:])\\n"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(\\d+)[.|-]\\s"), "\n$1. ")
            .replace(Regex("([a-d])[.|)]\\s"), "\n$1) ")

        var preguntas = ParseadorExamenes.parsearTexto(textoLimpio)

        if (preguntas.isEmpty()) {
            Log.d("MainActivity", "Reintentando con texto raw...")
            preguntas = ParseadorExamenes.parsearTexto(texto)
        }

        if (preguntas.isNotEmpty()) {
            binding.tvEstadoAnalisis.text = "¡Éxito! ${preguntas.size} preguntas encontradas."
            delay(800)
            iniciarExamen(preguntas)
        } else {
            binding.tvEstadoAnalisis.text = "No se detectaron preguntas válidas."
            Toast.makeText(this, "El formato del PDF no parece un test válido.", Toast.LENGTH_LONG)
                .show()
            // Optionally finish or allow retry
        }
    }

    // MODE 2: THEORY (IA Generation)
    private fun procesarModoTeoria(texto: String) {
        // We need to ask for quantity. Since we are in a loading screen, we show a dialog.
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
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .show()
    }

    private fun generarPreguntasIA(textoCompleto: String, cantidadTotalSolicitada: Int) {
        binding.tvEstadoAnalisis.text = "Planificando generación..."

        lifecycleScope.launch {
            val preguntasAcumuladas = mutableListOf<Pregunta>()
            val tamanoLote = 4
            val longitudTotal = textoCompleto.length
            val avance = 1500
            var cursor = 0

            var preguntasFaltantes = cantidadTotalSolicitada
            var intentosSinExito = 0

            while (preguntasFaltantes > 0 && intentosSinExito < 5) {
                val fin = min(cursor + 2500, longitudTotal)
                val textoRonda = if (cursor >= longitudTotal) {
                    cursor = 0
                    textoCompleto.take(2500)
                } else {
                    textoCompleto.substring(cursor, fin)
                }

                val pedirAhora =
                    if (preguntasFaltantes > tamanoLote) tamanoLote else preguntasFaltantes

                runOnUiThread {
                    binding.tvEstadoAnalisis.text =
                        "Generando... (${preguntasAcumuladas.size} / $cantidadTotalSolicitada)"
                }

                val respuestaRaw = MotorIA.generarPreguntas(textoRonda, pedirAhora)
                val nuevas = ParseadorExamenes.parsearSalidaIA(respuestaRaw)
                val validas = nuevas.filter { it.opcionA.isNotBlank() && it.enunciado.isNotBlank() }

                if (validas.isNotEmpty()) {
                    preguntasAcumuladas.addAll(validas)
                    preguntasFaltantes -= validas.size
                    cursor += avance
                    intentosSinExito = 0
                } else {
                    cursor += 500
                    intentosSinExito++
                }
            }

            if (preguntasAcumuladas.isNotEmpty()) {
                binding.tvEstadoAnalisis.text = "¡Finalizado! Total: ${preguntasAcumuladas.size}"
                iniciarExamen(preguntasAcumuladas.take(cantidadTotalSolicitada))
            } else {
                binding.tvEstadoAnalisis.text = "Error: La IA no pudo generar preguntas."
                Toast.makeText(this@MainActivity, "La IA no pudo generar preguntas.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // MODE 3: AUDIO
    private fun abrirLectorAudio(texto: String) {
        if (texto.length < 50) {
            Toast.makeText(this, "El texto es demasiado corto.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        QuizRepository.textoTeoriaActual = texto
        val intent = Intent(this, AudioActivity::class.java)
        startActivity(intent)
        finish() // Close processing activity
    }

    private fun iniciarExamen(preguntas: List<Pregunta>) {
        QuizRepository.preguntasActuales = preguntas
        // Reset progress when starting new
        QuizRepository.reiniciar() // This now resets indices and stats

        val intent = Intent(this, PreguntaActivity::class.java)
        startActivity(intent)
        finish() // Close processing activity
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
