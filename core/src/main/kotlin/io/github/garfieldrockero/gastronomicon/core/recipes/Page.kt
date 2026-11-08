package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * Página de resultados con la aritmética de paginación en el núcleo.
 *
 * El servidor manda `next` y `previous` como URLs, pero aquí se ignoran:
 * seguir una URL ajena sacaría la petición (y el token) del origen
 * configurado. La página siguiente se calcula con `page` y `perPage`.
 * Todas las cifras del servidor se tratan como no fiables y se acotan.
 */
class Page<out T> private constructor(
    val items: List<T>,
    val page: Int,
    val perPage: Int,
    val total: Long,
) {
    /** Páginas que caben con ese total; se calcula, no se fía del `total_pages` del servidor. */
    // Sin sumar antes de dividir: «total + perPage» desbordaría con totales enormes.
    val totalPages: Int =
        (total / perPage + if (total % perPage != 0L) 1 else 0)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    // Con la página vacía se corta aunque el total diga que hay más: un
    // servidor incoherente no debe dejar a la lista pidiendo páginas sin fin.
    val hasNext: Boolean get() = items.isNotEmpty() && page < totalPages

    val hasPrevious: Boolean get() = page > 1

    /** Número de la página siguiente, o null si no hay. */
    val nextPage: Int? get() = if (hasNext) page + 1 else null

    val previousPage: Int? get() = if (hasPrevious) page - 1 else null

    /** Consulta de la página siguiente a partir de la actual, o null si no hay. */
    fun nextQuery(current: RecipeListQuery): RecipeListQuery? =
        nextPage?.let { current.withPage(it) }

    companion object {
        /** Datos de página tal como llegan: se acotan los valores imposibles. */
        fun <T> of(items: List<T>, page: Int, perPage: Int, total: Long): Page<T> =
            Page(
                items = items.toList(),
                page = page.coerceAtLeast(1),
                perPage = perPage.coerceAtLeast(1),
                total = total.coerceAtLeast(0),
            )
    }
}
