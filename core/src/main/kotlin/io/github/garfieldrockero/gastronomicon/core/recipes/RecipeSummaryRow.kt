package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * Fila de la lista tal como se pinta: la receta y los datos ya calculados
 * (tiempo total en texto y estrellas), para que la interfaz no formatee nada.
 *
 * Los textos de [summary] ya vienen saneados de `RecipeMapper`; el del tiempo
 * se vuelve a sanear aquí.
 */
data class RecipeSummaryRow(
    val summary: RecipeSummary,
    /** «45 min», «1 h 30 min» o el texto de la receta; vacío si no hay tiempo total. */
    val totalTimeText: String,
    /** Estrellas llenas de 0 a 5, o null si no hay valoración (ver [RatingStars.of]). */
    val ratingStars: Int?,
) {
    val slug: String get() = summary.slug
    val name: String get() = summary.name

    // Sin el contenido: son datos del servidor y una fila puede acabar en un log.
    override fun toString(): String = "RecipeSummaryRow(ratingStars=$ratingStars)"

    companion object {
        fun from(summary: RecipeSummary): RecipeSummaryRow = RecipeSummaryRow(
            summary = summary,
            totalTimeText = RecipeTimeText.total(summary.times),
            ratingStars = RatingStars.of(summary.rating),
        )
    }
}
