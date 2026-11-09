package io.github.garfieldrockero.gastronomicon.ui.shopping

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingClient
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListController
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSource
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingText
import kotlinx.coroutines.CancellationException
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
 * Lo que pinta la pantalla: el estado del núcleo más lo que pasa ANTES de tener
 * lista (buscar cuál abrir) y el texto del campo de añadir.
 *
 * Sin token, sin URL del servidor y sin mensajes de excepción: los fallos son un
 * [ConnectionProblem] o un motivo tipado del núcleo.
 */
data class ShoppingUiState(
    val list: ShoppingListState = ShoppingListState(),
    /** Buscando qué lista abrir (antes de la primera carga de sus elementos). */
    val resolving: Boolean = false,
    /** Fallo al buscar la lista; la pantalla lo enseña con «Volver a intentar». */
    val resolveProblem: ConnectionProblem? = null,
    /** Mealie respondió bien pero no hay ninguna lista de la compra. */
    val noList: Boolean = false,
    /** Texto del campo de añadir; se conserva si añadir falla. */
    val draft: String = "",
)

/**
 * Pasarela entre la lista de la compra y el [ShoppingListController]: no decide
 * nada. Lanza cada operación (bloqueante) en un hilo de fondo y publica el
 * estado del controlador.
 *
 * - Cada petición crea su cliente con [ShoppingAccess] (nunca se guarda uno), y
 *   un fallo al crearlo llega al controlador como [ApiResult] tipado, no como
 *   excepción.
 * - Mientras hay operaciones en marcha, un sondeo corto publica el estado del
 *   controlador: así el marcado optimista, que el núcleo aplica antes de ir a la
 *   red, se ve al instante.
 * - Al terminar se publica el estado VIGENTE del controlador, no el devuelto
 *   por la operación (puede haber otras en vuelo).
 * - Qué lista se abre (hoy, la primera por nombre) es una regla provisional: ver
 *   el informe; si hace falta elegir entre varias, que la ponga el núcleo.
 * - [reset] cancela todo, sube la época y empieza de cero (cambio o borrado de
 *   sesión): lo que ya estaba en vuelo no usa la sesión nueva.
 */
