package com.oasis.reader

/** El PDF no tiene texto extraíble (es un escaneo). */
class PdfEscaneadoException(message: String) : Exception(message)

/**
 * Lógica pura (sin Android) que convierte el texto crudo de cada página de un PDF
 * en capítulos y párrafos. No corrige ni reescribe el contenido jurídico:
 * solo quita ruido (encabezados, pies, números de página), une líneas cortadas
 * y separa párrafos.
 */
object PdfTextProcessor {

    class Resultado(
        val titulos: MutableList<String>,
        val contenidos: MutableList<List<String>>,
        /** true = capítulos por títulos del texto; false = por grupos de páginas. */
        val porTitulos: Boolean,
        val paginasSinTexto: Int,
        val calidadBaja: Boolean
    )

    private const val PAGINAS_POR_GRUPO = 10
    private const val MAX_PARRAFO = 2500
    private const val MAX_TITULO = 120

    private val RE_ESPACIOS = Regex("\\s+")
    private val RE_ROMANO = Regex("^(?=[IVXLC])C{0,3}(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")
    private val RE_NUM_PAG = Regex("^\\s*[-–—]?\\s*(\\d{1,4})\\s*[-–—]?\\s*$")
    private val RE_ROMANO_PAG = Regex("^\\s*[-–—]?\\s*(?=[ivxlc])c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})\\s*[-–—]?\\s*$", RegexOption.IGNORE_CASE)
    private val RE_PAGINA_DE = Regex("^\\s*(p[áa]gina|p[áa]g\\.?|page)\\s*\\d{1,4}(\\s*(de|/|of)\\s*\\d{1,4})?\\s*$", RegexOption.IGNORE_CASE)

    private val RE_CLAVE_TITULO = Regex(
        "^(LIBRO|T[ÍI]TULO|CAP[ÍI]TULO|SECCI[ÓO]N|PARTE|UNIDAD|TEMA|LECCI[ÓO]N|M[ÓO]DULO)\\s+(\\S+)(.*)$",
        RegexOption.IGNORE_CASE
    )
    private val RE_TITULO_SUELTO = Regex(
        "^(ART[ÍI]CULOS\\s+TRANSITORIOS|DISPOSICIONES\\s+TRANSITORIAS|TRANSITORIOS|DISPOSICIONES\\s+FINALES|EXPOSICI[ÓO]N\\s+DE\\s+MOTIVOS|CONSIDERANDO)\\s*:?$"
    )
    private val RE_ORDINAL = Regex(
        "^(PRIMER[OA]?|SEGUND[OA]|TERCER[OA]?|CUART[OA]|QUINT[OA]|SEXT[OA]|S[ÉE]PTIM[OA]|OCTAV[OA]|NOVEN[OA]|D[ÉE]CIM[OA]|[ÚU]NIC[OA]|PRELIMINAR)$",
        RegexOption.IGNORE_CASE
    )
    private val RE_REFERENCIA_EN_TEXTO = Regex(
        "^(de\\s+(esta|este|la\\s+presente|el\\s+presente|la\\s+ley|el\\s+c[óo]digo)|del\\s+(presente|c[óo]digo)|y\\s+siguientes|y\\s+los|y\\s+las|,|;|se\\s|que\\s)"
    )
    private val RE_ARTICULO = Regex("^(Art[ií]culo|ART[ÍI]CULO|Art\\.)\\s*\\d+")
    private val RE_MARCADOR = Regex("^(([IVXLC]{1,6}|\\d{1,2})[.)]|[a-zñ]\\)|\\([a-zñ0-9]{1,4}\\)|[•▪●])\\s+")
    private val RE_FIN_FRASE = Regex("([.:;?!]|[;,]\\s+(y|o|e))$")
    private val RE_FIN_FRASE_FUERTE = Regex("[.:?!]$")
    private val RE_ALFANUM_OK = Regex("^[(\\[]?\\d+(-?[A-Za-z]{1,5}|[ºo°ª])?[)\\].,;:]*$")

