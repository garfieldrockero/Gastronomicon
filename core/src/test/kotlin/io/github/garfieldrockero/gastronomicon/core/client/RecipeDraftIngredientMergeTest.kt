package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftValidation
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unidad y alimento de una línea de ingredientes: qué se lee del servidor y
 * qué se envía en el `PUT` cuando se eligen del catálogo.
 *
 * La regla es la de siempre: se parte del JSON crudo del `GET` y solo se
 * reescribe la referencia que cambia; el resto de la línea (nota, título,
 * receta referenciada, claves que esta versión no conoce) sale tal cual.
 */
class RecipeDraftIngredientMergeTest {

    private val ingredientId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val unitId = "11111111-1111-4111-8111-111111111111"
    private val otherUnitId = "33333333-3333-4333-8333-333333333333"
    private val foodId = "22222222-2222-4222-8222-222222222222"

    private val originalText = """
        {
          "name": "Tarta",
          "recipeIngredient": [
            {
              "quantity": 2.0,
              "unit": {"id": "$unitId", "name": "cucharada", "abbreviation": "cda", "extras": {"a": 1}},
              "food": {"id": "$foodId", "name": "azúcar", "pluralName": "azúcares"},
              "note": "fino",
              "display": "2 cucharadas de azúcar fino",
              "referenceId": "$ingredientId",
              "campoFuturo": {"k": [1, 2]}
            }
          ]
        }
    """.trimIndent()

    private fun original(): JsonObject = CoreJson.json.parseToJsonElement(originalText) as JsonObject

    private fun line(merged: JsonObject, index: Int = 0): JsonObject =
        (merged["recipeIngredient"] as JsonArray)[index] as JsonObject

    @Test
    fun `al leer se guardan los ids de unidad y alimento`() {
        val ingredient = RecipeDraftJson.toDraft(original()).ingredients.single()
        assertEquals("cucharada", ingredient.unit)
        assertEquals(unitId, ingredient.unitId)
        assertEquals("azúcar", ingredient.food)
        assertEquals(foodId, ingredient.foodId)
    }

    @Test
    fun `sin tocar nada la linea sale identica al servidor`() {
        val source = original()
        val merged = RecipeDraftJson.merge(source, RecipeDraftJson.toDraft(source))
        assertEquals(source["recipeIngredient"], merged["recipeIngredient"])
    }

