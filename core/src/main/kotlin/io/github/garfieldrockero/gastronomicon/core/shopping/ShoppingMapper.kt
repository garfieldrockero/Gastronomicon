package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.recipes.ServingScaler
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Convierte los DTO de la compra en modelos saneados y acotados.
 *
 * Es el único sitio por el que los textos del servidor entran en el estado:
 * notas, alimentos, unidades y etiquetas pasan por [PlainTextSanitizer]. Los
 * elementos sin id utilizable se descartan, porque no se podrían marcar ni
 * borrar sin inventar la ruta.
 *
 * Además del DTO se guarda el objeto JSON crudo del elemento (acotado a
 * [MAX_RAW_ITEM_CHARS]): el `PUT` parte de él para no perder los campos que
 * esta versión de la app no conoce (`recipeReferences`, `extras`, etc.).
 */
internal object ShoppingMapper {

    private const val MAX_TEXT = 100
    private const val MAX_LABEL = 100

    /**
     * Tamaño hasta el que el JSON crudo de un elemento se guarda entero. Por
     * encima se reduce quitando las claves de solo lectura (ver [boundedRaw]).
     */
    const val MAX_RAW_ITEM_CHARS = 4_096

    /**
     * Tope duro tras quitar las claves de solo lectura. La respuesta entera ya
     * está acotada a 2 MiB por el transporte, así que retener hasta 32 KiB por
     * elemento no abre un riesgo de memoria nuevo.
     */
    const val MAX_RAW_ITEM_HARD_CHARS = 32_768

    /**
     * Claves que Mealie rellena al leer (objetos anidados y texto compuesto) y
     * que se pueden volver a derivar de `foodId`, `unitId` y `labelId`. Se
     * pierden las primeras al reducir un elemento grande; los ids se conservan.
     */
    private val READ_ONLY_KEYS = setOf("food", "unit", "label", "display")

    /** Null si el id no es un UUID: un resumen sin id no se puede abrir. */
    fun summary(dto: ShoppingListSummaryDto): ShoppingListSummary? {
        val id = ShoppingIds.valid(dto.id) ?: return null
        return ShoppingListSummary(id = id, name = ShoppingText.listName(dto.name))
    }

    /**
     * [requestedId] ya está validado: es el de la ruta y el que se usa siempre,
     * también para los elementos, porque es la única lista que se ha pedido.
     * [rawItems] son los objetos JSON del array `listItems`, en el mismo orden
     * que [dto]: de ahí sale el JSON crudo de cada elemento.
     */
    fun list(
        dto: ShoppingListDto,
        requestedId: String,
        rawItems: List<JsonElement> = emptyList(),
    ): ShoppingList {
        val id = ShoppingIds.valid(dto.id) ?: requestedId
        val seen = HashSet<String>()
        val items = ArrayList<ShoppingItem>()
        val dtos = dto.listItems.orEmpty()
        for ((index, itemDto) in dtos.withIndex()) {
            // El tope evita que un servidor que ignora cualquier límite llene la memoria.
            if (items.size >= ShoppingListState.MAX_ITEMS) break
            if (index >= ShoppingListState.MAX_ITEMS * 2) break
            if (itemDto == null) continue
            val raw = rawItems.getOrNull(index) as? JsonObject
            val parsed = item(itemDto, id, raw) ?: continue
            if (seen.add(parsed.id)) items += parsed
        }
        return ShoppingList(id = id, name = ShoppingText.listName(dto.name), items = items)
    }

