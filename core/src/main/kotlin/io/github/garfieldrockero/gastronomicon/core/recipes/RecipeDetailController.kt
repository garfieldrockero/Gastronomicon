package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem

/** Estado del detalle de una receta; la interfaz solo lo pinta. */
sealed interface RecipeDetailState {
    /** No hay detalle abierto. */
    object Closed : RecipeDetailState {
        override fun toString(): String = "RecipeDetailState.Closed"
    }

    object Loading : RecipeDetailState {
        override fun toString(): String = "RecipeDetailState.Loading"
    }

    /** Receta cargada y ya formateada para las raciones elegidas. */
    class Ready(val view: RecipeDetailView) : RecipeDetailState {
        // Sin el contenido: son datos del servidor.
        override fun toString(): String = "RecipeDetailState.Ready($view)"
    }

    /** El servidor rechaza el token (401 o 403): se pide ir a Ajustes, no reintentar. Va aparte de [Failed], que nunca lo lleva. */
    object Unauthorized : RecipeDetailState {
        override fun toString(): String = "RecipeDetailState.Unauthorized"
    }

    /** La receta ya no existe (404) o su identificador no es válido. */
    object NotFound : RecipeDetailState {
        override fun toString(): String = "RecipeDetailState.NotFound"
    }

    /** Cualquier otro fallo; [problem] nunca es [ConnectionProblem.Unauthorized]. */
    class Failed(val problem: ConnectionProblem) : RecipeDetailState {
        override fun toString(): String = "RecipeDetailState.Failed($problem)"
    }
}

/**
 * Lleva el detalle de una receta: la carga con [RecipeDetailSource.getRecipe],
 * construye [RecipeDetailView] y recalcula las raciones. El ViewModel de
 * `app/` solo llama a estos métodos y pinta [state].
 *
 * [open] y [retry] son **bloqueantes** (como el resto del núcleo): `app/` los
 * lanza desde un hilo de fondo. Si mientras tanto se abre otra receta o se
 * llama a [close], la respuesta vieja se descarta y se devuelve el estado
 * vigente. Los fallos se reducen a un estado tipado; el mensaje de la
 * excepción no se guarda.
 */
class RecipeDetailController(private val source: RecipeDetailSource) {
    private val lock = Any()
    private var generation = 0L
    private var currentSlug: String? = null

    @Volatile
    var state: RecipeDetailState = RecipeDetailState.Closed
        private set

    /** Carga la receta [slug]; se queda en [RecipeDetailState.Loading] hasta que acabe. */
    fun open(slug: String): RecipeDetailState {
        val ticket = synchronized(lock) {
            currentSlug = slug
            state = RecipeDetailState.Loading
            ++generation
        }
        return load(ticket, slug)
    }

    /** Repite la carga de la receta abierta tras un fallo; sin receta abierta o ya cargando no hace nada. */
    fun retry(): RecipeDetailState {
        val (ticket, slug) = synchronized(lock) {
            val slug = currentSlug
            if (slug == null || state == RecipeDetailState.Loading || state is RecipeDetailState.Ready) {
                return state
            }
            state = RecipeDetailState.Loading
            ++generation to slug
        }
        return load(ticket, slug)
    }

    /** Vuelve a la lista: descarta lo cargado y cualquier carga en curso. */
    fun close(): RecipeDetailState =
        synchronized(lock) {
            generation++
            currentSlug = null
            state = RecipeDetailState.Closed
            state
        }

    /** Cambia las raciones (acotadas por [RecipeDetailView.withServings]); sin receta lista no hace nada. */
    fun setServings(servings: Double?): RecipeDetailState = update { it.withServings(servings) }

    fun moreServings(): RecipeDetailState = update { it.moreServings() }

    fun fewerServings(): RecipeDetailState = update { it.fewerServings() }

    // ------------------------------------------------------------- privados

    private fun update(change: (RecipeDetailView) -> RecipeDetailView): RecipeDetailState =
        synchronized(lock) {
            val current = state
            if (current is RecipeDetailState.Ready) {
                state = RecipeDetailState.Ready(change(current.view))
            }
            state
        }

    private fun load(ticket: Long, slug: String): RecipeDetailState {
        val result: RecipeDetailState = try {
            RecipeDetailState.Ready(RecipeDetailView.from(source.getRecipe(slug)))
        } catch (error: NetworkError) {
            fromNetworkError(error)
        } catch (error: IllegalArgumentException) {
            // Slug fuera de la lista blanca: no se envió nada y no hay receta que abrir.
            RecipeDetailState.NotFound
        } catch (error: RuntimeException) {
            RecipeDetailState.Failed(ConnectionProblem.Unknown)
        }
        return synchronized(lock) {
            if (ticket == generation) state = result
            state
        }
    }

    private fun fromNetworkError(error: NetworkError): RecipeDetailState {
        if (error is NetworkError.HttpError && error.statusCode == 404) return RecipeDetailState.NotFound
        val problem = error.toConnectionProblem()
        return if (problem == ConnectionProblem.Unauthorized) {
            RecipeDetailState.Unauthorized
        } else {
            RecipeDetailState.Failed(problem)
        }
    }
}
