package com.oasis.reader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Extrae el texto de cada página de un PDF sin las notas al pie ni los números
 * "sobrescritos" (las llamadas de nota que se pegan al número de artículo, como
 * "Artículo 7" + nota 2 = "Artículo 72"). Se apoya en el tamaño de letra:
 *  - caracteres claramente más chicos que el cuerpo del texto (< 70 %) se descartan;
 *  - palabras pequeñas (< 85 %) en la parte baja de la página (nota al pie, número de página) se descartan;
 *  - notas de reforma intercaladas en el texto ("Artículo reformado DOF 30-11-2012"), que vienen en letra
 *    algo menor (< 95 %), se descartan en cualquier parte de la página.
 * Si el PDF no permite medir el tamaño, extrae sin filtrar.
 */
class PdfExtraccion(private val doc: PDDocument) {

    /** Tamaño de letra del cuerpo (pt); 0 si no se pudo medir. */
    var tamanoBase: Float = 0f
        private set

    private class Calibrador : PDFTextStripper() {
        val hist = HashMap<Float, Int>()
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            for (p in textPositions) {
                if (p.unicode.isNullOrBlank()) continue
                val k = Math.round(p.fontSizeInPt * 2f) / 2f
                hist[k] = (hist[k] ?: 0) + 1
            }
        }
    }

    private class Filtro(var base: Float) : PDFTextStripper() {
        private val reNota = Regex("(reformad|adicionad|derogad|recorrid|fe de erratas|DOF\\s*\\d)", RegexOption.IGNORE_CASE)
        private var notaPrevia = false

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (base <= 0f || textPositions.isEmpty()) {
                super.writeString(text, textPositions); return
            }
            var max = 0f
            for (p in textPositions) if (p.fontSizeInPt > max) max = p.fontSizeInPt
            val primera = textPositions[0]
            val alto = primera.pageHeight
            val relY = if (alto > 0f) primera.yDirAdj / alto else 0f
            if (max < base * 0.85f && relY > 0.55f) return          // nota al pie / número de página
            // Nota de reforma intercalada (letra algo menor); sus líneas de continuación también.
            if (max < base * 0.95f) {
                if (notaPrevia || reNota.containsMatchIn(text)) { notaPrevia = true; return }
            } else {
                notaPrevia = false
            }
            if (text.length == textPositions.size) {
                val sb = StringBuilder()
                val keep = ArrayList<TextPosition>(textPositions.size)
                for (i in text.indices) {
                    val p = textPositions[i]
                    if (p.fontSizeInPt >= base * 0.7f) { sb.append(text[i]); keep.add(p) }
                }
                if (sb.isEmpty()) return
                super.writeString(sb.toString(), keep)
            } else {
                super.writeString(text, textPositions)
            }
        }
    }

    private val filtro = Filtro(0f).apply {
        sortByPosition = true
        lineSeparator = "\n"
    }

    /** Mide el tamaño de letra del cuerpo con las páginas 2 a 16 (la 1 suele ser portada). */
    fun calibrar() {
        val total = doc.numberOfPages
        val c = Calibrador()
        c.startPage = minOf(2, total)
        c.endPage = minOf(total, 16)
        try { c.getText(doc) } catch (e: Exception) { return }
        var mejor = 0f
        var n = -1
        for ((k, v) in c.hist) if (v > n) { n = v; mejor = k }
        tamanoBase = mejor
        filtro.base = mejor
    }

    /** Texto de la página (1..N). */
    fun pagina(i: Int): String {
        filtro.startPage = i
        filtro.endPage = i
        return filtro.getText(doc)
    }
}
