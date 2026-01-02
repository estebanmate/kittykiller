package com.emtp.kittykiller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileOutputStream

class ProcesadorDocumentos(private val context: Context) {

    enum class TipoDoc { TEST, TEORIA, DESCONOCIDO }

    init {
        // Inicializar PDFBox al arrancar la clase
        PDFBoxResourceLoader.init(context)
    }

    suspend fun procesarArchivo(
        uri: Uri,
        nombreArchivo: String,
        onProgress: (String) -> Unit
    ): Pair<TipoDoc, String> {
        return withContext(Dispatchers.IO) {
            // Clasificación por nombre (Test vs Teoría)
            val tipo = determinarTipo(nombreArchivo)
            val extension = nombreArchivo.substringAfterLast('.', "").lowercase()

            val tempFile = crearArchivoTemporal(uri, extension)
            var texto = ""

            try {
                texto = when (extension) {
                    "pdf" -> leerPdfInteligente(tempFile, onProgress)
                    "docx" -> {
                        onProgress("Leyendo documento Word...")
                        leerDocx(tempFile)
                    }

                    "doc" -> {
                        onProgress("Leyendo documento Word antiguo...")
                        leerDocLegacy(tempFile)
                    }

                    else -> "Formato no soportado ($extension)"
                }

                // IMPORTANTE: Solo limpiamos (borramos portadas/índice) si es TEORÍA.
                // Si es un TEST, necesitamos todo el contenido íntegro para detectar preguntas.
                if (tipo == TipoDoc.TEORIA) {
                    onProgress("Optimizando contenido para IA...")
                    texto = limpiarContenidoTeoria(texto)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                texto = "Error al leer el archivo: ${e.message}"
            } finally {
                // Borrar archivo temporal para no llenar el almacenamiento
                if (tempFile.exists()) tempFile.delete()
            }

            Pair(tipo, texto)
        }
    }

    // --- LECTURA DE PDF (Híbrida: Texto nativo + OCR) ---
    private suspend fun leerPdfInteligente(file: File, onProgress: (String) -> Unit): String {
        var textoNativo = ""

        try {
            onProgress("Analizando estructura del PDF...")
            PDDocument.load(file).use { document ->
                val paginas = document.numberOfPages
                if (paginas > 10) onProgress("Extrayendo texto digital de $paginas páginas...")

                val stripper = PDFTextStripper()
                // Ordenar por posición evita que el texto salga desordenado en columnas
                stripper.sortByPosition = true
                textoNativo = stripper.getText(document)
            }
        } catch (e: Exception) {
            Log.e("PDF", "Error lectura nativa", e)
        }

        // --- LÓGICA DE DETECCIÓN DE ESCANEADO ---
        // 1. Si el texto es muy corto (< 200 chars), es casi seguro una imagen.
        // 2. Si tiene muchos símbolos de "desconocido" (), es un PDF mal codificado.
        val esMuyCorto = textoNativo.trim().length < 200
        val pareceBasura =
            textoNativo.count { it == '\uFFFD' } > 20 // Carácter 'replacement' común en errores

        if (esMuyCorto || pareceBasura) {
            onProgress("Texto digital no detectado o ilegible. Activando OCR (Escaneo)...")
            return realizarOCR(file, onProgress)
        }

        return textoNativo
    }

    // --- MOTOR OCR (ML Kit) ---
    private suspend fun realizarOCR(file: File, onProgress: (String) -> Unit): String {
        val textoCompleto = StringBuilder()
        // Cliente de reconocimiento de texto
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            val fileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            val totalPaginas = renderer.pageCount

            onProgress("Iniciando escaneo inteligente de $totalPaginas páginas...")

            for (i in 0 until totalPaginas) {
                // Notificar progreso cada pocas páginas
                if (i % 3 == 0 || i == totalPaginas - 1) {
                    withContext(Dispatchers.Main) {
                        onProgress("Escaneando página ${i + 1} de $totalPaginas...")
                    }
                }

                // Renderizar página a imagen (Bitmap)
                val page = renderer.openPage(i)
                // Aumentamos la densidad (escala x2) para que ML Kit lea mejor la letra pequeña
                val width = (page.width * 2).toInt()
                val height = (page.height * 2).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Procesar imagen con ML Kit
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await() // .await() requiere corrutinas

                textoCompleto.append(result.text).append("\n\n")

                // Limpieza de memoria (CRÍTICO en bucles grandes)
                page.close()
                bitmap.recycle()
            }

            renderer.close()
            fileDescriptor.close()

        } catch (e: Exception) {
            return "Error OCR: ${e.message}"
        }

        return textoCompleto.toString()
    }

