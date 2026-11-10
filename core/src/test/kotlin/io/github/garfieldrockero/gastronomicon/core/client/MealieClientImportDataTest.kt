package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Importar pegando el JSON o el HTML de una receta
 * (`POST /api/recipes/create/html-or-json`).
 *
 * Lo que se comprueba aquí es la frontera: el texto no se sanea ni se interpreta
 * en la app (lo hace el servidor), pero **no sale** si está vacío, si pasa del
 * tope en bytes, si lleva controles o si no es texto UTF-8 codificable; y, una
 * vez enviado, cualquier duda es «quizá creada» y nunca un reintento.
 */
class MealieClientImportDataTest {

    private lateinit var mealie: FakeMealie

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    /** El cuerpo que ha llegado al servidor de pruebas, ya como objeto JSON. */
    private fun sentBody(): JsonObject =
        CoreJson.json.parseToJsonElement(String(mealie.seen.single().body, Charsets.UTF_8)) as JsonObject

    // ---------------------------------------------------------------- envío

    @Test
    fun `el HTML de una receta se manda tal cual y devuelve el slug`() {
        mealie.fixed("POST", PATH, 201, "\"tarta-de-la-abuela\"")
        val html = """
            <html><head><script type="application/ld+json">{"@type":"Recipe","name":"Tarta"}</script></head>
            <body><h1>Tarta</h1><p>Mezcla y hornea.</p></body></html>
        """.trimIndent()

        val result = mealie.client().importFromData(html)

        assertEquals(ApiResult.Success("tarta-de-la-abuela"), result)
        val request = mealie.seen.single()
        assertEquals("POST", request.method)
        assertEquals(PATH, request.path)
        assertNull(request.query)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
        assertTrue(request.headers["content-type"].orEmpty().startsWith("application/json"))
        val body = sentBody()
        // Íntegro: la app no sanea, ni recorta, ni reinterpreta el contenido.
        assertEquals(JsonPrimitive(html), body["data"])
        assertEquals(JsonPrimitive(false), body["includeTags"])
        assertEquals(JsonPrimitive(false), body["includeCategories"])
        assertEquals(setOf("data", "includeTags", "includeCategories"), body.keys)
        // `url` es opcional en el esquema y este endpoint no descarga nada.
        assertFalse(body.containsKey("url"))
    }

    @Test
    fun `un objeto JSON-LD de receta tambien va tal cual`() {
        mealie.fixed("POST", PATH, 201, "\"tarta\"")
        val json = """{"@context":"https://schema.org","@type":"Recipe","name":"Tarta","recipeIngredient":["2 huevos"]}"""

        assertEquals(ApiResult.Success("tarta"), mealie.client().importFromData(json))
        assertEquals(JsonPrimitive(json), sentBody()["data"])
    }

    @Test
    fun `se pueden pedir etiquetas y categorias`() {
        mealie.fixed("POST", PATH, 201, "\"x\"")

        assertEquals(
            ApiResult.Success("x"),
            mealie.client().importFromData("<html/>", includeTags = true, includeCategories = true),
        )
        val body = sentBody()
        assertEquals(JsonPrimitive(true), body["includeTags"])
        assertEquals(JsonPrimitive(true), body["includeCategories"])
    }

    // ------------------------------------------------------- rechazo local

