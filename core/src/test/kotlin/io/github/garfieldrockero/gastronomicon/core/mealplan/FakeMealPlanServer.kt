package io.github.garfieldrockero.gastronomicon.core.mealplan

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Servidor de pruebas sobre el `HttpServer` del JDK (cero dependencias) que
 * anota cada petición recibida. No es una clase de prueba: la usan las pruebas
 * del cliente del planificador.
 */
internal class FakeMealPlanServer {

    class Seen(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String,
    )

    val token = ApiToken("tok-secreto-ABC123xyz")

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = ConcurrentHashMap<String, (Seen, HttpExchange) -> Unit>()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    val port: Int get() = server.address.port

    init {
        server.createContext("/") { exchange ->
            val body = try {
                exchange.requestBody.readBytes()
            } catch (ignored: IOException) {
                ByteArray(0)
            }
            val request = Seen(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                body = body.toString(Charsets.UTF_8),
            )
            seen.add(request)
            val handler = routes["${request.method} ${request.path}"]
            if (handler == null) reply(exchange, 404) else handler(request, exchange)
        }
        server.start()
    }

    fun route(method: String, path: String, handler: (Seen, HttpExchange) -> Unit) {
        routes["$method $path"] = handler
    }

    fun fixed(method: String, path: String, status: Int, body: String = "", contentType: String = "application/json") {
        route(method, path) { _, exchange -> reply(exchange, status, body.toByteArray(), contentType) }
    }

    fun requestsTo(method: String, path: String): List<Seen> =
        synchronized(seen) { seen.filter { it.method == method && it.path == path } }

    /**
     * Cliente apuntando al servidor de pruebas, con el token y un límite de
     * respuesta configurable. Los plazos se bajan en las pruebas que simulan un
     * servidor que contesta tarde.
     */
    fun client(
        maxBytes: Long = 1_000_000,
        readTimeoutMs: Int = 5_000,
        totalTimeoutMs: Long = 60_000L,
    ): MealPlanClient = MealPlanClient(
        UrlConnectionTransport(
            baseUri = URI("http://127.0.0.1:$port"),
            token = token,
            connectTimeoutMs = 5_000,
            readTimeoutMs = readTimeoutMs,
            maxResponseBytes = maxBytes,
            totalTimeoutMs = totalTimeoutMs,
        ),
    )

    fun stop() {
        runCatching { server.stop(0) }
    }

    companion object {
        fun reply(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0), contentType: String? = null) {
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
}
