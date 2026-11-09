package io.github.garfieldrockero.gastronomicon.ui.recipes

import io.github.garfieldrockero.gastronomicon.core.html.HtmlBlock
import io.github.garfieldrockero.gastronomicon.core.html.InlineSpan
import io.github.garfieldrockero.gastronomicon.core.html.ListItem
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import io.github.garfieldrockero.gastronomicon.core.recipes.Ingredient
import io.github.garfieldrockero.gastronomicon.core.recipes.Label
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailView
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeNote
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummary
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeTimes
import io.github.garfieldrockero.gastronomicon.core.recipes.Step

/**
 * Datos inventados para las previews. Nada de servidores, URL ni tokens reales:
 * el enlace de origen es un dominio de ejemplo reservado.
 */
internal object RecipePreviewData {

    private fun summary(
        slug: String,
        name: String,
        description: String,
        total: String = "",
        rating: Double? = null,
    ) = RecipeSummary(
        id = null,
        slug = slug,
        name = name,
        description = description,
        hasImage = false,
        servings = 4.0,
        rating = rating,
        times = RecipeTimes(total = total),
        categories = emptyList(),
        tags = emptyList(),
    )

    val summaries: List<RecipeSummary> = listOf(
        summary("arroz-con-verduras", "Arroz con verduras", "Un arroz de domingo, con lo que haya en la nevera.", "50 min", 4.0),
        summary("tortilla-de-patatas", "Tortilla de patatas", "Jugosa por dentro, con cebolla.", "35 min", 5.0),
        summary("crema-de-calabaza", "Crema de calabaza y zanahoria con un nombre bastante largo para probar", ""),
        summary("lentejas-de-la-abuela", "Lentejas de la abuela", "Con chorizo y un buen sofrito. Mejor del día anterior.", "1 h 30 min", 3.0),
    )

    private fun text(vararg spans: InlineSpan) = SanitizedHtml(listOf(HtmlBlock.Paragraph(spans.toList())))

    private fun plain(value: String) = InlineSpan.Text(value)

    val recipe: Recipe = Recipe(
        id = null,
        slug = "arroz-con-verduras",
        name = "Arroz con verduras",
        description = "Un arroz de domingo, con lo que haya en la nevera.\nSe come a cucharadas y repite todo el mundo.",
        hasImage = false,
        servings = 4.0,
        yieldText = "4 raciones",
        rating = 4.0,
        sourceUrl = "https://recetas.example.com/arroz-con-verduras",
        times = RecipeTimes(prep = "15 min", cook = "35 min", total = "50 min"),
        categories = listOf(Label("Arroces", "arroces"), Label("Comidas", "comidas")),
        tags = listOf(Label("Vegetariano", "vegetariano"), Label("Fácil", "facil")),
        ingredients = listOf(
            Ingredient(title = "Para el arroz"),
            Ingredient(
                quantity = 300.0,
                unitSingular = "gramo",
                unitPlural = "gramos",
                foodSingular = "arroz redondo",
                foodPlural = "arroz redondo",
            ),
            Ingredient(
                quantity = 2.0,
                unitSingular = "cucharada",
                unitPlural = "cucharadas",
                foodSingular = "aceite de oliva",
                foodPlural = "aceite de oliva",
            ),
            Ingredient(quantity = 1.5, foodSingular = "pimiento rojo", foodPlural = "pimientos rojos"),
            Ingredient(title = "Para el caldo"),
            Ingredient(
                quantity = 0.75,
                unitSingular = "litro",
                unitPlural = "litros",
                foodSingular = "caldo de verduras",
                foodPlural = "caldo de verduras",
            ),
            Ingredient(foodSingular = "sal", foodPlural = "sal", note = "al gusto"),
        ),
        steps = listOf(
            Step(
                title = "Sofrito",
                text = text(
                    plain("Calienta el aceite en la paella y sofríe el pimiento a fuego medio durante "),
                    InlineSpan.Text("diez minutos", bold = true),
                    plain(", hasta que esté blando."),
                ),
            ),
            Step(
                summary = "Añade el arroz y el caldo.",
                text = SanitizedHtml(
                    listOf(
                        HtmlBlock.Paragraph(listOf(plain("Reparte el arroz y vierte el caldo caliente:"))),
                        HtmlBlock.UnorderedList(
                            listOf(
                                ListItem(listOf(plain("No remuevas más a partir de ahora."))),
                                ListItem(listOf(InlineSpan.Text("Fuego fuerte", italic = true), plain(" los primeros cinco minutos."))),
                                ListItem(listOf(plain("Después, fuego suave hasta que se seque."))),
                            ),
                        ),
                    ),
                ),
            ),
            Step(
                text = text(
                    plain("Apaga el fuego y deja reposar cinco minutos tapado con un paño."),
                    InlineSpan.LineBreak,
                    plain("Sirve en la misma paella."),
                ),
            ),
        ),
        notes = listOf(
            RecipeNote(title = "Para la semana", text = text(plain("Aguanta bien un día en la nevera."))),
        ),
    )

    val detail: RecipeDetailView = RecipeDetailView.from(recipe)
}
