package com.emtp.kittykiller

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class TextSelectionActivity : AppCompatActivity() {

    private lateinit var tvTextoDocumento: TextView
    private lateinit var tvEstadoSeleccion: TextView
    private lateinit var btnConfirmarSeleccion: Button

    private var textoCompleto: String = ""
    private var cantidadPreguntas: Int = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_selection)

        // Inicializar vistas
        tvTextoDocumento = findViewById(R.id.tvTextoDocumento)
        tvEstadoSeleccion = findViewById(R.id.tvEstadoSeleccion)
        btnConfirmarSeleccion = findViewById(R.id.btnConfirmarSeleccion)

        // Obtener datos del intent
        textoCompleto = intent.getStringExtra("TEXTO_COMPLETO") ?: ""
        cantidadPreguntas = intent.getIntExtra("CANTIDAD_PREGUNTAS", 20)

        if (textoCompleto.isEmpty()) {
            Toast.makeText(this, "Error: No se recibió el texto", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Mostrar texto
        tvTextoDocumento.text = textoCompleto

        // Configurar listener de selección
        tvTextoDocumento.setOnLongClickListener {
            // El TextView ya maneja la selección automáticamente con textIsSelectable="true"
            true
        }

        // Monitorear cambios en la selección
        tvTextoDocumento.customSelectionActionModeCallback = object :
            android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean {
                return true
            }

            override fun onPrepareActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean {
                // Limpiar el menú por defecto
                menu?.clear()
                // Agregar nuestra opción personalizada
                menu?.add(0, 1, 0, "Usar esta selección")
                return true
            }

            override fun onActionItemClicked(
                mode: android.view.ActionMode?,
                item: android.view.MenuItem?
            ): Boolean {
                if (item?.itemId == 1) {
                    actualizarSeleccion()
                    mode?.finish()
                    return true
                }
                return false
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                // Actualizar estado cuando se cierra el modo de selección
                actualizarSeleccion()
            }
        }

        // Botón confirmar
        btnConfirmarSeleccion.setOnClickListener {
            confirmarYEnviarSeleccion()
        }
    }

    private fun actualizarSeleccion() {
        val start = tvTextoDocumento.selectionStart
        val end = tvTextoDocumento.selectionEnd

        if (start >= 0 && end > start) {
            val textoSeleccionado = textoCompleto.substring(start, end)
            val longitud = textoSeleccionado.length

            tvEstadoSeleccion.text = "✓ Seleccionados $longitud caracteres"
            tvEstadoSeleccion.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btnConfirmarSeleccion.isEnabled = longitud >= 100 // Mínimo 100 caracteres

            if (longitud < 100) {
                tvEstadoSeleccion.text = "⚠ Selección muy corta (mínimo 100 caracteres)"
                tvEstadoSeleccion.setTextColor(
                    ContextCompat.getColor(
                        this,
                        android.R.color.holo_orange_dark
                    )
                )
            }
        } else {
            tvEstadoSeleccion.text = "Ningún texto seleccionado"
            tvEstadoSeleccion.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnConfirmarSeleccion.isEnabled = false
        }
    }

    private fun confirmarYEnviarSeleccion() {
        val start = tvTextoDocumento.selectionStart
        val end = tvTextoDocumento.selectionEnd

        if (start >= 0 && end > start) {
            val textoSeleccionado = textoCompleto.substring(start, end)

            if (textoSeleccionado.length < 100) {
                Toast.makeText(
                    this,
                    "El texto seleccionado es muy corto. Selecciona al menos 100 caracteres.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Devolver el texto seleccionado
            val resultIntent = Intent()
            resultIntent.putExtra("TEXTO_SELECCIONADO", textoSeleccionado)
            setResult(RESULT_OK, resultIntent)
            finish()
        } else {
            Toast.makeText(this, "Por favor selecciona un texto primero", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        // Cancelar selección
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }
}
