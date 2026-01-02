package com.emtp.kittykiller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    // Variable para saber qué botón se pulsó antes de elegir archivo
    // Modos: "TEST", "TEORIA", "AUDIO"
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
        // Asegúrate de que este ID existe en tu activity_menu.xml
        val btnAudioTeoria = findViewById<Button>(R.id.btnAudioTeoria)
        val btnRetomar = findViewById<Button>(R.id.btnRetomar)

        // MODO TEST
        btnCargarTest.setOnClickListener {
            modoSeleccionado = "TEST"
            abrirSelector()
        }

        // MODO TEORÍA (IA)
        btnCargarTeoria.setOnClickListener {
            modoSeleccionado = "TEORIA"
            abrirSelector()
        }

        // MODO AUDIO
        btnAudioTeoria.setOnClickListener {
            modoSeleccionado = "AUDIO"
            abrirSelector()
        }

        // RETOMAR EXAMEN
        btnRetomar.setOnClickListener {
            mostrarDialogoRetomar()
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

    private fun mostrarDialogoRetomar() {
        // Accedemos a las preferencias para buscar los exámenes guardados
        val sharedPreferences = getSharedPreferences("OposicionesPrefs", MODE_PRIVATE)
        val todos = sharedPreferences.all

        // Filtramos las claves que empiezan por "EXAMEN_"
        val listaNombres = todos.keys
            .filter { it.startsWith("EXAMEN_") }
            .map { it.removePrefix("EXAMEN_") }
            .sorted()

        if (listaNombres.isEmpty()) {
            Toast.makeText(this, "No hay exámenes guardados", Toast.LENGTH_SHORT).show()
            return
        }

        // Mostramos la lista en un diálogo
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Selecciona un test para retomar")
        builder.setItems(listaNombres.toTypedArray()) { _, which ->
            val nombreSeleccionado = listaNombres[which]

            // Cargamos el progreso usando el nombre seleccionado
            if (GestorPersistencia.cargarProgreso(this, nombreSeleccionado)) {
                Toast.makeText(this, "Retomando: $nombreSeleccionado", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, PreguntaActivity::class.java))
            } else {
                Toast.makeText(this, "Error al cargar el examen", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }
}