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

    data class ChapterNode(
        val title: String,
        val src: String,
        val children: List<ChapterNode> = emptyList()
    )

    /**
     * Extrae la estructura jerárquica del NCX (Índice)
     */
    fun getHierarchicalChapters(uri: Uri): List<ChapterNode> {
        val inputStream = contentResolver.openInputStream(uri) ?: return emptyList()
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

        if (containerXml.isEmpty()) return emptyList()

        val opfPath = parseContainerXml(containerXml) ?: return emptyList()
        val opfDir = File(opfPath).parent ?: ""

        // Segunda pasada para OPF y NCX
        val zip2 = ZipInputStream(contentResolver.openInputStream(uri))
        var opfContent = ""
        var ncxPath: String? = null        
        entry = zip2.nextEntry
        while (entry != null) {
            if (entry.name.equals(opfPath, ignoreCase = true)) {
                opfContent = String(zip2.readBytes(), Charsets.UTF_8)
            }
            entry = zip2.nextEntry
        }
        zip2.close()

        if (opfContent.isEmpty()) return emptyList()

        ncxPath = extractNcxPathFromOpf(opfContent, opfDir)
        if (ncxPath.isNullOrEmpty()) return emptyList()

        // Tercera pasada para NCX (Podría optimizarse a una sola pasada guardando bytes, pero mantenemos claridad)
        val zip3 = ZipInputStream(contentResolver.openInputStream(uri))
        val nodes = parseNcxFromZip(zip3, ncxPath)
        zip3.close()
        
        return nodes
    }

    private fun extractNcxPathFromOpf(opfXml: String, opfDir: String): String? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(opfXml.reader())
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val id = parser.getAttributeValue(null, "id")
                    val href = parser.getAttributeValue(null, "href")
                    val mediaType = parser.getAttributeValue(null, "media-type")
                    if (mediaType == "application/x-dtbncx+xml" || id == "ncx") {
                        return if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun parseNcxFromZip(zip: ZipInputStream, ncxPath: String): List<ChapterNode> {
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name.equals(ncxPath, ignoreCase = true)) {                val content = String(zip.readBytes(), Charsets.UTF_8)
                return parseNcxContent(content)
            }
            entry = zip.nextEntry
        }
        return emptyList()
    }

    private fun parseNcxContent(ncxXml: String): List<ChapterNode> {
        val nodes = mutableListOf<ChapterNode>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(ncxXml.toByteArray(Charsets.UTF_8)), null)
            
            var eventType = parser.eventType
            // Stack para manejar la jerarquía: Pair<ListaHijos, TítuloActual, SrcActual>
            val stack = ArrayDeque<MutableList<ChapterNode>>()
            var currentTitle = ""
            var currentSrc = ""
            
            // Inicializamos la lista raíz
            stack.addLast(mutableListOf())

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "navPoint" -> {
                                // Nuevo nivel de jerarquía
                                stack.addLast(mutableListOf())
                                currentTitle = ""
                                currentSrc = ""
                            }
                            "text" -> {
                                // El título puede estar anidado, nextText() consume hasta END_TAG
                                currentTitle = parser.nextText().trim()
                            }
                            "content" -> {
                                currentSrc = parser.getAttributeValue(null, "src") ?: ""
                                // Normalizar src (eliminar fragmentos # si es necesario para matching)
                                if (currentSrc.contains("#")) {
                                    currentSrc = currentSrc.substringBefore("#")
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "navPoint") {
                            val children = stack.removeLast()                            
                            val newNode = ChapterNode(currentTitle, currentSrc, children)
                            
                            // Añadir al padre
                            if (stack.isNotEmpty()) {
                                stack.last().add(newNode)
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            
            // La lista raíz contiene los nodos de primer nivel
            return stack.firstOrNull() ?: emptyList()

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    /**
     * Parsea el EPUB completo extrayendo títulos y contenidos.
     * Usa el NCX para títulos si está disponible, sino fallback a HTML headers.
     */
    fun parse(uri: Uri): ParsedEpub {
        // 1. Obtener estructura NCX (Títulos jerárquicos)
        val ncxNodes = getHierarchicalChapters(uri)
        val ncxMap = mutableMapOf<String, String>()
        flattenNcxNodes(ncxNodes, ncxMap)

        // 2. Leer Container y OPF para obtener el Spine (Orden de lectura)
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
        // 3. Leer OPF y Contenidos en una sola pasada adicional
        val zip2 = ZipInputStream(contentResolver.openInputStream(uri))
        var opfContent = ""
        val filesMap = mutableMapOf<String, String>()
        val itemsSet = mutableSetOf<String>() // Para saber qué archivos son relevantes
        
        // Primero necesitamos parsear el OPF para saber qué items están en el spine
        // Pero como estamos leyendo el zip linealmente, leemos todo a memoria o buscamos específicamente.
        // Estrategia: Leer OPF primero (suele ser pequeño), parsear spine, luego leer contenidos.
        // Como ZipInputStream es secuencial, es difícil saltar atrás.
        // Opción Robusta: Leer todo el zip a un Map<String, ByteArray> si cabe en memoria, o hacer 2 pasadas.
        // Dado que ya hicimos 1 pasada para NCX, haremos una segunda para OPF + Contenidos.
        
        entry = zip2.nextEntry
        while (entry != null) {
            if (entry.name.equals(opfPath, ignoreCase = true)) {
                opfContent = String(zip2.readBytes(), Charsets.UTF_8)
            } else {
                // Guardamos referencia de archivos XHTML/HTML para leer después si están en el spine
                // No leemos el contenido aún para ahorrar memoria, solo marcamos interés? 
                // No, ZipInputStream no permite seek. Debemos leerlo ahora o repetir pasada.
                // Leemos a memoria si es XHTML.
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

        val (items, spine) = parseOpf(opfContent, opfDir)
        
        val finalTitles = mutableListOf<String>()
        val finalContents = mutableListOf<List<String>>()

        var chapterCounter = 0

        for (href in spine) {
            val rawHtml = filesMap[href]
            if (rawHtml == null || rawHtml.isBlank()) continue

            // 1. Determinar Título
            var title = ncxMap[href] // Intentar obtener del NCX
            
            if (title.isNullOrBlank()) {                title = extractTitleFromHtml(rawHtml) // Fallback a <h1-h6>
            }
            
            if (title.isNullOrBlank()) {
                chapterCounter++
                title = "Sección $chapterCounter" // Fallback genérico
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

    private fun flattenNcxNodes(nodes: List<ChapterNode>, map: MutableMap<String, String>) {
        for (node in nodes) {
            if (node.src.isNotEmpty()) {
                // Si ya existe, priorizamos el nodo más profundo o el primero? 
                // Generalmente el NCX tiene la ruta completa.
                map[node.src] = node.title
            }
            if (node.children.isNotEmpty()) {
                flattenNcxNodes(node.children, map)
            }
        }
    }

    private fun splitHtmlIntoParagraphs(html: String): List<String> {
        val paragraphs = mutableListOf<String>()
        // Eliminar head y scripts para limpiar ruido
        val bodyContent = html.replace(Regex("(?s)<head>.*?</head>"), "")
                               .replace(Regex("(?s)<script[^>]*>.*?</script>"), "")
        
        // Dividir por etiquetas de bloque significativas
        val parts = bodyContent.split(Regex("(?i)<p[^>]*>|</p>|<div[^>]*>|</div>|<br[^>]*>|<h[1-6][^>]*>|</h[1-6]>"))
        
        for (part in parts) {
            val trimmed = part.trim()
            // Filtrar fragmentos que sean puramente etiquetas o vacíos
            if (trimmed.isNotEmpty() && !trimmed.matches(Regex("^<[^>]*>$"))) {
                paragraphs.add(trimmed)
            }        }
        
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
                                
                                if (href != null) {
                                    val fullHref = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                                    // Guardamos todos los items, no solo xhtml, por si acaso
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
        result = result.replace("&#x27;", "'")
        result = result.replace("&apos;", "'")
        return result
    }

    private fun htmlToPlainText(html: String): String {
        // Eliminar etiquetas
        val withoutTags = html.replace(Regex("<[^>]*>"), " ")
        val decoded = decodeHtmlEntities(withoutTags)
        // Colapsar espacios en blanco
        return decoded.replace(Regex("\\s+"), " ").trim()    }
}
