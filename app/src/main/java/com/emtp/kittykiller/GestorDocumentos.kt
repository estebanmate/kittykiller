package com.emtp.kittykiller

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GestorDocumentos(private val context: Context) {

    private val procesador = ProcesadorDocumentos(context)

    // Callback modificado para devolver también errores si ocurren
    fun clasificarYProcesar(
        uri: Uri,
        nombreArchivo: String,
        onResult: (String, TipoDoc) -> Unit,
        onError: (String) -> Unit
    ) {

        // 1. Clasificación preliminar por nombre
        val tipo = when {
            nombreArchivo.startsWith("EXAMEN", ignoreCase = true) ||
                    nombreArchivo.contains("TEST", ignoreCase = true) -> TipoDoc.TEST

            nombreArchivo.startsWith("Tema", ignoreCase = true) -> TipoDoc.TEORIA
            else -> TipoDoc.DESCONOCIDO
        }

        if (tipo == TipoDoc.DESCONOCIDO) {
            onError("Documento no reconocido. Debe contener 'EXAMEN' o 'Tema' en el nombre.")
            return
        }

        // 2. Delegar el procesamiento pesado a un hilo secundario (IO)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Llamamos a ProcesadorDocumentos (que ya maneja PDFBox, OCR y limpieza)
                val resultado = procesador.procesarArchivo(uri, nombreArchivo) { progreso ->
                    // Opcional: Podrías pasar este progreso a la UI si cambias la firma del callback
                    android.util.Log.d("GestorDocs", progreso)
                }

                val (_, textoExtraido) = resultado

                // Usamos el tipo determinado por el nombre (más fiable para tu estructura de archivos)
                // o el que devuelve el procesador si fuera más inteligente en el futuro.
                onResult(textoExtraido, tipo)

            } catch (e: Exception) {
                onError("Error procesando archivo: ${e.localizedMessage}")
            }
        }
    }

    enum class TipoDoc { TEST, TEORIA, DESCONOCIDO }
}