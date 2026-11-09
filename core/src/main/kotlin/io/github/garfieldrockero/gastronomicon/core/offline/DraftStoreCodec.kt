package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftNote
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftOrganizer
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftStep
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import kotlinx.serialization.Serializable

/** Borrador recuperado del disco con la marca de cuándo se guardó. */
class CachedDraft internal constructor(val draft: RecipeDraft, val savedAtMillis: Long) {
    // Sin nombre, descripción ni pasos: es texto que ha escrito la persona.
    override fun toString(): String = "CachedDraft(savedAtMillis=$savedAtMillis)"
}

/*
 * Formato persistido de un borrador.
 *
 * Es texto plano (nunca HTML) y se guarda saneado y acotado. Al leerlo se
 * vuelve a sanear: un fichero manipulado no puede colar controles
 * bidireccionales, texto desmesurado ni identificadores que no existan.
 */

@Serializable
internal class StoredDraft(
    val format: Int = 0,
    // Identidad del borrador: ámbito y destino. Si no coinciden con la clave,
    // el blob se ha copiado desde otra entrada y no se sirve.
    val scope: String = "",
    val target: String = "",
    val savedAt: Long = 0,
    val name: String = "",
    val description: String = "",
    val servings: Double? = null,
    val totalTime: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val performTime: String = "",
    val ingredients: List<StoredDraftIngredient> = emptyList(),
    val steps: List<StoredDraftStep> = emptyList(),
    val notes: List<StoredDraftNote> = emptyList(),
    val tags: List<StoredDraftOrganizer> = emptyList(),
    val categories: List<StoredDraftOrganizer> = emptyList(),
)

@Serializable
internal class StoredDraftIngredient(
    val quantity: Double? = null,
    val unit: String = "",
    val food: String = "",
    val note: String = "",
    val title: String = "",
    val sourceId: String? = null,
    // Referencias al catálogo del servidor, validadas como UUID. Son opcionales
    // con valor por defecto para que un borrador antiguo (sin estos campos) se
    // siga leyendo: sin id, la línea se trata como «sin cambio» al guardar.
    val unitId: String? = null,
    val foodId: String? = null,
)

@Serializable
internal class StoredDraftStep(
    val title: String = "",
    val text: String = "",
    val sourceId: String? = null,
)

@Serializable
internal class StoredDraftNote(
    val title: String = "",
    val text: String = "",
    val sourceId: String? = null,
)

@Serializable
internal class StoredDraftOrganizer(
    val id: String? = null,
    val name: String = "",
    val slug: String? = null,
)

internal object DraftStoreCodec {

    private const val MAX_ORGANIZERS = RecipeDraftValidator.MAX_ORGANIZERS

