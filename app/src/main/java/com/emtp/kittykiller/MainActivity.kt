package com.emtp.kittykiller

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emtp.kittykiller.ProcesadorDocumentos.TipoDoc
import com.emtp.kittykiller.repository.GeneradorTestsRepository
import com.emtp.kittykiller.repository.ModoEjecucion
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvEstado: TextView
    private lateinit var gestorDocumentos: GestorDocumentos
    private val generadorTests = GeneradorTestsRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvEstado = findViewById(R.id.tvEstado)
        gestorDocumentos = GestorDocumentos(this)

        // 1. Recuperar datos del Intent
        val uri = intent.data
        val modoApp = intent.getStringExtra("MODO_APP") ?: "TEST"
        val esNube = intent.getBooleanExtra("ES_NUBE", false)
        val cantidad = intent.getIntExtra("CANTIDAD", 20)

        if (uri == null) {
            terminarConError("Error: No se recibió ningún archivo.")
            return
        }

        // 2. Iniciar proceso automático
        procesar(uri, modoApp, esNube, cantidad)
    }

    private fun procesar(uri: android.net.Uri, modoApp: String, esNube: Boolean, cantidad: Int) {
        val nombreArchivo = obtenerNombreArchivo(uri)
        QuizRepository.nombreArchivoOriginal = nombreArchivo

        // Paso A: Leer el archivo (Extracción de texto)
        gestorDocumentos.clasificarYProcesar(
            uri,
            nombreArchivo,
            onResult = { texto, tipoDetectado ->
                // Paso B: Decidir qué hacer con el texto
                ejecutarLogicaNegocio(texto, modoApp, esNube, cantidad, tipoDetectado)
            },
            onError = { error -> terminarConError(error) },
            onProgress = { msg -> actualizarEstado(msg) } // GestorDocumentos ahora reporta progreso
        )
    }

    private fun ejecutarLogicaNegocio(
        texto: String,
        modoApp: String,
        esNube: Boolean,
        cantidad: Int,
        tipoDocDetectado: TipoDoc
    ) {
        lifecycleScope.launch {
            try {
                // MODO AUDIO: Caso especial, no necesita generación
                if (modoApp == "AUDIO") {
                    actualizarEstado("Preparando reproductor...")
                    QuizRepository.textoTeoriaParaAudio = texto
                    launchActivity(AudioActivity::class.java)
                    return@launch
                }

                // MODO TEST / TEORÍA: Usar Repositorio
                val modoEjecucion = if (esNube) ModoEjecucion.NUBE else ModoEjecucion.LOCAL

                // Forzamos el tipo según lo que el usuario eligió en el menú
                val tipoDocFinal = if (modoApp == "TEST") TipoDoc.TEST else TipoDoc.TEORIA

                actualizarEstado(if (esNube) "Consultando a Gemini..." else "Procesando localmente...")

                // Llamada bloqueante al repositorio
                val preguntas =
                    generadorTests.generarTest(texto, tipoDocFinal, modoEjecucion, cantidad)

                if (preguntas.isNotEmpty()) {
                    QuizRepository.preguntas = preguntas
                    QuizRepository.reiniciar()
                    actualizarEstado("¡Finalizado!")

                    launchActivity(PreguntaActivity::class.java)
                } else {
                    terminarConError("No se generaron preguntas válidas.")
                }

            } catch (e: Exception) {
                terminarConError(e.message ?: "Error desconocido")
            }
        }
    }

    private fun launchActivity(clase: Class<*>) {
        val intent = Intent(this, clase)
        startActivity(intent)
        finish() // Cierra esta pantalla intermedia para que "Back" vuelva al Menú, no aquí
    }

    private fun actualizarEstado(mensaje: String) {
        runOnUiThread { tvEstado.text = mensaje }
    }

    private fun terminarConError(mensaje: String) {
        runOnUiThread {
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
            finish() // Vuelve al menú
        }
    }

    private fun obtenerNombreArchivo(uri: android.net.Uri): String {
        var nombre = "desconocido"
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) nombre = it.getString(idx)
            }
        }
        return nombre
    }
}