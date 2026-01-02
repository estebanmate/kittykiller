package com.emtp.kittykiller

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

object MotorIA {
    private var llmInference: LlmInference? = null

    // Configuración para división de texto
    private const val TAMANO_CHUNK_CARACTERES = 3500 // ~1000 tokens, seguro para móviles
    private const val SOLAPE_CHUNK = 200 // Solape para no cortar frases a medias

    suspend fun inicializar(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            if (llmInference != null) return@withContext true
            try {
                // Buscamos el modelo en el almacenamiento interno (descargado previamente)
                val modelFile = File(context.filesDir, "gemma-2b-it-cpu-int4.bin")
                if (!modelFile.exists()) return@withContext false

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024) // Tokens para la RESPUESTA generada
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun generarPreguntas(textoTeoria: String, cantidadTotal: Int): String {
        return withContext(Dispatchers.IO) {
            if (llmInference == null) return@withContext "Error: IA no inicializada."

            // Limpieza básica de espacios múltiples
            val textoLimpio = textoTeoria.replace(Regex("\\s+"), " ")
            val longitud = textoLimpio.length

            // Si es corto, procesar de una vez
            if (longitud < TAMANO_CHUNK_CARACTERES) {
                return@withContext llamarGemini(textoLimpio, cantidadTotal)
            }

            // Si es largo, dividir en bloques (Chunks)
            val numChunks = ceil(longitud.toDouble() / TAMANO_CHUNK_CARACTERES).toInt()
            // Repartimos las preguntas equitativamente entre los bloques
            val preguntasPorChunk = maxOf(1, cantidadTotal / numChunks)

            val resultadoFinal = StringBuilder()
            Log.d("MotorIA", "Texto largo ($longitud chars). Procesando en $numChunks bloques.")

            var cursor = 0
            var chunksProcesados = 0

            while (cursor < longitud && chunksProcesados < numChunks) {
                val fin = min(cursor + TAMANO_CHUNK_CARACTERES, longitud)
                val textoChunk = textoLimpio.substring(cursor, fin)

                // Generar preguntas para este fragmento
                val respuestaParcial = llamarGemini(textoChunk, preguntasPorChunk)
                resultadoFinal.append(respuestaParcial).append("\n\n")

                // Avanzar cursor restando el solape
                cursor += (TAMANO_CHUNK_CARACTERES - SOLAPE_CHUNK)
                chunksProcesados++
            }

            resultadoFinal.toString()
        }
    }

    private fun llamarGemini(texto: String, cantidad: Int): String {
        // Prompt optimizado con One-Shot Learning implícito en las instrucciones
        val prompt = """
<start_of_turn>user
Eres un profesor experto. Genera exactamente $cantidad preguntas de examen tipo test basadas EXCLUSIVAMENTE en el siguiente texto.

TEXTO: "$texto"

FORMATO OBLIGATORIO (Usa estas etiquetas exactas):
### PREGUNTA ###
ENUNCIADO: [Pregunta]
OPCION_A: [Respuesta]
OPCION_B: [Respuesta]
OPCION_C: [Respuesta]
OPCION_D: [Respuesta]
SOLUCION: [solo la letra a, b, c o d]

Genera las preguntas ahora:
<end_of_turn>
<start_of_turn>model
""".trimIndent()

        return try {
            llmInference?.generateResponse(prompt) ?: ""
        } catch (e: Exception) {
            Log.e("MotorIA", "Error en chunk: ${e.message}")
            ""
        }
    }
}