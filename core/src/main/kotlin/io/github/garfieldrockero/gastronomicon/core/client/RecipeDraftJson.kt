package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftErrorKind
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftNote
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftOrganizer
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftStep
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Puente entre el `JsonObject` de `GET /api/recipes/{slug}` y el [RecipeDraft].
 *
 * Por qué no hay un DTO para escribir: un DTO con «ignoreUnknownKeys» y
 * «coerceInputValues» perdería los campos que esta versión de la app no
 * conoce y convertiría los `null` en valores por defecto, es decir, el `PUT`
 * borraría o inventaría datos. Aquí se parte del árbol JSON entero del GET y
 * se cambian SOLO las claves que la persona ha editado; todo lo demás sale tal
 * cual lo entregó el servidor.
 *
 * «Editado» significa «distinto de lo que el formulario habría mostrado»: se
 * compara el borrador con el borrador que sale de leer el original con las
 * mismas reglas de saneado. Así, una descripción con caracteres que el saneado
 * quita, o un nombre que pasa del límite, no se reescriben si la persona no los
 * ha tocado.
 */
internal object RecipeDraftJson {

    /**
     * Marcas de tiempo que Mealie rellena al guardar. Reenviar la del GET
     * sería devolverle un valor que no hemos editado y que ya está viejo, así
     * que no se envían (ni `updatedAt`, que es la clave de salida, ni
     * `update_at`, que es la de entrada, según `docs/openapi.json`).
     */
    val SERVER_MANAGED_KEYS: Set<String> = setOf("dateUpdated", "updatedAt", "update_at", "createdAt")

    sealed interface Merge {
        class Body(val json: JsonObject) : Merge
        class Errors(val errors: List<DraftError>) : Merge
    }

    // ---------------------------------------------------------------- leer

    fun toDraft(obj: JsonObject): RecipeDraft = RecipeDraft(
        name = RecipeDraftValidator.plain(obj.string("name"), RecipeDraftValidator.MAX_NAME),
        description = RecipeDraftValidator.multiline(obj.string("description"), RecipeDraftValidator.MAX_DESCRIPTION),
        servings = servingsOf(obj),
        totalTime = timeOf(obj, "totalTime"),
        prepTime = timeOf(obj, "prepTime"),
        cookTime = timeOf(obj, "cookTime"),
        performTime = timeOf(obj, "performTime"),
        ingredients = objects(obj["recipeIngredient"]).map { ingredientOf(it) },
        steps = objects(obj["recipeInstructions"]).map { stepOf(it) },
        notes = objects(obj["notes"]).map { noteOf(it) },
        tags = objects(obj["tags"]).mapNotNull { organizerOf(it) },
        categories = objects(obj["recipeCategory"]).mapNotNull { organizerOf(it) },
    )

    private fun servingsOf(obj: JsonObject): Double? =
        obj.number("recipeServings")?.takeIf { it.isFinite() && it > 0.0 && it <= RecipeDraftValidator.MAX_SERVINGS }

    private fun timeOf(obj: JsonObject, key: String): String =
        RecipeDraftValidator.plain(obj.string(key), RecipeDraftValidator.MAX_TIME_TEXT)

    private fun ingredientOf(o: JsonObject) = DraftIngredient(
        quantity = RecipeDraftValidator.validQuantity(o.number("quantity")),
        unit = RecipeDraftValidator.plain((o["unit"] as? JsonObject)?.string("name"), RecipeDraftValidator.MAX_UNIT_FOOD),
        food = RecipeDraftValidator.plain((o["food"] as? JsonObject)?.string("name"), RecipeDraftValidator.MAX_UNIT_FOOD),
        note = RecipeDraftValidator.plain(o.string("note"), RecipeDraftValidator.MAX_INGREDIENT_TEXT),
        title = RecipeDraftValidator.plain(o.string("title"), RecipeDraftValidator.MAX_INGREDIENT_TEXT),
        sourceId = RecipeDraftValidator.validUuid(o.string("referenceId")),
    )

    private fun stepOf(o: JsonObject) = DraftStep(
        title = RecipeDraftValidator.plain(o.string("title"), RecipeDraftValidator.MAX_STEP_TITLE),
        text = RecipeDraftValidator.multiline(o.string("text"), RecipeDraftValidator.MAX_STEP_TEXT),
        sourceId = RecipeDraftValidator.validUuid(o.string("id")),
    )

    private fun noteOf(o: JsonObject) = DraftNote(
        title = RecipeDraftValidator.plain(o.string("title"), RecipeDraftValidator.MAX_NOTE_TITLE),
        text = RecipeDraftValidator.multiline(o.string("text"), RecipeDraftValidator.MAX_NOTE_TEXT),
        sourceId = RecipeDraftValidator.validUuid(o.string("referenceId")),
    )