    // --- LECTURA WORD ---
    private fun leerDocx(file: File): String {
        file.inputStream().use { fis ->
            val document = XWPFDocument(fis)
            val extractor = org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)
            return extractor.text
        }
    }

    private fun leerDocLegacy(file: File): String {
        file.inputStream().use { fis ->
            val document = HWPFDocument(fis)
            val extractor = org.apache.poi.hwpf.extractor.WordExtractor(document)
            return extractor.text
        }
    }

    // --- LÓGICA DE NEGOCIO ---
    private fun determinarTipo(nombre: String): TipoDoc {
        return when {
            nombre.contains("EXAMEN", ignoreCase = true) ||
                    nombre.contains("TEST", ignoreCase = true) ||
                    nombre.contains("OPE", ignoreCase = true) -> TipoDoc.TEST

            nombre.contains("TEMA", ignoreCase = true) ||
                    nombre.contains("TEMARIO", ignoreCase = true) ||
                    nombre.contains("APUNTES", ignoreCase = true) -> TipoDoc.TEORIA

            else -> TipoDoc.TEORIA // Por defecto
        }
    }

    // --- LIMPIEZA DE TEORÍA ---
    private fun limpiarContenidoTeoria(texto: String): String {
        // Eliminar cabeceras repetitivas que confunden a la IA
        var textoLimpio = texto.replace(Regex("(?i)Ucademy|Manual Oposiciones|TCAE SERMAS"), "")

        // Patrones para detectar dónde empieza el contenido real (Tema 1, Capítulo 1...)
        val patronesInicio = listOf(
            Regex("TEMA\\s+\\d+", RegexOption.IGNORE_CASE),
            Regex("MÓDULO\\s+\\d+", RegexOption.IGNORE_CASE),
            Regex("CAPÍTULO\\s+\\d+", RegexOption.IGNORE_CASE),
            Regex("UNIDAD\\s+\\d+", RegexOption.IGNORE_CASE)
        )

        val cabecera = textoLimpio.take(35000)

        // Buscar índice
        val posIndice = cabecera.indexOf("ÍNDICE", ignoreCase = true)
        val posTabla = cabecera.indexOf("TABLA DE CONTENIDOS", ignoreCase = true)
        val puntoDeCorte = if (posIndice != -1) posIndice else posTabla

        // Estrategia: Buscar "TEMA X" después del índice
        val inicioBusqueda = if (puntoDeCorte != -1) puntoDeCorte + 100 else 0

        for (patron in patronesInicio) {
            val match = patron.find(cabecera, startIndex = inicioBusqueda)
            if (match != null) {
                return textoLimpio.substring(match.range.first)
            }
        }

        // Fallback: Si había índice pero no encontramos TEMA X, cortar después del índice
        if (puntoDeCorte != -1) {
            val saltoSeguridad = minOf(puntoDeCorte + 2000, textoLimpio.length)
            return textoLimpio.substring(saltoSeguridad)
        }

        return textoLimpio
    }

    // --- UTILIDADES ---
    private fun crearArchivoTemporal(uri: Uri, extension: String): File {
        val tempFile = File.createTempFile("temp_doc_", ".$extension", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }
}