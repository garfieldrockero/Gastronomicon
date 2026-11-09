package io.github.garfieldrockero.gastronomicon.ui.mealplan

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanClient
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanSource
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.platform.SessionStorageException

/**
 * Da una fuente del planificador nueva para UNA operación de red, igual que
 * `ClientAccess` hace con `MealieClient`: no se guarda en ningún campo, así
 * borrar la sesión o apagar el HTTP local se nota al momento. Se llama desde
 * un hilo de fondo.
 */
fun interface MealPlanAccess {
    fun newSource(): MealPlanSource
}

/**
 * Lee la sesión del almacén seguro y crea el cliente con [clientFor].
 * Mismos fallos que `StoreClientAccess`: sin sesión, 401 (la interfaz lo
 * enseña como «sesión no válida»); almacén sin respuesta,
 * [IllegalStateException] con mensaje fijo (el núcleo lo reduce a «desconocido»).
 */
class StoreMealPlanAccess(
    private val sessionStore: SessionStore,
    private val clientFor: (Session) -> MealPlanClient,
) : MealPlanAccess {

    override fun newSource(): MealPlanSource {
        val session = try {
            sessionStore.load()
        } catch (ignored: SessionStorageException) {
            throw IllegalStateException("El almacén seguro no ha respondido.")
        } ?: throw NetworkError.HttpError(401)
        return clientFor(session)
    }
}
