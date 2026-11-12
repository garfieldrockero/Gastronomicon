package io.github.garfieldrockero.gastronomicon.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineCopyDate
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineRead
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.core.offline.allowsOfflineCopy
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailController
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailState
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailView
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAddition
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAdditionProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAdditions
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingIds
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerController
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerState
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import io.github.garfieldrockero.gastronomicon.platform.OfflineRecipes
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShopping
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingOutcome
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingUi
import io.github.garfieldrockero.gastronomicon.ui.components.guardedBy
import io.github.garfieldrockero.gastronomicon.ui.components.servingsInputText
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado del borrado de la receta abierta. La interfaz solo lo pinta. */
data class RecipeDeleteUiState(
    val confirming: Boolean = false,
    val deleting: Boolean = false,
    val problem: ConnectionProblem? = null,
    val rejected: Boolean = false,
    val deleted: Boolean = false,
)

/** Lo que se confirma con un aviso de un solo uso al volver a la lista. */
enum class RecipeNotice {
    /** La receta abierta se borró: la lista se recarga y su fila ya no está. */
    Deleted,
}

/**
 * Aviso de un solo uso de la receta que se acaba de cerrar. Vive **fuera** de
 * [RecipeDeleteUiState] a propósito: `close()` vacía el borrado (el detalle ya no
 * existe) y el aviso tiene que llegar igualmente a la lista, que es donde se pinta.
 *
 * [noticeId] es un contador que **cambia** con cada aviso; la pantalla lo usa como
 * clave para no perderlo ni repetirlo al girar el móvil ni al recomponer. Un booleano
 * (`deleted = true`) volvería a dispararse en cada giro, y como aviso se oiría
 * repetido. `onNoticeShown(id)` **consume** el aviso: deja [notice] en `null` y mantiene
 * el id.
 */
data class RecipeNoticeUi(
    val notice: RecipeNotice? = null,
    val noticeId: Long = 0L,
)

/**
 * Una copia guardada de la receta, mientras no hay conexión: la receta tal como
 * se guardó (con las raciones que se elijan ahora), la fecha de la copia ya en
 * texto (la compone el núcleo, ver `OfflineCopyDate`) y el motivo real del fallo
 * cuando no es simple falta de red (certificado, HTTP local fuera de casa).
 *
 * [retrying] = se está intentando volver a conectar: la copia **sigue** en
 * pantalla (no se borra hasta que llegue la receta del servidor) y así no se
 * pierden las raciones ajustadas.
 *
 * Con esto en pantalla no se edita ni se borra: lo que se ve no está en el
 * servidor, y guardar cambios encima exigiría conexión.
 */
data class OfflineCopyUi(
    val view: RecipeDetailView,
    val cachedAtText: String,
    val problem: ConnectionProblem? = null,
    val retrying: Boolean = false,
) {
    // `data class` imprimiría el nombre y los pasos: son datos de la persona.
    override fun toString(): String = "OfflineCopyUi(cachedAtText=$cachedAtText, retrying=$retrying)"
}

/**
 * Pasarela entre el detalle y el [RecipeDetailController] del núcleo: no decide
 * nada. Lanza la carga (bloqueante) en un hilo de fondo con un cliente nuevo y
 * publica el estado del controlador, que es quien sabe si carga, si ha fallado
 * y cómo, y quien recalcula las raciones.
 *
 * - [RecipeDetailState.Closed] = no hay detalle abierto: se ve la lista.
 * - Al terminar una carga se publica el estado VIGENTE del controlador, no el
 *   devuelto por ella: una respuesta superada por otra llamada se descarta en el núcleo.
 * - Sin token, sin URL del servidor ni mensajes de excepción en el estado.
 *
 * Fase 8 (sin conexión):
 * - Al cargar bien una receta, se guarda una copia en [offline] en segundo plano,
 *   sin bloquear la pantalla, sin reintentos y sin avisar si falla.
 * - Si la carga falla por red (no por 401/403/404, ver `allowsOfflineCopy`), se
 *   ofrece la copia guardada en [offlineCopy]. Las raciones se siguen pudiendo
 *   escalar (es cálculo local) y la copia no guarda fotos.
 * - La [epoch] sube al reiniciar el ViewModel: un guardado que llegue tarde, de
 *   la sesión anterior, se descarta por el camino.
 *
 * Fase 9, extra 1: [shopping] lleva el diálogo de «añadir a la lista de la
 * compra» y su aviso. Qué recetas entran, cómo se escala y si se puede enviar
 * lo decide `core/shopping` (`RecipeAdditions`, `ShoppingListPickerController`);
 * aquí solo se lanzan en segundo plano y se publica el desenlace, sin
 * reintentos. Con la copia sin conexión no se ofrece: es una escritura y hace
 * falta el servidor.
 *
 * Desde el detalle se piden **raciones** (las que se hayan escalado, si la
 * receta dice para cuántas es): la conversión a escala la hace
 * `RecipeAdditions.scaleForServings`, no esta clase. El aviso de después
 * (también el «puede que ya se hayan añadido») **no se pierde** al cerrar la
 * receta, al abrir otra ni al volver a abrir el diálogo: solo lo cierra la
 * persona, porque es la única prueba de un posible duplicado.
 */
