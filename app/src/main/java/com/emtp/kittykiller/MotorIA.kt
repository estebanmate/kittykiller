package com.emtp.kittykiller

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

import kotlin.math.min

object MotorIA {
    private var llmInference: LlmInference? = null

    // Configuración para división de texto mejorada (reducida para evitar OOM)
    private const val TAMANO_CHUNK_CARACTERES = 2500  // Reducido para menor uso de memoria
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
            val textoLimpio = textoTeoria.replace(Regex("\\s+"), " ").trim()
            val longitud = textoLimpio.length

            Log.d("MotorIA", "Procesando texto de $longitud caracteres para generar $cantidadTotal preguntas")

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
            for (chunk in dividirEnChunksInteligentes(textoLimpio)) {
                val numPreguntas = preguntasPorChunk.getOrElse(indiceChunk) { 0 }
                if (numPreguntas == 0) {
                    indiceChunk++
                    continue
                }

                Log.d("MotorIA", "Chunk ${chunk.indice + 1}/${chunk.total}: generando $numPreguntas preguntas (${chunk.texto.length} chars)")

                val respuestaParcial = llamarGemini(chunk, numPreguntas)
                if (respuestaParcial.isNotBlank()) {
                    resultadoFinal.append(respuestaParcial).append("\n\n")
                    val conteo = contarPreguntasGeneradas(respuestaParcial)
                    preguntasGeneradas += conteo
                    Log.d("MotorIA", "Chunk ${chunk.indice + 1}: generadas $conteo preguntas")
                }
                
                indiceChunk++
            }

            Log.d("MotorIA", "Total preguntas generadas: $preguntasGeneradas de $cantidadTotal solicitadas")
            resultadoFinal.toString()
        }
    }

    private fun llamarGemini(chunk: Chunk, cantidad: Int): String {
        val contextoChunk = if (chunk.total > 1) {
            "Este es el fragmento ${chunk.indice + 1} de ${chunk.total} del documento completo."
        } else {
            "Este es el documento completo."
        }
        
        // Prompt optimizado con Few-Shot Learning
        val prompt = """
<start_of_turn>user
Eres un profesor experto en TCAE (Técnico en Cuidados Auxiliares de Enfermería). Tu tarea es generar preguntas de examen tipo test de alta calidad.

$contextoChunk

INSTRUCCIONES:
1. Lee cuidadosamente el siguiente texto
2. Genera EXACTAMENTE $cantidad preguntas basadas EXCLUSIVAMENTE en el contenido del texto
3. Las preguntas deben:
   - Evaluar conceptos clave y conocimientos importantes
   - Ser claras y sin ambigüedades
   - Tener 4 opciones (A, B, C, D)
   - Tener UNA ÚNICA respuesta correcta
   - Evitar preguntas triviales o demasiado obvias
   - Cubrir diferentes partes del texto proporcionado

EJEMPLO DE FORMATO CORRECTO:
### PREGUNTA ###
ENUNCIADO: ¿Cuál es el artículo de la Constitución Española que reconoce el derecho a la protección de la salud?
OPCION_A: Artículo 41
OPCION_B: Artículo 42
OPCION_C: Artículo 43
OPCION_D: Artículo 44
SOLUCION: c

TEXTO A ANALIZAR:
${chunk.texto}

IMPORTANTE: 
- Usa EXACTAMENTE el formato mostrado en el ejemplo
- Cada pregunta debe empezar con "### PREGUNTA ###"
- La solución debe ser solo la letra (a, b, c o d) en minúscula
- NO inventes información que no esté en el texto
- NO generes más de $cantidad preguntas

Genera las preguntas ahora:
<end_of_turn>
<start_of_turn>model
### PREGUNTA ###
""".trimIndent()

        return try {
            val respuesta = llmInference?.generateResponse(prompt) ?: ""
            // Asegurar que la respuesta tiene el formato correcto
            if (!respuesta.trim().startsWith("ENUNCIADO:")) {
                "### PREGUNTA ###\n$respuesta"
            } else {
                respuesta
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
        val totalEstimado = ceil(longitud.toDouble() / (TAMANO_CHUNK_CARACTERES - SOLAPE_CHUNK)).toInt()
        
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
}