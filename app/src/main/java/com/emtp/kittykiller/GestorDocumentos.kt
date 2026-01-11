package com.emtp.kittykiller

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Sacamos el typealias fuera de la clase para evitar el error "nested type aliases".
typealias TipoDoc = ProcesadorDocumentos.TipoDoc

class GestorDocumentos(private val context: Context) {

    private val procesador = ProcesadorDocumentos(context)

    // Callback modificado para manejar Éxito, Error y PROGRESO
    fun clasificarYProcesar(
        uri: Uri,
        nombreArchivo: String,
        onResult: (String, TipoDoc) -> Unit,
        onError: (String) -> Unit,
        onProgress: (String) -> Unit = {} // Valor por defecto vacío para compatibilidad
    ) {
        // Lanzamos en el hilo principal para poder actualizar la UI con el resultado
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Clasificación preliminar
                if (nombreArchivo.isBlank()) {
                    onError("Nombre de archivo inválido.")
                    return@launch
                }

                // 2. Procesamiento (Lectura, OCR automático, Limpieza de índices)
                // Pasamos el onProgress recibido hacia el procesador
                val resultado = procesador.procesarArchivo(uri, nombreArchivo) { mensaje ->
                    // 1. Log interno
                    android.util.Log.d("GestorDocs", "Progreso: $mensaje")
                    // 2. Actualizar UI a través del callback
                    onProgress(mensaje)
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
    // Método para preparar el archivo (copiar a cache) sin procesar texto
    fun prepararArchivo(
        uri: Uri,
        nombreArchivo: String,
        onReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (nombreArchivo.isBlank()) {
                    onError("Nombre de archivo inválido.")
                    return@launch
                }

                val resultFile = withContext(Dispatchers.IO) {
                    procesador.prepararArchivoTemporal(uri, nombreArchivo)
                }
                
                onReady(resultFile)

            } catch (e: Exception) {
                onError("Error preparando archivo: ${e.localizedMessage}")
            }
        }
    }

    // Método para procesar un archivo ya existente en disco
    fun procesarArchivoYaPreparado(
        file: File,
        nombreArchivo: String,
        paginas: List<Int>?,
        onResult: (String, TipoDoc) -> Unit,
        onError: (String) -> Unit,
        onProgress: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resultado = procesador.procesarFicheroExistente(file, nombreArchivo, paginas) { msg ->
                    android.util.Log.d("GestorDocs", "Progreso: $msg")
                    onProgress(msg)
                }

                val (tipoDetectado, textoExtraido) = resultado

                if (textoExtraido.isBlank()) {
                    onError("No se pudo extraer texto.")
                    return@launch
                }

                onResult(textoExtraido, tipoDetectado)

            } catch (e: Exception) {
                onError("Error procesando archivo: ${e.localizedMessage}")
            }
        }
    }
}