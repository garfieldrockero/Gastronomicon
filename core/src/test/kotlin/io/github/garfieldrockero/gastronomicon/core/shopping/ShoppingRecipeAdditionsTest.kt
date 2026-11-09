package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDraftValidator
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.mealplan.PlannedMeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pruebas de la lógica pura de «ingredientes de la semana»: solo entran las
 * entradas con receta, la misma receta se suma (no se duplica), el orden es el
 * de la primera aparición y el tope se aplica sin inventar recetas.
 */
class ShoppingRecipeAdditionsTest {

    private val recetaA = "33333333-3333-4333-8333-333333333333"
    private val recetaB = "44444444-4444-4444-8444-444444444444"
    private val recetaC = "55555555-5555-4555-8555-555555555555"
    private val fecha = LocalDate.of(2026, 11, 9)

    private fun meal(
        id: Long,
        recipeId: String?,
        scale: Double = 1.0,
        type: MealType = MealType.LUNCH,
    ): PlannedMeal = PlannedMeal(
        id = id,
        date = fecha,
        mealType = type,
        title = "",
        note = "",
        recipeId = recipeId,
        recipeName = null,
        recipeSlug = null,
        recipeScale = scale,
        groupId = null,
        userId = null,
    )

    private fun addition(recipeId: String, scale: Double) = RecipeAddition(recipeId, scale)

    // ------------------------------------------------------------------ plan

    @Test
    fun `la misma receta se suma una sola vez y en orden de aparicion`() {
        val plan = RecipeAdditions.plan(
            listOf(
                meal(1, recetaB, scale = 1.0),
                meal(2, recetaA, scale = 2.0),
                meal(3, recetaB, scale = 1.5),
                meal(4, recetaA, scale = 0.5),
            ),
        )

        assertEquals(
            listOf(addition(recetaB, 2.5), addition(recetaA, 2.5)),
            plan.additions,
        )
        assertEquals(0, plan.skippedRecipes)
        assertFalse(plan.truncated)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `dos ejecuciones con las mismas entradas dan lo mismo`() {
        val entries = listOf(
            meal(1, recetaC),
            meal(2, recetaA, scale = 3.0),
            meal(3, recetaC, scale = 2.0),
        )

        assertEquals(RecipeAdditions.plan(entries), RecipeAdditions.plan(entries))
    }

    @Test
    fun `las entradas sin receta o con un id que no es uuid se ignoran`() {
        val plan = RecipeAdditions.plan(
            listOf(
                meal(1, null),
                meal(2, ""),
                meal(3, "../../fuera"),
                meal(4, recetaA),
            ),
        )

        assertEquals(listOf(addition(recetaA, 1.0)), plan.additions)
    }

    @Test
    fun `una escala rota no se convierte en escala uno`() {
        val plan = RecipeAdditions.plan(
            listOf(
                meal(1, recetaA, scale = 0.0),
                meal(2, recetaB, scale = -2.0),
                meal(3, recetaC, scale = Double.NaN),
                meal(4, recetaA, scale = MealPlanDraftValidator.MAX_SCALE + 1.0),
            ),
        )

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.skippedRecipes)
        assertFalse(plan.truncated)
    }

    @Test
    fun `la suma justo en el tope se acepta`() {
        val plan = RecipeAdditions.plan(
            listOf(
                meal(1, recetaA, scale = 50.0),
                meal(2, recetaA, scale = 50.0),
            ),
        )

        assertEquals(listOf(addition(recetaA, MealPlanDraftValidator.MAX_SCALE)), plan.additions)
    }

    @Test
    fun `una suma que se sale del rango descarta la receta y lo cuenta`() {
        val plan = RecipeAdditions.plan(
            listOf(
                meal(1, recetaA, scale = 60.0),
                meal(2, recetaB, scale = 1.0),
                meal(3, recetaA, scale = 60.0),
            ),
        )

        assertEquals(listOf(addition(recetaB, 1.0)), plan.additions)
        assertEquals(1, plan.skippedRecipes)
        assertFalse(plan.truncated)
    }

    @Test
    fun `una receta descartada no vuelve a aparecer mas tarde`() {
        val plan = RecipeAdditions.plan(
            listOf(
                meal(1, recetaA, scale = 60.0),
                meal(2, recetaA, scale = 60.0),
                meal(3, recetaA, scale = 1.0),
            ),
        )

        assertTrue(plan.isEmpty)
        assertEquals(1, plan.skippedRecipes)
    }

    @Test
    fun `el tope recorta y lo dice`() {
        val plan = RecipeAdditions.plan(
            listOf(
                meal(1, recetaA),
                meal(2, recetaB),
                meal(3, recetaC),
            ),
            maxRecipes = 2,
        )

        assertEquals(listOf(addition(recetaA, 1.0), addition(recetaB, 1.0)), plan.additions)
        assertEquals(1, plan.skippedRecipes)
        assertTrue(plan.truncated)
    }

