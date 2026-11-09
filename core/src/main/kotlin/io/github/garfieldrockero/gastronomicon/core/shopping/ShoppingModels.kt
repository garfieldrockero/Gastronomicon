package io.github.garfieldrockero.gastronomicon.core.shopping

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Una lista de la compra tal como llega del servidor, ya saneada.
 *
 * [name] puede quedar vacío (Mealie lo permite); la interfaz enseña
 * [displayName] para no tener que decidir un texto por su cuenta.
 */
data class ShoppingListSummary(val id: String, val name: String) {
    val displayName: String get() = name.ifEmpty { ShoppingText.DEFAULT_LIST_NAME }

    // Sin el nombre: es texto del servidor y un resumen puede acabar en un log.
    override fun toString(): String =
        "ShoppingListSummary(id=$id, hasName=${name.isNotEmpty()})"
}

/**
 * Una lista con sus elementos. [items] ya viene saneada, sin ids repetidos y
 * acotada por el cliente.
 */
data class ShoppingList(
    val id: String,
    val name: String,
    val items: List<ShoppingItem>,
) {
    val displayName: String get() = name.ifEmpty { ShoppingText.DEFAULT_LIST_NAME }

    // Sin el nombre ni los textos de los elementos: son datos del servidor.
    override fun toString(): String =
        "ShoppingList(id=$id, items=${items.size}, hasName=${name.isNotEmpty()})"
}

/**
 * Un elemento de la lista de la compra, ya saneado y listo para pintar o para
 * reenviar al servidor al marcar o editar.
 *
 * Las cantidades absurdas (negativas, no finitas o por encima del tope) se
 * guardan como 0, que es el «sin cantidad» de Mealie. La unidad y el alimento
 * son de solo lectura en esta fase: se conservan para no perderlos al editar la
 * nota y para poder volver a mandarlos en el `PUT`.
 *
 * [foodId] y [unitId] no se enseñan nunca: están solo para devolver al servidor
 * lo que ya tenía el elemento.
 *
 * [rawJson] es el objeto JSON tal cual lo mandó el servidor (acotado por
 * `ShoppingMapper`). El `PUT` parte de él y cambia solo lo editado, para no
 * perder `recipeReferences`, `extras` ni campos de otra versión de Mealie. Es
 * `null` si el elemento no vino del servidor (o si era desmesurado); en ese
 * caso se compone un cuerpo mínimo con [foodId], [unitId] y [labelId].
 */
data class ShoppingItem(
    val id: String,
    val shoppingListId: String,
    val note: String,
    /** 0 significa «sin cantidad». */
    val quantity: Double,
    val unitSingular: String,
    val unitPlural: String,
    val foodSingular: String,
    val foodPlural: String,
    val labelId: String?,
    val labelName: String,
    val checked: Boolean,
    /** Orden manual que pone Mealie; nunca negativo. */
    val position: Int,
    val foodId: String? = null,
    val unitId: String? = null,
    val rawJson: JsonObject? = null,
) {
    /** Unidad según la cantidad (plural por encima de 1). */
    val unit: String get() = if (quantity > 1.0) unitPlural else unitSingular

    /** Alimento según la cantidad (plural por encima de 1). */
    val food: String get() = if (quantity > 1.0) foodPlural else foodSingular

    // Sin notas, alimentos ni el JSON crudo: todo eso es del servidor y un elemento puede acabar en un log.
    override fun toString(): String =
        "ShoppingItem(id=$id, checked=$checked, quantity=$quantity, hasNote=${note.isNotEmpty()}, " +
            "hasFood=${foodSingular.isNotEmpty()}, hasRaw=${rawJson != null})"
}

/**
 * Copia del elemento con la nota y la cantidad que se acaban de guardar, y con
 * el JSON crudo actualizado igual. Sin esto, un `PUT` posterior de solo
 * `checked` reenviaría la nota vieja del JSON y desharía la edición.
 *
 * Solo se tocan esas dos claves: el resto del objeto sigue tal cual lo mandó el
 * servidor.
 */
internal fun ShoppingItem.withSavedContent(note: String, quantity: Double): ShoppingItem {
    val raw = rawJson
        ?: return copy(note = note, quantity = quantity)
    val fields = LinkedHashMap<String, JsonElement>(raw)
    fields["note"] = JsonPrimitive(note)
    fields["quantity"] = JsonPrimitive(quantity)
    return copy(note = note, quantity = quantity, rawJson = JsonObject(fields))
}
