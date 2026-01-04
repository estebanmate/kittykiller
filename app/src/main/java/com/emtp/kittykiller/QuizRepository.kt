package com.emtp.kittykiller

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SesionGuardada(
    val nombreArchivo: String,
    val indice: Int,
    val aciertos: Int,
    val fallos: Int,
    val preguntas: List<Pregunta>
)

object QuizRepository {
    var preguntas: List<Pregunta> = emptyList()
    var indiceActual: Int = 0
    var esModoTeoria: Boolean = false
    var nombreArchivoOriginal: String = "test_sin_nombre"

    // New statistics properties
    var aciertos: Int = 0
    var preguntasFalladas: MutableList<Pregunta> = mutableListOf()

    // Backing properties for compatibility with MainActivity usages
    var preguntasActuales: List<Pregunta>
        get() = preguntas
        set(value) { preguntas = value }

    var textoTeoriaParaAudio: String = ""

    // Alias for MainActivity compatibility
    var textoTeoriaActual: String
        get() = textoTeoriaParaAudio
        set(value) { textoTeoriaParaAudio = value }

    private var context: Context? = null

    fun inicializar(context: Context) {
        this.context = context.applicationContext
    }

    fun reiniciar() {
        indiceActual = 0
        aciertos = 0
        preguntasFalladas.clear()
        // No reiniciamos el nombre ni el texto de audio aquí
    }

    // Proxy to GestorPersistencia to satisfy MainActivity calls
    fun obtenerSesionesGuardadas(): List<SesionGuardada> {
        val ctx = context ?: return emptyList()
        val nombres = GestorPersistencia.obtenerListaTests(ctx)
        val sesiones = mutableListOf<SesionGuardada>()

        val sharedPreferences = ctx.getSharedPreferences("OposicionesPrefs", Context.MODE_PRIVATE)
        val gson = Gson()
        // We use the Type for EstadoExamen.
        // Note: We need to reference the EstadoExamen class.
        // Since it's in GestorPersistencia.kt, we assume it's accessible.
        // However, EstadoExamen is defined in GestorPersistencia.kt file, but outside the object?
        // Let's check imports. It's in the same package.
        val tipo = object : TypeToken<EstadoExamen>() {}.type

        for (nombre in nombres) {
            val json = sharedPreferences.getString("EXAMEN_$nombre", null)
            if (json != null) {
                try {
                    val estado: EstadoExamen = gson.fromJson(json, tipo)
                    sesiones.add(SesionGuardada(
                        nombreArchivo = estado.nombreArchivo,
                        indice = estado.indiceActual,
                        aciertos = estado.aciertos,
                        fallos = estado.preguntasFalladas.size,
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
