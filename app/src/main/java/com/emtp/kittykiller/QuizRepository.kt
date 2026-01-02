package com.emtp.kittykiller

object QuizRepository {
    var preguntas: List<Pregunta> = emptyList()
    var indiceActual: Int = 0
    var esModoTeoria: Boolean = false
    var nombreArchivoOriginal: String = "test_sin_nombre"

    // NUEVA VARIABLE: Para pasar el texto limpio al reproductor
    var textoTeoriaParaAudio: String = ""

    fun reiniciar() {
        indiceActual = 0
        // No reiniciamos el nombre ni el texto de audio aquí
    }
}