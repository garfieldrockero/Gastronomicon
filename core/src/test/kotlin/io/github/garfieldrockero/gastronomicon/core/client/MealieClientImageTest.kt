package io.github.garfieldrockero.gastronomicon.core.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageSize
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Pruebas de [MealieClient.fetchImage] contra el HttpServer del JDK. Son de
 * frontera: la imagen viene de fuera y por el mismo transporte que lleva el token.
 */
class MealieClientImageTest {

    private val token = ApiToken("tok-secreto-ABC123xyz")
    private val recipeId = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val imagePath = "/api/media/recipes/$recipeId/images/min-original.webp"

    private class Seen(val path: String, val authorization: String?, val accept: String?)

    private lateinit var server: HttpServer
    private lateinit var otherServer: HttpServer
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    private val seenByOther: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen.add(
                Seen(
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Accept"),
                ),
            )
            val handler: (HttpExchange) -> Unit = routes[exchange.requestURI.path] ?: { e -> respond(e, 404) }
            handler(exchange)
        }
        server.start()
        otherServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        otherServer.createContext("/") { exchange ->
            seenByOther.add(exchange.requestURI.path)
            respond(exchange, 200, WEBP, "image/webp")
        }
        otherServer.start()
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
        runCatching { otherServer.stop(0) }
    }

    // ----------------------------------------------------------------- éxito

    @Test
    fun `una imagen valida llega con sus bytes y su tipo`() {
        val cases = listOf(
            Triple("image/webp", WEBP, ImageType.Webp),
            Triple("image/jpeg", JPEG, ImageType.Jpeg),
            Triple("image/png", PNG, ImageType.Png),
        )
        for ((contentType, bytes, type) in cases) {
            routes[imagePath] = { e -> respond(e, 200, bytes, contentType) }
            val result = client().fetchImage(recipeId)
            val success = result as ImageResult.Success
            assertEquals(type, success.type)
            assertArrayEquals(bytes, success.bytes)
        }
    }

    @Test
    fun `la peticion lleva el token y pide solo imagenes`() {
        routes[imagePath] = { e -> respond(e, 200, WEBP, "image/webp") }
        client().fetchImage(recipeId)
        assertEquals(imagePath, seen[0].path)
        assertEquals("Bearer tok-secreto-ABC123xyz", seen[0].authorization)
        val accept = seen[0].accept!!
        assertTrue(accept, accept.contains("image/webp"))
        assertFalse(accept, accept.contains("json"))
    }

    @Test
    fun `el tamaño elige el fichero y el id se pasa a minusculas`() {
        val path = "/api/media/recipes/$recipeId/images/original.webp"
        routes[path] = { e -> respond(e, 200, WEBP, "image/webp") }
        assertTrue(client().fetchImage(recipeId.uppercase(), ImageSize.Original) is ImageResult.Success)
        assertEquals(path, seen[0].path)
    }

    @Test
    fun `el tipo se compara sin mayusculas y con parametros`() {
        for (header in listOf("IMAGE/WEBP", "image/webp; charset=binary", " image/webp ", "Image/Webp;q=1")) {
            routes[imagePath] = { e -> respond(e, 200, WEBP, header) }
            assertTrue(header, client().fetchImage(recipeId) is ImageResult.Success)
        }
    }

    // ---------------------------------------------------------- id no válido

    @Test
    fun `un id que no es un UUID no sale de la app`() {
        for (bad in listOf(null, "", "tortilla", "../x", "$recipeId/../x", " $recipeId", "$recipeId\n")) {
            assertEquals(bad, ImageResult.NotFound, client().fetchImage(bad))
        }
        assertTrue("no se envió ninguna petición", seen.isEmpty())
    }

    // -------------------------------------------------------- códigos de error

    @Test
    fun `404 es NotFound`() {
        // Sin ruta registrada, el servidor de pruebas contesta 404.
        assertEquals(ImageResult.NotFound, client().fetchImage(recipeId))
    }

    @Test
    fun `401 y 403 son Unauthorized`() {
        for (status in listOf(401, 403)) {
            routes[imagePath] = { e -> respond(e, status, "{\"detail\":\"${token.value}\"}".toByteArray(), "application/json") }
            assertEquals(status.toString(), ImageResult.Unauthorized, client().fetchImage(recipeId))
        }
    }

    @Test
    fun `5xx y otros codigos son Failure sin tocar el cuerpo`() {
        routes[imagePath] = { e -> respond(e, 500, "x".toByteArray(), "text/plain") }
        assertEquals(ImageResult.Failure(ConnectionProblem.ServerError), client().fetchImage(recipeId))
        routes[imagePath] = { e -> respond(e, 418, "x".toByteArray(), "text/plain") }
        assertEquals(ImageResult.Failure(ConnectionProblem.Unknown), client().fetchImage(recipeId))
    }

    @Test
    fun `sin servidor es Failure y el resultado no lleva el token`() {
        server.stop(0)
        val result = client().fetchImage(recipeId)
        assertEquals(ImageResult.Failure(ConnectionProblem.ServerNotResponding), result)
        assertNoSecret(result)
    }

    // ---------------------------------------------------- tipo y contenido

    @Test
    fun `los tipos no permitidos se rechazan aunque el cuerpo parezca una imagen`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()
        val cases = listOf(
            "image/svg+xml" to svg,
            "image/svg+xml" to PNG,
            "image/gif" to "GIF89a....".toByteArray(),
            "image/avif" to WEBP,
            "image/webp-x" to WEBP,
            "image/*" to WEBP,
            "text/html" to "<html></html>".toByteArray(),
            "application/octet-stream" to PNG,
            "application/json" to "{}".toByteArray(),
            "" to PNG,
        )
        for ((contentType, body) in cases) {
            routes[imagePath] = { e -> respond(e, 200, body, contentType) }
            assertEquals(contentType, ImageResult.InvalidImage, client().fetchImage(recipeId))
        }
    }

    @Test
    fun `sin Content-Type se rechaza`() {
        routes[imagePath] = { e -> respond(e, 200, PNG, null) }
        assertEquals(ImageResult.InvalidImage, client().fetchImage(recipeId))
    }

    @Test
    fun `un tipo permitido con un cuerpo que no es esa imagen se rechaza`() {
        val cases = listOf(
            "image/png" to JPEG,
            "image/jpeg" to WEBP,
            "image/webp" to PNG,
            "image/png" to "<svg><script>alert(1)</script></svg>".toByteArray(),
            "image/webp" to "<html>proxy</html>".toByteArray(),
            // RIFF sin «WEBP»: un WAV o un AVI.
            "image/webp" to ("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + ByteArray(8)),
            // Cabecera cortada.
            "image/png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E),
        )
        for ((contentType, body) in cases) {
            routes[imagePath] = { e -> respond(e, 200, body, contentType) }
            assertEquals(contentType, ImageResult.InvalidImage, client().fetchImage(recipeId))
        }
    }

    @Test
    fun `un cuerpo vacio se rechaza`() {
        routes[imagePath] = { e -> respond(e, 200, ByteArray(0), "image/webp") }
        assertEquals(ImageResult.InvalidImage, client().fetchImage(recipeId))
    }

    // ------------------------------------------------------------- tamaño

    @Test
    fun `una imagen de mas de 5 MiB es TooLarge aunque el transporte la deje pasar`() {
        val big = WEBP + ByteArray((MealieClient.MAX_IMAGE_BYTES - WEBP.size + 1).toInt())
        assertEquals(MealieClient.MAX_IMAGE_BYTES + 1, big.size.toLong())
        routes[imagePath] = { e -> respond(e, 200, big, "image/webp") }
        assertEquals(ImageResult.TooLarge, client(imageMaxBytes = 8L * 1024 * 1024).fetchImage(recipeId))
    }

    @Test
    fun `una imagen justo en el limite se acepta`() {
        val exact = WEBP + ByteArray((MealieClient.MAX_IMAGE_BYTES - WEBP.size).toInt())
        routes[imagePath] = { e -> respond(e, 200, exact, "image/webp") }
        val result = client(imageMaxBytes = 8L * 1024 * 1024).fetchImage(recipeId)
        assertEquals(MealieClient.MAX_IMAGE_BYTES, (result as ImageResult.Success).bytes.size.toLong())
    }

    @Test
    fun `el limite del transporte tambien da TooLarge`() {
        val big = WEBP + ByteArray(50_000)
        routes[imagePath] = { e -> respond(e, 200, big, "image/webp") }
        assertEquals(ImageResult.TooLarge, client(imageMaxBytes = 10_000).fetchImage(recipeId))
    }

    @Test
    fun `el limite de la imagen es independiente del de las respuestas JSON`() {
        val medium = WEBP + ByteArray(50_000)
        routes[imagePath] = { e -> respond(e, 200, medium, "image/webp") }
        // El transporte JSON solo admite 10 kB, el de imágenes sí cabe.
        assertTrue(client(maxBytes = 10_000, imageMaxBytes = 1_000_000).fetchImage(recipeId) is ImageResult.Success)
    }

    // ------------------------------------------------------ redirecciones

    @Test
    fun `una redireccion a otro origen no recibe ni el token ni una peticion`() {
        routes[imagePath] = { e ->
            e.responseHeaders.add("Location", "http://127.0.0.1:${otherServer.address.port}$imagePath")
            respond(e, 302)
        }
        val result = client().fetchImage(recipeId)
        assertEquals(ImageResult.Failure(ConnectionProblem.Unknown), result)
        assertTrue(seenByOther.isEmpty())
        assertNoSecret(result)
    }

    // ----------------------------------------------------------- impresión

    @Test
    fun `Success no imprime los bytes ni el token`() {
        routes[imagePath] = { e -> respond(e, 200, WEBP, "image/webp") }
        val text = client().fetchImage(recipeId).toString()
        assertNotNull(text)
        assertTrue(text, text.contains("image/webp"))
        assertFalse(text, text.contains("["))
        assertNoSecret(ImageResult.Success(WEBP, ImageType.Webp))
    }

    // -------------------------------------------------------------- ayudas

    private fun client(maxBytes: Long = 1_000_000, imageMaxBytes: Long = maxBytes): MealieClient {
        val base = URI("http://127.0.0.1:${server.address.port}")
        fun transport(withToken: Boolean, limit: Long) = UrlConnectionTransport(
            baseUri = base,
            token = if (withToken) token else null,
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            maxResponseBytes = limit,
        )
        return MealieClient(
            transport = transport(true, maxBytes),
            anonymousTransport = transport(false, maxBytes),
            imageTransport = transport(true, imageMaxBytes),
        )
    }

    private fun assertNoSecret(result: ImageResult) {
        val text = result.toString()
        assertFalse(text, text.contains(token.value))
        assertFalse(text, text.contains("127.0.0.1"))
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

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 1, 2, 3)
        val WEBP = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBP".toByteArray() + byteArrayOf(1, 2, 3, 4)
    }
}