    /**
     * Serializa el borrador, saneado y acotado a los mismos máximos con los que
     * se enviará. No falla por contenido: un borrador es texto sin guardar y es
     * mejor conservar lo que se pueda que perderlo entero. Lo que pasa del tope
     * se recorta, como ya haría el formulario al validar.
     *
     * Null solo si no se puede escribir (tamaño absurdo o error del codificador).
     */
    fun encode(draft: RecipeDraft, savedAtMillis: Long, scope: ServerScope, target: DraftTarget): ByteArray? {
        if (savedAtMillis <= 0) return null
        val stored = StoredDraft(
            format = OfflineFormat.CURRENT,
            scope = scope.id,
            target = target.id,
            savedAt = savedAtMillis,
            name = clean(draft.name, RecipeDraftValidator.MAX_NAME),
            description = clean(draft.description, RecipeDraftValidator.MAX_DESCRIPTION, newlines = true),
            servings = finite(draft.servings),
            totalTime = clean(draft.totalTime, RecipeDraftValidator.MAX_TIME_TEXT),
            prepTime = clean(draft.prepTime, RecipeDraftValidator.MAX_TIME_TEXT),
            cookTime = clean(draft.cookTime, RecipeDraftValidator.MAX_TIME_TEXT),
            performTime = clean(draft.performTime, RecipeDraftValidator.MAX_TIME_TEXT),
            ingredients = draft.ingredients.take(RecipeDraftValidator.MAX_INGREDIENTS).map {
                val unit = clean(it.unit, RecipeDraftValidator.MAX_UNIT_FOOD)
                val food = clean(it.food, RecipeDraftValidator.MAX_UNIT_FOOD)
                StoredDraftIngredient(
                    quantity = finite(it.quantity),
                    unit = unit,
                    food = food,
                    note = clean(it.note, RecipeDraftValidator.MAX_INGREDIENT_TEXT),
                    title = clean(it.title, RecipeDraftValidator.MAX_INGREDIENT_TEXT),
                    sourceId = RecipeImage.normalizeId(it.sourceId),
                    unitId = referenceId(unit, it.unitId),
                    foodId = referenceId(food, it.foodId),
                )
            },
            steps = draft.steps.take(RecipeDraftValidator.MAX_STEPS).map {
                StoredDraftStep(
                    title = clean(it.title, RecipeDraftValidator.MAX_STEP_TITLE),
                    text = clean(it.text, RecipeDraftValidator.MAX_STEP_TEXT, newlines = true),
                    sourceId = RecipeImage.normalizeId(it.sourceId),
                )
            },
            notes = draft.notes.take(RecipeDraftValidator.MAX_NOTES).map {
                StoredDraftNote(
                    title = clean(it.title, RecipeDraftValidator.MAX_NOTE_TITLE),
                    text = clean(it.text, RecipeDraftValidator.MAX_NOTE_TEXT, newlines = true),
                    sourceId = RecipeImage.normalizeId(it.sourceId),
                )
            },
            tags = draft.tags.take(MAX_ORGANIZERS).map { encodeOrganizer(it) },
            categories = draft.categories.take(MAX_ORGANIZERS).map { encodeOrganizer(it) },
        )
        return try {
            CoreJson.json.encodeToString(StoredDraft.serializer(), stored).toByteArray(Charsets.UTF_8)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /**
     * Lee y vuelve a sanear. Null si el formato no es el actual, si el blob no
     * es de [scope] y [target] (se ha copiado desde otra entrada) o si el JSON
     * no se entiende.
     */
    fun decode(bytes: ByteArray, scope: ServerScope, target: DraftTarget, maxBytes: Int): CachedDraft? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        val stored = try {
            CoreJson.json.decodeFromString(StoredDraft.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (ignored: IllegalArgumentException) {
            return null
        } catch (ignored: StackOverflowError) {
            return null
        }
        if (stored.format != OfflineFormat.CURRENT) return null
        if (stored.scope != scope.id || stored.target != target.id) return null
        if (stored.savedAt <= 0) return null

        val draft = RecipeDraft(
            name = clean(stored.name, RecipeDraftValidator.MAX_NAME),
            description = clean(stored.description, RecipeDraftValidator.MAX_DESCRIPTION, newlines = true),
            servings = finite(stored.servings),
            totalTime = clean(stored.totalTime, RecipeDraftValidator.MAX_TIME_TEXT),
            prepTime = clean(stored.prepTime, RecipeDraftValidator.MAX_TIME_TEXT),
            cookTime = clean(stored.cookTime, RecipeDraftValidator.MAX_TIME_TEXT),
            performTime = clean(stored.performTime, RecipeDraftValidator.MAX_TIME_TEXT),
            ingredients = stored.ingredients.take(RecipeDraftValidator.MAX_INGREDIENTS).map {
                val unit = clean(it.unit, RecipeDraftValidator.MAX_UNIT_FOOD)
                val food = clean(it.food, RecipeDraftValidator.MAX_UNIT_FOOD)
                DraftIngredient(
                    quantity = finite(it.quantity),
                    unit = unit,
                    food = food,
                    note = clean(it.note, RecipeDraftValidator.MAX_INGREDIENT_TEXT),
                    title = clean(it.title, RecipeDraftValidator.MAX_INGREDIENT_TEXT),
                    sourceId = RecipeImage.normalizeId(it.sourceId),
                    unitId = referenceId(unit, it.unitId),
                    foodId = referenceId(food, it.foodId),
                )
            },
            steps = stored.steps.take(RecipeDraftValidator.MAX_STEPS).map {
                DraftStep(
                    title = clean(it.title, RecipeDraftValidator.MAX_STEP_TITLE),
                    text = clean(it.text, RecipeDraftValidator.MAX_STEP_TEXT, newlines = true),
                    sourceId = RecipeImage.normalizeId(it.sourceId),
                )
            },
            notes = stored.notes.take(RecipeDraftValidator.MAX_NOTES).map {
                DraftNote(
                    title = clean(it.title, RecipeDraftValidator.MAX_NOTE_TITLE),
                    text = clean(it.text, RecipeDraftValidator.MAX_NOTE_TEXT, newlines = true),
                    sourceId = RecipeImage.normalizeId(it.sourceId),
                )
            },
            tags = stored.tags.take(MAX_ORGANIZERS).map { decodeOrganizer(it) },
            categories = stored.categories.take(MAX_ORGANIZERS).map { decodeOrganizer(it) },
        )
        return CachedDraft(draft, stored.savedAt)
    }

    // ------------------------------------------------------------ utilidades

    private fun encodeOrganizer(organizer: DraftOrganizer): StoredDraftOrganizer =
        StoredDraftOrganizer(
            id = RecipeImage.normalizeId(organizer.id),
            name = clean(organizer.name, RecipeDraftValidator.MAX_ORGANIZER_NAME),
            slug = organizer.slug?.takeIf { RecipeSlug.isValid(it) },
        )

    private fun decodeOrganizer(organizer: StoredDraftOrganizer): DraftOrganizer =
        DraftOrganizer(
            id = RecipeImage.normalizeId(organizer.id),
            name = clean(organizer.name, RecipeDraftValidator.MAX_ORGANIZER_NAME),
            slug = organizer.slug?.takeIf { RecipeSlug.isValid(it) },
        )

    /** Sanea y recorta al máximo (aquí sí se recorta: es un borrador propio). */
    private fun clean(raw: String, max: Int, newlines: Boolean = false): String =
        PlainTextSanitizer.sanitize(raw, max, newlines)

    /**
     * Id de catálogo que se guarda: solo si el nombre saneado no quedó vacío.
     * Un id sin nombre no se puede reenviar (el esquema exige los dos), y
     * conservarlo haría que un borrador recuperado no se pudiera guardar por una
     * referencia que no se puede ni mostrar. Sin id, la línea se trata como «sin
     * cambio».
     */
    private fun referenceId(name: String, raw: String?): String? =
        if (name.isEmpty()) null else RecipeImage.normalizeId(raw)

    /** JSON no admite NaN ni infinito: se guardan como «sin valor». */
    private fun finite(raw: Double?): Double? = raw?.takeIf { it.isFinite() }
}
