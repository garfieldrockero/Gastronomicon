package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.html.HtmlSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.SafeUrl
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.recipes.Ingredient
import io.github.garfieldrockero.gastronomicon.core.recipes.Label
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeNote
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeTimes
import io.github.garfieldrockero.gastronomicon.core.recipes.ServingScaler
import io.github.garfieldrockero.gastronomicon.core.recipes.Step
import kotlinx.serialization.Serializable

/** Receta recuperada de la caché con la marca de cuándo se guardó. */
class CachedRecipe internal constructor(val recipe: Recipe, val cachedAtMillis: Long) {
    // Sin nombre, slug ni pasos: la receta es contenido de la persona.
    override fun toString(): String = "CachedRecipe(cachedAtMillis=$cachedAtMillis)"
}

/*
 * Formato persistido de una receta.
 *
 * Todo son campos primitivos y listas acotadas; el HTML de las instrucciones va
 * como texto para volver a pasarlo por HtmlSanitizer al leer. No se guarda
 * ningún objeto de `client/` ni el JSON crudo del servidor: si se guardara el
 * crudo, habría que confiar en él al releerlo.
 */

@Serializable
internal class StoredRecipe(
    val format: Int = 0,
    // Ámbito al que pertenece el blob: si no coincide con el de la clave, se
    // descarta. Un blob copiado al ámbito de otra cuenta no se sirve.
    val scope: String = "",
    val cachedAt: Long = 0,
    val id: String? = null,
    val slug: String = "",
    val name: String = "",
    val description: String = "",
    val hasImage: Boolean = false,
    val servings: Double? = null,
    val yieldText: String = "",
    val rating: Double? = null,
    val sourceUrl: String? = null,
    val times: StoredTimes = StoredTimes(),
    val categories: List<StoredLabel> = emptyList(),
    val tags: List<StoredLabel> = emptyList(),
    val ingredients: List<StoredIngredient> = emptyList(),
    val steps: List<StoredStep> = emptyList(),
    val notes: List<StoredNote> = emptyList(),
)

@Serializable
internal class StoredTimes(
    val total: String = "",
    val prep: String = "",
    val cook: String = "",
    val perform: String = "",
    val totalSeconds: Int? = null,
    val prepSeconds: Int? = null,
    val performSeconds: Int? = null,
)

@Serializable
internal class StoredLabel(val name: String = "", val slug: String = "")

@Serializable
internal class StoredIngredient(
    val title: String = "",
    val quantity: Double? = null,
    val unitSingular: String = "",
    val unitPlural: String = "",
    val foodSingular: String = "",
    val foodPlural: String = "",
    val note: String = "",
    val display: String = "",
)

@Serializable
internal class StoredStep(
    val title: String = "",
    val summary: String = "",
    val html: String = "",
)

@Serializable
internal class StoredNote(
    val title: String = "",
    val html: String = "",
)

/**
 * Serialización y validación de una receta cacheada.
 *
 * El mismo camino sirve para guardar y para leer, y al leer se vuelve a aplicar
 * el saneado del núcleo: [PlainTextSanitizer] para el texto, [HtmlSanitizer]
 * para las instrucciones, [SafeUrl] para el origen, [RecipeSlug] y
 * [RecipeImage] para identificadores y [ServingScaler] para cantidades. Un
 * texto que pase de su máximo, un HTML truncado por el saneador o una versión
 * desconocida hacen que la entrada se descarte entera: la caché nunca entrega
 * algo a medias.
 */
internal object RecipeCacheCodec {

    private const val MAX_RATING = 5.0
    private const val MAX_LABELS = RecipeDraftValidator.MAX_ORGANIZERS

