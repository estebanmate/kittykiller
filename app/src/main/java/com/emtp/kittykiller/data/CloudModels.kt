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

    // Aseguramos que siempre haya 4 opciones, aunque la IA devuelva menos (rellenando con vacíos)
    val opA = opciones.getOrElse(0) { "" }.let { limpiarOpcion(it) }
    val opB = opciones.getOrElse(1) { "" }.let { limpiarOpcion(it) }
    val opC = opciones.getOrElse(2) { "" }.let { limpiarOpcion(it) }
    val opD = opciones.getOrElse(3) { "" }.let { limpiarOpcion(it) }

    return Pregunta(
        enunciado = this.pregunta,
        opcionA = opA,
        opcionB = opB,
        opcionC = opC,
        opcionD = opD,
        solucion = this.respuestaCorrecta.let { raw ->
            // Buscamos explícitamente una letra aislada o al principio: "A", "a)", "Respuesta: A"
            // Primero intentamos match estricto de letra única
            val regexLetra = Regex("^[a-dA-D]$")
            if (raw.trim().matches(regexLetra)) {
                raw.trim().lowercase()
            } else {
                // Si viene con basura ("A) Madrid", "Respuesta Correcta: B"), buscamos la primera letra válida
                val match = Regex("([a-dA-D])([).:]|$)").find(raw)
                    ?: Regex("(?:^|\\s)([a-dA-D])(?:$|\\s)").find(raw)
                
                // Si encontramos letra, la usamos. Si no, fallback a "a" (o loguear error)
                match?.groupValues?.get(1)?.lowercase() ?: "a"
            }
        }
    )
}