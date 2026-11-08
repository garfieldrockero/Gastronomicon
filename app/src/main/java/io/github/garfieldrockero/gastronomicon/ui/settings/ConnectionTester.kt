package io.github.garfieldrockero.gastronomicon.ui.settings

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem

/** Resultado de «Probar conexión»: o responde, o hay un motivo cerrado del núcleo. */
sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class Failure(val problem: ConnectionProblem) : ConnectionTestResult
}

/**
 * Lo que Ajustes necesita para probar la conexión. `MealieClient` (que escribe
 * `cliente-mealie`) aportará la implementación real: `GET /api/app/about` y
 * después `GET /api/users/self`. La interfaz vive aquí para que la pantalla no
 * dependa de él.
 *
 * Contrato:
 * - [serverUrl] llega ya normalizada por `ServerAddress.build`.
 * - Se llama fuera del hilo principal.
 * - No debe lanzar excepciones de red: las convierte en [ConnectionTestResult.Failure].
 * - Nada de lo recibido (URL, token) puede acabar en un log ni en un mensaje.
 */
fun interface ConnectionTester {
    suspend fun test(serverUrl: String, token: ApiToken, allowLocalHttp: Boolean): ConnectionTestResult
}

/** Implementación falsa para previews y pruebas: contesta lo que se le diga. */
class FakeConnectionTester(
    private val result: ConnectionTestResult = ConnectionTestResult.Success,
) : ConnectionTester {
    override suspend fun test(
        serverUrl: String,
        token: ApiToken,
        allowLocalHttp: Boolean,
    ): ConnectionTestResult = result
}
