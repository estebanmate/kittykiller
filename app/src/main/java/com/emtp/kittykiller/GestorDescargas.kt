package com.emtp.kittykiller

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GestorDescargas {

    // --- CAMBIO IMPORTANTE: USAMOS LA VERSIÓN CPU ---
    // Esta versión es universal y funciona en Samsung (Exynos), Pixel (Tensor) y Xiaomi (Snapdragon).
    private const val MODEL_URL =
        "https://huggingface.co/ASahu16/gemma/resolve/main/gemma-2b-it-cpu-int4.bin"
    private const val MODEL_FILENAME = "gemma-2b-it-cpu-int4.bin"

    fun obtenerArchivoModelo(context: Context): File {
        return File(context.filesDir, MODEL_FILENAME)
    }

    suspend fun descargarModeloSiNoExiste(
        context: Context,
        onProgress: (Int) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val archivoDestino = obtenerArchivoModelo(context)

            // Si ya existe y pesa más de 1GB (aprox), asumimos que está bien
            if (archivoDestino.exists() && archivoDestino.length() > 1000000000L) {
                withContext(Dispatchers.Main) { onProgress(100) }
                return@withContext true
            }

            try {
                val url = URL(MODEL_URL)
                val conexion = url.openConnection() as HttpURLConnection
                conexion.requestMethod = "GET"
                conexion.connect()

                if (conexion.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext false
                }

                val longitudArchivo = conexion.contentLength
                val input = conexion.inputStream
                val output = FileOutputStream(archivoDestino)

                val data = ByteArray(8192) // Buffer 8KB
                var total: Long = 0
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    output.write(data, 0, count)

                    if (longitudArchivo > 0) {
                        val porcentaje = (total * 100 / longitudArchivo).toInt()
                        withContext(Dispatchers.Main) {
                            onProgress(porcentaje)
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()
                return@withContext true

            } catch (e: Exception) {
                e.printStackTrace()
                // Borramos el archivo corrupto si falló a medias
                if (archivoDestino.exists()) archivoDestino.delete()
                return@withContext false
            }
        }
    }
}