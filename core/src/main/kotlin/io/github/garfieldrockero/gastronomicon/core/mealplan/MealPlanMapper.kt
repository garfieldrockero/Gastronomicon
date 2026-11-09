package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Convierte los DTO del planificador en [PlannedMeal] ya saneados y acotados.
 *
 * Es el único sitio por el que los textos del servidor entran en el estado de
 * la app. Una entrada sin id utilizable o con una fecha que no se puede leer
 * se descarta: no se puede colocar en el calendario sin inventarse el día.
 *
 * Además del DTO se guarda la entrada JSON cruda (acotada a
 * [MAX_RAW_ENTRY_CHARS]): mover o editar parte de ella para no perder campos
 * que esta versión de la app no conoce ni reescribir lo que no se ha tocado.
 */
internal object MealPlanMapper {

    const val MAX_TITLE = MealPlanDraftValidator.MAX_TITLE
    const val MAX_TEXT = MealPlanDraftValidator.MAX_TEXT
    const val MAX_RECIPE_NAME = 300

    /**
     * Tamaño hasta el que el JSON crudo de una entrada se guarda entero. Por
     * encima se reduce quitando `recipe` (ver [boundedRaw]).
     */
    const val MAX_RAW_ENTRY_CHARS = 8_192

    /** Tope duro tras quitar `recipe`; la respuesta entera ya va acotada a 2 MiB por el transporte. */
    const val MAX_RAW_ENTRY_HARD_CHARS = 32_768

    private val READ_ONLY_KEYS = setOf("recipe")

    /** Null si la entrada no se puede usar (sin id o sin fecha válida). */
    fun entry(dto: PlanEntryDto, raw: JsonObject? = null): PlannedMeal? {
        val id = MealPlanIds.checked(dto.id) ?: return null
        val date = parseDate(dto.date) ?: return null
        val recipe = dto.recipe
        val recipeName = PlainTextSanitizer.sanitize(recipe?.name, MAX_RECIPE_NAME).ifEmpty { null }
        return PlannedMeal(
            id = id,
            date = date,
            mealType = MealType.fromApi(dto.entryType),
            title = PlainTextSanitizer.sanitize(dto.title, MAX_TITLE),
            note = PlainTextSanitizer.sanitize(dto.text, MAX_TEXT),
            recipeId = RecipeImage.normalizeId(dto.recipeId),
            recipeName = recipeName,
            recipeSlug = recipe?.slug?.takeIf { RecipeSlug.isValid(it) },
            recipeScale = scale(dto.recipeScale),
            groupId = RecipeImage.normalizeId(dto.groupId),
            userId = RecipeImage.normalizeId(dto.userId),
            rawJson = boundedRaw(raw),
        )
    }

    /**
     * El JSON crudo, acotado sin perder lo que el `PUT` necesita: hasta
     * [MAX_RAW_ENTRY_CHARS] entero; por encima se quita `recipe` (el resumen de
     * receta que el servidor recompone desde `recipeId`) y se conserva el resto.
     * Solo si aun así pasa de [MAX_RAW_ENTRY_HARD_CHARS] se descarta.
     */
    internal fun boundedRaw(raw: JsonObject?): JsonObject? {
        if (raw == null) return null
        if (raw.toString().length <= MAX_RAW_ENTRY_CHARS) return raw
        val slim = JsonObject(raw.filterKeys { it !in READ_ONLY_KEYS })
        return if (slim.toString().length <= MAX_RAW_ENTRY_HARD_CHARS) slim else null
    }

    /**
     * Solo `yyyy-MM-dd`. Se exige longitud 10 para no admitir años de cinco
     * cifras (que el servidor no espera y que la consulta no puede llevar) y
     * se comprueba además el rango de años del planificador.
     */
    private fun parseDate(raw: String?): LocalDate? {
        val text = raw?.trim() ?: return null
        if (text.length != 10) return null
        val date = try {
            LocalDate.parse(text)
        } catch (ignored: DateTimeParseException) {
            return null
        }
        return date.takeIf { MealPlanDates.check(it) == null }
    }

    /** Raciones utilizables; 1.0 si el servidor no manda un número con sentido. */
    private fun scale(raw: Double?): Double =
        raw?.takeIf { it.isFinite() && it > 0.0 && it <= MealPlanDraftValidator.MAX_SCALE } ?: 1.0
}