    /** Null si no tiene nombre: sin nombre no se puede mostrar ni elegir. */
    private fun organizerOf(o: JsonObject): DraftOrganizer? {
        val name = RecipeDraftValidator.plain(o.string("name"), RecipeDraftValidator.MAX_ORGANIZER_NAME)
        if (name.isEmpty()) return null
        return DraftOrganizer(
            id = RecipeDraftValidator.validUuid(o.string("id")),
            name = name,
            slug = o.string("slug")?.takeIf { RecipeSlug.isValid(it) },
        )
    }

    // ------------------------------------------------------------- mezclar

    /**
     * El cuerpo del `PUT`: [original] con las claves editadas sustituidas.
     * [draft] tiene que haber pasado ya por `RecipeDraftValidator`.
     *
     * Devuelve [Merge.Errors] solo para lo que no se puede escribir sin
     * inventar la API: cambiar la unidad o el alimento de una línea.
     */
    fun merge(original: JsonObject, draft: RecipeDraft): Merge {
        val errors = ArrayList<DraftError>()
        val before = toDraft(original)
        val out = LinkedHashMap<String, JsonElement>(original)
        for (key in SERVER_MANAGED_KEYS) out.remove(key)

        if (draft.name != before.name) out["name"] = JsonPrimitive(draft.name)
        if (draft.description != before.description) out["description"] = JsonPrimitive(draft.description)
        if (draft.servings != before.servings) {
            // Mealie usa 0 para «sin raciones».
            out["recipeServings"] = JsonPrimitive(draft.servings ?: 0.0)
        }
        // Los «…Seconds» los calcula Mealie a partir del texto. Si el texto
        // cambia, el valor viejo ya no corresponde: se anula (solo si la clave
        // existía) en vez de reenviar un dato falso.
        time(out, original, "totalTime", draft.totalTime, before.totalTime, "totalTimeSeconds")
        time(out, original, "prepTime", draft.prepTime, before.prepTime, "prepTimeSeconds")
        time(out, original, "cookTime", draft.cookTime, before.cookTime, null)
        time(out, original, "performTime", draft.performTime, before.performTime, "performTimeSeconds")

        mergeList(
            out, original, "recipeIngredient", draft.ingredients,
            parse = { ingredientOf(it) },
            idOf = { it.sourceId },
            strip = { it.copy(sourceId = null) },
            build = { wanted, index -> newIngredient(wanted, index, errors) },
            rebuild = { base, was, wanted, index -> editedIngredient(base, was, wanted, index, errors) },
        )
        mergeList(
            out, original, "recipeInstructions", draft.steps,
            parse = { stepOf(it) },
            idOf = { it.sourceId },
            strip = { it.copy(sourceId = null) },
            build = { wanted, _ -> JsonObject(mapOf("title" to JsonPrimitive(wanted.title), "text" to JsonPrimitive(wanted.text))) },
            rebuild = { base, was, wanted, _ ->
                val b = LinkedHashMap<String, JsonElement>(base)
                if (wanted.title != was.title) b["title"] = JsonPrimitive(wanted.title)
                if (wanted.text != was.text) b["text"] = JsonPrimitive(wanted.text)
                JsonObject(b)
            },
        )
        mergeList(
            out, original, "notes", draft.notes,
            parse = { noteOf(it) },
            idOf = { it.sourceId },
            strip = { it.copy(sourceId = null) },
            build = { wanted, _ -> JsonObject(mapOf("title" to JsonPrimitive(wanted.title), "text" to JsonPrimitive(wanted.text))) },
            rebuild = { base, was, wanted, _ ->
                val b = LinkedHashMap<String, JsonElement>(base)
                if (wanted.title != was.title) b["title"] = JsonPrimitive(wanted.title)
                if (wanted.text != was.text) b["text"] = JsonPrimitive(wanted.text)
                JsonObject(b)
            },
        )
        mergeOrganizers(out, original, "tags", draft.tags)
        mergeOrganizers(out, original, "recipeCategory", draft.categories)

        return if (errors.isEmpty()) Merge.Body(JsonObject(out)) else Merge.Errors(errors)
    }

    private fun time(
        out: MutableMap<String, JsonElement>,
        original: JsonObject,
        key: String,
        wanted: String,
        was: String,
        secondsKey: String?,
    ) {
        if (wanted == was) return
        out[key] = if (wanted.isEmpty()) JsonNull else JsonPrimitive(wanted)
        if (secondsKey != null && original.containsKey(secondsKey)) out[secondsKey] = JsonNull
    }

