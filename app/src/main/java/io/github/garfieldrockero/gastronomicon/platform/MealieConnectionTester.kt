package io.github.garfieldrockero.gastronomicon.platform

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
 * Fuera del hilo principal (la llamada de red es bloqueante). La red activa se
 * consulta en cada salto con [activeNetwork] (Android), no se guarda.
 * Ni la URL ni el token se registran ni acaban en el resultado: lo único que
 * sale es un [ConnectionProblem] cerrado.
 */
class MealieConnectionTester(
    private val activeNetwork: ActiveNetworkProvider,
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
                is ConnectionCheck.Success -> ConnectionTestResult.Success
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
}
