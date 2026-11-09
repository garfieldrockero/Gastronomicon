package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.ConnectionCheck
import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.addressProblemOf
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.ui.settings.ConnectionTestResult
import io.github.garfieldrockero.gastronomicon.ui.settings.ConnectionTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Probar conexión» de verdad: `MealieClient.checkConnection()`, que pregunta
 * primero a `/api/app/about` SIN token (¿es un Mealie?) y solo después a
 * `/api/users/self` con él. Nunca se hace un GET autenticado suelto.
 *
 * Si la conexión sale bien, se pregunta la cuenta (`currentUser()`) y se le
 * pasa a [scopeStore]: es lo que permite enseñar las recetas guardadas al
 * arrancar sin red. Que esa segunda petición falle no convierte la prueba en un
 * fallo —la conexión ya está comprobada—, solo deja los datos sin conexión
 * apagados hasta la próxima vez (falla cerrado).
 *
 * Fuera del hilo principal (la llamada de red es bloqueante). La red activa se
 * consulta en cada salto con [activeNetwork] (Android), no se guarda.
 * Ni la URL ni el token se registran ni acaban en el resultado: lo único que
 * sale es un [ConnectionProblem] cerrado.
 */
class MealieConnectionTester(
    private val activeNetwork: ActiveNetworkProvider,
    private val scopeStore: OfflineScopeStore? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionTester {

    override suspend fun test(
        serverUrl: String,
        token: ApiToken,
        allowLocalHttp: Boolean,
    ): ConnectionTestResult = withContext(ioDispatcher) {
        try {
            val client = MealieClient.create(Session(serverUrl, token), allowLocalHttp, activeNetwork)
            when (val check = client.checkConnection()) {
                is ConnectionCheck.Success -> {
                    rememberScope(client, serverUrl, token)
                    ConnectionTestResult.Success
                }
                is ConnectionCheck.Failure -> ConnectionTestResult.Failure(check.problem)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // URL no admitida por la política de red (p. ej. http sin el interruptor).
            ConnectionTestResult.Failure(addressProblemOf(e))
        } catch (e: Exception) {
            ConnectionTestResult.Failure(ConnectionProblem.Unknown)
        }
    }

    /**
     * Apunta la cuenta con la que se acaba de hablar, para que el ámbito de lo
     * offline se escriba al guardar la sesión. Best effort: si el servidor no
     * devuelve un id válido o la petición falla, no se apunta nada y no se
     * ofrece contenido sin conexión.
     */
    private fun rememberScope(client: MealieClient, serverUrl: String, token: ApiToken) {
        val store = scopeStore ?: return
        val user = try {
            client.currentUser()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin la cuenta no hay ámbito; la conexión sigue siendo válida.
            return
        }
        if (user is ApiResult.Success) store.onConnectionSucceeded(serverUrl, token, user.value.id)
    }
}
