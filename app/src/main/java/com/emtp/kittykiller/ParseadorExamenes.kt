package com.emtp.kittykiller

import android.util.Log

object ParseadorExamenes {

    data class Pregunta(
        val enunciado: String,
        val opcionA: String,
        val opcionB: String,
        val opcionC: String,
        val opcionD: String,
        val solucion: String
    )

    fun parsearTexto(textoCompleto: String): List<Pregunta> {
        val texto = textoCompleto.replace("\r", "")
        Log.d("Parseador", "Iniciando análisis (Estrategia Híbrida)...")

        // 1. ESTRATEGIA: TABLA DE RESPUESTAS (Andalucía, Murcia, Madrid)
        // Buscamos patrones de "Número + Letra" que aparezcan en bloque (generalmente al final)
        val mapaRespuestas = detectarTablaDeRespuestas(texto)
        Log.d("Parseador", "Tabla detectada: ${mapaRespuestas.size} respuestas encontradas.")

        // 2. ESTRATEGIA: PARSEO SECUENCIAL (Pregunta a Pregunta)
        return parsearPreguntasSecuencial(texto, mapaRespuestas)
    }

    // --- DETECTOR DE TABLAS (Optimizado para OCR y Texto Nativo) ---
    private fun detectarTablaDeRespuestas(texto: String): Map<String, String> {
        val mapa = mutableMapOf<String, String>()

        // Regex para capturar filas de tablas.
        // Soporta: "1. a", "1-a", "1) A", "1 a", e incluso saltos de línea por culpa del OCR.
        // \s{0,20} permite que el número y la letra estén muy separados visualmente.
        val regexFilaTabla = Regex(
            "(?:^|\\n|\\s)(\\d{1,3})\\s{0,20}[:.\\-\\)]?\\s{0,5}([a-dA-D])(?![a-zA-Záéíóú])"
        )

        val coincidencias = regexFilaTabla.findAll(texto).toList()
        if (coincidencias.isEmpty()) return emptyMap()

        // Agrupamiento por densidad:
        // Las tablas reales tienen muchos pares "número-letra" juntos.
        // El texto normal tiene menciones dispersas.
        val grupos = mutableListOf<MutableList<MatchResult>>()
        var grupoActual = mutableListOf<MatchResult>()
        grupos.add(grupoActual)

        if (coincidencias.isNotEmpty()) grupoActual.add(coincidencias[0])

        for (i in 1 until coincidencias.size) {
            val actual = coincidencias[i]
            val previo = coincidencias[i - 1]
            val distancia = actual.range.first - previo.range.last

            // Si hay menos de 200 caracteres entre una coincidencia y otra, es probable que sea la misma tabla
            if (distancia < 200) {
                grupoActual.add(actual)
            } else {
                grupoActual = mutableListOf() // Nuevo grupo (posiblemente otra tabla o ruido)
                grupoActual.add(actual)
                grupos.add(grupoActual)
            }
        }

        // Elegimos el grupo más grande que tenga pinta de tabla (mínimo 10 aciertos para evitar falsos positivos)
        // Para Madrid (55 preguntas), Murcia (85) y Andalucía (153), esto funcionará bien.
        val mejorGrupo = grupos.maxByOrNull { it.size } ?: return emptyMap()
        if (mejorGrupo.size < 10) return emptyMap()

        Log.d("Parseador", "Grupo candidato a tabla encontrado con ${mejorGrupo.size} elementos.")

        for (match in mejorGrupo) {
            val numero = match.groupValues[1]
            val letra = match.groupValues[2].lowercase()
            // Si hay duplicados, guardamos la última ocurrencia (a veces hay correcciones al final)
            // o la primera si preferimos seguridad. Aquí usamos put directo.
            mapa[numero] = letra
        }

        return mapa
    }

