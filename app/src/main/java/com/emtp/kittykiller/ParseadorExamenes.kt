package com.emtp.kittykiller

import android.util.Log
import java.util.regex.Pattern

object ParseadorExamenes {

    // PATRÓN MAESTRO: Detecta el inicio de una pregunta.
    // Estrategia:
    // 1. Número seguido de punto/paréntesis/guión (ej: "1.", "1)", "1-")
    //    Ahora permite newlines opcionales después del punto (para formato Murcia: "1.\n\nTexto")
    // 2. O número seguido de espacio si después viene una Mayúscula (ej: "1 ¿Cuál...") para casos donde el OCR se come el punto.
    private val PATRON_INICIO_PREGUNTA = Pattern.compile(
        "(?:^|\\n|\\s)(\\d{1,3})\\s?[\\.\\)\\-](?:\\s*\\n)?\\s*|(?:^|\\n)(\\d{1,3})\\s+(?=[A-Z¿¡])"
    )

    // PATRÓN OPCIONES: Detecta a), a., A), A. al inicio de línea o tras espacio
    private val PATRON_OPCION = Pattern.compile(
        "(?:^|\\n|\\s)([a-d])[\\.\\)]\\s+", Pattern.CASE_INSENSITIVE
    )

    fun parsearTexto(textoCompleto: String): List<Pregunta> {
        // Normalización previa: eliminar basura típica de PDFs
        var texto = textoCompleto.replace("\r", "")
            .replace("\u000C", "") // Eliminar saltos de página (Form Feed)

        // LIMPIEZA INTELIGENTE DE ENCABEZADOS Y PIES DE PÁGINA
        texto = limpiarEncabezadosRecurrentes(texto)

        Log.d("Parseador", "Iniciando análisis iterativo V12 (con limpieza de headers)...")

        // 1. Detectar Tabla de Respuestas (Si existe al final del documento)
        val mapaRespuestas = detectarTablaDeRespuestas(texto)
        Log.d("Parseador", "Tabla detectada con ${mapaRespuestas.size} respuestas.")

        // 2. PRE-PROCESAMIENTO: Extraer todas las respuestas inline del documento
        val mapaRespuestasInline = extraerRespuestasInline(texto)
        Log.d("Parseador", "Respuestas inline detectadas: ${mapaRespuestasInline.size}")
        
        // 3. Combinar mapas de respuestas (tabla tiene prioridad sobre inline)
        val mapaRespuestasCombinado = mapaRespuestasInline.toMutableMap()
        mapaRespuestasCombinado.putAll(mapaRespuestas) // La tabla sobrescribe inline si hay conflicto
        Log.d("Parseador", "Total respuestas combinadas: ${mapaRespuestasCombinado.size}")

        // 4. Escaneo de Preguntas (Búsqueda activa)
        val preguntas = mutableListOf<Pregunta>()

        // Buscamos todas las posiciones donde parece empezar una pregunta
        val matcher = PATRON_INICIO_PREGUNTA.matcher(texto)
        val inicios =
            mutableListOf<Pair<Int, String>>() // Guardamos (PosiciónInicio, NúmeroPregunta)

        while (matcher.find()) {
            // El número puede estar en el grupo 1 o en el 2 según el patrón que coincida
            val numero = matcher.group(1) ?: matcher.group(2) ?: continue
            inicios.add(Pair(matcher.start(), numero))
        }

        // 5. Procesar bloques de texto entre cada inicio detectado
        for (i in inicios.indices) {
            val inicioActual = inicios[i].first
            // El bloque va hasta el inicio de la siguiente pregunta o hasta el final del texto
            val finActual = if (i < inicios.size - 1) inicios[i + 1].first else texto.length

            // Extraer el texto crudo de esta pregunta potencial
            val bloque = texto.substring(inicioActual, finActual)
            val numeroPregunta = inicios[i].second

            // Intentar convertir ese bloque en un objeto Pregunta
            val pregunta = procesarBloquePregunta(bloque, numeroPregunta, mapaRespuestasCombinado)
            if (pregunta != null) {
                preguntas.add(pregunta)
            }
        }

        Log.d("Parseador", "Total preguntas extraídas: ${preguntas.size}")
        return preguntas
    }

