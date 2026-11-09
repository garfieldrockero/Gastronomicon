package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.recipes.Page

/**
 * De dónde salen y a dónde van los cambios de una lista de la compra. Lo
 * implementa [ShoppingClient]; las pruebas del controlador ponen una versión
 * falsa y no necesitan red.
 *
 * Es síncrono, como el resto del núcleo, y devuelve [ApiResult]: nunca lanza
 * por red. La única excepción prevista es [IllegalArgumentException] si un id o
 * un texto no pasan la validación local antes de enviar nada.
 *
 * [listLists], [createList], [addRecipe] y [addRecipes] tienen una
 * implementación por defecto que falla sin tocar la red: existen para que una
 * fuente que solo gestiona elementos (como la que monta `app/`) siga
 * compilando. Una fuente que quiera usarlas tiene que sobrescribirlas; si no,
 * el controlador recibe un fallo tipado y no una respuesta inventada.
 */
interface ShoppingListSource {

    /** Una página de listas. Si la fuente no la implementa, falla sin tocar la red. */
    fun listLists(
        page: Int = 1,
        perPage: Int = ShoppingClient.DEFAULT_LISTS_PER_PAGE,
    ): ApiResult<Page<ShoppingListSummary>> =
        throw UnsupportedOperationException("La fuente de la compra no implementa listLists.")

    /**
     * Crea una lista con [name] ya saneado y validado. Si la fuente no la
     * implementa, falla sin tocar la red.
     */
    fun createList(name: String): ApiResult<ShoppingListSummary> =
        throw UnsupportedOperationException("La fuente de la compra no implementa createList.")

    /** La lista completa con sus elementos. */
    fun getList(listId: String): ApiResult<ShoppingList>

    /**
     * Añade una nota de texto libre a la lista. No devuelve el elemento: el
     * controlador vuelve a leer la lista para no inventar ids ni duplicar.
     */
    fun addItem(listId: String, text: String): ApiResult<Unit>

    /**
     * Añade los ingredientes de una receta, escalados por [scale]
     * (`POST /api/households/shopping/lists/{item_id}/recipe`). Si la fuente no
     * la implementa, falla sin tocar la red.
     */
    fun addRecipe(listId: String, recipeId: String, scale: Double = 1.0): ApiResult<Unit> =
        throw UnsupportedOperationException("La fuente de la compra no implementa addRecipe.")

    /**
     * Añade los ingredientes de varias recetas en una sola petición. Es la que
     * usa el controlador; [addRecipe] es el atajo de una. Si la fuente no la
     * implementa, falla sin tocar la red.
     */
    fun addRecipes(listId: String, additions: List<RecipeAddition>): ApiResult<Unit> =
        throw UnsupportedOperationException("La fuente de la compra no implementa addRecipes.")

    /** Marca o desmarca un elemento existente. */
    fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit>

    /** Cambia la nota y la cantidad de un elemento existente. */
    fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit>

    /** Borra un elemento por su id. */
    fun deleteItem(itemId: String): ApiResult<Unit>

    /** Borra todos los elementos marcados de la lista recibida. */
    fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit>
}