    @Test
    fun `un texto vacio o de solo espacios no sale de la app`() {
        val blanks = listOf("", " ", "\n\t\r ", "\u00A0", "\u2003\u00A0", "\u3000")
        for (data in blanks) {
            assertEquals(
                "longitud ${data.length}",
                ApiResult.InputRejected(InputProblem.EmptyData),
                mealie.client().importFromData(data),
            )
        }
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `el tope se cuenta en bytes y no en caracteres`() {
        mealie.fixed("POST", PATH, 201, "\"tarta\"")
        val max = MealieClient.MAX_IMPORT_DATA_BYTES
        // «ñ» ocupa 2 bytes en UTF-8: justo en el tope con la mitad de caracteres.
        val atLimit = "\u00F1".repeat(max / 2)
        assertEquals(max, atLimit.toByteArray(Charsets.UTF_8).size)
        assertEquals(ApiResult.Success("tarta"), mealie.client().importFromData(atLimit))
        assertEquals(1, mealie.requestsTo("POST", PATH).size)

        // Un solo byte ASCII más ya pasa, aunque sigan siendo menos de 1 MiB de
        // caracteres: lo que cuenta es el tamaño en UTF-8, no la longitud.
        mealie.seen.clear()
        val overLimit = atLimit + "a"
        assertEquals(max + 1, overLimit.toByteArray(Charsets.UTF_8).size)
        assertTrue(overLimit.length < max)
        assertEquals(
            ApiResult.InputRejected(InputProblem.DataTooLarge),
            mealie.client().importFromData(overLimit),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `los caracteres de control no salen de la app`() {
        val controls = listOf("\u0000", "a\u0000b", "\u0007", "\u001B", "<p>ok</p>\u001F", "abc\u007F", "\u0085", "\u009F")
        for (data in controls) {
            assertEquals(
                ApiResult.InputRejected(InputProblem.DataNotText),
                mealie.client().importFromData(data),
            )
        }
        assertTrue(mealie.seen.isEmpty())

        // Tabulador, salto de línea y retorno de carro sí: son formato normal
        // en HTML y en JSON.
        mealie.fixed("POST", PATH, 201, "\"x\"")
        assertEquals(
            ApiResult.Success("x"),
            mealie.client().importFromData("<p>uno</p>\r\n\t<p>dos</p>"),
        )
    }

    @Test
    fun `un surrogado suelto no se puede enviar`() {
        val lone = listOf("\uD800", "abc\uDC00", "\uD83D", "x\uD83Dy")
        for (data in lone) {
            assertEquals(
                ApiResult.InputRejected(InputProblem.DataNotText),
                mealie.client().importFromData(data),
            )
        }
        assertTrue(mealie.seen.isEmpty())

        // Un emoji bien formado (par suplente completo) sí viaja.
        mealie.fixed("POST", PATH, 201, "\"x\"")
        assertEquals(ApiResult.Success("x"), mealie.client().importFromData("tarta \uD83D\uDE00"))
    }

    // ------------------------------------------------------------ respuestas

    @Test
    fun `los rechazos del servidor se traducen sin repetir el cuerpo`() {
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
            val result = mealie.client().importFromData("<html/>")
            assertEquals(status.toString(), expected, result)
            assertFalse(status.toString(), result.toString().contains("SECRETO-DEL-SERVIDOR"))
            // Un rechazo definitivo no se repite: no puede haber dos importaciones.
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", PATH).size)
        }
    }

    @Test
    fun `el detalle del 422 llega saneado`() {
        // El BEL va escapado (un control literal no es JSON válido) y tiene que
        // desaparecer al sanear el detalle antes de entregarlo.
        val body = """{"detail":[{"loc":["body","data"],"msg":"El texto no sirve """ + "\\u0007" + """ para importar"}]}"""
        mealie.fixed("POST", PATH, 422, body)
        val result = mealie.client().importFromData("<html/>")
        // «data» no es un campo del formulario de recetas: sin campo ni fila.
        assertEquals(
            ApiResult.Invalid(listOf(ValidationDetail(null, null, "El texto no sirve para importar"))),
            result,
        )
    }

    @Test
    fun `un 5xx es ambiguo y no se reintenta`() {
        for (status in listOf(500, 502, 503, 504)) {
            mealie.seen.clear()
            mealie.fixed("POST", PATH, status)
            val result = mealie.client().importFromData("<html/>")
            assertEquals(status.toString(), ApiResult.MaybeCreated(ConnectionProblem.ServerError), result)
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", PATH).size)
        }
    }

    @Test
    fun `un plazo agotado no afirma que no se creo`() {
        mealie.route("POST", PATH) { _, exchange ->
            Thread.sleep(800)
            runCatching { FakeMealie.reply(exchange, 201, "\"tardia\"".toByteArray()) }
        }
        val result = mealie.client(readTimeoutMs = 200).importFromData("<html/>")
        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding), result)
        assertEquals(1, mealie.requestsTo("POST", PATH).size)
    }

    @Test
    fun `un 2xx sin slug legible es ambiguo`() {
        mealie.fixed("POST", PATH, 201, "{\"slug\":\"tarta\"}")
        assertEquals(
            ApiResult.MaybeCreated(ConnectionProblem.Unknown),
            mealie.client().importFromData("<html/>"),
        )
        mealie.fixed("POST", PATH, 201, "\"../x\"")
        assertEquals(
            ApiResult.MaybeCreated(ConnectionProblem.Unknown),
            mealie.client().importFromData("<html/>"),
        )
        assertEquals(2, mealie.requestsTo("POST", PATH).size)
    }

    @Test
    fun `una respuesta demasiado grande es ambigua`() {
        mealie.fixed("POST", PATH, 201, "x".repeat(50_000))
        assertEquals(
            ApiResult.MaybeCreated(ConnectionProblem.Unknown),
            mealie.client(maxBytes = 1_000).importFromData("<html/>"),
        )
        assertEquals(1, mealie.requestsTo("POST", PATH).size)
    }

    @Test
    fun `sin servidor es un fallo y no un quiza`() {
        mealie.stop()
        val result = mealie.client().importFromData("<html/>")
        assertTrue(result.toString(), result is ApiResult.Failure)
    }

    // ------------------------------------------------------- el texto no se filtra

    @Test
    fun `el contenido no aparece en ningun resultado`() {
        val marker = "MARCA-DE-LA-RECETA-1234"
        val data = "<html><body>$marker</body></html>"
        val outcomes = ArrayList<ApiResult<String>>()

        // Rechazo local, éxito y cuatro desenlaces del servidor.
        outcomes.add(mealie.client().importFromData("$marker$marker\u0000"))
        mealie.fixed("POST", PATH, 201, "\"tarta\"")
        outcomes.add(mealie.client().importFromData(data))
        mealie.fixed("POST", PATH, 422, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
        outcomes.add(mealie.client().importFromData(data))
        mealie.fixed("POST", PATH, 503, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
        outcomes.add(mealie.client().importFromData(data))
        mealie.fixed("POST", PATH, 201, "no es json")
        outcomes.add(mealie.client().importFromData(data))

        for (result in outcomes) {
            val text = result.toString()
            assertFalse(text, text.contains(marker))
            assertFalse(text, text.contains(mealie.token.value))
            assertFalse(text, text.contains("SECRETO-DEL-SERVIDOR"))
        }
    }

    private companion object {
        const val PATH = "/api/recipes/create/html-or-json"
    }
}
