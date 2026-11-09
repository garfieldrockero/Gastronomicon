package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult

/**
 * De dónde salen y a dónde van los cambios de una lista de la compra. Lo
 * implementa [ShoppingClient]; las pruebas del controlador ponen una versión
 * falsa y no necesitan red.
 *
 * Es síncrono, como el resto del núcleo, y devuelve [ApiResult]: nunca lanza
 * por red. La única excepción prevista es [IllegalArgumentException] si un id o
 * un texto no pasan la validación local antes de enviar nada.
 */
interface ShoppingListSource {

    /** La lista completa con sus elementos. */
    fun getList(listId: String): ApiResult<ShoppingList>

    /**
     * Añade una nota de texto libre a la lista. No devuelve el elemento: el
     * controlador vuelve a leer la lista para no inventar ids ni duplicar.
     */
    fun addItem(listId: String, text: String): ApiResult<Unit>

    /** Marca o desmarca un elemento existente. */
    fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit>

    /** Cambia la nota y la cantidad de un elemento existente. */
    fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit>

    /** Borra un elemento por su id. */
    fun deleteItem(itemId: String): ApiResult<Unit>

    /** Borra todos los elementos marcados de la lista recibida. */
    fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit>
}
