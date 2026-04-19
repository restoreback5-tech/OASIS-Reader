package com.oasis.reader

import android.content.ContentResolver
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class EpubParser(private val contentResolver: ContentResolver) {

    data class ParsedEpub(
        val chapterTitles: List<String>,
        val chapterContents: List<List<String>>
    )

    /**
     * Parsea el EPUB completo extrayendo títulos y contenidos.
     * Usa exclusivamente el Spine del OPF para garantizar el orden correcto.
     * Los títulos se extraen de los headers HTML (<h1>-<h6>) o se generan genéricos.
     */
    fun parse(uri: Uri): ParsedEpub {
        // 1. Leer Container para encontrar el OPF
        val inputStream = contentResolver.openInputStream(uri) ?: return ParsedEpub(emptyList(), emptyList())
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

        if (containerXml.isEmpty()) return ParsedEpub(emptyList(), emptyList())

        val opfPath = parseContainerXml(containerXml) ?: return ParsedEpub(emptyList(), emptyList())
        val opfDir = File(opfPath).parent ?: ""

        // 2. Leer OPF y Contenidos en una sola pasada
        val zip2 = ZipInputStream(contentResolver.openInputStream(uri))
        var opfContent = ""
        val filesMap = mutableMapOf<String, String>()

        entry = zip2.nextEntry
        while (entry != null) {
            if (entry.name.equals(opfPath, ignoreCase = true)) {                opfContent = String(zip2.readBytes(), Charsets.UTF_8)
            } else {
                // Guardamos contenido XHTML/HTML en memoria
                if (entry.name.endsWith(".xhtml", ignoreCase = true) ||
                    entry.name.endsWith(".html", ignoreCase = true) ||
                    entry.name.endsWith(".htm", ignoreCase = true)) {
                    filesMap[entry.name] = String(zip2.readBytes(), Charsets.UTF_8)
                }
            }
            entry = zip2.nextEntry
        }
        zip2.close()

        if (opfContent.isEmpty()) return ParsedEpub(emptyList(), emptyList())

        val (_, spine) = parseOpf(opfContent, opfDir)
        
        val finalTitles = mutableListOf<String>()
        val finalContents = mutableListOf<List<String>>()
        var chapterCounter = 0

        for (href in spine) {
            val rawHtml = filesMap[href]
            if (rawHtml == null || rawHtml.isBlank()) continue

            // 1. Determinar Título desde HTML
            var title = extractTitleFromHtml(rawHtml)
            
            if (title.isNullOrBlank()) {
                chapterCounter++
                title = "Capítulo $chapterCounter"
            }

            // 2. Extraer Contenido
            val paragraphs = splitHtmlIntoParagraphs(rawHtml)
                .map { htmlToPlainText(it) }
                .filter { it.isNotBlank() }

            if (paragraphs.isNotEmpty()) {
                finalTitles.add(title)
                finalContents.add(paragraphs)
            }
        }

        return ParsedEpub(finalTitles, finalContents)
    }

    private fun splitHtmlIntoParagraphs(html: String): List<String> {
        val paragraphs = mutableListOf<String>()
        // Eliminar head y scripts para limpiar ruido        val bodyContent = html.replace(Regex("(?s)<head>.*?</head>"), "")
            .replace(Regex("(?s)<script[^>]*>.*?</script>"), "")
        
        // Dividir por etiquetas de bloque significativas
        val parts = bodyContent.split(Regex("(?i)<p[^>]*>|</p>|<div[^>]*>|</div>|<br[^>]*>|<h[1-6][^>]*>|</h[1-6]>"))
        
        for (part in parts) {
            val trimmed = part.trim()
            // Filtrar fragmentos que sean puramente etiquetas o vacíos
            if (trimmed.isNotEmpty() && !trimmed.matches(Regex("^<[^>]*>$"))) {
                paragraphs.add(trimmed)
            }
        }
        
        // Si no se encontraron párrafos estructurados, devolver el texto limpio completo
        return if (paragraphs.isEmpty()) listOf(htmlToPlainText(bodyContent)) else paragraphs
    }

    private fun extractTitleFromHtml(html: String): String? {
        // Buscar h1, luego h2, etc.
        val headerRegex = Regex("""<h([1-6])[^>]*>(.*?)</h\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val match = headerRegex.find(html)
        return match?.groupValues?.get(2)?.let { htmlToPlainText(it) }?.takeIf { it.isNotBlank() }
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
            val factory = XmlPullParserFactory.newInstance()            val parser = factory.newPullParser()
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
                                if (href != null) {
                                    val fullHref = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                                    items[fullHref] = Pair(mediaType ?: "", id ?: "")
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

    private fun decodeHtmlEntities(text: String): String {
        var result = text
        result = result.replace("&nbsp;", " ")
        result = result.replace("&amp;", "&")
        result = result.replace("&lt;", "<")
        result = result.replace("&gt;", ">")
        result = result.replace("&quot;", "\"")
        result = result.replace("&#39;", "'")
        result = result.replace("&#x27;", "'")        result = result.replace("&apos;", "'")
        return result
    }

    private fun htmlToPlainText(html: String): String {
        // Eliminar etiquetas
        val withoutTags = html.replace(Regex("<[^>]*>"), " ")
        val decoded = decodeHtmlEntities(withoutTags)
        // Colapsar espacios en blanco
        return decoded.replace(Regex("\\s+"), " ").trim()
    }
}
