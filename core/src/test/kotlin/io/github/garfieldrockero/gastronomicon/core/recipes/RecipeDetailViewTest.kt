package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.html.HtmlSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeDetailViewTest {

    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun recipe(
        servings: Double? = 4.0,
        ingredients: List<Ingredient> = emptyList(),
        times: RecipeTimes = RecipeTimes(),
        sourceUrl: String? = null,
        name: String = "Tortilla",
        description: String = "",
        hasImage: Boolean = true,
        recipeId: String? = id,
        steps: List<Step> = emptyList(),
        notes: List<RecipeNote> = emptyList(),
    ) = Recipe(
        id = recipeId, slug = "tortilla", name = name, description = description, hasImage = hasImage,
        servings = servings, yieldText = "", rating = null, sourceUrl = sourceUrl, times = times,
        categories = emptyList(), tags = emptyList(), ingredients = ingredients, steps = steps, notes = notes,
    )

    private fun oil(quantity: Double? = 2.0) = Ingredient(
        quantity = quantity, unitSingular = "cucharada", unitPlural = "cucharadas",
        foodSingular = "aceite", foodPlural = "aceite", note = "de oliva", display = "2 cucharadas aceite de oliva",
    )

    private fun lines(view: RecipeDetailView) = view.ingredients.map { it.text }

    // ---------------------------------------------------------------- escalado

    @Test
    fun `sin elegir raciones salen las originales y las cantidades tal cual`() {
        val view = RecipeDetailView.from(recipe(ingredients = listOf(oil())))
        assertEquals(4.0, view.selectedServings!!, 0.0)
        assertEquals("4", view.servingsText)
        assertEquals(listOf("2 cucharadas aceite de oliva"), lines(view))
    }

    @Test
    fun `se escalan las cantidades y el plural sigue a la cantidad`() {
        val base = recipe(ingredients = listOf(oil()))
        assertEquals(listOf("4 cucharadas aceite de oliva"), lines(RecipeDetailView.from(base, 8.0)))
        assertEquals(listOf("1 cucharada aceite de oliva"), lines(RecipeDetailView.from(base, 2.0)))
        assertEquals(listOf("1/2 cucharada aceite de oliva"), lines(RecipeDetailView.from(base, 1.0)))
        assertEquals(listOf("3 cucharadas aceite de oliva"), lines(RecipeDetailView.from(base, 6.0)))
    }

    @Test
    fun `las fracciones y los decimales se formatean`() {
        val gramos = Ingredient(quantity = 300.0, unitSingular = "g", unitPlural = "g", foodSingular = "patata", foodPlural = "patatas")
        val view = RecipeDetailView.from(recipe(ingredients = listOf(oil(1.0), gramos)), 6.0)
        assertEquals(listOf("1 1/2 cucharadas aceite de oliva", "450 g patatas"), lines(view))
    }

    @Test
    fun `sin cantidad se usa el texto del servidor y no se escala`() {
        val sal = Ingredient(quantity = 0.0, note = "sal al gusto", display = "sal al gusto")
        val soloDisplay = Ingredient(quantity = null, display = "un poco de perejil")
        val view = RecipeDetailView.from(recipe(ingredients = listOf(sal, soloDisplay)), 12.0)
        assertEquals(listOf("sal al gusto", "un poco de perejil"), lines(view))
    }

    @Test
    fun `una cantidad absurda se muestra sin numero`() {
        val absurd = Ingredient(quantity = 1e12, unitSingular = "g", unitPlural = "g", foodSingular = "harina", foodPlural = "harina")
        val negative = Ingredient(quantity = -3.0, foodSingular = "sal", foodPlural = "sal")
        val nan = Ingredient(quantity = Double.NaN, foodSingular = "azucar", foodPlural = "azucar")
        val infinite = Ingredient(quantity = Double.POSITIVE_INFINITY, foodSingular = "miel", foodPlural = "miel")
        val view = RecipeDetailView.from(recipe(ingredients = listOf(absurd, negative, nan, infinite)))
        assertEquals(listOf("g harina", "sal", "azucar", "miel"), lines(view))
        for (line in lines(view)) assertFalse(line, line.any { it.isDigit() })
    }

    @Test
    fun `un escalado que se desborda se muestra sin numero`() {
        val big = Ingredient(quantity = 900_000.0, unitSingular = "g", unitPlural = "g", foodSingular = "arroz", foodPlural = "arroz")
        val view = RecipeDetailView.from(recipe(servings = 1.0, ingredients = listOf(big)), 1000.0)
        assertEquals(listOf("g arroz"), lines(view))
    }

    @Test
    fun `sin raciones en la receta no se escala`() {
        val view = RecipeDetailView.from(recipe(servings = null, ingredients = listOf(oil())), 10.0)
        assertFalse(view.canScale)
        assertNull(view.selectedServings)
        assertNull(view.originalServings)
        assertEquals("", view.servingsText)
        assertEquals(listOf("2 cucharadas aceite de oliva"), lines(view))
        assertSame(view, view.withServings(3.0))
        assertSame(view, view.moreServings())
        assertSame(view, view.fewerServings())
        assertFalse(view.canIncreaseServings)
        assertFalse(view.canDecreaseServings)
    }

    @Test
    fun `las raciones elegidas se acotan y lo invalido vuelve a las originales`() {
        val base = recipe(servings = 4.0)
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, -3.0, null)) {
            assertEquals(bad.toString(), 4.0, RecipeDetailView.from(base, bad).selectedServings!!, 0.0)
        }
        assertEquals(RecipeDetailView.MAX_SERVINGS, RecipeDetailView.from(base, 1e9).selectedServings!!, 0.0)
        assertEquals(RecipeDetailView.MIN_SERVINGS, RecipeDetailView.from(base, 0.2).selectedServings!!, 0.0)
    }

    @Test
    fun `mas y menos raciones respetan los limites`() {
        var view = RecipeDetailView.from(recipe(servings = 2.0))
        assertTrue(view.canDecreaseServings)
        view = view.fewerServings()
        assertEquals(1.0, view.selectedServings!!, 0.0)
        assertFalse(view.canDecreaseServings)
        assertEquals(1.0, view.fewerServings().selectedServings!!, 0.0)
        view = view.moreServings().moreServings()
        assertEquals(3.0, view.selectedServings!!, 0.0)

        val top = RecipeDetailView.from(recipe(servings = 4.0), RecipeDetailView.MAX_SERVINGS)
        assertFalse(top.canIncreaseServings)
        assertEquals(RecipeDetailView.MAX_SERVINGS, top.moreServings().selectedServings!!, 0.0)
    }

    @Test
    fun `cambiar las raciones recalcula desde las originales sin acumular errores`() {
        val base = RecipeDetailView.from(recipe(ingredients = listOf(oil(1.0))))
        val back = base.withServings(7.0).withServings(4.0)
        assertEquals(lines(base), lines(back))
        assertEquals(4.0, back.originalServings!!, 0.0)
    }

    // ------------------------------------------------------------- ingredientes

    @Test
    fun `los encabezados de seccion se conservan y las lineas vacias se descartan`() {
        val view = RecipeDetailView.from(
            recipe(
                ingredients = listOf(
                    Ingredient(title = "Para la masa"),
                    Ingredient(quantity = 200.0, unitSingular = "g", unitPlural = "g", foodSingular = "harina", foodPlural = "harina"),
                    Ingredient(quantity = 3.0),
                    Ingredient(title = "Relleno", foodSingular = "queso", foodPlural = "queso"),
                ),
            ),
        )
        assertEquals(3, view.ingredients.size)
        assertEquals("Para la masa", view.ingredients[0].title)
        assertTrue(view.ingredients[0].isHeaderOnly)
        assertEquals("200 g harina", view.ingredients[1].text)
        assertFalse(view.ingredients[1].isHeaderOnly)
        assertEquals("Relleno", view.ingredients[2].title)
        assertEquals("queso", view.ingredients[2].text)
    }

    @Test
    fun `sin unidad ni alimento con cantidad al escalar no se enseña el numero viejo`() {
        // Solo «display» (con la cantidad original dentro) y una cantidad: al escalar, ese texto mentiría.
        val raw = Ingredient(quantity = 2.0, display = "2 huevos")
        assertEquals(listOf("2 huevos"), lines(RecipeDetailView.from(recipe(ingredients = listOf(raw)))))
        assertEquals(listOf("4"), lines(RecipeDetailView.from(recipe(ingredients = listOf(raw)), 8.0)))
    }

    // ------------------------------------------------------------------ tiempos

    @Test
    fun `los tiempos prefieren el texto de la receta y si no usan los segundos`() {
        val view = RecipeDetailView.from(
            recipe(
                times = RecipeTimes(
                    total = "", prep = "unos 20 min", cook = "una hora", perform = "",
                    totalSeconds = 5400, prepSeconds = 600, performSeconds = 0,
                ),
            ),
        )
        assertEquals(
            listOf(
                TimeLine(TimeKind.Total, "1 h 30 min"),
                TimeLine(TimeKind.Prep, "unos 20 min"),
                TimeLine(TimeKind.Cook, "una hora"),
            ),
            view.times,
        )
    }

    @Test
    fun `sin tiempos no hay lineas`() {
        assertTrue(RecipeDetailView.from(recipe()).times.isEmpty())
    }

    @Test
    fun `DurationFormatter da formas cortas y omite lo absurdo`() {
        assertEquals("", DurationFormatter.format(0))
        assertEquals("", DurationFormatter.format(-5))
        assertEquals("", DurationFormatter.format(null))
        assertEquals("< 1 min", DurationFormatter.format(30))
        assertEquals("1 min", DurationFormatter.format(60))
        assertEquals("1 min", DurationFormatter.format(89))
        assertEquals("2 min", DurationFormatter.format(90))
        assertEquals("45 min", DurationFormatter.format(2700))
        assertEquals("1 h", DurationFormatter.format(3600))
        assertEquals("1 h 30 min", DurationFormatter.format(5400))
        assertEquals("25 h", DurationFormatter.format(90_000))
        assertEquals("", DurationFormatter.format(Int.MAX_VALUE))
        assertEquals("", DurationFormatter.format(3_600 * 1_000))
    }

    // ---------------------------------------------------------- enlace de origen

    @Test
    fun `el enlace de origen solo es un dato con la URL y su servidor`() {
        val view = RecipeDetailView.from(recipe(sourceUrl = "HTTPS://Ejemplo.ORG/tortilla?a=b"))
        assertEquals("HTTPS://Ejemplo.ORG/tortilla?a=b", view.sourceLink!!.url)
        assertEquals("ejemplo.org", view.sourceLink!!.host)
    }

    @Test
    fun `un origen peligroso no llega al detalle aunque la receta lo traiga`() {
        val bad = listOf(
            "javascript:alert(1)", "data:text/html,<script>1</script>", "file:///etc/passwd", "intent://x#Intent;end",
            "//ejemplo.org/x", "https://user:pw@ejemplo.org/", "https://ejemplo.org/con espacio", "ftp://ejemplo.org/",
            "https://ejemplo.org/‮cod.exe", "", "   ", "https://",
        )
        for (url in bad) {
            assertNull(url, RecipeDetailView.from(recipe(sourceUrl = url)).sourceLink)
        }
        assertNull(RecipeDetailView.from(recipe(sourceUrl = null)).sourceLink)
    }

    // ------------------------------------------------------------------ imagen

    @Test
    fun `la imagen solo se ofrece con id valido y receta con imagen`() {
        assertEquals(id, RecipeDetailView.from(recipe()).imageRecipeId)
        assertEquals(id, RecipeDetailView.from(recipe(recipeId = id.uppercase())).imageRecipeId)
        assertNull(RecipeDetailView.from(recipe(hasImage = false)).imageRecipeId)
        assertNull(RecipeDetailView.from(recipe(recipeId = null)).imageRecipeId)
        assertNull(RecipeDetailView.from(recipe(recipeId = "../x")).imageRecipeId)
    }

    // ------------------------------------------------------ pasos, notas y texto

    @Test
    fun `los pasos van numerados y conservan el HTML ya saneado`() {
        val steps = listOf(
            Step(title = "Patatas", text = HtmlSanitizer.sanitize("<p>Pela <b>todo</b></p>")),
            Step(summary = "Cuajar", text = HtmlSanitizer.sanitize("<script>x</script><p>Dale la vuelta</p>")),
        )
        val view = RecipeDetailView.from(recipe(steps = steps))
        assertEquals(listOf(1, 2), view.steps.map { it.number })
        assertEquals("Patatas", view.steps[0].title)
        assertEquals("Pela todo", view.steps[0].body.toPlainText())
        assertEquals("Cuajar", view.steps[1].summary)
        assertEquals("Dale la vuelta", view.steps[1].body.toPlainText())
    }

    @Test
    fun `las notas se conservan`() {
        val note = RecipeNote("Truco", HtmlSanitizer.sanitize("<p>Reposar</p>"))
        val view = RecipeDetailView.from(recipe(notes = listOf(note, RecipeNote("", SanitizedHtml.EMPTY))))
        assertEquals(1, view.notes.size)
        assertEquals("Truco", view.notes[0].title)
        assertEquals("Reposar", view.notes[0].text.toPlainText())
    }

    @Test
    fun `los textos hostiles salen sin controles ni caracteres bidireccionales`() {
        val rlo = "‮"
        val view = RecipeDetailView.from(
            recipe(
                name = "To${rlo}rtilla\u0000 de\n\npatatas​",
                description = "Linea 1\r\n\r\n\r\n\r\nLinea 2 <script>alert(1)</script> $rlo",
                ingredients = listOf(
                    Ingredient(quantity = 1.0, unitSingular = "g$rlo", unitPlural = "g", foodSingular = "pa\u0007ta⁦ta", foodPlural = "x", note = "n\u0085ota"),
                ),
                times = RecipeTimes(prep = "10$rlo min"),
            ),
        )
        val everything = listOf(view.name, view.description, view.ingredients.single().text, view.times.single().text)
        for (text in everything) {
            for (ch in text) {
                assertFalse("bidireccional en «$text»", ch.code in 0x202A..0x202E || ch.code in 0x2066..0x2069)
                assertFalse("control en «$text»", Character.isISOControl(ch) && ch != '\n')
                assertFalse("ancho cero en «$text»", ch == '​')
            }
        }
        assertEquals("Tortilla de patatas", view.name)
        // El HTML de un texto plano no se interpreta: queda como caracteres, sin nada que ejecutar.
        assertTrue(view.description.contains("<script>"))
        assertFalse(view.description.contains("\n\n\n"))
    }

    @Test
    fun `los textos largos se acotan`() {
        val long = "a".repeat(100_000)
        val view = RecipeDetailView.from(
            recipe(name = long, description = long, ingredients = listOf(Ingredient(note = long, display = long))),
        )
        assertTrue(view.name.length <= 300)
        assertTrue(view.description.length <= 5_000)
        assertTrue(view.ingredients.single().text.length <= 600)
    }

    @Test
    fun `la valoracion se acota y lo no finito se descarta`() {
        fun rated(value: Double?) = RecipeDetailView.from(recipe().copy(rating = value)).rating
        assertEquals(5.0, rated(9.0)!!, 0.0)
        assertEquals(0.0, rated(-1.0)!!, 0.0)
        assertNull(rated(Double.NaN))
        assertNull(rated(null))
        assertEquals(3.5, rated(3.5)!!, 0.0)
    }

    @Test
    fun `una receta vacia da un detalle vacio y no falla`() {
        val view = RecipeDetailView.from(recipe(servings = null, name = "", hasImage = false, recipeId = null))
        assertEquals("", view.name)
        assertTrue(view.ingredients.isEmpty() && view.steps.isEmpty() && view.notes.isEmpty() && view.times.isEmpty())
        assertNull(view.sourceLink)
        assertNull(view.imageRecipeId)
    }

    @Test
    fun `el detalle no imprime sus textos`() {
        val view = RecipeDetailView.from(recipe(name = "Secreto-123", description = "Privado"))
        assertFalse(view.toString().contains("Secreto-123"))
        assertFalse(view.toString().contains("Privado"))
    }
}
