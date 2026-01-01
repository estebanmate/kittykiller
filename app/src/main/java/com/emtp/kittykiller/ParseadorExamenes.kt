package com.emtp.kittykiller

import android.util.Log

object ParseadorExamenes {

    fun parsearTexto(textoCompleto: String): List<Pregunta> {
        // Limpieza básica inicial
        val texto = textoCompleto.replace("\r", "")
        Log.d("Parseador", "Iniciando análisis V3...")

        // 1. ESTRATEGIA IA: Si vemos etiquetas ###, usamos el parser estricto
        if (texto.contains("###", ignoreCase = true)) {
            return parsearSalidaIA_Etiquetas(texto)
        }

        // 2. ESTRATEGIA PDF TEST:
        // A. Detectamos la tabla de respuestas (ahora con lógica de densidad)
        val mapaRespuestas = detectarTablaDeRespuestasInteligente(texto)
        Log.d("Parseador", "Respuestas en tabla validadas: ${mapaRespuestas.size}")

        // B. Parseamos las preguntas usando troceado por índices
        return parsearPatronesNumericos(texto, mapaRespuestas)
    }

    // --- PARSER IA (Sin cambios, funciona bien) ---
    fun parsearSalidaIA_Etiquetas(textoIA: String): List<Pregunta> {
        val lista = mutableListOf<Pregunta>()
        val textoLimpio = textoIA.replace("**", "")
        val bloques = textoLimpio.split(Regex("###\\s*PREGUNTA\\s*###", RegexOption.IGNORE_CASE))

        for (bloque in bloques) {
            if (bloque.isBlank()) continue
            val enunciado = extraerValorEtiqueta(bloque, "ENUNCIADO")
            val a = extraerValorEtiqueta(bloque, "OPCION_A")
            val b = extraerValorEtiqueta(bloque, "OPCION_B")
            val c = extraerValorEtiqueta(bloque, "OPCION_C")
            val d = extraerValorEtiqueta(bloque, "OPCION_D")
            var correcta = extraerValorEtiqueta(bloque, "SOLUCION").lowercase().trim().take(1)

            if (correcta.isBlank() || correcta !in "abcd") correcta = "a"

            if (enunciado.isNotBlank() && a.isNotBlank() && b.isNotBlank()) {
                lista.add(Pregunta(enunciado, a, b, c, d, correcta))
            }
        }
        return lista
    }

