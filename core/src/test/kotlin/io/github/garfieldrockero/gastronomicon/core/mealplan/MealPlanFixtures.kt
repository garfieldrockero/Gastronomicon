package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * Ayudas de las pruebas del planificador: una entrada del modelo y su JSON,
 * para no repetir plantillas con comillas y no colar un escape mal.
 */
internal object MealPlanFixtures {

    const val GROUP = "11111111-1111-4111-8111-111111111111"
    const val USER = "22222222-2222-4222-8222-222222222222"
    const val RECIPE = "33333333-3333-4333-8333-333333333333"

    fun plannedMeal(
        id: Long,
        date: LocalDate,
        mealType: MealType,
        title: String = "Sopa",
        note: String = "",
        recipeId: String? = null,
        recipeName: String? = null,
        recipeSlug: String? = null,
        recipeScale: Double = 1.0,
        groupId: String? = GROUP,
        userId: String? = USER,
    ): PlannedMeal = PlannedMeal(
        id = id,
        date = date,
        mealType = mealType,
        title = title,
        note = note,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeSlug = recipeSlug,
        recipeScale = recipeScale,
        groupId = groupId,
        userId = userId,
    )

    /** `ReadPlanEntry` en JSON; con [recipeName] se añade el resumen de receta. */
    fun entryJson(
        id: Long = 1,
        date: String = "2026-11-09",
        type: String = "lunch",
        title: String = "Sopa",
        text: String = "",
        recipeId: String? = null,
        recipeName: String? = null,
        recipeSlug: String = "sopa",
        groupId: String = GROUP,
        userId: String = USER,
    ): String {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["id"] = JsonPrimitive(id)
        fields["date"] = JsonPrimitive(date)
        fields["entryType"] = JsonPrimitive(type)
        fields["title"] = JsonPrimitive(title)
        fields["text"] = JsonPrimitive(text)
        fields["groupId"] = JsonPrimitive(groupId)
        fields["userId"] = JsonPrimitive(userId)
        if (recipeId != null) fields["recipeId"] = JsonPrimitive(recipeId)
        if (recipeName != null) {
            fields["recipe"] = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(recipeId ?: RECIPE),
                    "slug" to JsonPrimitive(recipeSlug),
                    "name" to JsonPrimitive(recipeName),
                ),
            )
        }
        return CoreJson.json.encodeToString(JsonElement.serializer(), JsonObject(fields))
    }

    fun pageJson(
        items: List<String>,
        page: Int = 1,
        perPage: Int = items.size.coerceAtLeast(1),
        total: Long = items.size.toLong(),
    ): String = """{"page":$page,"per_page":$perPage,"total":$total,"items":[${items.joinToString(",")}]}"""
}
