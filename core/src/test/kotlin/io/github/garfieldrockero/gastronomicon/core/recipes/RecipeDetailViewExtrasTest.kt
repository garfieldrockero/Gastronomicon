package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ingredientes por partes, estrellas, enlace https y tiempos del detalle.
 * Va aparte de `RecipeDetailViewTest` y también protege el saneado de textos y
 * URLs que llegan del servidor.
 */
class RecipeDetailViewExtrasTest {

    private fun recipe(
        ingredients: List<Ingredient> = emptyList(),
        times: RecipeTimes = RecipeTimes(),
        sourceUrl: String? = null,
        rating: Double? = null,
        servings: Double? = 4.0,
    ) = Recipe(
        id = null, slug = "tortilla", name = "Tortilla", description = "", hasImage = false, servings = servings,
        yieldText = "", rating = rating, sourceUrl = sourceUrl, times = times, categories = emptyList(),
        tags = emptyList(), ingredients = ingredients, steps = emptyList(), notes = emptyList(),
    )

    private fun view(r: Recipe, servings: Double? = null) = RecipeDetailView.from(r, servings)

    // -------------------------------------------------------------- ingredientes

    @Test
    fun `la cantidad y la descripcion van por separado y juntas dan el texto`() {
        val oil = Ingredient(
            quantity = 1.5, unitSingular = "cucharada", unitPlural = "cucharadas",
            foodSingular = "aceite", foodPlural = "aceite", note = "de oliva",
        )
        val line = view(recipe(listOf(oil))).ingredients.single()
        assertEquals("1 1/2", line.quantityText)
        assertEquals("cucharadas aceite de oliva", line.description)
        assertEquals("1 1/2 cucharadas aceite de oliva", line.text)
    }

    @Test
    fun `escalar cambia la cantidad y la descripcion sigue al plural`() {
        val egg = Ingredient(quantity = 1.0, foodSingular = "huevo", foodPlural = "huevos")
        val r = recipe(listOf(egg), servings = 2.0)
        val one = view(r).ingredients.single()
        assertEquals("1", one.quantityText)
        assertEquals("huevo", one.description)
        val six = view(r, 6.0).ingredients.single()
        assertEquals("3", six.quantityText)
        assertEquals("huevos", six.description)
    }

    @Test
    fun `sin cantidad la descripcion queda sola y la cantidad vacia`() {
        val salt = Ingredient(quantity = 0.0, foodSingular = "sal", note = "al gusto")
        val line = view(recipe(listOf(salt))).ingredients.single()
        assertEquals("", line.quantityText)
        assertEquals("sal al gusto", line.description)
        assertEquals("sal al gusto", line.text)
    }

    @Test
    fun `el texto del servidor sin partes va en la descripcion y no en la cantidad`() {
        val free = Ingredient(quantity = null, display = "Una pizca de sal")
        val line = view(recipe(listOf(free))).ingredients.single()
        assertEquals("", line.quantityText)
        assertEquals("Una pizca de sal", line.description)
    }

    @Test
    fun `solo cantidad escalada sin partes deja la cantidad sola`() {
        val bare = Ingredient(quantity = 2.0, display = "2")
        val line = view(recipe(listOf(bare)), 8.0).ingredients.single()
        assertEquals("4", line.quantityText)
        assertEquals("", line.description)
        assertEquals("4", line.text)
    }

    @Test
    fun `un encabezado de seccion solo tiene titulo y esta vacio de texto`() {
        val header = Ingredient(title = "Para la masa")
        val line = view(recipe(listOf(header))).ingredients.single()
        assertEquals("Para la masa", line.title)
        assertTrue(line.isHeaderOnly)
        assertEquals("", line.text)
    }

    @Test
    fun `los textos hostiles de un ingrediente salen limpios en las dos partes`() {
        val bad = Ingredient(
            quantity = 1.0, unitSingular = "taza\u0007‮", foodSingular = "harina\u0000", note = "x".repeat(5_000),
        )
        val line = view(recipe(listOf(bad))).ingredients.single()
        val all = line.quantityText + line.description
        assertFalse(all.any { Character.isISOControl(it) || it == '‮' })
        assertTrue(line.description.length <= 800)
    }

    // ------------------------------------------------------------------ estrellas

    @Test
    fun `las estrellas se redondean y se acotan de 0 a 5`() {
        fun stars(value: Double?) = view(recipe(rating = value)).ratingStars
        assertEquals(0, stars(0.0))
        assertEquals(3, stars(3.4))
        assertEquals(4, stars(3.5))
        assertEquals(5, stars(4.6))
        assertEquals(5, stars(99.0))
        assertEquals(0, stars(-3.0))
        assertEquals(5, stars(Double.MAX_VALUE))
    }

    @Test
    fun `sin valoracion o con un valor no numerico no hay estrellas`() {
        assertNull(view(recipe(rating = null)).ratingStars)
        assertNull(view(recipe(rating = Double.NaN)).ratingStars)
        assertNull(view(recipe(rating = Double.POSITIVE_INFINITY)).ratingStars)
        assertNull(view(recipe(rating = Double.NEGATIVE_INFINITY)).ratingStars)
        assertNull(RatingStars.of(Double.NaN))
    }

