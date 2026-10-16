package com.oasis.reader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException

/**
 * Lee un PDF con texto y lo entrega como EpubParser.ParsedEpub.
 * Llamar SIEMPRE fuera del hilo principal (Thread / corrutina en Dispatchers.IO).
 * Lanza Exception con mensaje listo para mostrar al usuario.
 */
class PdfParser(private val context: Context, private val contentResolver: ContentResolver) {

    /** Aviso opcional para mostrar tras cargar (OCR dudoso, páginas sin texto). */
    var aviso: String? = null
        private set

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        // PDFBox usa archivos temporales; en Android java.io.tmpdir puede no estar definido.
        System.setProperty("java.io.tmpdir", context.cacheDir.absolutePath)
    }

    @Throws(Exception::class)
    fun parse(uri: Uri, onProgress: ((hecho: Int, total: Int) -> Unit)? = null): EpubParser.ParsedEpub {
        aviso = null
        val input = contentResolver.openInputStream(uri)
            ?: throw Exception("No se pudo abrir el archivo.")
        var doc: PDDocument? = null
        try {
            doc = input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
            val total = doc.numberOfPages
            if (total == 0) throw Exception("El PDF no tiene páginas.")

            val extractor = PdfExtraccion(doc)
            extractor.calibrar()
            val paginas = ArrayList<String>(total)
            var fallidas = 0
            for (i in 1..total) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Carga cancelada.")
                val texto = try {
                    extractor.pagina(i)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    fallidas++; ""
                }
                paginas.add(texto)
                onProgress?.invoke(i, total)
            }

            val r = PdfTextProcessor.procesar(paginas)
            val avisos = mutableListOf<String>()
            if (r.calidadBaja) avisos.add("El texto parece de un OCR deficiente; puede haber errores de lectura. Verifica cifras y artículos.")
            if (r.paginasSinTexto > 0) avisos.add("${r.paginasSinTexto} página(s) sin texto se omitieron.")
            if (fallidas > 0) avisos.add("$fallidas página(s) no se pudieron leer.")
            aviso = avisos.joinToString(" ").ifEmpty { null }

            return EpubParser.ParsedEpub(
                r.titulos,
                r.contenidos,
                MutableList(r.titulos.size) { false }
            )
        } catch (e: InvalidPasswordException) {
            throw Exception("El PDF está protegido con contraseña.")
        } catch (e: PdfEscaneadoException) {
            throw Exception(e.message)
        } catch (e: OutOfMemoryError) {
            throw Exception("El PDF es demasiado grande para este dispositivo.")
        } finally {
            try { doc?.close() } catch (_: Exception) {}
        }
    }
}
