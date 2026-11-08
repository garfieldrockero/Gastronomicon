package io.github.garfieldrockero.gastronomicon.core.recipes

import java.text.Normalizer
import java.util.Locale

/**
 * Filtro local de una lista ya cargada, sin acentos ni mayúsculas
 * («cafe» encuentra «Café», «nino» encuentra «Niño»).
 *
 * Cada palabra de la búsqueda tiene que aparecer en alguno de los textos del
 * elemento (todas a la vez, en cualquier orden).
 */
object TextFilter {
    private const val MAX_QUERY_LENGTH = 200

    // El texto viene del servidor: se acota para que un campo gigante no
    // convierta cada pulsación del teclado en un trabajo enorme.
    private const val MAX_FIELD_LENGTH = 2_000
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    /** Minúsculas, sin marcas diacríticas y con espacios colapsados. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return MARKS.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(SPACES, " ")
            .trim()
    }

    /** Elementos cuyos textos contienen todas las palabras de `query`; sin búsqueda, la lista tal cual. */
    fun <T> filter(items: List<T>, query: String?, fields: (T) -> List<String?>): List<T> {
        val tokens = query?.take(MAX_QUERY_LENGTH)?.let { normalize(it) }
            ?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        if (tokens.isEmpty()) return items
        return items.filter { item ->
            val haystack = fields(item).filterNotNull().joinToString(" ") { normalize(it.take(MAX_FIELD_LENGTH)) }
            tokens.all { it in haystack }
        }
    }
}