    /** Null si el elemento no tiene id, o si no le queda nada visible. */
    fun item(dto: ShoppingItemDto, expectedListId: String, raw: JsonObject? = null): ShoppingItem? {
        val id = ShoppingIds.valid(dto.id) ?: return null
        // Manda el id de la ruta: el que diga el elemento no se acepta para no
        // tratar como propia una lista distinta de la que se pidió. El JSON
        // crudo, en cambio, se reenvía tal cual al editar.
        val listId = expectedListId

        val labelId = ShoppingIds.valid(dto.labelId) ?: ShoppingIds.valid(dto.label?.id)
        // Una etiqueta sin id no se puede conservar al reenviar; se ignora entera.
        val labelName = if (labelId == null) "" else text(dto.label?.name, MAX_LABEL)

        val foodId = ShoppingIds.valid(dto.foodId) ?: ShoppingIds.valid(dto.food?.id)
        val foodSingular = text(dto.food?.name, MAX_TEXT)
        val foodPlural = text(dto.food?.pluralName, MAX_TEXT).ifEmpty { foodSingular }

        val unitId = ShoppingIds.valid(dto.unitId) ?: ShoppingIds.valid(dto.unit?.id)
        val unitName = text(dto.unit?.name, MAX_TEXT)
        val unitPluralName = text(dto.unit?.pluralName, MAX_TEXT).ifEmpty { unitName }
        val abbreviation = text(dto.unit?.abbreviation, MAX_TEXT)
        val pluralAbbreviation = text(dto.unit?.pluralAbbreviation, MAX_TEXT).ifEmpty { abbreviation }
        // Con «usar abreviatura» y una abreviatura vacía se cae al nombre completo.
        val useAbbreviation = dto.unit?.useAbbreviation == true && abbreviation.isNotEmpty()
        val unitSingular = if (useAbbreviation) abbreviation else unitName
        val unitPlural = if (useAbbreviation) pluralAbbreviation else unitPluralName

        val note = text(dto.note, ShoppingText.MAX_ITEM_TEXT)
        val quantity = quantity(dto.quantity)
        // Una línea sin texto ni cantidad no aporta nada; el servidor puede
        // mandar elementos a medio crear y no se pintan.
        val empty = note.isEmpty() && foodSingular.isEmpty() && unitSingular.isEmpty() && quantity <= 0.0
        if (empty) return null

        return ShoppingItem(
            id = id,
            shoppingListId = listId,
            note = note,
            quantity = quantity,
            unitSingular = unitSingular,
            unitPlural = unitPlural,
            foodSingular = foodSingular,
            foodPlural = foodPlural,
            labelId = labelId,
            labelName = labelName,
            checked = dto.checked == true,
            position = (dto.position ?: 0).coerceAtLeast(0),
            foodId = foodId,
            unitId = unitId,
            rawJson = boundedRaw(raw),
        )
    }

    /**
     * El JSON crudo, acotado sin perder lo que el `PUT` necesita. Hasta
     * [MAX_RAW_ITEM_CHARS] se guarda entero. Por encima se quitan solo las
     * claves de solo lectura (objetos anidados que el servidor recompone a
     * partir de los ids, y el texto ya compuesto), que son las que inflan un
     * elemento de receta, y se conservan `recipeReferences`, `extras` y las
     * claves desconocidas. Solo si aun así pasa de [MAX_RAW_ITEM_HARD_CHARS] se
     * descarta (cuerpo mínimo): perder en silencio los campos ajenos es el
     * último recurso, no el primero.
     */
    internal fun boundedRaw(raw: JsonObject?): JsonObject? {
        if (raw == null) return null
        if (raw.toString().length <= MAX_RAW_ITEM_CHARS) return raw
        val slim = JsonObject(raw.filterKeys { it !in READ_ONLY_KEYS })
        return if (slim.toString().length <= MAX_RAW_ITEM_HARD_CHARS) slim else null
    }

    private fun text(raw: String?, max: Int): String = PlainTextSanitizer.sanitize(raw, max)

    /** 0 si no hay cantidad: es el «sin cantidad» de Mealie y lo que entiende `QuantityFormatter`. */
    private fun quantity(raw: Double?): Double =
        raw?.takeIf { it.isFinite() && it >= 0.0 && it <= ServingScaler.MAX_QUANTITY } ?: 0.0
}
