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

            // Aumentamos un poco la temperatura para favorecer la creatividad en las opciones falsas
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .setTemperature(0.8f) // Subimos de 0.7 a 0.8 para menos repetición
                .setTopK(40)
                .build()

            try {
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

            // Limpieza de espacios para ahorrar tokens
            val contextoLimpio = textoTeoria.replace(Regex("\\s+"), " ").trim()

            // PROMPT ENGINEERING AVANZADO (Chain-of-Thought implícito + Restricciones Fuertes)
            val prompt = """
<start_of_turn>user
Actúa como un experto redactor de exámenes de oposiciones sanitarias (TCAE). 
Tu tarea es generar un examen tipo test de alta dificultad basado EXCLUSIVAMENTE en el texto proporcionado abajo.

INSTRUCCIONES DE OBLIGADO CUMPLIMIENTO:
1. Genera exactamente $cantidad preguntas.
2. **ALEATORIEDAD RADICAL**: La respuesta correcta NO puede ser siempre la 'a'. Debes distribuir las soluciones equitativamente entre a, b, c y d.
3. **CALIDAD DE OPCIONES**: Las opciones incorrectas (distractores) deben ser muy plausibles y estar relacionadas con el texto. No uses opciones absurdas como "Ninguna es correcta" salvo que sea estrictamente necesario.
4. **FORMATO**: Sigue estrictamente la estructura para que el sistema pueda leerlo.

TEXTO DE ESTUDIO:
"$contextoLimpio"

FORMATO DE SALIDA REQUERIDO:
1. [Enunciado claro y conciso]
a) [Opción posible 1]
b) [Opción posible 2]
c) [Opción posible 3]
d) [Opción posible 4]
Solución: [a/b/c/d]

Ejemplo de lo que espero:
1. ¿Cuál es la función principal de los leucocitos?
a) Transporte de oxígeno
b) Coagulación sanguínea
c) Defensa del organismo
d) Producción de hormonas
Solución: c

¡Empieza a generar las preguntas ahora!
<end_of_turn>
<start_of_turn>model
""".trimIndent()

            try {
                val respuesta = llmInference?.generateResponse(prompt) ?: "Error IA"
                // Log para depuración
                android.util.Log.d(
                    "IA_RAW",
                    "Prompt length: ${prompt.length} | Respuesta: ${respuesta.take(100)}..."
                )
                respuesta
            } catch (e: Exception) {
                "Excepción IA: ${e.message}"
            }
        }
    }
}