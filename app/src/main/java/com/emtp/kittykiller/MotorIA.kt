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
//                val modelFile = File(context.filesDir, "gemma-2-2b-it-cpu-int8.task")
                //if (!modelFile.exists()) return@withContext false

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
            withContext(Dispatchers.Main) { onProgress(0, 0, cantidadTotal) }
            val chunks = dividirEnChunksInteligentes(textoLimpio)
            for (chunk in chunks) {
                // Verificar si el usuario canceló la operación
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                val numPreguntas = preguntasPorChunk.getOrElse(indiceChunk) { 0 }

                // Verificamos si hay preguntas asignadas a este chunk
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
                    
                    // Actualizamos progreso basado en PREGUNTAS REALES
                    val porcentaje = ((preguntasGeneradas.toFloat() / cantidadTotal) * 100).toInt().coerceAtMost(100)
                    withContext(Dispatchers.Main) {
                        onProgress(porcentaje, preguntasGeneradas, cantidadTotal)
                    }
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

FORMATO OBLIGATORIO (Imítalo exactamente):
### PREGUNTA ###
ENUNCIADO: [Pregunta]
OPCION_A: [Opción A]
OPCION_B: [Opción B]
OPCION_C: [Opción C]
OPCION_D: [Opción D]
SOLUCION: [solo la letra a,b,c o d]

EJEMPLO DE SALIDA:
### PREGUNTA ###
ENUNCIADO: ¿Cuál es la función principal de los glóbulos rojos?
OPCION_A: Coagulación
OPCION_B: Transporte de oxígeno
OPCION_C: Defensa
OPCION_D: Estructura ósea
SOLUCION: b

Genera las preguntas ahora siguiendo el ejemplo:
<end_of_turn>
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

    // Parser dedicado para la salida de la IA (Versión Robusta)
    fun parsearRespuestaIA(texto: String): List<Pregunta> {
        val preguntas = mutableListOf<Pregunta>()
        val bloques = texto.split("### PREGUNTA ###")

        for (bloque in bloques) {
            if (bloque.isBlank()) continue

            // 1. Extracción de campos (usando la nueva regex permisiva)
            val enunciado = extraerCampo(bloque, "ENUNCIADO")
            val opA = extraerCampo(bloque, "OPCION_A")
            val opB = extraerCampo(bloque, "OPCION_B")
            val opC = extraerCampo(bloque, "OPCION_C")
            val opD = extraerCampo(bloque, "OPCION_D")

            // Obtenemos el texto crudo de la solución
            val solucionRaw =
                extraerCampoFuzzy(bloque, listOf("SOLUCION", "SOLUCIÓN", "RESPUESTA CORRECTA"))

            // 2. Lógica inteligente para deducir la letra correcta (aunque la IA escriba texto)
            val solucionFinal =
                if (solucionRaw.length == 1 && solucionRaw.matches(Regex("[a-dA-D]"))) {
                    solucionRaw.lowercase()
                } else {
                    val textoSolucion = solucionRaw.lowercase()

                    // Prioridad 1: Coincidencia de texto
                    if (opA.isNotBlank() && textoSolucion.contains(opA.lowercase().take(15))) "a"
                    else if (opB.isNotBlank() && textoSolucion.contains(
                            opB.lowercase().take(15)
                        )
                    ) "b"
                    else if (opC.isNotBlank() && textoSolucion.contains(
                            opC.lowercase().take(15)
                        )
                    ) "c"
                    else if (opD.isNotBlank() && textoSolucion.contains(
                            opD.lowercase().take(15)
                        )
                    ) "d"
                    else {
                        // Prioridad 2: Buscar letra explícita (ej: "b)")
                        Regex("([a-dA-D])([).:\\s]|$)").find(solucionRaw)?.groupValues?.get(1)
                            ?.lowercase()
                            ?: "a" // Fallback
                    }
                }

            // 3. IF PERMISIVO:
            // Solo exigimos Enunciado, A, B y Solución.
            // Si falta C o D, rellenamos con espacio en blanco para que no crashee.
            if (enunciado.isNotBlank() && opA.isNotBlank() && opB.isNotBlank() && solucionFinal.isNotBlank()) {
                val pregunta = Pregunta(
                    enunciado = enunciado,
                    opcionA = opA,
                    opcionB = opB,
                    opcionC = opC.ifBlank { " " }, // Rellenamos si falta
                    opcionD = opD.ifBlank { " " }, // Rellenamos si falta
                    solucion = solucionFinal
                )
                preguntas.add(pregunta)
            }
        }
        return preguntas
    }

    private fun extraerCampo(texto: String, etiqueta: String): String {
        return extraerCampoFuzzy(texto, listOf(etiqueta))
    }

    private fun extraerCampoFuzzy(texto: String, etiquetasTarget: List<String>): String {
        // Todas las posibles etiquetas que pueden aparecer en la respuesta
        // Esto sirve como "freno" para el regex: deja de capturar cuando ve una de estas
        val todasLasEtiquetas = listOf(
            "ENUNCIADO",
            "OPCION_A", "OPCION_B", "OPCION_C", "OPCION_D",
            "SOLUCION", "SOLUCIÓN", "RESPUESTA CORRECTA",
            "### PREGUNTA ###" // También paramos si vemos el inicio de otra pregunta
        )

        // 1. Construimos el patrón de lo que QUEREMOS buscar (ej: "OPCION_A")
        val targetPattern = etiquetasTarget.joinToString("|") { Regex.escape(it) }

        // 2. Construimos el patrón de lo que nos debe DETENER (cualquier etiqueta conocida)
        val stopPattern = todasLasEtiquetas.joinToString("|") { Regex.escape(it) }

        // REGEX EXPLICADA:
        // (?:$targetPattern): -> Busca la etiqueta objetivo + dos puntos
        // \s* -> Ignora espacios después de los dos puntos
        // (.*?) -> CAPTURA el contenido de forma no codiciosa...
        // (?=\s*(?:$stopPattern)|$) -> ...hasta que encuentre (lookahead) una StopLabel o el final del string
        val regex = Regex(
            "(?:$targetPattern):\\s*(.*?)(?=\\s*(?:$stopPattern)|$)",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val match = regex.find(texto)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }
}