package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import java.util.Locale

/**
 * Valida y sanea un [RecipeDraft] antes de que salga hacia el servidor.
 *
 * Todo texto pasa por [PlainTextSanitizer] (se quitan controles, bidireccionales
 * y marcas invisibles); no se recorta en silencio: si tras sanear pasa del
 * máximo, es un error [DraftErrorKind.TooLong], porque cortar lo que la persona
 * escribió sin avisar es perder datos. Los límites son los mismos con los que se
 * lee una receta, para que lo que se guarda se pueda volver a leer entero.
 *
 * Las líneas NUEVAS (sin `sourceId`) completamente vacías se descartan, para no
 * obligar a la interfaz a limpiar la fila en blanco del final del formulario;
 * las que ya existían en el servidor se conservan aunque se vean vacías (pueden
 * llevar una receta referenciada u otros datos que el formulario no muestra).
 */
object RecipeDraftValidator {

    const val MAX_NAME = 300
    const val MAX_DESCRIPTION = 5_000
    const val MAX_TIME_TEXT = 100
    const val MAX_INGREDIENT_TEXT = 500
    const val MAX_UNIT_FOOD = 100
    const val MAX_STEP_TITLE = 300
    const val MAX_STEP_TEXT = 10_000
    const val MAX_NOTE_TITLE = 300
    const val MAX_NOTE_TEXT = 10_000
    const val MAX_ORGANIZER_NAME = 100

    const val MAX_INGREDIENTS = 500
    const val MAX_STEPS = 500
    const val MAX_NOTES = 100
    const val MAX_ORGANIZERS = 100

    const val MAX_SERVINGS = 10_000.0

    /** Texto limpio y si se pasaba del máximo. */
    private class Cleaned(val text: String, val tooLong: Boolean)

    fun validate(draft: RecipeDraft): DraftValidation {
        val errors = ArrayList<DraftError>()

        val name = clean(draft.name, MAX_NAME, false)
        if (name.text.isEmpty()) errors += DraftError(DraftField.Name, DraftErrorKind.Required)
        flagLong(name, DraftField.Name, null, errors)

        val description = clean(draft.description, MAX_DESCRIPTION, true)
        flagLong(description, DraftField.Description, null, errors)

        val servings = draft.servings
        if (servings != null) {
            when {
                !servings.isFinite() -> errors += DraftError(DraftField.Servings, DraftErrorKind.NotFinite)
                servings <= 0.0 || servings > MAX_SERVINGS ->
                    errors += DraftError(DraftField.Servings, DraftErrorKind.OutOfRange)
            }
        }

        val total = timeText(draft.totalTime, DraftField.TotalTime, errors)
        val prep = timeText(draft.prepTime, DraftField.PrepTime, errors)
        val cook = timeText(draft.cookTime, DraftField.CookTime, errors)
        val perform = timeText(draft.performTime, DraftField.PerformTime, errors)

        val ingredients = checkIngredients(draft.ingredients, errors)
        val steps = checkSteps(draft.steps, errors)
        val notes = checkNotes(draft.notes, errors)
        val tags = checkOrganizers(draft.tags, DraftField.Tags, errors)
        val categories = checkOrganizers(draft.categories, DraftField.Categories, errors)

        if (errors.isNotEmpty()) return DraftValidation.Invalid(errors)
        return DraftValidation.Valid(
            RecipeDraft(
                name = name.text,
                description = description.text,
                servings = servings,
                totalTime = total,
                prepTime = prep,
                cookTime = cook,
                performTime = perform,
                ingredients = ingredients,
                steps = steps,
                notes = notes,
                tags = tags,
                categories = categories,
            ),
        )
    }

    /**
     * Nombre para crear una receta vacía: el mismo criterio que el campo
     * «nombre» del borrador. Null más los errores si no vale.
     */
    fun validateName(raw: String): Pair<String?, List<DraftError>> {
        val name = clean(raw, MAX_NAME, false)
        val errors = ArrayList<DraftError>()
        if (name.text.isEmpty()) errors += DraftError(DraftField.Name, DraftErrorKind.Required)
        flagLong(name, DraftField.Name, null, errors)
        return if (errors.isEmpty()) name.text to emptyList() else null to errors
    }

