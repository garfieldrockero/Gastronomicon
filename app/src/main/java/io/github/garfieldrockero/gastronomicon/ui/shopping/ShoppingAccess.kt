package io.github.garfieldrockero.gastronomicon.ui.shopping

import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingClient
import io.github.garfieldrockero.gastronomicon.platform.SessionStorageException

/**
 * Da un cliente de la compra nuevo para UNA operación de red, igual que
 * `ClientAccess` hace con las recetas: nunca se guarda uno en un ViewModel, para
 * que apagar el interruptor de HTTP local o borrar la sesión se note al momento.
 * Se llama desde un hilo de fondo.
 */
fun interface ShoppingAccess {
    fun newClient(): ShoppingClient
}

/**
 * Misma política que `StoreClientAccess`: sin sesión guardada, 401; almacén
 * seguro sin respuesta, un fallo con mensaje fijo.
 */
class StoreShoppingAccess(
    private val sessionStore: SessionStore,
    private val clientFor: (Session) -> ShoppingClient,
) : ShoppingAccess {

    override fun newClient(): ShoppingClient {
        val session = try {
            sessionStore.load()
        } catch (ignored: SessionStorageException) {
            throw IllegalStateException("El almacén seguro no ha respondido.")
        } ?: throw NetworkError.HttpError(401)
        return clientFor(session)
    }
}
