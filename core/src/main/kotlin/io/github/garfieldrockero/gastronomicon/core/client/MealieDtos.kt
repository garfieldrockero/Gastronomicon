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

/**
 * `UserOut` de `GET /api/users/self`, reducido al `id`.
 *
 * No se declaran correo, grupo, hogar ni la lista de tokens: lo que no se lee
 * no se puede filtrar. `ignoreUnknownKeys` hace que el resto se ignore solo.
 */
@Serializable
internal class UserSelfDto(
    val id: String? = null,
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

/**
 * `GroupSummary` de `GET /api/groups/self`, reducido a lo que la app pregunta:
 * si el grupo tiene IA configurada y con qué proveedores. Lo demás (nombre,
 * preferencias, proveedores con sus claves) no se declara, así que ni se lee.
 */
@Serializable
internal class GroupSummaryDto(
    val aiProviderSettings: AiProviderSettingsDto? = null,
)

/**
 * `AIProviderSettingsOut` de `GET /api/groups/self`, solo con las tres banderas
 * que la app pregunta: IA general, proveedor de imagen y proveedor de audio.
 * Los identificadores de los proveedores y sus `providers` no se declaran, así
 * que ni se leen.
 *
 * Los nombres son los del contrato de Mealie v3.29.0
 * (`docs/openapi.json` → `AIProviderSettingsOut`): `aiEnabled`,
 * `imageProviderEnabled` y `audioProviderEnabled`, las tres `readOnly`.
 *
 * Los tres son anulables a propósito: `coerceInputValues` convertiría un `null`
 * en el valor por defecto de un `Boolean` no anulable, y aquí un «no viene»
 * tiene que poder distinguirse de un `false`: con `aiEnabled` se decide si la
 * función se ofrece (ver `MealieClient.aiAvailable`).
 */
@Serializable
internal class AiProviderSettingsDto(
    val aiEnabled: Boolean? = null,
    val imageProviderEnabled: Boolean? = null,
    val audioProviderEnabled: Boolean? = null,
)
