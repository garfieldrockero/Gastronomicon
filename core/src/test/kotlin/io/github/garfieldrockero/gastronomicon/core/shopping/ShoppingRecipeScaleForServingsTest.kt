package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDraftValidator
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pruebas de la conversión «raciones que quiero» → «escala de la receta», la que
 * usa el detalle al añadir ingredientes a la lista de la compra. Sin raciones
 * originales no hay proporción; los números dudosos no se mandan.
 */
class ShoppingRecipeScaleForServingsTest {

    private val recetaA = "66666666-6666-4666-8666-666666666666"
    private val delta = 1e-9

    @Test
    fun `las raciones que pide la persona se convierten en escala`() {
        assertEquals(2.0, RecipeAdditions.scaleForServings(4.0, 8.0) ?: 0.0, delta)
        assertEquals(0.5, RecipeAdditions.scaleForServings(4.0, 2.0) ?: 0.0, delta)
        // Las mismas raciones que dice la receta no cambian nada.
        assertEquals(1.0, RecipeAdditions.scaleForServings(4.0, 4.0) ?: 0.0, delta)
    }

    @Test
    fun `una receta con raciones fraccionarias tambien se convierte`() {
        assertEquals(2.0, RecipeAdditions.scaleForServings(1.5, 3.0) ?: 0.0, delta)
        val tercero = RecipeAdditions.scaleForServings(3.0, 4.0)
        assertNotNull(tercero)
        assertEquals(4.0 / 3.0, tercero ?: 0.0, delta)
    }

    @Test
    fun `sin raciones originales no hay escala que calcular`() {
        assertNull(RecipeAdditions.scaleForServings(null, 8.0))
        // Una receta con «0 raciones» o un valor absurdo es como no tenerlas.
        assertNull(RecipeAdditions.scaleForServings(0.0, 8.0))
        assertNull(RecipeAdditions.scaleForServings(-4.0, 8.0))
        assertNull(RecipeAdditions.scaleForServings(Double.NaN, 8.0))
        assertNull(RecipeAdditions.scaleForServings(Double.POSITIVE_INFINITY, 8.0))
    }

    @Test
    fun `unas raciones que no son un numero no dan escala`() {
        val wantedInvalidos = listOf(
            null,
            0.0,
            -2.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            // Por encima del tope del selector del detalle.
            RecipeDetailView.MAX_SERVINGS + 1.0,
        )
        for (wanted in wantedInvalidos) {
            assertNull("raciones $wanted", RecipeAdditions.scaleForServings(4.0, wanted))
        }
    }

    @Test
    fun `las raciones justo en el tope del detalle se aceptan`() {
        // 1000 raciones de una receta de 10 son escala 100: justo el tope del núcleo.
        assertEquals(
            RecipeAdditions.MAX_SCALE,
            RecipeAdditions.scaleForServings(10.0, RecipeDetailView.MAX_SERVINGS) ?: 0.0,
            delta,
        )
        // Las mismas 1000 raciones de una receta de 1 ración serían escala 1000: no cabe.
        assertNull(RecipeAdditions.scaleForServings(1.0, RecipeDetailView.MAX_SERVINGS))
    }

    @Test
    fun `una escala que se sale del tope del nucleo no se devuelve`() {
        // 1 ración original y 200 pedidas serían escala 200, fuera de MAX_SCALE.
        assertNull(RecipeAdditions.scaleForServings(1.0, 200.0))
        // Justo en el tope sí vale.
        assertEquals(
            MealPlanDraftValidator.MAX_SCALE,
            RecipeAdditions.scaleForServings(1.0, MealPlanDraftValidator.MAX_SCALE) ?: 0.0,
            delta,
        )
    }

    @Test
    fun `la escala que sale pasa la validacion de la tanda`() {
        val scale = RecipeAdditions.scaleForServings(4.0, 8.0)
        assertNotNull(scale)
        assertNull(
            RecipeAdditions.check(listOf(RecipeAddition(recetaA, scale ?: 0.0))),
        )
    }
}
