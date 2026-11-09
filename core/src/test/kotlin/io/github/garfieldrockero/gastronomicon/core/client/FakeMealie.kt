package io.github.garfieldrockero.gastronomicon.core.client

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
 * Servidor de pruebas sobre el HttpServer del JDK (cero dependencias) que
 * anota cada petición recibida. No es una clase de prueba: la usan las de
 * escritura, importar y subir imagen del cliente.
 */
internal class FakeMealie {

    class Seen(
        val method: String,
        val path: String,
        val query: String?,
        /** Cabeceras con el nombre en minúsculas. */
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    val token = ApiToken("tok-secreto-ABC123xyz")

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = ConcurrentHashMap<String, (Seen, HttpExchange) -> Unit>()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

    // Se guarda al construir: alguna prueba apaga el servidor y luego crea el
    // cliente para comprobar qué pasa con una dirección cerrada.
    val port: Int = server.address.port

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
                headers = exchange.requestHeaders.entries
                    .associate { it.key.lowercase() to (it.value.firstOrNull() ?: "") },
                body = body,
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

    /** Ruta que contesta siempre lo mismo. */
    fun fixed(method: String, path: String, status: Int, body: String = "", contentType: String = "application/json") {
        route(method, path) { _, exchange -> reply(exchange, status, body.toByteArray(), contentType) }
    }

    fun requestsTo(method: String, path: String): List<Seen> =
        synchronized(seen) { seen.filter { it.method == method && it.path == path } }

    fun stop() {
        runCatching { server.stop(0) }
    }

    /**
     * Cliente con el token y un límite de respuesta para todos los transportes.
     * Los plazos se bajan en las pruebas que simulan un servidor que contesta
     * tarde, para no esperar los cinco segundos.
     */
    fun client(
        maxBytes: Long = 1_000_000,
        readTimeoutMs: Int = 5_000,
        totalTimeoutMs: Long = 60_000L,
    ): MealieClient {
        val base = URI("http://127.0.0.1:$port")
        fun transport(withToken: Boolean) = UrlConnectionTransport(
            baseUri = base,
            token = if (withToken) token else null,
            connectTimeoutMs = 5_000,
            readTimeoutMs = readTimeoutMs,
            maxResponseBytes = maxBytes,
            totalTimeoutMs = totalTimeoutMs,
        )
        return MealieClient(transport = transport(true), anonymousTransport = transport(false))
    }

    companion object {
        // ----------------------------------------- imágenes bien formadas
        //
        // Las pruebas de escritura y de subida necesitan imágenes que el
        // detector de metadatos acepte: desde que falla cerrado, bytes
        // truncados o con basura detrás del fin ya no sirven.

        /** Byte sin signo pasado a Byte, para escribir fixtures legibles. */
        fun rawBytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

        /** Chunk PNG sin la firma: longitud, tipo, datos y un CRC que el detector no mira. */
        fun pngChunk(type: String, data: ByteArray): ByteArray {
            val size = data.size
            return rawBytes(size ushr 24, size ushr 16, size ushr 8, size) +
                type.toByteArray(Charsets.US_ASCII) + data + ByteArray(4)
        }

        /** PNG válido: firma, IHDR, un IDAT del tamaño pedido e IEND, nada detrás. */
        fun pngWithData(dataSize: Int): ByteArray =
            PNG_SIGNATURE + pngChunk("IHDR", ByteArray(13)) +
                pngChunk("IDAT", ByteArray(dataSize)) + pngChunk("IEND", ByteArray(0))

        /** PNG válido de exactamente [total] bytes. */
        fun pngOfSize(total: Int): ByteArray {
            val overhead = pngWithData(0).size
            require(total >= overhead) { "El PNG mínimo ya ocupa $overhead bytes." }
            return pngWithData(total - overhead)
        }

        /** PNG mínimo válido, sin metadatos. */
        fun minimalPng(): ByteArray = pngWithData(0)

        /** JPEG mínimo y bien formado: SOI, APP0, SOS, un byte de datos y EOI. */
        fun minimalJpeg(): ByteArray = jpegWithSegment(0xE0, ByteArray(14))

        /** JPEG bien formado con un APP1 de EXIF: el detector tiene que rechazarlo. */
        fun jpegWithExif(): ByteArray =
            jpegWithSegment(0xE1, rawBytes('E'.code, 'x'.code, 'i'.code, 'f'.code, 0, 0, 0, 0))

        private fun jpegWithSegment(marker: Int, payload: ByteArray): ByteArray {
            val length = payload.size + 2
            return rawBytes(0xFF, 0xD8, 0xFF, marker, (length shr 8) and 0xFF, length and 0xFF) +
                payload +
                // SOS de una componente con un byte de datos detrás y EOI.
                rawBytes(0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0x00, 0xFF, 0xD9)
        }

        /** Chunk WebP con el relleno a par que exige RIFF. */
        fun webpChunk(fourcc: String, data: ByteArray): ByteArray {
            val size = data.size
            val padding = if (size % 2 == 1) rawBytes(0) else ByteArray(0)
            return fourcc.toByteArray(Charsets.US_ASCII) +
                rawBytes(size and 0xFF, (size ushr 8) and 0xFF, (size ushr 16) and 0xFF, (size ushr 24) and 0xFF) +
                data + padding
        }

        /** WebP válido: el tamaño RIFF cuenta «WEBP» y todos los chunks. */
        fun webp(vararg chunks: ByteArray): ByteArray {
            val data = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
            val size = 4 + data.size
            return "RIFF".toByteArray(Charsets.US_ASCII) +
                rawBytes(size and 0xFF, (size ushr 8) and 0xFF, (size ushr 16) and 0xFF, (size ushr 24) and 0xFF) +
                "WEBP".toByteArray(Charsets.US_ASCII) +
                data
        }

        /** WebP mínimo válido, sin metadatos. */
        fun minimalWebp(): ByteArray = webp(webpChunk("VP8 ", rawBytes(1, 2, 3, 4, 5)))

        private val PNG_SIGNATURE = rawBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

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