    @Test
    fun `elegir otra unidad manda la referencia minima y no toca el alimento`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(
            ingredients = listOf(draft.ingredients[0].copy(unit = "gramo", unitId = otherUnitId)),
        )
        val merged = line(RecipeDraftJson.merge(source, edited))
        assertEquals(
            JsonObject(mapOf("id" to JsonPrimitive(otherUnitId), "name" to JsonPrimitive("gramo"))),
            merged["unit"],
        )
        // El alimento conserva su objeto entero (con `pluralName`).
        val before = (source["recipeIngredient"] as JsonArray)[0] as JsonObject
        assertEquals(before["food"], merged["food"])
        assertEquals(JsonPrimitive("fino"), merged["note"])
        assertEquals(JsonPrimitive(ingredientId), merged["referenceId"])
        assertEquals(JsonPrimitive(1), ((merged["campoFuturo"] as JsonObject)["k"] as JsonArray)[0])
        assertNull(merged["display"])
    }

    @Test
    fun `cambiar el nombre con el mismo id no renombra el catalogo`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(ingredients = listOf(draft.ingredients[0].copy(unit = "cucharadas")))
        val merged = line(RecipeDraftJson.merge(source, edited))
        // Solo la referencia por id: reenviar el nombre nuevo con el id viejo
        // renombraría la unidad del catálogo para todas las recetas.
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive(unitId))), merged["unit"])
        assertEquals(JsonPrimitive("fino"), merged["note"])
        assertEquals(JsonPrimitive(ingredientId), merged["referenceId"])
    }

    @Test
    fun `cambiar solo el id con el mismo nombre se detecta y se envia`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(ingredients = listOf(draft.ingredients[0].copy(unitId = otherUnitId)))
        val merged = line(RecipeDraftJson.merge(source, edited))
        // El nombre no cambia, pero la unidad elegida es otra: sin esto el
        // cambio se perdería en silencio.
        assertEquals(
            JsonObject(mapOf("id" to JsonPrimitive(otherUnitId), "name" to JsonPrimitive("cucharada"))),
            merged["unit"],
        )
        assertEquals(JsonPrimitive(foodId), (merged["food"] as JsonObject)["id"])
    }

    @Test
    fun `una linea con id de unidad y nombre vacio no bloquea ni se reescribe`() {
        // El servidor puede devolver un nombre que el saneado deja vacío. El id
        // se descarta al leer y la línea sin tocar sale idéntica.
        val source = CoreJson.json.parseToJsonElement(
            """
            {
              "name": "Tarta",
              "recipeIngredient": [
                {
                  "unit": {"id": "$unitId", "name": "\u202E"},
                  "note": "fino",
                  "referenceId": "$ingredientId"
                }
              ]
            }
            """.trimIndent(),
        ) as JsonObject
        val draft = RecipeDraftJson.toDraft(source)
        assertNull(draft.ingredients.single().unitId)
        assertEquals("", draft.ingredients.single().unit)

        assertTrue(RecipeDraftValidator.validate(draft) is DraftValidation.Valid)
        assertEquals(source["recipeIngredient"], RecipeDraftJson.merge(source, draft)["recipeIngredient"])
    }

    @Test
    fun `quitar la unidad la deja a null y el resto de la linea sigue igual`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(ingredients = listOf(draft.ingredients[0].copy(unit = "", unitId = null)))
        val merged = line(RecipeDraftJson.merge(source, edited))
        assertEquals(JsonNull, merged["unit"])
        val before = (source["recipeIngredient"] as JsonArray)[0] as JsonObject
        assertEquals(before["food"], merged["food"])
    }

    @Test
    fun `una linea nueva lleva la unidad y el alimento elegidos`() {
        val source = original()
        val base = RecipeDraftJson.toDraft(source)
        val draft = base.copy(
            ingredients = base.ingredients + DraftIngredient(
                quantity = 1.0,
                unit = "gramo",
                unitId = unitId,
                food = "harina",
                foodId = foodId,
                note = "tamizada",
            ),
        )
        val added = line(RecipeDraftJson.merge(source, draft), index = 1)
        assertEquals(
            JsonObject(
                mapOf(
                    "quantity" to JsonPrimitive(1.0),
                    "unit" to JsonObject(mapOf("id" to JsonPrimitive(unitId), "name" to JsonPrimitive("gramo"))),
                    "food" to JsonObject(mapOf("id" to JsonPrimitive(foodId), "name" to JsonPrimitive("harina"))),
                    "note" to JsonPrimitive("tamizada"),
                ),
            ),
            added,
        )
    }

    @Test
    fun `una linea nueva con solo el nombre del alimento va como creacion por nombre`() {
        val source = original()
        val base = RecipeDraftJson.toDraft(source)
        val edited = base.copy(ingredients = base.ingredients + DraftIngredient(food = "sal", note = "una pizca"))
        val added = line(RecipeDraftJson.merge(source, edited), index = 1)
        assertEquals(JsonObject(mapOf("name" to JsonPrimitive("sal"))), added["food"])
        assertNull(added["unit"])
    }

    @Test
    fun `un borrador que perdio los ids no reescribe la linea`() {
        // Es el caso de un borrador recuperado de disco por una versión que aún
        // no guardaba `unitId`/`foodId`: los nombres son los mismos.
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val withoutIds = draft.copy(
            ingredients = listOf(draft.ingredients[0].copy(unitId = null, foodId = null)),
        )
        assertEquals(source["recipeIngredient"], RecipeDraftJson.merge(source, withoutIds)["recipeIngredient"])
    }

    @Test
    fun `cambiar la nota de una linea conserva las dos referencias enteras`() {
        val source = original()
        val draft = RecipeDraftJson.toDraft(source)
        val edited = draft.copy(ingredients = listOf(draft.ingredients[0].copy(note = "muy fino")))
        val merged = line(RecipeDraftJson.merge(source, edited))
        val before = (source["recipeIngredient"] as JsonArray)[0] as JsonObject
        assertEquals(before["unit"], merged["unit"])
        assertEquals(before["food"], merged["food"])
        assertEquals(JsonPrimitive("muy fino"), merged["note"])
    }
}
