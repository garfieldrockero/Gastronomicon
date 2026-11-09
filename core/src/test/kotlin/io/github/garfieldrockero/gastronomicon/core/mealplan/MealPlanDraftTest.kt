package io.github.garfieldrockero.gastronomicon.core.mealplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Validación del borrador y reglas de fecha del planificador, sin red. */
class MealPlanDraftTest {

    private val day = LocalDate.of(2026, 11, 10)

    private fun invalidProblem(draft: MealPlanDraft): MealPlanInputProblem? =
        (MealPlanDraftValidator.validate(draft) as? MealPlanDraftCheck.Invalid)?.problem

    private fun cleaned(draft: MealPlanDraft): CleanMealPlanDraft =
        (MealPlanDraftValidator.validate(draft) as? MealPlanDraftCheck.Valid)?.draft
            ?: throw AssertionError("Se esperaba un borrador válido")

    @Test
    fun `un borrador con titulo se sanea`() {
        val result = cleaned(
            MealPlanDraft(
                date = day,
                mealType = MealType.LUNCH,
                title = "  Sopa\u202e   de  ajo ",
            ),
        )

        assertEquals("Sopa de ajo", result.title)
        assertEquals("", result.text)
        assertNull(result.recipeId)
        assertEquals(1.0, result.recipeScale, 0.0)
    }

    @Test
    fun `sin receta ni texto ni titulo se rechaza`() {
        assertEquals(
            MealPlanInputProblem.EmptyEntry,
            invalidProblem(MealPlanDraft(date = day, mealType = MealType.LUNCH)),
        )
        assertEquals(
            MealPlanInputProblem.EmptyEntry,
            invalidProblem(MealPlanDraft(date = day, mealType = MealType.LUNCH, title = "   ")),
        )
        // Un título hecho solo de controles y marcas bidi queda vacío al sanearlo.
        assertEquals(
            MealPlanInputProblem.EmptyEntry,
            invalidProblem(
                MealPlanDraft(date = day, mealType = MealType.LUNCH, title = "\u202e\u0000\u200e"),
            ),
        )
    }

    @Test
    fun `con receta y sin titulo vale y el id se normaliza`() {
        val result = cleaned(
            MealPlanDraft(
                date = day,
                mealType = MealType.DINNER,
                recipeId = MealPlanFixtures.RECIPE.uppercase(),
            ),
        )

        assertEquals(MealPlanFixtures.RECIPE, result.recipeId)
        assertEquals("", result.title)
    }

    @Test
    fun `un id de receta en blanco es sin receta`() {
        val result = cleaned(
            MealPlanDraft(date = day, mealType = MealType.DINNER, recipeId = "   ", title = "Sopa"),
        )

        assertNull(result.recipeId)
        assertEquals("Sopa", result.title)
    }

    @Test
    fun `un id de receta que no es UUID se rechaza`() {
        for (raw in listOf("no-es-uuid", "../../etc/passwd", "javascript:alert(1)")) {
            assertEquals(
                raw,
                MealPlanInputProblem.InvalidRecipeId,
                invalidProblem(
                    MealPlanDraft(date = day, mealType = MealType.DINNER, recipeId = raw, title = "Sopa"),
                ),
            )
        }
    }

    @Test
    fun `el titulo no se recorta en silencio`() {
        assertEquals(
            MealPlanInputProblem.TitleTooLong,
            invalidProblem(
                MealPlanDraft(
                    date = day,
                    mealType = MealType.LUNCH,
                    title = "a".repeat(MealPlanDraftValidator.MAX_TITLE + 1),
                ),
            ),
        )
        val justFits = cleaned(
            MealPlanDraft(
                date = day,
                mealType = MealType.LUNCH,
                title = "a".repeat(MealPlanDraftValidator.MAX_TITLE),
            ),
        )
        assertEquals(MealPlanDraftValidator.MAX_TITLE, justFits.title.length)
    }

    @Test
    fun `el texto no se recorta en silencio`() {
        assertEquals(
            MealPlanInputProblem.TextTooLong,
            invalidProblem(
                MealPlanDraft(
                    date = day,
                    mealType = MealType.LUNCH,
                    text = "a".repeat(MealPlanDraftValidator.MAX_TEXT + 1),
                ),
            ),
        )
    }

    @Test
    fun `el tipo desconocido no se puede crear`() {
        assertEquals(
            MealPlanInputProblem.InvalidMealType,
            invalidProblem(MealPlanDraft(date = day, mealType = MealType.UNKNOWN, title = "Sopa")),
        )
    }

    @Test
    fun `las raciones fuera de rango se rechazan`() {
        for (scale in listOf(0.0, -1.0, 100.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                scale.toString(),
                MealPlanInputProblem.InvalidRecipeScale,
                invalidProblem(
                    MealPlanDraft(date = day, mealType = MealType.LUNCH, title = "Sopa", recipeScale = scale),
                ),
            )
        }
        val top = cleaned(
            MealPlanDraft(
                date = day,
                mealType = MealType.LUNCH,
                title = "Sopa",
                recipeScale = MealPlanDraftValidator.MAX_SCALE,
            ),
        )
        assertEquals(MealPlanDraftValidator.MAX_SCALE, top.recipeScale, 0.0)
    }

    @Test
    fun `la fecha fuera del rango de años se rechaza`() {
        assertEquals(
            MealPlanInputProblem.InvalidDate,
            invalidProblem(MealPlanDraft(date = LocalDate.of(1899, 12, 31), mealType = MealType.LUNCH, title = "Sopa")),
        )
        assertEquals(
            MealPlanInputProblem.InvalidDate,
            invalidProblem(MealPlanDraft(date = LocalDate.of(3000, 1, 1), mealType = MealType.LUNCH, title = "Sopa")),
        )
    }

    @Test
    fun `los rangos invertidos y enormes se acotan`() {
        assertEquals(
            MealPlanInputProblem.InvalidRange,
            MealPlanDates.check(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 9)),
        )
        val start = LocalDate.of(2026, 1, 1)
        // 2026 no es bisiesto: del 1 de enero al 1 de enero siguiente son 366 días.
        assertNull(MealPlanDates.check(start, start.plusDays(365)))
        assertEquals(
            MealPlanInputProblem.RangeTooWide,
            MealPlanDates.check(start, start.plusDays(366)),
        )
        assertEquals(
            MealPlanInputProblem.InvalidDate,
            MealPlanDates.check(start, LocalDate.of(10000, 1, 1)),
        )
    }

    @Test
    fun `la semana empieza en lunes, tambien en domingo y al cruzar el año`() {
        assertEquals(LocalDate.of(2026, 11, 9), MealPlanDates.weekStart(LocalDate.of(2026, 11, 9)))
        assertEquals(LocalDate.of(2026, 11, 9), MealPlanDates.weekStart(LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2025, 12, 29), MealPlanDates.weekStart(LocalDate.of(2026, 1, 1)))
        assertEquals(LocalDate.of(2026, 2, 23), MealPlanDates.weekStart(LocalDate.of(2026, 3, 1)))
        // 2028 es bisiesto y el 29 de febrero cae en martes.
        assertEquals(LocalDate.of(2028, 2, 28), MealPlanDates.weekStart(LocalDate.of(2028, 2, 29)))
    }
}
