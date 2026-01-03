package com.emtp.kittykiller

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emtp.kittykiller.databinding.ActivityPreguntaBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PreguntaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreguntaBinding
    private lateinit var preguntaActual: Pregunta

    private var falloEnPreguntaActual = false
    private var haAcertado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreguntaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configuración de Opciones
        val opciones =
            listOf(binding.btnOpcionA, binding.btnOpcionB, binding.btnOpcionC, binding.btnOpcionD)
        opciones.forEach { btn ->
            btn.setOnClickListener { validarRespuesta(btn) }
        }

        // Botón SALTAR (Ahora disponible y visible)
        binding.btnSaltar.setOnClickListener {
            if (!haAcertado) {
                // Saltar cuenta como fallo si no se ha intentado
                if (!falloEnPreguntaActual) {
                    QuizRepository.preguntasFalladas.add(preguntaActual)
                }
                avanzarPregunta()
            }
        }

        // Botón GUARDAR Y SALIR
        binding.btnGuardarSalir.setOnClickListener {
            mostrarDialogoGuardar()
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
        falloEnPreguntaActual = false
        haAcertado = false

        // UI Header
        val total = QuizRepository.preguntas.size
        val actual = QuizRepository.indiceActual + 1
        binding.tvContador.text = "Pregunta $actual / $total"

        // UI Textos
        binding.tvEnunciado.text = preguntaActual.enunciado
        binding.btnOpcionA.text = "A) ${preguntaActual.opcionA}"
        binding.btnOpcionB.text = "B) ${preguntaActual.opcionB}"
        binding.btnOpcionC.text = "C) ${preguntaActual.opcionC}"
        binding.btnOpcionD.text = "D) ${preguntaActual.opcionD}"

        resetearEstilosBotones()
        habilitarBotones(true)
        binding.btnSaltar.isEnabled = true
    }

    private fun validarRespuesta(botonSeleccionado: Button) {
        if (haAcertado) return

        val letraSeleccionada = when (botonSeleccionado.id) {
            binding.btnOpcionA.id -> "a"
            binding.btnOpcionB.id -> "b"
            binding.btnOpcionC.id -> "c"
            binding.btnOpcionD.id -> "d"
            else -> ""
        }

        val esCorrecta = letraSeleccionada.equals(preguntaActual.solucion.trim(), ignoreCase = true)

        if (esCorrecta) {
            haAcertado = true
            if (!falloEnPreguntaActual) QuizRepository.aciertos++

            pintarBoton(botonSeleccionado, true)
            habilitarBotones(false)
            binding.btnSaltar.isEnabled = false // No saltar si ya acertó

            lifecycleScope.launch {
                delay(2000) // 2 segundos de pausa
                if (!isFinishing) avanzarPregunta()
            }
        } else {
            pintarBoton(botonSeleccionado, false)
            botonSeleccionado.isEnabled = false
            if (!falloEnPreguntaActual) {
                QuizRepository.preguntasFalladas.add(preguntaActual)
                falloEnPreguntaActual = true
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
        val fallos = QuizRepository.preguntasFalladas.size
        AlertDialog.Builder(this)
            .setTitle("Examen Finalizado")
            .setMessage("Resultados:\n✅ Aciertos: ${QuizRepository.aciertos}\n❌ Fallos: $fallos\nTotal: $total")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNeutralButton("Repasar Fallos") { _, _ ->
                if (fallos > 0) reintentarFalladas() else finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun reintentarFalladas() {
        QuizRepository.preguntas = QuizRepository.preguntasFalladas.toList()
        QuizRepository.reiniciar()
        cargarPregunta()
    }

    private fun pintarBoton(btn: Button, esAcierto: Boolean) {
        if (esAcierto) {
            btn.setBackgroundColor(Color.parseColor("#4CAF50")) // Verde sólido
            btn.setTextColor(Color.WHITE)
        } else {
            btn.setBackgroundColor(Color.parseColor("#F44336")) // Rojo sólido
            btn.setTextColor(Color.WHITE)
        }
    }

    private fun resetearEstilosBotones() {
        val botones =
            listOf(binding.btnOpcionA, binding.btnOpcionB, binding.btnOpcionC, binding.btnOpcionD)
        botones.forEach {
            it.setBackgroundColor(Color.WHITE)
            it.setTextColor(Color.BLACK)
            it.isEnabled = true
        }
    }

    private fun habilitarBotones(habilitar: Boolean) {
        listOf(
            binding.btnOpcionA,
            binding.btnOpcionB,
            binding.btnOpcionC,
            binding.btnOpcionD
        ).forEach {
            it.isEnabled = habilitar
        }
    }

    private fun mostrarDialogoGuardar() {
        val nombreArchivo = QuizRepository.nombreArchivoOriginal
        val preguntaActual = QuizRepository.indiceActual + 1
        val totalPreguntas = QuizRepository.preguntas.size

        AlertDialog.Builder(this)
            .setTitle("Guardar Progreso")
            .setMessage(
                "¿Deseas guardar el progreso del test?\n\n" +
                "📄 Archivo: $nombreArchivo\n" +
                "📊 Progreso: $preguntaActual/$totalPreguntas preguntas\n" +
                "✅ Aciertos: ${QuizRepository.aciertos}\n" +
                "❌ Fallos: ${QuizRepository.preguntasFalladas.size}"
            )
            .setPositiveButton("Guardar") { _, _ ->
                GestorPersistencia.guardarProgreso(this)
                Toast.makeText(
                    this,
                    "Test guardado como: $nombreArchivo",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
            .setNegativeButton("Salir sin guardar") { _, _ ->
                finish()
            }
            .setNeutralButton("Cancelar", null)
            .show()
    }
}