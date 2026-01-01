package com.emtp.kittykiller

data class Pregunta(
    val enunciado: String,
    val opcionA: String,
    val opcionB: String,
    val opcionC: String,
    val opcionD: String,
    val respuestaCorrecta: String // "a", "b", "c" o "d"
)