    @Test
    fun `el tope de la llamada no puede pasar del tope del nucleo`() {
        val entries = (1..RecipeAdditions.MAX_RECIPES_PER_OPERATION + 5).map { index ->
            val hex = index.toString(16).padStart(8, '0')
            meal(index.toLong(), "$hex-0000-4000-8000-000000000000")
        }

        val plan = RecipeAdditions.plan(entries, maxRecipes = Int.MAX_VALUE)

        assertEquals(RecipeAdditions.MAX_RECIPES_PER_OPERATION, plan.additions.size)
        assertEquals(5, plan.skippedRecipes)
        assertTrue(plan.truncated)
    }

    @Test
    fun `un tope menor que uno se queda en uno`() {
        val plan = RecipeAdditions.plan(listOf(meal(1, recetaA), meal(2, recetaB)), maxRecipes = 0)

        assertEquals(listOf(addition(recetaA, 1.0)), plan.additions)
        assertTrue(plan.truncated)
    }

    @Test
    fun `fromWeek devuelve solo la lista de recetas`() {
        val additions = RecipeAdditions.fromWeek(
            listOf(meal(1, recetaA, scale = 2.0), meal(2, null), meal(3, recetaB)),
        )

        assertEquals(listOf(addition(recetaA, 2.0), addition(recetaB, 1.0)), additions)
    }

    @Test
    fun `una semana vacia no da recetas`() {
        val plan = RecipeAdditions.plan(emptyList())

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.skippedRecipes)
        assertFalse(plan.truncated)
    }

    // ----------------------------------------------------------------- check

    @Test
    fun `una tanda vacia se rechaza`() {
        assertEquals(RecipeAdditionProblem.Empty, RecipeAdditions.check(emptyList()))
    }

    @Test
    fun `mas recetas que el tope se rechazan antes que sus datos`() {
        // Ids distintos (aunque no sean UUID) para que el tope cuente entradas
        // de verdad: los repetidos se fusionan antes.
        val tooMany = (1..RecipeAdditions.MAX_RECIPES_PER_OPERATION + 1)
            .map { addition("no-es-uuid-$it", 1.0) }

        assertEquals(RecipeAdditionProblem.TooManyRecipes, RecipeAdditions.check(tooMany))
    }

    @Test
    fun `los ids repetidos se fusionan sumando escalas y en orden`() {
        val merged = RecipeAdditions.merge(
            listOf(
                addition(recetaB, 1.0),
                addition(recetaA, 2.0),
                addition(recetaB, 1.5),
                addition(recetaA, 0.5),
            ),
        )

        assertEquals(listOf(addition(recetaB, 2.5), addition(recetaA, 2.5)), merged)
        assertNull(RecipeAdditions.check(merged))
    }

    @Test
    fun `el mismo id en mayusculas cuenta como una sola receta`() {
        val merged = RecipeAdditions.merge(
            listOf(addition(recetaA, 1.0), addition(recetaA.uppercase(), 2.0)),
        )

        assertEquals(listOf(addition(recetaA, 3.0)), merged)
    }

    @Test
    fun `los duplicados no hacen saltar el tope`() {
        val repeated = List(RecipeAdditions.MAX_RECIPES_PER_OPERATION + 5) { addition(recetaA, 1.0) }

        assertNull(RecipeAdditions.check(repeated))
        assertEquals(1, RecipeAdditions.merge(repeated).size)
    }

    @Test
    fun `una suma de duplicados fuera de rango se rechaza`() {
        val tooMuch = listOf(
            addition(recetaA, MealPlanDraftValidator.MAX_SCALE),
            addition(recetaA, MealPlanDraftValidator.MAX_SCALE),
        )

        assertEquals(RecipeAdditionProblem.InvalidScale, RecipeAdditions.check(tooMuch))
    }

    @Test
    fun `un id que no es uuid se rechaza`() {
        assertEquals(
            RecipeAdditionProblem.InvalidRecipeId,
            RecipeAdditions.check(listOf(addition("../../fuera", 1.0))),
        )
        assertEquals(
            RecipeAdditionProblem.InvalidRecipeId,
            RecipeAdditions.check(listOf(addition(recetaA, 1.0), addition("", 1.0))),
        )
    }

    @Test
    fun `una escala fuera del rango se rechaza`() {
        val invalidScales = listOf(
            0.0,
            -1.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            MealPlanDraftValidator.MAX_SCALE + 0.001,
        )
        for (scale in invalidScales) {
            assertEquals(
                "escala $scale",
                RecipeAdditionProblem.InvalidScale,
                RecipeAdditions.check(listOf(addition(recetaA, scale))),
            )
        }
    }

    @Test
    fun `una tanda valida no tiene problema`() {
        assertNull(
            RecipeAdditions.check(
                listOf(addition(recetaA, 1.0), addition(recetaB, MealPlanDraftValidator.MAX_SCALE)),
            ),
        )
    }
}
