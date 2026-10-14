package com.oasis.reader

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/**
 * Excepciones específicas del parser de PDF.
 * ReaderActivity las captura y muestra el mensaje al usuario.
 */
class PdfProtegidoException(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)
class PdfDanadoException(mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)
class PdfEscaneadoException(mensaje: String) : Exception(mensaje)

/**
 * Parser de PDF para OASIS-Reader.
 *
 * Devuelve exactamente la misma estructura que [EpubParser.ParsedEpub],
 * por lo que ReaderActivity no necesita cambiar sus tipos.
 *
 * IMPORTANTE: la extracción es potencialmente lenta. Llamar a [parse]
 * desde un hilo de fondo (Thread o Corrutinas), nunca en el hilo principal.
 *
 * Tras un parse exitoso con OCR de mala calidad, [ultimaAdvertencia]
 * contiene el texto de advertencia (null si el OCR parece limpio).
 * NUNCA se corrige el texto automáticamente: en textos jurídicos, cambiar
 * una palabra es peor que mostrar el error.
 */
class PdfParser(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** Advertencia del último parse exitoso (OCR de mala calidad). Null si fue limpio. */
    var ultimaAdvertencia: String? = null
        private set

    // ---------------------------------------------------------------------
    // Expresiones regulares de limpieza y detección
    // ---------------------------------------------------------------------

    // Palabra partida con guion de fin de línea: "consti-\ntucional"
    private val guionFinLineaRegex = Regex("([\\p{L}])\\s*[-–]\\s*\\n\\s*([\\p{L}])")

    // Línea que solo contiene un número (posible número de página), con guiones opcionales: "- 12 -"
    private val lineaSoloNumerosRegex = Regex("^[-–—\\s]*\\d+[-–—\\s]*$")

    // Títulos de nivel superior al inicio de línea.
    private val tituloRegex = Regex(
        "^\\s*(LIBRO|T[ÍI]TULO|CAP[ÍI]TULO|SECCI[ÓO]N|PARTE|UNIDAD|TEMA|LECCI[ÓO]N)\\b[\\p{L}\\p{Nd}\\p{Punct}\\s]{0,78}$",
        RegexOption.IGNORE_CASE
    )

    // Línea que termina con un número de página suelto ("LIBRO I  42")
    private val numeroPaginaFinalRegex = Regex("\\s\\d{1,4}\\s*$")

    // Puntos guía de índice ("TÍTULO SEGUNDO ........ 45")
    private val puntosGuiaRegex = Regex("\\.{3,}")

    // Abreviaturas que NO cierran párrafo aunque terminen en punto.
    private val abreviaturasRegex = Regex(
        "(Sr\\.|Sra\\.|Dr\\.|Dra\\.|Lic\\.|Ing\\.|C\\.P\\.|etc\\.|aprox\\.|Art\\.|arts?\\.|no\\.)$",
        RegexOption.IGNORE_CASE
    )

    // Misma expresión que EpubParser.kt para secciones auxiliares.
    private val auxiliaryTitleRegex = Regex(
        "^(?:(?:lista de )?(?:abreviaturas|siglas|acr[oó]nimos)(?: y (?:siglas|acr[oó]nimos|abreviaturas))?" +
            "|notas?(?: del (?:autor|editor|traductor))?" +
            "|notes" +
            "|glosario" +
            "|[ií]ndice (?:anal[ií]tico|onom[aá]stico|alfab[eé]tico|de nombres|de materias|de t[eé]rminos))$"
    )

    // Palabra con dígitos mezclados con letras (OCR de mala calidad: "CONST1-JYENTE")
    private val palabraConDigitoRegex = Regex("^(?=.*\\p{L})(?=.*\\d)[\\p{L}\\p{Nd}]+$")

    private val caracteresExtraiblesMinimos = 500

    // ---------------------------------------------------------------------
    // API pública
    // ---------------------------------------------------------------------

    fun parse(uri: Uri): EpubParser.ParsedEpub {
        ultimaAdvertencia = null

        // 1. Extraer texto por página (lanza excepciones si está dañado o protegido)
        val paginas = extraerPaginas(uri)

        // 2. Limpieza: guiones, números de página, encabezados repetidos
        val lineasPorPagina = limpiarPaginas(paginas)

        // 3. Texto total para detección de PDF escaneado y calidad de OCR
        val textoTotal = lineasPorPagina.joinToString("\n") { pagina ->
            pagina.joinToString("\n") { it }
        }
        if (textoTotal.replace("\\s".toRegex(), "").length < caracteresExtraiblesMinimos) {
            throw PdfEscaneadoException(
                "Este PDF parece escaneado, no tiene texto extraíble. " +
                    "Conviértelo con OCR o usa la versión EPUB."
            )
        }
        evaluarCalidadOcr(textoTotal)

        // 4. Dividir en capítulos
        val capitulos = dividirEnCapitulos(lineasPorPagina)

        if (capitulos.isEmpty()) {
            throw PdfDanadoException("No se pudo extraer contenido legible del PDF")
        }

        val titulos = mutableListOf<String>()
        val contenidos = mutableListOf<List<String>>()
        val ocultos = mutableListOf<Boolean>()
        for ((titulo, parrafos) in capitulos) {
            if (parrafos.isEmpty()) continue
            titulos.add(titulo)
            contenidos.add(parrafos)
            ocultos.add(isAuxiliaryTitle(titulo))
        }
        if (titulos.isEmpty()) {
            throw PdfDanadoException("No se pudo extraer contenido legible del PDF")
        }
        return EpubParser.ParsedEpub(titulos, contenidos, ocultos)
    }

    // ---------------------------------------------------------------------
    // 1. Extracción con PdfBox-Android
    // ---------------------------------------------------------------------

    /**
     * Extrae el texto de cada página. No cierra nada manualmente: usa `use {}`
     * tanto para el InputStream como para el PDDocument.
     */
    private fun extraerPaginas(uri: Uri): List<String> {
        try {
            val paginas = mutableListOf<String>()
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { documento ->
                    if (documento.isEncrypted) {
                        throw PdfProtegidoException(
                            "El PDF está protegido con contraseña y no se puede leer"
                        )
                    }
                    val total = documento.numberOfPages
                    if (total <= 0) {
                        throw PdfDanadoException("El PDF no contiene páginas")
                    }
                    val stripper = PDFTextStripper()
                    stripper.sortByPosition = true // conserva el layout del PDF
                    for (pagina in 1..total) {
                        stripper.startPage = pagina
                        stripper.endPage = pagina
                        paginas.add(stripper.getText(documento))
                    }
                }
            } ?: throw PdfDanadoException("No se pudo abrir el archivo PDF")
            return paginas
        } catch (e: PdfProtegidoException) {
            throw e
        } catch (e: PdfDanadoException) {
            throw e
        } catch (e: IOException) {
            // PdfBox lanza IOException InvalidCryptFilterException / etc. para PDFs
            // cifrados sin contraseña o estructuras corruptas.
            val mensaje = e.message.orEmpty().lowercase()
            if (mensaje.contains("password") || mensaje.contains("crypt") ||
                mensaje.contains("encrypt") || mensaje.contains("decrypt")
            ) {
                throw PdfProtegidoException(
                    "El PDF está protegido con contraseña y no se puede leer", e
                )
            }
            throw PdfDanadoException("El PDF está dañado o no es un PDF válido: ${e.message}", e)
        } catch (e: Exception) {
            throw PdfDanadoException("Error al procesar el PDF: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------------
    // 2. Limpieza por página
    // ---------------------------------------------------------------------

    private fun limpiarPaginas(paginas: List<String>): List<List<String>> {
        // a) Unir palabras partidas por guion de fin de línea, página por página.
        val paginasUnidas = paginas.map { it.replace(guionFinLineaRegex) { m ->
            m.groupValues[1] + m.groupValues[2]
        } }

        // b) Descartar líneas vacías y líneas que solo contienen números.
        val paginasPorLineas = paginasUnidas.map { texto ->
            texto.split('\n').map { it.trim() }
                .filter { it.isNotEmpty() && !lineaSoloNumerosRegex.matches(it) }
        }

        // c) Detectar encabezados/pies repetidos: se cuentan la primera y la última
        //    línea de cada página; si la misma línea (normalizando dígitos) aparece
        //    en más del 60% de las páginas, se elimina de todas.
        val firmasRepetidas = detectarFirmasRepetidas(paginasPorLineas)

        // d) Colapsar espacios múltiples en cada línea superviviente.
        return paginasPorLineas.map { lineas ->
            lineas.filter { normalizarFirma(it) !in firmasRepetidas }
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotEmpty() }
        }
    }

    /** Normaliza una línea para comparar encabezados: espacios colapsados y dígitos como '#'. */
    private fun normalizarFirma(linea: String): String =
        linea.replace(Regex("\\s+"), " ").trim().replace(Regex("\\d"), "#")

    /**
     * Devuelve el conjunto de "firmas" de línea que aparecen como primera o última
     * línea de más del 60% de las páginas (encabezados y pies de página repetidos).
     */
    private fun detectarFirmasRepetidas(paginasPorLineas: List<List<String>>): Set<String> {
        if (paginasPorLineas.size < 3) return emptySet() // con pocas páginas no hay evidencia
        val contador = HashMap<String, Int>()
        for (lineas in paginasPorLineas) {
            if (lineas.isEmpty()) continue
            val candidatas = mutableSetOf(normalizarFirma(lineas.first()))
            if (lineas.size > 1) candidatas.add(normalizarFirma(lineas.last()))
            for (firma in candidatas) {
                if (firma.isEmpty()) continue
                contador[firma] = (contador[firma] ?: 0) + 1
            }
        }
        val umbral = paginasPorLineas.size * 0.6
        return contador.filterValues { it > umbral }.keys
    }

    // ---------------------------------------------------------------------
    // 3. Calidad del OCR
    // ---------------------------------------------------------------------

    /**
     * Si más del 5% de las palabras mezclan dígitos con letras (ej. "CONST1-JYENTE"),
     * deja una advertencia en [ultimaAdvertencia]. No lanza excepción: el libro se
     * puede leer, pero con reservas. Tampoco corrige nada automáticamente.
     */
    private fun evaluarCalidadOcr(texto: String) {
        val palabras = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (palabras.isEmpty()) return
        val sospechosas = palabras.count {
            val limpia = it.trim(' ', '-', '.', ',', ';', ':', '(', ')', '"', '\'', '¿', '?', '¡', '!')
            palabraConDigitoRegex.matches(limpia)
        }
        val proporcion = sospechosas.toDouble() / palabras.size
        if (proporcion > 0.05) {
            val porcentaje = (proporcion * 100).toInt()
            ultimaAdvertencia =
                "El texto de este PDF parece tener OCR de mala calidad " +
                    "(~$porcentaje% de palabras con caracteres mezclados). " +
                    "Lee con precaución: puede haber palabras corruptas."
        }
    }

    // ---------------------------------------------------------------------
    // 4. División en capítulos
    // ---------------------------------------------------------------------

    private fun dividirEnCapitulos(paginasPorLineas: List<List<String>>): List<Pair<String, List<String>>> {
        val lineas = paginasPorLineas.flatten()

        // PRIORIDAD 1: títulos de nivel superior detectados al inicio de línea.
        val indicesTitulos = mutableListOf<Int>()
        for (i in lineas.indices) {
            if (esTituloDeCapitulo(lineas[i])) indicesTitulos.add(i)
        }

        val capitulos = mutableListOf<Pair<String, List<String>>>()
        if (indicesTitulos.isNotEmpty()) {
            // Contenido previo al primer título = introducción.
            val intro = lineas.subList(0, indicesTitulos.first())
            val parrafosIntro = construirParrafos(intro)
            if (parrafosIntro.isNotEmpty()) {
                capitulos.add(Pair("Introducción", parrafosIntro))
            }
            for (t in indicesTitulos.indices) {
                val inicio = indicesTitulos[t] + 1 // el título no entra al contenido
                val fin = if (t + 1 < indicesTitulos.size) indicesTitulos[t + 1] else lineas.size
                val titulo = lineas[indicesTitulos[t]].trim()
                capitulos.add(Pair(titulo, construirParrafos(lineas.subList(inicio, fin))))
            }
            return capitulos
        }

        // PRIORIDAD 2: sin títulos detectados, dividir en grupos de 10 páginas.
        val totalPaginas = paginasPorLineas.size
        if (totalPaginas == 0) return capitulos
        var inicio = 0
        while (inicio < totalPaginas) {
            val fin = minOf(inicio + 10, totalPaginas)
            val titulo = if (inicio == 0 && fin == totalPaginas) {
                "Documento completo"
            } else {
                "Páginas ${inicio + 1}–$fin"
            }
            val parrafos = construirParrafos(paginasPorLineas.subList(inicio, fin).flatten())
            if (parrafos.isNotEmpty()) capitulos.add(Pair(titulo, parrafos))
            inicio = fin
        }
        return capitulos
    }

    /**
     * ¿Es esta línea un título de nivel superior?
     * Debe empezar por LIBRO/TÍTULO/CAPÍTULO/..., medir menos de 80 caracteres,
     * no tener puntos guía de índice y no terminar con número de página.
     */
    private fun esTituloDeCapitulo(linea: String): Boolean {
        val limpia = linea.trim()
        if (limpia.length >= 80) return false
        if (!tituloRegex.containsMatchIn(limpia)) return false
        if (puntosGuiaRegex.containsMatchIn(limpia)) return false
        if (numeroPaginaFinalRegex.containsMatchIn(limpia)) return false
        return true
    }

    /**
     * Agrupa líneas en párrafos. Un párrafo termina en línea en blanco, o cuando
     * una línea termina en puntuación de cierre (sin abreviatura), es más corta
     * que el ancho típico del bloque, y la siguiente empieza con mayúscula.
     * Los "Artículo N" quedan como párrafos internos del capítulo actual,
     * nunca como capítulos propios.
     */
    private fun construirParrafos(lineas: List<String>): List<String> {
        if (lineas.isEmpty()) return emptyList()
        val anchoMax = lineas.maxOf { it.length }.coerceAtLeast(1)
        val parrafos = mutableListOf<String>()
        val actual = StringBuilder()
        for (i in lineas.indices) {
            val linea = lineas[i]
            if (linea.isBlank()) {
                if (actual.isNotEmpty()) {
                    parrafos.add(actual.toString().trim())
                    actual.setLength(0)
                }
                continue
            }
            actual.append(linea)
            val siguiente = lineas.getOrNull(i + 1)
            if (cierraParrafo(linea, siguiente, anchoMax)) {
                parrafos.add(actual.toString().trim())
                actual.setLength(0)
            } else if (siguiente != null) {
                actual.append(' ')
            }
        }
        if (actual.isNotEmpty()) parrafos.add(actual.toString().trim())
        return parrafos.filter { it.isNotEmpty() }
    }

    private fun cierraParrafo(linea: String, siguiente: String?, anchoMax: Int): Boolean {
        if (siguiente == null || siguiente.isBlank()) return true
        if (!Regex("[.!?:…\"']$").containsMatchIn(linea)) return false
        if (abreviaturasRegex.containsMatchIn(linea)) return false
        // Línea corta que acaba en punto + siguiente en mayúscula = fin de párrafo.
        if (linea.length < anchoMax * 0.75 && siguiente.first().isUpperCase()) return true
        // La última línea del bloque también cierra.
        return false
    }

    // ---------------------------------------------------------------------
    // Secciones auxiliares (misma regla que EpubParser.kt)
    // ---------------------------------------------------------------------

    private fun isAuxiliaryTitle(titulo: String): Boolean =
        auxiliaryTitleRegex.matches(titulo.trim().trimEnd('.', ':').lowercase())
}
