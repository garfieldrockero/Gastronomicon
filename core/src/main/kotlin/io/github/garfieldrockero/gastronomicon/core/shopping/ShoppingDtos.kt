package io.github.garfieldrockero.gastronomicon.core.shopping

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Lectura de `/api/households/shopping/...` según docs/openapi.json y cuerpos
 * de escritura mínimos.
 *
 * Como el resto de DTO de lectura: todo anulable y con valor por defecto, para
 * que un campo nuevo o un null de otra versión de Mealie no rompan la app.
 * Solo se declaran los campos que se usan; el resto los ignora `CoreJson`.
 */

/** `ShoppingListPagination.items` de `GET /api/households/shopping/lists`. */
@Serializable
internal class ShoppingListSummaryDto(
    val id: String? = null,
    val name: String? = null,
)

/** `ShoppingListPagination`. `next` y `previous` no se declaran: no se siguen. */
@Serializable
internal class ShoppingListPageDto(
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val total: Long? = null,
    val items: List<ShoppingListSummaryDto?>? = null,
)

/**
 * Cuerpo de `POST /api/households/shopping/lists` (`ShoppingListCreate`).
 *
 * Solo viaja el nombre: `extras` y las marcas de tiempo son opcionales y las
 * rellena el servidor. `CoreJson` omite los campos nulos, pero aquí no hay
 * ninguno que enviar. La respuesta (201, `ShoppingListOut`) se lee con
 * [ShoppingListSummaryDto], que ignora el resto de campos.
 */
@Serializable
internal class ShoppingListCreateDto(
    val name: String,
)

/** `ShoppingListOut` de `GET /api/households/shopping/lists/{item_id}`. */
@Serializable
internal class ShoppingListDto(
    val id: String? = null,
    val name: String? = null,
    val listItems: List<ShoppingItemDto?>? = null,
)

/**
 * `ShoppingListItemOut-Output`. `display` no se declara a propósito: el texto
 * de la línea lo compone el núcleo y no se confía en el que mande el servidor.
 */
@Serializable
internal class ShoppingItemDto(
    val id: String? = null,
    val shoppingListId: String? = null,
    val quantity: Double? = null,
    val note: String? = null,
    val checked: Boolean? = null,
    val position: Int? = null,
    val foodId: String? = null,
    val unitId: String? = null,
    val labelId: String? = null,
    val food: ShoppingFoodDto? = null,
    val unit: ShoppingUnitDto? = null,
    val label: ShoppingLabelDto? = null,
)

@Serializable
internal class ShoppingFoodDto(
    val id: String? = null,
    val name: String? = null,
    val pluralName: String? = null,
)

@Serializable
internal class ShoppingUnitDto(
    val id: String? = null,
    val name: String? = null,
    val pluralName: String? = null,
    val abbreviation: String? = null,
    val pluralAbbreviation: String? = null,
    val useAbbreviation: Boolean? = null,
)

@Serializable
internal class ShoppingLabelDto(
    val id: String? = null,
    val name: String? = null,
)

/**
 * Cuerpo de `POST /api/households/shopping/items` (`ShoppingListItemCreate`).
 *
 * Solo viaja lo declarado con valor no nulo: `CoreJson` tiene
 * `explicitNulls = false`, así que los campos opcionales que no se rellenan se
 * omiten y el servidor aplica sus valores por defecto. El elemento se crea con
 * una nota de texto libre; no se inventan alimento, unidad ni cantidad.
 *
 * El `PUT` de un elemento no tiene DTO: se hace sobre el `JsonObject` crudo de
 * la lectura para no perder los campos que esta versión de la app no conoce
 * (ver `ShoppingClient.update` y `ShoppingItem.rawJson`).
 */
@Serializable
internal class ShoppingItemCreateDto(
    val shoppingListId: String,
    val note: String,
    val quantity: Double? = null,
)

/**
 * Un elemento del cuerpo de `POST /api/households/shopping/lists/{item_id}/recipe`
 * (`ShoppingListAddRecipeParamsBulk`): la petición lleva un array de estos.
 *
 * Solo viajan `recipeId` y `recipeIncrementQuantity` (la escala). Se omite
 * `recipeIngredients` a propósito: el servidor toma los ingredientes de la
 * receta, y mandarlos obligaría a la app a reproducir su formato de ingrediente
 * (`RecipeIngredient-Input`) sin necesidad. `recipeIncrementQuantity` es
 * opcional en el esquema (vale 1 por defecto), pero se manda siempre para que la
 * escala no dependa del valor por defecto de cada versión de Mealie.
 */
@Serializable
internal class ShoppingListAddRecipeDto(
    val recipeId: String,
    val recipeIncrementQuantity: Double,
)
