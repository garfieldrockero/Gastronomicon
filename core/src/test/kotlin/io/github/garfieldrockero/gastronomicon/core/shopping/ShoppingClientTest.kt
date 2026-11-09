package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.FakeMealie
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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

/**
 * Pruebas del cliente de la compra contra el HttpServer del JDK (a través de
 * [FakeMealie]): cabeceras, cuerpos, códigos de error, respuestas enormes y
 * textos hostiles. Son de frontera: el token sale en cada petición autenticada,
 * el texto del servidor no puede colarse sin sanear y los errores no repiten
 * token, URL ni cuerpo.
 */
class ShoppingClientTest {

    private lateinit var mealie: FakeMealie

    private val listId = "11111111-1111-4111-8111-111111111111"
    private val itemId = "22222222-2222-4222-8222-222222222222"
    private val secondItemId = "33333333-3333-4333-8333-333333333333"
    private val labelId = "44444444-4444-4444-8444-444444444444"
    private val foodId = "55555555-5555-4555-8555-555555555555"
    private val unitId = "66666666-6666-4666-8666-666666666666"

    private val listPath = "/api/households/shopping/lists/$listId"
    private val itemsPath = "/api/households/shopping/items"

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun client(maxBytes: Long = 1_000_000, readTimeoutMs: Int = 5_000): ShoppingClient =
        ShoppingClient(
            UrlConnectionTransport(
                baseUri = URI("http://127.0.0.1:${mealie.port}"),
                token = mealie.token,
                connectTimeoutMs = 5_000,
                readTimeoutMs = readTimeoutMs,
                maxResponseBytes = maxBytes,
                totalTimeoutMs = 60_000L,
            ),
        )

    @Suppress("UNCHECKED_CAST")
    private fun <T> success(result: ApiResult<T>): T = (result as ApiResult.Success<T>).value

    private fun bodyOf(request: FakeMealie.Seen): JsonObject =
        CoreJson.json.parseToJsonElement(String(request.body, Charsets.UTF_8)).jsonObject

    private fun listResponse(vararg items: String): String = """
        {
          "id": "$listId",
          "name": "Compra",
          "listItems": [${items.joinToString(",")}]
        }
    """.trimIndent()

    private fun itemResponse(
        id: String = itemId,
        note: String = "Leche",
        quantity: String = "1",
        checked: Boolean = false,
        position: Int = 0,
        label: Boolean = false,
        foodName: String? = null,
        foodPlural: String? = null,
        unitName: String? = null,
        unitPlural: String? = null,
        abbreviation: String? = null,
        useAbbreviation: Boolean = false,
    ): String {
        val labelJson = if (label) {
            """{"id":"$labelId","name":"Frutas"}"""
        } else {
            "null"
        }
        val foodJson = if (foodName == null) {
            "null"
        } else {
            """{"id":"$foodId","name":"$foodName","pluralName":"${foodPlural ?: foodName}"}"""
        }
        val unitJson = if (unitName == null) {
            "null"
        } else {
            """{"id":"$unitId","name":"$unitName","pluralName":"${unitPlural ?: unitName}",""" +
                """"abbreviation":"${abbreviation.orEmpty()}","pluralAbbreviation":"${abbreviation.orEmpty()}",""" +
                """"useAbbreviation":$useAbbreviation}"""
        }
        return """
            {
              "id": "$id",
              "shoppingListId": "$listId",
              "quantity": $quantity,
              "note": "$note",
              "checked": $checked,
              "position": $position,
              "labelId": ${if (label) "\"$labelId\"" else "null"},
              "label": $labelJson,
              "foodId": ${if (foodName == null) "null" else "\"$foodId\""},
              "food": $foodJson,
              "unitId": ${if (unitName == null) "null" else "\"$unitId\""},
              "unit": $unitJson
            }
        """.trimIndent()
    }

    /**
     * Elemento con claves que esta versión de la app no declara
     * (`recipeReferences`, `extras`, `display`, `createdAt`) y una nota con un
     * salto de línea: el `PUT` no puede perder nada de eso.
     */
    private fun richItemResponse(): String = """
        {
          "id": "$itemId",
          "shoppingListId": "$listId",
          "quantity": 2.5,
          "note": "Leche\nentera ",
          "checked": false,
          "position": 3,
          "recipeReferences": [{"recipeId": "$labelId", "recipeQuantity": 2}],
          "extras": {"a": [1, 2, 3]},
          "display": "2,5 l Leche entera",
          "createdAt": "2026-01-01T00:00:00Z"
        }
    """.trimIndent()

