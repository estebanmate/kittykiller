package com.emtp.kittykiller

object QuizRepository {
    var preguntas: List<Pregunta> = emptyList()
    var indiceActual: Int = 0
    var esModoTeoria: Boolean = false
    var nombreArchivoOriginal: String = "test_sin_nombre" // Nueva variable

    fun reiniciar() {
        indiceActual = 0
        // No reiniciamos el nombre aquí para no perder la referencia
    }
}