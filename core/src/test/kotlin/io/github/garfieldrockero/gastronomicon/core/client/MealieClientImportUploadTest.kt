package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.recipes.Organizer
import io.github.garfieldrockero.gastronomicon.core.recipes.OrganizerKind
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Importar por URL, subir imagen y listas de etiquetas y categorías. La URL la
 * descarga el servidor; la imagen y las listas son datos de fuera que van por
 * el mismo transporte que lleva el token.
 */
class MealieClientImportUploadTest {

    private lateinit var mealie: FakeMealie

    private val uuid1 = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val uuid2 = "1f8fad5b-d9cb-469f-a165-70867728950e"

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 1, 2, 3)
    private val webp = "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBP".toByteArray() + byteArrayOf(1, 2, 3, 4)

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun latin1(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    // --------------------------------------------------------------- importar

    @Test
    fun `importar manda la URL al servidor y devuelve el slug`() {
        mealie.fixed("POST", "/api/recipes/create/url", 201, "\"tarta-importada\"")
        val result = mealie.client().importFromUrl("https://ejemplo.org/recetas/tarta?x=1")
        assertEquals(ApiResult.Success("tarta-importada"), result)
        val request = mealie.seen.single()
        val body = CoreJson.json.parseToJsonElement(String(request.body, Charsets.UTF_8)) as JsonObject
        assertEquals(
            mapOf(
                "url" to JsonPrimitive("https://ejemplo.org/recetas/tarta?x=1"),
                "includeTags" to JsonPrimitive(false),
                "includeCategories" to JsonPrimitive(false),
            ),
            body.toMap(),
        )
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `importar puede pedir etiquetas y categorias`() {
        mealie.fixed("POST", "/api/recipes/create/url", 201, "\"x\"")
        mealie.client().importFromUrl("https://ejemplo.org/r", includeTags = true, includeCategories = true)
        val body = CoreJson.json.parseToJsonElement(String(mealie.seen.single().body, Charsets.UTF_8)) as JsonObject
        assertEquals(JsonPrimitive(true), body["includeTags"])
        assertEquals(JsonPrimitive(true), body["includeCategories"])
    }

    @Test
    fun `solo se importan URL https validas y el resto no sale de la app`() {
        val bad = listOf(
            "", "   ", "http://ejemplo.org/r", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,x",
            "ftp://ejemplo.org/r", "//ejemplo.org/r", "ejemplo.org/r", "https://usuario:clave@ejemplo.org/r",
            "https://ejemplo.org/a b", "https://ejemplo.org/\r\nX: y", "https://", "https:///x",
            "https://ejemplo.org/" + "a".repeat(2_100), "https://ejemplo.org/ñ",
        )
        for (url in bad) {
            assertEquals(url.take(30), ApiResult.InputRejected(InputProblem.InvalidUrl), mealie.client().importFromUrl(url))
        }
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `importar tolera mayusculas en el esquema y espacios en los extremos`() {
        mealie.fixed("POST", "/api/recipes/create/url", 201, "\"x\"")
        assertEquals(ApiResult.Success("x"), mealie.client().importFromUrl("  HTTPS://Ejemplo.org/r  "))
    }

    @Test
    fun `importar traduce los errores y rechaza un slug raro`() {
        mealie.fixed("POST", "/api/recipes/create/url", 400, "{\"detail\":{\"message\":\"SECRETO-DEL-SERVIDOR\"}}")
        val rejected = mealie.client().importFromUrl("https://ejemplo.org/r")
        assertEquals(ApiResult.Rejected(400), rejected)
        assertFalse(rejected.toString().contains("SECRETO"))

        mealie.fixed("POST", "/api/recipes/create/url", 401)
        assertEquals(ApiResult.Unauthorized, mealie.client().importFromUrl("https://ejemplo.org/r"))
        mealie.fixed("POST", "/api/recipes/create/url", 502)
        assertEquals(ApiResult.Failure(ConnectionProblem.ServerError), mealie.client().importFromUrl("https://ejemplo.org/r"))
        mealie.fixed("POST", "/api/recipes/create/url", 201, "\"../x\"")
        assertEquals(ApiResult.Failure(ConnectionProblem.Unknown), mealie.client().importFromUrl("https://ejemplo.org/r"))
        mealie.fixed("POST", "/api/recipes/create/url", 422, """{"detail":[{"loc":["body","url"],"msg":"URL no válida"}]}""")
        assertEquals(
            ApiResult.Invalid(listOf(ValidationDetail(null, null, "URL no válida"))),
            mealie.client().importFromUrl("https://ejemplo.org/r"),
        )
    }

    // ------------------------------------------------------------------ imagen

    @Test
    fun `subir una imagen manda un multipart bien formado`() {
        mealie.fixed("PUT", "/api/recipes/tarta/image", 200, "{\"image\":\"abc\"}")
        val result = mealie.client().uploadImage("tarta", png, "png")
        assertEquals(ApiResult.Success(Unit), result)
        val request = mealie.seen.single()
        assertEquals("PUT", request.method)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
        val contentType = request.headers["content-type"]!!
        val boundary = Regex("^multipart/form-data; boundary=([A-Za-z0-9]{16,70})$").matchEntire(contentType)!!.groupValues[1]
        val expected = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"image.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n" +
            latin1(png) + "\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"extension\"\r\n\r\n" +
            "png\r\n" +
            "--$boundary--\r\n"
        assertEquals(expected, latin1(request.body))
    }

    @Test
    fun `cada formato permitido sube con su extension y su tipo`() {
        mealie.fixed("PUT", "/api/recipes/tarta/image", 200, "{}")
        val cases = listOf(Triple("webp", webp, "image/webp"), Triple("jpeg", jpeg, "image/jpeg"), Triple("png", png, "image/png"))
        for ((extension, bytes, mime) in cases) {
            mealie.seen.clear()
            assertEquals(extension, ApiResult.Success(Unit), mealie.client().uploadImage("tarta", bytes, extension))
            val body = latin1(mealie.seen.single().body)
            assertTrue(body, body.contains("filename=\"image.$extension\""))
            assertTrue(body, body.contains("Content-Type: $mime\r\n"))
            assertTrue(body, body.contains("name=\"extension\"\r\n\r\n$extension\r\n"))
        }
        // La extension se admite en mayusculas y con espacios.
        assertEquals(ApiResult.Success(Unit), mealie.client().uploadImage("tarta", png, " PNG "))
    }

    @Test
    fun `solo webp jpeg y png y los bytes tienen que ser de ese formato`() {
        val unsupported = listOf("gif", "svg", "jpg", "bmp", "", "png\r\nX: y", "../png", "png;x", "image/png", ".png")
        for (extension in unsupported) {
            assertEquals(extension, ApiResult.InputRejected(InputProblem.UnsupportedImageType), mealie.client().uploadImage("tarta", png, extension))
        }
        val notImages = listOf(
            "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),
            "<html><script>alert(1)</script></html>".toByteArray(),
            "GIF89a....".toByteArray(),
            "RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WAVE".toByteArray() + byteArrayOf(1, 2),
            byteArrayOf(0x89.toByte(), 0x50, 0x4E),
            byteArrayOf(0),
        )
        for (bytes in notImages) {
            assertEquals(ApiResult.InputRejected(InputProblem.InvalidImage), mealie.client().uploadImage("tarta", bytes, "png"))
        }
        // Una imagen real con la extension de otro formato tampoco se envia.
        assertEquals(ApiResult.InputRejected(InputProblem.InvalidImage), mealie.client().uploadImage("tarta", png, "webp"))
        assertEquals(ApiResult.InputRejected(InputProblem.InvalidImage), mealie.client().uploadImage("tarta", jpeg, "png"))
        assertEquals(ApiResult.InputRejected(InputProblem.InvalidImage), mealie.client().uploadImage("tarta", ByteArray(0), "png"))
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `una imagen por encima del tope no sale de la app`() {
        val huge = png + ByteArray(MealieClient.MAX_IMAGE_BYTES.toInt())
        assertEquals(ApiResult.InputRejected(InputProblem.ImageTooLarge), mealie.client().uploadImage("tarta", huge, "png"))
        assertTrue(mealie.seen.isEmpty())
        // Justo en el tope sí se envia.
        mealie.fixed("PUT", "/api/recipes/tarta/image", 200, "{}")
        val atLimit = png + ByteArray(MealieClient.MAX_IMAGE_BYTES.toInt() - png.size)
        assertEquals(ApiResult.Success(Unit), mealie.client().uploadImage("tarta", atLimit, "png"))
    }

    @Test
    fun `subir imagen traduce los errores aunque el servidor conteste con la subida a medias`() {
        val errors = mapOf(
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            404 to ApiResult.NotFound,
            413 to ApiResult.Rejected(413),
            500 to ApiResult.Failure(ConnectionProblem.ServerError),
        )
        for ((status, result) in errors) {
            mealie.fixed("PUT", "/api/recipes/tarta/image", status, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
            val actual = mealie.client().uploadImage("tarta", png + ByteArray(30_000), "png")
            assertEquals(status.toString(), result, actual)
            assertFalse(actual.toString().contains("SECRETO"))
        }
    }

    // ---------------------------------------------------------- organizadores

    private fun page(vararg items: String, total: Int = items.size): String =
        """{"page":1,"per_page":100,"total":$total,"total_pages":1,"items":[${items.joinToString(",")}],"next":"https://otro.example/x","previous":null}"""

    @Test
    fun `las categorias se piden por nombre y llegan saneadas`() {
        mealie.fixed(
            "GET", "/api/organizers/categories", 200,
            page(
                """{"id":"$uuid1","name":"  Postres ‮ ","slug":"postres","recipeCount":3,"groupId":"g"}""",
                """{"id":"${uuid2.uppercase()}","name":"<b>Cenas</b>","slug":"cenas"}""",
            ),
        )
        val result = mealie.client().listCategories() as ApiResult.Success<Page<Organizer>>
        assertEquals(
            listOf(Organizer(uuid1, "Postres", "postres"), Organizer(uuid2, "<b>Cenas</b>", "cenas")),
            result.value.items,
        )
        assertEquals(2L, result.value.total)
        val request = mealie.seen.single()
        assertEquals("page=1&perPage=100&orderBy=name&orderDirection=asc", request.query)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `las etiquetas usan su ruta y la busqueda va codificada`() {
        mealie.fixed("GET", "/api/organizers/tags", 200, page())
        mealie.client().listTags(page = 3, perPage = 20, search = "a b&c=d")
        assertEquals("page=3&perPage=20&search=a%20b%26c%3Dd&orderBy=name&orderDirection=asc", mealie.seen.single().query)
    }

    @Test
    fun `pagina y tamaño se acotan y las busquedas vacias no se mandan`() {
        mealie.fixed("GET", "/api/organizers/tags", 200, page())
        mealie.client().listOrganizers(OrganizerKind.Tags, page = -5, perPage = 100_000, search = " \u0007 ")
        assertEquals("page=1&perPage=100&orderBy=name&orderDirection=asc", mealie.seen.single().query)
        mealie.seen.clear()
        mealie.client().listOrganizers(OrganizerKind.Tags, page = 2, perPage = 0)
        assertEquals("page=2&perPage=1&orderBy=name&orderDirection=asc", mealie.seen.single().query)
    }

    @Test
    fun `se descartan las entradas sin id slug o nombre validos`() {
        mealie.fixed(
            "GET", "/api/organizers/tags", 200,
            page(
                """{"id":"$uuid1","name":"Bien","slug":"bien"}""",
                """{"id":"no-uuid","name":"Mal id","slug":"x"}""",
                """{"id":"$uuid2","name":"Mal slug","slug":"../x"}""",
                """{"id":"$uuid2","name":"   ","slug":"y"}""",
                """{"id":"$uuid2","slug":"z"}""",
                """{"name":"Sin id","slug":"w"}""",
                "null",
                """{"id":"$uuid2","name":"Tambien bien","slug":"tambien-bien"}""",
            ),
        )
        val result = mealie.client().listTags() as ApiResult.Success<Page<Organizer>>
        assertEquals(listOf("Bien", "Tambien bien"), result.value.items.map { it.name })
    }

    @Test
    fun `una pagina con mas de cien elementos se corta a cien`() {
        val items = (1..250).map { """{"id":"$uuid1","name":"t$it","slug":"t$it"}""" }.toTypedArray()
        mealie.fixed("GET", "/api/organizers/tags", 200, page(*items))
        val result = mealie.client().listTags() as ApiResult.Success<Page<Organizer>>
        assertEquals(100, result.value.items.size)
    }

    @Test
    fun `next del servidor se ignora y la paginacion se calcula`() {
        mealie.fixed("GET", "/api/organizers/tags", 200, page("""{"id":"$uuid1","name":"a","slug":"a"}""", total = 250))
        val result = mealie.client().listTags() as ApiResult.Success<Page<Organizer>>
        assertEquals(3, result.value.totalPages)
        assertEquals(2, result.value.nextPage)
        // Solo se pidió al servidor configurado, no a la URL de «next».
        assertEquals(1, mealie.seen.size)
    }

    @Test
    fun `las listas traducen errores y respuestas hostiles`() {
        mealie.fixed("GET", "/api/organizers/tags", 401)
        assertEquals(ApiResult.Unauthorized, mealie.client().listTags())
        mealie.fixed("GET", "/api/organizers/tags", 403)
        assertEquals(ApiResult.Forbidden, mealie.client().listTags())
        mealie.fixed("GET", "/api/organizers/tags", 500)
        assertEquals(ApiResult.Failure(ConnectionProblem.ServerError), mealie.client().listTags())
        for (body in listOf("", "[]", "no es json", "{\"items\":\"x\"}", "{\"items\":{\"a\":1}}", "[".repeat(100_000))) {
            mealie.fixed("GET", "/api/organizers/tags", 200, body)
            val result = mealie.client().listTags()
            assertEquals(body.take(15), ApiResult.Failure(ConnectionProblem.Unknown), result)
            assertFalse(result.toString().contains(mealie.token.value))
        }
        mealie.fixed("GET", "/api/organizers/tags", 200, "x".repeat(50_000))
        assertEquals(ApiResult.Failure(ConnectionProblem.Unknown), mealie.client(maxBytes = 1_000).listTags())
    }
}
