import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument // Para Docx
import java.io.InputStream

class GestorDocumentos(private val context: Context) {

    // Clasificación basada en nombre (según tus archivos)
    fun clasificarYProcesar(uri: Uri, nombreArchivo: String, onResult: (String, TipoDoc) -> Unit) {
        val tipo = when {
            nombreArchivo.startsWith("EXAMEN", ignoreCase = true) -> TipoDoc.TEST
            nombreArchivo.startsWith("Tema", ignoreCase = true) -> TipoDoc.TEORIA
            else -> TipoDoc.DESCONOCIDO
        }

        if (tipo == TipoDoc.DESCONOCIDO) {
            onResult("Documento no reconocido. Debe empezar por 'EXAMEN' o 'Tema'.", tipo)
            return
        }

        procesarContenido(uri, nombreArchivo, tipo) { textoExtraido ->
            if (tipo == TipoDoc.TEORIA) {
                // Lógica de exclusión de índice para Teoría
                val textoFinal = filtrarTeoria(textoExtraido, nombreArchivo)
                onResult(textoFinal, tipo)
            } else {
                onResult(textoExtraido, tipo)
            }
        }
    }

    private fun procesarContenido(uri: Uri, nombre: String, tipo: TipoDoc, callback: (String) -> Unit) {
        val extension = nombre.substringAfterLast('.', "").lowercase()

        try {
            val inputStream = context.contentResolver.openInputStream(uri)

            if (extension == "docx") {
                // Procesar Word (Apache POI)
                val doc = XWPFDocument(inputStream)
                val textExtractor = org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)
                callback(textExtractor.text)
                doc.close()
            } else if (extension == "pdf") {
                procesarPDF(inputStream!!, nombre, callback)
            }
        } catch (e: Exception) {
            callback("Error leyendo archivo: ${e.message}")
        }
    }

    private fun procesarPDF(inputStream: InputStream, nombre: String, callback: (String) -> Unit) {
        // Cargar documento con PDFBox
        val document = PDDocument.load(inputStream)
        val stripper = PDFTextStripper()
        val textoPlano = stripper.getText(document)
        document.close()

        // Lógica para detectar si es escaneado (Caso MADRID)
        // Si el texto extraído es muy corto o vacío, asumimos que es imagen
        if (textoPlano.trim().length < 50 || nombre.contains("MADRID", ignoreCase = true)) {
            // Es escaneado -> Usar ML Kit OCR
            realizarOCR(inputStream, callback)
        } else {
            // Es texto nativo -> Devolver texto
            callback(textoPlano)
        }
    }

    private fun realizarOCR(inputStream: InputStream, callback: (String) -> Unit) {
        // Nota: Para OCR de PDF completo, se suele renderizar cada página a Bitmap
        // y pasarla por ML Kit. Aquí simplifico el concepto.
        // Necesitarás 'PdfRenderer' de Android para convertir PDF -> Bitmap -> InputImage

        // Pseudo-código para brevedad:
        // val bitmaps = convertirPdfABitmaps(inputStream)
        // val textoCompleto = StringBuilder()
        // for (bitmap in bitmaps) {
        //     val image = InputImage.fromBitmap(bitmap, 0)
        //     val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        //     recognizer.process(image).addOnSuccessListener { res -> textoCompleto.append(res.text) }
        // }
        // callback(textoCompleto.toString())

        callback("Texto extraído vía OCR (Simulado para este ejemplo)")
    }

    private fun filtrarTeoria(texto: String, nombreArchivo: String): String {
        // Si es DOCX (Tema Medicamentos), no tiene índice estándar, devolvemos todo.
        if (nombreArchivo.endsWith("docx", ignoreCase = true)) return texto

        // Lógica heurística para encontrar el índice en PDFs (Tema 1, Tema 10)
        val palabrasClave = listOf("ÍNDICE", "TABLA DE CONTENIDOS", "SUMARIO")
        var indiceCorte = -1

        for (clave in palabrasClave) {
            val pos = texto.indexOf(clave, ignoreCase = true)
            if (pos != -1) {
                // Encontramos el índice. Intentamos buscar dónde termina.
                // Generalmente asumimos que el contenido real empieza unas líneas después o en la siguiente página.
                // Para simplificar, cortamos 1000 caracteres después de encontrar la palabra "Índice"
                // o buscamos la palabra "Introducción" o el primer capítulo.
                indiceCorte = pos
                break
            }
        }

        return if (indiceCorte != -1) {
            // Devolvemos el texto DESPUÉS del índice (aproximación)
            texto.substring(indiceCorte)
        } else {
            texto
        }
    }

    enum class TipoDoc { TEST, TEORIA, DESCONOCIDO }
}