package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftErrorKind
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Crear, editar y borrar recetas contra un servidor de pruebas. Son de
 * frontera: el cuerpo del PUT sale hacia el servidor con el token, y los
 * errores no pueden repetir token, URL ni cuerpos crudos.
 */
class MealieClientWriteTest {

    private lateinit var mealie: FakeMealie

    private val recipeUuid = "11111111-1111-4111-8111-111111111111"
    private val ingredientId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun original(name: String = "Tarta", slug: String = "tarta"): String = """
        {
          "id": "$recipeUuid",
          "name": "$name",
          "slug": "$slug",
          "image": "x1",
          "recipeServings": 4,
          "description": "Rica",
          "prepTime": "20 min",
          "prepTimeSeconds": 1200,
          "dateUpdated": "2026-02-02T10:00:00",
          "updatedAt": "2026-02-02T10:00:00",
          "createdAt": "2026-01-01T10:00:00",
          "recipeIngredient": [
            {"quantity": 2.0, "unit": {"id": "u1", "name": "cucharada"}, "food": {"id": "f1", "name": "azúcar"},
             "note": "fino", "referenceId": "$ingredientId", "campoFuturo": {"a": [1]}}
          ],
          "recipeInstructions": [],
          "campoNuevo2027": {"k": [1, 2, 3], "n": null}
        }
    """.trimIndent()

    private fun assertNoSecrets(result: Any) {
        val text = result.toString()
        assertFalse(text, text.contains(mealie.token.value))
        assertFalse(text, text.contains("127.0.0.1"))
        assertFalse(text, text.contains("SECRETO-DEL-SERVIDOR"))
    }

    // ----------------------------------------------------------------- crear

