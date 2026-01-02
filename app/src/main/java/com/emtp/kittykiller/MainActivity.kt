package com.emtp.kittykiller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var btnCargarArchivo: Button
    private lateinit var btnVerHistorial: Button
    private lateinit var tvEstado: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var gestorDocumentos: GestorDocumentos

    // Selector de archivos
    private val selectorArchivos =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                procesarArchivoSeleccionado(uri)
            } else {
                tvEstado.text = "Selección cancelada"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar vistas
        btnCargarArchivo = findViewById(R.id.btnCargarArchivo)
        btnVerHistorial = findViewById(R.id.btnVerHistorial)
        tvEstado = findViewById(R.id.tvEstado)
        progressBar = findViewById(R.id.progressBar)

        // Inicializar Gestor
        gestorDocumentos = GestorDocumentos(this)

        // Inicializar Motor IA en segundo plano
        lifecycleScope.launch {
            val exito = MotorIA.inicializar(applicationContext)
            if (exito) {
                tvEstado.text = "Sistema listo. Selecciona un archivo."
            } else {
                tvEstado.text = "Nota: IA no disponible (Modelo no encontrado)."
            }
        }

        // Configurar botones
        btnCargarArchivo.setOnClickListener {
            abrirSelectorArchivos()
        }

        btnVerHistorial.setOnClickListener {
            // Aquí podrías abrir una pantalla de historial si la tienes
            Toast.makeText(this, "Historial no implementado aún", Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirSelectorArchivos() {
        // Permitimos PDF y Word
        selectorArchivos.launch(
            arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
    }

    private fun procesarArchivoSeleccionado(uri: Uri) {
        // 1. Obtener nombre del archivo para decidir estrategia
        val nombreArchivo = obtenerNombreArchivo(uri)
        tvEstado.text = "Procesando: $nombreArchivo..."
        progressBar.visibility = View.VISIBLE
        btnCargarArchivo.isEnabled = false

        // 2. Llamada al GestorDocumentos (ACTUALIZADO con onError)
        gestorDocumentos.clasificarYProcesar(
            uri,
            nombreArchivo,
            onResult = { textoExtraido, tipoDoc ->
                // Callback de ÉXITO
                manejarResultadoProcesamiento(textoExtraido, tipoDoc)
            },
            onError = { mensajeError ->
                // Callback de ERROR
                progressBar.visibility = View.GONE
                btnCargarArchivo.isEnabled = true
                tvEstado.text = "Error: $mensajeError"
                Toast.makeText(this, mensajeError, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun manejarResultadoProcesamiento(texto: String, tipo: TipoDoc) {
        lifecycleScope.launch {
            try {
                QuizRepository.reiniciar() // Limpiar preguntas anteriores

                val preguntasGeneradas = if (tipo == TipoDoc.TEST) {
                    // CASO TEST: Usar ParseadorExamenes (Reglas)
                    tvEstado.text = "Analizando estructura del test..."
                    ParseadorExamenes.parsearTexto(texto)
                } else {
                    // CASO TEORÍA: Usar Motor IA (Generativo)
                    tvEstado.text = "Generando preguntas con IA..."
                    val promptSalida = MotorIA.generarPreguntas(texto, 20) // Pedimos 20 preguntas
                    ParseadorExamenes.parsearTexto(promptSalida)
                }

                progressBar.visibility = View.GONE
                btnCargarArchivo.isEnabled = true

                if (preguntasGeneradas.isNotEmpty()) {
                    QuizRepository.preguntas = preguntasGeneradas
                    tvEstado.text = "¡Listo! ${preguntasGeneradas.size} preguntas cargadas."

                    // Iniciar la actividad de preguntas
                    val intent = Intent(this@MainActivity, PreguntaActivity::class.java)
                    startActivity(intent)
                } else {
                    tvEstado.text = "No se pudieron extraer preguntas válidas."
                    Toast.makeText(
                        this@MainActivity,
                        "El documento no parece contener preguntas o formato válido.",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                btnCargarArchivo.isEnabled = true
                tvEstado.text = "Error interno: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    // Utilidad para sacar el nombre del archivo de la URI
    private fun obtenerNombreArchivo(uri: Uri): String {
        var nombre = "desconocido"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    nombre = it.getString(index)
                }
            }
        }
        return nombre
    }
}