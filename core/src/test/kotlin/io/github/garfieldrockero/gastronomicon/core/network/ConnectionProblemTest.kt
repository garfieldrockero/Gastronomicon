package io.github.garfieldrockero.gastronomicon.core.network

import io.github.garfieldrockero.gastronomicon.core.NotLocalAddressException
import io.github.garfieldrockero.gastronomicon.core.config.ServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pruebas del paso de [NetworkError] al motivo que ve la persona al probar la
 * conexión. Se recorren todos los subtipos: un error mal clasificado manda a
 * la persona a arreglar lo que no está roto (el token cuando es la dirección,
 * o al revés).
 */
class ConnectionProblemTest {

    /** Cada subtipo «object» de NetworkError con el motivo que le toca. */
    private val objectCases: Map<NetworkError, ConnectionProblem> = mapOf(
        NetworkError.NoConnection to ConnectionProblem.NoNetwork,
        NetworkError.Timeout to ConnectionProblem.ServerNotResponding,
        NetworkError.ConnectionRefused to ConnectionProblem.ServerNotResponding,
        NetworkError.DnsFailure to ConnectionProblem.InvalidAddress,
        NetworkError.TlsError to ConnectionProblem.SecureConnectionFailed,
        NetworkError.InvalidRequest to ConnectionProblem.InvalidAddress,
        NetworkError.RedirectRejected to ConnectionProblem.Unknown,
        NetworkError.TooManyRedirects to ConnectionProblem.Unknown,
        NetworkError.InvalidResponse to ConnectionProblem.Unknown,
        NetworkError.ResponseTooLarge to ConnectionProblem.Unknown,
        NetworkError.LocalHttpNotAllowedOnThisNetwork to ConnectionProblem.LocalHttpNeedsHomeNetwork,
    )

    /**
     * El envoltorio «pudo enviarse» no cambia el motivo: la persona ve lo que
     * le toca hacer, no si el servidor llegó a recibir la petición.
     */
    private val wrapperCases: Map<NetworkError, ConnectionProblem> = mapOf(
        NetworkError.AfterSend(NetworkError.NoConnection) to ConnectionProblem.NoNetwork,
        NetworkError.AfterSend(NetworkError.Timeout) to ConnectionProblem.ServerNotResponding,
        NetworkError.AfterSend(NetworkError.TlsError) to ConnectionProblem.SecureConnectionFailed,
    )

    @Test
    fun `cada error sin codigo lleva a su motivo`() {
        for ((error, expected) in objectCases + wrapperCases) {
            assertEquals("Motivo de ${error::class.java.simpleName}", expected, error.toConnectionProblem())
        }
    }

    @Test
    fun `el tiempo agotado no se confunde con la falta de red`() {
        assertEquals(ConnectionProblem.ServerNotResponding, NetworkError.Timeout.toConnectionProblem())
        assertEquals(ConnectionProblem.NoNetwork, NetworkError.NoConnection.toConnectionProblem())
        assertNotEquals(ConnectionProblem.NoNetwork, NetworkError.Timeout.toConnectionProblem())
    }

    @Test
    fun `la conexion rechazada es servidor sin respuesta y no falta de red`() {
        assertEquals(ConnectionProblem.ServerNotResponding, NetworkError.ConnectionRefused.toConnectionProblem())
        assertNotEquals(ConnectionProblem.NoNetwork, NetworkError.ConnectionRefused.toConnectionProblem())
    }

    @Test
    fun `401 y 403 son un problema del token`() {
        assertProblem(ConnectionProblem.Unauthorized, 401, 403)
    }

    @Test
    fun `404 es una direccion que no lleva a la API`() {
        assertProblem(ConnectionProblem.InvalidAddress, 404)
    }

    @Test
    fun `los 5xx son un fallo del servidor, de punta a punta del rango`() {
        assertProblem(ConnectionProblem.ServerError, 500, 502, 503, 504, 599)
    }

