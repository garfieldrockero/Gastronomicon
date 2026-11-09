package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage

/**
 * Identificadores de la compra.
 *
 * Mealie usa UUID en todos los ids de lista y de elemento. Se reutiliza la
 * comprobación de [RecipeImage] (forma canónica de UUID) para no tener dos
 * listas blancas distintas en el núcleo. Nada que no sea un
 * UUID llega a una ruta: así el servidor no puede recibir un id que se salga
 * del segmento, ni siquiera si alguien lo devolvió así.
 */
object ShoppingIds {

    /** El id en minúsculas si tiene forma de UUID, o null. */
    fun valid(raw: String?): String? = RecipeImage.normalizeId(raw)

    /**
     * El id validado o [IllegalArgumentException] antes de enviar nada.
     * Es el mismo criterio que `MealieClient.getRecipe` con el slug.
     */
    fun checked(raw: String?): String =
        valid(raw) ?: throw IllegalArgumentException("El identificador de la compra no es un UUID válido.")
}
