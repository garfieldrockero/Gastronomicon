package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore

/**
 * Da un cliente nuevo para UNA operación de red. Lee la sesión del almacén
 * seguro y vuelve a leer el interruptor de HTTP local (ver
 * [AppContainer.newClient]); el resultado no se guarda en ningún campo ni
 * ViewModel. Se llama desde un hilo de fondo.
 *
 * Los fallos salen como [NetworkError] o [RuntimeException] para que el
 * núcleo los reduzca a un motivo sin texto técnico:
 * - Sin sesión guardada: 401, que la interfaz enseña como «sesión no válida».
 * - Almacén seguro sin respuesta: [IllegalStateException] (motivo «desconocido»).
 * - Sesión http con el interruptor apagado: [IllegalArgumentException] de
 *   `MealieClient.create` (falla cerrado, también «desconocido»).
 */
fun interface ClientAccess {
    fun newClient(): MealieClient
}

class StoreClientAccess(
    private val sessionStore: SessionStore,
    private val clientFor: (Session) -> MealieClient,
) : ClientAccess {

    override fun newClient(): MealieClient {
        val session = try {
            sessionStore.load()
        } catch (ignored: SessionStorageException) {
            // Mensaje fijo: no se arrastra nada del almacén.
            throw IllegalStateException("El almacén seguro no ha respondido.")
        } ?: throw NetworkError.HttpError(401)
        return clientFor(session)
    }
}
