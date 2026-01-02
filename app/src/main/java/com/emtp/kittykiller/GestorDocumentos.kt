package com.emtp.kittykiller

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// --- CORRECCIÓN ---
// Sacamos el typealias fuera de la clase para evitar el error "nested type aliases".
// Ahora es accesible en todo el archivo sin problemas.
typealias TipoDoc = ProcesadorDocumentos.TipoDoc

class GestorDocumentos(private val context: Context) {

    private val procesador = ProcesadorDocumentos(context)

    // Callback modificado para manejar Éxito y Error
    fun clasificarYProcesar(
        uri: Uri,
        nombreArchivo: String,
        onResult: (String, TipoDoc) -> Unit,
        onError: (String) -> Unit
    ) {
        // Lanzamos en el hilo principal para poder actualizar la UI con el resultado,
        // pero el procesador cambiará internamente a IO para no bloquear.
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Clasificación preliminar (Test vs Teoría)
                if (nombreArchivo.isBlank()) {
                    onError("Nombre de archivo inválido.")
                    return@launch
                }

                // 2. Procesamiento (Lectura, OCR automático, Limpieza de índices)
                val resultado = procesador.procesarArchivo(uri, nombreArchivo) { mensajeProgreso ->
                    // Log de progreso
                    android.util.Log.d("GestorDocs", "Progreso: $mensajeProgreso")
                }

                val (tipoDetectado, textoExtraido) = resultado

                // 3. Validación de contenido
                if (textoExtraido.isBlank()) {
                    onError("No se pudo extraer texto. El archivo podría estar vacío o protegido.")
                    return@launch
                }

                // 4. Devolver resultado
                onResult(textoExtraido, tipoDetectado)

            } catch (e: Exception) {
                onError("Error procesando archivo: ${e.localizedMessage}")
            }
        }
    }
}