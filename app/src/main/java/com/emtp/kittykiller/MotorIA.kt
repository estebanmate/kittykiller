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

    // Configuración para división de texto mejorada
    private const val TAMANO_CHUNK_CARACTERES = 4000  // Aumentado para mejor contexto
    private const val TAMANO_MINIMO_CHUNK = 2000      // Tamaño mínimo de chunk
    private const val TAMANO_MAXIMO_CHUNK = 5000      // Tamaño máximo de chunk
    private const val SOLAPE_CHUNK = 300              // Solape aumentado para mejor continuidad
    
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

            // Dividir en chunks inteligentes
            val chunks = dividirEnChunksInteligentes(textoLimpio)
            Log.d("MotorIA", "Texto dividido en ${chunks.size} chunks")

            // Distribuir preguntas proporcionalmente
            val preguntasPorChunk = distribuirPreguntas(chunks, cantidadTotal)
            
            val resultadoFinal = StringBuilder()
            var preguntasGeneradas = 0

            for ((chunk, numPreguntas) in chunks.zip(preguntasPorChunk)) {
                if (numPreguntas == 0) continue

                Log.d("MotorIA", "Chunk ${chunk.indice + 1}/${chunk.total}: generando $numPreguntas preguntas (${chunk.texto.length} chars)")

                val respuestaParcial = llamarGemini(chunk, numPreguntas)
                if (respuestaParcial.isNotBlank()) {
                    resultadoFinal.append(respuestaParcial).append("\n\n")
                    val conteo = contarPreguntasGeneradas(respuestaParcial)
                    preguntasGeneradas += conteo
                    Log.d("MotorIA", "Chunk ${chunk.indice + 1}: generadas $conteo preguntas")
                }
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
    private fun dividirEnChunksInteligentes(texto: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val longitud = texto.length
        
        // Si es corto, un solo chunk
        if (longitud <= TAMANO_CHUNK_CARACTERES) {
            return listOf(Chunk(texto, 0, 1, true))
        }
        
        var cursor = 0
        var indice = 0
        
        while (cursor < longitud) {
            val finIdeal = minOf(cursor + TAMANO_CHUNK_CARACTERES, longitud)
            
            // Buscar punto de corte natural
            val finReal = encontrarPuntoCorteNatural(texto, cursor, finIdeal)
            
            val textoChunk = texto.substring(cursor, finReal)
            chunks.add(Chunk(textoChunk, indice, -1, false))
            
            // Avanzar con solape
            cursor = finReal - SOLAPE_CHUNK
            indice++
        }
        
        // Actualizar total y marcar último chunk
        val total = chunks.size
        return chunks.mapIndexed { i, chunk ->
            chunk.copy(total = total, esUltimo = i == chunks.size - 1)
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
    
    // Distribuye las preguntas proporcionalmente entre los chunks
    private fun distribuirPreguntas(chunks: List<Chunk>, total: Int): List<Int> {
        if (chunks.isEmpty()) return emptyList()
        if (chunks.size == 1) return listOf(total)
        
        // Calcular distribución proporcional basada en tamaño de chunk
        val tamanos = chunks.map { it.texto.length }
        val tamanoTotal = tamanos.sum()
        
        val distribucion = chunks.map { chunk ->
            val proporcion = chunk.texto.length.toDouble() / tamanoTotal
            val preguntas = (total * proporcion).toInt()
            maxOf(1, preguntas) // Al menos 1 pregunta por chunk
        }.toMutableList()
        
        // Ajustar para que coincida con el total exacto
        val suma = distribucion.sum()
        if (suma < total) {
            // Añadir preguntas restantes a los chunks más grandes
            val diferencia = total - suma
            for (i in 0 until diferencia) {
                val indiceMax = distribucion.indices.maxByOrNull { distribucion[it] } ?: 0
                distribucion[indiceMax]++
            }
        } else if (suma > total) {
            // Quitar exceso de los chunks más pequeños
            val exceso = suma - total
            for (i in 0 until exceso) {
                val indiceMin = distribucion.indices.filter { distribucion[it] > 1 }
                    .minByOrNull { distribucion[it] } ?: 0
                distribucion[indiceMin]--
            }
        }
        
        return distribucion
    }
    
    // Cuenta cuántas preguntas fueron generadas en el texto
    private fun contarPreguntasGeneradas(texto: String): Int {
        return texto.split("### PREGUNTA ###").size - 1
    }
}