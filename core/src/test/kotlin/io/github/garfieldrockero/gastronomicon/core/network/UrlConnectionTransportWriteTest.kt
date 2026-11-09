package io.github.garfieldrockero.gastronomicon.core.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Escrituras (POST, PUT, DELETE) del transporte: cuerpo JSON y multipart,
 * cuerpo de error del 422, 401 con cuerpo y redirecciones de escrituras. Misma
 * política que las lecturas: el token solo viaja al origen de la base.
 */
class UrlConnectionTransportWriteTest {

    private val token = ApiToken("token-de-escritura-ABCD1234")

    private class Seen(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private lateinit var server: HttpServer
    private lateinit var otherServer: HttpServer
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    private val seenByOther: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            // El cuerpo se lee siempre antes de contestar: una escritura se ve entera.
            val body = runCatching { exchange.requestBody.readBytes() }.getOrDefault(ByteArray(0))
            seen.add(record(exchange, body))
            val handler = routes[exchange.requestURI.path] ?: { e -> respond(e, 404) }
            handler(exchange)
        }
        server.start()
        otherServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        otherServer.createContext("/") { exchange ->
            val body = runCatching { exchange.requestBody.readBytes() }.getOrDefault(ByteArray(0))
            seenByOther.add(record(exchange, body))
            respond(exchange, 200, "{}".toByteArray())
        }
        otherServer.start()
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
        runCatching { otherServer.stop(0) }
    }

    private fun record(exchange: HttpExchange, body: ByteArray) = Seen(
        method = exchange.requestMethod,
        path = exchange.requestURI.path,
        headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to (it.value.firstOrNull() ?: "") },
        body = body,
    )

    private fun transport(
        withToken: Boolean = true,
        maxBytes: Long = 1_000_000,
        port: Int = server.address.port,
    ) = UrlConnectionTransport(
        baseUri = URI("http://127.0.0.1:$port"),
        token = if (withToken) token else null,
        connectTimeoutMs = 5_000,
        readTimeoutMs = 5_000,
        maxResponseBytes = maxBytes,
    )

    private fun httpError(block: () -> Unit): NetworkError.HttpError {
        try {
            block()
        } catch (error: NetworkError.HttpError) {
            return error
        }
        fail("Debía lanzar HttpError")
        throw IllegalStateException()
    }

    private fun networkError(block: () -> Unit): NetworkError {
        try {
            block()
        } catch (error: NetworkError) {
            return error
        }
        fail("Debía lanzar NetworkError")
        throw IllegalStateException()
    }

    // ------------------------------------------------------------ métodos y cuerpo

    @Test
    fun `un POST con JSON llega con metodo cuerpo tipo y token`() {
        routes["/api/x"] = { e -> respond(e, 201, "\"nueva\"".toByteArray()) }
        val response = transport().execute(HttpRequest.json(HttpMethod.POST, "/api/x", """{"name":"Tarta ñ"}"""))
        assertEquals(201, response.statusCode)
        assertEquals("\"nueva\"", response.bodyAsText())
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("""{"name":"Tarta ñ"}""", String(request.body, Charsets.UTF_8))
        assertEquals("Bearer ${token.value}", request.headers["authorization"])
        assertEquals(request.body.size.toString(), request.headers["content-length"])
    }

    @Test
    fun `PUT y DELETE usan su metodo y DELETE no lleva cuerpo`() {
        routes["/api/x"] = { e -> respond(e, 200, "{}".toByteArray()) }
        transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/x", "{}"))
        transport().execute(HttpRequest(HttpMethod.DELETE, "/api/x"))
        assertEquals(listOf("PUT", "DELETE"), seen.map { it.method })
        assertEquals(0, seen[1].body.size)
        assertEquals("Bearer ${token.value}", seen[1].headers["authorization"])
    }

    @Test
    fun `un multipart llega byte a byte con su delimitador`() {
        routes["/api/img"] = { e -> respond(e, 200, "{}".toByteArray()) }
        val image = ByteArray(300_000) { (it % 251).toByte() }
        val built = MultipartBody.build(
            listOf(
                MultipartBody.Part.File("image", "image.png", "image/png", image),
                MultipartBody.Part.Field("extension", "png"),
            ),
            "limiteDePrueba1234567890",
        )
        transport().execute(HttpRequest.multipart(HttpMethod.PUT, "/api/img", built))
        val request = seen.single()
        assertEquals("PUT", request.method)
        assertEquals("multipart/form-data; boundary=limiteDePrueba1234567890", request.headers["content-type"])
        assertArrayEquals(built.bytes, request.body)
    }

    @Test
    fun `un cuerpo por encima del tope no se construye`() {
        try {
            HttpRequest(HttpMethod.PUT, "/api/x", body = ByteArray(HttpRequest.MAX_BODY_BYTES + 1))
            fail("Debía rechazarlo")
        } catch (expected: IllegalArgumentException) {
            // bien
        }
        // Justo en el tope sí.
        HttpRequest(HttpMethod.PUT, "/api/x", body = ByteArray(HttpRequest.MAX_BODY_BYTES))
    }

    @Test
    fun `el transporte no deja poner Authorization a mano en una escritura`() {
        try {
            HttpRequest(HttpMethod.POST, "/api/x", headers = mapOf("Authorization" to "Bearer otro"), body = ByteArray(1))
            fail("Debía rechazarlo")
        } catch (expected: IllegalArgumentException) {
            // bien
        }
    }

    // ------------------------------------------------------------------- errores

    @Test
    fun `un 401 en un POST con cuerpo es un HttpError 401 y no se reintenta`() {
        routes["/api/x"] = { e ->
            e.responseHeaders.add("WWW-Authenticate", "Basic realm=\"mealie\"")
            respond(e, 401, "{\"detail\":\"no\"}".toByteArray(), "application/json")
        }
        val body = ByteArray(20_000) { 'a'.code.toByte() }
        val error = httpError { transport().execute(HttpRequest(HttpMethod.POST, "/api/x", body = body)) }
        assertEquals(401, error.statusCode)
        assertEquals(1, seen.size)
        assertNull(error.body)
        assertFalse(error.message.orEmpty().contains(token.value))
    }

    @Test
    fun `un 401 en un PUT multipart tampoco se reintenta ni lleva cuerpo`() {
        routes["/api/img"] = { e -> respond(e, 401) }
        val built = MultipartBody.build(listOf(MultipartBody.Part.File("image", "a.png", "image/png", ByteArray(50_000))))
        val error = httpError { transport().execute(HttpRequest.multipart(HttpMethod.PUT, "/api/img", built)) }
        assertEquals(401, error.statusCode)
        assertEquals(1, seen.size)
    }

    @Test
    fun `403 404 409 y 500 llegan como HttpError sin cuerpo aunque se pida`() {
        for (status in listOf(400, 403, 404, 409, 500, 503)) {
            routes["/api/x"] = { e -> respond(e, status, "{\"detail\":\"secreto-del-servidor\"}".toByteArray()) }
            val error = httpError {
                transport().execute(HttpRequest.json(HttpMethod.POST, "/api/x", "{}", readErrorBody = true))
            }
            assertEquals(status, error.statusCode)
            assertNull("solo el 422 lleva cuerpo", error.body)
            assertFalse(error.message.orEmpty().contains("secreto-del-servidor"))
        }
    }

    @Test
    fun `un 422 lleva su cuerpo solo si la peticion lo pidio`() {
        val detail = """{"detail":[{"loc":["body","name"],"msg":"campo obligatorio","type":"missing"}]}"""
        routes["/api/x"] = { e -> respond(e, 422, detail.toByteArray(), "application/json") }

        val withBody = httpError {
            transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/x", "{}", readErrorBody = true))
        }
        assertEquals(422, withBody.statusCode)
        assertNotNull(withBody.body)
        assertEquals(detail, String(withBody.body!!, Charsets.UTF_8))

        val without = httpError { transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/x", "{}")) }
        assertEquals(422, without.statusCode)
        assertNull(without.body)
    }

    @Test
    fun `el cuerpo de un 422 nunca aparece en el mensaje ni en toString`() {
        routes["/api/x"] = { e -> respond(e, 422, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}".toByteArray()) }
        val error = httpError {
            transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/x", "{}", readErrorBody = true))
        }
        assertNotNull(error.body)
        assertFalse(error.message.orEmpty().contains("SECRETO"))
        assertFalse(error.toString().contains("SECRETO"))
        assertFalse(error.toString().contains(token.value))
    }

    @Test
    fun `el cuerpo de un 422 se acota al tope y no se lee entero`() {
        routes["/api/x"] = { e -> respond(e, 422, ByteArray(500_000) { 'x'.code.toByte() }) }
        val error = httpError {
            transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/x", "{}", readErrorBody = true))
        }
        assertEquals(422, error.statusCode)
        assertEquals(HttpRequest.MAX_ERROR_BODY_BYTES, error.body!!.size)
    }

    @Test
    fun `un 422 sin cuerpo da HttpError con cuerpo nulo`() {
        routes["/api/x"] = { e -> respond(e, 422) }
        val error = httpError {
            transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/x", "{}", readErrorBody = true))
        }
        assertEquals(422, error.statusCode)
        assertTrue(error.body == null || error.body!!.isEmpty())
    }

    @Test
    fun `una respuesta enorme a una escritura se corta con ResponseTooLarge`() {
        routes["/api/x"] = { e -> respond(e, 200, ByteArray(200_000) { 'a'.code.toByte() }) }
        val error = networkError {
            transport(maxBytes = 1_000).execute(HttpRequest.json(HttpMethod.PUT, "/api/x", "{}"))
        }
        assertEquals(NetworkError.ResponseTooLarge, error)
    }

    // ------------------------------------------------------------ redirecciones

    @Test
    fun `un 307 en una escritura del mismo origen conserva metodo cuerpo y token`() {
        routes["/api/viejo"] = { e ->
            e.responseHeaders.add("Location", "/api/nuevo")
            respond(e, 307)
        }
        routes["/api/nuevo"] = { e -> respond(e, 200, "{}".toByteArray()) }
        transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/viejo", """{"a":1}"""))
        assertEquals(listOf("/api/viejo", "/api/nuevo"), seen.map { it.path })
        assertEquals(listOf("PUT", "PUT"), seen.map { it.method })
        assertEquals("""{"a":1}""", String(seen[1].body, Charsets.UTF_8))
        assertEquals("Bearer ${token.value}", seen[1].headers["authorization"])
    }

    @Test
    fun `un 302 en una escritura no se sigue`() {
        routes["/api/viejo"] = { e ->
            e.responseHeaders.add("Location", "/api/nuevo")
            respond(e, 302)
        }
        routes["/api/nuevo"] = { e -> respond(e, 200) }
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            val error = networkError {
                transport().execute(HttpRequest(method, "/api/viejo", body = if (method == HttpMethod.DELETE) null else ByteArray(3)))
            }
            assertEquals(method.name, NetworkError.RedirectRejected, error)
        }
        assertTrue(seen.none { it.path == "/api/nuevo" })
    }

    @Test
    fun `una escritura nunca se redirige a otro origen y al otro no le llega ni el token ni el cuerpo`() {
        val other = "http://127.0.0.1:${otherServer.address.port}/api/robo"
        for (status in listOf(307, 308)) {
            routes["/api/viejo"] = { e ->
                e.responseHeaders.add("Location", other)
                respond(e, status)
            }
            val error = networkError {
                transport().execute(HttpRequest.json(HttpMethod.POST, "/api/viejo", """{"secreto":"x"}""", readErrorBody = true))
            }
            assertEquals(NetworkError.RedirectRejected, error)
        }
        // También hacia otro host por nombre y con otro esquema.
        for (location in listOf("http://localhost:${server.address.port}/x", "https://127.0.0.1:${server.address.port}/x")) {
            routes["/api/viejo"] = { e ->
                e.responseHeaders.add("Location", location)
                respond(e, 307)
            }
            assertEquals(
                NetworkError.RedirectRejected,
                networkError { transport().execute(HttpRequest.json(HttpMethod.PUT, "/api/viejo", "{}")) },
            )
        }
        assertTrue("al otro origen no le llega nada", seenByOther.isEmpty())
    }

    @Test
    fun `un 303 tras una escritura se sigue como GET sin cuerpo y sin tipo`() {
        routes["/api/crear"] = { e ->
            e.responseHeaders.add("Location", "/api/ver")
            respond(e, 303)
        }
        routes["/api/ver"] = { e -> respond(e, 200, "{}".toByteArray()) }
        transport().execute(HttpRequest.json(HttpMethod.POST, "/api/crear", """{"a":1}"""))
        val second = seen[1]
        assertEquals("GET", second.method)
        assertEquals(0, second.body.size)
        assertNull(second.headers["content-type"])
    }

    // -------------------------------------------------------------------- red

    @Test
    fun `la red local bloqueada corta una escritura sin enviar nada`() {
        routes["/api/x"] = { e -> respond(e, 200) }
        val blocked = UrlConnectionTransport(
            baseUri = URI("http://127.0.0.1:${server.address.port}"),
            token = token,
            activeNetwork = ActiveNetworkProvider { NetworkKind.Cellular },
        )
        val error = networkError { blocked.execute(HttpRequest.json(HttpMethod.POST, "/api/x", "{}")) }
        assertEquals(NetworkError.LocalHttpNotAllowedOnThisNetwork, error)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `una escritura sin token no lleva Authorization`() {
        routes["/api/x"] = { e -> respond(e, 200) }
        transport(withToken = false).execute(HttpRequest.json(HttpMethod.POST, "/api/x", "{}"))
        assertNull(seen.single().headers["authorization"])
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0), contentType: String? = null) {
        try {
            if (contentType != null) exchange.responseHeaders.add("Content-Type", contentType)
            if (body.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        } catch (ignored: IOException) {
            // El cliente cortó a propósito (límite de bytes): no es un fallo de la prueba.
        } finally {
            exchange.close()
        }
    }
}
