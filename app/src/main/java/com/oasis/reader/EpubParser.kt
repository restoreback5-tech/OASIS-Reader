package com.oasis.reader

import android.content.ContentResolver
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipInputStream

class EpubParser(private val contentResolver: ContentResolver) {

    data class ParsedEpub(
        val chapterTitles: MutableList<String>,
        val chapterContents: MutableList<List<String>>,
        // true = sección auxiliar (notas, abreviaturas, apéndices cortos...) oculta por defecto
        val chapterHidden: MutableList<Boolean> = mutableListOf()
    )

    private val regexOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    // Bloques que no son texto del libro.
    private val headRegex = Regex("<head\\b.*?</head\\s*>", regexOptions)
    private val scriptStyleRegex = Regex("<(script|style)\\b.*?</\\1\\s*>", regexOptions)
    private val commentRegex = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    // Etiquetas que separan párrafos. Las demás (i, b, span, a...) se quitan sin partir el texto.
    private val blockTagRegex = Regex(
        "</?(?:p|div|br|h[1-6]|li|ul|ol|tr|td|th|table|blockquote|section|article|aside|header|footer|figure|figcaption|pre|hr|dt|dd|nav|caption)\\b[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val anyTagRegex = Regex("<[^>]*>")
    private val entityRegex = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    // Títulos de secciones que solo traen datos o palabras sueltas.
    private val auxiliaryTitleRegex = Regex(
        "^(?:(?:lista de )?(?:abreviaturas|siglas|acr[oó]nimos)(?: y (?:siglas|acr[oó]nimos|abreviaturas))?" +
            "|notas?(?: del (?:autor|editor|traductor))?" +
            "|notes" +
            "|glosario" +
            "|[ií]ndice (?:anal[ií]tico|onom[aá]stico|alfab[eé]tico|de nombres|de materias|de t[eé]rminos))$"
    )
    private val headingRegex = Regex("<h[1-6][^>]*>(.*?)</h[1-6]\\s*>", regexOptions)

    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "bull" to "•", "middot" to "·",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "iexcl" to "¡", "iquest" to "¿",
        "ordf" to "ª", "ordm" to "º", "deg" to "°", "copy" to "©", "reg" to "®", "sect" to "§",
        "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú",
        "ntilde" to "ñ", "Ntilde" to "Ñ", "uuml" to "ü", "Uuml" to "Ü",
        "agrave" to "à", "egrave" to "è", "igrave" to "ì", "ograve" to "ò", "ugrave" to "ù",
        "acirc" to "â", "ecirc" to "ê", "icirc" to "î", "ocirc" to "ô", "ucirc" to "û",
        "auml" to "ä", "euml" to "ë", "iuml" to "ï", "ouml" to "ö",
        "ccedil" to "ç", "Ccedil" to "Ç", "atilde" to "ã", "otilde" to "õ"
    )

