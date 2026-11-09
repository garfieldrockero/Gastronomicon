package io.github.garfieldrockero.gastronomicon.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailController
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailState
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 */
class RecipeDetailViewModel(
    access: ClientAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    // Cada petición crea su cliente con ClientAccess (nunca se guarda uno).
    private val controller = RecipeDetailController(
        RecipeDetailSource { slug -> clientOrFail(access).getRecipe(slug) },
    )

    private val _state = MutableStateFlow<RecipeDetailState>(controller.state)
    val state: StateFlow<RecipeDetailState> = _state.asStateFlow()

    private var job: Job? = null

    fun open(slug: String) {
        job?.cancel()
        _state.value = RecipeDetailState.Loading
        job = viewModelScope.launch {
            withContext(ioDispatcher) { controller.open(slug) }
            _state.value = controller.state
        }
    }

    /** «Volver a intentar» tras un fallo (o tras un 401 ya arreglado en Ajustes). */
    fun retry() {
        val current = _state.value
        if (current is RecipeDetailState.Ready || current == RecipeDetailState.Loading || current == RecipeDetailState.Closed) return
        job?.cancel()
        _state.value = RecipeDetailState.Loading
        job = viewModelScope.launch {
            withContext(ioDispatcher) { controller.retry() }
            _state.value = controller.state
        }
    }

    /** Vuelve a la lista; el núcleo descarta lo que estuviera cargando. */
    fun close() {
        job?.cancel()
        job = null
        _state.value = controller.close()
    }

    // Instantáneas (no tocan la red): el núcleo acota y recalcula las raciones.
    fun onMoreServings() {
        _state.value = controller.moreServings()
    }

    fun onFewerServings() {
        _state.value = controller.fewerServings()
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

        fun factory(access: ClientAccess): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeDetailViewModel(access) as T
            }
    }
}
