package com.oasis.reader

import java.text.Normalizer

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

    private fun hallar(t: String, q: String): List<IntRange> {
        val art = Regex("^art(?:iculo|\\.)?\\s*(\\d+)$").find(q)
        if (art != null) {
            val regex = Regex("art(?:iculo|\\.)\\s*${art.groupValues[1]}(?!\\d)")
            return regex.findAll(t).map { it.range }.toList()
        }
        val out = ArrayList<IntRange>()
        var i = t.indexOf(q)
        while (i >= 0) {
            out.add(i until i + q.length)
            i = t.indexOf(q, i + q.length)
        }
        return out
    }

    // Para resaltar en el párrafo que se muestra
    fun coincidencias(textoOriginal: String, consulta: String): List<IntRange> {
        val q = norm(consulta.trim())
        if (q.length < 2) return emptyList()
        return hallar(norm(textoOriginal), q)
    }

    fun buscar(consulta: String, max: Int = 200): List<Resultado> {
        val q = norm(consulta.trim())
        if (q.length < 2) return emptyList()
        val out = ArrayList<Resultado>()
        for (c in indice.indices) for (p in indice[c].indices) {
            val orig = capitulos[c][p]
            for (r in hallar(indice[c][p], q)) {
                val a = maxOf(0, r.first - 30)
                val b = minOf(orig.length, r.last + 31)
                out.add(Resultado(c, p, r.first, r.last - r.first + 1, orig.substring(a, b)))
                if (out.size >= max) return out
            }
        }
        return out
    }
}
