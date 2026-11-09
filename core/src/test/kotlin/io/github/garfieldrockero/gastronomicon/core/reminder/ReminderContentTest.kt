package io.github.garfieldrockero.gastronomicon.core.reminder

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.offline.CachedDay
import io.github.garfieldrockero.gastronomicon.core.offline.CachedMeal
import io.github.garfieldrockero.gastronomicon.core.offline.CachedMealPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Composición del aviso: orden, recorte, saneado y los casos en que no se avisa.
 * Ni un nombre de nota puede acabar aquí: las entradas sin receta ni título ni
 * siquiera llegan (las descarta la caché).
 */
class ReminderContentTest {

    private val monday = LocalDate.of(2026, 11, 9)

    @Test
    fun `una sola comida compone una linea con su tipo`() {
        val message = ReminderContent.of(listOf(ReminderMeal(MealType.LUNCH, "Lentejas")))!!

        assertEquals(ReminderContent.TITLE, message.title)
        assertEquals("Comida: Lentejas", message.body)
        assertEquals(1, message.mealCount)
        assertEquals(0, message.hiddenCount)
        assertFalse(message.hasHidden)
    }

    @Test
    fun `las comidas se ordenan por tipo y no por nombre`() {
        val message = ReminderContent.of(
            listOf(
                ReminderMeal(MealType.DINNER, "Sopa"),
                ReminderMeal(MealType.BREAKFAST, "Huevos"),
                ReminderMeal(MealType.LUNCH, "Lentejas"),
            ),
        )!!

        assertEquals("Desayuno: Huevos · Comida: Lentejas · Cena: Sopa", message.body)
        assertEquals(3, message.mealCount)
    }

    @Test
    fun `con mas de cinco comidas se recorta y se dice cuantas quedan`() {
        val meals = (1..8).map { ReminderMeal(MealType.LUNCH, "Plato $it") }

        val message = ReminderContent.of(meals)!!

        assertTrue(message.body.contains("Comida: Plato 5"))
        assertFalse(message.body.contains("Plato 6"))
        assertTrue(message.body.endsWith("y 3 más"))
        assertEquals(8, message.mealCount)
        assertEquals(3, message.hiddenCount)
        assertTrue(message.hasHidden)
    }

    @Test
    fun `con exactamente cinco comidas no hay resumen`() {
        val meals = (1..5).map { ReminderMeal(MealType.LUNCH, "Plato $it") }

        val message = ReminderContent.of(meals)!!

        assertEquals(0, message.hiddenCount)
        assertFalse(message.body.contains("más"))
    }

    @Test
    fun `los caracteres de control y bidi desaparecen`() {
        val message = ReminderContent.of(
            listOf(ReminderMeal(MealType.LUNCH, "Lentejas\u202E\u0007finito")),
        )!!

        assertEquals("Comida: Lentejasfinito", message.body)
    }

    @Test
    fun `un nombre larguisimo se acota a una linea de 120`() {
        val message = ReminderContent.of(
            listOf(ReminderMeal(MealType.LUNCH, "x".repeat(5_000))),
        )!!

        assertTrue(message.body.codePointCount(0, message.body.length) <= ReminderContent.MAX_LINE_CODE_POINTS)
        assertTrue(message.body.endsWith("…"))
        assertTrue(message.body.startsWith("Comida: x"))
    }

    @Test
    fun `una receta borrada se anuncia con el texto generico`() {
        val message = ReminderContent.of(
            listOf(ReminderMeal(MealType.DINNER, "Receta no disponible")),
        )!!

        assertEquals("Cena: Receta no disponible", message.body)
    }

    @Test
    fun `una entrada sin nombre no compone aviso`() {
        assertNull(ReminderContent.of(listOf(ReminderMeal(MealType.LUNCH, ""))))
        assertNull(ReminderContent.of(listOf(ReminderMeal(MealType.LUNCH, "   "))))
        assertNull(ReminderContent.of(listOf(ReminderMeal(MealType.LUNCH, "\u202E\u0007"))))
    }

    @Test
    fun `un dia sin comidas no genera aviso`() {
        assertNull(ReminderContent.of(emptyList()))
    }

    @Test
    fun `las entradas vacias no cuentan como comidas`() {
        val message = ReminderContent.of(
            listOf(
                ReminderMeal(MealType.BREAKFAST, ""),
                ReminderMeal(MealType.LUNCH, "Lentejas"),
            ),
        )!!

        assertEquals(1, message.mealCount)
        assertEquals("Comida: Lentejas", message.body)
    }

    @Test
    fun `la copia del plan se convierte solo para el dia pedido`() {
        val plan = CachedMealPlan(
            savedAtMillis = 1_000L,
            days = listOf(
                CachedDay(
                    date = monday,
                    meals = listOf(
                        CachedMeal(MealType.DINNER, "Tortilla"),
                        CachedMeal(MealType.LUNCH, "Lentejas"),
                    ),
                ),
            ),
        )

        val message = ReminderContent.of(plan, monday)!!
        assertEquals("Comida: Lentejas · Cena: Tortilla", message.body)
        assertNull(ReminderContent.of(plan, monday.plusDays(1)))
    }

    @Test
    fun `el resultado no arrastra nombres en toString`() {
        val message = ReminderContent.of(
            listOf(ReminderMeal(MealType.LUNCH, "Lentejas de la abuela")),
        )!!

        assertFalse(message.toString().contains("Lentejas"))
        assertFalse(message.toString().contains("Comida:"))
    }
}
