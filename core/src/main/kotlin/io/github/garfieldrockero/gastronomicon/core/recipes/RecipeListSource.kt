package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * De dónde salen las páginas de recetas. Lo implementa `MealieClient`; las
 * pruebas del estado de la lista ponen una versión falsa y no necesitan red.
 *
 * Es síncrono y lanza `NetworkError` si falla, igual que el transporte.
 */
fun interface RecipeListSource {
    fun listRecipes(query: RecipeListQuery): Page<RecipeSummary>
}
