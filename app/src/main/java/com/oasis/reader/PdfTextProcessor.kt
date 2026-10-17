package com.oasis.reader

import java.text.Normalizer

/** El PDF no tiene texto extraíble (es un escaneo). */
class PdfEscaneadoException(message: String) : Exception(message)

/**
 * Lógica pura (sin Android) que convierte el texto crudo de cada página de un PDF
 * en capítulos y párrafos. No corrige ni reescribe el contenido jurídico:
 * solo quita ruido (encabezados, pies, números de página), une líneas cortadas
 * y separa párrafos.
 *
 * Capítulos, por orden de confianza:
 *  1. Títulos que aparecen en el índice del propio libro ("3.1. Transporte ... 21").
 *  2. Títulos con palabra clave: Libro, Título, Capítulo, Sección, Parte, Transitorios...
 *  3. Secciones numeradas ("1. Introducción") si hay al menos 3 y no hay índice.
 *  4. Grupos de 10 páginas.
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
    private const val MAX_TITULO = 160
    private const val VENTANA_BORDE = 6
    private const val PAGINAS_INDICE = 40

    private val RE_ESPACIOS = Regex("\\s+")
    private val RE_ROMANO = Regex("^(?=[IVXLC])C{0,3}(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")
    private val RE_NUM_PAG = Regex("^\\s*[-–—]?\\s*(\\d{1,4})\\s*[-–—]?\\s*$")
    private val RE_ROMANO_PAG = Regex("^\\s*[-–—]?\\s*(?=[ivxlc])c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})\\s*[-–—]?\\s*$", RegexOption.IGNORE_CASE)
    private val RE_PAGINA_DE = Regex("^\\s*(p[áa]gina|p[áa]g\\.?|page)\\s*\\d{1,4}(\\s*(de|/|of)\\s*\\d{1,4})?\\s*$", RegexOption.IGNORE_CASE)
    private val RE_N_DE_M = Regex("^\\s*\\d{1,4}\\s+de\\s+\\d{1,4}\\s*$", RegexOption.IGNORE_CASE)
    private val RE_TOKEN_NUMERAL = Regex("^[\\d.,:;()\\-–—]+$")

    private val RE_CLAVE_TITULO = Regex(
        "^(LIBRO|T[ÍI]TULO|CAP[ÍI]TULO|SECCI[ÓO]N|PARTE|UNIDAD|TEMA|LECCI[ÓO]N|M[ÓO]DULO)\\s+(\\S+)(.*)$",
        RegexOption.IGNORE_CASE
    )
    private val RE_TITULO_SUELTO = Regex(
        "^(ART[ÍI]CULOS\\s+TRANSITORIOS|DISPOSICIONES\\s+TRANSITORIAS|TRANSITORIOS|DISPOSICIONES\\s+FINALES|EXPOSICI[ÓO]N\\s+DE\\s+MOTIVOS|CONSIDERANDO)\\s*:?$",
        RegexOption.IGNORE_CASE
    )
    // Notas de reforma que el Diario Oficial intercala en el texto: "Artículo reformado DOF 30-11-2012".
    private const val SUSTANTIVOS_NOTA = "(?:Art[ií]culo|P[aá]rrafo|Fracci[oó]n|Inciso|Cap[ií]tulo|T[ií]tulo|Secci[oó]n|Denominaci[oó]n|Apartado|Numeral|Libro|Subinciso|Letra|Ep[ií]grafe)"
    private const val PARTICIPIO_NOTA = "(?:reformad|adicionad|derogad|recorrid|modificad|suprimid|sustituid)[oa]s?"
    private val RE_NOTA_REFORMA = Regex(
        "^(?:$SUSTANTIVOS_NOTA\\s+)?$PARTICIPIO_NOTA\\b.{0,160}?\\bDOF\\b.*$|^Fe de erratas\\b.{0,80}\\bDOF\\b.*$",
        RegexOption.IGNORE_CASE
    )
    private val RE_NOTA_AL_FINAL = Regex(
        "\\s+$SUSTANTIVOS_NOTA\\s+$PARTICIPIO_NOTA\\b.{0,160}?\\bDOF\\b.*$",
        RegexOption.IGNORE_CASE
    )
    private val RE_SOLO_FECHAS = Regex("^\\d{1,2}-\\d{1,2}-\\d{2,4}(?:\\s*(?:,|;|y|e)\\s*\\d{1,2}-\\d{1,2}-\\d{2,4})*\\s*\\.?$")
    private val RE_ORDINAL = Regex(
        "^(PRIMER[OA]?|SEGUND[OA]|TERCER[OA]?|CUART[OA]|QUINT[OA]|SEXT[OA]|S[ÉE]PTIM[OA]|OCTAV[OA]|NOVEN[OA]|D[ÉE]CIM[OA]|UND[ÉE]CIM[OA]|DUOD[ÉE]CIM[OA]|" +
            "VIG[ÉE]SIM[OA]|TRIG[ÉE]SIM[OA]|CUADRAG[ÉE]SIM[OA]|QUINCUAG[ÉE]SIM[OA]|SEXAG[ÉE]SIM[OA]|" +
            "SEPTUAG[ÉE]SIM[OA]|OCTOG[ÉE]SIM[OA]|NONAG[ÉE]SIM[OA]|CENT[ÉE]SIM[OA]|VEINTI\\w+|[ÚU]NIC[OA]|PRELIMINAR)$",
        RegexOption.IGNORE_CASE
    )
    private val RE_REFERENCIA_EN_TEXTO = Regex(
        "^(de\\s+(esta|este|la\\s+presente|el\\s+presente|la\\s+ley|el\\s+c[óo]digo)|del\\s+(presente|c[óo]digo)|y\\s+siguientes|y\\s+los|y\\s+las|,|;|se\\s|que\\s)"
    )
    private val RE_ARTICULO = Regex("^(Art[ií]culo|ART[ÍI]CULO|Art\\.)\\s*\\d+")
    private val RE_ARTICULO_SOLO = Regex(
        "^(Art[ií]culo|ART[ÍI]CULO)\\s+\\d+\\s*(Bis|bis|BIS|Ter|TER|Qu[aá]ter|[A-Z]|º|o\\.?)?\\s*\\.?$"
    )
    private val RE_ART_NUM = Regex("^(Art[ií]culo|ART[ÍI]CULO)\\s+(\\d+)")
    private val RE_MARCADOR = Regex("^(([IVXLC]{1,6}|\\d{1,2})[.)]-?|[a-zñ]\\)|\\([a-zñ0-9]{1,4}\\)|[•▪●])\\s+")
    private val RE_FIN_FRASE = Regex("([.:;?!]|[;,]\\s+(y|o|e))$")
    private val RE_FIN_FRASE_FUERTE = Regex("[.:?!]$")
    private val RE_ALFANUM_OK = Regex("^[(\\[]?\\d+(-?[A-Za-z]{1,5}|[ºo°ª])?[)\\].,;:]*$")

    // Índice del libro y secciones numeradas
    private val RE_LIDERES = Regex("(\\.{3,}|(?:\\.\\s){3,}|…)")
    private val RE_ENTRADA_INDICE = Regex(
        "^(?:(\\d{1,2}(?:\\.\\d{1,2}){0,3})[.)]?\\s+)?(\\S.*?)\\s*(?:\\.{3,}|(?:\\.\\s){3,}|…+)\\s*[\\dIVXLCivxlc]{1,5}$"
    )
    private val RE_NUMERADO = Regex("^(\\d{1,2}(?:\\.\\d{1,2}){0,3})[.)]?\\s+(\\S.{1,89})$")
    private val RE_PAGINA_AL_FINAL = Regex("\\s\\d{1,4}$")

    private class Parrafo(val texto: String, val pagina: Int, val esTitulo: Boolean, val generico: Boolean = false)
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
                "Este PDF parece un escaneo: solo ${n - sinTexto} de $n páginas tienen texto seleccionable. " +
                    "Por ahora solo se pueden leer PDFs con texto; usa una versión con OCR."
            )
        }

        // 2) Limpiar páginas (encabezados, pies, números)
        val paginas = limpiarPaginas(paginasCrudas)

        // 3) Índice del libro (si lo tiene)
        val indice = extraerIndice(paginas)

        // 4) Aplanar y unir líneas en párrafos
        val lineas = ArrayList<Linea>()
        for ((i, lista) in paginas.withIndex()) {
            val limpias = lista.map { quitarGuionSuave(it).replace(RE_ESPACIOS, " ").trim() }
            var a = 0
            var b = limpias.size
            while (a < b && limpias[a].isEmpty()) a++
            while (b > a && limpias[b - 1].isEmpty()) b--
            for (k in a until b) lineas.add(Linea(limpias[k], i))
        }
        var parrafos = repararNumeracion(armarParrafos(lineas, indice))
        if (parrafos.isEmpty()) throw PdfEscaneadoException("No se encontró texto legible en el PDF.")

        // Secciones numeradas sin respaldo del índice: solo valen si hay varias y no son la mayoría del texto.
        val genericos = parrafos.count { it.generico }
        val porClave = parrafos.count { it.esTitulo && !it.generico }
        if (genericos in 1..2 || genericos > parrafos.size / 4 || porClave >= 3) {
            parrafos = parrafos.map { if (it.generico) Parrafo(it.texto, it.pagina, false) else it }
        }

        // 5) Calidad (OCR dudoso)
        val calidadBaja = textoSospechoso(parrafos)

        // 6) Capítulos
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

    private fun quitarGuionSuave(s: String): String {
        val base = if (s.endsWith("­")) s.dropLast(1) + "-" else s
        return base.replace("­", "")
    }

    private fun esNumeral(t: String): Boolean =
        RE_TOKEN_NUMERAL.matches(t) || RE_ROMANO.matches(t.uppercase())

    /** Clave exacta (para no duplicar títulos repetidos). */
    private fun claveExacta(s: String): String =
        s.lowercase().replace(Regex("\\d+"), "#").replace(RE_ESPACIOS, " ").trim()

    /** Clave aproximada de una línea de borde: sin números romanos/arábigos al inicio o al final, solo letras. */
    private fun claveBorde(s: String): String {
        val tokens = s.trim().split(RE_ESPACIOS).dropWhile { esNumeral(it) }.dropLastWhile { esNumeral(it) }
        return tokens.joinToString("").lowercase().filter { it.isLetter() }.take(20)
    }

    private fun letrasClave(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).lowercase().filter { it in 'a'..'z' }.take(24)

    private fun indicesBorde(lineas: List<String>): List<Int> {
        val no = lineas.indices.filter { lineas[it].isNotBlank() }
        if (no.isEmpty()) return emptyList()
        // En páginas cortas la ventana se reduce, para no tocar el texto del cuerpo.
        val v = minOf(VENTANA_BORDE, maxOf(2, no.size / 4))
        return (no.take(v) + no.takeLast(v)).distinct()
    }

    private fun esNumeroPagina(s: String): Boolean =
        RE_NUM_PAG.matches(s) || RE_ROMANO_PAG.matches(s) || RE_PAGINA_DE.matches(s) || RE_N_DE_M.matches(s)

    /** Quita las notas de reforma del DOF ("Artículo reformado DOF …") sueltas o pegadas al final de una línea. */
    private fun quitarNotasReforma(lineas: List<String>): List<String> {
        val res = ArrayList<String>(lineas.size)
        var nota = false
        for (l in lineas) {
            val t = l.trim()
            if (t.isNotEmpty() && t.length <= 400 && RE_NOTA_REFORMA.matches(t)) { nota = true; continue }
            if (nota && t.isNotEmpty() && RE_SOLO_FECHAS.matches(t)) continue   // fechas que siguen a la nota
            if (t.isNotEmpty()) nota = false
            val m = if (t.length <= 400) RE_NOTA_AL_FINAL.find(l) else null
            res.add(if (m != null && m.range.first > 0) l.substring(0, m.range.first).trimEnd() else l)
        }
        return res
    }

    private fun limpiarPaginas(crudas: List<String>): List<List<String>> {
        val n = crudas.size
        val listas = crudas.map { it.replace("\r", "").split("\n") }

        // Líneas de borde que se repiten en muchas páginas (encabezados/pies)
        val conteo = HashMap<String, Int>()
        if (n >= 6) {
            for (lineas in listas) {
                val claves = indicesBorde(lineas).map { claveBorde(lineas[it]) }.filter { it.length >= 4 }.toSet()
                for (k in claves) conteo[k] = (conteo[k] ?: 0) + 1
            }
        }
        val umbral = maxOf(3, (n * 0.3).toInt())

        return listas.map { lineasBrutas ->
            val lineas = quitarNotasReforma(lineasBrutas)
            val quitar = HashSet<Int>()
            for (i in indicesBorde(lineas)) {
                val t = lineas[i].trim()
                if (esNumeroPagina(t)) { quitar.add(i); continue }
                if (n >= 6 && t.length <= 120) {
                    val k = claveBorde(t)
                    val protegida = RE_ARTICULO.containsMatchIn(t) || RE_MARCADOR.containsMatchIn(t) ||
                        esTituloBasico(t)
                    if (k.length >= 4 && !protegida && (conteo[k] ?: 0) >= umbral) quitar.add(i)
                }
            }
            lineas.filterIndexed { i, _ -> i !in quitar }
        }
    }

    // ---------------------------------------------------------------- índice y secciones numeradas

    /** Claves "numero|letras" de las entradas del índice (líneas con puntos guía y número de página). */
    private fun extraerIndice(paginas: List<List<String>>): Set<String> {
        val res = HashSet<String>()
        for (p in paginas.take(PAGINAS_INDICE)) {
            for (raw in p) {
                val s = raw.replace(RE_ESPACIOS, " ").trim()
                if (s.length < 6 || s.length > 160) continue
                val m = RE_ENTRADA_INDICE.find(s) ?: continue
                val letras = letrasClave(m.groupValues[2])
                if (letras.length >= 3) res.add(m.groupValues[1] + "|" + letras)
            }
        }
        return res
    }

    /** 0 = no es título; 1 = respaldado por el índice; 2 = sección numerada sin índice. */
    private fun analizarNumerado(s: String, indice: Set<String>): Int {
        if (s.length > 100 || RE_LIDERES.containsMatchIn(s)) return 0
        val m = RE_NUMERADO.find(s)
        if (m != null) {
            val num = m.groupValues[1]
            val texto = m.groupValues[2].trim()
            if ("$num|${letrasClave(texto)}" in indice) return 1
            if (indice.size >= 3) return 0
            if (!texto[0].isUpperCase() || texto.length > 80) return 0
            if (texto.last() in ".,;:)") return 0
            if (RE_PAGINA_AL_FINAL.containsMatchIn(texto)) return 0
            if (texto.split(' ').size > 12) return 0
            return 2
        }
        if (indice.isNotEmpty() && s.length in 3..100 && !s.endsWith(".") && ("|" + letrasClave(s)) in indice) return 1
        return 0
    }

    // ---------------------------------------------------------------- títulos con palabra clave

    /** Detección sin contexto (para proteger líneas de la limpieza). */
    private fun esTituloBasico(s: String): Boolean = analizarTitulo(s, true) != null

    /**
     * Devuelve el texto del título, o null si no lo es.
     * [inicioBloque] = la línea anterior cerró una frase (o es el inicio).
     */
    private fun analizarTitulo(s: String, inicioBloque: Boolean): String? {
        if (s.length > 100 || s.isEmpty() || !s[0].isUpperCase()) return null
        if (RE_TITULO_SUELTO.matches(s)) return s
        val m = RE_CLAVE_TITULO.find(s) ?: return null
        var num = m.groupValues[2]
        val resto = m.groupValues[3]
        num = num.trimEnd('.', ':', '-', '–', '—', ')', '°', 'º')
        val numeralValido = (num.length in 1..3 && num.all { it.isDigit() }) ||
            RE_ROMANO.matches(num) || RE_ORDINAL.matches(num)
        if (!numeralValido) return null
        if (RE_LIDERES.containsMatchIn(s) || s.contains(". . .")) return null // índice con puntos
        val restoLimpio = resto.trimStart(' ', '.', ':', '-', '–', '—').trim()
        if (restoLimpio.isNotEmpty()) {
            if (RE_PAGINA_AL_FINAL.containsMatchIn(restoLimpio) && restoLimpio.any { it.isLetter() }) return null
            if (restoLimpio.last() in ",;") return null
            if (RE_REFERENCIA_EN_TEXTO.containsMatchIn(restoLimpio)) return null
            // "Capítulo II de los delitos": solo si empieza bloque y es corto
            val empiezaMinuscula = restoLimpio[0].isLowerCase()
            if (empiezaMinuscula && (!inicioBloque || s.length > 80)) return null
            if (restoLimpio.endsWith(".") && restoLimpio.length > 40) return null
        }
        return s
    }

    private val RE_SUFIJO_TITULO = Regex("^(BIS|TER|QU[ÁA]TER|QUINQUIES|SEXIES|SEPTIES|OCTIES|[A-Z])$", RegexOption.IGNORE_CASE)

    /**
     * "CAPÍTULO I", "Capítulo III BIS", "CAPÍTULO DÉCIMO TERCERO": la clave sola, sin nombre.
     * Lo que sobra tras el primer numeral solo puede ser otro numeral (ordinal compuesto) o un sufijo bis/ter.
     */
    private fun esSoloClave(s: String): Boolean {
        val m = RE_CLAVE_TITULO.find(s) ?: return false
        val resto = m.groupValues[3].trimStart(' ', '.', ':', '-', '–', '—').trim()
        if (resto.isEmpty()) return true
        val palabras = resto.split(' ').filter { it.isNotEmpty() }
        if (palabras.size > 2) return false
        return palabras.all { w ->
            val x = w.trimEnd('.', ':', ',', '-', '–', '—')
            RE_ORDINAL.matches(x) || RE_SUFIJO_TITULO.matches(x) || RE_ROMANO.matches(x.uppercase())
        }
    }

    private val RE_CONECTOR_FINAL = Regex("(,|\\s(y|o|e|u|de|del|la|las|los|el|en|a|al|para|con|por|que|sus|su|se))$", RegexOption.IGNORE_CASE)

    private fun esMayusculas(s: String): Boolean {
        val letras = s.filter { it.isLetter() }
        return letras.length >= 6 && letras.count { it.isUpperCase() } >= letras.length * 0.9
    }

    /** Número de nota pegado al final del nombre ("DESINTOXICACIÓN84"). */
    private fun quitarNotaPegada(s: String): String = s.replace(Regex("(?<=\\p{L})\\d{1,3}$"), "")

    /** ¿Esta línea puede ser (parte de) el nombre de un capítulo que viene en la línea siguiente a su clave? */
    private fun aceptaNombre(sig: String, primera: Boolean, despues: String): Boolean {
        if (sig.isEmpty() || sig.length > 110) return false
        if (analizarTitulo(sig, true) != null) return false
        if (RE_ARTICULO.containsMatchIn(sig) || RE_ARTICULO_SOLO.matches(sig) || RE_MARCADOR.containsMatchIn(sig)) return false
        if (esNumeroPagina(sig) || RE_NUM_PAG.matches(sig)) return false
        if (primera) {
            if (!sig[0].isUpperCase()) return false
            if (sig.length > 80 && !esMayusculas(sig)) return false
        }
        // Un punto final solo vale si lo que sigue ya es el articulado.
        if (sig.endsWith(".") && !RE_ARTICULO.containsMatchIn(despues) && !RE_ARTICULO_SOLO.matches(despues)) return false
        return true
    }

    /** El nombre sigue en la línea de abajo: termina en conector/coma, o es una línea larga en mayúsculas. */
    private fun nombreAbierto(sig: String): Boolean =
        !sig.endsWith(".") && (RE_CONECTOR_FINAL.containsMatchIn(sig) || (esMayusculas(sig) && sig.length >= 30))

    // ---------------------------------------------------------------- párrafos

    private fun terminaFrase(s: String) = RE_FIN_FRASE.containsMatchIn(s)

    private fun armarParrafos(lineas: List<Linea>, indice: Set<String>): List<Parrafo> {
        val largos = lineas.filter { it.texto.length >= 20 }.map { it.texto.length }.sorted()
        val ancho = if (largos.isEmpty()) 80.0 else largos[(largos.size * 0.9).toInt().coerceAtMost(largos.size - 1)].toDouble()

        val res = ArrayList<Parrafo>()
        val sb = StringBuilder()
        var paginaInicio = 0
        var previa: String? = null
        var vacias = 0

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
            // Un renglón en blanco aislado no separa párrafos (muchos PDFs lo meten entre líneas);
            // tres o más seguidos sí.
            if (t.isEmpty()) {
                vacias++
                if (vacias == 3) { cerrar(); previa = null }
                i++
                continue
            }
            vacias = 0

            val cierreAnterior = previa == null || terminaFrase(previa) || previa.length < 0.6 * ancho
            var titulo = analizarTitulo(t, cierreAnterior)
            var tipo = if (titulo != null) 3 else 0
            if (titulo == null) {
                val num = analizarNumerado(t, indice)
                if (num == 1 || (num == 2 && cierreAnterior)) { titulo = t; tipo = num }
            }
            if (titulo != null) {
                cerrar()
                var texto: String = titulo
                // "CAPÍTULO I" y el nombre en la línea siguiente
                if (tipo == 3 && esSoloClave(titulo)) {
                    var j = i + 1
                    while (j < lineas.size && j <= i + 3 && lineas[j].texto.isEmpty()) j++
                    var partes = 0
                    while (j < lineas.size && partes < 4) {
                        val sig = quitarNotaPegada(lineas[j].texto)
                        var k2 = j + 1
                        while (k2 < lineas.size && k2 <= j + 3 && lineas[k2].texto.isEmpty()) k2++
                        val despues = lineas.getOrNull(k2)?.texto ?: ""
                        if (!aceptaNombre(sig, partes == 0, despues)) break
                        texto = "$texto $sig"; i = j; partes++; j++
                        if (!nombreAbierto(sig)) break   // el nombre ya terminó
                    }
                }
                res.add(Parrafo(texto, l.pagina, true, tipo == 2))
                previa = null
                i++
                continue
            }

            val empiezaMayus = t[0].isUpperCase() || t[0].isDigit() || t[0] in "«“\"¿¡(["
            val nuevo = when {
                previa == null -> true
                RE_ARTICULO_SOLO.matches(t) -> true
                RE_ARTICULO.containsMatchIn(t) -> terminaFrase(previa) || previa.length < 0.6 * ancho
                RE_MARCADOR.containsMatchIn(t) -> terminaFrase(previa) || previa.length < 0.6 * ancho
                sb.length > MAX_PARRAFO && RE_FIN_FRASE_FUERTE.containsMatchIn(previa) && empiezaMayus -> true
                sb.length > 3 * MAX_PARRAFO && RE_FIN_FRASE.containsMatchIn(previa) -> true
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

    /**
     * Si un número de nota quedó pegado al artículo ("Artículo 72" por "Artículo 7" + nota 2),
     * se detecta porque rompe la secuencia y se corrige solo si al quitarle 1 o 2 cifras
     * resulta exactamente el artículo siguiente al anterior.
     */
    private fun repararNumeracion(parrafos: List<Parrafo>): List<Parrafo> {
        var ultimo = -1L
        val res = ArrayList<Parrafo>(parrafos.size)
        for (p in parrafos) {
            val m = if (p.esTitulo) null else RE_ART_NUM.find(p.texto)
            if (m == null) { res.add(p); continue }
            val digitos = m.groupValues[2]
            val n = digitos.toLongOrNull()
            if (n == null || digitos.length > 9) { res.add(p); continue }
            var valor: Long = n
            var texto = p.texto
            if (ultimo >= 0 && n > ultimo + 1) {
                for (corte in 1..2) {
                    if (digitos.length <= corte) break
                    val c = digitos.dropLast(corte).toLong()
                    if (c == ultimo + 1) {
                        val g = m.groups[2]!!
                        texto = texto.substring(0, g.range.first) + c + texto.substring(g.range.last + 1)
                        valor = c
                        break
                    }
                }
            }
            ultimo = valor
            res.add(if (texto == p.texto) p else Parrafo(texto, p.pagina, p.esTitulo, p.generico))
        }
        return res
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
            if (tituloActual != null) lista.addAll(partesTitulo)
            lista.addAll(cuerpo)
            if (lista.isEmpty()) lista.add(nombre)
            titulos.add(recortar(nombre))
            contenidos.add(lista)
        }

        for (p in parrafos) {
            if (p.esTitulo) {
                val clave = claveExacta(p.texto)
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