    // -------------------------------------------------------------- enlace https

    @Test
    fun `isHttps distingue https de http sin importar mayusculas`() {
        assertTrue(view(recipe(sourceUrl = "https://ejemplo.org/a")).sourceLink!!.isHttps)
        assertTrue(view(recipe(sourceUrl = "HTTPS://Ejemplo.ORG/a")).sourceLink!!.isHttps)
        assertFalse(view(recipe(sourceUrl = "http://ejemplo.org/a")).sourceLink!!.isHttps)
        assertFalse(view(recipe(sourceUrl = "HTTP://ejemplo.org/a")).sourceLink!!.isHttps)
    }

    @Test
    fun `isHttps no se deja engañar por un https dentro de otra parte de la URL`() {
        assertFalse(SourceLink("http://ejemplo.org/https://x", "ejemplo.org").isHttps)
        assertFalse(SourceLink("javascript:https://x", "x").isHttps)
        assertFalse(SourceLink(" https://ejemplo.org", "ejemplo.org").isHttps)
        assertFalse(SourceLink("", "").isHttps)
    }

    @Test
    fun `un origen peligroso nunca llega a tener isHttps`() {
        for (url in listOf("javascript:alert(1)", "data:text/html,x", "https://user:pw@ejemplo.org/", "ftp://ejemplo.org/")) {
            assertNull(url, view(recipe(sourceUrl = url)).sourceLink)
        }
    }

    // ------------------------------------------------------------------- tiempos

    private fun lines(times: RecipeTimes) = view(recipe(times = times)).times

    @Test
    fun `performTime se muestra como coccion y gana a cookTime`() {
        assertEquals(
            listOf(TimeLine(TimeKind.Cook, "35 min")),
            lines(RecipeTimes(perform = "35 min", cook = "otra cosa")),
        )
    }

    @Test
    fun `sin performTime la coccion sale de cookTime`() {
        assertEquals(listOf(TimeLine(TimeKind.Cook, "una hora")), lines(RecipeTimes(cook = "una hora")))
    }

    @Test
    fun `los segundos de performTime se usan si no hay texto y antes que cookTime`() {
        assertEquals(
            listOf(TimeLine(TimeKind.Cook, "1 h")),
            lines(RecipeTimes(performSeconds = 3600, cook = "texto de cookTime")),
        )
    }

    @Test
    fun `cada tiempo sale solo si existe y en orden total preparacion coccion`() {
        assertEquals(
            listOf(
                TimeLine(TimeKind.Total, "1 h 30 min"),
                TimeLine(TimeKind.Prep, "20 min"),
                TimeLine(TimeKind.Cook, "1 h 10 min"),
            ),
            lines(RecipeTimes(totalSeconds = 5400, prepSeconds = 1200, performSeconds = 4200)),
        )
        assertEquals(listOf(TimeLine(TimeKind.Prep, "20 min")), lines(RecipeTimes(prepSeconds = 1200)))
        assertTrue(lines(RecipeTimes(totalSeconds = 0, prepSeconds = -5, performSeconds = 0)).isEmpty())
    }

    @Test
    fun `no se repite un tiempo igual al total cuando es el unico otro`() {
        assertEquals(
            listOf(TimeLine(TimeKind.Total, "30 min")),
            lines(RecipeTimes(totalSeconds = 1800, performSeconds = 1800)),
        )
        assertEquals(
            listOf(TimeLine(TimeKind.Total, "30 MIN")),
            lines(RecipeTimes(total = "30 MIN", prep = " 30 min ")),
        )
    }

    @Test
    fun `con preparacion y coccion iguales entre si pero distintas del total se muestran las dos`() {
        assertEquals(
            listOf(
                TimeLine(TimeKind.Total, "1 h"),
                TimeLine(TimeKind.Prep, "30 min"),
                TimeLine(TimeKind.Cook, "30 min"),
            ),
            lines(RecipeTimes(totalSeconds = 3600, prepSeconds = 1800, performSeconds = 1800)),
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `los textos de tiempo hostiles salen limpios y acotados y nunca emiten Perform`() {
        val hostile = "10\u0007 min‮" + "y".repeat(1_000)
        val result = lines(RecipeTimes(total = hostile, prep = hostile, perform = hostile + "z"))
        assertFalse(result.isEmpty())
        for (line in result) {
            assertTrue(line.text.length <= 100)
            assertFalse(line.text.any { Character.isISOControl(it) || it == '‮' })
            assertTrue(line.kind != TimeKind.Perform)
        }
    }

    @Test
    fun `segundos absurdos no se muestran`() {
        assertTrue(lines(RecipeTimes(totalSeconds = Int.MAX_VALUE, performSeconds = Int.MAX_VALUE)).isEmpty())
    }
}
