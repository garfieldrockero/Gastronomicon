package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * «No sé si se creó»: un plazo agotado después de enviar la petición no puede
 * contarse como «no se creó», porque la persona repetiría y acabaría con dos
 * recetas. También comprueba que crear e importar no reintentan solos y que el
 * plazo de la subida de imagen vence con un error tipado.
 */
class MealieClientMaybeCreatedTest {

    private lateinit var mealie: FakeMealie

    // Imagen bien formada: ImageMetadata falla cerrado y rechaza truncados.
    private val png = FakeMealie.minimalPng()

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun assertNoSecrets(result: Any) {
        val text = result.toString()
        assertFalse(text, text.contains(mealie.token.value))
        assertFalse(text, text.contains("127.0.0.1"))
        // MaybeCreated no puede llevar nada del cuerpo del servidor.
        assertFalse(text, text.contains("SECRETO-DEL-SERVIDOR"))
    }

    /** Handler que lee el cuerpo (llega entero) y tarda más que el plazo del cliente. */
    private fun slowRoute(method: String, path: String, status: Int, body: String) {
        mealie.route(method, path) { _, exchange ->
            Thread.sleep(800)
            runCatching { FakeMealie.reply(exchange, status, body.toByteArray()) }
        }
    }

    // ----------------------------------------------------------------- crear

    @Test
    fun `un plazo agotado al crear no afirma que no se creo y no reintenta`() {
        slowRoute("POST", "/api/recipes", 201, "\"tarta\"")
        val result = mealie.client(readTimeoutMs = 200).createRecipe("Tarta")
        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding), result)
        // Una sola petición: repetir a ciegas dejaría dos recetas.
        assertEquals(1, mealie.requestsTo("POST", "/api/recipes").size)
        assertNoSecrets(result)
    }

    @Test
    fun `un rechazo o un fallo antes de enviar no son ambiguos`() {
        mealie.fixed("POST", "/api/recipes", 413, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
        val rejected = mealie.client().createRecipe("Tarta")
        assertEquals(ApiResult.Rejected(413), rejected)
        assertNoSecrets(rejected)

        mealie.fixed("POST", "/api/recipes", 401, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
        assertEquals(ApiResult.Unauthorized, mealie.client().createRecipe("Tarta"))
    }

    @Test
    fun `un 5xx al crear es ambiguo y no se reintenta`() {
        for (status in listOf(500, 502, 503, 504)) {
            mealie.seen.clear()
            mealie.fixed("POST", "/api/recipes", status, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
            val result = mealie.client().createRecipe("Tarta")
            assertEquals(status.toString(), ApiResult.MaybeCreated(ConnectionProblem.ServerError), result)
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", "/api/recipes").size)
            assertNoSecrets(result)
        }
    }

    @Test
    fun `un 2xx con slug ilegible es ambiguo`() {
        mealie.fixed("POST", "/api/recipes", 201, "{\"slug\":\"SECRETO-DEL-SERVIDOR\"}")
        val result = mealie.client().createRecipe("Tarta")
        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.Unknown), result)
        assertEquals(1, mealie.requestsTo("POST", "/api/recipes").size)
        assertNoSecrets(result)
    }

    @Test
    fun `una respuesta demasiado grande al crear es ambigua`() {
        mealie.fixed("POST", "/api/recipes", 201, "x".repeat(50_000))
        val result = mealie.client(maxBytes = 1_000).createRecipe("Tarta")
        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.Unknown), result)
        assertEquals(1, mealie.requestsTo("POST", "/api/recipes").size)
        assertNoSecrets(result)
    }

    @Test
    fun `sin servidor el fallo sigue siendo un fallo y no un quiza`() {
        mealie.stop()
        val result = mealie.client().createRecipe("Tarta")
        assertTrue(result.toString(), result is ApiResult.Failure)
        assertNoSecrets(result)
    }

    // --------------------------------------------------------------- importar

    @Test
    fun `un plazo agotado al importar tampoco afirma que no se creo`() {
        slowRoute("POST", "/api/recipes/create/url", 201, "\"importada\"")
        val result = mealie.client(readTimeoutMs = 200).importFromUrl("https://ejemplo.org/r")
        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding), result)
        assertEquals(1, mealie.requestsTo("POST", "/api/recipes/create/url").size)
        assertNoSecrets(result)
    }

    @Test
    fun `un 5xx al importar tambien es ambiguo y no se reintenta`() {
        for (status in listOf(500, 502, 503, 504)) {
            mealie.seen.clear()
            mealie.fixed("POST", "/api/recipes/create/url", status)
            val result = mealie.client().importFromUrl("https://ejemplo.org/r")
            assertEquals(status.toString(), ApiResult.MaybeCreated(ConnectionProblem.ServerError), result)
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", "/api/recipes/create/url").size)
            assertNoSecrets(result)
        }
    }

    @Test
    fun `una respuesta demasiado grande al importar es ambigua`() {
        mealie.fixed("POST", "/api/recipes/create/url", 201, "x".repeat(50_000))
        val result = mealie.client(maxBytes = 1_000).importFromUrl("https://ejemplo.org/r")
        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.Unknown), result)
        assertEquals(1, mealie.requestsTo("POST", "/api/recipes/create/url").size)
        assertNoSecrets(result)
    }

    @Test
    fun `importar una URL mala no envia nada y no es ambiguo`() {
        val result = mealie.client().importFromUrl("http://ejemplo.org/r")
        assertEquals(ApiResult.InputRejected(InputProblem.InvalidUrl), result)
        assertTrue(mealie.seen.isEmpty())
    }

    // ----------------------------------------------------------------- imagen

    @Test
    fun `la subida de imagen vence con un error tipado y sin colgarse`() {
        slowRoute("PUT", "/api/recipes/tarta/image", 200, "{}")
        val result = mealie.client(readTimeoutMs = 200).uploadImage("tarta", png, "png")
        assertEquals(ApiResult.Failure(ConnectionProblem.ServerNotResponding), result)
        assertNoSecrets(result)
    }

    @Test
    fun `la subida tiene un plazo total nombrado y positivo`() {
        assertTrue(MealieClient.UPLOAD_TOTAL_TIMEOUT_MS > 0)
    }

    @Test
    fun `una imagen con exif no se sube`() {
        val result = mealie.client().uploadImage("tarta", FakeMealie.jpegWithExif(), "jpeg")
        assertEquals(ApiResult.InputRejected(InputProblem.ImageHasMetadata), result)
        assertTrue(mealie.seen.isEmpty())
    }
}
