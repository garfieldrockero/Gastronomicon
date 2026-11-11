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
    fun `la etiqueta de un dia suelto lleva el año cuando no es el actual`() {
        assertEquals("miércoles 11 de noviembre", MealPlanWeekView.dayLabel(today, today))
        assertEquals(
            "viernes 1 de enero de 2027",
            MealPlanWeekView.dayLabel(LocalDate.of(2027, 1, 1), today),
        )
        // Dentro del mismo año, el año sobra: la etiqueta no se alarga.
        assertEquals(
            "jueves 17 de diciembre",
            MealPlanWeekView.dayLabel(LocalDate.of(2026, 12, 17), today),
        )
    }

    @Test
    fun `contains dice si un dia cae en la semana`() {
        val view = MealPlanWeekView.of(monday, emptyList(), today)

        assertTrue(view.contains(monday))
        assertTrue(view.contains(view.weekEnd))
        assertFalse(view.contains(monday.minusDays(1)))
        assertFalse(view.contains(view.weekEnd.plusDays(1)))
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

    // --- Secciones de un día -------------------------------------------------

    private fun sectionsOf(vararg meals: PlannedMeal): List<MealPlanSectionView> =
        MealPlanWeekView.of(monday, meals.toList(), today).days[0].sections

    @Test
    fun `un dia sin comidas no tiene secciones`() {
        val day = MealPlanWeekView.of(monday, emptyList(), today).days[0]

        assertTrue(day.meals.isEmpty())
        assertTrue(day.sections.isEmpty())
    }

    @Test
    fun `un solo tipo de comida deja una sola seccion`() {
        val sections = sectionsOf(
            meal(id = 2, date = monday, type = MealType.LUNCH),
            meal(id = 1, date = monday, type = MealType.LUNCH),
        )

        val section = sections.single()
        assertEquals("Comida", section.title)
        assertFalse(section.isOther)
        assertEquals(listOf(1L, 2L), section.meals.map { it.id })
    }

    @Test
    fun `las cuatro secciones principales van en orden de reloj aunque lleguen desordenadas`() {
        val sections = sectionsOf(
            meal(id = 4, date = monday, type = MealType.DINNER),
            meal(id = 3, date = monday, type = MealType.SNACK),
            meal(id = 2, date = monday, type = MealType.LUNCH),
            meal(id = 1, date = monday, type = MealType.BREAKFAST),
        )

        assertEquals(listOf("Desayuno", "Comida", "Aperitivo", "Cena"), sections.map { it.title })
        assertEquals(listOf(false, false, false, false), sections.map { it.isOther })
        assertEquals(listOf(1L, 2L, 3L, 4L), sections.map { it.meals.single().id })
    }

    @Test
    fun `guarnicion postre y desconocida van juntas en una sola seccion Otras`() {
        val sections = sectionsOf(
            meal(id = 10, date = monday, type = MealType.UNKNOWN),
            meal(id = 9, date = monday, type = MealType.DESSERT),
            meal(id = 7, date = monday, type = MealType.SIDE),
            meal(id = 1, date = monday, type = MealType.LUNCH),
        )

        assertEquals(listOf("Comida", MealPlanWeekView.OTHER_SECTION_LABEL), sections.map { it.title })
        assertFalse(sections[0].isOther)

        val other = sections[1]
        assertTrue(other.isOther)
        // Por tipo (guarnición, postre, desconocida) y dentro del tipo por id.
        assertEquals(listOf(7L, 9L, 10L), other.meals.map { it.id })
        assertEquals(
            listOf(MealType.SIDE, MealType.DESSERT, MealType.UNKNOWN),
            other.meals.map { it.mealType },
        )
        // El día sigue teniendo las cuatro comidas: `meals` no se toca.
        assertEquals(4, sections.sumOf { it.meals.size })
    }

    @Test
    fun `un dia solo con comidas de Otras no inventa secciones principales`() {
        val sections = sectionsOf(
            meal(id = 5, date = monday, type = MealType.DRINK),
            meal(id = 2, date = monday, type = MealType.SIDE),
        )

        val section = sections.single()
        assertEquals(MealPlanWeekView.OTHER_SECTION_LABEL, section.title)
        assertTrue(section.isOther)
        assertEquals(listOf(2L, 5L), section.meals.map { it.id })
    }

    @Test
    fun `dentro de una seccion manda el id y no el orden de llegada`() {
        val sections = sectionsOf(
            meal(id = 30, date = monday, type = MealType.SIDE),
            meal(id = 10, date = monday, type = MealType.SIDE),
            meal(id = 20, date = monday, type = MealType.SIDE),
            meal(id = 300, date = monday, type = MealType.BREAKFAST),
            meal(id = 100, date = monday, type = MealType.BREAKFAST),
        )

        assertEquals(listOf("Desayuno", MealPlanWeekView.OTHER_SECTION_LABEL), sections.map { it.title })
        assertEquals(listOf(100L, 300L), sections[0].meals.map { it.id })
        assertEquals(listOf(10L, 20L, 30L), sections[1].meals.map { it.id })
    }

    @Test
    fun `el tope de comidas por dia no rompe el agrupado`() {
        val breakfasts = (1L..30L).map { meal(id = it, date = monday, type = MealType.BREAKFAST) }
        val dinners = (31L..60L).map { meal(id = it, date = monday, type = MealType.DINNER) }

        val day = MealPlanWeekView.of(monday, breakfasts + dinners, today).days[0]

        assertEquals(50, day.meals.size)
        assertEquals(listOf("Desayuno", "Cena"), day.sections.map { it.title })
        assertEquals(listOf(30, 20), day.sections.map { it.meals.size })
        assertEquals(day.meals.size, day.sections.sumOf { it.meals.size })
        assertEquals((1L..30L).toList(), day.sections[0].meals.map { it.id })
        assertEquals((31L..50L).toList(), day.sections[1].meals.map { it.id })
    }

    @Test
    fun `el texto de las secciones no lleva lo que vino del servidor`() {
        val sections = sectionsOf(
            meal(
                id = 1,
                date = monday,
                type = MealType.LUNCH,
                title = "Título secreto",
                note = "Nota privada",
                recipeId = MealPlanFixtures.RECIPE,
                recipeName = "Receta confidencial",
            ),
            meal(id = 2, date = monday, type = MealType.DESSERT, title = "Postre secreto"),
        )

        for (section in sections) {
            val text = section.toString()
            assertFalse(text.contains("Título secreto"))
            assertFalse(text.contains("Nota privada"))
            assertFalse(text.contains("Receta confidencial"))
            assertFalse(text.contains("Postre secreto"))
        }
        assertEquals(
            "MealPlanSectionView(title=Comida, isOther=false, meals=1)",
            sections[0].toString(),
        )
        assertEquals(
            "MealPlanSectionView(title=Otras, isOther=true, meals=1)",
            sections[1].toString(),
        )
    }
}
