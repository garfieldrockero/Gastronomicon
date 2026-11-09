package io.github.garfieldrockero.gastronomicon.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineCopyDate
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineList
import io.github.garfieldrockero.gastronomicon.core.offline.allowsOfflineCopy
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListController
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummary
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import io.github.garfieldrockero.gastronomicon.platform.OfflineRecipes
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
 * Aviso de que la lista que se ve es la de las copias guardadas: lleva la fecha
 * de la copia más reciente, ya en texto (la compone el núcleo, ver
 * `OfflineCopyDate`), y el motivo por el que no se pudo hablar con el servidor
 * cuando no es simple falta de red (certificado, HTTP local fuera de casa), para
 * que no se esconda lo que hay que arreglar.
 */
class OfflineBanner(
    val cachedAtText: String,
    val problem: ConnectionProblem? = null,
) {
    override fun toString(): String = "OfflineBanner(cachedAtText=$cachedAtText, problem=$problem)"
}

/**
 * Lo que pinta la lista: el estado del núcleo y el texto del campo de búsqueda.
 * Sin token, sin URL del servidor y sin mensajes de excepción: los fallos son
 * un `ConnectionProblem` dentro de [list].
 *
 * [topRequest] sube cada vez que la lista debe volver arriba (otra búsqueda,
 * otro filtro o un reinicio); quien tiene el estado del desplazamiento lo vigila.
 * [offline] no nulo = lo que hay en [list] son las copias guardadas, no el
 * servidor: la pantalla lo dice y ofrece «Volver a intentar».
 * [offlineScopeHint] = no hay ámbito guardado y sin él no habrá copias: la
 * pantalla pide «Probar conexión» para activarlas.
 */
class RecipeListUiState(
    val list: RecipeListState,
    val query: String,
    val topRequest: Int = 0,
    val offline: OfflineBanner? = null,
    val offlineScopeHint: Boolean = false,
)

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
 *
 * Fase 8: si la primera carga falla por red (no por 401/403/404 ni por un
 * servidor que contesta mal) y hay recetas guardadas de esa cuenta, se enseña
 * esa lista con su aviso y la fecha de la copia más reciente. Sin copias, el
 * error de siempre. En cuanto una carga sale bien, el aviso desaparece.
 */
