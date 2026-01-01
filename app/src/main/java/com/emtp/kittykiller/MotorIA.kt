package com.emtp.kittykiller

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MotorIA {

    private var llmInference: LlmInference? = null
    var estaCargado = false

    // Se llama desde la SplashActivity
    suspend fun inicializar(context: Context): Boolean {
        if (estaCargado && llmInference != null) return true // Ya estaba listo

        return withContext(Dispatchers.IO) {
            val modelFile = GestorDescargas.obtenerArchivoModelo(context)

            if (!modelFile.exists() || modelFile.length() == 0L) {
                return@withContext false
            }

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .setTemperature(0.7f)
                .setTopK(40)
                .build()

            try {
                // Carga pesada en RAM
                llmInference = LlmInference.createFromOptions(context, options)
                estaCargado = true
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun generarPreguntas(textoTeoria: String, cantidad: Int): String {
        return withContext(Dispatchers.IO) {
            if (llmInference == null) return@withContext "Error: IA no inicializada."

            // 1. ESTRATEGIA DE "SALTO DE ÍNDICE"
            // Si el texto empieza por "ÍNDICE", nos saltamos los primeros 1500 caracteres
            // (que suelen ser la tabla de contenidos) para llegar al temario real.
            var inicioTexto = 0
            if (textoTeoria.trim().startsWith("ÍNDICE", ignoreCase = true) ||
                textoTeoria.trim().startsWith("INDICE", ignoreCase = true)
            ) {
                inicioTexto = 1500
            }

            minOf(inicioTexto + 2500, textoTeoria.length)
            val contextoRecortado = textoTeoria.replace(Regex("\\s+"), " ")

            // 2. PROMPT REFORZADO (ONE-SHOT)
            // Le obligamos a generar opciones inventadas si no las sabe, pero respetando el formato.
            val prompt = """
<start_of_turn>user
Eres un profesor de sanidad. Basándote en el siguiente texto, crea $cantidad preguntas de examen tipo test.

IMPORTANTE:
- Cada pregunta DEBE tener 4 opciones (a, b, c, d).
- Si el texto no es suficiente, invéntate opciones plausibles relacionadas con el tema.
- NO hagas listas ni resúmenes. Solo preguntas tipo test completas.

TEXTO:
"$contextoRecortado"

FORMATO OBLIGATORIO (Úsalo para cada pregunta):
1. [Enunciado de la pregunta]
a) [Opción A]
b) [Opción B]
c) [Opción C]
d) [Opción D]
Solución: [letra]

Genera las preguntas ahora:
<end_of_turn>
<start_of_turn>model
""".trimIndent()

            try {
                val respuesta = llmInference?.generateResponse(prompt) ?: "Error IA"
                android.util.Log.d("IA_RAW", "Respuesta Gemma: $respuesta")
                respuesta
            } catch (e: Exception) {
                "Excepción IA: ${e.message}"
            }
        }
    }
}