    fun parse(uri: Uri): ParsedEpub {
        val mainTitles = mutableListOf<String>()
        val mainContents = mutableListOf<List<String>>()
        val appendixTitles = mutableListOf<String>()
        val appendixContents = mutableListOf<List<String>>()
        val mainHidden = mutableListOf<Boolean>()
        val appendixHidden = mutableListOf<Boolean>()

        val inputStream = contentResolver.openInputStream(uri) ?: return ParsedEpub(mainTitles, mainContents)
        val zip = ZipInputStream(inputStream)

        var containerXml = ""
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name.equals("META-INF/container.xml", ignoreCase = true)) {
                containerXml = String(zip.readBytes(), Charsets.UTF_8)
                break
            }
            entry = zip.nextEntry
        }
        zip.close()
        if (containerXml.isEmpty()) return ParsedEpub(mainTitles, mainContents)

        val opfPath = parseContainerXml(containerXml) ?: return ParsedEpub(mainTitles, mainContents)
        val opfPathDecoded = Uri.decode(opfPath)
        val opfKey = key(opfPathDecoded)

        val zip2 = ZipInputStream(contentResolver.openInputStream(uri))
        var opfContent = ""
        var opfDir = ""
        entry = zip2.nextEntry
        while (entry != null) {
            if (key(entry.name) == opfKey) {
                opfContent = String(zip2.readBytes(), Charsets.UTF_8)
                opfDir = File(opfPathDecoded).parent ?: ""
                break
            }
            entry = zip2.nextEntry
        }
        zip2.close()
        if (opfContent.isEmpty()) return ParsedEpub(mainTitles, mainContents)

        val (items, spine) = parseOpf(opfContent, opfDir)
        if (spine.isEmpty()) return ParsedEpub(mainTitles, mainContents)

        val zip3 = ZipInputStream(contentResolver.openInputStream(uri))
        val filesMap = mutableMapOf<String, String>()
        entry = zip3.nextEntry
        while (entry != null) {
            val entryKey = key(entry.name)
            if (items.containsKey(entryKey)) {
                filesMap[entryKey] = String(zip3.readBytes(), Charsets.UTF_8)
            }
            entry = zip3.nextEntry
        }
        zip3.close()

        val MIN_CHAR_COUNT = 500

        for (href in spine) {
            val rawHtml = filesMap[href] ?: continue
            val cleanHtml = removeNonContent(rawHtml)
            val paragraphs = splitHtmlIntoParagraphs(cleanHtml)

            if (paragraphs.isEmpty()) continue

            val totalLength = paragraphs.sumOf { it.length }
            val isMainChapter = totalLength >= MIN_CHAR_COUNT

            // Extraer título del primer encabezado HTML
            val extractedTitle = extractTitleFromHtml(cleanHtml)

            if (isMainChapter) {
                val title = when {
                    extractedTitle != null -> extractedTitle
                    mainTitles.size == 0 -> "Introducción"
                    mainTitles.size == 1 -> "Prólogo"
                    else -> {
                        val romanNumber = convertToRoman(mainTitles.size - 1)
                        "Capítulo $romanNumber"
                    }
                }
                mainTitles.add(title)
                mainContents.add(paragraphs)
                mainHidden.add(isAuxiliaryTitle(title))
            } else {
                val appendixIndex = appendixTitles.size + 1
                val title = extractedTitle ?: "Apéndice $appendixIndex"
                appendixTitles.add(title)
                appendixContents.add(paragraphs)
                appendixHidden.add(true) // secciones cortas: datos sueltos, se ocultan por defecto
            }
        }

        // Combinar listas: principales primero, luego apéndices
        val finalTitles = mutableListOf<String>().apply {
            addAll(mainTitles)
            addAll(appendixTitles)
        }
        val finalContents = mutableListOf<List<String>>().apply {
            addAll(mainContents)
            addAll(appendixContents)
        }

        val finalHidden = mutableListOf<Boolean>().apply {
            addAll(mainHidden)
            addAll(appendixHidden)
        }

        return ParsedEpub(finalTitles, finalContents, finalHidden)
    }

    private fun isAuxiliaryTitle(title: String): Boolean {
        return auxiliaryTitleRegex.matches(title.trim().trimEnd('.', ':').lowercase())
    }

    // Quita head, script, style y comentarios (con su contenido).
    private fun removeNonContent(html: String): String {
        return html
            .replace(commentRegex, "")
            .replace(headRegex, "")
            .replace(scriptStyleRegex, "")
    }

    private fun splitHtmlIntoParagraphs(html: String): List<String> {
        // Se parte por etiquetas de bloque; en cada trozo se quitan las etiquetas restantes
        // (cursiva, negrita, enlaces...) y se decodifican las entidades.
        val paragraphs = html.split(blockTagRegex)
            .map { htmlToPlainText(it) }
            .filter { it.isNotBlank() }

        return if (paragraphs.isEmpty()) {
            val whole = htmlToPlainText(html)
            if (whole.isBlank()) emptyList() else listOf(whole)
        } else {
            paragraphs
        }
    }

    private fun extractTitleFromHtml(html: String): String? {
        val match = headingRegex.find(html)
        return match?.groupValues?.get(1)?.let { htmlToPlainText(it, " ") }?.takeIf { it.isNotBlank() }
    }

    private fun parseContainerXml(xml: String): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return parser.getAttributeValue(null, "full-path")
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseOpf(opfXml: String, opfDir: String): Pair<MutableMap<String, Pair<String, String>>, MutableList<String>> {
        val items = mutableMapOf<String, Pair<String, String>>()
        val idToHref = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(opfXml.reader())
            var eventType = parser.eventType
            var insideSpine = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val mediaType = parser.getAttributeValue(null, "media-type")
                                if (href != null && (mediaType?.contains("xhtml") == true || mediaType?.contains("html") == true)) {
                                    val fullHref = resolveHref(opfDir, href)
                                    items[fullHref] = Pair(mediaType ?: "", "")
                                    if (id != null) idToHref[id] = fullHref
                                }
                            }
                            "itemref" -> {
                                if (insideSpine) {
                                    val idref = parser.getAttributeValue(null, "idref")
                                    val href = idToHref[idref]
                                    if (href != null) spine.add(href)
                                }
                            }
                            "spine" -> insideSpine = true
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "spine") insideSpine = false
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(items, spine)
    }

    // Ruta de un href del OPF: decodifica %20 etc., quita #fragmento y resuelve ../ y ./
    private fun resolveHref(baseDir: String, href: String): String {
        val clean = Uri.decode(href.substringBefore('#').substringBefore('?'))
        return key(if (baseDir.isNotEmpty()) "$baseDir/$clean" else clean)
    }

    // Llave para comparar rutas: normalizada y sin distinguir mayúsculas.
    private fun key(path: String): String = normalizePath(path).lowercase()

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        for (seg in path.replace('\\', '/').split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            } else {
                parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun htmlToPlainText(html: String, tagReplacement: String = ""): String {
        val noTags = html.replace(anyTagRegex, tagReplacement)
        return decodeEntities(noTags)
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return entityRegex.replace(text) { m ->
            val ent = m.groupValues[1]
            when {
                ent.startsWith("#x") || ent.startsWith("#X") -> codePointToString(ent.substring(2).toIntOrNull(16), m.value)
                ent.startsWith("#") -> codePointToString(ent.substring(1).toIntOrNull(), m.value)
                else -> namedEntities[ent] ?: m.value
            }
        }
    }

    private fun codePointToString(cp: Int?, fallback: String): String {
        return if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else fallback
    }

    private fun convertToRoman(num: Int): String {
        if (num < 1) return ""
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = num
        val roman = StringBuilder()
        for (i in values.indices) {
            while (n >= values[i]) {
                n -= values[i]
                roman.append(symbols[i])
            }
        }
        return roman.toString()
    }
}