class ShoppingViewModel(
    private val access: ShoppingAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * Sube con cada [reset]. La fuente de cada controlador compara esta época
     * con la suya antes de pedir nada: una operación de la sesión anterior no
     * llega a leer el almacén seguro ni a abrir conexión con el servidor nuevo.
     * Se escribe desde el hilo principal y se lee desde el de fondo (`@Volatile`).
     */
    @Volatile
    private var epoch = 0L

    private var controller: ShoppingListController = newController()
    private var resolved = false
    private var resolving = false
    private var resolveProblem: ConnectionProblem? = null
    private var noList = false
    private var draft = ""

    /** Aviso que la persona ya ha cerrado; ver [visibleList]. */
    private var dismissedProblem: ShoppingProblem? = null

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<ShoppingUiState> = _state.asStateFlow()

    // Solo se tocan desde el hilo principal (viewModelScope).
    private var inFlight = 0
    private var poller: Job? = null
    private val running = mutableSetOf<Job>()

    private fun newController(): ShoppingListController {
        // La fuente nace con la época de ahora: si reset() la supera, deja de pedir.
        val bornEpoch = epoch
        return ShoppingListController(sourceFor(bornEpoch))
    }

    /**
     * Fuente de UNA sesión ([bornEpoch]). El cliente se crea en cada llamada y
     * no se guarda; un fallo al crearlo (sin sesión, almacén sin respuesta, HTTP
     * local con datos móviles) se convierte en [ApiResult] de fallo y no puede
     * escapar de la corrutina. La llamada va fuera del `try` para no perder el
     * matiz de [NetworkError.AfterSend] al añadir.
     */
    private fun sourceFor(bornEpoch: Long): ShoppingListSource = object : ShoppingListSource {

        override fun getList(listId: String) = withClient(bornEpoch) { it.getList(listId) }

        override fun addItem(listId: String, text: String) =
            withClient(bornEpoch) { it.addItem(listId, text) }

        override fun setChecked(item: ShoppingItem, checked: Boolean) =
            withClient(bornEpoch) { it.setChecked(item, checked) }

        override fun editItem(item: ShoppingItem, note: String, quantity: Double) =
            withClient(bornEpoch) { it.editItem(item, note, quantity) }

        override fun deleteItem(itemId: String) = withClient(bornEpoch) { it.deleteItem(itemId) }

        override fun deleteChecked(items: List<ShoppingItem>) =
            withClient(bornEpoch) { it.deleteChecked(items) }
    }

    private fun <T> withClient(bornEpoch: Long, call: (ShoppingClient) -> ApiResult<T>): ApiResult<T> {
        // Una operación de antes de reset() no puede pedir con la sesión nueva.
        if (bornEpoch != epoch) return ApiResult.Failure(ConnectionProblem.Unknown)
        val client = try {
            access.newClient()
        } catch (error: NetworkError) {
            return failureOf(error)
        }
        return call(client)
    }

    /** 401 y 403 se distinguen; el resto se queda en el motivo de conexión de siempre. */
    private fun failureOf(error: NetworkError): ApiResult<Nothing> = when {
        error is NetworkError.HttpError && error.statusCode == 401 -> ApiResult.Unauthorized
        error is NetworkError.HttpError && error.statusCode == 403 -> ApiResult.Forbidden
        else -> ApiResult.Failure(error.toConnectionProblem())
    }

    private fun snapshot() = ShoppingUiState(
        list = visibleList(controller.state),
        resolving = resolving,
        resolveProblem = resolveProblem,
        noList = noList,
        draft = draft,
    )

    /**
     * Un aviso que la persona ha cerrado no se vuelve a pintar. Solo se oculta
     * ESE aviso: al empezar cualquier operación se olvida, así que si el fallo
     * se repite, vuelve a salir. No se toca el estado del núcleo.
     */
    private fun visibleList(list: ShoppingListState): ShoppingListState {
        val problem = list.problem ?: return list
        return if (problem == dismissedProblem) list.copy(problem = null) else list
    }

    private fun publish(source: ShoppingListController = controller) {
        if (source !== controller) return
        _state.value = snapshot()
    }

    // ------------------------------------------------------------- lectura

    /** Al mostrar la pantalla: primera carga, o repetir si lo último fue un fallo. */
    fun onShown() {
        if (!resolved) {
            if (!resolving) resolve()
            return
        }
        val current = controller.state
        if (current.problem != null && !current.isLoading) onRetry()
    }

    /** «Volver a intentar» y «Recargar»: cada caso lo resuelve el núcleo o se vuelve a buscar la lista. */
    fun onRetry() {
        if (!resolved) {
            if (!resolving) resolve()
            return
        }
        launchOperation { c ->
            if (c.state.problem != null && !c.state.loadedOnce) c.retry() else c.refresh()
        }
    }

    fun onRefresh() = onRetry()

    /** Cierra el aviso que está a la vista (un rechazo de la app, por ejemplo). */
    fun onDismissProblem() {
        val current = controller.state
        // Solo con la lista a la vista: un fallo sin nada que enseñar tiene su propia pantalla.
        if (current.loadedOnce) dismissedProblem = current.problem
        publish()
    }

    /** Busca la lista que abrir y la carga. */
    private fun resolve() {
        val target = controller
        val bornEpoch = epoch
        resolving = true
        resolveProblem = null
        noList = false
        dismissedProblem = null
        publish(target)
        val job = viewModelScope.launch {
            val outcome = try {
                withContext(ioDispatcher) {
                    // Una sesión reiniciada mientras se buscaba la lista: no se pide nada.
                    if (bornEpoch != epoch) Problem(ConnectionProblem.Unknown) else findList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unexpected: Exception) {
                // Red de seguridad: la pantalla nunca se queda sin decir nada.
                Problem(ConnectionProblem.Unknown)
            }
            if (target !== controller) return@launch
            resolving = false
            when (outcome) {
                is Found -> {
                    resolved = true
                    publish(target)
                    launchOperation(target) { it.open(outcome.id) }
                }
                NoList -> {
                    // Sin `resolved`: «Volver a mirar» y volver a la pantalla buscan otra vez.
                    noList = true
                    publish(target)
                }
                is Problem -> {
                    resolveProblem = outcome.problem
                    publish(target)
                }
            }
        }
        running.add(job)
        job.invokeOnCompletion { running.remove(job) }
    }

    // ------------------------------------------------------------- escritura

    fun onToggle(itemId: String) = launchOperation { it.toggle(itemId) }

    fun onDraftChange(text: String) {
        // El tope es el del núcleo: lo que se escribe nunca pasa de lo que se aceptaría.
        draft = text.take(ShoppingText.MAX_ITEM_TEXT)
        publish()
    }

    /** Añade lo escrito. Si sale bien el campo se vacía; si no, el texto se conserva. */
    fun onAdd() {
        val text = draft
        launchOperation(onDone = { result ->
            // Solo se vacía si el núcleo no ha puesto ningún aviso (con «puede que ya
            // esté añadido» se conserva, para que la persona decida qué hacer).
            if (result.problem == null && draft == text) draft = ""
        }) { it.add(text) }
    }

    fun onEditNote(itemId: String, note: String, quantity: Double) =
        launchOperation { it.editNote(itemId, note, quantity) }

    fun onDelete(itemId: String) = launchOperation { it.delete(itemId) }

    fun onDeleteChecked() = launchOperation { it.deleteChecked() }

    /** Cancela lo que corra y empieza de cero: cambio de sesión, borrado o primera configuración. */
    fun reset() {
        running.forEach { it.cancel() }
        running.clear()
        poller?.cancel()
        poller = null
        inFlight = 0
        // Nueva época: lo que siga en vuelo no puede pedir con la sesión nueva.
        epoch++
        resolved = false
        resolving = false
        resolveProblem = null
        noList = false
        draft = ""
        dismissedProblem = null
        controller = newController()
        publish()
    }

    // ------------------------------------------------------------- privados

    private fun launchOperation(
        target: ShoppingListController = controller,
        onDone: (ShoppingListState) -> Unit = {},
        operation: (ShoppingListController) -> ShoppingListState,
    ) {
        // Se cierra el aviso anterior: si esta operación falla igual, vuelve a salir.
        dismissedProblem = null
        viewModelScope.launch {
            // El propio trabajo, para poder cancelarlo en reset().
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
            var result: ShoppingListState? = null
            try {
                // El núcleo devuelve fallos tipados y su fuente no deja escapar la
                // creación del cliente; esto es una red de seguridad para que una
                // excepción rara no tumbe la app ni deje la fila «guardando».
                result = try {
                    withContext(ioDispatcher) { operation(target) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unexpected: Exception) {
                    null
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
                    val done = result
                    if (done != null) onDone(done)
                }
                publish(target)
            }
        }
    }

    private sealed interface Outcome
    private data class Found(val id: String) : Outcome
    private data object NoList : Outcome
    private data class Problem(val problem: ConnectionProblem) : Outcome

    /** Lee las listas del servidor y se queda con la primera (el cliente ya las ordena por nombre). */
    private fun findList(): Outcome {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return Problem(e.toConnectionProblem())
        } catch (e: Exception) {
            return Problem(ConnectionProblem.Unknown)
        }
        val result = try {
            client.listLists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Problem(ConnectionProblem.Unknown)
        }
        return when (result) {
            is ApiResult.Success -> {
                val first = result.value.items.firstOrNull()
                if (first == null) NoList else Found(first.id)
            }
            ApiResult.Unauthorized, ApiResult.Forbidden -> Problem(ConnectionProblem.Unauthorized)
            is ApiResult.Failure -> Problem(result.problem)
            is ApiResult.MaybeCreated -> Problem(result.problem)
            else -> Problem(ConnectionProblem.Unknown)
        }
    }

    companion object {
        // Más corto que en recetas: el marcado optimista tiene que verse al toque.
        private const val POLL_MILLIS = 60L

        fun factory(access: ShoppingAccess): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShoppingViewModel(access) as T
            }
    }
}
