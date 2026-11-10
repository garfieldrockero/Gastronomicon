package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Importar con la IA de Mealie (`POST /api/recipes/create/ai`).
 *
 * Lo que se comprueba aquí es el contrato con el servidor y con la persona: el
 * cuerpo multipart lleva `content`, `url`, `createNewOrganizers` e `images` (y
 * **no** `translateLanguage`), la petición no se repite nunca, cualquier duda
 * después de enviar es «quizá creada» —también el plazo, que aquí es de minutos
 * porque la IA tarda— y ni el texto, ni el enlace, ni las fotos, ni el token
 * aparecen en ningún resultado.
 *
 * La validación de la entrada (topes, tipos, metadatos) está en
 * [MealieClientAiLimitsTest].
 */
class MealieClientAiTest {

    private lateinit var mealie: FakeMealie

    private val png = FakeMealie.minimalPng()
    private val jpeg = FakeMealie.minimalJpeg()

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun latin1(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    /** El delimitador del `Content-Type`, que es el que estructura el cuerpo. */
    private fun boundaryOf(contentType: String): String =
        Regex("^multipart/form-data; boundary=([A-Za-z0-9]{16,70})$")
            .matchEntire(contentType)!!.groupValues[1]

    // ----------------------------------------------------------------- envío

    @Test
    fun `texto enlace y foto viajan en un multipart con los campos del esquema`() {
        mealie.fixed("POST", PATH, 201, "\"tarta-con-ia\"")
        val content = "<html><body>Mezcla y hornea.</body></html>"
        val url = "https://www.youtube.com/watch?v=abc123"

        val result = mealie.client().createRecipeWithAi(content, url, listOf(AiImage(png, "png")))

        assertEquals(ApiResult.Success("tarta-con-ia"), result)
        val request = mealie.seen.single()
        assertEquals("POST", request.method)
        assertEquals(PATH, request.path)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
        val boundary = boundaryOf(request.headers["content-type"]!!)
        val expected = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"content\"\r\n\r\n" +
            content + "\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"url\"\r\n\r\n" +
            url + "\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"createNewOrganizers\"\r\n\r\n" +
            "false\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"images\"; filename=\"image-1.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n" +
            latin1(png) + "\r\n" +
            "--$boundary--\r\n"
        assertEquals(expected, latin1(request.body))
        // La app no traduce recetas: el campo existe en el esquema y no se manda.
        assertFalse(latin1(request.body).contains("translateLanguage"))
    }

    @Test
    fun `solo con fotos no se manda content ni url`() {
        mealie.fixed("POST", PATH, 201, "\"solo-fotos\"")
        assertEquals(
            ApiResult.Success("solo-fotos"),
            mealie.client().createRecipeWithAi(null, null, listOf(AiImage(jpeg, "jpeg"))),
        )
        val body = latin1(mealie.seen.single().body)
        assertFalse(body.contains("name=\"content\""))
        assertFalse(body.contains("name=\"url\""))
        assertTrue(body.contains("name=\"createNewOrganizers\"\r\n\r\nfalse\r\n"))
        assertTrue(body.contains("name=\"images\"; filename=\"image-1.jpeg\"\r\n"))
        assertTrue(body.contains("Content-Type: image/jpeg\r\n\r\n"))
    }

    @Test
    fun `solo con enlace no se manda content ni fotos`() {
        mealie.fixed("POST", PATH, 201, "\"solo-enlace\"")
        assertEquals(
            ApiResult.Success("solo-enlace"),
            mealie.client().createRecipeWithAi(null, "https://ejemplo.org/receta", emptyList()),
        )
        val body = latin1(mealie.seen.single().body)
        assertTrue(body.contains("name=\"url\"\r\n\r\nhttps://ejemplo.org/receta\r\n"))
        assertFalse(body.contains("name=\"content\""))
        assertFalse(body.contains("name=\"images\""))
    }

    @Test
    fun `las fotos se numeran y llevan su tipo real`() {
        mealie.fixed("POST", PATH, 201, "\"x\"")
        mealie.client().createRecipeWithAi(
            content = "Tarta",
            url = null,
            images = listOf(AiImage(png, "png"), AiImage(jpeg, "JPEG")),
        )
        val body = latin1(mealie.seen.single().body)
        assertTrue(body.contains("name=\"images\"; filename=\"image-1.png\"\r\nContent-Type: image/png\r\n"))
        // La extensión se admite en mayúsculas, como en la subida de imagen.
        assertTrue(body.contains("name=\"images\"; filename=\"image-2.jpeg\"\r\nContent-Type: image/jpeg\r\n"))
    }

    @Test
    fun `se puede pedir que la IA cree etiquetas y categorias`() {
        mealie.fixed("POST", PATH, 201, "\"x\"")
        mealie.client().createRecipeWithAi("Tarta", null, emptyList(), createNewOrganizers = true)
        val body = latin1(mealie.seen.single().body)
        assertTrue(body.contains("name=\"createNewOrganizers\"\r\n\r\ntrue\r\n"))
    }

    @Test
    fun `un texto o un enlace en blanco cuentan como no puestos`() {
        mealie.fixed("POST", PATH, 201, "\"x\"")
        assertEquals(
            ApiResult.Success("x"),
            mealie.client().createRecipeWithAi("   ", "  ", listOf(AiImage(png, "png"))),
        )
        val body = latin1(mealie.seen.single().body)
        assertFalse(body.contains("name=\"content\""))
        assertFalse(body.contains("name=\"url\""))
        assertTrue(body.contains("name=\"images\""))
    }

    @Test
    fun `el texto se manda tal cual, sin sanear ni recortar`() {
        mealie.fixed("POST", PATH, 201, "\"x\"")
        // Comillas, barras, tildes, un emoji y saltos de línea: multipart no
        // escapa nada y el servidor tiene que recibirlo byte a byte.
        val content = "Tarta \"de la abuela\" & <b>horno</b>\r\n\tñandú 🍰 \\ 100 %"
        assertEquals(
            ApiResult.Success("x"),
            mealie.client().createRecipeWithAi(content, null, emptyList()),
        )
        val body = String(mealie.seen.single().body, Charsets.UTF_8)
        assertTrue(body.contains("name=\"content\"\r\n\r\n$content\r\n"))
    }

    // ------------------------------------------------------------ respuestas

    @Test
    fun `los rechazos definitivos se traducen y no se repiten`() {
        val cases = listOf(
            400 to ApiResult.Rejected(400),
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            404 to ApiResult.NotFound,
            409 to ApiResult.Conflict,
            413 to ApiResult.Rejected(413),
            415 to ApiResult.Rejected(415),
        )
        for ((status, expected) in cases) {
            mealie.seen.clear()
            mealie.fixed("POST", PATH, status, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
            val result = mealie.client().createRecipeWithAi("Tarta", null, emptyList())
            assertEquals(status.toString(), expected, result)
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", PATH).size)
            assertFalse(status.toString(), result.toString().contains("SECRETO-DEL-SERVIDOR"))
        }
    }

    @Test
    fun `el detalle del 422 llega saneado`() {
        // El BEL va escapado (un control literal no es JSON válido) y tiene que
        // desaparecer al sanear el detalle antes de entregarlo.
        val body = """{"detail":[{"loc":["body","content"],"msg":"El texto no sirve """ + "\\u0007" + """ para la IA"}]}"""
        mealie.fixed("POST", PATH, 422, body)
        assertEquals(
            ApiResult.Invalid(listOf(ValidationDetail(null, null, "El texto no sirve para la IA"))),
            mealie.client().createRecipeWithAi("Tarta", null, emptyList()),
        )
    }

    @Test
    fun `un 5xx es ambiguo y no se reintenta`() {
        for (status in listOf(500, 502, 503, 504)) {
            mealie.seen.clear()
            mealie.fixed("POST", PATH, status, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
            val result = mealie.client().createRecipeWithAi("Tarta", null, emptyList())
            assertEquals(status.toString(), ApiResult.MaybeCreated(ConnectionProblem.ServerError), result)
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", PATH).size)
        }
    }

    @Test
    fun `el plazo de la IA vence como quiza creada y no reintenta`() {
        mealie.route("POST", PATH) { _, exchange ->
            Thread.sleep(800)
            runCatching { FakeMealie.reply(exchange, 201, "\"tardia\"".toByteArray()) }
        }
        val result = mealie.client(readTimeoutMs = 200).createRecipeWithAi("Tarta", null, emptyList())
        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding), result)
        assertEquals(1, mealie.requestsTo("POST", PATH).size)
    }

    @Test
    fun `un 2xx sin slug legible es ambiguo`() {
        for (body in listOf("{\"slug\":\"tarta\"}", "\"../x\"", "", "no es json")) {
            mealie.seen.clear()
            mealie.fixed("POST", PATH, 201, body)
            assertEquals(
                body.take(20),
                ApiResult.MaybeCreated(ConnectionProblem.Unknown),
                mealie.client().createRecipeWithAi("Tarta", null, emptyList()),
            )
            assertEquals(1, mealie.requestsTo("POST", PATH).size)
        }
    }

    @Test
    fun `una respuesta demasiado grande es ambigua`() {
        mealie.fixed("POST", PATH, 201, "x".repeat(50_000))
        assertEquals(
            ApiResult.MaybeCreated(ConnectionProblem.Unknown),
            mealie.client(maxBytes = 1_000).createRecipeWithAi("Tarta", null, emptyList()),
        )
        assertEquals(1, mealie.requestsTo("POST", PATH).size)
    }

    @Test
    fun `sin servidor es un fallo y no un quiza`() {
        mealie.stop()
        val result = mealie.client().createRecipeWithAi("Tarta", null, emptyList())
        assertTrue(result.toString(), result is ApiResult.Failure)
    }

    @Test
    fun `el plazo de la IA es larga pero acotado`() {
        assertTrue(MealieClient.AI_TOTAL_TIMEOUT_MS > 0)
        // La IA puede tardar minutos: menos de dos minutos no tiene sentido y
        // más de diez (el tope del transporte) tampoco se puede configurar.
        assertTrue(MealieClient.AI_TOTAL_TIMEOUT_MS in 120_000L..600_000L)
    }

    // --------------------------------------------------- el contenido no se filtra

    @Test
    fun `ni el texto ni el enlace ni las fotos aparecen en ningun resultado`() {
        val marker = "MARCA-DE-LA-RECETA-IA-1234"
        val urlMarker = "https://ejemplo.org/MARCA-DE-LA-WEB-5678"
        val photoMarker = "MARCA-DE-LA-FOTO-9012".toByteArray()
        // Una foto válida para que las peticiones lleguen al servidor, y otra
        // con la marca dentro solo para comprobar su `toString`.
        val images = listOf(AiImage(FakeMealie.minimalPng(), "png"))
        val markedImage = AiImage(photoMarker, "png")
        val outcomes = ArrayList<ApiResult<String>>()

        // Rechazo local y cuatro desenlaces del servidor.
        outcomes.add(mealie.client().createRecipeWithAi("$marker\u0000", urlMarker, images))
        mealie.fixed("POST", PATH, 201, "\"tarta\"")
        outcomes.add(mealie.client().createRecipeWithAi(marker, urlMarker, images))
        mealie.fixed("POST", PATH, 422, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
        outcomes.add(mealie.client().createRecipeWithAi(marker, urlMarker, images))
        mealie.fixed("POST", PATH, 503, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
        outcomes.add(mealie.client().createRecipeWithAi(marker, urlMarker, images))
        mealie.fixed("POST", PATH, 201, "no es json")
        outcomes.add(mealie.client().createRecipeWithAi(marker, urlMarker, images))

        // Las cuatro últimas sí han salido: el contenido viaja al servidor.
        assertEquals(4, mealie.requestsTo("POST", PATH).size)
        for (result in outcomes) {
            val text = result.toString()
            assertFalse(text, text.contains(marker))
            assertFalse(text, text.contains("MARCA-DE-LA-WEB-5678"))
            assertFalse(text, text.contains("MARCA-DE-LA-FOTO-9012"))
            assertFalse(text, text.contains(mealie.token.value))
            assertFalse(text, text.contains("SECRETO-DEL-SERVIDOR"))
        }
        // Y la foto tampoco se imprime sola.
        val imageText = markedImage.toString()
        assertFalse(imageText, imageText.contains("MARCA-DE-LA-FOTO-9012"))
        assertTrue(imageText, imageText.contains("bytes="))
    }

    // ------------------------------------------------------ ¿hay IA en el servidor?

    @Test
    fun `la IA se pregunta al grupo de la cuenta, que no exige ser administrador`() {
        mealie.fixed(
            "GET",
            GROUPS_SELF,
            200,
            group("""{"aiEnabled":true,"imageProviderEnabled":true,"audioProviderEnabled":false}"""),
        )
        assertEquals(
            ApiResult.Success(AiCapabilities(available = true, imageAvailable = true, audioAvailable = false)),
            mealie.client().aiAvailable(),
        )
        val request = mealie.seen.single()
        assertEquals("GET", request.method)
        assertEquals(GROUPS_SELF, request.path)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `las tres capacidades se leen por separado`() {
        // Nombres de AIProviderSettingsOut en el openapi de Mealie 3.29.0.
        val cases = mapOf(
            """{"aiEnabled":true,"imageProviderEnabled":false,"audioProviderEnabled":true}""" to
                AiCapabilities(true, imageAvailable = false, audioAvailable = true),
            """{"aiEnabled":true,"imageProviderEnabled":false,"audioProviderEnabled":false}""" to
                AiCapabilities(true, imageAvailable = false, audioAvailable = false),
            """{"aiEnabled":true,"imageProviderEnabled":true,"audioProviderEnabled":true}""" to
                AiCapabilities(true, imageAvailable = true, audioAvailable = true),
        )
        for ((settings, expected) in cases) {
            mealie.fixed("GET", GROUPS_SELF, 200, group(settings))
            assertEquals(settings, ApiResult.Success(expected), mealie.client().aiAvailable())
        }
    }

    @Test
    fun `sin IA configurada la respuesta es un no`() {
        mealie.fixed("GET", GROUPS_SELF, 200, group("""{"aiEnabled":false}"""))
        assertEquals(
            ApiResult.Success(AiCapabilities(available = false, imageAvailable = false, audioAvailable = false)),
            mealie.client().aiAvailable(),
        )
    }

    @Test
    fun `un proveedor que queda de antes no enciende nada con la IA apagada`() {
        // Sin IA general no se manda nada a ningún sitio, aunque el grupo
        // conserve un proveedor de imagen o audio configurado.
        mealie.fixed(
            "GET",
            GROUPS_SELF,
            200,
            group("""{"aiEnabled":false,"imageProviderEnabled":true,"audioProviderEnabled":true}"""),
        )
        assertEquals(
            ApiResult.Success(AiCapabilities(available = false, imageAvailable = false, audioAvailable = false)),
            mealie.client().aiAvailable(),
        )
    }

    @Test
    fun `un proveedor sin dato cuenta como no disponible`() {
        // Un servidor de solo texto, o uno anterior a estos campos: se apagan
        // las fotos y el vídeo, pero la IA de texto se sigue ofreciendo.
        mealie.fixed("GET", GROUPS_SELF, 200, group("""{"aiEnabled":true}"""))
        assertEquals(ApiResult.Success(AiCapabilities(true, false, false)), mealie.client().aiAvailable())

        // Un null explícito es lo mismo que no venir.
        mealie.fixed(
            "GET",
            GROUPS_SELF,
            200,
            group("""{"aiEnabled":true,"imageProviderEnabled":null,"audioProviderEnabled":null}"""),
        )
        assertEquals(ApiResult.Success(AiCapabilities(true, false, false)), mealie.client().aiAvailable())

        // Y solo uno de los dos: el otro no se contagia.
        mealie.fixed(
            "GET",
            GROUPS_SELF,
            200,
            group("""{"aiEnabled":true,"imageProviderEnabled":true}"""),
        )
        assertEquals(ApiResult.Success(AiCapabilities(true, true, false)), mealie.client().aiAvailable())
    }

    @Test
    fun `un campo con un tipo raro no inventa capacidades`() {
        // «si» no es un booleano: la respuesta entera se descarta y se falla
        // cerrado en vez de adivinar qué quería decir el servidor.
        mealie.fixed(
            "GET",
            GROUPS_SELF,
            200,
            group("""{"aiEnabled":true,"imageProviderEnabled":"si","audioProviderEnabled":true}"""),
        )
        assertEquals(
            ApiResult.Failure(ConnectionProblem.Unknown),
            mealie.client().aiAvailable(),
        )
    }

    @Test
    fun `sin el campo no se puede saber y no se esconde la opcion`() {
        // Un servidor anterior a esta función, o una respuesta recortada: decir
        // «no hay IA» escondería una función que sí puede estar.
        val bodies = listOf(
            """{"name":"Casa","id":"$uuid","slug":"casa"}""",
            """{"name":"Casa","id":"$uuid","slug":"casa","aiProviderSettings":null}""",
            """{"name":"Casa","id":"$uuid","slug":"casa","aiProviderSettings":{}}""",
            """{"name":"Casa","id":"$uuid","slug":"casa","aiProviderSettings":{"providers":[]}}""",
            // Los proveedores sin `aiEnabled` tampoco dicen si hay IA.
            group("""{"imageProviderEnabled":true,"audioProviderEnabled":true}"""),
            "no es json",
        )
        for (body in bodies) {
            mealie.seen.clear()
            mealie.fixed("GET", GROUPS_SELF, 200, body)
            assertEquals(
                body.take(30),
                ApiResult.Failure(ConnectionProblem.Unknown),
                mealie.client().aiAvailable(),
            )
        }
    }

    @Test
    fun `los errores de la consulta de IA se traducen sin filtrar nada`() {
        mealie.fixed("GET", GROUPS_SELF, 401)
        assertEquals(ApiResult.Unauthorized, mealie.client().aiAvailable())
        mealie.fixed("GET", GROUPS_SELF, 403)
        assertEquals(ApiResult.Forbidden, mealie.client().aiAvailable())
        mealie.fixed("GET", GROUPS_SELF, 500, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
        val failed = mealie.client().aiAvailable()
        assertEquals(ApiResult.Failure(ConnectionProblem.ServerError), failed)
        assertFalse(failed.toString().contains("SECRETO-DEL-SERVIDOR"))
        assertFalse(failed.toString().contains(mealie.token.value))
    }

    @Test
    fun `sin servidor la consulta de IA es un fallo`() {
        mealie.stop()
        val result = mealie.client().aiAvailable()
        assertTrue(result.toString(), result is ApiResult.Failure)
    }

    private fun group(settings: String): String =
        """{"name":"Casa","id":"$uuid","slug":"casa","aiProviderSettings":$settings}"""

    private companion object {
        const val PATH = "/api/recipes/create/ai"
        const val GROUPS_SELF = "/api/groups/self"
        const val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