    @Test
    fun `el resto de codigos es un motivo desconocido`() {
        // Bordes de los rangos incluidos: 499 y 600 no son 5xx, y 400, 402 y
        // 405 rodean a los códigos con motivo propio.
        assertProblem(ConnectionProblem.Unknown, 304, 400, 402, 405, 418, 422, 429, 499, 600)
    }

    @Test
    fun `ningun error de red se confunde con un token mal escrito`() {
        // InvalidToken es para cuando ApiToken.fromInput rechaza lo pegado: un
        // error de red nunca puede decir que el token tiene mala forma.
        val all = objectCases.keys + listOf(401, 403, 404, 422, 500).map { NetworkError.HttpError(it) }
        for (error in all) {
            assertFalse(error.toConnectionProblem() == ConnectionProblem.InvalidToken)
        }
    }

    @Test
    fun `ningun error de red se confunde con una direccion que no es local`() {
        // NotLocalAddress solo sale de addressProblemOf, al guardar la dirección.
        val all = objectCases.keys + listOf(401, 403, 404, 422, 500).map { NetworkError.HttpError(it) }
        for (error in all) {
            assertFalse(error.toConnectionProblem() == ConnectionProblem.NotLocalAddress)
        }
    }

    @Test
    fun `el rechazo por no local se traduce a NotLocalAddress`() {
        assertEquals(
            ConnectionProblem.NotLocalAddress,
            addressProblemOf(NotLocalAddressException("no es local")),
        )
    }

    @Test
    fun `cualquier otro rechazo de la direccion es InvalidAddress`() {
        assertEquals(ConnectionProblem.InvalidAddress, addressProblemOf(IllegalArgumentException("otra cosa")))
        // NumberFormatException también es IllegalArgumentException.
        assertEquals(ConnectionProblem.InvalidAddress, addressProblemOf(NumberFormatException("puerto")))
    }

    @Test
    fun `addressProblemOf distingue los rechazos reales de ServerAddress`() {
        assertEquals(ConnectionProblem.NotLocalAddress, problemOfBuild("mealie.example.com", 9000, true))
        assertEquals(ConnectionProblem.NotLocalAddress, problemOfBuild("mealie.local", 9000, true))
        assertEquals(ConnectionProblem.NotLocalAddress, problemOfBuild("8.8.8.8", null, true))
        assertEquals(ConnectionProblem.InvalidAddress, problemOfBuild("http://192.168.1.50", 9000, false))
        assertEquals(ConnectionProblem.InvalidAddress, problemOfBuild("mi_host", null, true))
        assertEquals(ConnectionProblem.InvalidAddress, problemOfBuild("", 9000, true))
        assertEquals(ConnectionProblem.InvalidAddress, problemOfBuild("192.168.1.50:8080", 7000, true))
    }

    @Test
    fun `la prueba conoce todos los subtipos de NetworkError`() {
        // Si alguien añade un subtipo, el «when» de toConnectionProblem deja de
        // compilar; esto obliga además a añadirlo aquí con su motivo. Se mira
        // con reflexión de Java para no depender de kotlin-reflect.
        val declared = NetworkError::class.java.declaredClasses
            .filter { NetworkError::class.java.isAssignableFrom(it) }
            .map { it.name }
            .toSet()
        val covered = (
            (objectCases.keys + wrapperCases.keys).map { it::class.java } +
                NetworkError.HttpError::class.java
            ).map { it.name }.toSet()
        assertEquals(declared, covered)
    }

    private fun problemOfBuild(server: String, port: Int?, allowLocalHttp: Boolean): ConnectionProblem {
        try {
            ServerAddress.build(server, port, allowLocalHttp)
        } catch (error: IllegalArgumentException) {
            return addressProblemOf(error)
        }
        throw AssertionError("Se esperaba que «$server» se rechazara")
    }

    private fun assertProblem(expected: ConnectionProblem, vararg statusCodes: Int) {
        for (code in statusCodes) {
            assertEquals("Motivo del código $code", expected, NetworkError.HttpError(code).toConnectionProblem())
        }
    }
}