    private fun procesarBloquePregunta(
        bloque: String,
        numero: String,
        mapaRespuestas: Map<String, String>
    ): Pregunta? {
        // 1. Localizar Opciones dentro del bloque
        val matcherOpcion = PATRON_OPCION.matcher(bloque)
        val opcionesEncontradas = mutableListOf<Pair<String, Int>>() // (Letra, PosiciónInicio)

        while (matcherOpcion.find()) {
            val letra = matcherOpcion.group(1)?.lowercase() ?: continue
            opcionesEncontradas.add(Pair(letra, matcherOpcion.start()))
        }

        // Requisito mínimo: Tener al menos opciones A y B
        if (opcionesEncontradas.size < 2) return null

        // 2. Extraer Enunciado
        // El enunciado empieza después del número de pregunta y termina donde empieza la opción A

        // Buscamos dónde termina exactamente el "1." o "1)" del inicio para no incluirlo en el enunciado
        val separadorInicial = Regex("^\\s*\\d+\\s*[\\.\\)\\-]?\\s*").find(bloque)
        val inicioEnunciadoTexto = separadorInicial?.range?.last?.plus(1) ?: 0
        val finEnunciadoTexto = opcionesEncontradas[0].second

        if (inicioEnunciadoTexto >= finEnunciadoTexto) return null // Algo salió mal

        var enunciado = bloque.substring(inicioEnunciadoTexto, finEnunciadoTexto).trim()
        // Limpieza extra: quitar guiones o puntos iniciales residuales
        enunciado = enunciado.replace(Regex("^\\s*[-.]\\s*"), "")

        // 3. Extraer Texto de las Opciones
        val mapOpciones = mutableMapOf<String, String>()
        for (i in opcionesEncontradas.indices) {
            val letra = opcionesEncontradas[i].first
            val start = opcionesEncontradas[i].second
            // El texto de la opción va hasta la siguiente opción o el final del bloque
            val end =
                if (i < opcionesEncontradas.size - 1) opcionesEncontradas[i + 1].second else bloque.length

            // Ajustamos el start para saltar el "a) " (aprox 3-4 chars)
            // Buscamos el primer carácter que no sea la letra ni signos de puntuación
            var textoOp = bloque.substring(start, end).trim()
            textoOp = textoOp.replaceFirst(Regex("^[a-dA-D][\\.\\)]\\s*"), "")

            mapOpciones[letra] = textoOp
        }

        var cA = mapOpciones["a"] ?: ""
        var cB = mapOpciones["b"] ?: ""
        var cC = mapOpciones["c"] ?: ""
        var cD = mapOpciones["d"] ?: ""

        // 4. Determinar la Solución Correcta
        var solucion = "a" // Valor por defecto
        var encontrada = false

        // A) Buscar en Mapa de Respuestas (Pre-procesado: Tabla Externa + Inline)
        if (mapaRespuestas.containsKey(numero)) {
            solucion = mapaRespuestas[numero] ?: "a"
            encontrada = true
        }

        // B) Buscar marcas tipo "(X)" o "Correcta" dentro de una opción
        if (!encontrada) {
            fun checkMark(txt: String, l: String): String {
                if (txt.contains("(X)", true) || txt.endsWith(" Correcta", true)) {
                    solucion = l
                    encontrada = true
                    return txt.replace("(X)", "", true).replace(" Correcta", "", true).trim()
                }
                return txt
            }
            cA = checkMark(cA, "a")
            cB = checkMark(cB, "b")
            cC = checkMark(cC, "c")
            cD = checkMark(cD, "d")
        }
        
        // C) LIMPIEZA FINAL: Eliminar cualquier texto de "Respuesta Correcta:" que haya quedado
        // Esto puede ocurrir cuando la respuesta de la pregunta anterior se coló en este bloque
        val regexLimpiarRespuesta = Regex("Respuesta\\s+Correcta:\\s*[a-dA-D].*", RegexOption.IGNORE_CASE)
        cA = regexLimpiarRespuesta.replace(cA, "").trim()
        cB = regexLimpiarRespuesta.replace(cB, "").trim()
        cC = regexLimpiarRespuesta.replace(cC, "").trim()
        cD = regexLimpiarRespuesta.replace(cD, "").trim()

        // Solo devolvemos la pregunta si tiene contenido válido
        if (enunciado.isNotBlank() && cA.isNotBlank() && cB.isNotBlank()) {
            return Pregunta(enunciado, cA, cB, cC, cD, solucion)
        }
        return null
    }

