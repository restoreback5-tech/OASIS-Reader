package com.oasis.reader

import java.text.Normalizer

class BuscadorLibro(private val capitulos: List<List<String>>) {

    data class Resultado(
        val capitulo: Int, val parrafo: Int,
        val inicio: Int, val largo: Int, val fragmento: String
    )

    // Mismo largo que el original, así los offsets no se desfasan
    private fun norm(s: String) = buildString(s.length) {
        for (c in s) {
            if (c == 'ñ' || c == 'Ñ') { append('ñ'); continue }
            append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar())
        }
    }

    private val indice = capitulos.map { cap -> cap.map(::norm) }

    fun buscar(consulta: String, max: Int = 200): List<Resultado> {
        val q = norm(consulta.trim())
        if (q.length < 2) return emptyList()
        val out = ArrayList<Resultado>()
        for (c in indice.indices) for (p in indice[c].indices) {
            val t = indice[c][p]
            var i = t.indexOf(q)
            while (i >= 0) {
                val orig = capitulos[c][p]
                val a = maxOf(0, i - 30)
                val b = minOf(orig.length, i + q.length + 30)
                out.add(Resultado(c, p, i, q.length, orig.substring(a, b)))
                if (out.size >= max) return out
                i = t.indexOf(q, i + q.length)
            }
        }
        return out
    }
}
