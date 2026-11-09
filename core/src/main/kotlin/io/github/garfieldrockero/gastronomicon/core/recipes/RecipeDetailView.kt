package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.SafeUrl
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Línea de ingredientes ya escalada y formateada.
 *
 * [title] no vacío abre una sección («Para la masa») que sigue hasta la
 * próxima línea con título. [quantityText] es la cantidad («1 1/2») y
 * [description] el resto («cucharadas aceite de oliva»), por separado para
 * poder alinear las cantidades en columna. [text] es lo mismo ya junto, para
 * TalkBack o para una sola línea. Sin cantidad, [quantityText] queda vacío.
 */
data class IngredientLine(
    val title: String,
    val quantityText: String,
    val description: String,
) {
    /** Cantidad y descripción en una sola frase, separadas por un espacio. */
    val text: String
        get() = if (quantityText.isEmpty()) description else if (description.isEmpty()) quantityText else "$quantityText $description"

    val isHeaderOnly: Boolean get() = text.isEmpty() && title.isNotEmpty()
}

/** Paso numerado desde 1; [body] es HTML ya saneado (texto y formato básico, sin enlaces). */
data class StepView(
    val number: Int,
    val title: String,
    val summary: String,
    val body: SanitizedHtml,
)

/**
 * Origen de la receta, **solo como dato**: [url] ya ha pasado por
 * [SafeUrl] (http/https, ASCII, sin credenciales) y [host] sirve para
 * mostrarlo. La interfaz no lo abre sola ni lo hace pulsable al pintar: solo
 * con un toque explícito de la persona y en el navegador del sistema.
 */
data class SourceLink(val url: String, val host: String) {
    /**
     * Solo `https`: un origen con `http` se enseña como dato, sin botón de
     * abrir. Es lo único que decide la interfaz sobre el enlace.
     */
    val isHttps: Boolean get() = url.startsWith("https://", ignoreCase = true)
}

/**
 * Detalle de una receta tal como se pinta: ingredientes escalados y
 * formateados, tiempos, pasos y notas. La interfaz no calcula nada de esto.
 *
 * Se construye con [from] y se recalcula con [withServings], [moreServings] y
 * [fewerServings], que parten siempre de la receta original (nada de acumular
 * redondeos). Los textos se vuelven a pasar por `PlainTextSanitizer` aunque
 * vengan de `RecipeMapper`: la vista no depende de que quien construyó la
 * [Recipe] se acordara de sanear.
 */
