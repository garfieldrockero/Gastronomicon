package io.github.garfieldrockero.gastronomicon.core.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * DTO de lectura: copia fiel y tolerante de lo que manda Mealie v3.29.0.
 *
 * Reglas, porque en `Recipe-Output` ningún campo es «required» (informe de
 * A1) y cada versión puede añadir otros:
 * - Todo campo tiene valor por defecto o es anulable. «coerceInputValues» de
 *   CoreJson cubre el null en campos no anulables; los elementos de lista se
 *   declaran anulables porque eso no lo cubre.
 * - Solo se declaran los campos que la app usa (los demás se ignoran).
 * - Nada de aquí llega a la interfaz: RecipeMapper lo sanea y lo acota.
 * - Son internos y sin toString útil a propósito: lo que viene del servidor
 *   no se imprime en ningún log.
 */

/** `AppInfo` de `GET /api/app/about`: lo mínimo para reconocer un Mealie. */
@Serializable
internal class AboutDto(
    val version: String? = null,
    // Anulable para distinguir «no viene» de «false»: un servidor que no es
    // Mealie no suele traer este campo.
    val production: Boolean? = null,
)

@Serializable
internal class LabelDto(
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
internal class UnitDto(
    val name: String? = null,
    val pluralName: String? = null,
    val abbreviation: String? = null,
    val pluralAbbreviation: String? = null,
    val useAbbreviation: Boolean = false,
)

@Serializable
internal class FoodDto(
    val name: String? = null,
    val pluralName: String? = null,
)

@Serializable
internal class IngredientDto(
    val quantity: Double? = null,
    val unit: UnitDto? = null,
    val food: FoodDto? = null,
    val note: String? = null,
    val display: String? = null,
    val title: String? = null,
)

@Serializable
internal class StepDto(
    val title: String? = null,
    val summary: String? = null,
    val text: String? = null,
)

@Serializable
internal class NoteDto(
    val title: String? = null,
    val text: String? = null,
)

/** `RecipeSummary`: una fila de la lista. */
@Serializable
internal class RecipeSummaryDto(
    val id: String? = null,
    val slug: String? = null,
    val name: String? = null,
    val description: String? = null,
    // El esquema lo declara sin tipo (`{}` o null). Solo se mira si es un
    // texto no vacío; su valor nunca se usa como URL.
    val image: JsonElement? = null,
    val recipeServings: Double? = null,
    val rating: Double? = null,
    val totalTime: String? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val performTime: String? = null,
    val totalTimeSeconds: Int? = null,
    val prepTimeSeconds: Int? = null,
    val performTimeSeconds: Int? = null,
    val recipeCategory: List<LabelDto?>? = null,
    val tags: List<LabelDto?>? = null,
)

/** `PaginationBase_RecipeSummary_`. `next` y `previous` no se declaran: no se siguen. */
@Serializable
internal class RecipePageDto(
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val total: Long? = null,
    val items: List<RecipeSummaryDto?>? = null,
)

/** `Recipe-Output`. */
@Serializable
internal class RecipeDto(
    val id: String? = null,
    val slug: String? = null,
    val name: String? = null,
    val description: String? = null,
    val image: JsonElement? = null,
    val recipeServings: Double? = null,
    val recipeYield: String? = null,
    val rating: Double? = null,
    @SerialName("orgURL") val orgUrl: String? = null,
    val totalTime: String? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val performTime: String? = null,
    val totalTimeSeconds: Int? = null,
    val prepTimeSeconds: Int? = null,
    val performTimeSeconds: Int? = null,
    val recipeCategory: List<LabelDto?>? = null,
    val tags: List<LabelDto?>? = null,
    val recipeIngredient: List<IngredientDto?>? = null,
    val recipeInstructions: List<StepDto?>? = null,
    val notes: List<NoteDto?>? = null,
)
