package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.html.HtmlSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.SafeUrl
import io.github.garfieldrockero.gastronomicon.core.recipes.Ingredient
import io.github.garfieldrockero.gastronomicon.core.recipes.Label
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeNote
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummary
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeTimes
import io.github.garfieldrockero.gastronomicon.core.recipes.ServingScaler
import io.github.garfieldrockero.gastronomicon.core.recipes.Step
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Convierte los DTO del servidor en modelos ya saneados y acotados.
 *
 * Es el único sitio por el que los textos del servidor entran en el estado de
 * la app. Los límites de longitud y de cantidad protegen memoria y pantalla:
 * el transporte ya acota la respuesta entera, pero dentro de ella una sola
 * lista podría traer decenas de miles de elementos.
 */
internal object RecipeMapper {

    const val MAX_NAME = 300
    const val MAX_DESCRIPTION = 5_000
    const val MAX_LABEL = 100
    const val MAX_TIME_TEXT = 100
    const val MAX_INGREDIENT_TEXT = 500
    const val MAX_UNIT_FOOD = 100
    const val MAX_STEP_TITLE = 300

    const val MAX_LABELS = 100
    const val MAX_INGREDIENTS = 500
    const val MAX_STEPS = 500
    const val MAX_NOTES = 100

    /** Raciones o cantidades por encima de esto son basura, no una receta. */
    private const val MAX_SERVINGS = 10_000.0
    private const val MAX_RATING = 5.0

    /** Null si el slug que manda el servidor no pasa la lista blanca: no se podría abrir sin riesgo. */
    fun summary(dto: RecipeSummaryDto): RecipeSummary? {
        val slug = dto.slug?.takeIf { RecipeSlug.isValid(it) } ?: return null
        return RecipeSummary(
            id = RecipeImage.normalizeId(dto.id),
            slug = slug,
            name = text(dto.name, MAX_NAME),
            description = multiline(dto.description, MAX_DESCRIPTION),
            hasImage = hasImage(dto.image),
            servings = servings(dto.recipeServings),
            rating = rating(dto.rating),
            times = times(
                dto.totalTime, dto.prepTime, dto.cookTime, dto.performTime,
                dto.totalTimeSeconds, dto.prepTimeSeconds, dto.performTimeSeconds,
            ),
            categories = labels(dto.recipeCategory),
            tags = labels(dto.tags),
        )
    }

    /** [requestedSlug] ya está validado: es el de la ruta y el que se usa si el servidor no manda uno bueno. */
    fun recipe(dto: RecipeDto, requestedSlug: String): Recipe =
        Recipe(
            id = RecipeImage.normalizeId(dto.id),
            slug = dto.slug?.takeIf { RecipeSlug.isValid(it) } ?: requestedSlug,
            name = text(dto.name, MAX_NAME),
            description = multiline(dto.description, MAX_DESCRIPTION),
            hasImage = hasImage(dto.image),
            servings = servings(dto.recipeServings),
            yieldText = text(dto.recipeYield, MAX_TIME_TEXT),
            rating = rating(dto.rating),
            sourceUrl = SafeUrl.httpOrNull(dto.orgUrl),
            times = times(
                dto.totalTime, dto.prepTime, dto.cookTime, dto.performTime,
                dto.totalTimeSeconds, dto.prepTimeSeconds, dto.performTimeSeconds,
            ),
            categories = labels(dto.recipeCategory),
            tags = labels(dto.tags),
            ingredients = dto.recipeIngredient.orEmpty().asSequence()
                .filterNotNull().take(MAX_INGREDIENTS * 2).mapNotNull { ingredient(it) }
                .take(MAX_INGREDIENTS).toList(),
            steps = dto.recipeInstructions.orEmpty().asSequence()
                .filterNotNull().take(MAX_STEPS * 2).mapNotNull { step(it) }
                .take(MAX_STEPS).toList(),
            notes = dto.notes.orEmpty().asSequence()
                .filterNotNull().take(MAX_NOTES * 2).mapNotNull { note(it) }
                .take(MAX_NOTES).toList(),
        )

    // ------------------------------------------------------------ elementos

