package com.emtp.kittykiller

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// Objeto simple que representa lo que vamos a guardar
data class EstadoExamen(
    val preguntas: List<Pregunta>,
    val indiceActual: Int,
    val nombreArchivo: String,
    val aciertos: Int = 0,
    val preguntasFalladas: List<Pregunta> = emptyList()
)

object GestorPersistencia {
    private const val PREFS_NAME = "OposicionesPrefs"
    private val gson = Gson()

    // Guarda el examen asociado al nombre del archivo
    fun guardarProgreso(context: Context) {
        val estado = EstadoExamen(
            preguntas = QuizRepository.preguntas,
            indiceActual = QuizRepository.indiceActual,
            nombreArchivo = QuizRepository.nombreArchivoOriginal,
            aciertos = QuizRepository.aciertos,
            preguntasFalladas = QuizRepository.preguntasFalladas
        )

        val json = gson.toJson(estado)

        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            // Guardamos con el nombre del archivo como CLAVE
            putString("EXAMEN_${QuizRepository.nombreArchivoOriginal}", json)
            // Guardamos también cuál fue el último para tener referencia si fuera necesario
            putString("ULTIMO_EXAMEN", QuizRepository.nombreArchivoOriginal)
            apply()
        }
    }

    // Carga el examen. Si nombreArchivo es null, carga el último guardado.
    fun cargarProgreso(context: Context, nombreArchivo: String? = null): Boolean {
        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Determinar qué clave buscar
        val claveBusqueda = if (nombreArchivo != null) {
            "EXAMEN_$nombreArchivo"
        } else {
            val ultimo = sharedPreferences.getString("ULTIMO_EXAMEN", null) ?: return false
            "EXAMEN_$ultimo"
        }

        val json = sharedPreferences.getString(claveBusqueda, null) ?: return false

        try {
            val tipo = object : TypeToken<EstadoExamen>() {}.type
            val estado: EstadoExamen = gson.fromJson(json, tipo)

            // Restaurar repositorio con el estado guardado
            QuizRepository.preguntas = estado.preguntas
            QuizRepository.indiceActual = estado.indiceActual
            QuizRepository.nombreArchivoOriginal = estado.nombreArchivo

            // Restore stats
            QuizRepository.aciertos = estado.aciertos
            QuizRepository.preguntasFalladas = estado.preguntasFalladas.toMutableList()

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // --- NUEVA FUNCIÓN: Obtener lista de tests guardados ---
    fun obtenerListaTests(context: Context): List<String> {
        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val todosLosDatos = sharedPreferences.all
        val listaNombres = mutableListOf<String>()

        // Recorremos todas las claves guardadas
        for (clave in todosLosDatos.keys) {
            // Filtramos solo las que empiezan por nuestro prefijo de examen
            if (clave.startsWith("EXAMEN_")) {
                // Quitamos el prefijo "EXAMEN_" para mostrar solo el nombre real al usuario
                val nombreReal = clave.removePrefix("EXAMEN_")
                listaNombres.add(nombreReal)
            }
        }
        return listaNombres
    }
}
