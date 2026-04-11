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
        val chapterContents: MutableList<List<String>>
    )

    fun parse(uri: Uri): ParsedEpub {
        val mainTitles = mutableListOf<String>()
        val mainContents = mutableListOf<List<String>>()
        val appendixTitles = mutableListOf<String>()
        val appendixContents = mutableListOf<List<String>>()

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

        val zip2 = ZipInputStream(contentResolver.openInputStream(uri))
        var opfContent = ""
        var opfDir = ""
        entry = zip2.nextEntry
        while (entry != null) {
            if (entry.name.equals(opfPath, ignoreCase = true)) {
                opfContent = String(zip2.readBytes(), Charsets.UTF_8)
                opfDir = opfPath.let { File(it).parent } ?: ""
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
            val name = entry.name
            if (items.containsKey(name)) {
                filesMap[name] = String(zip3.readBytes(), Charsets.UTF_8)
            }
            entry = zip3.nextEntry
        }
        zip3.close()

        val MIN_CHAR_COUNT = 500

        for (href in spine) {
            val rawHtml = filesMap[href] ?: continue
            val plainText = htmlToPlainText(rawHtml)
            val paragraphs = splitHtmlIntoParagraphs(rawHtml)
                .map { htmlToPlainText(it) }
                .filter { it.isNotBlank() }
            
            if (paragraphs.isEmpty()) continue

            val totalLength = plainText.length
            val isMainChapter = totalLength >= MIN_CHAR_COUNT

            // Extraer título del primer encabezado HTML
            val extractedTitle = extractTitleFromHtml(rawHtml)

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
            } else {
                val appendixIndex = appendixTitles.size + 1
                val title = extractedTitle ?: "Apéndice $appendixIndex"
                appendixTitles.add(title)
                appendixContents.add(paragraphs)
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

        return ParsedEpub(finalTitles, finalContents)
    }

    private fun splitHtmlIntoParagraphs(html: String): List<String> {
        val paragraphs = mutableListOf<String>()
        
        // Eliminar contenido de <head> si existe
        val bodyContent = html.replace(Regex("(?s)<head>.*?</head>"), "")
        
        // Dividir por etiquetas <p>, </p>, <div>, </div>, <br>, <h1>-<h6>
        val parts = bodyContent.split(Regex("(?i)<p[^>]*>|</p>|<div[^>]*>|</div>|<br[^>]*>|<h[1-6][^>]*>|</h[1-6]>"))
        
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("<") && !trimmed.endsWith(">")) {
                paragraphs.add(trimmed)
            }
        }
        
        return if (paragraphs.isEmpty()) listOf(htmlToPlainText(html)) else paragraphs
    }

    private fun extractTitleFromHtml(html: String): String? {
        val headerRegex = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", RegexOption.IGNORE_CASE)
        val match = headerRegex.find(html)
        return match?.groupValues?.get(1)?.let { htmlToPlainText(it) }?.takeIf { it.isNotBlank() }
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

    private fun htmlToPlainText(html: String): String {
        return html.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
