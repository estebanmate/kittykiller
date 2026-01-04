package com.emtp.kittykiller

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    // Variables temporales para mantener estado durante el flujo de selección
    private var tempModoApp: String = ""
    private var tempEsNube: Boolean = false
    private var tempCantidad: Int = 20
    private var tempTipoDoc: TipoDoc = TipoDoc.TEST

    private val seleccionTextoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val textoSeleccionado = result.data?.getStringExtra("TEXTO_SELECCIONADO")
                if (!textoSeleccionado.isNullOrBlank()) {
                    // Continuar con el texto seleccionado
                    ejecutarLogicaNegocio(
                        textoSeleccionado,
                        tempModoApp,
                        tempEsNube,
                        tempCantidad,
                        tempTipoDoc
                    )
                } else {
                    terminarConError("Error: No se recibió texto seleccionado.")
                }
            } else {
                // Si cancela, volvemos al menú o cerramos (opción de diseño).
                // Aquí optamos por cerrar con mensaje.
                terminarConError("Selección de texto cancelada.")
            }
        }

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
                // Guardar estado temporal
                tempModoApp = modoApp
                tempEsNube = esNube
                tempCantidad = cantidad
                tempTipoDoc = tipoDetectado

                // Paso B: Decidir si mostrar selector o seguir directo
                if (modoApp == "TEORIA") {
                    mostrarDialogoOpcionSeleccion(texto)
                } else {
                    ejecutarLogicaNegocio(texto, modoApp, esNube, cantidad, tipoDetectado)
                }
            },
            onError = { error -> terminarConError(error) },
            onProgress = { msg -> actualizarEstado(msg) } // GestorDocumentos ahora reporta progreso
        )
    }

    private fun mostrarDialogoOpcionSeleccion(textoCompleto: String) {
        AlertDialog.Builder(this)
            .setTitle("Opciones de Procesamiento")
            .setMessage("¿Deseas generar preguntas de TODO el documento o seleccionar una parte específica?")
            .setPositiveButton("Todo el documento") { _, _ ->
                ejecutarLogicaNegocio(
                    textoCompleto,
                    tempModoApp,
                    tempEsNube,
                    tempCantidad,
                    tempTipoDoc
                )
            }
            .setNeutralButton("Seleccionar texto") { _, _ ->
                launchTextSelection(textoCompleto)
            }
            .setCancelable(false) // Obligar a elegir
            .show()
    }

    private fun launchTextSelection(texto: String) {
        val intent = Intent(this, TextSelectionActivity::class.java).apply {
            putExtra("TEXTO_COMPLETO", texto)
            putExtra("CANTIDAD_PREGUNTAS", tempCantidad)
        }
        seleccionTextoLauncher.launch(intent)
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

                // Forzamos el tipo según lo que el usuario eligió en el menú
                val tipoDocFinal = if (modoApp == "TEST") TipoDoc.TEST else TipoDoc.TEORIA

                // LÓGICA DE RESTRICCIÓN: Si es un Test/Examen, SIEMPRE usar local (ignorar switch de nube)
                val modoEjecucion =
                    if (esNube && tipoDocFinal == TipoDoc.TEORIA) ModoEjecucion.NUBE else ModoEjecucion.LOCAL

                actualizarEstado(if (esNube) "Consultando a Gemini..." else "Procesando localmente...")

                // Llamada bloqueante al repositorio
                val preguntas =
                    generadorTests.generarTest(
                        texto,
                        tipoDocFinal,
                        modoEjecucion,
                        cantidad
                    ) { msg ->
                        actualizarEstado(msg)
                    }

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