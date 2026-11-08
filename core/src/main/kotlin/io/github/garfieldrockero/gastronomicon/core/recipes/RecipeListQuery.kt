package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import io.github.garfieldrockero.gastronomicon.core.network.HttpRequest

/** Sentido de ordenación; los valores de la API (`asc`, `desc`) son los de OrderDirection en openapi.json. */
enum class SortDirection(val apiValue: String) {
    ASCENDING("asc"),
    DESCENDING("desc"),
}

/**
 * Parámetros de GET /api/recipes que usa la app: page, perPage, search,
 * orderBy y orderDirection (nombres tomados de docs/openapi.json).
 *
 * Los valores se normalizan en vez de rechazarse cuando vienen de la
 * interfaz (página y tamaño fuera de rango, texto largo o con controles), y
 * solo `orderBy` lanza excepción porque es un nombre de campo, no texto libre.
 */
class RecipeListQuery(
    page: Int = 1,
    perPage: Int = DEFAULT_PER_PAGE,
    search: String? = null,
    orderBy: String? = null,
    val orderDirection: SortDirection? = null,
) {
    val page: Int = page.coerceAtLeast(1)

    // Tope razonable: una página enorme es memoria y tiempo de respuesta
    // que decide quien escribe el parámetro, y el servidor podría aceptarla.
    val perPage: Int = perPage.coerceIn(1, MAX_PER_PAGE)

    val search: String? = normalizeSearch(search)

    val orderBy: String? = orderBy?.also {
        // Se exige un identificador simple y no se asume una lista de campos:
        // la lista depende de la versión de Mealie y no está en el JSON.
        require(ORDER_FIELD.matches(it)) { "El campo de ordenación no es un nombre válido." }
    }

    /** Consulta ya codificada (sin «?»), con el orden de parámetros fijo para poder probarla. */
    fun toQueryString(): String {
        val parts = ArrayList<String>(5)
        parts += "page=$page"
        parts += "perPage=$perPage"
        search?.let { parts += "search=" + PercentEncoding.encode(it) }
        orderBy?.let { parts += "orderBy=" + PercentEncoding.encode(it) }
        orderDirection?.let { parts += "orderDirection=" + it.apiValue }
        return parts.joinToString("&")
    }

    /** La petición lista para el transporte; la ruta es fija y la consulta va validada aparte. */
    fun toRequest(): HttpRequest =
        HttpRequest(HttpMethod.GET, PATH, query = toQueryString())

    /** Misma consulta con otra página; la paginación la calcula `Page`, nunca las URLs next/previous. */
    fun withPage(newPage: Int): RecipeListQuery =
        RecipeListQuery(newPage, perPage, search, orderBy, orderDirection)

    companion object {
        const val PATH = "/api/recipes"
        const val DEFAULT_PER_PAGE = 20
        const val MAX_PER_PAGE = 100
        const val MAX_SEARCH_LENGTH = 200

        private val ORDER_FIELD = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

        private fun normalizeSearch(raw: String?): String? {
            if (raw == null) return null
            val cleaned = raw.filter { !Character.isISOControl(it) }.trim()
            if (cleaned.isEmpty()) return null
            var end = minOf(cleaned.length, MAX_SEARCH_LENGTH)
            // No cortar un par sustituto por la mitad.
            if (end < cleaned.length && Character.isHighSurrogate(cleaned[end - 1])) end--
            return cleaned.substring(0, end).trim().ifEmpty { null }
        }
    }
}

/**
 * Codificación de porcentaje RFC 3986 para valores de consulta: solo los
 * caracteres sin reservar pasan tal cual y el resto va en UTF-8 como «%XX»
 * (el espacio, `%20`, nunca «+»; «&», «=», «#» y «?» también quedan escapados).
 */
internal object PercentEncoding {
    private const val HEX = "0123456789ABCDEF"

    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        // toByteArray sustituye un sustituto suelto por «?», que luego se escapa.
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c)
            } else {
                val v = byte.toInt() and 0xFF
                out.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
            }
        }
        return out.toString()
    }
}