    private class Parrafo(val texto: String, val pagina: Int, val esTitulo: Boolean)
    private class Linea(val texto: String, val pagina: Int)

    @Throws(PdfEscaneadoException::class)
    fun procesar(paginasCrudas: List<String>): Resultado {
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
                "Este PDF parece un escaneo (imágenes sin texto seleccionable). " +
                    "Por ahora solo se pueden leer PDFs con texto; usa una versión con OCR."
            )
        }

        // 2) Limpiar páginas (encabezados, pies, números)
        val paginas = limpiarPaginas(paginasCrudas)

        // 3) Aplanar y unir líneas en párrafos
        val lineas = ArrayList<Linea>()
        for ((i, lista) in paginas.withIndex()) {
            var ultimaVacia = true
            for (l in lista) {
                val t = l.replace("­", "-").replace(RE_ESPACIOS, " ").trim()
                if (t.isEmpty()) {
                    if (!ultimaVacia) lineas.add(Linea("", i))
                    ultimaVacia = true
                } else {
                    lineas.add(Linea(t, i)); ultimaVacia = false
                }
            }
        }
        val parrafos = armarParrafos(lineas)

        // 4) Calidad (OCR dudoso)
        val calidadBaja = textoSospechoso(parrafos)

        // 5) Capítulos
        if (parrafos.isEmpty()) throw PdfEscaneadoException("No se encontró texto legible en el PDF.")
        val titulosDetectados = parrafos.count { it.esTitulo }
        return if (titulosDetectados >= 2) {
            val (t, c) = capitulosPorTitulos(parrafos)
            Resultado(t, c, true, sinTexto, calidadBaja)
        } else {
            val (t, c) = capitulosPorPaginas(parrafos)
            Resultado(t, c, false, sinTexto, calidadBaja)
        }
    }

    // ---------------------------------------------------------------- limpieza

    private fun normalizarClave(s: String): String =
        s.lowercase().replace(Regex("\\d+"), "#").replace(RE_ESPACIOS, " ").trim()

    private fun indicesBorde(lineas: List<String>): List<Int> {
        val no = lineas.indices.filter { lineas[it].isNotBlank() }
        if (no.isEmpty()) return emptyList()
        return (no.take(2) + no.takeLast(2)).distinct()
    }

    private fun esNumeroPagina(s: String): Boolean =
        RE_NUM_PAG.matches(s) || RE_ROMANO_PAG.matches(s) || RE_PAGINA_DE.matches(s)

    private fun limpiarPaginas(crudas: List<String>): List<List<String>> {
        val n = crudas.size
        val listas = crudas.map { it.replace("\r", "").split("\n") }

        // Líneas de borde que se repiten en muchas páginas (encabezados/pies)
        val conteo = HashMap<String, Int>()
        if (n >= 6) {
            for (lineas in listas) {
                val claves = indicesBorde(lineas).map { normalizarClave(lineas[it]) }.toSet()
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
                    val k = normalizarClave(t)
                    val protegida = RE_ARTICULO.containsMatchIn(t) || RE_MARCADOR.containsMatchIn(t) ||
                        esTituloBasico(t)
                    if (!protegida && (conteo[k] ?: 0) >= umbral) quitar.add(i)
                }
            }
            lineas.filterIndexed { i, _ -> i !in quitar }
        }
    }

    // ---------------------------------------------------------------- títulos

    /** Detección sin contexto (para proteger líneas de la limpieza). */
    private fun esTituloBasico(s: String): Boolean = analizarTitulo(s, true) != null

    /**
     * Devuelve el texto normalizado del título, o null si no lo es.
     * [inicioBloque] = la línea anterior cerró una frase (o es el inicio).
     */
    private fun analizarTitulo(s: String, inicioBloque: Boolean): String? {
        if (s.length > 100 || s.isEmpty() || !s[0].isUpperCase()) return null
        if (RE_TITULO_SUELTO.matches(s)) return s
        val m = RE_CLAVE_TITULO.find(s) ?: return null
        val clave = m.groupValues[1]
        var num = m.groupValues[2]
        val resto = m.groupValues[3]
        num = num.trimEnd('.', ':', '-', '–', '—', ')', '°', 'º')
        val numeralValido = (num.length in 1..3 && num.all { it.isDigit() }) ||
            RE_ROMANO.matches(num) || RE_ORDINAL.matches(num)
        if (!numeralValido) return null
        if (s.contains("...") || s.contains("…") || s.contains(". . .")) return null // índice con puntos
        val restoLimpio = resto.trimStart(' ', '.', ':', '-', '–', '—').trim()
        if (restoLimpio.isNotEmpty()) {
            if (Regex("\\s\\d{1,4}$").containsMatchIn(restoLimpio) && restoLimpio.any { it.isLetter() }) return null
            if (restoLimpio.last() in ",;") return null
            if (RE_REFERENCIA_EN_TEXTO.containsMatchIn(restoLimpio)) return null
            // "Capítulo II de los delitos": solo si empieza bloque y es corto
            val empiezaMinuscula = restoLimpio[0].isLowerCase()
            if (empiezaMinuscula && (!inicioBloque || s.length > 80)) return null
            if (restoLimpio.endsWith(".") && restoLimpio.length > 40) return null
        }
        return s
    }

    private fun esSoloClave(s: String): Boolean {
        val m = RE_CLAVE_TITULO.find(s) ?: return false
        return m.groupValues[3].trimStart(' ', '.', ':', '-', '–', '—').trim().isEmpty()
    }

    // ---------------------------------------------------------------- párrafos

    private fun terminaFrase(s: String) = RE_FIN_FRASE.containsMatchIn(s)

    private fun armarParrafos(lineas: List<Linea>): List<Parrafo> {
        val largos = lineas.filter { it.texto.length >= 20 }.map { it.texto.length }.sorted()
        val ancho = if (largos.isEmpty()) 80.0 else largos[(largos.size * 0.9).toInt().coerceAtMost(largos.size - 1)].toDouble()

        val res = ArrayList<Parrafo>()
        val sb = StringBuilder()
        var paginaInicio = 0
        var previa: String? = null

        fun cerrar() {
            if (sb.isNotEmpty()) {
                res.add(Parrafo(sb.toString().trim(), paginaInicio, false))
                sb.setLength(0)
            }
        }

        var i = 0
        while (i < lineas.size) {
            val l = lineas[i]
            val t = l.texto
            if (t.isEmpty()) { cerrar(); previa = null; i++; continue }

            val cierreAnterior = previa == null || terminaFrase(previa) || previa.length < 0.6 * ancho
            val titulo = analizarTitulo(t, cierreAnterior)
            if (titulo != null) {
                cerrar()
                var texto = titulo
                // "CAPÍTULO I" y el nombre en la línea siguiente
                if (esSoloClave(titulo) && i + 1 < lineas.size) {
                    val sig = lineas[i + 1].texto
                    if (sig.isNotEmpty() && sig.length <= 80 && analizarTitulo(sig, true) == null &&
                        !RE_ARTICULO.containsMatchIn(sig) && !sig.endsWith(".")
                    ) {
                        texto = "$titulo $sig"; i++
                    }
                }
                res.add(Parrafo(texto, l.pagina, true))
                previa = null
                i++
                continue
            }

            val empiezaMayus = t[0].isUpperCase() || t[0].isDigit() || t[0] in "«“\"¿¡(["
            val nuevo = when {
                previa == null -> true
                RE_ARTICULO.containsMatchIn(t) -> terminaFrase(previa) || previa.length < 0.6 * ancho
                RE_MARCADOR.containsMatchIn(t) -> terminaFrase(previa) || previa.length < 0.6 * ancho
                sb.length > MAX_PARRAFO && RE_FIN_FRASE_FUERTE.containsMatchIn(previa) && empiezaMayus -> true
                RE_FIN_FRASE_FUERTE.containsMatchIn(previa) && previa.length < 0.8 * ancho && empiezaMayus -> true
                else -> false
            }
            if (nuevo) {
                cerrar(); sb.append(t); paginaInicio = l.pagina
            } else {
                val fin = sb.length
                if (fin >= 2 && sb[fin - 1] == '-' && sb[fin - 2].isLetter() && t[0].isLowerCase()) {
                    sb.setLength(fin - 1); sb.append(t)       // des-guionar
                } else {
                    sb.append(' ').append(t)
                }
            }
            previa = t
            i++
        }
        cerrar()

        // Una racha larga de títulos seguidos es un índice: se queda como texto normal.
        val limpio = ArrayList<Parrafo>(res.size)
        var j = 0
        while (j < res.size) {
            if (res[j].esTitulo) {
                var k = j
                while (k < res.size && res[k].esTitulo) k++
                if (k - j >= 5) {
                    for (x in j until k) limpio.add(Parrafo(res[x].texto, res[x].pagina, false))
                } else {
                    for (x in j until k) limpio.add(res[x])
                }
                j = k
            } else { limpio.add(res[j]); j++ }
        }
        return limpio
    }

    // ---------------------------------------------------------------- capítulos

    private fun recortar(s: String) = if (s.length <= MAX_TITULO) s else s.take(MAX_TITULO - 1).trimEnd() + "…"

    private fun capitulosPorTitulos(parrafos: List<Parrafo>): Pair<MutableList<String>, MutableList<List<String>>> {
        val titulos = mutableListOf<String>()
        val contenidos = mutableListOf<List<String>>()
        var tituloActual: String? = null
        val partesTitulo = mutableListOf<String>()
        var cuerpo = mutableListOf<String>()
        var ultimaClave = ""

        fun volcar() {
            if (tituloActual == null && cuerpo.isEmpty()) return
            val nombre = tituloActual ?: "Inicio"
            val lista = ArrayList<String>()
            if (tituloActual != null) lista.addAll(partesTitulo.map { it })
            lista.addAll(cuerpo)
            if (lista.isEmpty()) lista.add(nombre)
            titulos.add(recortar(nombre))
            contenidos.add(lista)
        }

        for (p in parrafos) {
            if (p.esTitulo) {
                val clave = normalizarClave(p.texto)
                if (clave == ultimaClave) continue            // encabezado corrido repetido
                ultimaClave = clave
                if (tituloActual != null && cuerpo.isEmpty()) {
                    tituloActual = tituloActual + " · " + p.texto
                    partesTitulo.add(p.texto)
                } else {
                    volcar()
                    tituloActual = p.texto
                    partesTitulo.clear(); partesTitulo.add(p.texto)
                    cuerpo = mutableListOf()
                }
            } else {
                cuerpo.add(p.texto)
            }
        }
        volcar()
        return Pair(titulos, contenidos)
    }

    private fun capitulosPorPaginas(parrafos: List<Parrafo>): Pair<MutableList<String>, MutableList<List<String>>> {
        val titulos = mutableListOf<String>()
        val contenidos = mutableListOf<List<String>>()
        if (parrafos.isEmpty()) return Pair(titulos, contenidos)
        val grupos = parrafos.groupBy { it.pagina / PAGINAS_POR_GRUPO }.toSortedMap()
        for ((g, lista) in grupos) {
            val desde = g * PAGINAS_POR_GRUPO + 1
            val hasta = lista.maxOf { it.pagina } + 1
            titulos.add("Páginas $desde–$hasta")
            contenidos.add(lista.map { it.texto })
        }
        return Pair(titulos, contenidos)
    }

    // ---------------------------------------------------------------- calidad

    private fun textoSospechoso(parrafos: List<Parrafo>): Boolean {
        var tokens = 0
        var raros = 0
        var rotos = 0
        var vistos = 0
        for (p in parrafos) {
            if (vistos > 200_000) break
            vistos += p.texto.length
            rotos += p.texto.count { it == '�' }
            for (w in p.texto.split(' ')) {
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
