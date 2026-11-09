package io.github.garfieldrockero.gastronomicon.core.mealplan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * DTO de lectura del planificador: copia tolerante de lo que manda Mealie
 * (esquemas CreatePlanEntry, UpdatePlanEntry y ReadPlanEntry de
 * docs/openapi.json).
 *
 * Reglas, como en el resto del núcleo:
 * - Todo campo tiene valor por defecto o es anulable, para que «ignoreUnknownKeys»
 *   y «coerceInputValues» de CoreJson absorban los cambios entre versiones.
 * - `entryType` se lee como texto y no como enum: así un valor nuevo del
 *   servidor cae en MealType.UNKNOWN en vez de convertirse en el valor por
 *   defecto del enum (que sería inventar un tipo de comida).
 * - Solo se declaran los campos que la app usa.
 * - Nada de aquí llega a la interfaz: MealPlanMapper lo sanea y lo acota.
 * - Son internos y sin toString útil a propósito.
 */

/** Resumen de receta incrustado en una entrada (`ReadPlanEntry.recipe`). */
@Serializable
internal class PlanRecipeDto(
    val id: String? = null,
    val slug: String? = null,
    val name: String? = null,
)

/** `ReadPlanEntry`. */
@Serializable
internal class PlanEntryDto(
    val id: Long? = null,
    val date: String? = null,
    val entryType: String? = null,
    val title: String? = null,
    val text: String? = null,
    val recipeId: String? = null,
    val recipeScale: Double? = null,
    // Los exige UpdatePlanEntry para mover o editar la entrada.
    val groupId: String? = null,
    val userId: String? = null,
    val recipe: PlanRecipeDto? = null,
)

/**
 * `PlanEntryPagination`. `next` y `previous` no se declaran: son URLs ajenas y
 * no se siguen; la paginación se calcula con `page`, `per_page` y `total`.
 */
@Serializable
internal class PlanEntryPageDto(
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val total: Long? = null,
    val items: List<PlanEntryDto?>? = null,
)
