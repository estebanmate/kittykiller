package com.emtp.kittykiller

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// Objeto simple que representa lo que vamos a guardar
data class EstadoExamen(
    val preguntas: List<Pregunta>,
    val indiceActual: Int,
    val nombreArchivo: String
)

object GestorPersistencia {
    private const val PREFS_NAME = "OposicionesPrefs"
    private val gson = Gson()

    // Guarda el examen asociado al nombre del archivo
    fun guardarProgreso(context: Context) {
        val estado = EstadoExamen(
            preguntas = QuizRepository.preguntas,
            indiceActual = QuizRepository.indiceActual,
            nombreArchivo = QuizRepository.nombreArchivoOriginal
        )

        val json = gson.toJson(estado)

        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            // Guardamos con el nombre del archivo como CLAVE
            putString("EXAMEN_${QuizRepository.nombreArchivoOriginal}", json)
            // Guardamos también cuál fue el último para el botón "Retomar" rápido
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

            // Restaurar repositorio
            QuizRepository.preguntas = estado.preguntas
            QuizRepository.indiceActual = estado.indiceActual
            QuizRepository.nombreArchivoOriginal = estado.nombreArchivo
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}