    private fun ingredient(dto: IngredientDto): Ingredient? {
        val unit = dto.unit
        val unitName = text(unit?.name, MAX_UNIT_FOOD)
        val unitPluralName = text(unit?.pluralName, MAX_UNIT_FOOD)
        val abbreviation = text(unit?.abbreviation, MAX_UNIT_FOOD)
        val pluralAbbreviation = text(unit?.pluralAbbreviation, MAX_UNIT_FOOD)
        // Con «usar abreviatura» y una abreviatura vacía se cae al nombre
        // completo; sin plural propio, el singular sirve (g, ml…).
        val useAbbreviation = unit?.useAbbreviation == true && abbreviation.isNotEmpty()
        val unitSingular = if (useAbbreviation) abbreviation else unitName
        val unitPlural = if (useAbbreviation) {
            pluralAbbreviation.ifEmpty { abbreviation }
        } else {
            unitPluralName.ifEmpty { unitName }
        }
        val foodSingular = text(dto.food?.name, MAX_UNIT_FOOD)
        val foodPlural = text(dto.food?.pluralName, MAX_UNIT_FOOD).ifEmpty { foodSingular }

        val ingredient = Ingredient(
            title = text(dto.title, MAX_INGREDIENT_TEXT),
            quantity = quantity(dto.quantity),
            unitSingular = unitSingular,
            unitPlural = unitPlural,
            foodSingular = foodSingular,
            foodPlural = foodPlural,
            note = text(dto.note, MAX_INGREDIENT_TEXT),
            display = text(dto.display, MAX_INGREDIENT_TEXT),
        )
        // Una línea sin texto de ningún tipo no aporta nada (la cantidad sola
        // tampoco se puede leer).
        val empty = ingredient.title.isEmpty() && ingredient.unitSingular.isEmpty() &&
            ingredient.foodSingular.isEmpty() && ingredient.note.isEmpty() && ingredient.display.isEmpty()
        return if (empty) null else ingredient
    }

    private fun step(dto: StepDto): Step? {
        val step = Step(
            title = text(dto.title, MAX_STEP_TITLE),
            summary = text(dto.summary, MAX_STEP_TITLE),
            text = HtmlSanitizer.sanitize(dto.text),
        )
        val empty = step.title.isEmpty() && step.summary.isEmpty() && step.text.blocks.isEmpty()
        return if (empty) null else step
    }

    private fun note(dto: NoteDto): RecipeNote? {
        val note = RecipeNote(
            title = text(dto.title, MAX_STEP_TITLE),
            text = HtmlSanitizer.sanitize(dto.text),
        )
        return if (note.title.isEmpty() && note.text.blocks.isEmpty()) null else note
    }

    private fun labels(raw: List<LabelDto?>?): List<Label> =
        raw.orEmpty().asSequence()
            .filterNotNull()
            .take(MAX_LABELS * 2)
            .mapNotNull { dto ->
                val name = text(dto.name, MAX_LABEL)
                if (name.isEmpty()) null else Label(name, dto.slug?.takeIf { RecipeSlug.isValid(it) } ?: "")
            }
            .take(MAX_LABELS)
            .toList()

    private fun times(
        total: String?,
        prep: String?,
        cook: String?,
        perform: String?,
        totalSeconds: Int?,
        prepSeconds: Int?,
        performSeconds: Int?,
    ): RecipeTimes = RecipeTimes(
        total = text(total, MAX_TIME_TEXT),
        prep = text(prep, MAX_TIME_TEXT),
        cook = text(cook, MAX_TIME_TEXT),
        perform = text(perform, MAX_TIME_TEXT),
        totalSeconds = seconds(totalSeconds),
        prepSeconds = seconds(prepSeconds),
        performSeconds = seconds(performSeconds),
    )

    // -------------------------------------------------------------- valores

    private fun text(raw: String?, max: Int): String = PlainTextSanitizer.sanitize(raw, max)

    private fun multiline(raw: String?, max: Int): String =
        PlainTextSanitizer.sanitize(raw, max, allowNewlines = true)

    private fun hasImage(image: JsonElement?): Boolean =
        image is JsonPrimitive && image.isString && image.content.isNotBlank()

    /** 0 o menos, NaN e infinito: sin raciones (no se puede escalar). */
    private fun servings(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_SERVINGS }

    /** Igual que `ServingScaler`: null si no hay nada que mostrar; 0 se conserva («sin cantidad»). */
    private fun quantity(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() && it >= 0.0 && it <= ServingScaler.MAX_QUANTITY }

    private fun rating(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_RATING)

    private fun seconds(raw: Int?): Int? = raw?.takeIf { it >= 0 }
}
