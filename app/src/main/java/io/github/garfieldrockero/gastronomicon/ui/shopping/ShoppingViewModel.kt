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
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerController
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerState
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
 * Lo que pinta la pantalla: la lista abierta, la página de listas del selector,
 * el diálogo de crear y el texto del campo de añadir.
 *
 * Sin token, sin URL del servidor y sin mensajes de excepción: los fallos son un
 * [ConnectionProblem] o un motivo tipado del núcleo.
 */
data class ShoppingUiState(
    val list: ShoppingListState = ShoppingListState(),
    /**
     * Página de listas y cuál está abierta. Qué lista se abre, en qué orden van y
     * cuál queda elegida lo decide el núcleo (`ShoppingListPickerController`).
     */
    val picker: ShoppingListPickerState = ShoppingListPickerState(),
    /** El diálogo de «Nueva lista» está abierto. */
    val createDialog: Boolean = false,
    /** Fallo del último intento de crear, para enseñarlo dentro del diálogo. */
    val createProblem: ShoppingListPickerProblem? = null,
    /**
     * El `POST` de crear pudo llegar al servidor y no se sabe si la lista existe.
     * El aviso se queda hasta que la persona lo cierre: reintentar a ciegas
     * crearía otra lista.
     */
    val maybeCreated: Boolean = false,
    /** Texto del campo de añadir; se conserva si añadir falla. */
    val draft: String = "",
)