    // --- PARSEO DEL CUERPO (Troceado inteligente) ---
    private fun parsearPreguntasSecuencial(
        texto: String,
        mapaRespuestas: Map<String, String>
    ): List<Pregunta> {
        val lista = mutableListOf<Pregunta>()

        // Regex para cortar preguntas. Busca "1.", "2)", "3-" al inicio de una línea nueva.
        // (?=...) es un Lookahead para no "consumir" el número y poder leerlo luego.
        val regexSeparador = Regex("(?=\\n\\s*\\d+[\\.|\\)|-]\\s*)")
        val bloques = texto.split(regexSeparador)

        // Regex para detectar opciones (a), b., C), etc.)
        val regexInicioOpcion = Regex("(?:^|\\n|\\s)([a-d])[\\.|\\)]\\s+", RegexOption.IGNORE_CASE)

        // Regex para detectar respuestas INLINE (Caso Canarias)
        // Busca "Sol: a", "Resp: b", "Respuesta Correcta: c", o marcas "(X)"
        val regexRespuestaInline = Regex(
            "(?:Soluci[óo]n|Resp|Respuesta|Correcta)\\s*[:.]?\\s*([abcd])|\\s*\\([xXvV]\\)\\s*$",
            RegexOption.IGNORE_CASE
        )

        for (bloque in bloques) {
            if (bloque.length < 30) continue // Ignorar basura

            // 1. Extraer NÚMERO
            val regexNumero = Regex("^\\s*(\\d+)[\\.|\\)|-]").find(bloque)
            val numeroPregunta = regexNumero?.groupValues?.getOrNull(1) ?: continue

            // 2. Extraer OPCIONES
            val matchesOpciones = regexInicioOpcion.findAll(bloque).toList()
            if (matchesOpciones.size < 2) continue // Necesitamos al menos 2 opciones para que sea un test

            // 3. Cortar ENUNCIADO
            // Desde el final del número hasta el inicio de la opción 'a)'
            val inicioEnunciado = regexNumero.range.last + 1
            val finEnunciado = matchesOpciones[0].range.first
            var enunciado = bloque.substring(inicioEnunciado, finEnunciado).trim()
            // Limpieza extra del enunciado (quitar guiones o puntos iniciales)
            enunciado = enunciado.replace(Regex("^\\s*[-.]\\s*"), "")

            // 4. Mapear textos de OPCIONES
            val opcionesMap = mutableMapOf<String, String>()
            for (i in matchesOpciones.indices) {
                val letra = matchesOpciones[i].groupValues[1].lowercase()
                val inicioTexto = matchesOpciones[i].range.last + 1
                // El texto va hasta la siguiente opción o hasta el final del bloque
                val finTexto =
                    if (i < matchesOpciones.size - 1) matchesOpciones[i + 1].range.first else bloque.length
                opcionesMap[letra] = bloque.substring(inicioTexto, finTexto).trim()
            }

            var cA = opcionesMap["a"] ?: ""
            var cB = opcionesMap["b"] ?: ""
            var cC = opcionesMap["c"] ?: ""
            var cD = opcionesMap["d"] ?: ""

            // 5. DETERMINAR SOLUCIÓN
            var solucion = "a" // Default
            var encontrada = false

            // PRIORIDAD A: ¿Está en el mapa de tabla (final del documento)?
            if (mapaRespuestas.containsKey(numeroPregunta)) {
                solucion = mapaRespuestas[numeroPregunta] ?: "a"
                encontrada = true
            }

            // PRIORIDAD B: ¿Está escrita en el propio texto (Caso Canarias)?
            // Si NO se encontró en la tabla, buscamos "Resp: X" en el bloque
            if (!encontrada) {
                val matchInline = regexRespuestaInline.find(bloque)
                if (matchInline != null) {
                    // Caso "Resp: a"
                    val letraCapturada = matchInline.groupValues.getOrNull(1)
                    if (!letraCapturada.isNullOrBlank()) {
                        solucion = letraCapturada.lowercase()
                        encontrada = true
                        // Limpiamos la pista de la respuesta del texto de la última opción (D)
                        // para que el usuario no vea "La respuesta es la b" en la app.
                        val textoPista = matchInline.value
                        if (cD.contains(textoPista)) cD = cD.replace(textoPista, "").trim()
                        if (cC.contains(textoPista)) cC = cC.replace(textoPista, "").trim()
                    }
                }
            }

            // PRIORIDAD C: Marcas específicas tipo "(X)" dentro de una opción
            if (!encontrada) {
                fun checkMark(texto: String, letra: String): String {
                    if (texto.contains("(X)", ignoreCase = true) || texto.endsWith(
                            " Correcta",
                            ignoreCase = true
                        )
                    ) {
                        solucion = letra
                        encontrada = true
                        return texto.replace("(X)", "", ignoreCase = true)
                            .replace(" Correcta", "", ignoreCase = true).trim()
                    }
                    return texto
                }
                cA = checkMark(cA, "a")
                cB = checkMark(cB, "b")
                cC = checkMark(cC, "c")
                cD = checkMark(cD, "d")
            }

            // Guardar
            if (enunciado.isNotBlank() && cA.isNotBlank() && cB.isNotBlank()) {
                lista.add(Pregunta(enunciado, cA, cB, cC, cD, solucion))
            }
        }

        return lista
    }
}