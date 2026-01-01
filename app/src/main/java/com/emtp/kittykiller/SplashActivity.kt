package com.emtp.kittykiller

import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val tvEstado = findViewById<TextView>(R.id.tvEstadoSplash)
        val progressBar = findViewById<ProgressBar>(R.id.progressBarSplash)

        lifecycleScope.launch {
            // PASO 1: Descargar si no existe
            val descargaOk =
                GestorDescargas.descargarModeloSiNoExiste(this@SplashActivity) { progreso ->
                    tvEstado.text = "Descargando modelo IA: $progreso%"
                    progressBar.progress = progreso
                }

            if (!descargaOk) {
                tvEstado.text = "Error de conexión. Reinicia la app."
                return@launch
            }

            // PASO 2: Cargar en Memoria (Esto tarda unos segundos)
            tvEstado.text = "Cargando motor neuronal en RAM..."
            progressBar.isIndeterminate = true // Barra infinita

            val cargaOk = MotorIA.inicializar(applicationContext)

            if (cargaOk) {
                // Todo listo, vamos al menú
                startActivity(Intent(this@SplashActivity, MenuActivity::class.java))
                finish() // Cerramos el Splash para no volver atrás
            } else {
                tvEstado.text = "Error: Tu móvil no soporta esta IA."
            }
        }
    }
}