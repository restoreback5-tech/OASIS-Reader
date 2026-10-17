package com.oasis.reader

import java.text.Normalizer

/**
 * Búsqueda en el libro. Para consultas de artículo ("artículo 123", "art. 3o", "123", "22 Bis", "836-A")
 * muestra primero los párrafos que ABREN ese artículo y después las menciones en otros textos
 * ("conforme al artículo 123 constitucional"). Cualquier otra consulta es texto literal, como antes.
 */
class BuscadorLibro(private val capitulos: List<List<String>>) {

    data class Resultado(
        val capitulo: Int, val parrafo: Int,
        val inicio: Int, val largo: Int, val fragmento: String
    )

    private fun norm(s: String) = buildString(s.length) {
        for (c in s) {
            if (c == 'ñ' || c == 'Ñ') { append('ñ'); continue }
            append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar())
        }
    }

    private val indice = capitulos.map { cap -> cap.map(::norm) }

    /** [encabezado] y [mencion] son null si la consulta no es de artículo. */
    private class Consulta(val texto: String, val encabezado: Regex?, val mencion: Regex?)

    private val reConsultaArt = Regex(
        "^(?:art(?:iculo|\\.)?\\s*)?(\\d{1,5})\\s*(?:o\\.?+|º|°)?\\s*-?\\s*(bis|ter|quater|quinquies|sexies|septies|octies|[a-z])?\\.?$"
    )

    // Texto literal de la última búsqueda de artículo que no encontró ninguno (para resaltar igual).
    private var literalPorFalta: String? = null

    private fun preparar(q: String): Consulta {
        val m = reConsultaArt.find(q) ?: return Consulta(q, null, null)
        val n = m.groupValues[1].trimStart('0').ifEmpty { "0" }
        val suf = m.groupValues[2]
        val cola = if (suf.isEmpty())
            "(?![0-9])(?!(?:\\s*(?:o\\.?+|º|°))?\\s*-?\\s*(?:bis|ter|quater|quinquies|sexies|septies|octies)(?![a-z]))" +
                "(?!(?:\\s*(?:o\\.?+|º|°))?-[a-z](?![a-z]))"
        else
            "\\s*-?\\s*$suf(?![a-z0-9])"
        val nucleo = "art(?:iculo|\\.)\\s*0*$n(?:\\s*(?:o\\.?+|º|°))?$cola"
        return Consulta(q, Regex("^\\s*$nucleo"), Regex(nucleo))
    }

    private fun literal(t: String, q: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = t.indexOf(q)
        while (i >= 0) {
            out.add(i until i + q.length)
            i = t.indexOf(q, i + q.length)
        }
        return out
    }

    private fun hallar(t: String, c: Consulta): List<IntRange> {
        if (c.mencion == null || c.encabezado == null) return literal(t, c.texto)
        val out = ArrayList<IntRange>()
        c.encabezado.find(t)?.let { out.add(it.range) }
        for (r in c.mencion.findAll(t)) if (out.none { it.first == r.range.first }) out.add(r.range)
        if (out.isEmpty() && literalPorFalta == c.texto) return literal(t, c.texto)
        return out
    }

    // Para resaltar en el párrafo que se muestra
    fun coincidencias(textoOriginal: String, consulta: String): List<IntRange> {
        val q = norm(consulta.trim())
        if (q.length < 2 && !reConsultaArt.matches(q)) return emptyList()
        return hallar(norm(textoOriginal), preparar(q))
    }

    fun buscar(consulta: String, max: Int = 200): List<Resultado> {
        val q = norm(consulta.trim())
        literalPorFalta = null
        if (q.length < 2 && !reConsultaArt.matches(q)) return emptyList()
        val c = preparar(q)
        val out = ArrayList<Resultado>()

        fun agregar(ci: Int, pi: Int, r: IntRange, largoCola: Int): Boolean {
            val orig = capitulos[ci][pi]
            val a = maxOf(0, r.first - 30)
            val b = minOf(orig.length, r.last + 1 + largoCola)
            out.add(Resultado(ci, pi, r.first, r.last - r.first + 1, orig.substring(a, b)))
            return out.size >= max
        }

        if (c.mencion != null && c.encabezado != null) {
            // 1) párrafos que abren el artículo
            for (ci in indice.indices) for (pi in indice[ci].indices) {
                val h = c.encabezado.find(indice[ci][pi]) ?: continue
                if (agregar(ci, pi, h.range, 90)) return out
            }
            // 2) menciones en otros textos
            for (ci in indice.indices) for (pi in indice[ci].indices) {
                val t = indice[ci][pi]
                val ini = c.encabezado.find(t)?.range?.first
                for (r in c.mencion.findAll(t)) {
                    if (r.range.first == ini) continue
                    if (agregar(ci, pi, r.range, 31)) return out
                }
            }
            if (out.isNotEmpty()) return out
            literalPorFalta = q          // ningún artículo con ese número: texto literal
        }
        for (ci in indice.indices) for (pi in indice[ci].indices) {
            for (r in literal(indice[ci][pi], q)) if (agregar(ci, pi, r, 31)) return out
        }
        return out
    }
}
