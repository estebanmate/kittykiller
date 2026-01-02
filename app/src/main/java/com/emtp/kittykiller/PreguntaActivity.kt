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
    private lateinit var tvFeedback: TextView

    private lateinit var preguntaActual: Pregunta

    // ESTADÍSTICAS DE LA SESIÓN: Now using QuizRepository directly
    // private var aciertos = 0 // Removed local state
    // private var preguntasFalladas = mutableListOf<Pregunta>() // Removed local state

    // Control de estado de la pregunta actual
    private var falloEnPreguntaActual = false // Para contar solo 1 fallo
    private var haAcertado = false // Para bloquear interacciones tras acertar

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
        tvFeedback = findViewById(R.id.tvFeedback)

        val opciones = listOf(btnOpA, btnOpB, btnOpC, btnOpD)

        // Listeners de Opciones
        opciones.forEach { btn ->
            btn.setOnClickListener { validarRespuesta(btn) }
        }

        // Botón Guardar y Salir
        btnGuardarSalir.setOnClickListener {
            // Stats are in QuizRepository, so GestorPersistencia will pick them up
            GestorPersistencia.guardarProgreso(this)
            Toast.makeText(this, "Progreso guardado", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Botón Saltar
        btnSaltar.setOnClickListener {
            if (!haAcertado) {
                // Si salta, cuenta como fallo
                if (!falloEnPreguntaActual) {
                    QuizRepository.preguntasFalladas.add(preguntaActual)
                }
                avanzarPregunta()
            }
        }

        // Note: We do NOT reset stats here anymore.
        // Logic for reset happens in MainActivity when starting a NEW exam,
        // or implicitly when loading an existing one (which overwrites them).

        cargarPregunta()
    }

    private fun cargarPregunta() {
        if (QuizRepository.preguntas.isEmpty()) {
            Toast.makeText(this, "Error: No hay preguntas", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        preguntaActual = QuizRepository.preguntas[QuizRepository.indiceActual]

        // Reseteamos estados para la nueva pregunta
        falloEnPreguntaActual = false
        haAcertado = false

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
        tvFeedback.visibility = View.INVISIBLE
        btnSaltar.isEnabled = true

        // Habilitar todos los botones
        listOf(btnOpA, btnOpB, btnOpC, btnOpD).forEach { it.isEnabled = true }
    }

    private fun validarRespuesta(botonSeleccionado: Button) {
        if (haAcertado) return // Evitar pulsaciones si ya acertó y está esperando

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
            haAcertado = true

            // Solo sumamos acierto si acertó a la primera (sin fallos previos en esta pregunta)
            // Opcional: Si prefieres que cuente siempre, quita el 'if'.
            if (!falloEnPreguntaActual) {
                QuizRepository.aciertos++
            }

            pintarBoton(botonSeleccionado, true)
            mostrarFeedback("¡CORRECTO!", true)

            // Bloqueamos todo para que espere
            bloquearBotones()
            btnSaltar.isEnabled = false

            // Pausa de 3.5 segundos y avance automático
            lifecycleScope.launch {
                delay(3500)
                if (!isFinishing && !isDestroyed) {
                    avanzarPregunta()
                }
            }

        } else {
            // --- FALLO ---
            pintarBoton(botonSeleccionado, false) // Rojo al fallado
            botonSeleccionado.isEnabled = false   // Desactivamos ESE botón

            mostrarFeedback("Incorrecto, prueba otra vez", false)

            // Registramos el fallo solo la primera vez que se equivoca en esta pregunta
            if (!falloEnPreguntaActual) {
                QuizRepository.preguntasFalladas.add(preguntaActual)
                falloEnPreguntaActual = true
            }

            // NO AVANZAMOS: El usuario debe seguir intentándolo
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
        val fallos = QuizRepository.preguntasFalladas.size

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Examen Finalizado")
        builder.setMessage(
            "Estadísticas:\n\n" +
                    "✅ Aciertos (a la primera): ${QuizRepository.aciertos}\n" +
                    "❌ Preguntas con fallos: $fallos\n" +
                    "Total: $total"
        )

        builder.setPositiveButton("Salir") { _, _ ->
            finish()
        }

        if (fallos > 0) {
            builder.setNeutralButton("Reintentar Fallos") { _, _ ->
                reintentarFalladas()
            }
        }

        builder.setCancelable(false)
        builder.show()
    }

    private fun reintentarFalladas() {
        // Here we modify the repository state for the retry session
        QuizRepository.preguntas = QuizRepository.preguntasFalladas.toList()
        QuizRepository.reiniciar() // Resets index, aciertos, and falladas
        cargarPregunta()
        Toast.makeText(this, "Repasando errores", Toast.LENGTH_SHORT).show()
    }

    // --- UTILS VISUALES ---

    private fun pintarBoton(btn: Button, esAcierto: Boolean) {
        if (esAcierto) {
            btn.setBackgroundColor(Color.parseColor("#4CAF50")) // Verde
            btn.setTextColor(Color.WHITE)
        } else {
            btn.setBackgroundColor(Color.parseColor("#F44336")) // Rojo
            btn.setTextColor(Color.WHITE)
        }
    }

    private fun mostrarFeedback(texto: String, positivo: Boolean) {
        tvFeedback.text = texto
        tvFeedback.setTextColor(if (positivo) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        tvFeedback.visibility = View.VISIBLE
    }

    private fun resetearEstilosBotones() {
        val botones = listOf(btnOpA, btnOpB, btnOpC, btnOpD)
        botones.forEach {
            it.setBackgroundColor(Color.WHITE)
            it.setTextColor(Color.BLACK)
        }
    }

    private fun bloquearBotones() {
        listOf(btnOpA, btnOpB, btnOpC, btnOpD).forEach { it.isEnabled = false }
    }
}
