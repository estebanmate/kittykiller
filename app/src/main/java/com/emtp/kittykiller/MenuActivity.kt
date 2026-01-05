package com.emtp.kittykiller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class MenuActivity : AppCompatActivity() {

    // Modos: "TEST", "TEORIA", "AUDIO"
    private var modoSeleccionado = "TEST"

    private lateinit var switchModoNube: SwitchMaterial
    private lateinit var tvEstadoMenu: TextView

    // Selector de archivos
    private val selectorArchivo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                // Al elegir archivo, persistimos permisos para que MainActivity pueda leerlo
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prepararLanzamientoProcesamiento(uri)
            } else {
                Toast.makeText(this, "Selección cancelada", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // Vistas
        tvEstadoMenu = findViewById(R.id.tvEstado) // Estado pequeño del menú
        switchModoNube = findViewById(R.id.switchModoNube)

        val btnCargarTest = findViewById<Button>(R.id.btnCargarTest)
        val btnCargarTeoria = findViewById<Button>(R.id.btnCargarTeoria)
        val btnAudioTeoria = findViewById<Button>(R.id.btnAudioTeoria)
        val btnRetomar = findViewById<Button>(R.id.btnRetomar)

        // Chequeo inicial de IA
        lifecycleScope.launch {
            if (MotorIA.inicializar(applicationContext)) {
                tvEstadoMenu.text = "Motor IA Local: Listo"
            } else {
                tvEstadoMenu.text = "Motor IA Local: No disponible (Usar Nube)"
                switchModoNube.isChecked = true
            }
        }

        // Listeners
        btnCargarTest.setOnClickListener {
            modoSeleccionado = "TEST"
            abrirSelector()
        }

        btnCargarTeoria.setOnClickListener {
            modoSeleccionado = "TEORIA"
            abrirSelector()
        }

        btnAudioTeoria.setOnClickListener {
            modoSeleccionado = "AUDIO"
            abrirSelector()
        }

        btnRetomar.setOnClickListener {
            mostrarDialogoRetomar()
        }

        // Feedback visual del switch

    }

    private fun abrirSelector() {
        selectorArchivo.launch(
            arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
    }

    /**
     * Prepara los datos antes de lanzar la pantalla intermedia.
     * Si es Teoría, pregunta la cantidad AQUÍ (antes de cambiar de pantalla).
     */
    private fun prepararLanzamientoProcesamiento(uri: Uri) {
        val esNube = switchModoNube.isChecked

        if (modoSeleccionado == "TEORIA") {
            // Preguntar cantidad antes de ir a la pantalla de carga
            mostrarDialogoCantidad { cantidad ->
                lanzarPantallaIntermedia(uri, cantidad, esNube)
            }
        } else {
            // Test o Audio van directos
            lanzarPantallaIntermedia(uri, 20, esNube)
        }
    }

    private fun lanzarPantallaIntermedia(uri: Uri, cantidad: Int, esNube: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            data = uri // Pasamos la URI como data del intent
            flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION // CRUCIAL: Permiso para leer en la otra activity

            putExtra("MODO_APP", modoSeleccionado) // "TEST", "TEORIA", "AUDIO"
            putExtra("ES_NUBE", esNube)
            putExtra("CANTIDAD", cantidad)
        }
        startActivity(intent)
    }

    private fun mostrarDialogoCantidad(onConfirm: (Int) -> Unit) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Configuración de Generación")
        builder.setMessage("¿Cuántas preguntas deseas generar?")

        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.setText("20")
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)
        builder.setView(input)

        builder.setPositiveButton("Procesar") { dialog, _ ->
            val cantidad = input.text.toString().toIntOrNull()?.coerceIn(5, 200) ?: 20
            onConfirm(cantidad)
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun mostrarDialogoRetomar() {
        val prefs = getSharedPreferences("OposicionesPrefs", MODE_PRIVATE)
        val tests =
            prefs.all.keys.filter { it.startsWith("EXAMEN_") }.map { it.removePrefix("EXAMEN_") }

        if (tests.isEmpty()) {
            Toast.makeText(this, "No hay tests guardados", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Retomar Test")
            .setItems(tests.toTypedArray()) { _, which ->
                if (GestorPersistencia.cargarProgreso(this, tests[which])) {
                    startActivity(Intent(this, PreguntaActivity::class.java))
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}