    @Test
    fun `crear una receta envia el nombre saneado y devuelve el slug`() {
        mealie.fixed("POST", "/api/recipes", 201, "\"tarta-nueva\"")
        val result = mealie.client().createRecipe("  Tarta‮  nueva ")
        assertEquals(ApiResult.Success("tarta-nueva"), result)
        val request = mealie.seen.single()
        assertEquals("""{"name":"Tarta nueva"}""", String(request.body, Charsets.UTF_8))
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `un nombre vacio o demasiado largo no sale de la app`() {
        assertEquals(
            ApiResult.DraftInvalid(listOf(DraftError(DraftField.Name, DraftErrorKind.Required))),
            mealie.client().createRecipe("   "),
        )
        assertEquals(
            ApiResult.DraftInvalid(listOf(DraftError(DraftField.Name, DraftErrorKind.TooLong))),
            mealie.client().createRecipe("a".repeat(301)),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `un 2xx con slug ilegible no afirma que no se creo y no reintenta`() {
        for (body in listOf("\"../etc\"", "\"a/b\"", "\"\"", "{\"slug\":\"x\"}", "[]", "12", "null", "no es json", "")) {
            mealie.seen.clear()
            mealie.fixed("POST", "/api/recipes", 201, body)
            val result = mealie.client().createRecipe("Tarta")
            assertEquals(body, ApiResult.MaybeCreated(ConnectionProblem.Unknown), result)
            // La receta puede existir: no se repite sola.
            assertEquals(body, 1, mealie.requestsTo("POST", "/api/recipes").size)
            assertNoSecrets(result)
        }
    }

    // ---------------------------------------------------------------- errores

    @Test
    fun `cada codigo de error se traduce a su resultado tipado`() {
        // Los 4xx de la lista son rechazos definitivos: el servidor contestó
        // que no lo aceptaba. Los 5xx, en cambio, son ambiguos una vez enviado
        // el cuerpo (puede ser un proxy mientras Mealie sigue trabajando).
        val expected = mapOf(
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            404 to ApiResult.NotFound,
            409 to ApiResult.Conflict,
            400 to ApiResult.Rejected(400),
            405 to ApiResult.Rejected(405),
            413 to ApiResult.Rejected(413),
            415 to ApiResult.Rejected(415),
            422 to ApiResult.Invalid(emptyList()),
            500 to ApiResult.MaybeCreated(ConnectionProblem.ServerError),
            502 to ApiResult.MaybeCreated(ConnectionProblem.ServerError),
            503 to ApiResult.MaybeCreated(ConnectionProblem.ServerError),
            504 to ApiResult.MaybeCreated(ConnectionProblem.ServerError),
        )
        for ((status, result) in expected) {
            mealie.seen.clear()
            mealie.fixed("POST", "/api/recipes", status, "{\"detail\":\"SECRETO-DEL-SERVIDOR\"}")
            val actual = mealie.client().createRecipe("Tarta")
            assertEquals(status.toString(), result, actual)
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", "/api/recipes").size)
            assertNoSecrets(actual)
        }
    }

    @Test
    fun `un 422 trae el detalle saneado sin copiar input ni contexto`() {
        mealie.fixed(
            "POST", "/api/recipes", 422,
            """{"detail":[
                {"loc":["body","name"],"msg":"Field required","type":"missing","input":"SECRETO-DEL-SERVIDOR","ctx":{"x":"SECRETO-DEL-SERVIDOR"}},
                {"loc":["body","recipeIngredient",2,"note"],"msg":"  Texto ‮ con  <b>marcas</b> \u0007 ","type":"x"},
                {"loc":["query","otro"],"msg":"Sin campo","type":"x"},
                {"loc":"mal","msg":42,"type":"x"},
                7,
                {"msg":""}
            ]}""",
        )
        val result = mealie.client().createRecipe("Tarta") as ApiResult.Invalid
        assertEquals(
            listOf(
                ValidationDetail(DraftField.Name, null, "Field required"),
                ValidationDetail(DraftField.Ingredients, 2, "Texto con <b>marcas</b>"),
                ValidationDetail(null, null, "Sin campo"),
            ),
            result.details,
        )
        assertNoSecrets(result)
    }

    @Test
    fun `un 422 hostil da un detalle acotado o vacio`() {
        val many = (1..50).joinToString(",") { """{"loc":["body","name"],"msg":"m$it","type":"x"}""" }
        mealie.fixed("POST", "/api/recipes", 422, """{"detail":[$many]}""")
        val limited = mealie.client().createRecipe("Tarta") as ApiResult.Invalid
        assertEquals(ApiErrors.MAX_DETAILS, limited.details.size)

        val longMessage = "x".repeat(10_000)
        mealie.fixed("POST", "/api/recipes", 422, """{"detail":[{"loc":["body","name"],"msg":"$longMessage"}]}""")
        val trimmed = mealie.client().createRecipe("Tarta") as ApiResult.Invalid
        assertEquals(ApiErrors.MAX_MESSAGE_LENGTH, trimmed.details.single().message.length)

        val garbage = listOf(
            "", "no es json", "[]", "\"detail\"", "{\"detail\":\"texto\"}", "{\"detail\":{\"message\":\"x\"}}",
            "{\"detail\":null}", "[".repeat(100_000),
        )
        for (body in garbage) {
            mealie.fixed("POST", "/api/recipes", 422, body)
            assertEquals(body.take(20), ApiResult.Invalid(emptyList()), mealie.client().createRecipe("Tarta"))
        }
        // Mayor que el tope del transporte: se corta y el 422 se entrega igual, sin detalle.
        mealie.fixed("POST", "/api/recipes", 422, "{\"detail\":[" + "{\"loc\":[],\"msg\":\"a\"},".repeat(5_000) + "{}]}")
        assertEquals(ApiResult.Invalid(emptyList()), mealie.client().createRecipe("Tarta"))
    }

    // --------------------------------------------------------------- loadDraft

    @Test
    fun `loadDraft devuelve el borrador con los ids de linea`() {
        mealie.fixed("GET", "/api/recipes/tarta", 200, original())
        val draft = (mealie.client().loadDraft("tarta") as ApiResult.Success).value
        assertEquals("Tarta", draft.name)
        assertEquals(4.0, draft.servings!!, 0.0)
        assertEquals(ingredientId, draft.ingredients.single().sourceId)
        assertEquals("cucharada", draft.ingredients.single().unit)
    }

    @Test
    fun `loadDraft con una respuesta que no es un objeto es un fallo`() {
        for (body in listOf("[]", "\"x\"", "null", "no es json", "")) {
            mealie.fixed("GET", "/api/recipes/tarta", 200, body)
            assertEquals(body, ApiResult.Failure(ConnectionProblem.Unknown), mealie.client().loadDraft("tarta"))
        }
        mealie.fixed("GET", "/api/recipes/tarta", 404)
        assertEquals(ApiResult.NotFound, mealie.client().loadDraft("tarta"))
    }

    // ------------------------------------------------------------------ editar

    /** Servidor que guarda lo que recibe en el PUT y lo devuelve en el siguiente GET. */
    private fun statefulRecipe(putResponse: String? = null) {
        val current = AtomicReference(original())
        mealie.route("GET", "/api/recipes/tarta") { _, e ->
            FakeMealie.reply(e, 200, current.get().toByteArray(), "application/json")
        }
        mealie.route("PUT", "/api/recipes/tarta") { request, e ->
            current.set(String(request.body, Charsets.UTF_8))
            FakeMealie.reply(e, 200, (putResponse ?: current.get()).toByteArray(), "application/json")
        }
    }

    @Test
    fun `editar manda el JSON entero del GET con solo las claves editadas cambiadas y relee`() {
        statefulRecipe()
        val client = mealie.client()
        val draft = (client.loadDraft("tarta") as ApiResult.Success).value.copy(
            name = "Tarta de limón",
            description = "Muy rica",
        )
        mealie.seen.clear()

        val result = client.updateRecipe("tarta", draft)

        assertEquals(listOf("GET", "PUT", "GET"), mealie.seen.map { it.method })
        val put = CoreJson.json.parseToJsonElement(String(mealie.seen[1].body, Charsets.UTF_8)) as JsonObject
        val source = CoreJson.json.parseToJsonElement(original()) as JsonObject
        val expected = source.toMutableMap()
        for (key in listOf("dateUpdated", "updatedAt", "update_at", "createdAt")) expected.remove(key)
        expected["name"] = JsonPrimitive("Tarta de limón")
        expected["description"] = JsonPrimitive("Muy rica")
        assertEquals(expected, put.toMap())
        // Lo que esta versión de la app no conoce sigue ahí, intacto.
        assertEquals(source["campoNuevo2027"], put["campoNuevo2027"])
        assertEquals(source["recipeIngredient"], put["recipeIngredient"])
        assertEquals("application/json", mealie.seen[1].headers["content-type"])
        assertEquals("Bearer ${mealie.token.value}", mealie.seen[1].headers["authorization"])

        val saved = (result as ApiResult.Success<Recipe>).value
        assertEquals("Tarta de limón", saved.name)
        assertEquals("Muy rica", saved.description)
    }

    @Test
    fun `el PUT nunca reenvia las marcas de tiempo del servidor aunque no se edite nada`() {
        statefulRecipe()
        val client = mealie.client()
        val draft = (client.loadDraft("tarta") as ApiResult.Success).value
        client.updateRecipe("tarta", draft)
        val put = String(mealie.requestsTo("PUT", "/api/recipes/tarta").single().body, Charsets.UTF_8)
        for (key in listOf("dateUpdated", "updatedAt", "update_at", "createdAt")) {
            assertFalse(key, put.contains("\"$key\""))
        }
    }

    @Test
    fun `si el nombre cambia y el servidor devuelve otro slug se relee con ese slug`() {
        val current = AtomicReference(original())
        mealie.route("GET", "/api/recipes/tarta") { _, e -> FakeMealie.reply(e, 200, current.get().toByteArray()) }
        mealie.route("PUT", "/api/recipes/tarta") { _, e ->
            current.set(original("Tarta de limón", "tarta-de-limon"))
            FakeMealie.reply(e, 200, "{\"slug\":\"tarta-de-limon\"}".toByteArray())
        }
        mealie.route("GET", "/api/recipes/tarta-de-limon") { _, e -> FakeMealie.reply(e, 200, current.get().toByteArray()) }
        val draft = (mealie.client().loadDraft("tarta") as ApiResult.Success).value.copy(name = "Tarta de limón")
        val saved = (mealie.client().updateRecipe("tarta", draft) as ApiResult.Success<Recipe>).value
        assertEquals("tarta-de-limon", saved.slug)
        assertEquals("/api/recipes/tarta-de-limon", mealie.seen.last().path)
    }

    @Test
    fun `una respuesta del PUT sin slug valido se ignora y se relee el slug de siempre`() {
        for (response in listOf("ok", "", "[]", "{\"slug\":\"../x\"}", "{\"slug\":5}", "{}")) {
            mealie.seen.clear()
            statefulRecipe(putResponse = response)
            val draft = (mealie.client().loadDraft("tarta") as ApiResult.Success).value.copy(description = "Otra")
            val result = mealie.client().updateRecipe("tarta", draft)
            assertTrue(response, result is ApiResult.Success<*>)
            assertEquals(response, "/api/recipes/tarta", mealie.seen.last().path)
        }
    }

    @Test
    fun `si el PUT se acepta y la relectura falla se avisa de que esta guardado`() {
        // GET 1: loadDraft. GET 2: el de updateRecipe. GET 3: la relectura, que falla.
        val gets = AtomicInteger()
        mealie.route("GET", "/api/recipes/tarta") { _, e ->
            if (gets.incrementAndGet() <= 2) FakeMealie.reply(e, 200, original().toByteArray()) else FakeMealie.reply(e, 500)
        }
        mealie.fixed("PUT", "/api/recipes/tarta", 200, "{}")
        val draft = (mealie.client().loadDraft("tarta") as ApiResult.Success).value.copy(description = "Otra")
        val result = mealie.client().updateRecipe("tarta", draft)
        assertEquals(ApiResult.SavedButNotReloaded(ConnectionProblem.ServerError), result)
        assertEquals(3, mealie.requestsTo("GET", "/api/recipes/tarta").size)
        assertNoSecrets(result)
    }

    @Test
    fun `un 422 del PUT trae el detalle y no se relee`() {
        mealie.fixed("GET", "/api/recipes/tarta", 200, original())
        mealie.fixed(
            "PUT", "/api/recipes/tarta", 422,
            """{"detail":[{"loc":["body","recipeServings"],"msg":"valor no válido","type":"x"}]}""",
        )
        val draft = (mealie.client().loadDraft("tarta") as ApiResult.Success).value.copy(description = "Otra")
        mealie.seen.clear()
        val result = mealie.client().updateRecipe("tarta", draft)
        assertEquals(
            ApiResult.Invalid(listOf(ValidationDetail(DraftField.Servings, null, "valor no válido"))),
            result,
        )
        assertEquals(listOf("GET", "PUT"), mealie.seen.map { it.method })
    }

    @Test
    fun `401 403 y 404 en el GET o el PUT cortan la edicion`() {
        val draft = RecipeDraft(name = "Tarta")
        mealie.fixed("GET", "/api/recipes/tarta", 404)
        assertEquals(ApiResult.NotFound, mealie.client().updateRecipe("tarta", draft))
        assertTrue(mealie.requestsTo("PUT", "/api/recipes/tarta").isEmpty())

        mealie.fixed("GET", "/api/recipes/tarta", 200, original())
        mealie.fixed("PUT", "/api/recipes/tarta", 401)
        assertEquals(ApiResult.Unauthorized, mealie.client().updateRecipe("tarta", draft))
        mealie.fixed("PUT", "/api/recipes/tarta", 403)
        assertEquals(ApiResult.Forbidden, mealie.client().updateRecipe("tarta", draft))
        mealie.fixed("PUT", "/api/recipes/tarta", 500)
        assertEquals(ApiResult.Failure(ConnectionProblem.ServerError), mealie.client().updateRecipe("tarta", draft))
    }

    @Test
    fun `un borrador invalido no envia nada`() {
        val result = mealie.client().updateRecipe("tarta", RecipeDraft(name = ""))
        assertEquals(ApiResult.DraftInvalid(listOf(DraftError(DraftField.Name, DraftErrorKind.Required))), result)
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `cambiar la unidad de una linea existente se rechaza antes del PUT`() {
        mealie.fixed("GET", "/api/recipes/tarta", 200, original())
        val loaded = (mealie.client().loadDraft("tarta") as ApiResult.Success).value
        val draft = loaded.copy(ingredients = listOf(loaded.ingredients[0].copy(unit = "g")))
        mealie.seen.clear()
        val result = mealie.client().updateRecipe("tarta", draft)
        assertEquals(
            ApiResult.DraftInvalid(listOf(DraftError(DraftField.IngredientUnit, DraftErrorKind.NotEditable, 0))),
            result,
        )
        assertTrue(mealie.requestsTo("PUT", "/api/recipes/tarta").isEmpty())
    }

    @Test
    fun `una linea nueva se escribe como cantidad y nota`() {
        statefulRecipe()
        val client = mealie.client()
        val loaded = (client.loadDraft("tarta") as ApiResult.Success).value
        val draft = loaded.copy(ingredients = loaded.ingredients + DraftIngredient(quantity = 1.0, note = "pizca de sal"))
        client.updateRecipe("tarta", draft)
        val put = CoreJson.json.parseToJsonElement(String(mealie.requestsTo("PUT", "/api/recipes/tarta").single().body, Charsets.UTF_8)) as JsonObject
        val lines = put["recipeIngredient"] as JsonArray
        assertEquals(2, lines.size)
        assertEquals(
            JsonObject(mapOf("quantity" to JsonPrimitive(1.0), "note" to JsonPrimitive("pizca de sal"))),
            lines[1],
        )
    }

    @Test
    fun `un GET hostil al editar es un fallo sin PUT`() {
        for (body in listOf("[]", "\"x\"", "null", "{", "[".repeat(100_000))) {
            mealie.seen.clear()
            mealie.fixed("GET", "/api/recipes/tarta", 200, body)
            val result = mealie.client().updateRecipe("tarta", RecipeDraft(name = "Tarta"))
            assertEquals(body.take(10), ApiResult.Failure(ConnectionProblem.Unknown), result)
            assertEquals(1, mealie.seen.size)
        }
    }

    @Test
    fun `un slug fuera de la lista blanca lanza antes de enviar nada`() {
        for (bad in listOf("", "../x", "a/b", "a b", "a?b", "a%2fb", "ñ")) {
            for (call in listOf<() -> Any>(
                { mealie.client().updateRecipe(bad, RecipeDraft(name = "T")) },
                { mealie.client().deleteRecipe(bad) },
                { mealie.client().loadDraft(bad) },
                { mealie.client().uploadImage(bad, ByteArray(1), "png") },
            )) {
                try {
                    call()
                    org.junit.Assert.fail("Debía lanzar IllegalArgumentException con «$bad»")
                } catch (expected: IllegalArgumentException) {
                    assertFalse(expected.message.orEmpty().contains(bad.ifEmpty { "\u0000" }))
                }
            }
        }
        assertTrue(mealie.seen.isEmpty())
    }

    // ------------------------------------------------------------------ borrar

    @Test
    fun `borrar usa DELETE y descarta el cuerpo`() {
        mealie.fixed("DELETE", "/api/recipes/tarta", 200, "{\"cualquier\":\"cosa\"}")
        assertEquals(ApiResult.Success(Unit), mealie.client().deleteRecipe("tarta"))
        val request = mealie.seen.single()
        assertEquals("DELETE", request.method)
        assertEquals(0, request.body.size)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `borrar traduce los errores`() {
        val expected = mapOf(
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            404 to ApiResult.NotFound,
            500 to ApiResult.Failure(ConnectionProblem.ServerError),
        )
        for ((status, result) in expected) {
            mealie.fixed("DELETE", "/api/recipes/tarta", status)
            assertEquals(status.toString(), result, mealie.client().deleteRecipe("tarta"))
        }
    }

    @Test
    fun `una respuesta enorme al borrar es un fallo y no un exito`() {
        mealie.fixed("DELETE", "/api/recipes/tarta", 200, "x".repeat(50_000))
        val result = mealie.client(maxBytes = 1_000).deleteRecipe("tarta")
        assertEquals(ApiResult.Failure(ConnectionProblem.Unknown), result)
    }

    @Test
    fun `sin servidor se devuelve un fallo de red sin direccion ni token`() {
        val client = mealie.client()
        mealie.stop()
        val result = client.deleteRecipe("tarta")
        assertTrue(result.toString(), result is ApiResult.Failure)
        assertNoSecrets(result)
    }
}
