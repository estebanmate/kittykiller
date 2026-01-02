package com.emtp.kittykiller

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MotorIA {
    private var llmInference: LlmInference? = null

    suspend fun inicializar(context: Context) {
        withContext(Dispatchers.IO) {
            if (llmInference == null) {
                try {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath("/data/local/tmp/gemma-2b-it-cpu-int4.bin") // Ajusta ruta si es necesario
                        .setMaxTokens(1024)
                        .build()
                    llmInference = LlmInference.createFromOptions(context, options)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun generarPreguntas(textoTeoria: String, cantidad: Int): String {
        return withContext(Dispatchers.IO) {
            if (llmInference == null) return@withContext "Error: IA no inicializada."

            // Limpieza para ahorrar tokens
            val textoLimpio = textoTeoria.replace(Regex("\\s+"), " ").take(2500)

            // Prompt One-Shot optimizado para formato con etiquetas estrictas
            val prompt = """
<start_of_turn>user
Eres un profesor. Crea exactamente $cantidad preguntas de test sobre este texto.

TEXTO: "$textoLimpio"

FORMATO OBLIGATORIO:
### PREGUNTA ###
ENUNCIADO: [Pregunta]
OPCION_A: [Respuesta A]
OPCION_B: [Respuesta B]
OPCION_C: [Respuesta C]
OPCION_D: [Respuesta D]
SOLUCION: [solo la letra a, b, c o d]

Genera las preguntas ahora:
<end_of_turn>
<start_of_turn>model
""".trimIndent()

            try {
                llmInference?.generateResponse(prompt) ?: "Error IA"
            } catch (e: Exception) {
                "Excepción IA: ${e.message}"
            }
        }
    }
}