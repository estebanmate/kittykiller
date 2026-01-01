package com.emtp.kittykiller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    // Variable para saber qué botón se pulsó antes de elegir archivo
    private var modoSeleccionado = "TEST"

    // Selector de archivos
    private val selectorArchivo =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    // LANZAMOS LA PANTALLA DE ANÁLISIS (MainActivity)
                    val intent = Intent(this, MainActivity::class.java)
                    intent.data = uri // Pasamos la URI del archivo
                    intent.putExtra("MODO", modoSeleccionado)
                    // Permisos para que la otra activity lea el archivo
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        val btnCargarTest = findViewById<Button>(R.id.btnCargarTest)
        val btnCargarTeoria = findViewById<Button>(R.id.btnCargarTeoria)
        val btnRetomar = findViewById<Button>(R.id.btnRetomar)

        btnCargarTest.setOnClickListener {
            modoSeleccionado = "TEST"
            abrirSelector()
        }

        btnCargarTeoria.setOnClickListener {
            modoSeleccionado = "TEORIA"
            abrirSelector()
        }

        btnRetomar.setOnClickListener {
            // Lógica de retomar último examen guardado
            if (GestorPersistencia.cargarProgreso(this)) {
                startActivity(Intent(this, PreguntaActivity::class.java))
            } else {
                Toast.makeText(this, "No hay examen guardado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun abrirSelector() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
        }
        selectorArchivo.launch(intent)
    }
}