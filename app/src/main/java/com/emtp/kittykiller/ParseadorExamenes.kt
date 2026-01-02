package com.emtp.kittykiller

import android.util.Log

object ParseadorExamenes {

    fun parsearTexto(textoCompleto: String): List<Pregunta> {
        val texto = textoCompleto.replace("\r", "")
        Log.d("Parseador", "Iniciando análisis MASTER V5...")

        // 1. MODO IA (Si viene del generador, usa etiquetas explícitas)
        if (texto.contains("### PREGUNTA ###", ignoreCase = true)) {
            return parsearSalidaIA_Etiquetas(texto)
        }

        // 2. MODO PDF/DOC EXISTENTE
        // A. Detectar Tabla de Respuestas (Algoritmo de Densidad)
        // Busca bloques densos de "Número + Letra" al final o principio del documento
        val mapaRespuestas = detectarTablaDeRespuestasInteligente(texto)
        Log.d("Parseador", "Respuestas en tabla validadas: ${mapaRespuestas.size}")

        // B. Parsear Preguntas (Algoritmo de Troceado + Inline)
        return parsearPatronesNumericos(texto, mapaRespuestas)
    }

    // --- A. DETECTOR DE TABLAS (Soporta columnas anchas y rejillas) ---
    private fun detectarTablaDeRespuestasInteligente(texto: String): Map<String, String> {
        val mapa = mutableMapOf<String, String>()

        // Regex permisivo: Busca "1. a", "1 a", "1-a", "1   A"
        // \s{0,25} permite hasta 25 espacios para tablas muy anchas como las de tus imágenes
        val regexPosible =
            Regex("(?:^|\\s|\\n)(\\d{1,3})\\s{0,25}[:.\\-\\)]?\\s*([a-dA-D])(?![a-zA-Záéíóú])")

        val coincidencias = regexPosible.findAll(texto).toList()
        if (coincidencias.isEmpty()) return emptyMap()

        // Agrupamiento por proximidad (Densidad)
        val grupos = mutableListOf<MutableList<MatchResult>>()
        var grupoActual = mutableListOf<MatchResult>()
        grupos.add(grupoActual)

        if (coincidencias.isNotEmpty()) grupoActual.add(coincidencias[0])

        for (i in 1 until coincidencias.size) {
            val actual = coincidencias[i]
            val previo = coincidencias[i - 1]
            val distancia = actual.range.first - previo.range.last

            // Si están a menos de 400 caracteres, son "vecinos" (misma tabla/página)
            if (distancia < 400) {
                grupoActual.add(actual)
            } else {
                grupoActual = mutableListOf()
                grupoActual.add(actual)
                grupos.add(grupoActual)
            }
        }

        // Elegimos el grupo más grande que parezca una tabla (mínimo 4 aciertos)
        val mejorGrupo = grupos.maxByOrNull { it.size } ?: return emptyMap()

        // Filtro de seguridad: Una tabla real suele tener al menos 4-5 respuestas juntas
        if (mejorGrupo.size < 4) return emptyMap()

        for (match in mejorGrupo) {
            val numero = match.groupValues[1]
            val letra = match.groupValues[2].lowercase()
            // Prioridad a la primera aparición para evitar duplicados erróneos
            if (!mapa.containsKey(numero)) mapa[numero] = letra
        }
        return mapa
    }

