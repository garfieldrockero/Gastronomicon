package io.github.garfieldrockero.gastronomicon.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
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

/**
 * Estado de «Importar receta». La descarga la hace el servidor Mealie; la app
 * solo manda la URL. [maybeCreated] es el aviso importante: el núcleo no sabe
 * si la receta se creó (plazo agotado, corte o un 5xx después de enviar), así
 * que no se reintenta sola.
 */
data class RecipeImportUiState(
    val visible: Boolean = false,
    val url: String = "",
    val running: Boolean = false,
    val invalidUrl: Boolean = false,
    val problem: ConnectionProblem? = null,
    val rejected: Boolean = false,
    val details: List<ValidationDetail> = emptyList(),
    val maybeCreated: Boolean = false,
    val cancelled: Boolean = false,
    val importedSlug: String? = null,
)

/**
 * Importar una receta por URL. Cada operación crea su cliente con
 * [ClientAccess] y corre en un hilo de fondo. El plazo largo lo pone el núcleo
 * (hasta dos minutos); aquí solo se puede dejar de esperar.
 *
 * Cancelar no aborta la petición HTTP que ya está en el servidor: por eso, al
 * cancelar, se avisa de que la receta puede acabar de crearse igualmente.
 */
class RecipeImportViewModel(
    private val access: ClientAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipeImportUiState())
    val state: StateFlow<RecipeImportUiState> = _state.asStateFlow()

    private var generation = 0
    private var job: Job? = null

    fun open() {
        generation++
        job?.cancel()
        job = null
        _state.value = RecipeImportUiState(visible = true)
    }

    fun onUrlChange(value: String) {
        _state.update { current ->
            if (current.running) {
                current
            } else {
                current.copy(
                    url = value,
                    invalidUrl = false,
                    problem = null,
                    rejected = false,
                    details = emptyList(),
                    maybeCreated = false,
                    cancelled = false,
                )
            }
        }
    }

    fun onImport() {
        val current = _state.value
        if (!current.visible || current.running || current.importedSlug != null) return
        _state.value = current.copy(
            running = true,
            invalidUrl = false,
            problem = null,
            rejected = false,
            details = emptyList(),
            maybeCreated = false,
            cancelled = false,
        )
        val url = current.url
        val gen = generation
        job = viewModelScope.launch {
            val result = withContext(ioDispatcher) { importRecipe(url) }
            if (gen != generation) return@launch
            val latest = _state.value
            if (!latest.visible) return@launch
            _state.value = when (result) {
                is ImportResult.Success -> latest.copy(running = false, importedSlug = result.slug)
                ImportResult.InvalidUrl -> latest.copy(running = false, invalidUrl = true)
                ImportResult.Unauthorized ->
                    latest.copy(running = false, problem = ConnectionProblem.Unauthorized)
                is ImportResult.Rejected ->
                    latest.copy(running = false, rejected = true, details = result.details)
                is ImportResult.MaybeCreated ->
                    latest.copy(running = false, maybeCreated = true, problem = result.problem)
                is ImportResult.Problem ->
                    latest.copy(running = false, problem = result.problem)
            }
        }
    }

    /** Deja de esperar; la petición del servidor puede seguir su curso. */
    fun onCancel() {
        val current = _state.value
        if (!current.running) return
        generation++
        job?.cancel()
        job = null
        _state.value = current.copy(
            running = false,
            cancelled = true,
            problem = null,
            rejected = false,
            details = emptyList(),
            maybeCreated = false,
        )
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        _state.value = RecipeImportUiState()
    }

    /** Al cambiar de sesión: nada de lo cargado vale. */
    fun reset() = close()

    private fun importRecipe(url: String): ImportResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return ImportResult.Problem(e.toConnectionProblem())
        } catch (e: Exception) {
            return ImportResult.Problem(ConnectionProblem.Unknown)
        }
        return when (val result = client.importFromUrl(url)) {
            is ApiResult.Success -> ImportResult.Success(result.value)
            is ApiResult.InputRejected -> ImportResult.InvalidUrl
            ApiResult.Unauthorized -> ImportResult.Unauthorized
            is ApiResult.Invalid -> ImportResult.Rejected(result.details)
            ApiResult.Forbidden, ApiResult.Conflict, ApiResult.NotFound,
            is ApiResult.Rejected, is ApiResult.DraftInvalid, is ApiResult.SavedButNotReloaded ->
                ImportResult.Rejected(emptyList())
            // El núcleo ya sabe cuándo un fallo prueba que NO se creó y cuándo no:
            // solo avisa de «quizá creada» cuando la petición pudo llegar a salir.
            is ApiResult.MaybeCreated -> ImportResult.MaybeCreated(result.problem)
            is ApiResult.Failure -> ImportResult.Problem(result.problem)
        }
    }

    private sealed interface ImportResult {
        data class Success(val slug: String) : ImportResult
        data object InvalidUrl : ImportResult
        data object Unauthorized : ImportResult
        data class Rejected(val details: List<ValidationDetail>) : ImportResult
        data class MaybeCreated(val problem: ConnectionProblem) : ImportResult
        data class Problem(val problem: ConnectionProblem) : ImportResult
    }

    companion object {
        fun factory(access: ClientAccess): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeImportViewModel(access) as T
            }
    }
}