class RecipeDetailView private constructor(
    private val recipe: Recipe,
    val slug: String,
    val name: String,
    val description: String,
    /** Id (UUID) para `MealieClient.fetchImage`, o null si no hay imagen que pedir. */
    val imageRecipeId: String?,
    val rating: Double?,
    /** Estrellas llenas de 0 a 5, o null si no hay valoración numérica. */
    val ratingStars: Int?,
    val yieldText: String,
    /** Raciones de la receta; null si no se pueden escalar. */
    val originalServings: Double?,
    /** Raciones elegidas (las originales por defecto); null si no se pueden escalar. */
    val selectedServings: Double?,
    val servingsText: String,
    val times: List<TimeLine>,
    val categories: List<String>,
    val tags: List<String>,
    val ingredients: List<IngredientLine>,
    val steps: List<StepView>,
    val notes: List<RecipeNote>,
    val sourceLink: SourceLink?,
) {
    val canScale: Boolean get() = selectedServings != null

    val canIncreaseServings: Boolean get() = selectedServings?.let { it < MAX_SERVINGS } == true

    val canDecreaseServings: Boolean get() = selectedServings?.let { it > MIN_SERVINGS } == true

    /** Misma receta con otras raciones; sin raciones originales no hay nada que escalar y devuelve `this`. */
    fun withServings(servings: Double?): RecipeDetailView =
        if (originalServings == null) this else from(recipe, servings)

    fun moreServings(): RecipeDetailView {
        val current = selectedServings ?: return this
        return withServings((current + 1.0).coerceAtMost(MAX_SERVINGS))
    }

    fun fewerServings(): RecipeDetailView {
        val current = selectedServings ?: return this
        return withServings((current - 1.0).coerceAtLeast(MIN_SERVINGS))
    }

    // Sin los textos: son datos del servidor y una vista puede acabar en un log.
    override fun toString(): String =
        "RecipeDetailView(ingredients=${ingredients.size}, steps=${steps.size}, notes=${notes.size}, " +
            "servings=$selectedServings)"

    companion object {
        const val MIN_SERVINGS = 1.0
        const val MAX_SERVINGS = 1000.0

        private const val MAX_NAME = 300
        private const val MAX_DESCRIPTION = 5_000
        private const val MAX_LABEL = 100
        private const val MAX_YIELD_TEXT = 100
        private const val MAX_LINE_TEXT = 500
        private const val MAX_UNIT_FOOD = 100
        private const val MAX_STEP_TITLE = 300
        private const val MAX_RATING = 5.0

        /**
         * A partir de esta cantidad (ya escalada) se usa el plural. El
         * formateador redondea a «1» lo que esté a menos de 0,02 de 1, y ese
         * «1» tiene que seguir en singular.
         */
        private const val PLURAL_FROM = 1.02

        /**
         * @param servings raciones que quiere la persona; null, no finito o no
         *   positivo deja las originales, y el resto se acota a
         *   [MIN_SERVINGS]..[MAX_SERVINGS]. Se ignora si la receta no tiene
         *   raciones.
         */
        fun from(recipe: Recipe, servings: Double? = null): RecipeDetailView {
            val original = recipe.servings?.takeIf { it.isFinite() && it > 0.0 }
            val selected = if (original == null) {
                null
            } else {
                servings?.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(MIN_SERVINGS, MAX_SERVINGS) ?: original
            }

            return RecipeDetailView(
                recipe = recipe,
                slug = recipe.slug,
                name = clean(recipe.name, MAX_NAME),
                description = PlainTextSanitizer.sanitize(recipe.description, MAX_DESCRIPTION, allowNewlines = true),
                imageRecipeId = if (recipe.hasImage) RecipeImage.normalizeId(recipe.id) else null,
                rating = recipe.rating?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_RATING),
                ratingStars = RatingStars.of(recipe.rating),
                yieldText = clean(recipe.yieldText, MAX_YIELD_TEXT),
                originalServings = original,
                selectedServings = selected,
                servingsText = QuantityFormatter.format(selected),
                times = RecipeTimeText.lines(recipe.times),
                categories = labelNames(recipe.categories),
                tags = labelNames(recipe.tags),
                ingredients = recipe.ingredients.mapNotNull { ingredientLine(it, original, selected) },
                steps = steps(recipe.steps),
                notes = recipe.notes.mapNotNull { note ->
                    val cleaned = RecipeNote(clean(note.title, MAX_STEP_TITLE), note.text)
                    if (cleaned.title.isEmpty() && cleaned.text.blocks.isEmpty()) null else cleaned
                },
                sourceLink = sourceLink(recipe.sourceUrl),
            )
        }

        // ------------------------------------------------------------ privados

        private fun clean(raw: String?, max: Int): String = PlainTextSanitizer.sanitize(raw, max)

        private fun labelNames(labels: List<Label>): List<String> =
            labels.mapNotNull { clean(it.name, MAX_LABEL).ifEmpty { null } }

        private fun steps(steps: List<Step>): List<StepView> {
            val result = ArrayList<StepView>(steps.size)
            for (step in steps) {
                val title = clean(step.title, MAX_STEP_TITLE)
                val summary = clean(step.summary, MAX_STEP_TITLE)
                if (title.isEmpty() && summary.isEmpty() && step.text.blocks.isEmpty()) continue
                result += StepView(result.size + 1, title, summary, step.text)
            }
            return result
        }

        /**
         * La línea de un ingrediente con la cantidad escalada.
         *
         * - Sin número si la cantidad falta, es 0 («al gusto») o es absurda
         *   (ver `ServingScaler`): nunca se pinta un `NaN` ni un exponente.
         * - Singular o plural de la unidad y del alimento según la cantidad ya
         *   escalada; sin número, singular.
         * - Sin unidad, alimento ni nota queda el texto `display` del servidor,
         *   pero solo si la cantidad no ha cambiado: lleva el número original
         *   dentro y, escalado, enseñaría uno falso.
         * - Una línea sin título ni texto se descarta.
         */
        private fun ingredientLine(ingredient: Ingredient, original: Double?, selected: Double?): IngredientLine? {
            val scaled = ServingScaler.scale(ingredient.quantity, original, selected)
            val number = QuantityFormatter.format(scaled)
            val plural = scaled != null && scaled > PLURAL_FROM

            val unit = pick(plural, ingredient.unitSingular, ingredient.unitPlural)
            val food = pick(plural, ingredient.foodSingular, ingredient.foodPlural)
            val note = clean(ingredient.note, MAX_LINE_TEXT)
            val hasParts = unit.isNotEmpty() || food.isNotEmpty() || note.isNotEmpty()

            val display = clean(ingredient.display, MAX_LINE_TEXT)
            val quantity = ingredient.quantity
            val description = listOf(unit, food, note).filter { it.isNotEmpty() }.joinToString(" ")
            val line = when {
                hasParts -> IngredientLine("", number, description)
                display.isEmpty() -> IngredientLine("", "", "")
                quantity == null || quantity == 0.0 -> IngredientLine("", "", display)
                scaled != null && Math.abs(scaled - quantity) < 1e-9 -> IngredientLine("", "", display)
                else -> IngredientLine("", number, "")
            }
            val title = clean(ingredient.title, MAX_LINE_TEXT)
            return if (title.isEmpty() && line.text.isEmpty()) null else line.copy(title = title)
        }

        /** Singular o plural; si falta el que toca, el otro. */
        private fun pick(plural: Boolean, singular: String, pluralForm: String): String {
            val s = clean(singular, MAX_UNIT_FOOD)
            val p = clean(pluralForm, MAX_UNIT_FOOD)
            return if (plural) p.ifEmpty { s } else s.ifEmpty { p }
        }

        private fun sourceLink(raw: String?): SourceLink? {
            val url = SafeUrl.httpOrNull(raw) ?: return null
            val host = try {
                URI(url).host
            } catch (ignored: URISyntaxException) {
                null
            }
            if (host.isNullOrEmpty()) return null
            return SourceLink(url, host.lowercase(Locale.ROOT))
        }
    }
}

/** Duraciones en forma corta, en castellano: «< 1 min», «45 min», «1 h 30 min». */
object DurationFormatter {
    /** Por encima de esto (1000 h) el dato está roto y se omite. */
    private const val MAX_HOURS = 1000L

    /** Cadena vacía si no hay nada que mostrar (nulo, 0, negativo o absurdo). */
    fun format(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return ""
        if (seconds < 60) return "< 1 min"
        // En Long: sumar 30 a un Int cerca del máximo desbordaría.
        val totalMinutes = (seconds.toLong() + 30) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        if (hours >= MAX_HOURS) return ""
        return when {
            hours == 0L -> "$minutes min"
            minutes == 0L -> "$hours h"
            else -> "$hours h $minutes min"
        }
    }
}
