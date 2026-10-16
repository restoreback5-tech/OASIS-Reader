package com.oasis.reader

/** El PDF no tiene texto extraíble (es un escaneo). */
class PdfEscaneadoException(message: String) : Exception(message)

/**
 * Convierte el texto crudo de cada página de un PDF en un árbol jerárquico
 * de Nodo. Diseñado específicamente para códigos y leyes:
 *
 *  - No fusiona LIBRO + CAPÍTULO + SECCIÓN en una sola línea (eso era lo que
 *    aplanaba la estructura antes).
 *  - No adivina números de artículos. "Artículo 51 Bis" se queda como
 *    "Artículo 51 Bis".
 *  - No trunca títulos. Un título de 200 caracteres se respeta completo.
 *  - Marca con revisar=true los bloques en duda (OCR dudoso, subtítulo
 *    huérfano sin título padre).
 *
 * Capítulos por orden de confianza:
 *  1. Títulos con palabra clave (LIBRO, CAPÍTULO, SECCIÓN...).
 *  2. Si no hay ninguno, se agrupa por bloques de 10 páginas.
 */
object PdfTextProcessor {

    private const val PAGINAS_POR_GRUPO = 10
    private const val MAX_PARRAFO = 8000
    private const val VENTANA_BORDE = 6

    private val RE_ESPACIOS = Regex("\\s+")
    private val RE_ROMANO = Regex("^(?=[IVXLC])C{0,3}(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")
    private val RE_NUM_PAG = Regex("^\\s*[-–—]?\\s*(\\d{1,4})\\s*[-–—]?\\s*$")
    private val RE_ROMANO_PAG = Regex(
        "^\\s*[-–—]?\\s*(?=[ivxlc])c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})\\s*[-–—]?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val RE_PAGINA_DE = Regex(
        "^\\s*(p[áa]gina|p[áa]g\\.?|page)\\s*\\d{1,4}(\\s*(de|/|of)\\s*\\d{1,4})?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val RE_TOKEN_NUMERAL = Regex("^[\\d.,:;()\\-–—]+$")

    // Palabras clave de nivel superior
    private val RE_TITULO_NIVEL = Regex(
        "^(PARTE|LIBRO|T[ÍI]TULO|CAP[ÍI]TULO|SECCI[ÓO]N|SUBSECCI[ÓO]N|UNIDAD|TEMA|LECCI[ÓO]N|M[ÓO]DULO)\\s+(\\S+)(.*)$",
        RegexOption.IGNORE_CASE
    )
    private val RE_TITULO_TRANSITORIO = Regex(
        "^(ART[ÍI]CULOS\\s+TRANSITORIOS|DISPOSICIONES\\s+TRANSITORIAS|TRANSITORIOS|DISPOSICIONES\\s+FINALES|DISPOSICIONES\\s+COMPLEMENTARIAS)\\s*:?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val RE_ORDINAL = Regex(
        "^(PRIMER[OA]?|SEGUND[OA]|TERCER[OA]?|CUART[OA]|QUINT[OA]|SEXT[OA]|S[ÉE]PTIM[OA]|OCTAV[OA]|" +
            "NOVEN[OA]|D[ÉE]CIM[OA]|UND[ÉE]CIM[OA]|DUOD[ÉE]CIM[OA]|" +
            "VIG[ÉE]SIM[OA]|TRIG[ÉE]SIM[OA]|CUADRAG[ÉE]SIM[OA]|QUINCUAG[ÉE]SIM[OA]|" +
            "SEXAG[ÉE]SIM[OA]|SEPTUAG[ÉE]SIM[OA]|OCTOG[ÉE]SIM[OA]|NONAG[ÉE]SIM[OA]|" +
            "CENT[ÉE]SIM[OA]|VEINTI\\w+|[ÚU]NIC[OA]|PRELIMINAR)$",
        RegexOption.IGNORE_CASE
    )
    private val RE_REFERENCIA_EN_TEXTO = Regex(
        "^(de\\s+(esta|este|la\\s+presente|el\\s+presente|la\\s+ley|el\\s+c[óo]digo)|" +
            "del\\s+(presente|c[óo]digo)|y\\s+siguientes|y\\s+los|y\\s+las|,|;|se\\s|que\\s)",
        RegexOption.IGNORE_CASE
    )
    private val RE_ARTICULO = Regex(
        "^(Art[ií]culo|ART[ÍI]CULO|Art\\.)\\s+(\\d+)(\\s+(Bis|Ter|Qu[aá]ter|Quinquies|Sexies|Septies|Octies|Nonies|Decies|Undecies|Duodecies|[A-Z]))?\\b",
        RegexOption.IGNORE_CASE
    )
    private val RE_MARCADOR = Regex("^(([IVXLC]{1,6}|\\d{1,2})[.)]|[a-zñ]\\)|\\([a-zñ0-9]{1,4}\\)|[•▪●])\\s+")
    private val RE_FIN_FRASE_FUERTE = Regex("[.:?!]$")
    private val RE_LIDERES = Regex("(\\.{3,}|(?:\\.\\s){3,}|…)")
    private val RE_PAGINA_AL_FINAL = Regex("\\s\\d{1,4}$")
    private val RE_ALFANUM_OK = Regex("^[(\\[]?\\d+(-?[A-Za-z]{1,5}|[ºo°ª])?[)\\].,;:]*$")

    // Niveles por palabra clave (el 0 se reserva para la raíz artificial)
    private val NIVEL_POR_CLAVE = mapOf(
        "PARTE" to 0, "parte" to 0,
        "LIBRO" to 1, "libro" to 1,
        "TÍTULO" to 2, "TITULO" to 2, "título" to 2, "titulo" to 2,
        "CAPÍTULO" to 3, "CAPITULO" to 3, "capítulo" to 3, "capitulo" to 3,
        "SECCIÓN" to 4, "SECCION" to 4, "sección" to 4, "seccion" to 4,
        "SUBSECCIÓN" to 5, "SUBSECCION" to 5,
        "UNIDAD" to 2, "TEMA" to 2, "LECCIÓN" to 2, "LECCION" to 2, "MÓDULO" to 2, "MODULO" to 2
    )

    private class Linea(val texto: String, val pagina: Int)

    // ----------------------------------------------------------------------
    // API pública
    // ----------------------------------------------------------------------

    @Throws(PdfEscaneadoException::class)
    fun procesar(paginasCrudas: List<String>): DocumentoEstructurado {
        val n = paginasCrudas.size
        if (n == 0) throw PdfEscaneadoException("El PDF no tiene páginas.")

        // 1) ¿Escaneo?
        var sinTexto = 0
        var totalCaracteres = 0L
        for (p in paginasCrudas) {
            val c = p.count { !it.isWhitespace() }
            totalCaracteres += c
            if (c < 25) sinTexto++
        }
        val promedio = totalCaracteres.toDouble() / n
        if (sinTexto.toDouble() / n >= 0.6 || promedio < 60.0) {
            throw PdfEscaneadoException(
                "Este PDF parece un escaneo: solo ${n - sinTexto} de $n páginas tienen texto " +
                    "seleccionable. Por ahora solo se pueden leer PDFs con texto; usa una versión con OCR."
            )
        }

        // 2) Limpiar páginas (encabezados, pies, números)
        val paginas = limpiarPaginas(paginasCrudas)

        // 3) Aplanar todas las líneas conservando su página de origen
        val lineas = ArrayList<Linea>()
        for ((i, lista) in paginas.withIndex()) {
            for (raw in lista) {
                val limpia = quitarGuionSuave(raw).replace(RE_ESPACIOS, " ").trim()
                if (limpia.isNotEmpty()) lineas.add(Linea(limpia, i))
            }
        }
        if (lineas.isEmpty()) throw PdfEscaneadoException("No se encontró texto legible en el PDF.")

        // 4) Construir el árbol
        val raiz = construirArbol(lineas)

        // 5) Calidad (OCR dudoso) y avisos
        val avisos = mutableListOf<String>()
        if (textoSospechoso(lineas)) {
            avisos.add("El texto parece de un OCR deficiente; puede haber errores de lectura. Verifica cifras y artículos.")
        }
        if (sinTexto > 0) avisos.add("$sinTexto página(s) sin texto se omitieron.")

        // 6) Recalcular IDs y paths, y construir índice de artículos
        raiz.recalcularIds()
        val indice = construirIndiceArticulos(raiz)

        val tituloDoc = extraerTituloDocumento(lineas) ?: "Documento"

        return DocumentoEstructurado(
            raiz = raiz,
            titulo = tituloDoc,
            tipoOrigen = DocumentoEstructurado.TipoOrigen.PDF,
            indiceArticulos = indice,
            avisos = avisos
        )
    }

    // ----------------------------------------------------------------------
    // Limpieza
    // ----------------------------------------------------------------------

    private fun quitarGuionSuave(s: String): String {
        val base = if (s.endsWith("­")) s.dropLast(1) + "-" else s
        return base.replace("­", "")
    }

    private fun esNumeral(t: String): Boolean =
        RE_TOKEN_NUMERAL.matches(t) || RE_ROMANO.matches(t.uppercase())

    private fun claveBorde(s: String): String {
        val tokens = s.trim().split(RE_ESPACIOS).dropWhile { esNumeral(it) }.dropLastWhile { esNumeral(it) }
        return tokens.joinToString("").lowercase().filter { it.isLetter() }.take(20)
    }

    private fun indicesBorde(lineas: List<String>): List<Int> {
        val no = lineas.indices.filter { lineas[it].isNotBlank() }
        if (no.isEmpty()) return emptyList()
        val v = minOf(VENTANA_BORDE, maxOf(2, no.size / 4))
        return (no.take(v) + no.takeLast(v)).distinct()
    }

    private fun esNumeroPagina(s: String): Boolean =
        RE_NUM_PAG.matches(s) || RE_ROMANO_PAG.matches(s) || RE_PAGINA_DE.matches(s)

    private fun limpiarPaginas(crudas: List<String>): List<List<String>> {
        val n = crudas.size
        val listas = crudas.map { it.replace("\r", "").split("\n") }

        val conteo = HashMap<String, Int>()
        if (n >= 6) {
            for (lineas in listas) {
                val claves = indicesBorde(lineas).map { claveBorde(lineas[it]) }.filter { it.length >= 4 }.toSet()
                for (k in claves) conteo[k] = (conteo[k] ?: 0) + 1
            }
        }
        val umbral = maxOf(3, (n * 0.3).toInt())

        return listas.map { lineas ->
            val quitar = HashSet<Int>()
            for (i in indicesBorde(lineas)) {
                val t = lineas[i].trim()
                if (esNumeroPagina(t)) { quitar.add(i); continue }
                if (n >= 6 && t.length <= 120) {
                    val k = claveBorde(t)
                    val protegida = RE_ARTICULO.containsMatchIn(t) ||
                        RE_MARCADOR.containsMatchIn(t) ||
                        RE_TITULO_NIVEL.containsMatchIn(t) ||
                        RE_TITULO_TRANSITORIO.matches(t)
                    if (k.length >= 4 && !protegida && (conteo[k] ?: 0) >= umbral) quitar.add(i)
                }
            }
            lineas.filterIndexed { i, _ -> i !in quitar }
        }
    }

    // ----------------------------------------------------------------------
    // Detección de títulos
    // ----------------------------------------------------------------------

    /** Devuelve (nivel, texto) si la línea es título de nivel superior, o null. */
    private fun detectarTituloNivel(s: String): Pair<Int, String>? {
        if (s.isEmpty() || s.length > 200) return null
        if (!s[0].isUpperCase()) return null
        if (RE_LIDERES.containsMatchIn(s)) return null  // índice con puntos guía
        if (RE_ARTICULO.containsMatchIn(s)) return null

        // Transitorios y similares: nivel 1, hermanos de LIBRO
        if (RE_TITULO_TRANSITORIO.matches(s)) return Pair(1, s)

        val m = RE_TITULO_NIVEL.find(s) ?: return null
        val clave = m.groupValues[1]
        val num = m.groupValues[2].trimEnd('.', ':', '-', '–', '—', ')', '°', 'º')
        val resto = m.groupValues[3].trimStart(' ', '.', ':', '-', '–', '—').trim()

        val numeralValido = (num.length in 1..3 && num.all { it.isDigit() }) ||
            RE_ROMANO.matches(num.uppercase()) ||
            RE_ORDINAL.matches(num)
        if (!numeralValido) return null

        if (resto.isNotEmpty()) {
            if (RE_REFERENCIA_EN_TEXTO.containsMatchIn(resto)) return null
            if (RE_PAGINA_AL_FINAL.containsMatchIn(resto) && resto.any { it.isLetter() }) return null
            if (resto.last() in ",;") return null
        }
        val nivel = NIVEL_POR_CLAVE[clave] ?: NIVEL_POR_CLAVE[clave.uppercase()] ?: 3
        return Pair(nivel, s)
    }

    /**
     * ¿La siguiente línea parece continuación del título actual?
     * Casos: "CAPÍTULO PRIMERO" en una línea, "APLICACIÓN DE LA LEY PENAL" en la siguiente.
     */
    private fun esContinuacionDeTitulo(titulo: String, siguiente: String): Boolean {
        if (siguiente.isEmpty()) return false
        if (siguiente.length > 120) return false
        if (!siguiente[0].isUpperCase()) return false
        if (RE_ARTICULO.containsMatchIn(siguiente)) return false
        if (RE_MARCADOR.containsMatchIn(siguiente)) return false
        if (detectarTituloNivel(siguiente) != null) return false
        if (RE_TITULO_TRANSITORIO.matches(siguiente)) return false
        if (siguiente.endsWith(".")) return false
        // Si el título actual ya tiene texto descriptivo (no solo "CAPÍTULO X"),
        // no arrastramos más líneas: probablemente es contenido normal.
        val tieneTextoDescriptivo = detectarTituloConTexto(titulo)
        return !tieneTextoDescriptivo
    }

    /** true si el título ya incluye texto después del numeral. */
    private fun detectarTituloConTexto(s: String): Boolean {
        val m = RE_TITULO_NIVEL.find(s) ?: return false
        return m.groupValues[3].trimStart(' ', '.', ':', '-', '–', '—').trim().isNotEmpty()
    }

    private fun extraerTituloDocumento(lineas: List<Linea>): String? {
        for (l in lineas.take(20)) {
            val t = l.texto.trim()
            if (t.length in 10..150 && t[0].isUpperCase() &&
                !RE_ARTICULO.containsMatchIn(t) && !RE_LIDERES.containsMatchIn(t)
            ) {
                return t
            }
        }
        return null
    }

    // ----------------------------------------------------------------------
    // Construcción del árbol
    // ----------------------------------------------------------------------

    private fun construirArbol(lineas: List<Linea>): Nodo {
        val raiz = Nodo(nivel = 0, titulo = "")
        var actual = raiz
        val pila = mutableListOf(raiz)  // pila de ancestros por nivel

        val buffer = StringBuilder()
        var pagInicio = 0

        fun volcarParrafo() {
            if (buffer.isEmpty()) return
            val texto = buffer.toString().trim()
            buffer.setLength(0)
            if (texto.isEmpty()) return
            // Si el párrafo es un artículo, se registra como Articulo en el nodo actual
            val m = RE_ARTICULO.find(texto)
            if (m != null && m.range.first == 0) {
                val numero = construirNumeroArticulo(m)
                actual.articulos.add(Articulo(numero, texto, actual.contenido.size))
            }
            actual.contenido.add(texto)
        }

        var i = 0
        while (i < lineas.size) {
            val l = lineas[i]
            val t = l.texto

            // ¿Es título de nivel?
            val det = detectarTituloNivel(t)
            if (det != null) {
                volcarParrafo()
                var texto = det.second
                var j = i + 1
                // Arrastrar continuación si el título no tiene texto descriptivo
                while (j < lineas.size && esContinuacionDeTitulo(texto, lineas[j].texto)) {
                    texto = "$texto ${lineas[j].texto}"
                    j++
                }

                val nivel = det.first
                // Encontrar padre correcto en la pila
                while (pila.isNotEmpty() && pila.last().nivel >= nivel) {
                    pila.removeAt(pila.size - 1)
                }
                val padre = pila.lastOrNull() ?: raiz
                val nodo = Nodo(nivel = nivel, titulo = texto)
                padre.hijos.add(nodo)
                pila.add(nodo)
                actual = nodo
                i = j
                continue
            }

            // ¿Es transitorio suelto?
            if (RE_TITULO_TRANSITORIO.matches(t)) {
                volcarParrafo()
                while (pila.isNotEmpty() && pila.last().nivel >= 1) {
                    pila.removeAt(pila.size - 1)
                }
                val padre = pila.lastOrNull() ?: raiz
                val nodo = Nodo(nivel = 1, titulo = t)
                padre.hijos.add(nodo)
                pila.add(nodo)
                actual = nodo
                i++
                continue
            }

            // Contenido normal: acumular en párrafo
            if (buffer.isEmpty()) pagInicio = l.pagina
            if (buffer.length + t.length + 1 > MAX_PARRAFO &&
                RE_FIN_FRASE_FUERTE.containsMatchIn(buffer.toString())
            ) {
                volcarParrafo()
                buffer.append(t)
            } else {
                if (buffer.isNotEmpty()) {
                    val fin = buffer.length
                    if (fin >= 2 && buffer[fin - 1] == '-' && buffer[fin - 2].isLetter() && t[0].isLowerCase()) {
                        buffer.setLength(fin - 1); buffer.append(t)
                    } else {
                        buffer.append(' ').append(t)
                    }
                } else {
                    buffer.append(t)
                }
            }
            i++
        }
        volcarParrafo()

        // Si no se detectó ningún título de nivel, se agrupa por páginas
        if (raiz.hijos.isEmpty()) {
            agruparPorPaginas(raiz, lineas)
        }

        return raiz
    }

    private fun agruparPorPaginas(raiz: Nodo, lineas: List<Linea>) {
        val porGrupo = lineas.groupBy { it.pagina / PAGINAS_POR_GRUPO }
        for ((g, lista) in porGrupo.toSortedMap()) {
            val desde = g * PAGINAS_POR_GRUPO + 1
            val hasta = lista.maxOf { it.pagina } + 1
            val nodo = Nodo(nivel = 1, titulo = "Páginas $desde–$hasta")
            for (l in lista) nodo.contenido.add(l.texto)
            raiz.hijos.add(nodo)
        }
    }

    private fun construirNumeroArticulo(m: MatchResult): String {
        val base = m.groupValues[2]
        val sufijo = m.groupValues[4].trim()
        return if (sufijo.isEmpty()) base else "$base $sufijo"
    }

    // ----------------------------------------------------------------------
    // Índice de artículos y calidad
    // ----------------------------------------------------------------------

    private fun construirIndiceArticulos(raiz: Nodo): Map<String, ReferenciaArticulo> {
        val out = HashMap<String, ReferenciaArticulo>()
        fun recorrer(n: Nodo) {
            for (a in n.articulos) {
                out[a.numero.lowercase()] = ReferenciaArticulo(
                    numero = a.numero,
                    ruta = n.id.split('.').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() },
                    posicionParrafo = a.posicionParrafo
                )
            }
            for (h in n.hijos) recorrer(h)
        }
        recorrer(raiz)
        return out
    }

    private fun textoSospechoso(lineas: List<Linea>): Boolean {
        var tokens = 0
        var raros = 0
        var rotos = 0
        var vistos = 0
        for (l in lineas) {
            if (vistos > 200_000) break
            vistos += l.texto.length
            rotos += l.texto.count { it == '�' }
            for (w in l.texto.split(' ')) {
                if (w.length < 3) continue
                tokens++
                val letras = w.any { it.isLetter() }
                val digitos = w.any { it.isDigit() }
                val simbolo = w.any { it in "|¦~^{}<>\\" }
                if (simbolo || (letras && digitos && !RE_ALFANUM_OK.matches(w))) raros++
            }
        }
        if (tokens == 0) return true
        return raros.toDouble() / tokens > 0.06 || rotos.toDouble() / maxOf(vistos, 1) > 0.01
    }
}
