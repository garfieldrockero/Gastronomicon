package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftOrganizer
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El PUT parte del JSON entero del GET: no puede perder campos que esta versión
 * de la app no conoce, ni inventar valores, ni reenviar lo que Mealie rellena.
 */
class RecipeDraftMergeTest {

    private val ingredientId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val stepId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val noteId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val categoryId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private val managed = RecipeDraftJson.SERVER_MANAGED_KEYS

    private val originalText = """
        {
          "id": "11111111-1111-4111-8111-111111111111",
          "userId": "22222222-2222-4222-8222-222222222222",
          "householdId": "33333333-3333-4333-8333-333333333333",
          "groupId": "44444444-4444-4444-8444-444444444444",
          "name": "Tarta",
          "slug": "tarta",
          "image": "abc1",
          "recipeServings": 4,
          "recipeYieldQuantity": 4,
          "recipeYield": "4 raciones",
          "totalTime": "1 hora",
          "prepTime": "20 min",
          "cookTime": null,
          "performTime": "10 min",
          "totalTimeSeconds": 3600,
          "prepTimeSeconds": 1200,
          "performTimeSeconds": 600,
          "description": "Rica",
          "recipeCategory": [
            {"id": "$categoryId", "groupId": "g1", "name": "Postres", "slug": "postres", "recipeCount": 3}
          ],
          "tags": [],
          "tools": [{"id": "t1", "name": "Horno", "slug": "horno"}],
          "rating": 4.5,
          "orgURL": "https://ejemplo.org/r",
          "dateAdded": "2026-01-01",
          "dateUpdated": "2026-02-02T10:00:00",
          "createdAt": "2026-01-01T10:00:00",
          "updatedAt": "2026-02-02T10:00:00",
          "recipeIngredient": [
            {
              "quantity": 2.0,
              "unit": {"id": "u1", "name": "cucharada", "abbreviation": "cda"},
              "food": {"id": "f1", "name": "azúcar"},
              "note": "fino",
              "display": "2 cucharada azúcar fino",
              "title": null,
              "originalText": null,
              "referenceId": "$ingredientId",
              "campoFuturoDeLinea": {"a": [1, 2, 3]}
            }
          ],
          "recipeInstructions": [
            {
              "id": "$stepId",
              "title": "",
              "summary": "",
              "text": "<p>Mezclar</p>",
              "ingredientReferences": [{"referenceId": "$ingredientId"}]
            }
          ],
          "nutrition": {"calories": "200"},
          "settings": {"public": true},
          "assets": [],
          "notes": [{"title": "Truco", "text": "Reposar", "referenceId": "$noteId"}],
          "extras": {"x": "1"},
          "comments": [],
          "campoNuevo2027": {"k": [1, 2, 3], "n": null}
        }
    """.trimIndent()

    private fun original(): JsonObject = CoreJson.json.parseToJsonElement(originalText) as JsonObject

    private fun body(merged: JsonObject): JsonObject = merged

    /** Lo que debe salir si no se edita nada: el original menos las marcas de tiempo que rellena Mealie. */
    private fun untouched(): Map<String, JsonElement> = original().filterKeys { it !in managed }

    private fun array(obj: JsonObject, key: String): JsonArray = obj[key] as JsonArray

    // ---------------------------------------------------------------- sin editar

    @Test
    fun `sin editar nada el cuerpo es el original sin las marcas de tiempo del servidor`() {
        val source = original()
        val merged = body(RecipeDraftJson.merge(source, RecipeDraftJson.toDraft(source)))
        assertEquals(untouched(), merged.toMap())
        for (key in listOf("dateUpdated", "updatedAt", "update_at", "createdAt")) {
            assertFalse(key, merged.containsKey(key))
        }
        // Lo desconocido, lo anidado y los null explícitos siguen intactos.
        assertEquals(source["campoNuevo2027"], merged["campoNuevo2027"])
        assertEquals(source["tools"], merged["tools"])
        assertEquals(JsonNull, merged["cookTime"])
        assertEquals(source["recipeIngredient"], merged["recipeIngredient"])
    }

