package com.emtp.kittykiller

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PreguntaActivity : AppCompatActivity() {

    // Header
    private lateinit var btnGuardarSalir: Button
    private lateinit var tvContadorHeader: TextView
    private lateinit var btnSaltar: Button

    // Contenido
    private lateinit var tvEnunciado: TextView
    private lateinit var btnOpA: Button
    private lateinit var btnOpB: Button
    private lateinit var btnOpC: Button
    private lateinit var btnOpD: Button
    private lateinit var btnSiguiente: Button
    private lateinit var tvFeedback: TextView

    private lateinit var preguntaActual: Pregunta

    // ESTADÍSTICAS DE LA SESIÓN
    private var aciertos = 0
    private var preguntasFalladas = mutableListOf<Pregunta>()
    private var haRespondido = false // Para bloquear doble click

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pregunta)

        // Vincular vistas
        btnGuardarSalir = findViewById(R.id.btnGuardarSalir)
        tvContadorHeader = findViewById(R.id.tvContadorHeader)
        btnSaltar = findViewById(R.id.btnSaltar)

        tvEnunciado = findViewById(R.id.tvEnunciado)
        btnOpA = findViewById(R.id.btnOpA)
        btnOpB = findViewById(R.id.btnOpB)
        btnOpC = findViewById(R.id.btnOpC)
        btnOpD = findViewById(R.id.btnOpD)
        btnSiguiente = findViewById(R.id.btnSiguiente)
        tvFeedback = findViewById(R.id.tvFeedback)

        val opciones = listOf(btnOpA, btnOpB, btnOpC, btnOpD)

        // Listeners de Opciones
        opciones.forEach { btn ->
            btn.setOnClickListener { validarRespuesta(btn) }
        }

        // Botón Guardar y Salir
        btnGuardarSalir.setOnClickListener {
            GestorPersistencia.guardarProgreso(this)
            Toast.makeText(this, "Progreso guardado", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Botón Saltar
        btnSaltar.setOnClickListener {
            // Si salta, cuenta como fallo o neutro.
            // En este caso, la añadimos a falladas para poder repasarla luego.
            if (!haRespondido) {
                preguntasFalladas.add(preguntaActual)
                avanzarPregunta()
            }
        }

        // El botón siguiente ahora es manual (por si alguien no quiere esperar los 3.5s)
        btnSiguiente.setOnClickListener {
            if (haRespondido) avanzarPregunta()
        }

        // Inicializar contadores si es el inicio del test (índice 0)
        if (QuizRepository.indiceActual == 0) {
            aciertos = 0
            preguntasFalladas.clear()
        }

        cargarPregunta()
    }

    private fun cargarPregunta() {
        if (QuizRepository.preguntas.isEmpty()) {
            Toast.makeText(this, "Error: No hay preguntas", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        preguntaActual = QuizRepository.preguntas[QuizRepository.indiceActual]
        haRespondido = false

        // Actualizar Header
        val total = QuizRepository.preguntas.size
        val actual = QuizRepository.indiceActual + 1
        tvContadorHeader.text = "$actual / $total"

        // Actualizar Textos
        tvEnunciado.text = preguntaActual.enunciado
        btnOpA.text = "A) ${preguntaActual.opcionA}"
        btnOpB.text = "B) ${preguntaActual.opcionB}"
        btnOpC.text = "C) ${preguntaActual.opcionC}"
        btnOpD.text = "D) ${preguntaActual.opcionD}"

        // Resetear UI
        resetearEstilosBotones()
        btnSiguiente.visibility = View.INVISIBLE
        tvFeedback.visibility = View.INVISIBLE
        btnSaltar.isEnabled = true

        // Desbloquear botones
        listOf(btnOpA, btnOpB, btnOpC, btnOpD).forEach { it.isEnabled = true }
    }

    private fun validarRespuesta(botonSeleccionado: Button) {
        if (haRespondido) return
        haRespondido = true

        bloquearBotones() // Evitar pulsar otra mientras esperamos

        val letraSeleccionada = when (botonSeleccionado.id) {
            R.id.btnOpA -> "a"
            R.id.btnOpB -> "b"
            R.id.btnOpC -> "c"
            R.id.btnOpD -> "d"
            else -> ""
        }

        val esCorrecta =
            letraSeleccionada.equals(preguntaActual.respuestaCorrecta.trim(), ignoreCase = true)

        if (esCorrecta) {
            // --- ACIERTO ---
            aciertos++
            pintarBoton(botonSeleccionado, true)
            mostrarFeedback("¡CORRECTO!", true)
        } else {
            // --- FALLO ---
            preguntasFalladas.add(preguntaActual)
            pintarBoton(botonSeleccionado, false) // Rojo al seleccionado

            // Marcar la correcta en verde para que aprenda
            resaltarRespuestaCorrecta()
            mostrarFeedback("Incorrecto", false)
        }

        // Programar avance automático en 3.5 segundos
        btnSiguiente.visibility = View.VISIBLE // Aparece por si quiere ir más rápido
        btnSaltar.isEnabled = false

        lifecycleScope.launch {
            delay(3500) // Espera de 3.5 segundos
            // Verificamos que la actividad siga viva antes de avanzar
            if (!isFinishing && !isDestroyed) {
                avanzarPregunta()
            }
        }
    }

    private fun avanzarPregunta() {
        if (QuizRepository.indiceActual < QuizRepository.preguntas.size - 1) {
            QuizRepository.indiceActual++
            cargarPregunta()
        } else {
            mostrarResultadosFinales()
        }
    }

    private fun mostrarResultadosFinales() {
        val total = QuizRepository.preguntas.size
        val fallos = preguntasFalladas.size

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Examen Finalizado")
        builder.setMessage(
            "Estadísticas:\n\n" +
                    "✅ Aciertos: $aciertos\n" +
                    "❌ Fallos/Saltos: $fallos\n" +
                    "Total: $total"
        )

        builder.setPositiveButton("Salir") { _, _ ->
            finish()
        }

        // Si hay fallos, ofrecemos reintentar
        if (fallos > 0) {
            builder.setNeutralButton("Reintentar Fallos") { _, _ ->
                reintentarFalladas()
            }
        }

        builder.setCancelable(false) // Obligar a elegir
        builder.show()
    }

    private fun reintentarFalladas() {
        // Cargar solo las preguntas falladas en el repositorio
        QuizRepository.preguntas = preguntasFalladas.toList() // Copia
        QuizRepository.reiniciar() // Índice a 0

        // Reiniciar contadores locales
        aciertos = 0
        preguntasFalladas.clear()

        Toast.makeText(
            this,
            "Repasando ${QuizRepository.preguntas.size} preguntas falladas",
            Toast.LENGTH_SHORT
        ).show()
        cargarPregunta() // Recargar actividad con la nueva lista
    }

    // --- UTILS VISUALES ---

    private fun pintarBoton(btn: Button, esAcierto: Boolean) {
        if (esAcierto) {
            btn.setBackgroundColor(Color.parseColor("#4CAF50")) // Verde Material
            btn.setTextColor(Color.WHITE)
        } else {
            btn.setBackgroundColor(Color.parseColor("#F44336")) // Rojo Material
            btn.setTextColor(Color.WHITE)
        }
    }

    private fun resaltarRespuestaCorrecta() {
        // Busca cuál botón tiene la letra correcta y lo pinta de verde
        val letraCorrecta = preguntaActual.respuestaCorrecta.trim().lowercase()
        val botonCorrecto = when (letraCorrecta) {
            "a" -> btnOpA
            "b" -> btnOpB
            "c" -> btnOpC
            "d" -> btnOpD
            else -> null
        }
        botonCorrecto?.let { pintarBoton(it, true) }
    }

    private fun mostrarFeedback(texto: String, positivo: Boolean) {
        tvFeedback.text = texto
        tvFeedback.setTextColor(if (positivo) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        tvFeedback.visibility = View.VISIBLE
    }

    private fun resetearEstilosBotones() {
        val botones = listOf(btnOpA, btnOpB, btnOpC, btnOpD)
        botones.forEach {
            it.setBackgroundColor(Color.WHITE) // O el color de fondo por defecto de tu tema
            it.setTextColor(Color.BLACK)
        }
    }

    private fun bloquearBotones() {
        listOf(btnOpA, btnOpB, btnOpC, btnOpD).forEach { it.isEnabled = false }
    }
}