class RecipeListViewModel(
    private val access: ClientAccess,
    private val offline: OfflineRecipes? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * Controlador vigente. `@Volatile`: lo lee el hilo de fondo ([updateOffline]) y
     * lo cambia el principal ([reset]), y la comparación de identidad tiene que ver
     * el valor nuevo.
     */
    @Volatile
    private var controller: RecipeListController = newController()
    private var query: String = ""
    private var topRequest = 0

    /**
     * Lo último que dijo el almacén sobre las copias guardadas, con la época y el
     * controlador a los que pertenece. Se escribe en el hilo de fondo y se lee al
     * publicar: `@Volatile`, y **validado al leer** en [publish] (`epoch` y
     * `target`), así una lectura lenta que termine después de un [reset] no puede
     * pintar la lista de la cuenta anterior.
     */
    @Volatile
    private var offlineSnapshot: OfflineSnapshot? = null

    /**
     * Sube al reiniciar (cambio de sesión). `updateOffline` corre en el hilo de
     * fondo: si `reset` ocurre mientras lee el almacén, lo que traiga de la cuenta
     * anterior no puede publicarse después.
     */
    @Volatile
    private var offlineEpoch = 0L

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
        controller.setLocalFilter(query)
        publish(controller)
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
        offlineSnapshot = null
        offlineEpoch++
        controller = newController()
        publish(controller)
    }

    private fun publish(source: RecipeListController) {
        if (source !== controller) return
        // Se validan la época y el controlador **al leer**: si la sesión cambió entre
        // la asignación del hilo de fondo y este punto, lo apuntado es de la cuenta
        // anterior y no se pinta (ni su lista ni su «sin ámbito»).
        val snapshot = offlineSnapshot?.takeIf { it.epoch == offlineEpoch && it.target === controller }
        val view = snapshot?.view
        // Las copias solo mandan mientras el servidor no haya dado nada.
        val showOffline = view != null && source.state.items.isEmpty()
        val noScope = snapshot?.noScope == true && source.state.items.isEmpty() && !source.state.isLoading
        _state.value = RecipeListUiState(
            list = if (view != null && showOffline) offlineListOf(source.state, view) else source.state,
            query = query,
            topRequest = topRequest,
            offline = if (view != null && showOffline) view.banner else null,
            offlineScopeHint = !showOffline && noScope,
        )
    }

    /**
     * La lista de copias con el filtro local que haya escrito la persona: se
     * parte del estado del controlador (que ya lo lleva) y se cambian solo las
     * recetas.
     *
     * La fase **no** se fuerza a `Idle`: si la persona pulsa «Volver a intentar»,
     * el aviso de la copia enseña que se está cargando y desactiva su botón. El
     * motivo del fallo sí se quita: ya lo cuenta el aviso de la copia.
     */
    private fun offlineListOf(base: RecipeListState, view: OfflineView): RecipeListState = base.copy(
        items = view.items,
        lastPage = 0,
        hasMore = false,
        total = view.items.size.toLong(),
        problem = null,
        loadedOnce = true,
        failedOperation = null,
    )

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
                withContext(ioDispatcher) {
                    operation(target)
                    updateOffline(target)
                }
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

    /**
     * Decide si toca enseñar las copias guardadas. Se llama en el hilo de fondo
     * después de cada operación, porque leer el almacén es bloqueante.
     *
     * Solo cuando el fallo es de red (ver `allowsOfflineCopy`) y no hay nada del
     * servidor en pantalla: una recarga fallida con recetas ya cargadas no
     * cambia lo que se ve. Sin ámbito no se ofrece nada (falla cerrado) y se
     * pide activarlo con «Probar conexión».
     *
     * Leer el almacén tarda: si mientras tanto la sesión cambia (`reset`), lo
     * leído es de la cuenta anterior. Por eso lo que se apunta lleva la época y el
     * controlador dentro, y `publish` lo descarta si ya no son los vigentes: la
     * comprobación va al leer, no aquí, pero se hace igual.
     */
    private fun updateOffline(target: RecipeListController) {
        if (target !== controller) return
        val bornEpoch = offlineEpoch
        val current = target.state
        val problem = current.problem
        val store = offline
        if (store == null || problem == null || current.items.isNotEmpty() || !problem.allowsOfflineCopy()) {
            publishOffline(null, noScope = false, bornEpoch = bornEpoch, target = target)
            return
        }
        val scope = store.scope()
        if (scope == null) {
            publishOffline(null, noScope = true, bornEpoch = bornEpoch, target = target)
            return
        }
        val cached = when (val listed = store.listRecipes(scope)) {
            is OfflineList.Ok -> listed.items
            is OfflineList.Failed -> emptyList()
        }
        if (cached.isEmpty()) {
            publishOffline(null, noScope = false, bornEpoch = bornEpoch, target = target)
            return
        }
        val view = OfflineView(
            // Sin red no hay fotos (la caché no las guarda): se piden solo si hay imagen que
            // pedir, así que se quita la marca y la fila se pinta con el texto.
            items = cached.map { it.summary.copy(hasImage = false) },
            banner = OfflineBanner(
                cachedAtText = OfflineCopyDate.format(cached.maxOf { it.cachedAtMillis }),
                problem = problem,
            ),
        )
        publishOffline(view, noScope = false, bornEpoch = bornEpoch, target = target)
    }

    /**
     * Apunta lo offline con la época y el controlador a los que pertenece. Aquí no
     * se decide nada: la comprobación va al leer ([publish]), que es el único punto
     * en el que el valor se usa.
     */
    private fun publishOffline(
        view: OfflineView?,
        noScope: Boolean,
        bornEpoch: Long,
        target: RecipeListController,
    ) {
        offlineSnapshot = OfflineSnapshot(view = view, noScope = noScope, epoch = bornEpoch, target = target)
    }

    /** Recetas guardadas y su aviso: lo que se pinta mientras el servidor no dé nada. */
    private class OfflineView(val items: List<RecipeSummary>, val banner: OfflineBanner)

    /**
     * Lo apuntado por [updateOffline]: la lista de copias (o null), si el fallo pedía
     * «Probar conexión», y la época y el controlador de los que viene, para poder
     * descartarlo al leer si la sesión cambió.
     */
    private class OfflineSnapshot(
        val view: OfflineView?,
        val noScope: Boolean,
        val epoch: Long,
        val target: RecipeListController,
    )

    companion object {
        private const val POLL_MILLIS = 120L

        fun factory(
            access: ClientAccess,
            offline: OfflineRecipes? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeListViewModel(access, offline) as T
            }
    }
}
