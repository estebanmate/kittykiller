package com.emtp.kittykiller.data

import com.emtp.kittykiller.Pregunta
import com.google.gson.annotations.SerializedName

// 1. El DTO que coincide con tu JSON de la Cloud Function
data class CloudResponse(
    val success: Boolean,
    val data: List<CloudPreguntaDto>?
)

data class CloudPreguntaDto(
    @SerializedName("id") val id: Int,
    @SerializedName("pregunta") val pregunta: String,
    @SerializedName("opciones") val opciones: List<String>, // Ej: ["A) Londres", "B) París"]
    @SerializedName("respuesta_correcta") val respuestaCorrecta: String // Ej: "A" o "B"
)

// 2. El Mapper (Traductor) a tu modelo de negocio
fun CloudPreguntaDto.toPreguntaDominio(): Pregunta {
    // Función auxiliar para limpiar "A) ", "1. ", etc.
    fun limpiarOpcion(texto: String): String {
        return texto.replace(Regex("^[A-D][\\.\\)]\\s*", RegexOption.IGNORE_CASE), "").trim()
    }

    val opA = opciones.getOrElse(0) { "" }.let { limpiarOpcion(it) }
    val opB = opciones.getOrElse(1) { "" }.let { limpiarOpcion(it) }
    val opC = opciones.getOrElse(2) { "" }.let { limpiarOpcion(it) }
    val opD = opciones.getOrElse(3) { "" }.let { limpiarOpcion(it) }

    // Lista de opciones limpias para buscar coincidencia por texto
    val opcionesLimpias = listOf(opA, opB, opC, opD)

    return Pregunta(
        enunciado = this.pregunta,
        opcionA = opA,
        opcionB = opB,
        opcionC = opC,
        opcionD = opD,
        solucion = this.respuestaCorrecta.let { raw ->
            val rawTrimmed = raw.trim()

            // 1. Intentar buscar coincidencia exacta por TEXTO
            // Si la nube devuelve "El paciente" y eso coincide con la Opción B, devolvemos "b"
            val indexCoincidencia = opcionesLimpias.indexOfFirst {
                it.equals(rawTrimmed, ignoreCase = true) || rawTrimmed.contains(
                    it,
                    ignoreCase = true
                )
            }

            if (indexCoincidencia != -1) {
                return@let when (indexCoincidencia) {
                    0 -> "a"
                    1 -> "b"
                    2 -> "c"
                    3 -> "d"
                    else -> "a"
                }
            }

            // 2. Si no es texto completo, usamos la lógica original de extracción de LETRA
            val regexLetra = Regex("^[a-dA-D]$")
            if (rawTrimmed.matches(regexLetra)) {
                rawTrimmed.lowercase()
            } else {
                val matchExplicit = Regex(
                    "(?:Opción|Opcion|Respuesta|Solución|Solucion|Correcta)[:\\s]+([a-dA-D])",
                    RegexOption.IGNORE_CASE
                ).find(raw)
                if (matchExplicit != null) {
                    matchExplicit.groupValues[1].lowercase()
                } else {
                    val matchPunt = Regex("([a-dA-D])([).:]|$)").find(raw)
                        ?: Regex("(?:^|\\s)([a-dA-D])(?:$|\\s)").find(raw)

                    // Si falla todo, por desgracia devolvía "a".
                    // Podrías poner un log aquí para depurar qué está llegando realmente.
                    matchPunt?.groupValues?.get(1)?.lowercase() ?: "a"
                }
            }
        }
    )
}