class RecipeDetailViewModel(
    private val access: ClientAccess,
    private val offline: OfflineRecipes? = null,
    /**
     * Transporte de la compra para «añadir a la lista». Sin él (null) la acción
     * ni se ofrece: no hay de dónde sacar la sesión para el `POST`.
     */
    private val shoppingAccess: ShoppingAccess? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    // Cada petición crea su cliente con ClientAccess (nunca se guarda uno).
    private val controller = RecipeDetailController(RecipeDetailSource { slug -> loadRecipe(slug) })

    private val _state = MutableStateFlow<RecipeDetailState>(controller.state)
    val state: StateFlow<RecipeDetailState> = _state.asStateFlow()

    private val _deleteState = MutableStateFlow(RecipeDeleteUiState())
    val deleteState: StateFlow<RecipeDeleteUiState> = _deleteState.asStateFlow()

    private val _notice = MutableStateFlow(RecipeNoticeUi())

    /**
     * Aviso de un solo uso que se pinta al volver a la lista (ver [RecipeNoticeUi]).
     * Hoy solo hay uno: «Receta borrada». Lo consume la lista cuando termina de verse.
     */
    val notice: StateFlow<RecipeNoticeUi> = _notice.asStateFlow()

    /** Contador de avisos: cambia con cada uno; su valor concreto da igual. */
    private var noticeSeq = 0L

    private val _offlineCopy = MutableStateFlow<OfflineCopyUi?>(null)

    /** La copia guardada que se está viendo, o null si lo que se ve viene del servidor. */
    val offlineCopy: StateFlow<OfflineCopyUi?> = _offlineCopy.asStateFlow()

    private var job: Job? = null
    private var deleteJob: Job? = null

    /**
     * Receta abierta (para poder reintentar y buscar su copia sin depender del
     * estado). Se lee también desde el hilo de fondo al cargar, para no dejar el
     * identificador de una receta que ya no está a la vista.
     */
    @Volatile
    private var openSlug: String? = null

    // ------------------------------------------------- compra (fase 9, extra 1)

    private val _shopping = MutableStateFlow(AddToShoppingUi(available = shoppingAccess != null))

    /**
     * «Añadir a la lista de la compra»: el diálogo, el envío y el aviso de
     * después. [AddToShoppingUi.available] dice si esta pantalla puede hacerlo.
     */
    val shopping: StateFlow<AddToShoppingUi> = _shopping.asStateFlow()

    /** Lectura de las listas del diálogo. */
    private var shoppingJob: Job? = null

    /** Envío en curso del diálogo (no se corta al abrir o cerrar otra receta). */
    private var shoppingSendJob: Job? = null

    /** Selector de listas de esta apertura del diálogo (lo lleva el núcleo). */
    private var shoppingPicker: ShoppingListPickerController? = null

    /**
     * Raciones de la receta abierta, tal como las dio el núcleo; null si la
     * receta no dice para cuántas es. Se fija al abrir el diálogo y es lo que
     * convierte las raciones escritas en escala (`RecipeAdditions.scaleForServings`).
     */
    private var shoppingServings: Double? = null

    /**
     * Identificador del envío que corresponde al diálogo que se está viendo. Si
     * mientras el `POST` viaja se cierra el diálogo o se abre otra receta, el
     * desenlace ya no es suyo: solo publica el aviso, sin tocar lo que la
     * persona tenga delante. Se toca solo desde el hilo principal.
     */
    private var shoppingSend = 0L
    private var shoppingSendSeq = 0L

    /**
     * UUID de la receta abierta, para pedir sus ingredientes a la compra. Se lee
     * en el hilo de fondo al cargar y se consulta en el principal al confirmar.
     */
    @Volatile
    private var openRecipeId: String? = null

    /** Sube al reiniciar (otra sesión): lo que traiga una carga vieja ya no vale. */
    @Volatile
    private var epoch = 0L

    fun open(slug: String) {
        job?.cancel()
        deleteJob?.cancel()
        deleteJob = null
        // Cerrar o cambiar de receta no borra el aviso de un envío anterior, ni
        // corta el `POST` que esté en vuelo: es la única prueba de que puede
        // haber ingredientes añadidos.
        resetShopping(keepNotice = true)
        openSlug = slug
        _deleteState.value = RecipeDeleteUiState()
        _offlineCopy.value = null
        _state.value = RecipeDetailState.Loading
        val bornEpoch = epoch
        job = viewModelScope.launch {
            withContext(ioDispatcher) {
                controller.open(slug)
                if (bornEpoch == epoch) offerOfflineCopy(slug)
            }
            if (bornEpoch != epoch) return@launch
            _state.value = controller.state
        }
    }

    /** «Volver a intentar» tras un fallo (o tras un 401 ya arreglado en Ajustes). */
    fun retry() {
        // Con la copia en pantalla no se borra ni se pasa a «cargando»: lo que se
        // cocina sigue a la vista (con sus raciones) hasta que llegue la receta del
        // servidor. Solo se marca que se está reintentando.
        if (_offlineCopy.value != null) {
            retryOfflineCopy()
            return
        }
        val current = _state.value
        if (current is RecipeDetailState.Ready || current == RecipeDetailState.Loading || current == RecipeDetailState.Closed) return
        job?.cancel()
        _offlineCopy.value = null
        _state.value = RecipeDetailState.Loading
        val bornEpoch = epoch
        val slug = openSlug
        job = viewModelScope.launch {
            withContext(ioDispatcher) {
                controller.retry()
                if (bornEpoch == epoch) offerOfflineCopy(slug)
            }
            if (bornEpoch != epoch) return@launch
            _state.value = controller.state
        }
    }

    /**
     * Reintento con la copia ya a la vista: no se toca el estado del controlador
     * hasta saber el resultado. Si la receta llega, [offerOfflineCopy] quita la
     * copia; si vuelve a fallar por red, se queda con las raciones que la persona
     * hubiera ajustado.
     */
    private fun retryOfflineCopy() {
        val copy = _offlineCopy.value ?: return
        if (copy.retrying) return
        if (copy.view.slug != openSlug) return
        job?.cancel()
        _offlineCopy.value = copy.copy(retrying = true)
        val bornEpoch = epoch
        val slug = openSlug
        job = viewModelScope.launch {
            withContext(ioDispatcher) {
                controller.retry()
                if (bornEpoch == epoch) offerOfflineCopy(slug)
            }
            if (bornEpoch != epoch) return@launch
            _state.value = controller.state
            // Si el fallo nuevo no permite copia, `offerOfflineCopy` la ha quitado y
            // esto no hace nada; si la deja, se apaga el «reintentando».
            _offlineCopy.update { current -> current?.copy(retrying = false) }
        }
    }

    /** Vuelve a la lista; el núcleo descarta lo que estuviera cargando. */
    fun close() {
        job?.cancel()
        job = null
        deleteJob?.cancel()
        deleteJob = null
        // Igual que al abrir otra receta: el aviso y el envío en vuelo se quedan.
        resetShopping(keepNotice = true)
        openSlug = null
        _deleteState.value = RecipeDeleteUiState()
        _offlineCopy.value = null
        _state.value = controller.close()
    }

    /** Al cambiar de sesión: nada de lo cargado vale, ni la copia ni el ámbito. */
    fun reset() {
        epoch++
        job?.cancel()
        job = null
        deleteJob?.cancel()
        deleteJob = null
        resetShopping(keepNotice = false)
        openSlug = null
        _deleteState.value = RecipeDeleteUiState()
        // Otra sesión: la lista se reinicia también, así que el aviso no tiene a quién contarse.
        _notice.value = RecipeNoticeUi()
        _offlineCopy.value = null
        _state.value = controller.close()
    }

    /** Cierra el diálogo de la compra y olvida la receta y el selector: otra sesión u otra receta. */
    private fun resetShopping(keepNotice: Boolean) {
        shoppingJob?.cancel()
        shoppingJob = null
        // Un envío en vuelo no se corta al abrir o cerrar la receta: el `POST`
        // que ya salió puede haber añadido los ingredientes y su aviso («puede
        // que ya se hayan añadido») tiene que llegar hasta que la persona lo
        // cierre. Al cambiar de sesión sí se corta: esa respuesta ya no es suya.
        if (!keepNotice) {
            shoppingSendJob?.cancel()
            shoppingSendJob = null
        }
        shoppingSend = 0
        shoppingPicker = null
        openRecipeId = null
        shoppingServings = null
        _shopping.value = AddToShoppingUi(
            available = shoppingAccess != null,
            notice = if (keepNotice) _shopping.value.notice else null,
        )
    }

    // Instantáneas (no tocan la red): el núcleo acota y recalcula las raciones.
    fun onMoreServings() {
        val copy = _offlineCopy.value
        if (copy != null) {
            // La copia se escala igual que una receta del servidor: es cálculo local.
            _offlineCopy.value = copy.copy(view = copy.view.moreServings())
            return
        }
        _state.value = controller.moreServings()
    }

    fun onFewerServings() {
        val copy = _offlineCopy.value
        if (copy != null) {
            _offlineCopy.value = copy.copy(view = copy.view.fewerServings())
            return
        }
        _state.value = controller.fewerServings()
    }

    // ------------------------------------------------------------- borrar

    fun onDeleteClick() {
        // Con la copia en pantalla no hay nada que borrar en el servidor.
        if (_offlineCopy.value != null) return
        if (_state.value !is RecipeDetailState.Ready) return
        val current = _deleteState.value
        if (current.deleting) return
        _deleteState.value = current.copy(confirming = true, problem = null, rejected = false)
    }

    fun onDeleteDismiss() {
        if (_deleteState.value.deleting) return
        _deleteState.value = RecipeDeleteUiState()
    }

    fun onDeleteConfirm() {
        if (_offlineCopy.value != null) return
        val detail = _state.value as? RecipeDetailState.Ready ?: return
        val current = _deleteState.value
        if (current.deleting) return
        _deleteState.value = current.copy(
            confirming = true,
            deleting = true,
            problem = null,
            rejected = false,
        )
        val slug = detail.view.slug
        deleteJob?.cancel()
        deleteJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) { delete(slug) }
            when (result) {
                DeleteResult.Deleted -> {
                    _state.value = controller.close()
                    _deleteState.value = RecipeDeleteUiState(deleted = true)
                    // Hasta ahora la receta desaparecía de la lista sin decir nada. El aviso
                    // se pinta en la lista (el detalle se acaba de cerrar) y solo lo cierra
                    // su propia pantalla: aquí no se espera a nadie.
                    _notice.value = RecipeNoticeUi(
                        notice = RecipeNotice.Deleted,
                        noticeId = ++noticeSeq,
                    )
                }
                is DeleteResult.Problem -> {
                    val problem = result.problem
                    _deleteState.update { latest ->
                        if (latest.confirming) {
                            latest.copy(deleting = false, problem = problem)
                        } else {
                            latest
                        }
                    }
                }
                DeleteResult.Rejected -> _deleteState.update { latest ->
                    if (latest.confirming) {
                        latest.copy(deleting = false, rejected = true)
                    } else {
                        latest
                    }
                }
            }
        }
    }

    /**
     * Da por visto el aviso de la lista al terminar de verse (`GastronomiconSnackbarHost`).
     * Se deja `notice` en `null` y se **mantiene** el id: si el contador subiera aquí, el
     * aviso se reproduciría en bucle.
     *
     * Se ignoran los ids que no sean el del aviso vigente: la llamada puede llegar tarde,
     * cuando ya hay otro aviso esperando, y comérselo dejaría sin confirmar una receta que
     * sí se borró.
     */
    fun onNoticeShown(id: Long) {
        _notice.update { current ->
            if (current.noticeId == id) current.copy(notice = null) else current
        }
    }

    private fun delete(slug: String): DeleteResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return DeleteResult.Problem(e.toConnectionProblem())
        } catch (e: Exception) {
            return DeleteResult.Problem(ConnectionProblem.Unknown)
        }
        val result = try {
            client.deleteRecipe(slug)
        } catch (e: IllegalArgumentException) {
            return DeleteResult.Rejected
        }
        return when (result) {
            is ApiResult.Success -> DeleteResult.Deleted
            ApiResult.NotFound -> DeleteResult.Deleted
            ApiResult.Unauthorized -> DeleteResult.Problem(ConnectionProblem.Unauthorized)
            ApiResult.Forbidden, ApiResult.Conflict,
            is ApiResult.Invalid, is ApiResult.Rejected, is ApiResult.InputRejected,
            is ApiResult.DraftInvalid, is ApiResult.SavedButNotReloaded -> DeleteResult.Rejected
            // `deleteRecipe` no lo devuelve (no crea nada). Si llegara, es un fallo que se
            // puede reintentar sin duplicar: borrar dos veces la misma receta no hace daño.
            is ApiResult.MaybeCreated -> DeleteResult.Problem(result.problem)
            is ApiResult.Failure -> DeleteResult.Problem(result.problem)
        }
    }

    // ------------------------------------------- añadir a la compra (fase 9)

    /**
     * Abre el diálogo de «Añadir a la lista de la compra» y lee las listas.
     * Con la copia sin conexión no se abre: añadir es una escritura y hace falta
     * el servidor. Sin UUID de la receta el diálogo se abre explicándolo: es
     * mejor que esconder el botón y no decir por qué.
     *
     * El campo arranca con las raciones que estén elegidas en el detalle. Si la
     * receta no dice para cuántas es, se abre como «veces la receta» con 1.
     */
    fun onAddToShoppingClick() {
        val store = shoppingAccess ?: return
        if (_offlineCopy.value != null) return
        val view = (_state.value as? RecipeDetailState.Ready)?.view ?: return
        if (_shopping.value.open) return
        val recipeId = openRecipeId
        val servings = view.originalServings
        shoppingServings = servings
        _shopping.value = AddToShoppingUi(
            available = true,
            open = true,
            usesServings = servings != null,
            scaleText = if (servings != null) {
                servingsInputText(view.selectedServings ?: servings)
            } else {
                TIMES_DEFAULT_TEXT
            },
            // Un aviso anterior que la persona no ha cerrado no se pierde por abrir otra vez.
            notice = _shopping.value.notice,
            picker = if (recipeId == null) {
                ShoppingListPickerState()
            } else {
                // Se ve «Buscando tus listas…» hasta que el núcleo conteste.
                ShoppingListPickerState(phase = ShoppingListPickerPhase.Loading)
            },
            problem = if (recipeId == null) {
                AddToShoppingOutcome.Invalid(RecipeAdditionProblem.InvalidRecipeId)
            } else {
                null
            },
        )
        if (recipeId != null) loadShoppingLists(store)
    }

    /** Elige la lista a la que irán los ingredientes. No toca la red. */
    fun onAddToShoppingListSelected(listId: String) {
        if (!_shopping.value.open) return
        val picker = shoppingPicker ?: return
        val state = picker.rememberOpenList(listId)
        _shopping.update { it.copy(picker = state, problem = null) }
    }

    /** Raciones escritas; el núcleo decide si el número vale al enviar. */
    fun onAddToShoppingScaleChange(text: String) {
        if (!_shopping.value.open) return
        _shopping.update { it.copy(scaleText = text.take(MAX_SCALE_INPUT), problem = null) }
    }

    /** «Volver a mirar» de las listas: las vuelve a leer. */
    fun onAddToShoppingRetryLists() {
        val store = shoppingAccess ?: return
        if (!_shopping.value.open || _shopping.value.sending) return
        _shopping.update { it.copy(problem = null) }
        loadShoppingLists(store)
    }

    /** Cierra el diálogo; con el `POST` en vuelo no hace nada. */
    fun onAddToShoppingDismiss() {
        if (_shopping.value.sending) return
        _shopping.update { it.copy(open = false, problem = null, sending = false) }
    }

    /** Cierra el aviso de después de añadir. */
    fun onAddToShoppingNoticeDismiss() {
        _shopping.update { it.copy(notice = null) }
    }

    /**
     * Confirma el diálogo. La tanda se valida con el núcleo antes de enviar
     * (`RecipeAdditions.check`): si no vale, no sale nada y el motivo se queda
     * en el diálogo. Nunca se reintenta solo: un desenlace dudoso es un aviso
     * fijo para mirar la lista antes de repetir.
     *
     * El número que escribe la persona son **raciones** (si la receta dice para
     * cuántas es) o **veces la receta**. La conversión a escala la hace el
     * núcleo (`RecipeAdditions.scaleForServings`); aquí no se divide.
     */
    fun onAddToShoppingConfirm() {
        val store = shoppingAccess ?: return
        val current = _shopping.value
        if (!current.open || current.sending) return
        val listId = current.picker.openListId ?: return

        val recipeId = openRecipeId
        if (recipeId == null) {
            _shopping.update {
                it.copy(problem = AddToShoppingOutcome.Invalid(RecipeAdditionProblem.InvalidRecipeId))
            }
            return
        }
        val written = numberOrNull(current.scaleText)
        val scale = if (current.usesServings) {
            RecipeAdditions.scaleForServings(shoppingServings, written)
        } else {
            written?.takeIf { RecipeAdditions.validScale(it) }
        }
        if (scale == null) {
            _shopping.update {
                it.copy(problem = AddToShoppingOutcome.Invalid(RecipeAdditionProblem.InvalidScale))
            }
            return
        }
        val additions = listOf(RecipeAddition(recipeId, scale))
        val invalid = RecipeAdditions.check(additions)
        if (invalid != null) {
            _shopping.update { it.copy(problem = AddToShoppingOutcome.Invalid(invalid)) }
            return
        }

        val listName = current.picker.openList?.displayName.orEmpty()
        val send = ++shoppingSendSeq
        shoppingSend = send
        _shopping.update { it.copy(sending = true, problem = null) }
        val bornEpoch = epoch
        // La sesión se lee solo si sigue siendo la de este envío: tras `reset()`
        // no se abre conexión ni se usa el token nuevos.
        val session = store.guardedBy { bornEpoch == epoch }
        shoppingSendJob = viewModelScope.launch {
            val outcome = withContext(ioDispatcher) {
                AddToShopping.add(session, listId, additions, listName)
            }
            if (bornEpoch != epoch) return@launch
            val mine = shoppingSend == send
            _shopping.update { latest ->
                when (outcome) {
                    is AddToShoppingOutcome.Added, is AddToShoppingOutcome.MaybeAdded -> if (mine) {
                        latest.copy(open = false, sending = false, problem = null, notice = outcome)
                    } else {
                        // El diálogo de ese envío ya no está (se cerró o se abrió
                        // otra receta): solo se publica el aviso, y se queda hasta
                        // que la persona lo cierre. Es la prueba de un posible
                        // duplicado: no se puede perder.
                        latest.copy(notice = outcome)
                    }
                    else -> if (mine) latest.copy(sending = false, problem = outcome) else latest
                }
            }
            if (mine) shoppingSend = 0L
        }
    }

    /** Lee la página de listas en segundo plano; el selector es del núcleo. */
    private fun loadShoppingLists(store: ShoppingAccess) {
        val bornEpoch = epoch
        val picker = AddToShopping.newPicker(store.guardedBy { bornEpoch == epoch })
        shoppingPicker = picker
        shoppingJob?.cancel()
        shoppingJob = viewModelScope.launch {
            val loaded = withContext(ioDispatcher) { picker.load() }
            if (bornEpoch != epoch) return@launch
            _shopping.update { if (it.open) it.copy(picker = loaded) else it }
        }
    }

    /**
     * El número escrito, como raciones o como veces la receta. Coma o punto,
     * como en el planificador. Null si no hay número (campo vacío o texto que
     * no lo es): el núcleo decide entonces qué decir, aquí no se adivina un 1.
     */
    private fun numberOrNull(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.replace(',', '.').toDoubleOrNull()
    }

    // ------------------------------------------------- sin conexión (fase 8)

    /** Carga la receta y, si sale bien, deja una copia para verla sin conexión. */
    private fun loadRecipe(slug: String): Recipe {
        val bornEpoch = epoch
        val recipe = clientOrFail(access).getRecipe(slug)
        // El UUID solo sirve para la compra; el núcleo comprueba que tiene forma de
        // UUID. Se apunta solo si esta carga sigue siendo la receta abierta: una
        // carga vieja que termina tarde no deja su id mientras se ve otra receta.
        if (bornEpoch == epoch && slug == openSlug) openRecipeId = ShoppingIds.valid(recipe.id)
        rememberForOffline(bornEpoch, recipe)
        return recipe
    }

    /**
     * Guarda la copia en su propio trabajo, fuera del hilo principal y sin
     * bloquear la pantalla. Sin reintentos y sin avisar: si no se puede guardar,
     * la receta se ve igual y la próxima vez se vuelve a intentar solo.
     */
    private fun rememberForOffline(bornEpoch: Long, recipe: Recipe) {
        val store = offline ?: return
        viewModelScope.launch(ioDispatcher) {
            if (bornEpoch != epoch) return@launch
            val scope = store.scope() ?: return@launch
            try {
                store.saveRecipe(scope, recipe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un fallo al guardar la copia no cambia nada de lo que se está viendo.
            }
        }
    }

    /**
     * Tras un fallo de red, ofrece la copia guardada si la hay. Con cualquier
     * otro fallo (401/403/404, servidor que contesta mal, configuración) no se
     * ofrece nada: lo que hay que arreglar es otra cosa.
     *
     * Se llama en el hilo de fondo, antes de publicar el estado.
     */
    private fun offerOfflineCopy(slug: String?) {
        val failure = controller.state as? RecipeDetailState.Failed
        if (slug == null || failure == null || !failure.problem.allowsOfflineCopy()) {
            _offlineCopy.value = null
            return
        }
        val store = offline ?: return
        val scope: ServerScope = store.scope() ?: return
        val cached = when (val hit = store.loadRecipe(scope, slug)) {
            is OfflineRead.Hit -> hit.value
            is OfflineRead.Miss, is OfflineRead.Failed -> {
                _offlineCopy.value = null
                return
            }
        }
        // Si ya se estaba viendo la copia de esta receta, se conserva tal cual (con sus
        // raciones ajustadas) y solo se actualiza la fecha y el motivo.
        val existing = _offlineCopy.value
        val view = if (existing != null && existing.view.slug == cached.recipe.slug) {
            existing.view
        } else {
            RecipeDetailView.from(cached.recipe)
        }
        _offlineCopy.value = OfflineCopyUi(
            view = view,
            cachedAtText = OfflineCopyDate.format(cached.cachedAtMillis),
            problem = failure.problem,
        )
    }

    private sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data class Problem(val problem: ConnectionProblem) : DeleteResult
        data object Rejected : DeleteResult
    }

    companion object {
        /** Tope del texto de raciones: el número útil no llega ni a cinco caracteres. */
        private const val MAX_SCALE_INPUT = 10

        /** Sin raciones en la receta, el campo cuenta «veces la receta» y empieza en 1. */
        private const val TIMES_DEFAULT_TEXT = "1"

        /**
         * El núcleo lee un `IllegalArgumentException` como «slug no válido» (= la receta no
         * existe). Pero el que sale de crear el cliente (HTTP local apagado, red no permitida)
         * no tiene nada que ver con la receta: se relanza como otro tipo para que se vea como
         * un fallo de conexión, igual que en la lista, y no como «Esta receta ya no está».
         */
        private fun clientOrFail(access: ClientAccess) = try {
            access.newClient()
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("No se puede crear el cliente con la configuración actual.")
        }

        /**
         * @param shoppingAccess transporte de la compra para «añadir a la lista».
         *   Sin él la acción no se ofrece: quien monte el ViewModel tiene que
         *   pasar el `ShoppingAccess` de la app (en `MainActivity`,
         *   `container.shoppingAccess`). Está pendiente de cablear porque el
         *   alcance de esta tarea no incluía la actividad.
         */
        fun factory(
            access: ClientAccess,
            offline: OfflineRecipes? = null,
            shoppingAccess: ShoppingAccess? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeDetailViewModel(access, offline, shoppingAccess) as T
            }
    }
}