/**
 * Pasarela entre la lista de la compra y el núcleo ([ShoppingListController] para
 * los elementos, [ShoppingListPickerController] para el selector de listas): no
 * decide nada. Lanza cada operación (bloqueante) en un hilo de fondo y publica el
 * estado de los dos controladores.
 *
 * Los dos controladores comparten época y fuente porque comparten la lista
 * abierta: el selector dice cuál toca y este ViewModel la abre. Tenerlos en el
 * mismo sitio evita dos verdades sobre qué lista se está viendo.
 *
 * - Cada petición crea su cliente con [ShoppingAccess] (nunca se guarda uno), y
 *   un fallo al crearlo llega al controlador como [ApiResult] tipado, no como
 *   excepción.
 * - Mientras hay operaciones en marcha, un sondeo corto publica el estado de los
 *   controladores: así el marcado optimista, que el núcleo aplica antes de ir a
 *   la red, se ve al instante.
 * - Al terminar se publica el estado VIGENTE de los controladores, no el devuelto
 *   por la operación (puede haber otras en vuelo).
 * - Crear una lista no se reintenta nunca sola: si la respuesta es dudosa el
 *   núcleo relee la página y aquí queda un aviso fijo ([ShoppingUiState.maybeCreated]).
 * - Qué lista se abre al entrar lo decide el núcleo: la recordada por
 *   [ShoppingListPickerController.rememberOpenList] o, si no hay ninguna, la
 *   primera por nombre. La interfaz no guarda nada en disco.
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
    private var picker: ShoppingListPickerController = newPicker()
    private var draft = ""
    private var createDialog = false
    private var createProblem: ShoppingListPickerProblem? = null
    private var maybeCreated = false

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

    private fun newPicker(): ShoppingListPickerController {
        // Sin lista inicial: la primera lectura abre la primera por nombre.
        val bornEpoch = epoch
        return ShoppingListPickerController(sourceFor(bornEpoch))
    }

    /**
     * Fuente de UNA sesión ([bornEpoch]). El cliente se crea en cada llamada y
     * no se guarda; un fallo al crearlo (sin sesión, almacén sin respuesta, HTTP
     * local con datos móviles) se convierte en [ApiResult] de fallo y no puede
     * escapar de la corrutina. La llamada va fuera del `try` para no perder el
     * matiz de [NetworkError.AfterSend] al añadir o al crear.
     */
    private fun sourceFor(bornEpoch: Long): ShoppingListSource = object : ShoppingListSource {

        override fun listLists(page: Int, perPage: Int) =
            withClient(bornEpoch) { it.listLists(page, perPage) }

        override fun createList(name: String) = withClient(bornEpoch) { it.createList(name) }

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
        // Solo se envuelve la creación del cliente: la llamada va fuera para no
        // perder el matiz de AfterSend (el servidor puede haber recibido el POST).
        val client = try {
            access.newClient()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NetworkError) {
            return failureOf(error)
        } catch (error: RuntimeException) {
            // El almacén seguro sin respuesta lanza IllegalStateException. Aquí no
            // ha salido ninguna petición, así que es un fallo normal y no un
            // MaybeCreated: si no, el aviso diría que la lista pudo crearse.
            return ApiResult.Failure(ConnectionProblem.Unknown)
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
        picker = picker.state,
        createDialog = createDialog,
        createProblem = createProblem,
        maybeCreated = maybeCreated,
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

    private fun publish() {
        _state.value = snapshot()
    }

    // ------------------------------------------------------------- lectura

    /**
     * Al mostrar la pantalla: lee la página de listas si aún no la tiene y abre
     * la lista que toca. Con una página buena no se pide nada: ir a Ajustes y
     * volver deja la lista donde estaba; solo se repite si lo último fue un fallo.
     */
    fun onShown() {
        val current = picker.state
        if (current.isBusy) return
        if (!current.loadedOnce || current.problem != null) {
            launchLists { it.load() }
            return
        }
        val open = controller.state
        if (open.problem != null && !open.isLoading) retryOpenList() else openChosen()
    }

    /**
     * «Recargar» y «Volver a intentar»: relee la página de listas (por si hay
     * alguna nueva) y la lista abierta.
     */
    fun onRetry() {
        launchLists { it.refresh() }
        retryOpenList()
    }

    fun onRefresh() = onRetry()

    /** Cierra el aviso que está a la vista (un rechazo de la app, por ejemplo). */
    fun onDismissProblem() {
        val current = controller.state
        // Solo con la lista a la vista: un fallo sin nada que enseñar tiene su propia pantalla.
        if (current.loadedOnce) dismissedProblem = current.problem
        publish()
    }

    // ------------------------------------------------------------- listas

    /**
     * Elige qué lista se abre. No toca la red: la recuerda el selector y la
     * pantalla carga la que ha quedado abierta.
     */
    fun onSelectList(listId: String) {
        picker.rememberOpenList(listId)
        createProblem = null
        publish()
        openChosen()
    }

    /**
     * Abre la lista que el selector da por abierta. Si el selector no tiene ninguna
     * (la página vino vacía), se cierra la que hubiera: la pantalla no puede seguir
     * enseñando una lista que ya no está en la página.
     */
    private fun openChosen() {
        val listId = picker.state.openListId
        if (listId == null) {
            if (controller.state.listId != null) {
                controller.close()
                publish()
            }
            return
        }
        if (controller.state.listId == listId) return
        launchOperation { it.open(listId) }
    }

    /** Reintenta la lista abierta (o abre la elegida, si la pantalla no la tiene). */
    private fun retryOpenList() {
        val listId = picker.state.openListId ?: return
        val current = controller.state
        if (current.isLoading) return
        if (current.listId != listId) {
            launchOperation { it.open(listId) }
            return
        }
        launchOperation { c ->
            if (c.state.problem != null && !c.state.loadedOnce) c.retry() else c.refresh()
        }
    }

    // ------------------------------------------------------------- crear lista

    /** Abre el diálogo de «Nueva lista». */
    fun onCreateListClick() {
        // Sin la página de listas leída no se crea: no se sabe qué hay y se
        // acabaría con dos listas iguales. La pantalla ya lo desactiva; esto
        // cubre el atajo del estado vacío y cualquier llamada futura.
        val current = picker.state
        if (!current.loadedOnce || current.isBusy) return
        createProblem = null
        createDialog = true
        publish()
    }

    /** Cierra el diálogo. Con el `POST` en vuelo no hace nada. */
    fun onDismissCreateList() {
        if (picker.state.isCreating) return
        createDialog = false
        createProblem = null
        publish()
    }

    /**
     * Crea una lista con [name]. El nombre lo valida el núcleo (vacío o demasiado
     * largo queda en [ShoppingListPickerProblem.InputRejected] sin enviar nada) y
     * la lista nueva queda abierta. Con una respuesta dudosa NO se reintenta: se
     * cierra el diálogo y se enciende [ShoppingUiState.maybeCreated], porque
     * repetir crearía otra lista.
     */
    fun onCreateList(name: String) {
        val current = picker.state
        if (!current.loadedOnce || current.isBusy) return
        createProblem = null
        publish()
        launchCreate { it.create(name) }
    }

    /** Cierra el aviso de «puede que la lista se haya creado». */
    fun onDismissCreateNotice() {
        maybeCreated = false
        publish()
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
        draft = ""
        dismissedProblem = null
        createDialog = false
        createProblem = null
        maybeCreated = false
        controller = newController()
        picker = newPicker()
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
        track(isCurrent = { target === controller }, onDone = onDone) { operation(target) }
    }

    /** Relee la página de listas; al acabar abre la lista que haya quedado elegida. */
    private fun launchLists(operation: (ShoppingListPickerController) -> ShoppingListPickerState) {
        val target = picker
        track(
            isCurrent = { target === picker },
            onDone = { result ->
                // Si la relectura sale bien, el aviso de «puede que se haya creado»
                // ya sobra: la persona está viendo las listas de verdad.
                if (maybeCreated && result.loadedOnce && result.problem == null) maybeCreated = false
                openChosen()
            },
        ) { operation(target) }
    }

    /**
     * Crea la lista y ajusta el diálogo y el aviso según el desenlace. Solo la
     * creación vigente manda: si `reset()` ha cambiado de sesión, el resultado
     * se descarta y no se abre nada.
     */
    private fun launchCreate(operation: (ShoppingListPickerController) -> ShoppingListPickerState) {
        val target = picker
        track(
            isCurrent = { target === picker },
            onDone = { result ->
                val problem = result.problem
                when {
                    problem == null -> {
                        // Lista creada y abierta por el núcleo: se carga la nueva.
                        createDialog = false
                        createProblem = null
                        maybeCreated = false
                        openChosen()
                    }
                    // Otra operación del selector ha tomado el relevo: manda su estado.
                    result.isBusy -> Unit
                    problem is ShoppingListPickerProblem.MaybeCreated -> {
                        // Pudo crearse: aviso fijo y que la persona mire antes de repetir.
                        createDialog = false
                        createProblem = null
                        maybeCreated = true
                    }
                    // Sesión caducada: se cierra el diálogo y el aviso de la pantalla
                    // lleva a Ajustes, como en el resto de la app.
                    problem == ShoppingListPickerProblem.Unauthorized -> {
                        createDialog = false
                        createProblem = null
                    }
                    else -> createProblem = problem
                }
            },
        ) { operation(target) }
    }

    /**
     * Lanza [work] en un hilo de fondo y publica el estado. [isCurrent] evita que
     * una operación de una sesión ya reiniciada toque el estado o el diálogo.
     */
    private fun <S> track(
        isCurrent: () -> Boolean,
        onDone: (S) -> Unit,
        work: () -> S,
    ) {
        viewModelScope.launch {
            // El propio trabajo, para poder cancelarlo en reset().
            val self = coroutineContext[Job]
            if (self != null) running.add(self)
            inFlight++
            if (poller == null) {
                poller = viewModelScope.launch {
                    while (true) {
                        delay(POLL_MILLIS)
                        publish()
                    }
                }
            }
            var result: S? = null
            try {
                // El núcleo devuelve fallos tipados y su fuente no deja escapar la
                // creación del cliente; esto es una red de seguridad para que una
                // excepción rara no tumbe la app ni deje la fila «guardando».
                result = try {
                    withContext(ioDispatcher) { work() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unexpected: Exception) {
                    null
                }
            } finally {
                if (self != null) running.remove(self)
                if (isCurrent()) {
                    inFlight--
                    if (inFlight <= 0) {
                        inFlight = 0
                        poller?.cancel()
                        poller = null
                    }
                    val done = result
                    if (done != null) onDone(done)
                }
                publish()
            }
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
