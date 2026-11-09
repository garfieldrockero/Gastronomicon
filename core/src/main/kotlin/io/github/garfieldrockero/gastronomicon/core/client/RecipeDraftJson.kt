package io.github.garfieldrockero.gastronomicon.core.client

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

    private fun ingredientOf(o: JsonObject): DraftIngredient {
        val unit = RecipeDraftValidator.plain(
            (o["unit"] as? JsonObject)?.string("name"),
            RecipeDraftValidator.MAX_UNIT_FOOD,
        )
        val food = RecipeDraftValidator.plain(
            (o["food"] as? JsonObject)?.string("name"),
            RecipeDraftValidator.MAX_UNIT_FOOD,
        )
        return DraftIngredient(
            quantity = RecipeDraftValidator.validQuantity(o.number("quantity")),
            unit = unit,
            food = food,
            note = RecipeDraftValidator.plain(o.string("note"), RecipeDraftValidator.MAX_INGREDIENT_TEXT),
            title = RecipeDraftValidator.plain(o.string("title"), RecipeDraftValidator.MAX_INGREDIENT_TEXT),
            sourceId = RecipeDraftValidator.validUuid(o.string("referenceId")),
            // Un id sin nombre no se puede ni mostrar ni reenviar: se descarta al
            // leer. Así una línea del servidor con el nombre vacío o ilegible
            // tras sanear no bloquea el guardado por un `Required` al no haberse
            // tocado (y la línea sale idéntica en el `PUT`).
            unitId = RecipeDraftValidator.validUuid((o["unit"] as? JsonObject)?.string("id"))
                ?.takeIf { unit.isNotEmpty() },
            foodId = RecipeDraftValidator.validUuid((o["food"] as? JsonObject)?.string("id"))
                ?.takeIf { food.isNotEmpty() },
        )
    }

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
     * No puede fallar: la validación previa ya ha rechazado lo que no se puede
     * escribir (un id de unidad sin nombre, por ejemplo).
     */
    fun merge(original: JsonObject, draft: RecipeDraft): JsonObject {
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
            // Se comparan cantidad, nota, título y las dos referencias: un id
            // distinto con el mismo nombre sí es un cambio, y un borrador sin
            // ids (de una versión anterior) sigue contando como «sin cambio»
            // porque solo se miran los nombres.
            same = { was, wanted -> !ingredientChanged(was, wanted) },
            build = { wanted, _ -> newIngredient(wanted) },
            rebuild = { base, was, wanted, _ -> editedIngredient(base, was, wanted) },
        )
        mergeList(
            out, original, "recipeInstructions", draft.steps,
            parse = { stepOf(it) },
            idOf = { it.sourceId },
            same = { was, wanted -> was == wanted },
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
            same = { was, wanted -> was == wanted },
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

        return JsonObject(out)
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

    /**
     * Una línea nueva: solo lo que la persona ha escrito. La unidad y el
     * alimento van como referencia al catálogo (`{"id":…,"name":…}`) o, si
     * solo hay nombre, como `CreateIngredientUnit`/`CreateIngredientFood`
     * (`{"name":…}`), que es lo que admite `RecipeIngredient-Input`.
     */
    private fun newIngredient(wanted: DraftIngredient): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        wanted.quantity?.let { fields["quantity"] = JsonPrimitive(it) }
        referenceValue(wanted.unitId, wanted.unit)?.let { fields["unit"] = it }
        referenceValue(wanted.foodId, wanted.food)?.let { fields["food"] = it }
        fields["note"] = JsonPrimitive(wanted.note)
        if (wanted.title.isNotEmpty()) fields["title"] = JsonPrimitive(wanted.title)
        return JsonObject(fields)
    }

    /**
     * Una línea que ya existía: se parte de su JSON y solo se toca lo editado.
     * Cambiar la unidad o el alimento NO pierde el resto de la línea (nota,
     * título, receta referenciada, campos que esta versión no conoce).
     */
    private fun editedIngredient(base: JsonObject, was: DraftIngredient, wanted: DraftIngredient): JsonObject {
        val b = LinkedHashMap<String, JsonElement>(base)
        if (wanted.quantity != was.quantity) {
            b["quantity"] = wanted.quantity?.let { JsonPrimitive(it) } ?: JsonNull
        }
        if (wanted.note != was.note) b["note"] = JsonPrimitive(wanted.note)
        if (wanted.title != was.title) b["title"] = if (wanted.title.isEmpty()) JsonNull else JsonPrimitive(wanted.title)
        if (referenceChanged(was.unit, was.unitId, wanted.unit, wanted.unitId)) {
            b["unit"] = editedReference(was.unitId, wanted.unitId, wanted.unit)
        }
        if (referenceChanged(was.food, was.foodId, wanted.food, wanted.foodId)) {
            b["food"] = editedReference(was.foodId, wanted.foodId, wanted.food)
        }
        // «display» es el texto que Mealie compone con cantidad, unidad,
        // alimento y nota: tras editar la línea ya no corresponde y se deja que
        // lo recalcule el servidor.
        b.remove("display")
        return JsonObject(b)
    }

    /**
     * ¿Ha cambiado la referencia al catálogo?
     *
     * - Mismo nombre y sin id nuevo no es un cambio (un borrador que perdió los
     *   ids, por ejemplo de una versión anterior, no reescribe la línea).
     * - Con id en el borrador, cambia si el id o el nombre no coinciden: el
     *   nombre distinto con el mismo id no se ignora (se manda solo la
     *   referencia por id) y el id distinto con el mismo nombre tampoco.
     * - Solo nombre: es una creación por nombre, cuenta si el nombre cambia.
     */
    private fun referenceChanged(wasName: String, wasId: String?, wantedName: String, wantedId: String?): Boolean =
        when {
            wantedName.isEmpty() && wantedId == null -> wasName.isNotEmpty() || wasId != null
            wantedId != null -> wantedId != wasId || wantedName != wasName
            // Solo nombre: es una creación por nombre, cuenta si el nombre cambia.
            else -> wantedName != wasName
        }

    /**
     * ¿Hay algo que escribir en esta línea? Se comparan los datos visibles y las
     * referencias con [referenceChanged], no la igualdad entera: un borrador
     * sin ids con los mismos nombres tiene que salir sin tocar.
     */
    private fun ingredientChanged(was: DraftIngredient, wanted: DraftIngredient): Boolean =
        was.quantity != wanted.quantity ||
            was.note != wanted.note ||
            was.title != wanted.title ||
            referenceChanged(was.unit, was.unitId, wanted.unit, wanted.unitId) ||
            referenceChanged(was.food, was.foodId, wanted.food, wanted.foodId)

    /**
     * El `unit`/`food` que se envía.
     *
     * - Mismo id que el original: se manda **solo** `{"id":…}`. Reenviar el id
     *   con un nombre nuevo podría renombrar la entrada del catálogo para todas
     *   las recetas (o, si Mealie solo mira el id, perder el cambio sin avisar):
     *   el nombre que se ve en la línea no se escribe en el catálogo.
     * - Id distinto (o ninguno): referencia mínima con id y nombre, o solo
     *   nombre (`CreateIngredientUnit`/`CreateIngredientFood`).
     */
    private fun editedReference(wasId: String?, wantedId: String?, name: String): JsonElement {
        if (wantedId != null && wantedId == wasId) {
            return JsonObject(mapOf("id" to JsonPrimitive(wantedId)))
        }
        return referenceValue(wantedId, name) ?: JsonNull
    }

    /** Referencia al catálogo, o null si la línea no lleva unidad o alimento. */
    private fun referenceValue(id: String?, name: String): JsonElement? =
        when {
            id != null -> JsonObject(
                linkedMapOf<String, JsonElement>(
                    "id" to JsonPrimitive(id),
                    "name" to JsonPrimitive(name),
                ),
            )
            name.isNotEmpty() -> JsonObject(mapOf("name" to JsonPrimitive(name)))
            else -> null
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
            same = { was, wanted -> was == wanted },
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
        same: (D, D) -> Boolean,
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
            result += if (same(match.draft, wanted)) match.json else rebuild(match.json, match.draft, wanted, index)
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
