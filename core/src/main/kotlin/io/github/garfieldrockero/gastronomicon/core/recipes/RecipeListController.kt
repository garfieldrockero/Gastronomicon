package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem

/**
 * Lleva la lista de recetas: búsqueda, páginas, recarga y filtro local. La
 * interfaz solo llama a estos métodos y pinta [state].
 *
 * Los métodos que piden datos (`load`, `search`, `refresh`, `loadMore`) son
 * **bloqueantes**, como el resto del núcleo: `app/` los lanza desde un hilo de
 * fondo y pinta el estado que devuelven (o lee [state], que es seguro desde
 * cualquier hilo). [setLocalFilter] no toca la red y es instantáneo.
 *
 * Una petición que ha sido superada por otra (la persona cambia la búsqueda o
 * recarga mientras llega la página siguiente) descarta su resultado al
 * terminar, y devuelve el estado vigente: así una respuesta lenta de la
 * búsqueda anterior no pisa a la nueva. `loadMore` mientras ya se está
 * cargando no hace nada.
 *
 * Los fallos se reducen a [ConnectionProblem]; cualquier otra excepción de la
 * fuente también (como [ConnectionProblem.Unknown]) para que el estado nunca
 * se quede en «cargando». El mensaje de la excepción no se guarda.
 */
class RecipeListController(
    private val source: RecipeListSource,
    private val perPage: Int = RecipeListQuery.DEFAULT_PER_PAGE,
    private val orderBy: String? = null,
    private val orderDirection: SortDirection? = null,
) {
    private val lock = Any()

    // Cuenta las cargas lanzadas; solo la última tiene derecho a escribir el estado.
    private var generation = 0L

    @Volatile
    var state: RecipeListState = RecipeListState()
        private set

    init {
        // Un orden mal escrito es un fallo del programa: que salte al crear el
        // controlador y no en la primera petición.
        query(1, null)
    }

    /** Primera carga; no hace nada si ya hay una cargada o en curso. */
    fun load(): RecipeListState {
        val current = state
        if (current.loadedOnce || current.isLoading) return current
        return run(Kind.Refresh, current.search)
    }

    /**
     * Cambia la búsqueda del servidor y empieza de nuevo por la página 1. La
     * misma búsqueda (ya normalizada) no repite la petición, salvo que la
     * anterior haya fallado.
     */
    fun search(text: String?): RecipeListState {
        val normalized = query(1, text).search
        synchronized(lock) {
            val current = state
            val sameSearch = normalized == current.search
            if (sameSearch && current.problem == null && (current.loadedOnce || current.phase == ListPhase.LoadingFirst)) {
                return current
            }
        }
        return run(Kind.First, normalized)
    }

    /** Vuelve a pedir la página 1 con la búsqueda actual y sustituye la lista. */
    fun refresh(): RecipeListState = run(Kind.Refresh, state.search)

    /** Pide la página siguiente; sin efecto si no hay más o ya se está cargando. */
    fun loadMore(): RecipeListState = run(Kind.More, state.search)

    /** Filtro local sobre lo cargado, sin red. */
    fun setLocalFilter(text: String): RecipeListState =
        synchronized(lock) {
            val updated = state.withLocalFilter(text)
            state = updated
            updated
        }

    // ------------------------------------------------------------- privados

    private enum class Kind { First, Refresh, More }

    private class Launch(val ticket: Long, val requestedPage: Int, val query: RecipeListQuery)

    private fun query(page: Int, search: String?) =
        RecipeListQuery(page, perPage, search, orderBy, orderDirection)

    private fun begin(kind: Kind, search: String?): Launch? =
        synchronized(lock) {
            val current = state
            val requested: Int
            when {
                kind == Kind.More -> {
                    if (!current.canLoadMore) return@synchronized null
                    requested = current.lastPage + 1
                    state = current.startLoadMore()
                }
                kind == Kind.Refresh && current.items.isNotEmpty() -> {
                    requested = 1
                    state = current.startRefresh()
                }
                else -> {
                    // Primera carga, búsqueda nueva o recarga de una lista vacía.
                    requested = 1
                    state = current.startFirstLoad(search)
                }
            }
            generation++
            Launch(generation, requested, query(requested, state.search))
        }

    private fun run(kind: Kind, search: String?): RecipeListState {
        val launch = begin(kind, search) ?: return state
        var page: Page<RecipeSummary>? = null
        var problem: ConnectionProblem? = null
        try {
            page = source.listRecipes(launch.query)
        } catch (error: NetworkError) {
            problem = error.toConnectionProblem()
        } catch (error: RuntimeException) {
            problem = ConnectionProblem.Unknown
        }
        // Copias inmutables: así no depende de que el compilador acepte el smart cast de un «var».
        val loaded = page
        val failure = problem ?: ConnectionProblem.Unknown
        return synchronized(lock) {
            if (launch.ticket != generation) {
                state
            } else {
                val updated = if (loaded != null) {
                    state.withPage(launch.requestedPage, loaded)
                } else {
                    state.withProblem(failure)
                }
                state = updated
                updated
            }
        }
    }
}