    // ------------------------------------------------- utilidades compartidas

    /** Texto de una sola línea saneado y recortado al máximo, para comparar con lo que ya hay en el servidor. */
    internal fun plain(raw: String?, max: Int): String = PlainTextSanitizer.sanitize(raw, max)

    /** Igual, conservando saltos de línea. */
    internal fun multiline(raw: String?, max: Int): String =
        PlainTextSanitizer.sanitize(raw, max, allowNewlines = true)

    /** Número válido para una cantidad, o null: lo mismo que acepta el lector (finito, 0..MAX). */
    internal fun validQuantity(raw: Double?): Double? =
        raw?.takeIf { it.isFinite() && it >= 0.0 && it <= ServingScaler.MAX_QUANTITY }

    internal fun validUuid(raw: String?): String? = RecipeImage.normalizeId(raw)

    private fun clean(raw: String, max: Int, newlines: Boolean): Cleaned {
        // Se sanea a max+1 para poder distinguir «justo cabe» de «se pasa».
        val text = PlainTextSanitizer.sanitize(raw, max + 1, newlines)
        val tooLong = text.codePointCount(0, text.length) > max
        return Cleaned(text, tooLong)
    }

    private fun flagLong(cleaned: Cleaned, field: DraftField, index: Int?, errors: MutableList<DraftError>) {
        if (cleaned.tooLong) errors += DraftError(field, DraftErrorKind.TooLong, index)
    }

    private fun timeText(raw: String, field: DraftField, errors: MutableList<DraftError>): String {
        val cleaned = clean(raw, MAX_TIME_TEXT, false)
        flagLong(cleaned, field, null, errors)
        return cleaned.text
    }

    // ------------------------------------------------------------- listas

    private fun checkIngredients(raw: List<DraftIngredient>, errors: MutableList<DraftError>): List<DraftIngredient> {
        if (raw.size > MAX_INGREDIENTS) {
            errors += DraftError(DraftField.Ingredients, DraftErrorKind.TooMany)
            return emptyList()
        }
        val seen = HashSet<String>()
        val result = ArrayList<DraftIngredient>(raw.size)
        raw.forEachIndexed { index, item ->
            val id = sourceId(item.sourceId, DraftField.Ingredients, index, seen, errors)
            val note = clean(item.note, MAX_INGREDIENT_TEXT, false)
            val title = clean(item.title, MAX_INGREDIENT_TEXT, false)
            val unit = clean(item.unit, MAX_UNIT_FOOD, false)
            val food = clean(item.food, MAX_UNIT_FOOD, false)
            flagLong(note, DraftField.IngredientNote, index, errors)
            flagLong(title, DraftField.IngredientTitle, index, errors)
            flagLong(unit, DraftField.IngredientUnit, index, errors)
            flagLong(food, DraftField.IngredientFood, index, errors)

            val quantity = item.quantity
            if (quantity != null) {
                when {
                    !quantity.isFinite() -> errors += DraftError(DraftField.IngredientQuantity, DraftErrorKind.NotFinite, index)
                    quantity < 0.0 || quantity > ServingScaler.MAX_QUANTITY ->
                        errors += DraftError(DraftField.IngredientQuantity, DraftErrorKind.OutOfRange, index)
                }
            }
            // Unidad y alimento no se pueden crear desde aquí: en una línea
            // nueva tienen que ir vacíos. En una existente, merge() comprueba
            // que no han cambiado (necesita el original).
            if (id == null) {
                if (unit.text.isNotEmpty()) errors += DraftError(DraftField.IngredientUnit, DraftErrorKind.NotEditable, index)
                if (food.text.isNotEmpty()) errors += DraftError(DraftField.IngredientFood, DraftErrorKind.NotEditable, index)
            }
            val hasText = note.text.isNotEmpty() || title.text.isNotEmpty() ||
                unit.text.isNotEmpty() || food.text.isNotEmpty()
            if (id == null && !hasText) {
                // Fila nueva en blanco: se descarta, salvo que lleve una cantidad
                // (eso es un dato escrito que no se puede perder en silencio).
                if (quantity != null && quantity > 0.0) {
                    errors += DraftError(DraftField.IngredientNote, DraftErrorKind.Required, index)
                }
                return@forEachIndexed
            }
            result += DraftIngredient(
                quantity = quantity,
                unit = unit.text,
                food = food.text,
                note = note.text,
                title = title.text,
                sourceId = id,
            )
        }
        return result
    }