    private fun newIngredient(wanted: DraftIngredient, index: Int, errors: MutableList<DraftError>): JsonObject {
        // Sin línea original no hay objeto de unidad o alimento que conservar.
        if (wanted.unit.isNotEmpty()) errors += DraftError(DraftField.IngredientUnit, DraftErrorKind.NotEditable, index)
        if (wanted.food.isNotEmpty()) errors += DraftError(DraftField.IngredientFood, DraftErrorKind.NotEditable, index)
        val fields = LinkedHashMap<String, JsonElement>()
        wanted.quantity?.let { fields["quantity"] = JsonPrimitive(it) }
        fields["note"] = JsonPrimitive(wanted.note)
        if (wanted.title.isNotEmpty()) fields["title"] = JsonPrimitive(wanted.title)
        return JsonObject(fields)
    }

    private fun editedIngredient(
        base: JsonObject,
        was: DraftIngredient,
        wanted: DraftIngredient,
        index: Int,
        errors: MutableList<DraftError>,
    ): JsonObject {
        if (wanted.unit != was.unit) errors += DraftError(DraftField.IngredientUnit, DraftErrorKind.NotEditable, index)
        if (wanted.food != was.food) errors += DraftError(DraftField.IngredientFood, DraftErrorKind.NotEditable, index)
        val b = LinkedHashMap<String, JsonElement>(base)
        if (wanted.quantity != was.quantity) {
            b["quantity"] = wanted.quantity?.let { JsonPrimitive(it) } ?: JsonNull
        }
        if (wanted.note != was.note) b["note"] = JsonPrimitive(wanted.note)
        if (wanted.title != was.title) b["title"] = if (wanted.title.isEmpty()) JsonNull else JsonPrimitive(wanted.title)
        // «display» es el texto que Mealie compone con cantidad, unidad,
        // alimento y nota: tras editar la línea ya no corresponde y se deja que
        // lo recalcule el servidor.
        b.remove("display")
        return JsonObject(b)
    }

    private fun mergeOrganizers(
        out: MutableMap<String, JsonElement>,
        original: JsonObject,
        key: String,
        drafts: List<DraftOrganizer>,
    ) {
        mergeList(
            out, original, key, drafts,
            // Los organizadores sin nombre ya no están en el borrador; el parse
            // de aquí solo se usa para casar por id, y un nombre vacío no casa.
            parse = { organizerOf(it) ?: DraftOrganizer() },
            idOf = { it.id },
            strip = { it },
            build = { wanted, _ ->
                val f = LinkedHashMap<String, JsonElement>()
                wanted.id?.let { f["id"] = JsonPrimitive(it) }
                f["name"] = JsonPrimitive(wanted.name)
                wanted.slug?.let { f["slug"] = JsonPrimitive(it) }
                JsonObject(f)
            },
            rebuild = { base, _, wanted, _ ->
                val f = LinkedHashMap<String, JsonElement>(base)
                f["name"] = JsonPrimitive(wanted.name)
                wanted.slug?.let { f["slug"] = JsonPrimitive(it) }
                JsonObject(f)
            },
        )
    }

    private class Existing<D>(val json: JsonObject, val draft: D, val id: String?)

    /**
     * Reconstruye la lista [key]:
     * - una línea con `sourceId` que casa con una del original y sin cambios
     *   sale idéntica (el JSON del servidor, sin tocar);
     * - si casa pero cambió, se parte del original y se sustituye lo editado;
     * - si no casa (nueva, o ya no existe en el servidor), se construye de cero.
     * Lo que estaba en el original y ya no está en el borrador se elimina: es
     * lo que significa borrar una línea del formulario.
     */
    private fun <D> mergeList(
        out: MutableMap<String, JsonElement>,
        original: JsonObject,
        key: String,
        drafts: List<D>,
        parse: (JsonObject) -> D,
        idOf: (D) -> String?,
        strip: (D) -> D,
        build: (D, Int) -> JsonObject,
        rebuild: (JsonObject, D, D, Int) -> JsonObject,
    ) {
        val existing = objects(original[key]).map { json ->
            val parsed = parse(json)
            Existing(json, parsed, idOf(parsed))
        }
        val used = HashSet<Int>()
        val result = ArrayList<JsonElement>(drafts.size)
        drafts.forEachIndexed { index, wanted ->
            val id = idOf(wanted)
            val position = if (id == null) {
                -1
            } else {
                existing.indices.firstOrNull { it !in used && existing[it].id == id } ?: -1
            }
            if (position < 0) {
                result += build(wanted, index)
                return@forEachIndexed
            }
            used += position
            val match = existing[position]
            result += if (strip(match.draft) == strip(wanted)) match.json else rebuild(match.json, match.draft, wanted, index)
        }
        // Sin nada que escribir y sin nada que borrar, la clave se deja como venía
        // (ausente, null o vacía): no se inventa un «[]» donde el servidor no lo puso.
        if (drafts.isEmpty() && existing.isEmpty()) return
        out[key] = JsonArray(result)
    }

    // ------------------------------------------------------------ utilidades

    private fun objects(element: JsonElement?): List<JsonObject> =
        (element as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
