package io.github.garfieldrockero.gastronomicon.core

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.config.ServerAddress
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frontera común de redacción: un token señuelo puede formar parte de una
 * entrada inválida, pero nunca del error que la interfaz o un log enseñan.
 */
class SecretRedactionTest {

    @Test
    fun `every NetworkError variant redacts the decoy token from message and toString`() {
        val errors = listOf<NetworkError>(
            NetworkError.NoConnection,
            NetworkError.DnsFailure,
            NetworkError.TlsError,
            NetworkError.Timeout,
            NetworkError.ResponseTooLarge,
            NetworkError.RedirectRejected,
            NetworkError.TooManyRedirects,
            NetworkError.InvalidRequest,
            NetworkError.LocalHttpNotAllowedOnThisNetwork,
            NetworkError.InvalidResponse,
        ) + listOf(400, 401, 403, 404, 418, 422, 500, 503, 599).map {
            // Recorre todas las ramas del mensaje HTTP, no solo una instancia.
            NetworkError.HttpError(it)
        }

        for (error in errors) {
            assertFalse(
                "${error::class.java.simpleName} repite el token en message",
                error.message.orEmpty().contains(DECOY_TOKEN, ignoreCase = true),
            )
            assertFalse(
                "${error::class.java.simpleName} repite el token en toString",
                error.toString().contains(DECOY_TOKEN, ignoreCase = true),
            )
        }
    }

    @Test
    fun `ServerUrl validation redacts a token pasted into URL host or invalid input`() {
        val invalidInputs = listOf(
            // La entrada entera, sin esquema.
            DECOY_TOKEN,
            // Credencial pegada dentro de la URL.
            "https://$DECOY_TOKEN@mealie.example.com/",
            // Token en la ruta y una consulta que fuerza el rechazo.
            "https://mealie.example.com/$DECOY_TOKEN?origen=pegado",
            // Los guiones bajos del señuelo hacen inválido este host.
            "https://$DECOY_TOKEN.example.com/",
            // La rama del parser tampoco puede repetir la entrada malformada.
            "https://mealie.example.com/$DECOY_TOKEN<",
        )
        for (input in invalidInputs) {
            assertRejectedWithoutSecret("ServerUrl") {
                ServerUrl.normalize(input)
            }
        }
    }

    @Test
    fun `ServerAddress validation redacts a token pasted into server or invalid input`() {
        val invalidServers = listOf(
            DECOY_TOKEN,
            "https://$DECOY_TOKEN@mealie.example.com/",
            "https://mealie.example.com/$DECOY_TOKEN?origen=pegado",
            "$DECOY_TOKEN.example.com",
            "https://mealie.example.com/$DECOY_TOKEN<",
        )
        for (server in invalidServers) {
            assertRejectedWithoutSecret("ServerAddress") {
                ServerAddress.build(server, null)
            }
        }

        // Los dos rechazos propios de ServerAddress se comprueban también con
        // el señuelo presente, aunque el motivo sea el puerto y no el servidor.
        assertRejectedWithoutSecret("ServerAddress con puerto inválido") {
            ServerAddress.build("https://mealie.example.com/$DECOY_TOKEN", 0)
        }
        assertRejectedWithoutSecret("ServerAddress con puertos en conflicto") {
            ServerAddress.build("https://mealie.example.com:8443/$DECOY_TOKEN", 9000)
        }
    }

    @Test
    fun `ApiToken validation redacts the decoy token from every applicable rejection branch`() {
        val invalidInputs = listOf(
            // Demasiado largo.
            "$DECOY_TOKEN${"a".repeat(ApiToken.MAX_LENGTH)}",
            // Cabecera Bearer pegada entera.
            "Bearer $DECOY_TOKEN",
            // Espacio dentro de la entrada.
            "prefijo $DECOY_TOKEN",
            // Carácter invisible.
            "$DECOY_TOKEN\u200B",
            // Carácter no ASCII.
            "${DECOY_TOKEN}é",
        )
        for (input in invalidInputs) {
            assertRejectedWithoutSecret("ApiToken") {
                ApiToken(input)
            }
        }
    }

    // ---------------------------------------------------------------- ayuda

    private fun assertRejectedWithoutSecret(component: String, validation: () -> Unit) {
        try {
            validation()
            throw AssertionError("Se esperaba que $component rechazara la entrada con el señuelo")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "El rechazo de $component no trae un mensaje claro",
                !expected.message.isNullOrBlank(),
            )
            assertFalse(
                "$component repite el token en message",
                expected.message.orEmpty().contains(DECOY_TOKEN, ignoreCase = true),
            )
            assertFalse(
                "$component repite el token en toString",
                expected.toString().contains(DECOY_TOKEN, ignoreCase = true),
            )
        }
    }

    private companion object {
        // Sin distinguir mayúsculas al comparar: ServerUrl pasa el host a minúsculas, y un
        // mensaje que repitiera la entrada normalizada no coincidiría con el señuelo exacto.
        const val DECOY_TOKEN = "SENHUELO_token_9f8e7c_NO_DEBE_APARECER"
    }
}