    private fun checkSteps(raw: List<DraftStep>, errors: MutableList<DraftError>): List<DraftStep> {
        if (raw.size > MAX_STEPS) {
            errors += DraftError(DraftField.Steps, DraftErrorKind.TooMany)
            return emptyList()
        }
        val seen = HashSet<String>()
        val result = ArrayList<DraftStep>(raw.size)
        raw.forEachIndexed { index, item ->
            val id = sourceId(item.sourceId, DraftField.Steps, index, seen, errors)
            val title = clean(item.title, MAX_STEP_TITLE, false)
            val text = clean(item.text, MAX_STEP_TEXT, true)
            flagLong(title, DraftField.StepTitle, index, errors)
            flagLong(text, DraftField.StepText, index, errors)
            if (id == null && title.text.isEmpty() && text.text.isEmpty()) return@forEachIndexed
            result += DraftStep(title = title.text, text = text.text, sourceId = id)
        }
        return result
    }

    private fun checkNotes(raw: List<DraftNote>, errors: MutableList<DraftError>): List<DraftNote> {
        if (raw.size > MAX_NOTES) {
            errors += DraftError(DraftField.Notes, DraftErrorKind.TooMany)
            return emptyList()
        }
        val seen = HashSet<String>()
        val result = ArrayList<DraftNote>(raw.size)
        raw.forEachIndexed { index, item ->
            val id = sourceId(item.sourceId, DraftField.Notes, index, seen, errors)
            val title = clean(item.title, MAX_NOTE_TITLE, false)
            val text = clean(item.text, MAX_NOTE_TEXT, true)
            flagLong(title, DraftField.NoteTitle, index, errors)
            flagLong(text, DraftField.NoteText, index, errors)
            if (id == null && title.text.isEmpty() && text.text.isEmpty()) return@forEachIndexed
            result += DraftNote(title = title.text, text = text.text, sourceId = id)
        }
        return result
    }

    private fun checkOrganizers(
        raw: List<DraftOrganizer>,
        field: DraftField,
        errors: MutableList<DraftError>,
    ): List<DraftOrganizer> {
        if (raw.size > MAX_ORGANIZERS) {
            errors += DraftError(field, DraftErrorKind.TooMany)
            return emptyList()
        }
        val seenNames = HashSet<String>()
        val result = ArrayList<DraftOrganizer>(raw.size)
        raw.forEachIndexed { index, item ->
            val name = clean(item.name, MAX_ORGANIZER_NAME, false)
            flagLong(name, field, index, errors)
            if (name.text.isEmpty()) {
                // Una etiqueta sin nombre en una fila nueva sobra; con id, es un error.
                if (item.id != null || item.slug != null) {
                    errors += DraftError(field, DraftErrorKind.Required, index)
                }
                return@forEachIndexed
            }
            val id = item.id?.let { validUuid(it) }
            if (item.id != null && id == null) {
                errors += DraftError(field, DraftErrorKind.Invalid, index)
            }
            val slug = item.slug
            if (slug != null && !RecipeSlug.isValid(slug)) {
                errors += DraftError(field, DraftErrorKind.Invalid, index)
            }
            // Mealie compara nombres sin distinguir mayúsculas.
            if (!seenNames.add(name.text.lowercase(Locale.ROOT))) {
                errors += DraftError(field, DraftErrorKind.Duplicate, index)
            }
            result += DraftOrganizer(id = id, name = name.text, slug = slug)
        }
        return result
    }

    /** UUID en minúsculas de un `sourceId`, o null si no hay; error si hay y no vale, o se repite. */
    private fun sourceId(
        raw: String?,
        field: DraftField,
        index: Int,
        seen: MutableSet<String>,
        errors: MutableList<DraftError>,
    ): String? {
        if (raw == null) return null
        val id = validUuid(raw)
        if (id == null) {
            errors += DraftError(field, DraftErrorKind.Invalid, index)
            return null
        }
        if (!seen.add(id)) errors += DraftError(field, DraftErrorKind.Duplicate, index)
        return id
    }
}
