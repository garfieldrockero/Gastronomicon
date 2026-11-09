package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.recipes.IngredientFood
import io.github.garfieldrockero.gastronomicon.core.recipes.IngredientUnit
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Lectura de `GET /api/units` y `GET /api/foods`, y de la respuesta de sus
 * `POST` (`IngredientUnit-Output`, `IngredientFood-Output`,
 * `IngredientUnitPagination` e `IngredientFoodPagination` de
 * docs/openapi.json).
 *
 * Como el resto de DTO de lectura: todo anulable, solo lo que se usa, y nada
 * llega a la interfaz sin pasar por [IngredientCatalogMapper]. Los campos que
 * la app no escribe (`fraction`, `extras`, `aliases`, `standardQuantity`…)
 * siguen en el JSON del servidor porque el `PUT` de la receta parte de su
 * árbol; aquí no se leen.
 */

@Serializable
internal class IngredientUnitDto(
    val id: String? = null,
    val name: String? = null,
    val pluralName: String? = null,
    val abbreviation: String? = null,
    val pluralAbbreviation: String? = null,
    val useAbbreviation: Boolean? = null,
)

@Serializable
internal class IngredientFoodDto(
    val id: String? = null,
    val name: String? = null,
    val pluralName: String? = null,
)

/** `next` y `previous` no se declaran: no se siguen (son URLs ajenas). */
@Serializable
internal class IngredientUnitPageDto(
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val total: Long? = null,
    val items: List<IngredientUnitDto?>? = null,
)

@Serializable
internal class IngredientFoodPageDto(
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val total: Long? = null,
    val items: List<IngredientFoodDto?>? = null,
)

internal object IngredientCatalogMapper {

    /**
     * Null si falta el id (UUID) o el nombre: sin ellos no se puede enseñar la
     * entrada ni reenviarla. La abreviatura y el plural son opcionales y se
     * sanean; si se pasan del tope se recortan (solo afecta a cómo se ven, no
     * se reenvían nunca: el `PUT` parte del JSON del servidor).
     */
    fun unit(dto: IngredientUnitDto): IngredientUnit? {
        val id = RecipeImage.normalizeId(dto.id) ?: return null
        val name = plain(dto.name, RecipeDraftValidator.MAX_UNIT_FOOD)
        if (name.isEmpty()) return null
        return IngredientUnit(
            id = id,
            name = name,
            pluralName = plain(dto.pluralName, RecipeDraftValidator.MAX_UNIT_FOOD).ifEmpty { null },
            abbreviation = plain(dto.abbreviation, RecipeDraftValidator.MAX_ABBREVIATION),
            pluralAbbreviation = plain(dto.pluralAbbreviation, RecipeDraftValidator.MAX_ABBREVIATION)
                .ifEmpty { null },
            useAbbreviation = dto.useAbbreviation == true,
        )
    }

    /** Igual que [unit], para `GET /api/foods`. */
    fun food(dto: IngredientFoodDto): IngredientFood? {
        val id = RecipeImage.normalizeId(dto.id) ?: return null
        val name = plain(dto.name, RecipeDraftValidator.MAX_UNIT_FOOD)
        if (name.isEmpty()) return null
        return IngredientFood(
            id = id,
            name = name,
            pluralName = plain(dto.pluralName, RecipeDraftValidator.MAX_UNIT_FOOD).ifEmpty { null },
        )
    }

    private fun plain(raw: String?, max: Int): String = PlainTextSanitizer.sanitize(raw, max)
}
