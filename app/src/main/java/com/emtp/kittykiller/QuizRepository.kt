package com.emtp.kittykiller

import android.content.Context

data class SesionGuardada(
    val nombreArchivo: String,
    val indice: Int,
    val aciertos: Int, // Placeholder as GestorPersistencia doesn't seem to save aciertos/fallos yet
    val fallos: Int,   // Placeholder
    val preguntas: List<Pregunta>
)

object QuizRepository {
    var preguntas: List<Pregunta> = emptyList()
    var indiceActual: Int = 0
    var esModoTeoria: Boolean = false
    var nombreArchivoOriginal: String = "test_sin_nombre"

    // Backing properties for compatibility with MainActivity usages
    var preguntasActuales: List<Pregunta>
        get() = preguntas
        set(value) { preguntas = value }

    var textoTeoriaParaAudio: String = ""

    // Alias for MainActivity compatibility
    var textoTeoriaActual: String
        get() = textoTeoriaParaAudio
        set(value) { textoTeoriaParaAudio = value }

    // Context reference if needed, though usually repositories shouldn't hold context.
    // MainActivity calls inicializar(this), so we add it.
    private var context: Context? = null

    fun inicializar(context: Context) {
        this.context = context.applicationContext
    }

    fun reiniciar() {
        indiceActual = 0
        // No reiniciamos el nombre ni el texto de audio aquí
    }

    // Proxy to GestorPersistencia to satisfy MainActivity calls
    fun obtenerSesionesGuardadas(): List<SesionGuardada> {
        val ctx = context ?: return emptyList()
        val nombres = GestorPersistencia.obtenerListaTests(ctx)
        val sesiones = mutableListOf<SesionGuardada>()

        // We need to load each session to get details. This might be slow but it's what was requested.
        // However, GestorPersistencia only exposes names via obtenerListaTests.
        // And `cargarProgreso` loads into the Repository directly.
        // We need a way to peek at the data without overwriting the repository state,
        // OR we just return basic info.

        // MainActivity expects: nombreArchivo, indice, aciertos, fallos, preguntas.
        // GestorPersistencia stores: preguntas, indiceActual, nombreArchivo.
        // Aciertos/Fallos are NOT stored in EstadoExamen in GestorPersistencia currently!

        // We will just return the names and load the rest if possible, or refactor GestorPersistencia.
        // For now, let's try to reconstruct what we can.

        // Note: GestorPersistencia.obtenerListaTests only returns names.
        // To get details we need to read the prefs.
        val sharedPreferences = ctx.getSharedPreferences("OposicionesPrefs", Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val tipo = object : com.google.gson.reflect.TypeToken<EstadoExamen>() {}.type

        for (nombre in nombres) {
            val json = sharedPreferences.getString("EXAMEN_$nombre", null)
            if (json != null) {
                try {
                    val estado: EstadoExamen = gson.fromJson(json, tipo)
                    sesiones.add(SesionGuardada(
                        nombreArchivo = estado.nombreArchivo,
                        indice = estado.indiceActual,
                        aciertos = 0, // Not saved
                        fallos = 0,   // Not saved
                        preguntas = estado.preguntas
                    ))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return sesiones
    }
}