    private fun extraerValorEtiqueta(texto: String, etiqueta: String): String {
        val regex = Regex(
            "###\\s*$etiqueta\\s*###\\s*[:|-]?\\s*(.*?)\\s*(?=###|$)",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return regex.find(texto)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    // --- DETECTOR DE TABLAS INTELIGENTE (Algoritmo de Densidad) ---
// --- DETECTOR DE TABLAS V4 (Optimizado para rejillas y columnas) ---
    private fun detectarTablaDeRespuestasInteligente(texto: String): Map<String, String> {
        val mapa = mutableMapOf<String, String>()

        // MEJORA REGEX:
        // 1. (\d{1,3}): Busca un número de 1 a 3 cifras.
        // 2. \s{0,20}: Permite hasta 20 espacios (para tablas muy anchas como la Imagen 1).
        // 3. [:.\-\)]?: Permite separadores (1. a) o ninguno (1 a).
        // 4. ([a-dA-D]): La letra de la respuesta.
        // 5. (?![a-zA-Z]): Asegura que no sea una palabra (ej: "1 año" no vale, "1 a" sí).
        val regexPosibleRespuesta =
            Regex("(?:^|\\s|\\n)(\\d{1,3})\\s{0,20}[:.\\-\\)]?\\s*([a-dA-D])(?![a-zA-Z])")

        val coincidencias = regexPosibleRespuesta.findAll(texto).toList()

        if (coincidencias.isEmpty()) return emptyMap()

        // Agrupamos por proximidad (Densidad)
        val grupos = mutableListOf<MutableList<MatchResult>>()
        var grupoActual = mutableListOf<MatchResult>()
        grupos.add(grupoActual)

        // Si es la primera coincidencia, la añadimos
        if (coincidencias.isNotEmpty()) grupoActual.add(coincidencias[0])

        for (i in 1 until coincidencias.size) {
            val actual = coincidencias[i]
            val previo = coincidencias[i - 1]

            // Distancia entre el final de la anterior y el inicio de la actual
            val distancia = actual.range.first - previo.range.last

            // Si están a menos de 300 caracteres (aprox 2-3 líneas o saltos de columna), son del mismo grupo.
            // Aumentado a 300 para soportar tablas con muchas columnas como la Imagen 1.
            if (distancia < 300) {
                grupoActual.add(actual)
            } else {
                grupoActual = mutableListOf()
                grupoActual.add(actual)
                grupos.add(grupoActual)
            }
        }

        // Buscamos el grupo ganador (el que tenga más respuestas)
        val mejorGrupo = grupos.maxByOrNull { it.size } ?: return emptyMap()

        // Filtro de seguridad: Una tabla debe tener al menos 5 respuestas para ser considerada válida
        // (Evita confundir una lista corta enumerada en el texto)
        if (mejorGrupo.size < 5) return emptyMap()

        Log.d("Parseador", "Tabla detectada con ${mejorGrupo.size} respuestas.")

        for (match in mejorGrupo) {
            val numero = match.groupValues[1]
            val letra = match.groupValues[2].lowercase()

            // Protección contra duplicados: Si el PDF tiene la tabla repetida, nos quedamos con la primera lectura
            if (!mapa.containsKey(numero)) {
                mapa[numero] = letra
            }
        }

        return mapa
    }

    // --- PARSER NUMÉRICO V3 (Troceado por índices) ---
    private fun parsearPatronesNumericos(
        texto: String,
        mapaRespuestas: Map<String, String>
    ): List<Pregunta> {
        val lista = mutableListOf<Pregunta>()

        // Separamos por preguntas (Números 1., 2., 3...)
        val regexSeparador = Regex("(?=\\n\\d+[\\.|\\)|-]\\s+)")
        val bloques = texto.split(regexSeparador)

        // Regex para detectar dónde empieza cada opción (a, b, c, d)
        // Busca " a) ", " a. ", " A) " al inicio de línea o tras espacio
        val regexInicioOpcion = Regex("(?:^|\\n|\\s)([a-d])[\\.|\\)]\\s+", RegexOption.IGNORE_CASE)

        // Regex para limpiar marcas finales ("Respuesta correcta: x")
        val regexLineaFinal = Regex(
            "(Soluci[óo]n|Respuesta|Correcta)(\\s+correcta)?[:.]?\\s*([abcd])",
            RegexOption.IGNORE_CASE
        )
        val regexMarcaCorrectaInline =
            Regex("\\s*(\\(?[xXvV]\\)?|\\(?Correcta\\)?|✅)\\s*$", RegexOption.IGNORE_CASE)

        for (bloque in bloques) {
            if (bloque.length < 20) continue

            // 1. Extraer Número
            val regexNumero = Regex("^(\\d+)[\\.|\\)|-]").find(bloque)
            val numeroPregunta = regexNumero?.groupValues?.getOrNull(1) ?: "0"

            // 2. Mapear dónde empieza cada opción (a, b, c, d)
            // En lugar de regex voraz, buscamos los índices exactos.
            val matchesOpciones = regexInicioOpcion.findAll(bloque).toList()

            // Si no detectamos al menos 2 opciones, descartamos
            if (matchesOpciones.size < 2) continue

            // 3. Cortar el texto quirúrgicamente
            val inicioEnunciado = regexNumero?.range?.last?.plus(1) ?: 0
            val finEnunciado = matchesOpciones[0].range.first

            var enunciado = bloque.substring(inicioEnunciado, finEnunciado).trim()

            // Mapa temporal para guardar textos: "a" -> "texto", "b" -> "texto"
            val opcionesMap = mutableMapOf<String, String>()

            for (i in matchesOpciones.indices) {
                val letra = matchesOpciones[i].groupValues[1].lowercase()
                val inicioTexto = matchesOpciones[i].range.last + 1

                // El texto va hasta donde empieza la siguiente opción...
                val finTexto = if (i < matchesOpciones.size - 1) {
                    matchesOpciones[i + 1].range.first
                } else {
                    // ... o hasta el final del bloque si es la última
                    bloque.length
                }

                val textoOpcion = bloque.substring(inicioTexto, finTexto).trim()
                opcionesMap[letra] = textoOpcion
            }

            var cleanA = opcionesMap["a"] ?: ""
            var cleanB = opcionesMap["b"] ?: ""
            var cleanC = opcionesMap["c"] ?: ""
            var cleanD = opcionesMap["d"] ?: ""

            // 4. Lógica de Respuesta Correcta
            var solucion = "a"
            var respuestaEncontrada = false

            // A. Buscar marcas inline (ej: "c) Texto (Correcta)")
            fun limpiarMarca(texto: String, letra: String): String {
                if (regexMarcaCorrectaInline.containsMatchIn(texto)) {
                    solucion = letra
                    respuestaEncontrada = true
                    return texto.replace(regexMarcaCorrectaInline, "").trim()
                }
                return texto
            }

            cleanA = limpiarMarca(cleanA, "a")
            cleanB = limpiarMarca(cleanB, "b")
            cleanC = limpiarMarca(cleanC, "c")
            cleanD = limpiarMarca(cleanD, "d")

            // B. Buscar frase final en el bloque ("Solución: x")
            // A menudo esta frase se queda pegada dentro de la última opción detectada.
            if (!respuestaEncontrada) {
                val matchFinal = regexLineaFinal.find(bloque)
                if (matchFinal != null) {
                    val letraFinal = matchFinal.groupValues[3].lowercase()
                    solucion = letraFinal
                    respuestaEncontrada = true

                    // Limpiamos esa frase de la última opción (que suele ser la D o la C)
                    val textoAQuitar = matchFinal.value
                    if (cleanD.contains(textoAQuitar)) cleanD =
                        cleanD.replace(textoAQuitar, "", ignoreCase = true).trim()
                    if (cleanC.contains(textoAQuitar)) cleanC =
                        cleanC.replace(textoAQuitar, "", ignoreCase = true).trim()
                }
            }

            // C. Buscar en Tabla de Respuestas (Detectada al principio)
            if (!respuestaEncontrada && mapaRespuestas.containsKey(numeroPregunta)) {
                solucion = mapaRespuestas[numeroPregunta] ?: "a"
            }

            if (cleanA.isNotBlank() && cleanB.isNotBlank()) {
                lista.add(Pregunta(enunciado, cleanA, cleanB, cleanC, cleanD, solucion))
            }
        }
        return lista
    }

    // Compatibilidad
    fun parsearSalidaIA(texto: String) = parsearTexto(texto)
}