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
        // Inicializar PDFBox (necesario para leer PDFs nativos)
        PDFBoxResourceLoader.init(context)
    }

    suspend fun procesarArchivo(
        uri: Uri,
        nombreArchivo: String,
        onProgress: (String) -> Unit
    ): Pair<TipoDoc, String> {
        return withContext(Dispatchers.IO) {
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

                // LÓGICA DE LIMPIEZA ESPECÍFICA PARA TEORÍA
                if (tipo == TipoDoc.TEORIA) {
                    if (extension == "docx" || extension == "doc") {
                        onProgress("Documento Word detectado: Se procesará todo el contenido.")
                        // No limpiamos DOCX, devolvemos todo tal cual
                    } else {
                        onProgress("Detectando inicio real del tema (saltando introducciones)...")
                        val esUcademy = nombreArchivo.contains("TCAE SERMAS", ignoreCase = true) ||
                                       nombreArchivo.contains("Ucademy", ignoreCase = true) ||
                                       texto.contains("Ucademy", ignoreCase = true)
                        texto = limpiarContenidoTeoriaPDF(texto, esUcademy)
                    }
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

    // --- LECTURA DE PDF (Híbrida: Texto nativo + OCR si falla) ---
    private suspend fun leerPdfInteligente(file: File, onProgress: (String) -> Unit): String {
        var textoNativo = ""

        try {
            onProgress("Analizando estructura del PDF...")
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true // Importante para mantener orden de columnas
                stripper.pageEnd = "\u000C" // Añadir Form Feed al final de cada página para poder contarlas
                textoNativo = stripper.getText(document)
            }
        } catch (e: Exception) {
            Log.e("PDF", "Error lectura nativa", e)
        }

        // Lógica de decisión: ¿Es texto real o una imagen/escaneado?
        // 1. Si es muy corto (< 200 chars), seguro es imagen.
        // 2. Si tiene muchos caracteres 'desconocidos' (), es basura de codificación.
        val esMuyCorto = textoNativo.trim().length < 200
        val pareceBasura = textoNativo.count { it == '\uFFFD' } > 20

        if (esMuyCorto || pareceBasura) {
            onProgress("Texto digital no legible. Activando Escáner OCR...")
            return realizarOCR(file, onProgress)
        }

        return textoNativo
    }

    // --- MOTOR OCR (Google ML Kit) ---
    private suspend fun realizarOCR(file: File, onProgress: (String) -> Unit): String {
        val textoCompleto = StringBuilder()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                .use { fileDescriptor ->
                    val renderer = PdfRenderer(fileDescriptor)
                    val totalPaginas = renderer.pageCount

                    onProgress("Iniciando escaneo de $totalPaginas páginas...")

                    for (i in 0 until totalPaginas) {
                        if (i % 2 == 0 || i == totalPaginas - 1) { // Reportar cada 2 páginas o la última
                            val porcentaje = (((i + 1).toFloat() / totalPaginas) * 100).toInt()
                            withContext(Dispatchers.Main) {
                                onProgress("Escaneando... $porcentaje% (Pág ${i + 1}/$totalPaginas)")
                            }
                        }

                        // Renderizar página a imagen de alta calidad (escala x2)
                        val page = renderer.openPage(i)
                        val bitmap = Bitmap.createBitmap(
                            page.width * 2,
                            page.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        // Procesar con ML Kit
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val result = recognizer.process(image).await()
                        textoCompleto.append(result.text).append("\n\u000C\n") // Añadir separador de página también en OCR

                        page.close()
                        bitmap.recycle() // Liberar memoria inmediatamente
                    }
                    renderer.close()
                }
        } catch (e: Exception) {
            return "Error OCR: ${e.message}"
        }
        return textoCompleto.toString()
    }

    // --- LECTURA WORD (Apache POI) ---
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

    // --- LIMPIEZA INTELIGENTE PARA PDFs (UCADEMY) ---
    private fun limpiarContenidoTeoriaPDF(texto: String, esUcademy: Boolean = false): String {
        // Para PDFs de Ucademy, sabemos que la teoría empieza en la página 9
        if (esUcademy) {
            Log.d("Procesador", "PDF Ucademy detectado, extrayendo desde página 9")
            val textoExtraido = extraerDesdePagina(texto, paginaInicio = 9) // 0-indexed logic in helper, passing 9 assumes helper handles "start at page 9" meaning skip 8
            // Limpiar cabeceras repetitivas
            return textoExtraido.replace(Regex("(?i)Ucademy|Manual Oposiciones|TÉCNICO EN CUIDADOS AUXILIARES DE ENFERMERÍA|SERVICIO MADRILEÑO DE SALUD"), "")
                .replace(Regex("\\s+"), " ") // Normalizar espacios
                .trim()
        }

        // Para otros PDFs, usar la lógica existente mejorada
        // 1. Eliminar cabeceras repetitivas
        val textoSinCabeceras = texto.replace(Regex("(?i)Ucademy|Manual Oposiciones|TCAE SERMAS|Bloque \\w+"), "")

        // 2. Buscar Índice
        val indices = listOf("ÍNDICE", "TABLA DE CONTENIDOS", "SUMARIO")
        var posIndice = -1
        for (idx in indices) {
            posIndice = textoSinCabeceras.indexOf(idx, ignoreCase = true)
            if (posIndice != -1) {
                Log.d("Procesador", "Índice encontrado en posición $posIndice")
                break
            }
        }

        if (posIndice == -1) {
            Log.d("Procesador", "No se encontró índice, procesando todo el contenido")
            return textoSinCabeceras
        }

        // 3. Buscar inicio real DESPUÉS del índice
        val textoPostIndice = textoSinCabeceras.substring(posIndice)
        val saltoSeguridad = minOf(3000, textoPostIndice.length)

        // Patrones de inicio mejorados
        val patronesInicio = listOf(
            Regex("\\n\\s*\\d+\\.\\s+[A-ZÁÉÍÓÚÑ]"),
            Regex("\\nTEMA\\s+\\d+\\b", RegexOption.IGNORE_CASE), // More generic: Tema X
            Regex("\\nUNIDAD\\s+DIDÁCTICA\\s+\\d+\\b", RegexOption.IGNORE_CASE),
            Regex("\\nCAPÍTULO\\s+\\d+\\b", RegexOption.IGNORE_CASE)
        )

        for (patron in patronesInicio) {
            val match = patron.find(textoPostIndice, startIndex = saltoSeguridad)
            if (match != null) {
                Log.d("Procesador", "Inicio de teoría detectado: ${match.value.trim()}")
                return textoPostIndice.substring(match.range.first).trim()
            }
        }

        // Fallback: Cortar después del margen de seguridad
        Log.d("Procesador", "Usando fallback: cortando después de $saltoSeguridad caracteres")
        return textoPostIndice.substring(saltoSeguridad).trim()
    }

    // Extrae contenido desde una página específica (basado en Form Feed characters)
    private fun extraerDesdePagina(texto: String, paginaInicio: Int): String {
        if (paginaInicio <= 0) return texto
        
        // Contar Form Feeds (\f o \u000C) que marcan saltos de página
        var contador = 0
        var posicion = 0
        
        while (contador < paginaInicio && posicion < texto.length) {
            val siguiente = texto.indexOf('\u000C', posicion)
            if (siguiente == -1) {
                // No hay más páginas, devolver desde la posición actual
                break
            }
            posicion = siguiente + 1
            contador++
        }
        
        return if (posicion < texto.length) {
            texto.substring(posicion)
        } else {
            Log.w("Procesador", "No se pudo llegar a la página $paginaInicio, devolviendo todo")
            texto
        }
    }

    private fun determinarTipo(nombre: String): TipoDoc {
        return when {
            nombre.contains("EXAMEN", ignoreCase = true) ||
                    nombre.contains("TEST", ignoreCase = true) ||
                    nombre.contains("OPE", ignoreCase = true) -> TipoDoc.TEST

            else -> TipoDoc.TEORIA
        }
    }

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