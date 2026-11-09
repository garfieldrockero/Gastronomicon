package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * De dónde sale una receta completa. Lo implementa `MealieClient`; las
 * pruebas del controlador de detalle ponen una versión falsa.
 *
 * Es síncrono y lanza `NetworkError` si falla; un slug que no pasa la lista
 * blanca lanza `IllegalArgumentException` sin enviar nada.
 */
fun interface RecipeDetailSource {
    fun getRecipe(slug: String): Recipe
}
