package io.github.garfieldrockero.gastronomicon.core.mealplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * La vista de la semana: orden de días y comidas, «hoy», semanas anterior y
 * siguiente, entradas vacías explícitas, receta borrada, cruce de año, febrero
 * bisiesto y saneado de lo que llegue sin sanear.
 */
class MealPlanWeekViewTest {

    private val monday = LocalDate.of(2026, 11, 9)
    private val today = LocalDate.of(2026, 11, 11)

    private fun meal(
        id: Long,
        date: LocalDate,
        type: MealType,
        title: String = "Sopa",
        note: String = "",
        recipeId: String? = null,
        recipeName: String? = null,
        recipeScale: Double = 1.0,
    ): PlannedMeal = MealPlanFixtures.plannedMeal(
        id = id,
        date = date,
        mealType = type,
        title = title,
        note = note,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeScale = recipeScale,
    )

    @Test
    fun `la semana va de lunes a domingo con los dias vacios explicitos`() {
        val view = MealPlanWeekView.of(today, emptyList(), today)

        assertEquals(monday, view.weekStart)
        assertEquals(LocalDate.of(2026, 11, 15), view.weekEnd)
        assertEquals(7, view.days.size)
        assertEquals(
            listOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY,
                DayOfWeek.SUNDAY,
            ),
            view.days.map { it.dayOfWeek },
        )
        assertEquals("Semana del 9 al 15 de noviembre de 2026", view.title)
        assertEquals("lunes 9 de noviembre", view.days[0].dayLabel)
        assertEquals(LocalDate.of(2026, 11, 2), view.previousWeekStart)
        assertEquals(LocalDate.of(2026, 11, 16), view.nextWeekStart)
        assertTrue(view.isCurrentWeek)
        assertFalse(view.hasMeals)
        assertEquals(0, view.mealCount)
        for (day in view.days) {
            assertTrue(day.isEmpty)
            assertNull(day.meals.firstOrNull())
            assertEquals(MealPlanWeekView.EMPTY_DAY_LABEL, day.emptyLabel)
        }
    }

    @Test
    fun `las comidas de un dia se ordenan por tipo y luego por id`() {
        val view = MealPlanWeekView.of(
            monday,
            listOf(
                meal(id = 2, date = monday, type = MealType.DINNER),
                meal(id = 5, date = monday, type = MealType.BREAKFAST),
                meal(id = 3, date = monday, type = MealType.LUNCH),
                meal(id = 1, date = monday, type = MealType.BREAKFAST),
            ),
            today,
        )

        assertEquals(listOf(1L, 5L, 3L, 2L), view.days[0].meals.map { it.id })
        assertFalse(view.days[0].isEmpty)
        assertNull(view.days[0].emptyLabel)
        assertEquals(4, view.mealCount)
    }

    @Test
    fun `hoy se marca una sola vez y solo en su semana`() {
        val view = MealPlanWeekView.of(monday, emptyList(), today)
        assertEquals(1, view.days.count { it.isToday })
        assertTrue(view.days[2].isToday)

        val next = MealPlanWeekView.of(view.nextWeekStart, emptyList(), today)
        assertFalse(next.isCurrentWeek)
        assertEquals(0, next.days.count { it.isToday })
    }

    @Test
    fun `las entradas fuera de la semana y los repetidos se descartan`() {
        val view = MealPlanWeekView.of(
            monday,
            listOf(
                meal(id = 1, date = monday, type = MealType.LUNCH),
                meal(id = 1, date = monday, type = MealType.LUNCH),
                meal(id = 2, date = LocalDate.of(2026, 11, 16), type = MealType.LUNCH),
            ),
            today,
        )

        assertEquals(1, view.mealCount)
    }

    @Test
    fun `una receta borrada se dice explicitamente`() {
        val view = MealPlanWeekView.of(
            monday,
            listOf(
                meal(
                    id = 1,
                    date = monday,
                    type = MealType.LUNCH,
                    title = "",
                    recipeId = MealPlanFixtures.RECIPE,
                    recipeName = null,
                ),
            ),
            today,
        )

        val shown = view.days[0].meals.single()
        assertEquals(MealPlanWeekView.MISSING_RECIPE_LABEL, shown.title)
        assertTrue(shown.recipeMissing)
        assertTrue(shown.hasRecipe)
        assertFalse(shown.isBlank)
        assertEquals("", shown.note)
    }

    @Test
    fun `una entrada sin nada dice que no tiene titulo`() {
        val view = MealPlanWeekView.of(
            monday,
            listOf(meal(id = 1, date = monday, type = MealType.SNACK, title = "", note = "")),
            today,
        )

        val shown = view.days[0].meals.single()
        assertEquals(MealPlanWeekView.UNTITLED_MEAL_LABEL, shown.title)
        assertTrue(shown.isBlank)
        assertFalse(shown.recipeMissing)
        assertFalse(shown.hasRecipe)
    }

    @Test
    fun `el nombre de la receta manda y lo escrito queda como nota`() {
        val view = MealPlanWeekView.of(
            monday,
            listOf(
                meal(
                    id = 1,
                    date = monday,
                    type = MealType.LUNCH,
                    title = "Postre",
                    note = "con nata",
                    recipeId = MealPlanFixtures.RECIPE,
                    recipeName = "Tarta de queso",
                ),
            ),
            today,
        )

        val shown = view.days[0].meals.single()
        assertEquals("Tarta de queso", shown.title)
        assertEquals("Postre · con nata", shown.note)
    }

    @Test
    fun `el texto hostil se vuelve a sanear en la vista`() {
        val view = MealPlanWeekView.of(
            monday,
            listOf(
                meal(
                    id = 1,
                    date = monday,
                    type = MealType.LUNCH,
                    title = "Sopa\u202e\u0000",
                    note = "<script>alert(1)</script>",
                ),
            ),
            today,
        )

        val shown = view.days[0].meals.single()
        assertFalse(shown.title.contains('\u202e'))
        assertFalse(shown.title.any { it.code < 0x20 })
        // Se queda como texto literal: nunca se ejecuta ni se interpreta.
        assertTrue(shown.note.contains("<script>"))
        assertFalse(shown.note.any { it.code < 0x20 })
    }

    @Test
    fun `la racion escalada se escribe con coma y sin cero de mas`() {
        fun scaleTextOf(scale: Double): String? = MealPlanWeekView.of(
            monday,
            listOf(
                meal(
                    id = 1,
                    date = monday,
                    type = MealType.LUNCH,
                    recipeId = MealPlanFixtures.RECIPE,
                    recipeName = "Sopa",
                    recipeScale = scale,
                ),
            ),
            today,
        ).days[0].meals.single().scaleText

        assertEquals("×2", scaleTextOf(2.0))
        assertEquals("×1,5", scaleTextOf(1.5))
        assertNull(scaleTextOf(1.0))
    }

    @Test
    fun `los tipos que la app no conoce van al final`() {
        val view = MealPlanWeekView.of(
            monday,
            listOf(
                meal(id = 1, date = monday, type = MealType.UNKNOWN),
                meal(id = 2, date = monday, type = MealType.DESSERT),
                meal(id = 3, date = monday, type = MealType.BREAKFAST),
            ),
            today,
        )

        assertEquals(listOf(3L, 2L, 1L), view.days[0].meals.map { it.id })
        assertEquals("Otra comida", view.days[0].meals.last().mealType.label)
    }

    @Test
    fun `una semana que cruza el año y el 29 de febrero`() {
        val newYear = MealPlanWeekView.of(LocalDate.of(2026, 1, 1), emptyList(), LocalDate.of(2026, 1, 1))

        assertEquals(LocalDate.of(2025, 12, 29), newYear.weekStart)
        assertEquals(LocalDate.of(2026, 1, 4), newYear.weekEnd)
        assertEquals("Semana del 29 de diciembre de 2025 al 4 de enero de 2026", newYear.title)

        val leap = MealPlanWeekView.of(LocalDate.of(2028, 2, 29), emptyList(), LocalDate.of(2028, 2, 29))

        assertEquals(LocalDate.of(2028, 2, 28), leap.weekStart)
        assertEquals(LocalDate.of(2028, 3, 5), leap.weekEnd)
        assertEquals(LocalDate.of(2028, 2, 29), leap.days[1].date)
        assertTrue(leap.days[1].isToday)
    }
}
