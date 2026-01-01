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
            val tipo = determinarTipo(nombreArchivo)
            // Extraer extensión ignorando mayúsculas
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
                // Si es un TEST, necesitamos todo el contenido íntegro.
                if (tipo == TipoDoc.TEORIA) {
                    onProgress("Optimizando contenido para IA...")
                    texto = limpiarContenidoTeoria(texto)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                texto = "Error al leer el archivo: ${e.message}"
            } finally {
                // Borrar archivo temporal para no llenar el móvil de basura
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

        // Si el texto extraído es muy corto, asumimos que es un PDF ESCANEADO (Imagen)
        // y lanzamos el OCR de Google.
        if (textoNativo.trim().length < 200) {
            return realizarOCR(file, onProgress)
        }

        return textoNativo
    }

    // --- MOTOR OCR (ML Kit) ---
    private suspend fun realizarOCR(file: File, onProgress: (String) -> Unit): String {
        val textoCompleto = StringBuilder()
        // Cliente de reconocimiento de texto latino (Español, Inglés, etc.)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            val fileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            val totalPaginas = renderer.pageCount

            onProgress("Documento escaneado detectado. Iniciando OCR...")

            for (i in 0 until totalPaginas) {
                // Notificar progreso cada pocas páginas para no saturar la UI
                if (i % 3 == 0 || i == totalPaginas - 1) {
                    withContext(Dispatchers.Main) {
                        onProgress("Escaneando página ${i + 1} de $totalPaginas...")
                    }
                }

                // Renderizar página a imagen
                val page = renderer.openPage(i)
                // Usamos densidad x2 para mejorar la calidad del reconocimiento
                val bitmap =
                    Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Procesar con ML Kit
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await() // .await() necesita corrutinas

                textoCompleto.append(result.text).append("\n\n")

                // Limpieza de memoria CRÍTICA
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

            else -> TipoDoc.TEORIA // Por defecto tratamos como teoría para IA
        }
    }

    // --- LIMPIEZA AVANZADA (Fix para Ucademy) ---
    // --- LÓGICA DE LIMPIEZA DE TEORÍA (REGEX AVANZADO) ---
    private fun limpiarContenidoTeoria(texto: String): String {
        // 1. Limpieza de "ruido" repetitivo (Cabeceras de Ucademy, etc.)
        // Esto evita que la IA lea "Ucademy" 50 veces y se distraiga.
        var textoLimpio = texto.replace(Regex("(?i)Ucademy|Manual Oposiciones|TCAE SERMAS"), "")

        // 2. Definimos patrones inteligentes (Regex)
        // \d+ significa "cualquier número" (1, 10, 25...)
        val patronesInicio = listOf(
            Regex("TEMA\\s+\\d+", RegexOption.IGNORE_CASE),      // Detecta: TEMA 1, TEMA 10...
            Regex("MÓDULO\\s+\\d+", RegexOption.IGNORE_CASE),    // Detecta: MÓDULO 3...
            Regex("CAPÍTULO\\s+\\d+", RegexOption.IGNORE_CASE),  // Detecta: CAPÍTULO 4...
            Regex("UNIDAD\\s+\\d+", RegexOption.IGNORE_CASE),    // Detecta: UNIDAD 2...
            Regex(
                "\\n1\\.\\s+[A-ZÁÉÍÓÚÑ]",
                RegexOption.IGNORE_CASE
            ) // Detecta: "1. INTRODUCCIÓN" (Genérico)
        )

        // Buscamos en las primeras 15 páginas (35.000 caracteres)
        val cabecera = textoLimpio.take(35000)

        // A. Buscamos la palabra ÍNDICE primero
        val posIndice = cabecera.indexOf("ÍNDICE", ignoreCase = true)
        val posTablaCont = cabecera.indexOf("TABLA DE CONTENIDOS", ignoreCase = true)

        // El punto de corte inicial será donde esté el índice (si existe)
        var puntoDeCorte =
            if (posIndice != -1) posIndice else if (posTablaCont != -1) posTablaCont else -1

        // B. ESTRATEGIA DE SALTO:
        // Si hay índice, buscamos el primer "TEMA X" o "1. X" que aparezca DESPUÉS del índice.
        // Si no hay índice, buscamos el primer "TEMA X" que aparezca en el documento.

        val inicioBusqueda = if (puntoDeCorte != -1) puntoDeCorte + 100 else 0

        for (patron in patronesInicio) {
            // Buscamos el patrón (ej: "TEMA 10")
            val match = patron.find(cabecera, startIndex = inicioBusqueda)

            if (match != null) {
                // ¡Encontrado! Este es el inicio real del temario.
                // Devolvemos el texto desde aquí, ignorando todo lo anterior (portadas e índice).
                Log.d(
                    "Procesador",
                    "Inicio detectado: '${match.value}' en pos ${match.range.first}"
                )
                return textoLimpio.substring(match.range.first)
            }
        }

        // C. Fallback: Si no encontramos "TEMA X" después del índice, pero había índice...
        if (puntoDeCorte != -1) {
            // Cortamos justo después del índice más un margen de seguridad (ej: 2000 letras)
            // para intentar saltarnos la lista de capítulos.
            val saltoSeguridad = minOf(puntoDeCorte + 2000, textoLimpio.length)
            return textoLimpio.substring(saltoSeguridad)
        }

        // D. Si no encontramos nada, devolvemos el texto (ya limpio de la palabra "Ucademy")
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