    // --- B. PARSER DE PREGUNTAS (Troceado por índices) ---
    private fun parsearPatronesNumericos(
        texto: String,
        mapaRespuestas: Map<String, String>
    ): List<Pregunta> {
        val lista = mutableListOf<Pregunta>()

        // Separador de preguntas: "1.", "2-", "3)" al inicio de línea
        val regexSeparador = Regex("(?=\\n\\d+[\\.|\\)|-]\\s+)")
        val bloques = texto.split(regexSeparador)

        // Detectores de inicio de opción: " a) ", " A. ", etc.
        val regexInicioOpcion = Regex("(?:^|\\n|\\s)([a-d])[\\.|\\)]\\s+", RegexOption.IGNORE_CASE)

        // Detectores de respuesta correcta "Inline" (ej: "[X]") o "Final" (ej: "Sol: a")
        val regexMarcaInline =
            Regex("\\s*(\\(?[xXvV]\\)?|\\(?Correcta\\)?|✅)\\s*$", RegexOption.IGNORE_CASE)
        val regexLineaFinal = Regex(
            "(Soluci[óo]n|Respuesta|Correcta)(\\s+correcta)?[:.]?\\s*([abcd])",
            RegexOption.IGNORE_CASE
        )

        for (bloque in bloques) {
            if (bloque.length < 20) continue

            // 1. Extraer Número
            val regexNumero = Regex("^(\\d+)[\\.|\\)|-]").find(bloque)
            val numeroPregunta = regexNumero?.groupValues?.getOrNull(1) ?: "0"

            // 2. Mapear Opciones (Troceado exacto para no duplicar texto)
            val matchesOpciones = regexInicioOpcion.findAll(bloque).toList()
            if (matchesOpciones.size < 2) continue // Necesitamos al menos A y B

            // Cortar Enunciado
            val inicioEnunciado = regexNumero?.range?.last?.plus(1) ?: 0
            val finEnunciado = matchesOpciones[0].range.first
            val enunciado = bloque.substring(inicioEnunciado, finEnunciado).trim()

            // Cortar Opciones usando los índices detectados
            val opcionesMap = mutableMapOf<String, String>()
            for (i in matchesOpciones.indices) {
                val letra = matchesOpciones[i].groupValues[1].lowercase()
                val inicioTexto = matchesOpciones[i].range.last + 1
                val finTexto =
                    if (i < matchesOpciones.size - 1) matchesOpciones[i + 1].range.first else bloque.length
                opcionesMap[letra] = bloque.substring(inicioTexto, finTexto).trim()
            }

            var cA = opcionesMap["a"] ?: ""
            var cB = opcionesMap["b"] ?: ""
            var cC = opcionesMap["c"] ?: ""
            var cD = opcionesMap["d"] ?: ""

            // 3. Lógica de Respuesta Correcta (Jerarquía de detección)
            var solucion = "a" // Default
            var encontrada = false

            // Nivel 1: Marcas Inline (ej: "a) Texto (X)")
            fun limpiar(tx: String, l: String): String {
                if (regexMarcaInline.containsMatchIn(tx)) {
                    solucion = l; encontrada = true
                    return tx.replace(regexMarcaInline, "").trim()
                }
                return tx
            }
            cA = limpiar(cA, "a"); cB = limpiar(cB, "b"); cC = limpiar(cC, "c"); cD =
                limpiar(cD, "d")

            // Nivel 2: Línea final (ej: "Solución: b" al final del bloque)
            if (!encontrada) {
                val matchFinal = regexLineaFinal.find(bloque)
                if (matchFinal != null) {
                    solucion = matchFinal.groupValues[3].lowercase()
                    encontrada = true
                    // Limpiar la frase de la última opción (normalmente D o C)
                    val basura = matchFinal.value
                    if (cD.contains(basura)) cD = cD.replace(basura, "", ignoreCase = true).trim()
                    if (cC.contains(basura)) cC = cC.replace(basura, "", ignoreCase = true).trim()
                }
            }

            // Nivel 3: Tabla externa (Leída en el paso A)
            if (!encontrada && mapaRespuestas.containsKey(numeroPregunta)) {
                solucion = mapaRespuestas[numeroPregunta] ?: "a"
            }

            if (cA.isNotBlank() && cB.isNotBlank()) {
                lista.add(Pregunta(enunciado, cA, cB, cC, cD, solucion))
            }
        }
        return lista
    }

    // --- C. PARSER IA (Etiquetas ###) ---
    fun parsearSalidaIA_Etiquetas(texto: String): List<Pregunta> {
        val lista = mutableListOf<Pregunta>()
        val bloques = texto.split(Regex("###\\s*PREGUNTA\\s*###", RegexOption.IGNORE_CASE))
        for (bloque in bloques) {
            if (bloque.isBlank()) continue
            val enun = extraerTag(bloque, "ENUNCIADO")
            val a = extraerTag(bloque, "OPCION_A")
            val b = extraerTag(bloque, "OPCION_B")
            val c = extraerTag(bloque, "OPCION_C")
            val d = extraerTag(bloque, "OPCION_D")
            var sol = extraerTag(bloque, "SOLUCION").lowercase().take(1)

            if (sol !in "abcd") sol = "a"
            if (enun.isNotBlank() && a.isNotBlank()) lista.add(Pregunta(enun, a, b, c, d, sol))
        }
        return lista
    }

    private fun extraerTag(tx: String, tag: String) =
        Regex(
            "$tag:\\s*(.*?)(?=$|\\n[A-Z_]+:)",
            RegexOption.DOT_MATCHES_ALL
        ).find(tx)?.groupValues?.get(1)?.trim() ?: ""

    // Alias para compatibilidad con código antiguo
    fun parsearSalidaIA(t: String) = parsearTexto(t)
}