    private fun shoppingItem(
        id: String = itemId,
        note: String = "Leche",
        quantity: Double = 1.0,
        checked: Boolean = false,
        position: Int = 0,
    ): ShoppingItem = ShoppingItem(
        id = id,
        shoppingListId = listId,
        note = note,
        quantity = quantity,
        unitSingular = "",
        unitPlural = "",
        foodSingular = "",
        foodPlural = "",
        labelId = null,
        labelName = "",
        checked = checked,
        position = position,
    )

    private fun assertNoSecrets(result: Any) {
        val text = result.toString()
        assertFalse(text, text.contains(mealie.token.value))
        assertFalse(text, text.contains("127.0.0.1"))
    }

    // ----------------------------------------------------------------- listas

    @Test
    fun `listar listas pide la pagina manda el token y sanea el nombre`() {
        mealie.fixed(
            "GET",
            ShoppingClient.LISTS_PATH,
            200,
            """{"page":2,"per_page":50,"total":2,"items":[
                {"id":"$secondItemId","name":"Compra\u202E dos"},
                {"id":"$listId","name":"Compra"}
            ]}""",
        )
        val page = success(client().listLists(page = 2))
        assertEquals(2, page.page)
        assertEquals(50, page.perPage)
        assertEquals(2, page.total.toInt())
        // Orden local por nombre y texto saneado.
        assertEquals(listOf("Compra", "Compra dos"), page.items.map { it.name })
        val request = mealie.seen.single()
        assertEquals("page=2&perPage=50", request.query)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `leer una lista sanea textos y descarta ids invalidos`() {
        mealie.fixed(
            "GET",
            listPath,
            200,
            listResponse(
                itemResponse(note = "Leche\u202E"),
                itemResponse(id = "no-es-uuid", note = "Basura"),
                itemResponse(id = secondItemId, note = "<b>Pan</b>", checked = true),
            ),
        )
        val list = success(client().getList(listId))
        assertEquals(listId, list.id)
        assertEquals("Compra", list.name)
        assertEquals(2, list.items.size)
        assertEquals("Leche", list.items[0].note)
        assertEquals("<b>Pan</b>", list.items[1].note)
        assertTrue(list.items[1].checked)
        assertEquals("Bearer ${mealie.token.value}", mealie.seen.single().headers["authorization"])
    }

    @Test
    fun `los elementos repetidos no se duplican`() {
        mealie.fixed("GET", listPath, 200, listResponse(itemResponse(), itemResponse()))
        assertEquals(1, success(client().getList(listId)).items.size)
    }

    @Test
    fun `lee etiqueta alimento y unidad con plural y abreviatura`() {
        mealie.fixed(
            "GET",
            listPath,
            200,
            listResponse(
                itemResponse(
                    quantity = "2",
                    label = true,
                    foodName = "patata",
                    foodPlural = "patatas",
                    unitName = "kilogramo",
                    unitPlural = "kilogramos",
                    abbreviation = "kg",
                    useAbbreviation = true,
                ),
            ),
        )
        val item = success(client().getList(listId)).items.single()
        assertEquals(labelId, item.labelId)
        assertEquals("Frutas", item.labelName)
        assertEquals(foodId, item.foodId)
        assertEquals(unitId, item.unitId)
        assertEquals("kg", item.unit)
        assertEquals("patatas", item.food)
    }

    @Test
    fun `una cantidad absurda se guarda como sin cantidad`() {
        mealie.fixed("GET", listPath, 200, listResponse(itemResponse(quantity = "1e300", note = "Leche")))
        assertEquals(0.0, success(client().getList(listId)).items.single().quantity, 0.0)
    }

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
            mealie.fixed("GET", listPath, status)
            assertEquals("estado $status", result, client().getList(listId))
        }
    }

    @Test
    fun `una respuesta enorme no se lee entera`() {
        mealie.fixed("GET", listPath, 200, "x".repeat(10_000))
        val result = client(maxBytes = 1_000).getList(listId)
        assertEquals(ApiResult.Failure(ConnectionProblem.Unknown), result)
        assertNoSecrets(result)
    }

    @Test
    fun `un id invalido no llega a la red`() {
        val client = client()
        assertThrows(IllegalArgumentException::class.java) { client.getList("../../fuera") }
        assertThrows(IllegalArgumentException::class.java) { client.deleteItem("no-es-uuid") }
        assertThrows(IllegalArgumentException::class.java) { client.addItem("no-es-uuid", "Leche") }
        assertTrue(mealie.seen.isEmpty())
    }

    // -------------------------------------------------------------- escritura

    @Test
    fun `anadir envia solo lista y nota saneada`() {
        mealie.fixed("POST", itemsPath, 201, "{}")
        val result = client().addItem(listId, "  Leche\u202E  ")
        assertEquals(ApiResult.Success(Unit), result)
        val request = mealie.seen.single()
        val body = bodyOf(request)
        assertEquals(listId, body["shoppingListId"]!!.jsonPrimitive.content)
        assertEquals("Leche", body["note"]!!.jsonPrimitive.content)
        assertFalse("la cantidad la pone el servidor", body.containsKey("quantity"))
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
    }

    @Test
    fun `un texto vacio o demasiado largo no sale de la app`() {
        val client = client()
        assertThrows(IllegalArgumentException::class.java) { client.addItem(listId, "   ") }
        assertThrows(IllegalArgumentException::class.java) { client.addItem(listId, "a".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { client.editItem(shoppingItem(), "a".repeat(201), 1.0) }
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `marcar reenvia el estado que conoce la app`() {
        mealie.fixed("PUT", "$itemsPath/$itemId", 200, "{}")
        val item = shoppingItem(note = "Leche", quantity = 1.0, position = 3)
        assertEquals(ApiResult.Success(Unit), client().setChecked(item, true))
        val body = bodyOf(mealie.seen.single())
        assertEquals(listId, body["shoppingListId"]!!.jsonPrimitive.content)
        assertEquals("Leche", body["note"]!!.jsonPrimitive.content)
        assertEquals(1.0, body["quantity"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3, body["position"]!!.jsonPrimitive.int)
        assertTrue(body["checked"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `editar cambia nota y cantidad`() {
        mealie.fixed("PUT", "$itemsPath/$itemId", 200, "{}")
        assertEquals(ApiResult.Success(Unit), client().editItem(shoppingItem(), "Leche entera", 2.0))
        val body = bodyOf(mealie.seen.single())
        assertEquals("Leche entera", body["note"]!!.jsonPrimitive.content)
        assertEquals(2.0, body["quantity"]!!.jsonPrimitive.double, 0.0)
        assertFalse(body["checked"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `borrar un elemento usa su ruta validada`() {
        mealie.fixed("DELETE", "$itemsPath/$itemId", 200, "{}")
        assertEquals(ApiResult.Success(Unit), client().deleteItem(itemId))
        assertEquals("$itemsPath/$itemId", mealie.seen.single().path)
    }

    @Test
    fun `borrar los marcados va de uno en uno y se para al fallar`() {
        mealie.fixed("DELETE", "$itemsPath/$itemId", 200, "{}")
        mealie.fixed("DELETE", "$itemsPath/$secondItemId", 200, "{}")
        val items = listOf(shoppingItem(id = itemId), shoppingItem(id = secondItemId))
        assertEquals(ApiResult.Success(Unit), client().deleteChecked(items))
        assertEquals(2, mealie.seen.size)

        mealie.seen.clear()
        mealie.fixed("DELETE", "$itemsPath/$secondItemId", 500)
        assertEquals(
            ApiResult.Failure(ConnectionProblem.ServerError),
            client().deleteChecked(items),
        )
        assertEquals(2, mealie.seen.size)
    }

    @Test
    fun `borrar los marcados sigue si uno ya no estaba`() {
        mealie.fixed("DELETE", "$itemsPath/$itemId", 404)
        mealie.fixed("DELETE", "$itemsPath/$secondItemId", 200, "{}")
        val items = listOf(shoppingItem(id = itemId), shoppingItem(id = secondItemId))

        assertEquals(ApiResult.Success(Unit), client().deleteChecked(items))
        assertEquals(2, mealie.seen.size)
    }

    @Test
    fun `marcar conserva el json crudo y cambia solo checked`() {
        mealie.fixed("GET", listPath, 200, listResponse(richItemResponse()))
        mealie.fixed("PUT", "$itemsPath/$itemId", 200, "{}")
        val client = client()
        val item = success(client.getList(listId)).items.single()

        assertEquals(ApiResult.Success(Unit), client.setChecked(item, true))

        val body = bodyOf(mealie.requestsTo("PUT", "$itemsPath/$itemId").single())
        // Ni una clave de menos: el cuerpo es el JSON del GET con `checked` cambiado.
        assertEquals(CoreJson.json.parseToJsonElement(richItemResponse()).jsonObject.keys, body.keys)
        assertTrue(body["checked"]!!.jsonPrimitive.boolean)
        // La nota viaja tal cual estaba en el JSON: ni recortada ni aplanada.
        assertEquals("Leche\nentera ", body["note"]!!.jsonPrimitive.content)
        assertEquals(2.5, body["quantity"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3, body["position"]!!.jsonPrimitive.int)
        assertTrue(body.containsKey("createdAt"))
        assertTrue(body.containsKey("display"))
        assertEquals(
            labelId,
            body["recipeReferences"]!!.jsonArray.single().jsonObject["recipeId"]!!.jsonPrimitive.content,
        )
        assertEquals(
            3,
            body["extras"]!!.jsonObject["a"]!!.jsonArray.size,
        )
    }

    @Test
    fun `editar cambia nota y cantidad y conserva el resto del json crudo`() {
        mealie.fixed("GET", listPath, 200, listResponse(richItemResponse()))
        mealie.fixed("PUT", "$itemsPath/$itemId", 200, "{}")
        val client = client()
        val item = success(client.getList(listId)).items.single()

        assertEquals(ApiResult.Success(Unit), client.editItem(item, "Leche entera", 3.0))

        val body = bodyOf(mealie.requestsTo("PUT", "$itemsPath/$itemId").single())
        assertEquals("Leche entera", body["note"]!!.jsonPrimitive.content)
        assertEquals(3.0, body["quantity"]!!.jsonPrimitive.double, 0.0)
        assertFalse(body["checked"]!!.jsonPrimitive.boolean)
        assertTrue(body.containsKey("recipeReferences"))
        assertTrue(body.containsKey("extras"))
        assertTrue(body.containsKey("createdAt"))
    }

    @Test
    fun `marcar despues de editar no reenvia la nota vieja`() {
        mealie.fixed("GET", listPath, 200, listResponse(richItemResponse()))
        mealie.fixed("PUT", "$itemsPath/$itemId", 200, "{}")
        val client = client()
        val item = success(client.getList(listId)).items.single()
        // Lo que deja el controlador tras un `editNote` correcto.
        val editado = item.withSavedContent("Leche entera", 3.0)

        assertEquals(ApiResult.Success(Unit), client.setChecked(editado, true))

        val body = bodyOf(mealie.requestsTo("PUT", "$itemsPath/$itemId").single())
        assertEquals("Leche entera", body["note"]!!.jsonPrimitive.content)
        assertEquals(3.0, body["quantity"]!!.jsonPrimitive.double, 0.0)
        assertTrue(body["checked"]!!.jsonPrimitive.boolean)
        assertTrue(body.containsKey("extras"))
    }

    @Test
    fun `anadir con un plazo agotado tras enviar es incierto`() {
        mealie.route("POST", itemsPath) { _, exchange ->
            Thread.sleep(800)
            runCatching { FakeMealie.reply(exchange, 201, "{}".toByteArray()) }
        }

        val result = client(readTimeoutMs = 200).addItem(listId, "Leche")

        assertEquals(ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding), result)
        assertNoSecrets(result)
    }

    @Test
    fun `el detalle de un 422 se lee saneado y acotado`() {
        mealie.fixed(
            "POST",
            itemsPath,
            422,
            """{"detail":[{"loc":["body","text"],"msg":"Mal\u202E dato"}]}""",
        )
        val result = client().addItem(listId, "Leche")
        assertEquals(ApiResult.Invalid(listOf(ValidationDetail(null, null, "Mal dato"))), result)
        assertNoSecrets(result)
    }

    @Test
    fun `un token rechazado no aparece en el resultado`() {
        mealie.fixed("GET", listPath, 401)
        val result = client().getList(listId)
        assertEquals(ApiResult.Unauthorized, result)
        assertNoSecrets(result)
    }
}
