package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem

/** Qué está haciendo la lista ahora mismo. */
enum class ListPhase {
    Idle,

    /** Primera carga o carga nueva (otra búsqueda): no hay nada que enseñar todavía. */
    LoadingFirst,

    /** Recarga con la lista anterior aún a la vista. */
    Refreshing,

    /** Se está pidiendo la página siguiente. */
    LoadingMore,
}

/**
 * Estado de la lista de recetas. Es inmutable: cada paso devuelve otro estado
 * y la interfaz solo lo pinta. Los pasos que no necesitan red (los de aquí)
 * se prueban sin nada más; [RecipeListController] los encadena con la red.
 *
 * Los elementos ya vienen saneados (ver `RecipeMapper`), así que aquí solo se
 * ordena, se deduplica y se acota lo que llega.
 */
data class RecipeListState(
    /** Búsqueda que se manda al servidor, ya normalizada; null si no hay. */
    val search: String? = null,
    /** Filtro local sobre lo ya cargado; no toca la red. */
    val localFilter: String = "",
    /** Todo lo cargado, sin repetidos por slug y en el orden del servidor. */
    val items: List<RecipeSummary> = emptyList(),
    /** Última página cargada (0 si ninguna). */
    val lastPage: Int = 0,
    val hasMore: Boolean = false,
    /** Total que dice el servidor; es solo informativo. */
    val total: Long = 0,
    val phase: ListPhase = ListPhase.Idle,
    /** Motivo del último fallo; se borra al empezar otra carga. */
    val problem: ConnectionProblem? = null,
    /** Si ya ha terminado bien alguna carga con esta búsqueda. */
    val loadedOnce: Boolean = false,
) {
    /** Lo cargado filtrado por [localFilter] (nombre, descripción, etiquetas y categorías). */
    val visibleItems: List<RecipeSummary> by lazy {
        TextFilter.filter(items, localFilter) { item ->
            listOf(item.name, item.description) + item.tags.map { it.name } + item.categories.map { it.name }
        }
    }

    val isLoading: Boolean get() = phase != ListPhase.Idle

    /** Cargada sin fallos y sin ninguna receta: la interfaz enseña «no hay recetas». */
    val isEmpty: Boolean get() = loadedOnce && phase == ListPhase.Idle && problem == null && items.isEmpty()

    /** Se puede pedir la página siguiente ahora. */
    val canLoadMore: Boolean get() = hasMore && phase == ListPhase.Idle

    // ---------------------------------------------------------------- pasos

    /** Otra consulta desde cero: se vacía la lista para no enseñar resultados de la anterior. */
    fun startFirstLoad(newSearch: String?): RecipeListState =
        RecipeListState(search = newSearch, localFilter = localFilter, phase = ListPhase.LoadingFirst)

    /** Recarga: la lista actual se queda a la vista hasta que llegue la nueva. */
    fun startRefresh(): RecipeListState = copy(phase = ListPhase.Refreshing, problem = null)

    fun startLoadMore(): RecipeListState = copy(phase = ListPhase.LoadingMore, problem = null)

    /** El texto se limpia y se acota: es entrada de la persona y no se guarda tal cual. */
    fun withLocalFilter(text: String): RecipeListState =
        copy(localFilter = PlainTextSanitizer.sanitize(text, MAX_FILTER_LENGTH))

    /**
     * Incorpora la página [requestedPage] que ha devuelto el servidor.
     *
     * - Si el servidor contesta con otra página que la pedida (p. ej. ignora
     *   `page` y manda siempre la 1), no se avanza: seguir pidiendo daría la
     *   misma página sin fin. Es un fallo ([ConnectionProblem.Unknown]) y,
     *   salvo en una recarga, se deja de ofrecer «más».
     * - Se añade solo lo que no estaba (por slug), dentro de la página y entre
     *   páginas: el servidor puede repetir recetas si cambian los datos entre
     *   dos peticiones.
     * - «Hay más» exige que la página nueva haya aportado algo: una página de
     *   puros repetidos con un total incoherente no puede mantener la lista
     *   pidiendo páginas.
     * - Con [MAX_ITEMS] recetas se para: es un tope de memoria, y quien busque
     *   algo más allá tiene la búsqueda.
     */
    fun withPage(requestedPage: Int, page: Page<RecipeSummary>): RecipeListState {
        if (page.page != requestedPage) {
            return copy(
                phase = ListPhase.Idle,
                problem = ConnectionProblem.Unknown,
                hasMore = phase == ListPhase.Refreshing && hasMore,
            )
        }
        val appending = phase == ListPhase.LoadingMore
        val merged = ArrayList<RecipeSummary>(if (appending) items.size + page.items.size else page.items.size)
        val seen = HashSet<String>()
        if (appending) {
            for (item in items) {
                if (seen.add(item.slug)) merged += item
            }
        }
        val before = merged.size
        for (item in page.items) {
            if (merged.size >= MAX_ITEMS) break
            if (seen.add(item.slug)) merged += item
        }
        val added = merged.size - before
        return copy(
            items = merged,
            lastPage = page.page,
            hasMore = page.hasNext && added > 0 && merged.size < MAX_ITEMS,
            total = page.total,
            phase = ListPhase.Idle,
            problem = null,
            loadedOnce = true,
        )
    }

    /** Fallo de la carga en curso: lo cargado se conserva y [hasMore] no cambia (se puede reintentar). */
    fun withProblem(reason: ConnectionProblem): RecipeListState =
        copy(phase = ListPhase.Idle, problem = reason)

    // Sin el contenido: son datos del servidor y un estado puede acabar en un log.
    override fun toString(): String =
        "RecipeListState(items=${items.size}, lastPage=$lastPage, hasMore=$hasMore, phase=$phase, problem=$problem)"

    companion object {
        /** Tope de recetas en memoria. */
        const val MAX_ITEMS = 5_000

        const val MAX_FILTER_LENGTH = 200
    }
}
