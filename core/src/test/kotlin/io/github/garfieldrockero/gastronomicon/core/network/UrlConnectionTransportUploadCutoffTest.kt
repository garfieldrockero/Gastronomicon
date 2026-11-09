package io.github.garfieldrockero.gastronomicon.core.network

import com.sun.net.httpserver.HttpServer
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Respuestas tempranas mientras se envía el cuerpo.
 *
 * Antes, un 413 (o un 401/403) de un proxy que cierra sin recibir el cuerpo
 * entero se veía como «no hay conexión». Ahora se intenta leer el código y, si
 * el JDK ya no puede reconstruir la respuesta, el fallo queda marcado como
 * ambiguo en vez de mentir sobre la causa.
 *
 * El resultado exacto depende de si la escritura falla antes o después de que
 * llegue la respuesta, que es cosa de la pila TCP del sistema; por eso las
 * comprobaciones aceptan las dos salidas buenas y rechazan la mala.
 */
class UrlConnectionTransportUploadCutoffTest {

    private val token = ApiToken("token-de-corte-ABCD1234")

    private lateinit var server: HttpServer
    private lateinit var pool: ExecutorService
    private val requests = AtomicInteger()

    /** Se guarda al arrancar: tras apagar el servidor, `address` ya no es de fiar. */
    private var port: Int = 0

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Un servidor que contesta y cierra sin leer el cuerpo puede acabar en
        // un hilo bloqueado; con un pool, no bloquea a los demás.
        pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val path = exchange.requestURI.path
            val status = when (path) {
                "/corta-413" -> 413
                "/corta-401" -> 401
                "/corta-403" -> 403
                "/lee-y-calla" -> {
                    // Lee el cuerpo entero (la petición llega a enviarse) y no
                    // contesta: el cliente tiene que vencer solo.
                    runCatching { exchange.requestBody.readBytes() }
                    Thread.sleep(1_000)
                    -1
                }
                else -> 404
            }
            if (status in 400..599) {
                // Sin leer el cuerpo: el cliente se encuentra la respuesta a
                // mitad de la escritura.
                runCatching {
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                }
            } else {
                runCatching { exchange.close() }
            }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
        pool.shutdownNow()
    }

    private fun transport(readTimeoutMs: Int = 5_000, totalTimeoutMs: Long = 15_000L) =
        UrlConnectionTransport(
            baseUri = URI("http://127.0.0.1:$port"),
            token = token,
            connectTimeoutMs = 5_000,
            readTimeoutMs = readTimeoutMs,
            maxResponseBytes = 1_000_000,
            totalTimeoutMs = totalTimeoutMs,
        )

    private fun errorOf(block: () -> Unit): NetworkError {
        try {
            block()
        } catch (error: NetworkError) {
            return error
        }
        fail("Debía lanzar NetworkError")
        throw IllegalStateException()
    }

    @Test
    fun `un 413 que corta el cuerpo no se convierte en falta de conexion`() {
        for ((path, status) in listOf("/corta-413" to 413, "/corta-401" to 401, "/corta-403" to 403)) {
            requests.set(0)
            val error = errorOf {
                transport().execute(
                    HttpRequest(HttpMethod.PUT, path, body = ByteArray(HttpRequest.MAX_BODY_BYTES)),
                )
            }
            when (error) {
                is NetworkError.HttpError -> assertEquals("$path", status, error.statusCode)
                // Si la escritura falló antes y el JDK ya no puede reconstruir
                // la respuesta, al menos no se presenta como un NoConnection
                // pelado: el fallo va marcado como «pudo enviarse».
                is NetworkError.AfterSend -> assertTrue(
                    "$path: ${error.original}",
                    error.original === NetworkError.NoConnection ||
                        error.original === NetworkError.InvalidResponse ||
                        error.original === NetworkError.Timeout,
                )
                else -> fail("$path: se esperaba HttpError o AfterSend, no $error")
            }
            // Nunca se reintenta una escritura a ciegas.
            assertEquals("$path", 1, requests.get())
        }
    }

    @Test
    fun `un plazo vencido despues de enviar el cuerpo queda marcado como ambiguo`() {
        val error = errorOf {
            transport(readTimeoutMs = 300, totalTimeoutMs = 5_000).execute(
                HttpRequest(HttpMethod.POST, "/lee-y-calla", body = ByteArray(1_000)),
            )
        }
        assertTrue("Se esperaba AfterSend, no $error", error is NetworkError.AfterSend)
        assertTrue(
            "El motivo real debe seguir disponible",
            (error as NetworkError.AfterSend).original === NetworkError.Timeout,
        )
        assertEquals(1, requests.get())
    }

    @Test
    fun `un fallo de red antes de enviar sigue siendo el motivo de siempre`() {
        // Se apaga el servidor: la conexión se rechaza antes de escribir nada.
        server.stop(0)
        val error = errorOf {
            transport().execute(HttpRequest(HttpMethod.POST, "/x", body = ByteArray(10)))
        }
        // Ni se envuelve ni se disfraza: aquí sí se sabe que no se envió.
        assertFalse(error is NetworkError.AfterSend)
        assertEquals(NetworkError.ConnectionRefused, error)
    }
}
