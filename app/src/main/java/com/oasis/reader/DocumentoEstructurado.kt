package com.oasis.reader

/**
 * Modelo de datos para documentos jurídicos con estructura jerárquica.
 *
 * Un documento (Libro, Código, Ley) se representa como un árbol de [Nodo].
 * La raíz es artificial (nivel 0, título vacío) y solo sirve como punto de
 * entrada. Sus hijos son los "libros" o bloques de nivel superior reales.
 *
 * Diseño pensado para uso jurídico:
 *  - Los títulos NUNCA se truncan.
 *  - Un nodo con [revisar] = true contiene texto en duda (OCR dudoso,
 *    párrafo huérfano). La UI debe señalarlo.
 *  - Los artículos viven como objetos [Articulo] dentro del nodo hoja,
 *    no como nodos independientes. Esto preserva la jerarquía real.
 */
data class DocumentoEstructurado(
    val raiz: Nodo,
    val titulo: String,
    val tipoOrigen: TipoOrigen,
    /** Índice para búsqueda rápida: "51 Bis" -> Articulo + ruta. */
    val indiceArticulos: Map<String, ReferenciaArticulo> = emptyMap(),
    /** Avisos no bloqueantes para mostrar al usuario (OCR, páginas omitidas). */
    val avisos: List<String> = emptyList()
) {

    enum class TipoOrigen { PDF, EPUB }

    /** Devuelve todos los nodos hoja en orden de lectura (pre-order). */
    fun nodosHoja(): List<Nodo> = raiz.hojas()

    /** Devuelve la ruta numérica ("0.2.1") de un nodo, o null si no existe. */
    fun rutaDe(nodo: Nodo): List<Int>? = raiz.rutaHacia(nodo)

    /** Busca un nodo por su ruta numérica. */
    fun nodoPorRuta(ruta: List<Int>): Nodo? = raiz.porRuta(ruta)

    /**
     * Busca un nodo por su path completo de títulos. Fallback para cuando
     * el documento se reforma y la ruta numérica deja de ser válida.
     */
    fun nodoPorPathTitulos(path: String): Nodo? = raiz.porPathTitulos(path)
}

/**
 * Un nodo del árbol. Puede tener contenido propio, hijos, o ambos.
 *
 * @param nivel        0 = raíz artificial. 1 = Libro. 2 = Título.
 *                     3 = Capítulo. 4 = Sección. 5 = Sub-sección...
 * @param titulo       Título completo. Nunca se trunca.
 * @param contenido    Párrafos propios (introducción al capítulo, etc.).
 * @param hijos        Sub-nodos.
 * @param articulos    Artículos detectados dentro del contenido del nodo.
 * @param oculto       true = auxiliar (Notas, Glosario, Índice analítico).
 * @param revisar      true = texto en duda (OCR dudoso, heurística conservadora).
 * @param id           Ruta numérica actual ("0.2.1") para persistencia rápida.
 * @param pathTitulos  Títulos concatenados ("LIBRO SEGUNDO > CAPÍTULO QUINTO")
 *                     para reubicar al usuario si el documento se reforma.
 */
data class Nodo(
    val nivel: Int,
    val titulo: String,
    val contenido: MutableList<String> = mutableListOf(),
    val hijos: MutableList<Nodo> = mutableListOf(),
    val articulos: MutableList<Articulo> = mutableListOf(),
    var oculto: Boolean = false,
    var revisar: Boolean = false,
    var id: String = "",
    var pathTitulos: String = ""
) {

    /** ¿Este nodo tiene contenido legible propio? */
    fun tieneContenido(): Boolean = contenido.isNotEmpty() || articulos.isNotEmpty()

    /** ¿Este nodo es una hoja navegable? (tiene contenido, sin hijos con contenido) */
    fun esHojaNavegable(): Boolean =
        tieneContenido() && hijos.none { it.tieneContenidoRecursivo() }

    private fun tieneContenidoRecursivo(): Boolean =
        tieneContenido() || hijos.any { it.tieneContenidoRecursivo() }

    /** Recorrido pre-order: devuelve todos los descendientes en orden de lectura. */
    fun hojas(): List<Nodo> {
        val out = mutableListOf<Nodo>()
        fun recorrer(n: Nodo) {
            if (n.esHojaNavegable()) out.add(n)
            for (h in n.hijos) recorrer(h)
        }
        for (h in hijos) recorrer(h)
        return out
    }

    /** Ruta numérica desde este nodo hacia un descendiente. */
    fun rutaHacia(objetivo: Nodo): List<Int>? {
        if (this === objetivo) return emptyList()
        for ((i, h) in hijos.withIndex()) {
            val sub = h.rutaHacia(objetivo)
            if (sub != null) return listOf(i) + sub
        }
        return null
    }

    /** Busca por ruta numérica. */
    fun porRuta(ruta: List<Int>): Nodo? {
        if (ruta.isEmpty()) return this
        val h = hijos.getOrNull(ruta[0]) ?: return null
        return h.porRuta(ruta.drop(1))
    }

    /** Busca por path de títulos. */
    fun porPathTitulos(path: String): Nodo? {
        if (pathTitulos == path) return this
        for (h in hijos) {
            val r = h.porPathTitulos(path)
            if (r != null) return r
        }
        return null
    }

    /** Recalcula id y pathTitulos de todo el subárbol. */
    fun recalcularIds(prefijo: String = "", prefijoTitulos: String = "") {
        id = prefijo
        pathTitulos = if (prefijoTitulos.isEmpty()) titulo else "$prefijoTitulos > $titulo"
        for ((i, h) in hijos.withIndex()) {
            val subPrefijo = if (prefijo.isEmpty()) "$i" else "$prefijo.$i"
            h.recalcularIds(subPrefijo, pathTitulos)
        }
    }

    /** true si este nodo o algún descendiente tiene revisar=true. */
    fun tieneRevisionPendiente(): Boolean =
        revisar || hijos.any { it.tieneRevisionPendiente() }
}

/**
 * Un artículo dentro de un nodo hoja.
 *
 * @param numero          "5", "51 Bis", "56 Nonies", "198 Quater"...
 * @param texto           Cuerpo del artículo.
 * @param posicionParrafo Índice del párrafo en `Nodo.contenido` donde empieza.
 */
data class Articulo(
    val numero: String,
    val texto: String,
    val posicionParrafo: Int
)

/** Referencia a un artículo dentro del documento. */
data class ReferenciaArticulo(
    val numero: String,
    val ruta: List<Int>,
    val posicionParrafo: Int
)
