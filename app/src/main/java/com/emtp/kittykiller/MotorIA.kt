package com.emtp.kittykiller

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

object MotorIA {
    private var llmInference: LlmInference? = null

    // Configuración para división de texto mejorada (reducida para evitar OOM)
    private const val TAMANO_CHUNK_CARACTERES = 2000  // Reducido más para evitar crash por contexto
    private const val TAMANO_MINIMO_CHUNK = 1500      // Tamaño mínimo de chunk
    private const val TAMANO_MAXIMO_CHUNK = 3500      // Tamaño máximo de chunk
    private const val SOLAPE_CHUNK = 150              // Solape reducido para menor duplicación

    // Data class para representar un chunk de texto
    data class Chunk(
        val texto: String,
        val indice: Int,
        val total: Int,
        val esUltimo: Boolean
    )

    suspend fun inicializar(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            if (llmInference != null) return@withContext true
            try {
                // Buscamos el modelo en el almacenamiento interno (descargado previamente)
                val modelFile = File(context.filesDir, "gemma-2b-it-cpu-int4.bin")
                if (!modelFile.exists()) return@withContext false

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(800) // Tokens para la RESPUESTA generada (reducido para dejar espacio al prompt)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun generarPreguntas(
        textoTeoria: String,
        cantidadTotal: Int,
        onProgress: (Int, Int, Int) -> Unit // (Porcentaje, Generadas, TotalSolicitado)
    ): String {
        return withContext(Dispatchers.IO) {
            if (llmInference == null) return@withContext "Error: IA no inicializada."

            // Limpieza básica de espacios múltiples
            val textoLimpio = textoTeoria.replace(Regex("\\s+"), " ").trim()
            val longitud = textoLimpio.length

            Log.d(
                "MotorIA",
                "Procesando texto de $longitud caracteres para generar $cantidadTotal preguntas"
            )

            // Calcular número estimado de chunks para distribución
            val numChunksEstimado = if (longitud <= TAMANO_CHUNK_CARACTERES) {
                1
            } else {
                ceil(longitud.toDouble() / (TAMANO_CHUNK_CARACTERES - SOLAPE_CHUNK)).toInt()
            }
            Log.d("MotorIA", "Número estimado de chunks: $numChunksEstimado")

            // Distribuir preguntas proporcionalmente
            val preguntasPorChunk = distribuirPreguntasUniforme(numChunksEstimado, cantidadTotal)

            val resultadoFinal = StringBuilder()
            var preguntasGeneradas = 0
            var indiceChunk = 0

            // Procesar chunks de forma lazy (uno a la vez)
            val chunks = dividirEnChunksInteligentes(textoLimpio)
            for (chunk in chunks) {
                // Verificar si el usuario canceló la operación
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                val numPreguntas = preguntasPorChunk.getOrElse(indiceChunk) { 0 }

                // Actualizar progreso UI (Inicio del chunk)
                val porcentaje = ((indiceChunk.toFloat() / numChunksEstimado) * 100).toInt()
                withContext(Dispatchers.Main) {
                    onProgress(porcentaje, preguntasGeneradas, cantidadTotal)
                }

                if (numPreguntas == 0) {
                    indiceChunk++
                    continue
                }

                Log.d(
                    "MotorIA",
                    "Chunk ${chunk.indice + 1}/${chunk.total}: generando $numPreguntas preguntas (${chunk.texto.length} chars)"
                )

                val respuestaParcial = llamarGemini(chunk, numPreguntas)
                if (respuestaParcial.isNotBlank()) {
                    resultadoFinal.append(respuestaParcial).append("\n\n")
                    val conteo = contarPreguntasGeneradas(respuestaParcial)
                    preguntasGeneradas += conteo
                    Log.d("MotorIA", "Chunk ${chunk.indice + 1}: generadas $conteo preguntas")
                }

                indiceChunk++
            }

            // Actualizar progreso final
            withContext(Dispatchers.Main) {
                onProgress(100, preguntasGeneradas, cantidadTotal)
            }

            Log.d(
                "MotorIA",
                "Total preguntas generadas: $preguntasGeneradas de $cantidadTotal solicitadas"
            )
            resultadoFinal.toString()
        }
    }

    private fun llamarGemini(chunk: Chunk, cantidad: Int): String {
        val contextoChunk = if (chunk.total > 1) {
            "Este es el fragmento ${chunk.indice + 1} de ${chunk.total} del documento completo."
        } else {
            "Este es el documento completo."
        }

        // Prompt optimizado con Few-Shot Learning (versión compacta)
        val prompt = """
<start_of_turn>user
Eres un profesor experto en TCAE. Genera $cantidad preguntas de test (A,B,C,D, única respuesta) basadas en el texto.

$contextoChunk

TEXTO:
${chunk.texto}

FORMATO OBLIGATORIO:
### PREGUNTA ###
ENUNCIADO: [Pregunta]
OPCION_A: [Opción A]
OPCION_B: [Opción B]
OPCION_C: [Opción C]
OPCION_D: [Opción D]
SOLUCION: [letra a,b,c,d]

IMPORTANTE:
- La SOLUCION debe ser solo la letra (a, b, c o d).
- No uses markdown en la solución.

Genera las preguntas ahora:
<end_of_turn>
<start_of_turn>model
### PREGUNTA ###
<start_of_turn>model
### PREGUNTA ###
""".trimIndent()

        return try {
            val respuesta = llmInference?.generateResponse(prompt) ?: ""
            val respuestaTrim = respuesta.trim()

            // Si la respuesta empieza por ENUNCIADO, significa que la IA continuó desde nuestra cabecera
            // pero la cabecera no está en la respuesta devuelta, así que la añadimos.
            if (respuestaTrim.startsWith("ENUNCIADO:")) {
                "### PREGUNTA ###\n$respuestaTrim"
            } else if (!respuestaTrim.startsWith("### PREGUNTA ###") && respuestaTrim.isNotEmpty()) {
                // Si devuelve algo que no empieza por la etiqueta ni por enunciado (raro), intentamos arreglarlo
                "### PREGUNTA ###\n$respuestaTrim"
            } else {
                respuestaTrim
            }
        } catch (e: Exception) {
            Log.e("MotorIA", "Error generando preguntas: ${e.message}")
            ""
        }
    }

    // Divide el texto en chunks inteligentes buscando puntos de corte naturales
    // Usa Sequence para procesamiento lazy (evita cargar todos los chunks en memoria)
    private fun dividirEnChunksInteligentes(texto: String): Sequence<Chunk> = sequence {
        val longitud = texto.length

        // Si es corto, un solo chunk
        if (longitud <= TAMANO_CHUNK_CARACTERES) {
            yield(Chunk(texto, 0, 1, true))
            return@sequence
        }

        // Calcular total de chunks estimado
        val totalEstimado =
            ceil(longitud.toDouble() / (TAMANO_CHUNK_CARACTERES - SOLAPE_CHUNK)).toInt()

        var cursor = 0
        var indice = 0

        while (cursor < longitud) {
            val finIdeal = minOf(cursor + TAMANO_CHUNK_CARACTERES, longitud)

            // Buscar punto de corte natural
            val finReal = encontrarPuntoCorteNatural(texto, cursor, finIdeal)

            val textoChunk = texto.substring(cursor, finReal)
            val esUltimo = finReal >= longitud

            yield(Chunk(textoChunk, indice, totalEstimado, esUltimo))

            if (esUltimo) break

            // Avanzar con solape
            cursor = finReal - SOLAPE_CHUNK
            indice++
        }
    }

    // Encuentra un punto de corte natural en el texto (párrafo, frase, etc.)
    private fun encontrarPuntoCorteNatural(texto: String, inicio: Int, finIdeal: Int): Int {
        if (finIdeal >= texto.length) return texto.length

        // Ventana de búsqueda: 200 caracteres antes del fin ideal
        val ventanaBusqueda = 200
        val inicioVentana = maxOf(inicio, finIdeal - ventanaBusqueda)
        val ventana = texto.substring(inicioVentana, finIdeal)

        // Buscar patrones de corte en orden de preferencia
        val patronesCorte = listOf(
            Regex("\\n\\n"),           // Salto de párrafo
            Regex("\\.\\n"),           // Punto + salto de línea
            Regex("\\n"),              // Salto de línea simple
            Regex("\\. [A-ZÁÉÍÓÚÑ]")   // Punto + mayúscula (nueva frase)
        )

        for (patron in patronesCorte) {
            val matches = patron.findAll(ventana).toList()
            if (matches.isNotEmpty()) {
                val ultimoMatch = matches.last()
                return inicioVentana + ultimoMatch.range.last + 1
            }
        }

        // No se encontró punto natural, usar posición ideal
        return finIdeal
    }

    // Distribuye las preguntas uniformemente entre los chunks
    private fun distribuirPreguntasUniforme(numChunks: Int, total: Int): List<Int> {
        if (numChunks == 0) return emptyList()
        if (numChunks == 1) return listOf(total)

        val preguntasPorChunk = total / numChunks
        val resto = total % numChunks

        return List(numChunks) { indice ->
            // Distribuir el resto en los primeros chunks
            if (indice < resto) preguntasPorChunk + 1 else preguntasPorChunk
        }
    }

    // Cuenta cuántas preguntas fueron generadas en el texto
    private fun contarPreguntasGeneradas(texto: String): Int {
        return texto.split("### PREGUNTA ###").size - 1
    }

    // Parser dedicado para la salida de la IA
    fun parsearRespuestaIA(texto: String): List<Pregunta> {
        val preguntas = mutableListOf<Pregunta>()
        val bloques = texto.split("### PREGUNTA ###")

        for (bloque in bloques) {
            if (bloque.isBlank()) continue

            // Extraer campos usando Regex
            val enunciado = extraerCampo(bloque, "ENUNCIADO")
            val opA = extraerCampo(bloque, "OPCION_A")
            val opB = extraerCampo(bloque, "OPCION_B")
            val opC = extraerCampo(bloque, "OPCION_C")
            val opD = extraerCampo(bloque, "OPCION_D")
            // Aceptamos variaciones con acento o sin él
            val solucion = extraerCampoFuzzy(bloque, listOf("SOLUCION", "SOLUCIÓN", "RESPUESTA CORRECTA")).lowercase().take(1)

            // Validar que tenemos lo mínimo
            if (enunciado.isNotBlank() && opA.isNotBlank() && opB.isNotBlank() && solucion.isNotBlank()) {
                // Crear objeto Pregunta (asumiendo que la clase Pregunta existe en el paquete)
                val pregunta = Pregunta(
                    enunciado = enunciado,
                    opcionA = opA,
                    opcionB = opB,
                    opcionC = opC.ifBlank { " " }, // Rellenar si falta para evitar nulls molestos si la clase lo exige
                    opcionD = opD.ifBlank { " " },
                    solucion = solucion
                )
                preguntas.add(pregunta)
            }
        }
        return preguntas
    }

    private fun extraerCampo(texto: String, etiqueta: String): String {
        return extraerCampoFuzzy(texto, listOf(etiqueta))
    }

    private fun extraerCampoFuzzy(texto: String, etiquetas: List<String>): String {
        // Construimos regex que busque cualquiera de las etiquetas
        // (?:ETIQUETA1|ETIQUETA2):\s*(.*?)...
        val keysPattern = etiquetas.joinToString("|") { Regex.escape(it) }
        val regex = Regex("(?:$keysPattern):\\s*(.*?)(?=\\n[A-Z_]+:|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val match = regex.find(texto)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }
}