package com.emtp.kittykiller

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.widget.ScrollView

class AudioActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var tvEstadoAudio: TextView
    private lateinit var tvTextoActual: TextView
    private lateinit var scrollViewTexto: ScrollView
    private lateinit var btnPlayPause: Button
    private lateinit var seekBarVelocidad: SeekBar
    private lateinit var btnSalir: Button

    private var listaParrafos: List<String> = emptyList()
    private var rangosParrafos: List<Pair<Int, Int>> = emptyList() // Start, End
    private var textoCompletoMostrado: String = ""
    private var indiceActual = 0
    private var estaReproduciendo = false
    private var velocidad = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        // Inicializar vistas
        tvEstadoAudio = findViewById(R.id.tvEstadoAudio)
        tvTextoActual = findViewById(R.id.tvTextoActual)
        scrollViewTexto = findViewById(R.id.scrollViewTexto)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        seekBarVelocidad = findViewById(R.id.seekBarVelocidad)
        btnSalir = findViewById(R.id.btnSalirAudio)

        val textoCompleto = QuizRepository.textoTeoriaParaAudio
        val titulo = QuizRepository.nombreArchivoOriginal

        tvEstadoAudio.text = "Cargando: $titulo"

    // Dividir texto en párrafos para mejor gestión del TTS
        listaParrafos = textoCompleto.split("\n").filter { it.isNotBlank() }

        // Construir texto completo con estructura
        val sb = StringBuilder()
        val nuevosRangos = mutableListOf<Pair<Int, Int>>()
        var desplazamiento = 0

        listaParrafos.forEach { parrafo ->
            sb.append(parrafo).append("\n\n")
            val fin = desplazamiento + parrafo.length
            nuevosRangos.add(Pair(desplazamiento, fin))
            desplazamiento = fin + 2 // +2 por los \n\n
        }

        textoCompletoMostrado = sb.toString()
        rangosParrafos = nuevosRangos

        // Mostrar texto completo de una vez
        tvTextoActual.text = textoCompletoMostrado

        if (listaParrafos.isEmpty()) {
            Toast.makeText(this, "No se pudo extraer texto leíble", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Configurar TTS
        tts = TextToSpeech(this, this)

        // Controles
        btnPlayPause.setOnClickListener {
            if (estaReproduciendo) pausarAudio() else reproducirParrafoActual()
        }

        btnSalir.setOnClickListener {
            finish()
        }

        // Control de velocidad (0.5x a 2.0x)
        seekBarVelocidad.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Progress 0-10 -> 0.5f a 2.0f (aprox)
                velocidad = 0.5f + (progress / 10f) * 1.5f
                if (estaReproduciendo) {
                    tts.setSpeechRate(velocidad) // Aplicar en caliente si es posible
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val resultado = tts.setLanguage(Locale("es", "ES"))
            if (resultado == TextToSpeech.LANG_MISSING_DATA || resultado == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Idioma español no soportado", Toast.LENGTH_SHORT).show()
            } else {
                tvEstadoAudio.text = "Listo para reproducir"
                btnPlayPause.isEnabled = true

                // Listener para avanzar automáticamente de párrafo
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            if (estaReproduciendo && indiceActual < listaParrafos.size - 1) {
                                indiceActual++
                                reproducirParrafoActual()
                            } else if (indiceActual >= listaParrafos.size - 1) {
                                estaReproduciendo = false
                                btnPlayPause.text = "REPRODUCIR"
                                tvEstadoAudio.text = "Finalizado"
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {}
                })
            }
        } else {
            Toast.makeText(this, "Error al inicializar TTS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reproducirParrafoActual() {
        if (indiceActual < listaParrafos.size) {
            estaReproduciendo = true
            btnPlayPause.text = "PAUSAR"

            val textoParaLeer = listaParrafos[indiceActual]
            // tvTextoActual.text = textoParaLeer // YA NO REEMPLAZAMOS EL TEXTO

            tvEstadoAudio.text = "Leyendo párrafo ${indiceActual + 1} de ${listaParrafos.size}"
            tts.setSpeechRate(velocidad)

            // --- ILUMINAR PÁRRAFO ACTUAL ---
            resaltarParrafoActual()
            // --------------------------------

            // Usamos QUEUE_FLUSH para limpiar lo anterior y hablar ya
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "id_$indiceActual")
            tts.speak(textoParaLeer, TextToSpeech.QUEUE_FLUSH, params, "id_$indiceActual")
        }
    }

    private fun pausarAudio() {
        estaReproduciendo = false
        btnPlayPause.text = "REPRODUCIR"
        tts.stop()
    }

    private fun resaltarParrafoActual() {
        if (indiceActual !in rangosParrafos.indices) return

        val (inicio, fin) = rangosParrafos[indiceActual]
        val spannable = SpannableString(textoCompletoMostrado)

        // Aplicar color de fondo (Amarillo suave o acorde al tema)
        spannable.setSpan(
            BackgroundColorSpan(Color.parseColor("#FFF59D")), // Amarillo claro
            inicio,
            fin,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        tvTextoActual.text = spannable

        // Auto-scroll para mantener el texto visible
        scrollViewTexto.post {
            try {
                val layout = tvTextoActual.layout
                if (layout != null) {
                    val linea = layout.getLineForOffset(inicio)
                    val y = layout.getLineTop(linea)
                    scrollViewTexto.smoothScrollTo(0, y)
                }
            } catch (e: Exception) {
                // Ignorar error de layout
            }
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}