    @Test
    fun `el orden de las claves se conserva`() {
        val source = original()
        val merged = body(RecipeDraftJson.merge(source, RecipeDraftJson.toDraft(source)))
        assertEquals(source.keys.filter { it !in managed }, merged.keys.toList())
    }

    @Test
    fun `un texto con caracteres que el saneado quita no se reescribe si no se toca`() {
        val source = CoreJson.json.parseToJsonElement(
            """{"name":"Tarta","description":"Rica\u0007‮","prepTime":"  20   min "}""",
        ) as JsonObject
        val merged = body(RecipeDraftJson.merge(source, RecipeDraftJson.toDraft(source)))
        assertEquals(source["description"], merged["description"])
        assertEquals(source["prepTime"], merged["prepTime"])
    }

    // --------------------------------------------------------------- campos sueltos

    @Test
    fun `editar nombre descripcion y un tiempo cambia solo eso`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source).copy(
            name = "Tarta de limón",
            description = "Muy rica",
            prepTime = "30 min",
        )
        val merged = body(RecipeDraftJson.merge(source, draft))
        val expected = untouched().toMutableMap()
        expected["name"] = JsonPrimitive("Tarta de limón")
        expected["description"] = JsonPrimitive("Muy rica")
        expected["prepTime"] = JsonPrimitive("30 min")
        // Los segundos los calcula Mealie del texto: el valor viejo ya no vale.
        expected["prepTimeSeconds"] = JsonNull
        assertEquals(expected, merged.toMap())
        assertEquals(JsonPrimitive(3600), merged["totalTimeSeconds"])
    }

    @Test
    fun `vaciar un tiempo lo pone a null y no inventa claves de segundos que no existian`() {
        val source = CoreJson.json.parseToJsonElement("""{"name":"T","prepTime":"5 min","cookTime":"1 h"}""") as JsonObject
        val draft = RecipeDraftJson.toDraft(source).copy(prepTime = "", cookTime = "2 h")
        val merged = body(RecipeDraftJson.merge(source, draft))
        assertEquals(JsonNull, merged["prepTime"])
        assertEquals(JsonPrimitive("2 h"), merged["cookTime"])
        assertFalse(merged.containsKey("prepTimeSeconds"))
        assertFalse(merged.containsKey("cookTimeSeconds"))
    }

    @Test
    fun `las raciones se editan y sin raciones se escribe cero`() {
        val source = original()
        val six = body(RecipeDraftJson.merge(source, RecipeDraftJson.toDraft(source).copy(servings = 6.0)))
        assertEquals(JsonPrimitive(6.0), six["recipeServings"])
        val none = body(RecipeDraftJson.merge(source, RecipeDraftJson.toDraft(source).copy(servings = null)))
        assertEquals(JsonPrimitive(0.0), none["recipeServings"])
        assertEquals(source["recipeYieldQuantity"], six["recipeYieldQuantity"])
    }

    // --------------------------------------------------------------- ingredientes

    @Test
    fun `editar la nota de una linea conserva unidad alimento y campos desconocidos`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(ingredients = listOf(draft.ingredients[0].copy(note = "muy fino")))
        val line = array(body(RecipeDraftJson.merge(source, edited)), "recipeIngredient")[0] as JsonObject
        val before = (source["recipeIngredient"] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive("muy fino"), line["note"])
        assertEquals(before["unit"], line["unit"])
        assertEquals(before["food"], line["food"])
        assertEquals(before["referenceId"], line["referenceId"])
        assertEquals(before["campoFuturoDeLinea"], line["campoFuturoDeLinea"])
        assertEquals(before["quantity"], line["quantity"])
        // El texto compuesto por el servidor ya no corresponde: lo recalcula él.
        assertFalse(line.containsKey("display"))
    }

    @Test
    fun `cambiar la cantidad y el titulo de una linea`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(ingredients = listOf(draft.ingredients[0].copy(quantity = 3.0, title = "Base")))
        val line = array(body(RecipeDraftJson.merge(source, edited)), "recipeIngredient")[0] as JsonObject
        assertEquals(JsonPrimitive(3.0), line["quantity"])
        assertEquals(JsonPrimitive("Base"), line["title"])
        val cleared = draft.copy(ingredients = listOf(draft.ingredients[0].copy(quantity = null)))
        val line2 = array(body(RecipeDraftJson.merge(source, cleared)), "recipeIngredient")[0] as JsonObject
        assertEquals(JsonNull, line2["quantity"])
    }

    @Test
    fun `cambiar el nombre de la unidad o del alimento reescribe solo esa referencia`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(ingredients = listOf(draft.ingredients[0].copy(unit = "gramo", food = "sal")))
        val line = array(body(RecipeDraftJson.merge(source, edited)), "recipeIngredient")[0] as JsonObject
        val before = (source["recipeIngredient"] as JsonArray)[0] as JsonObject
        // Sin id, el nombre viaja como `CreateIngredientUnit`/`CreateIngredientFood`.
        assertEquals(JsonObject(mapOf("name" to JsonPrimitive("gramo"))), line["unit"])
        assertEquals(JsonObject(mapOf("name" to JsonPrimitive("sal"))), line["food"])
        // El resto de la línea se conserva tal cual.
        assertEquals(before["quantity"], line["quantity"])
        assertEquals(before["note"], line["note"])
        assertEquals(before["referenceId"], line["referenceId"])
        assertEquals(before["campoFuturoDeLinea"], line["campoFuturoDeLinea"])
        assertFalse(line.containsKey("display"))
    }

    @Test
    fun `una linea nueva solo lleva cantidad nota y titulo`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(
            ingredients = draft.ingredients + DraftIngredient(quantity = 1.0, note = "sal", title = "Extra"),
        )
        val lines = array(body(RecipeDraftJson.merge(source, edited)), "recipeIngredient")
        assertEquals(2, lines.size)
        assertEquals((source["recipeIngredient"] as JsonArray)[0], lines[0])
        assertEquals(
            JsonObject(
                mapOf(
                    "quantity" to JsonPrimitive(1.0),
                    "note" to JsonPrimitive("sal"),
                    "title" to JsonPrimitive("Extra"),
                ),
            ),
            lines[1],
        )
    }

    @Test
    fun `una linea con un id que el servidor ya no tiene se trata como nueva`() {
        val source = original()
        val ghost = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val edited = RecipeDraftJson.toDraft(source).copy(ingredients = listOf(DraftIngredient(note = "sal", sourceId = ghost)))
        val lines = array(body(RecipeDraftJson.merge(source, edited)), "recipeIngredient")
        assertEquals(1, lines.size)
        assertEquals(JsonObject(mapOf("note" to JsonPrimitive("sal"))), lines[0])
    }

    @Test
    fun `borrar todas las lineas deja una lista vacia y no toca el resto`() {
        val source = original()
        val merged = body(RecipeDraftJson.merge(source, RecipeDraftJson.toDraft(source).copy(ingredients = emptyList())))
        assertEquals(JsonArray(emptyList()), merged["recipeIngredient"])
        assertEquals(source["recipeInstructions"], merged["recipeInstructions"])
    }

    // ------------------------------------------------------------ pasos y notas

    @Test
    fun `editar el texto de un paso conserva sus referencias a ingredientes`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(steps = listOf(draft.steps[0].copy(text = "Mezclar bien")))
        val step = array(body(RecipeDraftJson.merge(source, edited)), "recipeInstructions")[0] as JsonObject
        val before = (source["recipeInstructions"] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive("Mezclar bien"), step["text"])
        assertEquals(before["ingredientReferences"], step["ingredientReferences"])
        assertEquals(before["id"], step["id"])
    }

    @Test
    fun `un paso nuevo y una nota editada`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(
            steps = draft.steps + DraftStep(title = "Hornear", text = "180 grados"),
            notes = listOf(draft.notes[0].copy(text = "Reposar una hora")),
        )
        val merged = body(RecipeDraftJson.merge(source, edited))
        val steps = array(merged, "recipeInstructions")
        assertEquals(
            JsonObject(mapOf("title" to JsonPrimitive("Hornear"), "text" to JsonPrimitive("180 grados"))),
            steps[1],
        )
        val note = array(merged, "notes")[0] as JsonObject
        assertEquals(JsonPrimitive("Reposar una hora"), note["text"])
        assertEquals(JsonPrimitive(noteId), note["referenceId"])
        assertEquals(JsonPrimitive("Truco"), note["title"])
    }

    // ---------------------------------------------------- etiquetas y categorias

    @Test
    fun `una categoria sin cambios sale tal cual y una etiqueta nueva solo lleva nombre`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        assertEquals(DraftOrganizer(categoryId, "Postres", "postres"), draft.categories[0])
        val edited = draft.copy(tags = listOf(DraftOrganizer(name = "Cena")))
        val merged = body(RecipeDraftJson.merge(source, edited))
        assertEquals(source["recipeCategory"], merged["recipeCategory"])
        assertEquals(JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Cena"))))), merged["tags"])
    }

    @Test
    fun `cambiar la categoria elegida usa el id y el slug de la lista del servidor`() {
        val source = original()
        val other = "99999999-9999-4999-8999-999999999999"
        val edited = RecipeDraftJson.toDraft(source).copy(categories = listOf(DraftOrganizer(other, "Cenas", "cenas")))
        val merged = body(RecipeDraftJson.merge(source, edited))
        assertEquals(
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf("id" to JsonPrimitive(other), "name" to JsonPrimitive("Cenas"), "slug" to JsonPrimitive("cenas")),
                    ),
                ),
            ),
            merged["recipeCategory"],
        )
    }

    @Test
    fun `renombrar una categoria que ya estaba conserva el resto de sus campos`() {
        val source = original()
        val edited = RecipeDraftJson.toDraft(source).copy(categories = listOf(DraftOrganizer(categoryId, "Dulces", "dulces")))
        val category = array(body(RecipeDraftJson.merge(source, edited)), "recipeCategory")[0] as JsonObject
        assertEquals(JsonPrimitive("Dulces"), category["name"])
        assertEquals(JsonPrimitive("g1"), category["groupId"])
        assertEquals(JsonPrimitive(3), category["recipeCount"])
    }

    // ------------------------------------------------------------- lectura hostil

    @Test
    fun `toDraft con tipos inesperados no revienta ni inventa`() {
        val hostile = CoreJson.json.parseToJsonElement(
            """
            {"name": 5, "description": {"x": 1}, "recipeServings": "4", "prepTime": [],
             "recipeIngredient": ["texto", 7, null, {"quantity": "2", "note": 3, "referenceId": "no", "unit": "g"}],
             "recipeInstructions": {"no": "lista"}, "notes": null,
             "tags": [{"name": "  "}, {"name": "<b>x</b>", "slug": "../x", "id": "1"}],
             "recipeCategory": "no"}
            """.trimIndent(),
        ) as JsonObject
        val draft = RecipeDraftJson.toDraft(hostile)
        assertEquals("", draft.name)
        assertEquals("", draft.description)
        assertNull(draft.servings)
        assertEquals("", draft.prepTime)
        assertEquals(listOf(DraftIngredient()), draft.ingredients)
        assertTrue(draft.steps.isEmpty())
        assertTrue(draft.notes.isEmpty())
        assertEquals(listOf(DraftOrganizer(null, "<b>x</b>", null)), draft.tags)
        assertTrue(draft.categories.isEmpty())
    }

    @Test
    fun `los ids de linea de toDraft salen en minusculas`() {
        val source = CoreJson.json.parseToJsonElement(
            """{"recipeInstructions":[{"id":"${stepId.uppercase()}","text":"a"}]}""",
        ) as JsonObject
        assertEquals(stepId, RecipeDraftJson.toDraft(source).steps[0].sourceId)
    }
}
