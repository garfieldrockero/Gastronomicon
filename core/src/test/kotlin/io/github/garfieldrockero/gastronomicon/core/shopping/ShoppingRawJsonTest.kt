package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.FakeMealie
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI

/**
 * El JSON crudo de un elemento de la compra: de él parte el `PUT`, así que
 * perderlo en silencio por pasar de un tamaño equivale a borrar
 * `recipeReferences`, `extras` y las claves de otra versión de Mealie. Estas
 * pruebas fijan qué se reduce, qué se conserva y cuándo se cae al cuerpo mínimo.
 */
class ShoppingRawJsonTest {

    private lateinit var mealie: FakeMealie

    private val listId = "11111111-1111-4111-8111-111111111111"
    private val itemId = "22222222-2222-4222-8222-222222222222"
    private val foodId = "55555555-5555-4555-8555-555555555555"
    private val unitId = "66666666-6666-4666-8666-666666666666"
    private val labelId = "44444444-4444-4444-8444-444444444444"

    private val listPath = "/api/households/shopping/lists/$listId"
    private val itemPath = "/api/households/shopping/items/$itemId"

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun client(): ShoppingClient = ShoppingClient(
        UrlConnectionTransport(
            baseUri = URI("http://127.0.0.1:${mealie.port}"),
            token = mealie.token,
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            maxResponseBytes = 1_000_000,
            totalTimeoutMs = 60_000L,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> success(result: ApiResult<T>): T = (result as ApiResult.Success<T>).value

    private fun bodyOf(request: FakeMealie.Seen): JsonObject =
        CoreJson.json.parseToJsonElement(String(request.body, Charsets.UTF_8)).jsonObject

    private fun listJson(itemJson: String): String =
        """{"id":"$listId","name":"Compra","listItems":[$itemJson]}"""

    /** Un elemento que viene de una receta: alimento con alias, unidad y etiqueta completos. */
    private fun recipeItemJson(aliases: Int, extrasPadding: Int = 0): String {
        val aliasJson = (1..aliases).joinToString(",") { """{"name":"alias-$it-xxxxxxxxxxxxxxxxxxxx"}""" }
        return """
            {
              "id": "$itemId",
              "shoppingListId": "$listId",
              "quantity": 2.0,
              "note": "",
              "checked": false,
              "position": 1,
              "foodId": "$foodId",
              "food": {"id":"$foodId","name":"Leche","pluralName":"Leches","aliases":[$aliasJson]},
              "unitId": "$unitId",
              "unit": {"id":"$unitId","name":"litro","pluralName":"litros","abbreviation":"l","useAbbreviation":false},
              "labelId": "$labelId",
              "label": {"id":"$labelId","name":"Lácteos"},
              "display": "2 litros Leche",
              "recipeReferences": [{"recipeId":"$labelId","recipeQuantity":2}],
              "extras": {"origen":"receta","relleno":"${"y".repeat(extrasPadding)}"},
              "claveNueva": {"a":[1,2,3]}
            }
        """.trimIndent()
    }

    // ------------------------------------------------------- reducción del crudo

    @Test
    fun `un elemento pequeno guarda el crudo entero`() {
        val raw = JsonObject(mapOf("id" to JsonPrimitive(itemId), "food" to JsonObject(emptyMap())))
        assertSame(raw, ShoppingMapper.boundedRaw(raw))
    }

    @Test
    fun `en el limite exacto no se quita nada y un caracter mas reduce`() {
        // «{"k":"…"}» mide el relleno + 8 caracteres.
        fun withLength(total: Int): JsonObject {
            val padding = total - """{"k":""}""".length - ""","food":{}""".length
            return JsonObject(
                linkedMapOf(
                    "k" to JsonPrimitive("z".repeat(padding)),
                    "food" to JsonObject(emptyMap()),
                ),
            )
        }
        val exacto = withLength(ShoppingMapper.MAX_RAW_ITEM_CHARS)
        assertEquals(ShoppingMapper.MAX_RAW_ITEM_CHARS, exacto.toString().length)
        assertTrue(ShoppingMapper.boundedRaw(exacto)!!.containsKey("food"))

        val pasado = withLength(ShoppingMapper.MAX_RAW_ITEM_CHARS + 1)
        val reducido = ShoppingMapper.boundedRaw(pasado)!!
        assertFalse(reducido.containsKey("food"))
        assertTrue(reducido.containsKey("k"))
    }

    @Test
    fun `por encima del tope se quitan solo las claves de solo lectura`() {
        val raw = CoreJson.json.parseToJsonElement(recipeItemJson(aliases = 200)).jsonObject
        assertTrue(raw.toString().length > ShoppingMapper.MAX_RAW_ITEM_CHARS)

        val reducido = ShoppingMapper.boundedRaw(raw)

        assertNotNull(reducido)
        assertEquals(
            raw.keys - setOf("food", "unit", "label", "display"),
            reducido!!.keys,
        )
        assertEquals(foodId, reducido["foodId"]!!.jsonPrimitive.content)
        assertTrue(reducido.containsKey("recipeReferences"))
        assertTrue(reducido.containsKey("extras"))
        assertTrue(reducido.containsKey("claveNueva"))
    }

    @Test
    fun `si aun reducido pasa del tope duro se descarta`() {
        val raw = CoreJson.json.parseToJsonElement(
            recipeItemJson(aliases = 1, extrasPadding = ShoppingMapper.MAX_RAW_ITEM_HARD_CHARS),
        ).jsonObject
        assertNull(ShoppingMapper.boundedRaw(raw))
    }

    // ---------------------------------------------------------- PUT por la red

    @Test
    fun `un elemento de receta de mas de 4 KiB conserva referencias extras y desconocidos en el PUT`() {
        val json = recipeItemJson(aliases = 200)
        assertTrue(json.length > ShoppingMapper.MAX_RAW_ITEM_CHARS)
        mealie.fixed("GET", listPath, 200, listJson(json))
        mealie.fixed("PUT", itemPath, 200, "{}")
        val client = client()
        val item = success(client.getList(listId)).items.single()

        // Lo que se enseña sigue saliendo del DTO, no del crudo reducido.
        assertEquals("Leche", item.foodSingular)
        assertNotNull(item.rawJson)

        assertEquals(ApiResult.Success(Unit), client.setChecked(item, true))

        val body = bodyOf(mealie.requestsTo("PUT", itemPath).single())
        assertTrue(body["checked"]!!.jsonPrimitive.boolean)
        assertEquals(
            labelId,
            body["recipeReferences"]!!.jsonArray.single().jsonObject["recipeId"]!!.jsonPrimitive.content,
        )
        assertEquals("receta", body["extras"]!!.jsonObject["origen"]!!.jsonPrimitive.content)
        assertEquals(3, body["claveNueva"]!!.jsonObject["a"]!!.jsonArray.size)
        // Los ids siguen, así que el servidor vuelve a asociar alimento, unidad y etiqueta.
        assertEquals(foodId, body["foodId"]!!.jsonPrimitive.content)
        assertEquals(unitId, body["unitId"]!!.jsonPrimitive.content)
        assertEquals(labelId, body["labelId"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("food"))
        assertFalse(body.containsKey("display"))
    }

    @Test
    fun `editar un elemento grande tambien conserva el resto del crudo`() {
        mealie.fixed("GET", listPath, 200, listJson(recipeItemJson(aliases = 200)))
        mealie.fixed("PUT", itemPath, 200, "{}")
        val client = client()
        val item = success(client.getList(listId)).items.single()

        assertEquals(ApiResult.Success(Unit), client.editItem(item, "Leche entera", 3.0))

        val body = bodyOf(mealie.requestsTo("PUT", itemPath).single())
        assertEquals("Leche entera", body["note"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("recipeReferences"))
        assertTrue(body.containsKey("extras"))
        assertTrue(body.containsKey("claveNueva"))
    }

    @Test
    fun `un elemento desmesurado cae al cuerpo minimo y el PUT sigue siendo valido`() {
        mealie.fixed(
            "GET",
            listPath,
            200,
            listJson(recipeItemJson(aliases = 1, extrasPadding = ShoppingMapper.MAX_RAW_ITEM_HARD_CHARS)),
        )
        mealie.fixed("PUT", itemPath, 200, "{}")
        val client = client()
        val item = success(client.getList(listId)).items.single()
        assertNull(item.rawJson)

        assertEquals(ApiResult.Success(Unit), client.setChecked(item, true))

        val body = bodyOf(mealie.requestsTo("PUT", itemPath).single())
        assertEquals(
            setOf("shoppingListId", "note", "quantity", "checked", "position", "labelId", "foodId", "unitId"),
            body.keys,
        )
        assertTrue(body["checked"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `sin json crudo el PUT lleva un cuerpo minimo con lo que conoce la app`() {
        mealie.fixed("PUT", itemPath, 200, "{}")
        val item = ShoppingItem(
            id = itemId,
            shoppingListId = listId,
            note = "Pan",
            quantity = 2.0,
            unitSingular = "",
            unitPlural = "",
            foodSingular = "",
            foodPlural = "",
            labelId = null,
            labelName = "",
            checked = false,
            position = 4,
        )
        assertNull(item.rawJson)

        assertEquals(ApiResult.Success(Unit), client().setChecked(item, true))

        val body = bodyOf(mealie.requestsTo("PUT", itemPath).single())
        assertEquals(setOf("shoppingListId", "note", "quantity", "checked", "position"), body.keys)
        assertEquals(listId, body["shoppingListId"]!!.jsonPrimitive.content)
        assertEquals("Pan", body["note"]!!.jsonPrimitive.content)
        assertEquals(4, body["position"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["checked"]!!.jsonPrimitive.boolean)
    }

    // --------------------------------------------------------------- toString

    @Test
    fun `toString de los modelos no saca notas ni nombres del servidor`() {
        val secreto = "NOTA-SECRETA-DEL-SERVIDOR"
        val raw = JsonObject(mapOf("note" to JsonPrimitive(secreto)))
        val item = ShoppingItem(
            id = itemId,
            shoppingListId = listId,
            note = secreto,
            quantity = 1.0,
            unitSingular = "unidad-secreta",
            unitPlural = "unidad-secreta",
            foodSingular = "comida-secreta",
            foodPlural = "comida-secreta",
            labelId = null,
            labelName = "etiqueta-secreta",
            checked = false,
            position = 0,
            rawJson = raw,
        )
        val list = ShoppingList(listId, "lista-secreta", listOf(item))
        val summary = ShoppingListSummary(listId, "lista-secreta")

        for (texto in listOf(item.toString(), list.toString(), summary.toString())) {
            assertFalse(texto, texto.contains(secreto))
            assertFalse(texto, texto.contains("secret", ignoreCase = true))
        }
        assertTrue(item.toString().contains("hasRaw=true"))
    }
}
