package io.github.garfieldrockero.gastronomicon.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListController
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lo que pinta la lista: el estado del núcleo y el texto del campo de búsqueda.
 * Sin token, sin URL del servidor y sin mensajes de excepción: los fallos son
 * un `ConnectionProblem` dentro de [list].
 *
 * [topRequest] sube cada vez que la lista debe volver arriba (otra búsqueda,
 * otro filtro o un reinicio); quien tiene el estado del desplazamiento lo vigila.
 */
class RecipeListUiState(val list: RecipeListState, val query: String, val topRequest: Int = 0)

/**
 * Pasarela entre la lista y el [RecipeListController]: no decide nada. Lanza
 * cada operación (que es bloqueante) en un hilo de fondo y publica el estado
 * del controlador, que es quien sabe si está cargando, si hay más páginas o
 * si algo ha fallado.
 *
 * - Cada petición crea su cliente con [ClientAccess] (nunca se guarda uno): así
 *   apagar el interruptor de HTTP local o borrar la sesión se nota al momento.
 * - Mientras hay operaciones en marcha, un sondeo corto publica el estado del
 *   controlador para que «cargando» se vea antes de que acabe la petición.
 * - Al terminar siempre se publica el estado VIGENTE del controlador, no el
 *   devuelto por esa operación: una operación que no hace nada (p. ej. «cargar
 *   más» mientras ya se carga) no debe tapar el resultado de la que sí corre.
 * - Cada búsqueda o filtro nuevo sube [RecipeListUiState.topRequest] para volver al principio.
 * - [reset] cancela todo y empieza con un controlador nuevo (al cambiar de
 *   sesión, borrarla o volver a la primera configuración); lo que quede de una petición vieja se descarta.
 */
class RecipeListViewModel(
    private val access: ClientAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private var controller: RecipeListController = newController()
    private var query: String = ""
    private var topRequest = 0

    private val _state = MutableStateFlow(RecipeListUiState(controller.state, query, topRequest))
    val state: StateFlow<RecipeListUiState> = _state.asStateFlow()

    // Solo se tocan desde el hilo principal (viewModelScope).
    private var inFlight = 0
    private var poller: Job? = null
    private val running = mutableSetOf<Job>()

    private fun newController() = RecipeListController(
        RecipeListSource { request -> access.newClient().listRecipes(request) },
    )

    /** Al mostrar la pantalla: primera carga; no hace nada si ya hay recetas o se está cargando. */
    fun onShown() = launchOperation { it.load() }

    /** Cada letra filtra lo ya cargado, sin red. */
    fun onQueryChange(text: String) {
        query = text.take(RecipeListState.MAX_FILTER_LENGTH)
        topRequest++
        _state.value = RecipeListUiState(controller.setLocalFilter(query), query, topRequest)
    }

    /** «Buscar»: pide al servidor las recetas que coinciden. Quita el filtro local para no filtrar dos veces. */
    fun onSubmitSearch() {
        val text = query.trim()
        topRequest++
        controller.setLocalFilter("")
        publish(controller)
        launchOperation { it.search(text.ifEmpty { null }) }
    }

    /** «Ver todas»: vuelve a la lista completa. */
    fun onClearSearch() {
        query = ""
        topRequest++
        controller.setLocalFilter("")
        publish(controller)
        launchOperation { it.search(null) }
    }

    fun onRefresh() = launchOperation { it.refresh() }

    fun onLoadMore() {
        if (!controller.state.canLoadMore) return
        launchOperation { it.loadMore() }
    }

    /**
     * «Volver a intentar»: el núcleo repite la operación que falló (recargar, cargar más
     * o la primera carga). Si el fallo no se arregla repitiendo (`canRetry` falso), se recarga.
     */
    fun onRetry() = launchOperation { if (it.state.canRetry) it.retry() else it.refresh() }

    /** Cancela lo que corra y empieza de cero: se llama cuando la sesión cambia, se borra o se vuelve a la primera configuración. */
    fun reset() {
        running.forEach { it.cancel() }
        running.clear()
        poller?.cancel()
        poller = null
        inFlight = 0
        query = ""
        topRequest++
        controller = newController()
        publish(controller)
    }

    private fun publish(source: RecipeListController) {
        if (source !== controller) return
        _state.value = RecipeListUiState(source.state, query, topRequest)
    }

    private fun launchOperation(operation: (RecipeListController) -> RecipeListState) {
        val target = controller
        viewModelScope.launch {
            // El propio trabajo, para poder cancelarlo en reset(). Se lee aquí y no de
            // la variable que devuelve launch: con el despachador inmediato esto corre antes.
            val self = coroutineContext[Job]
            if (self != null) running.add(self)
            inFlight++
            if (poller == null) {
                poller = viewModelScope.launch {
                    while (true) {
                        delay(POLL_MILLIS)
                        publish(target)
                    }
                }
            }
            try {
                withContext(ioDispatcher) { operation(target) }
            } finally {
                if (self != null) running.remove(self)
                if (target === controller) {
                    inFlight--
                    if (inFlight <= 0) {
                        inFlight = 0
                        poller?.cancel()
                        poller = null
                    }
                }
                publish(target)
            }
        }
    }

    companion object {
        private const val POLL_MILLIS = 120L

        fun factory(access: ClientAccess): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeListViewModel(access) as T
            }
    }
}