    // PRE-EXTRACTOR DE RESPUESTAS INLINE
    // Busca todas las "Respuesta Correcta: X" en el documento y las asocia al número de pregunta más cercano
    private fun extraerRespuestasInline(texto: String): Map<String, String> {
        val mapa = mutableMapOf<String, String>()
        
        // Patrón para encontrar "Respuesta Correcta: X"
        val regexRespuesta = Regex("Respuesta\\s+Correcta:\\s*([a-dA-D])(?![a-zA-Záéíóú])", RegexOption.IGNORE_CASE)
        
        // Encontrar todas las respuestas en el documento
        val respuestas = regexRespuesta.findAll(texto).toList()
        
        // Para cada respuesta, buscar el número de pregunta más cercano ANTES de ella
        for (matchRespuesta in respuestas) {
            val posicionRespuesta = matchRespuesta.range.first
            val letraRespuesta = matchRespuesta.groupValues[1].lowercase()
            
            // Buscar hacia atrás el número de pregunta más cercano
            // Tomamos los últimos 500 caracteres antes de la respuesta para buscar el número
            val inicioVentana = maxOf(0, posicionRespuesta - 500)
            val ventana = texto.substring(inicioVentana, posicionRespuesta)
            
            // Buscar el último número de pregunta en esta ventana
            val regexNumero = Regex("(?:^|\\n|\\s)(\\d{1,3})\\s?[\\.\\)\\-]\\s+|(?:^|\\n)(\\d{1,3})\\s+(?=[A-Z¿¡])")
            val numerosEncontrados = regexNumero.findAll(ventana).toList()
            
            if (numerosEncontrados.isNotEmpty()) {
                // Tomar el último número encontrado (el más cercano a la respuesta)
                val ultimoMatch = numerosEncontrados.last()
                val numeroPregunta = ultimoMatch.groupValues[1] ?: ultimoMatch.groupValues[2]
                
                if (numeroPregunta.isNotEmpty()) {
                    mapa[numeroPregunta] = letraRespuesta
                    Log.d("Parseador", "Respuesta inline encontrada: Pregunta $numeroPregunta -> $letraRespuesta")
                }
            }
        }
        
        return mapa
    }

    // Detector de tablas robusto (Tolera errores de OCR)
    private fun detectarTablaDeRespuestas(texto: String): Map<String, String> {
        // Estrategia 1: Buscar tabla estructurada con encabezados (formato Murcia)
        val tablaEstructurada = detectarTablaEstructurada(texto)
        if (tablaEstructurada.isNotEmpty()) {
            Log.d("Parseador", "Tabla estructurada detectada con ${tablaEstructurada.size} respuestas")
            return tablaEstructurada
        }
        
        // Estrategia 2: Detección por patrón (formato original)
        val mapa = mutableMapOf<String, String>()
        // Regex busca: Número + (espacios/puntos/guiones opcionales) + Letra
        // (?![a-zA-Z]) asegura que la letra no sea el inicio de una palabra (ej: "1 Año")
        val regex = Regex("(\\d{1,3})[\\s\\.\\-]*([a-dA-D])(?![a-zA-Záéíóú])")

        val matches = regex.findAll(texto).toList()

        // Filtro de densidad:
        // Si hay menos de 10 coincidencias en todo el texto, probablemente no sea una tabla válida
        // o es un documento muy corto.
        if (matches.size < 10) return emptyMap()

        // Analizamos si las coincidencias están agrupadas (típico de una tabla al final)
        // o dispersas (típico de referencias en el texto "ver apartado 1 a").
        // Para simplificar: Si detectamos muchas (>10), asumimos que las procesamos todas.
        // En una tabla real, el último valor suele ser el correcto (si hay correcciones).
        for (m in matches) {
            val num = m.groupValues[1]
            val letra = m.groupValues[2].lowercase()
            mapa[num] = letra
        }
        
        Log.d("Parseador", "Tabla por patrón detectada con ${mapa.size} respuestas")
        return mapa
    }
    
    // Detecta tablas estructuradas con encabezados (formato Murcia)
    private fun detectarTablaEstructurada(texto: String): Map<String, String> {
        val mapa = mutableMapOf<String, String>()
        
        // Buscar encabezados de tabla
        // Ejemplos: "NÚMERO PREGUNTA RESPUESTA CORRECTA", "NUM RESP CORRECTA", etc.
        val headerPattern = Regex(
            "(?:NÚMERO|N[UÚ]MERO|NUM)\\s+(?:PREGUNTA)?\\s*(?:RESPUESTA|RESP)\\s+(?:CORRECTA)?",
            RegexOption.IGNORE_CASE
        )
        
        val headerMatch = headerPattern.find(texto) ?: return emptyMap()
        val startPos = headerMatch.range.last
        
        Log.d("Parseador", "Encabezado de tabla encontrado en posición $startPos")
        
        // Extraer filas de tabla después del encabezado
        val tableText = texto.substring(startPos)
        val lines = tableText.lines().take(200) // Limitar a 200 líneas para evitar procesar todo el documento
        
        for (line in lines) {
            // Coincidir: número + espacios + letra (formato tabla)
            // Ejemplos: "1    C", "85   B", "  42  A"
            val rowPattern = Regex("^\\s*(\\d{1,3})\\s+([A-Da-d])\\s*$")
            val match = rowPattern.find(line.trim())
            if (match != null) {
                val num = match.groupValues[1]
                val letra = match.groupValues[2].lowercase()
                mapa[num] = letra
            }
        }
        
        return mapa
    }
}