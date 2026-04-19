package com.oasis.reader

import android.content.ContentResolver
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipInputStream

class EpubParser(private val contentResolver: ContentResolver) {

    data class ParsedEpub(
        val chapterTitles: List<String>,
        val chapterContents: List<List<String>>
    )

    /**
     * Parsea el EPUB concatenando todo el HTML en orden y dividiendo por <h1>.
     */
    fun parse(uri: Uri): ParsedEpub {
        val fullHtml = buildFullHtml(uri) ?: return ParsedEpub(emptyList(), emptyList())
        val chapters = splitByH1(fullHtml)
        
        val titles = mutableListOf<String>()
        val contents = mutableListOf<List<String>>()
        
        for ((titleHtml, bodyHtml) in chapters) {
            val title = cleanHtmlTitle(titleHtml)
            if (title.isBlank()) continue
            
            val paragraphs = extractParagraphs(bodyHtml)
            if (paragraphs.isNotEmpty()) {
                titles.add(title)
                contents.add(paragraphs)
            }
        }
        
        return ParsedEpub(titles, contents)
    }

    private fun buildFullHtml(uri: Uri): String? {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val zip = ZipInputStream(inputStream)
        
        // 1. Obtener container.xml
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
        if (containerXml.isEmpty()) return null
        
        val opfPath = parseContainerXml(containerXml) ?: return null
        val opfDir = File(opfPath).parent ?: ""
        
        // 2. Leer OPF y spine
        val zip2 = ZipInputStream(contentResolver.openInputStream(uri))
        var opfContent = ""
        entry = zip2.nextEntry
        while (entry != null) {
            if (entry.name.equals(opfPath, ignoreCase = true)) {
                opfContent = String(zip2.readBytes(), Charsets.UTF_8)
                break
            }
            entry = zip2.nextEntry
        }
        zip2.close()
        if (opfContent.isEmpty()) return null
        
        val (_, spine) = parseOpf(opfContent, opfDir)
        if (spine.isEmpty()) return null
        
        // 3. Leer todos los archivos HTML en orden y concatenarlos
        val zip3 = ZipInputStream(contentResolver.openInputStream(uri))
        val filesMap = mutableMapOf<String, String>()
        entry = zip3.nextEntry
        while (entry != null) {
            val name = entry.name
            if (name.endsWith(".xhtml", ignoreCase = true) ||
                name.endsWith(".html", ignoreCase = true) ||
                name.endsWith(".htm", ignoreCase = true)) {
                filesMap[name] = String(zip3.readBytes(), Charsets.UTF_8)
            }
            entry = zip3.nextEntry
        }
        zip3.close()
        
        val fullHtml = StringBuilder()
        for (href in spine) {
            val html = filesMap[href]
            if (!html.isNullOrBlank()) {
                // Limpiar scripts y estilos antes de concatenar
                val cleaned = cleanHtml(html)
                fullHtml.append(cleaned).append("\n")
            }
        }
        return fullHtml.toString()
    }

    private fun cleanHtml(html: String): String {
        // Eliminar scripts y estilos
        var result = html.replace(Regex("(?s)<script[^>]*>.*?</script>"), "")
        result = result.replace(Regex("(?s)<style[^>]*>.*?</style>"), "")
        // Eliminar etiquetas de cabecera
        result = result.replace(Regex("(?s)<head>.*?</head>"), "")
        return result
    }

    private fun splitByH1(html: String): List<Pair<String, String>> {
        val chapters = mutableListOf<Pair<String, String>>()
        // Buscar etiquetas <h1> (con o sin atributos)
        val regex = Regex("(?i)<h1[^>]*>(.*?)</h1>", RegexOption.DOT_MATCHES_ALL)
        var lastIndex = 0
        var match = regex.find(html)
        var currentTitle = ""
        var currentStart = 0
        
        while (match != null) {
            if (currentTitle.isNotEmpty()) {
                val body = html.substring(currentStart, match.range.first)
                chapters.add(Pair(currentTitle, body))
            }
            currentTitle = match.groupValues[1].trim()
            currentStart = match.range.last + 1
            match = match.next()
        }
        if (currentTitle.isNotEmpty()) {
            val body = html.substring(currentStart)
            chapters.add(Pair(currentTitle, body))
        }
        return chapters
    }

    private fun extractParagraphs(html: String): List<String> {
        val paragraphs = mutableListOf<String>()
        // Dividir por etiquetas de párrafo y líneas
        val parts = html.split(Regex("(?i)<p[^>]*>|</p>|<div[^>]*>|</div>|<br[^>]*>|<h[2-6][^>]*>|</h[2-6]>"))
        for (part in parts) {
            val text = htmlToPlainText(part).trim()
            if (text.isNotEmpty() && text.length > 10) {
                paragraphs.add(text)
            }
        }
        return paragraphs
    }

    private fun cleanHtmlTitle(html: String): String {
        val withoutTags = html.replace(Regex("<[^>]*>"), "")
        val decoded = decodeHtmlEntities(withoutTags)
        return decoded.trim().let {
            if (it.isBlank() || it.length < 2) "Capítulo" else it
        }
    }

    private fun decodeHtmlEntities(text: String): String {
        var result = text
        result = result.replace("&nbsp;", " ")
        result = result.replace("&amp;", "&")
        result = result.replace("&lt;", "<")
        result = result.replace("&gt;", ">")
        result = result.replace("&quot;", "\"")
        result = result.replace("&#39;", "'")
        result = result.replace("&#x27;", "'")
        result = result.replace("&apos;", "'")
        return result
    }

    private fun htmlToPlainText(html: String): String {
        val withoutTags = html.replace(Regex("<[^>]*>"), " ")
        val decoded = decodeHtmlEntities(withoutTags)
        return decoded.replace(Regex("\\s+"), " ").trim()
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
                                    val fullHref = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
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
}
