package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import io.github.garfieldrockero.gastronomicon.core.network.HttpRequest

/*
 * Modelos de lectura de recetas.
 *
 * Todo texto que contienen ya ha pasado por PlainTextSanitizer o
 * HtmlSanitizer (ver `client/RecipeMapper`), así que la interfaz puede
 * pintarlo tal cual. No son @Serializable a propósito: el JSON del servidor se
 * lee en DTO propios del cliente y aquí solo llega lo ya saneado y acotado.
 */

/** Etiqueta o categoría. [slug] queda vacío si el del servidor no pasa la lista blanca. */
data class Label(val name: String, val slug: String)

/** Tiempos tal como los escribe la receta (texto libre) y en segundos cuando el servidor los da. */
data class RecipeTimes(
    val total: String = "",
    val prep: String = "",
    val cook: String = "",
    val perform: String = "",
    val totalSeconds: Int? = null,
    val prepSeconds: Int? = null,
    val performSeconds: Int? = null,
)

/**
 * Línea de ingredientes.
 *
 * [display] es el texto que compone el servidor con la cantidad original: al
 * escalar raciones no sirve, y la interfaz compone la línea con [quantity],
 * [unitSingular]/[unitPlural], [food] y [note] (ver `ServingScaler`). [title]
 * es el encabezado de una sección de ingredientes.
 */
data class Ingredient(
    val title: String = "",
    /** Null si no hay cantidad; 0 significa «sin cantidad» en Mealie y no se escala. */
    val quantity: Double? = null,
    val unitSingular: String = "",
    val unitPlural: String = "",
    val foodSingular: String = "",
    val foodPlural: String = "",
    val note: String = "",
    val display: String = "",
)

/** Paso de la receta; [text] es HTML saneado. */
data class Step(
    val title: String = "",
    val summary: String = "",
    val text: SanitizedHtml = SanitizedHtml.EMPTY,
)

/** Nota de la receta; [text] es HTML saneado. */
data class RecipeNote(
    val title: String = "",
    val text: SanitizedHtml = SanitizedHtml.EMPTY,
)

/** Fila de la lista de recetas (`RecipeSummary` de Mealie). */
data class RecipeSummary(
    /** UUID en minúsculas, o null. Es el que forma la ruta de la imagen. */
    val id: String?,
    val slug: String,
    val name: String,
    val description: String,
    val hasImage: Boolean,
    val servings: Double?,
    val rating: Double?,
    val times: RecipeTimes,
    val categories: List<Label>,
    val tags: List<Label>,
) {
    /** Ruta de la imagen, o null si la receta no tiene o no se puede formar. */
    fun imagePath(size: ImageSize = ImageSize.Min): String? =
        if (hasImage) RecipeImage.path(id, size) else null

    fun imageRequest(size: ImageSize = ImageSize.Min): HttpRequest? =
        if (hasImage) RecipeImage.request(id, size) else null
}

/** Receta completa (`Recipe-Output` de Mealie), solo lectura. */
data class Recipe(
    val id: String?,
    val slug: String,
    val name: String,
    val description: String,
    val hasImage: Boolean,
    /** Raciones originales; null si el servidor da 0 o nada (no se puede escalar). */
    val servings: Double?,
    val yieldText: String,
    val rating: Double?,
    /** Origen de la receta: solo `http`/`https` válidos, o null. */
    val sourceUrl: String?,
    val times: RecipeTimes,
    val categories: List<Label>,
    val tags: List<Label>,
    val ingredients: List<Ingredient>,
    val steps: List<Step>,
    val notes: List<RecipeNote>,
) {
    fun imagePath(size: ImageSize = ImageSize.Min): String? =
        if (hasImage) RecipeImage.path(id, size) else null

    fun imageRequest(size: ImageSize = ImageSize.Min): HttpRequest? =
        if (hasImage) RecipeImage.request(id, size) else null
}
