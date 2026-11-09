package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.FakeMealie
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Pruebas del `POST` de ingredientes de receta contra el HttpServer del JDK (a
 * través de [FakeMealie]): ruta, cuerpo con la escala, cabeceras, códigos de
 * error y desenlaces dudosos. Son de frontera: el token sale en cada petición,
 * ningún id llega a la ruta sin ser UUID y los errores no repiten token, URL ni
 * cuerpo.
 */
class ShoppingRecipeAddTest {

    private lateinit var mealie: FakeMealie

    private val listId = "11111111-1111-4111-8111-111111111111"
    private val recetaA = "33333333-3333-4333-8333-333333333333"
    private val recetaB = "44444444-4444-4444-8444-444444444444"

    private val recipePath = "/api/households/shopping/lists/$listId/recipe"

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun client(readTimeoutMs: Int = 5_000): ShoppingClient =
        ShoppingClient(
            UrlConnectionTransport(
                baseUri = URI("http://127.0.0.1:${mealie.port}"),
                token = mealie.token,
                connectTimeoutMs = 5_000,
                readTimeoutMs = readTimeoutMs,
                maxResponseBytes = 1_000_000,
                totalTimeoutMs = 60_000L,
            ),
        )

    private fun bodyArray(request: FakeMealie.Seen): JsonArray =
        CoreJson.json.parseToJsonElement(String(request.body, Charsets.UTF_8)).jsonArray

    /** UUID distinto por índice, para probar el tope con recetas diferentes. */
    private fun uuid(index: Int): String =
        index.toString(16).padStart(8, '0') + "-0000-4000-8000-000000000000"

    private fun firstElement(request: FakeMealie.Seen): JsonObject =
        bodyArray(request).single().jsonObject

    private fun assertNoSecrets(result: Any) {
        val text = result.toString()
        assertFalse(text, text.contains(mealie.token.value))
        assertFalse(text, text.contains("127.0.0.1"))
    }

    // ------------------------------------------------------------- petición