    /**
     * Serializa la receta, o null si no se puede guardar tal cual.
     *
     * No recorta: si un campo pasa de su límite, la entrada no se guarda. Una
     * receta que llega hasta aquí ya viene del mapeador, así que un exceso
     * significa que algo no cuadra.
     */
    fun encode(recipe: Recipe, cachedAtMillis: Long, scope: ServerScope): ByteArray? {
        if (cachedAtMillis <= 0) return null
        if (!RecipeSlug.isValid(recipe.slug)) return null

        val name = clean(recipe.name, RecipeDraftValidator.MAX_NAME) ?: return null
        val description = clean(recipe.description, RecipeDraftValidator.MAX_DESCRIPTION, newlines = true)
            ?: return null
        val yieldText = clean(recipe.yieldText, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null
        val times = encodeTimes(recipe.times) ?: return null
        val categories = encodeLabels(recipe.categories) ?: return null
        val tags = encodeLabels(recipe.tags) ?: return null
        val ingredients = encodeIngredients(recipe.ingredients) ?: return null
        val steps = encodeSteps(recipe.steps) ?: return null
        val notes = encodeNotes(recipe.notes) ?: return null

        val stored = StoredRecipe(
            format = OfflineFormat.CURRENT,
            scope = scope.id,
            cachedAt = cachedAtMillis,
            id = RecipeImage.normalizeId(recipe.id),
            slug = recipe.slug,
            name = name,
            description = description,
            hasImage = recipe.hasImage,
            servings = validServings(recipe.servings),
            yieldText = yieldText,
            rating = validRating(recipe.rating),
            sourceUrl = SafeUrl.httpOrNull(recipe.sourceUrl),
            times = times,
            categories = categories,
            tags = tags,
            ingredients = ingredients,
            steps = steps,
            notes = notes,
        )
        return try {
            CoreJson.json.encodeToString(StoredRecipe.serializer(), stored).toByteArray(Charsets.UTF_8)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /**
     * Lee una entrada. [scope] y [expectedSlug] son los de la clave: si no
     * coinciden con los del blob, la entrada no es la que se pedía y se
     * descarta (también si el fichero se ha copiado desde otro ámbito).
     *
     * [maxBytes] evita parsear un blob desmesurado antes de comprobar el tope
     * de la caché.
     */
    fun decode(bytes: ByteArray, scope: ServerScope, expectedSlug: String, maxBytes: Int): CachedRecipe? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        if (!RecipeSlug.isValid(expectedSlug)) return null

        val stored = try {
            CoreJson.json.decodeFromString(StoredRecipe.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (ignored: IllegalArgumentException) {
            // Incluye SerializationException, que hereda de IllegalArgumentException.
            return null
        } catch (ignored: StackOverflowError) {
            return null
        }
        if (stored.format != OfflineFormat.CURRENT) return null
        if (stored.scope != scope.id) return null
        if (stored.cachedAt <= 0) return null
        if (stored.slug != expectedSlug || !RecipeSlug.isValid(stored.slug)) return null

        val name = clean(stored.name, RecipeDraftValidator.MAX_NAME) ?: return null
        val description = clean(stored.description, RecipeDraftValidator.MAX_DESCRIPTION, newlines = true)
            ?: return null
        val yieldText = clean(stored.yieldText, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null
        val times = decodeTimes(stored.times) ?: return null
        val categories = decodeLabels(stored.categories) ?: return null
        val tags = decodeLabels(stored.tags) ?: return null
        val ingredients = decodeIngredients(stored.ingredients) ?: return null
        val steps = decodeSteps(stored.steps) ?: return null
        val notes = decodeNotes(stored.notes) ?: return null

        val recipe = Recipe(
            id = RecipeImage.normalizeId(stored.id),
            slug = stored.slug,
            name = name,
            description = description,
            hasImage = stored.hasImage,
            servings = validServings(stored.servings),
            yieldText = yieldText,
            rating = validRating(stored.rating),
            // Una URL manipulada se cae a null; no invalida la receta entera
            // porque la interfaz ya sabe pintar «sin origen».
            sourceUrl = SafeUrl.httpOrNull(stored.sourceUrl),
            times = times,
            categories = categories,
            tags = tags,
            ingredients = ingredients,
            steps = steps,
            notes = notes,
        )
        return CachedRecipe(recipe, stored.cachedAt)
    }

    // ------------------------------------------------------------ tiempos

    private fun encodeTimes(times: RecipeTimes): StoredTimes? {
        val total = clean(times.total, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null
        val prep = clean(times.prep, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null
        val cook = clean(times.cook, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null
        val perform = clean(times.perform, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null
        return StoredTimes(
            total = total,
            prep = prep,
            cook = cook,
            perform = perform,
            totalSeconds = validSeconds(times.totalSeconds),
            prepSeconds = validSeconds(times.prepSeconds),
            performSeconds = validSeconds(times.performSeconds),
        )
    }

    private fun decodeTimes(times: StoredTimes): RecipeTimes? {
        return RecipeTimes(
            total = clean(times.total, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null,
            prep = clean(times.prep, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null,
            cook = clean(times.cook, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null,
            perform = clean(times.perform, RecipeDraftValidator.MAX_TIME_TEXT) ?: return null,
            totalSeconds = validSeconds(times.totalSeconds),
            prepSeconds = validSeconds(times.prepSeconds),
            performSeconds = validSeconds(times.performSeconds),
        )
    }

    // ----------------------------------------------------------- etiquetas

    private fun encodeLabels(labels: List<Label>): List<StoredLabel>? {
        if (labels.size > MAX_LABELS) return null
        val result = ArrayList<StoredLabel>(labels.size)
        for (label in labels) {
            val name = clean(label.name, RecipeDraftValidator.MAX_ORGANIZER_NAME) ?: return null
            if (name.isEmpty()) continue
            result += StoredLabel(name = name, slug = validSlug(label.slug))
        }
        return result
    }

    private fun decodeLabels(labels: List<StoredLabel>): List<Label>? {
        if (labels.size > MAX_LABELS) return null
        val result = ArrayList<Label>(labels.size)
        for (label in labels) {
            val name = clean(label.name, RecipeDraftValidator.MAX_ORGANIZER_NAME) ?: return null
            if (name.isEmpty()) continue
            result += Label(name, validSlug(label.slug))
        }
        return result
    }

    // --------------------------------------------------------- ingredientes

    private fun encodeIngredients(items: List<Ingredient>): List<StoredIngredient>? {
        if (items.size > RecipeDraftValidator.MAX_INGREDIENTS) return null
        val result = ArrayList<StoredIngredient>(items.size)
        for (item in items) {
            val encoded = StoredIngredient(
                title = clean(item.title, RecipeDraftValidator.MAX_INGREDIENT_TEXT) ?: return null,
                quantity = validQuantity(item.quantity),
                unitSingular = clean(item.unitSingular, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null,
                unitPlural = clean(item.unitPlural, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null,
                foodSingular = clean(item.foodSingular, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null,
                foodPlural = clean(item.foodPlural, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null,
                note = clean(item.note, RecipeDraftValidator.MAX_INGREDIENT_TEXT) ?: return null,
                display = clean(item.display, RecipeDraftValidator.MAX_INGREDIENT_TEXT) ?: return null,
            )
            if (isEmpty(encoded)) continue
            result += encoded
        }
        return result
    }

    private fun decodeIngredients(items: List<StoredIngredient>): List<Ingredient>? {
        if (items.size > RecipeDraftValidator.MAX_INGREDIENTS) return null
        val result = ArrayList<Ingredient>(items.size)
        for (item in items) {
            val title = clean(item.title, RecipeDraftValidator.MAX_INGREDIENT_TEXT) ?: return null
            val unitSingular = clean(item.unitSingular, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null
            val unitPlural = clean(item.unitPlural, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null
            val foodSingular = clean(item.foodSingular, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null
            val foodPlural = clean(item.foodPlural, RecipeDraftValidator.MAX_UNIT_FOOD) ?: return null
            val note = clean(item.note, RecipeDraftValidator.MAX_INGREDIENT_TEXT) ?: return null
            val display = clean(item.display, RecipeDraftValidator.MAX_INGREDIENT_TEXT) ?: return null
            val stored = StoredIngredient(
                title = title,
                quantity = validQuantity(item.quantity),
                unitSingular = unitSingular,
                unitPlural = unitPlural,
                foodSingular = foodSingular,
                foodPlural = foodPlural,
                note = note,
                display = display,
            )
            if (isEmpty(stored)) continue
            result += Ingredient(
                title = title,
                quantity = validQuantity(item.quantity),
                unitSingular = unitSingular,
                unitPlural = unitPlural,
                foodSingular = foodSingular,
                foodPlural = foodPlural,
                note = note,
                display = display,
            )
        }
        return result
    }

    private fun isEmpty(item: StoredIngredient): Boolean =
        item.title.isEmpty() && item.unitSingular.isEmpty() && item.foodSingular.isEmpty() &&
            item.note.isEmpty() && item.display.isEmpty()

    // --------------------------------------------------------------- pasos

    private fun encodeSteps(steps: List<Step>): List<StoredStep>? {
        if (steps.size > RecipeDraftValidator.MAX_STEPS) return null
        val result = ArrayList<StoredStep>(steps.size)
        for (step in steps) {
            val title = clean(step.title, RecipeDraftValidator.MAX_STEP_TITLE) ?: return null
            val summary = clean(step.summary, RecipeDraftValidator.MAX_STEP_TITLE) ?: return null
            val html = HtmlWriter.toHtml(step.text) ?: return null
            if (title.isEmpty() && summary.isEmpty() && html.isEmpty()) continue
            result += StoredStep(title = title, summary = summary, html = html)
        }
        return result
    }

    private fun decodeSteps(steps: List<StoredStep>): List<Step>? {
        if (steps.size > RecipeDraftValidator.MAX_STEPS) return null
        val result = ArrayList<Step>(steps.size)
        for (step in steps) {
            val title = clean(step.title, RecipeDraftValidator.MAX_STEP_TITLE) ?: return null
            val summary = clean(step.summary, RecipeDraftValidator.MAX_STEP_TITLE) ?: return null
            val text = decodeHtml(step.html) ?: return null
            if (title.isEmpty() && summary.isEmpty() && text.blocks.isEmpty()) continue
            result += Step(title = title, summary = summary, text = text)
        }
        return result
    }

    // --------------------------------------------------------------- notas

    private fun encodeNotes(notes: List<RecipeNote>): List<StoredNote>? {
        if (notes.size > RecipeDraftValidator.MAX_NOTES) return null
        val result = ArrayList<StoredNote>(notes.size)
        for (note in notes) {
            val title = clean(note.title, RecipeDraftValidator.MAX_NOTE_TITLE) ?: return null
            val html = HtmlWriter.toHtml(note.text) ?: return null
            if (title.isEmpty() && html.isEmpty()) continue
            result += StoredNote(title = title, html = html)
        }
        return result
    }

    private fun decodeNotes(notes: List<StoredNote>): List<RecipeNote>? {
        if (notes.size > RecipeDraftValidator.MAX_NOTES) return null
        val result = ArrayList<RecipeNote>(notes.size)
        for (note in notes) {
            val title = clean(note.title, RecipeDraftValidator.MAX_NOTE_TITLE) ?: return null
            val text = decodeHtml(note.html) ?: return null
            if (title.isEmpty() && text.blocks.isEmpty()) continue
            result += RecipeNote(title = title, text = text)
        }
        return result
    }

    // ------------------------------------------------------------ utilidades

    /**
     * Sanea y comprueba el máximo. Null si el texto ya venía por encima: no se
     * recorta en silencio lo que debería caber (la caché no es un formulario).
     */
    private fun clean(raw: String, max: Int, newlines: Boolean = false): String? {
        if (raw.codePointCount(0, raw.length) > max) return null
        return PlainTextSanitizer.sanitize(raw, max, newlines)
    }

    /** Vuelve a pasar el HTML guardado por el saneador; null si salió truncado. */
    private fun decodeHtml(raw: String): SanitizedHtml? {
        if (raw.length > HtmlSanitizer.MAX_INPUT_LENGTH) return null
        val sanitized = HtmlSanitizer.sanitize(raw)
        return if (sanitized.truncated) null else sanitized
    }

    private fun validServings(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() && it > 0.0 && it <= RecipeDraftValidator.MAX_SERVINGS }

    private fun validRating(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_RATING)

    private fun validQuantity(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() && it >= 0.0 && it <= ServingScaler.MAX_QUANTITY }

    private fun validSeconds(raw: Int?): Int? = raw?.takeIf { it >= 0 }

    private fun validSlug(raw: String): String = if (RecipeSlug.isValid(raw)) raw else ""
}
