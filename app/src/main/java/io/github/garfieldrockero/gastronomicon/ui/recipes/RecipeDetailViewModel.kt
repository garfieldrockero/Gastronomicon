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
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import io.github.garfieldrockero.gastronomicon.platform.OfflineRecipes
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
 */
class RecipeDetailViewModel(
    private val access: ClientAccess,
    private val offline: OfflineRecipes? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    // Cada petición crea su cliente con ClientAccess (nunca se guarda uno).
    private val controller = RecipeDetailController(RecipeDetailSource { slug -> loadRecipe(slug) })

    private val _state = MutableStateFlow<RecipeDetailState>(controller.state)
    val state: StateFlow<RecipeDetailState> = _state.asStateFlow()

    private val _deleteState = MutableStateFlow(RecipeDeleteUiState())
    val deleteState: StateFlow<RecipeDeleteUiState> = _deleteState.asStateFlow()

    private val _offlineCopy = MutableStateFlow<OfflineCopyUi?>(null)

    /** La copia guardada que se está viendo, o null si lo que se ve viene del servidor. */
    val offlineCopy: StateFlow<OfflineCopyUi?> = _offlineCopy.asStateFlow()

    private var job: Job? = null
    private var deleteJob: Job? = null

    /** Receta abierta (para poder reintentar y buscar su copia sin depender del estado). */
    private var openSlug: String? = null

    /** Sube al reiniciar (otra sesión): lo que traiga una carga vieja ya no vale. */
    @Volatile
    private var epoch = 0L

    fun open(slug: String) {
        job?.cancel()
        deleteJob?.cancel()
        deleteJob = null
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
        openSlug = null
        _deleteState.value = RecipeDeleteUiState()
        _offlineCopy.value = null
        _state.value = controller.close()
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

    // ------------------------------------------------- sin conexión (fase 8)

    /** Carga la receta y, si sale bien, deja una copia para verla sin conexión. */
    private fun loadRecipe(slug: String): Recipe {
        val bornEpoch = epoch
        val recipe = clientOrFail(access).getRecipe(slug)
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

        fun factory(access: ClientAccess, offline: OfflineRecipes? = null): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeDetailViewModel(access, offline) as T
            }
    }
}