    @Test
    fun `anadir una receta manda un array con su id y su escala`() {
        mealie.fixed("POST", recipePath, 200, "{}")

        val result = client().addRecipe(listId, recetaA, 2.0)

        assertEquals(ApiResult.Success(Unit), result)
        val request = mealie.seen.single()
        assertEquals("POST", request.method)
        assertEquals(recipePath, request.path)
        val body = firstElement(request)
        assertEquals(setOf("recipeId", "recipeIncrementQuantity"), body.keys)
        assertEquals(recetaA, body["recipeId"]!!.jsonPrimitive.content)
        assertEquals(2.0, body["recipeIncrementQuantity"]!!.jsonPrimitive.double, 0.0)
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `varias recetas van en una sola peticion y en orden`() {
        mealie.fixed("POST", recipePath, 200, "{}")

        val result = client().addRecipes(
            listId,
            listOf(RecipeAddition(recetaB, 1.0), RecipeAddition(recetaA, 3.5)),
        )

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals(1, mealie.seen.size)
        val body = bodyArray(mealie.seen.single())
        assertEquals(2, body.size)
        assertEquals(recetaB, body[0].jsonObject["recipeId"]!!.jsonPrimitive.content)
        assertEquals(1.0, body[0].jsonObject["recipeIncrementQuantity"]!!.jsonPrimitive.double, 0.0)
        assertEquals(recetaA, body[1].jsonObject["recipeId"]!!.jsonPrimitive.content)
        assertEquals(3.5, body[1].jsonObject["recipeIncrementQuantity"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `el id de la receta se normaliza a minusculas en el cuerpo`() {
        mealie.fixed("POST", recipePath, 200, "{}")

        client().addRecipe(listId, recetaA.uppercase(), 1.0)

        assertEquals(recetaA, firstElement(mealie.seen.single())["recipeId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `una receta repetida va una sola vez con las escalas sumadas`() {
        mealie.fixed("POST", recipePath, 200, "{}")

        val result = client().addRecipes(
            listId,
            listOf(
                RecipeAddition(recetaA, 1.0),
                RecipeAddition(recetaB, 1.0),
                RecipeAddition(recetaA.uppercase(), 2.0),
            ),
        )

        assertEquals(ApiResult.Success(Unit), result)
        assertEquals(1, mealie.seen.size)
        val body = bodyArray(mealie.seen.single())
        assertEquals(2, body.size)
        assertEquals(recetaA, body[0].jsonObject["recipeId"]!!.jsonPrimitive.content)
        assertEquals(3.0, body[0].jsonObject["recipeIncrementQuantity"]!!.jsonPrimitive.double, 0.0)
        assertEquals(recetaB, body[1].jsonObject["recipeId"]!!.jsonPrimitive.content)
        assertEquals(1.0, body[1].jsonObject["recipeIncrementQuantity"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun `no se manda el campo de ingredientes`() {
        mealie.fixed("POST", recipePath, 200, "{}")

        client().addRecipe(listId, recetaA, 1.0)

        assertFalse(firstElement(mealie.seen.single()).containsKey("recipeIngredients"))
    }

    // ----------------------------------------------------------- validación

    @Test
    fun `un id o una escala invalidos no llegan a la red`() {
        val client = client()

        assertThrows(IllegalArgumentException::class.java) { client.addRecipe("../../fuera", recetaA, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { client.addRecipe(listId, "no-es-uuid", 1.0) }
        assertThrows(IllegalArgumentException::class.java) { client.addRecipes(listId, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { client.addRecipe(listId, recetaA, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { client.addRecipe(listId, recetaA, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { client.addRecipe(listId, recetaA, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            client.addRecipe(listId, recetaA, RecipeAdditions.MAX_SCALE + 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.addRecipes(
                listId,
                (1..RecipeAdditions.MAX_RECIPES_PER_OPERATION + 1).map { RecipeAddition(uuid(it), 1.0) },
            )
        }
        assertTrue(mealie.seen.isEmpty())
    }

    // -------------------------------------------------------------- errores

    @Test
    fun `los codigos de error se traducen a su resultado tipado`() {
        val expected = mapOf(
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            404 to ApiResult.NotFound,
            400 to ApiResult.Rejected(400),
            500 to ApiResult.Failure(ConnectionProblem.ServerError),
        )
        for ((status, result) in expected) {
            mealie.fixed("POST", recipePath, status)
            assertEquals("estado $status", result, client().addRecipe(listId, recetaA, 1.0))
        }
    }

    @Test
    fun `un 5xx es dudoso porque los ingredientes pueden estar metidos`() {
        mealie.fixed("POST", recipePath, 500)

        val result = client().addRecipe(listId, recetaA, 1.0)

        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.ServerError), result)
    }

    @Test
    fun `un plazo agotado tras enviar es dudoso y no se repite`() {
        mealie.route("POST", recipePath) { _, exchange ->
            Thread.sleep(800)
            runCatching { FakeMealie.reply(exchange, 200, "{}".toByteArray()) }
        }

        val result = client(readTimeoutMs = 200).addRecipe(listId, recetaA, 1.0)

        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding), result)
        assertNoSecrets(result)
        // Ni un reintento automático: solo ha salido el POST original.
        assertEquals(1, mealie.seen.size)
    }

    @Test
    fun `el detalle de un 422 se lee saneado y acotado`() {
        mealie.fixed(
            "POST",
            recipePath,
            422,
            """{"detail":[{"loc":["body",0,"recipeId"],"msg":"Mal\u202E id"}]}""",
        )

        val result = client().addRecipe(listId, recetaA, 1.0)

        assertEquals(ApiResult.Invalid(listOf(ValidationDetail(null, null, "Mal id"))), result)
        assertNoSecrets(result)
    }

    @Test
    fun `un token rechazado no aparece en el resultado`() {
        mealie.fixed("POST", recipePath, 401)

        val result = client().addRecipe(listId, recetaA, 1.0)

        assertEquals(ApiResult.Unauthorized, result)
        assertNoSecrets(result)
    }

    @Test
    fun `dos peticiones a la vez no mezclan sus cuerpos`() {
        mealie.fixed("POST", recipePath, 200, "{}")
        val client = client()
        val listo = CountDownLatch(1)
        val hilos = listOf(recetaA, recetaB).map { receta ->
            thread {
                listo.await()
                client.addRecipe(listId, receta, 1.0)
            }
        }

        listo.countDown()
        hilos.forEach { it.join(5_000) }

        // Dos POST independientes, cada uno con su receta y un solo elemento.
        assertEquals(2, mealie.seen.size)
        val ids = mealie.seen.map { firstElement(it)["recipeId"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf(recetaA, recetaB), ids)
        for (request in mealie.seen) {
            assertEquals(1, bodyArray(request).size)
        }
    }
}
