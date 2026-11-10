package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.graphics.Bitmap
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.html.HtmlBlock
import io.github.garfieldrockero.gastronomicon.core.html.InlineSpan
import io.github.garfieldrockero.gastronomicon.core.html.ListItem
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftOrganizer
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftStep
import io.github.garfieldrockero.gastronomicon.core.recipes.Ingredient
import io.github.garfieldrockero.gastronomicon.core.recipes.Label
import io.github.garfieldrockero.gastronomicon.core.recipes.Organizer
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailView
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft
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

    /**
     * La receta de ejemplo con un paso que traía una imagen o un vídeo: el saneador
     * los tira por seguridad y marca el paso ([SanitizedHtml.omittedMedia]) para
     * que el detalle pueda decirlo. Sirve para previsualizar ese aviso.
     */
    val recipeWithOmittedMedia: Recipe = recipe.copy(
        steps = recipe.steps.mapIndexed { index, step ->
            if (index == 1) {
                step.copy(
                    text = SanitizedHtml(
                        blocks = step.text.blocks,
                        truncated = step.text.truncated,
                        omittedMedia = true,
                    ),
                )
            } else {
                step
            }
        },
    )

    /** Borrador de ejemplo con ids válidos: nada de servidores, URL ni tokens reales. */
    val draft: RecipeDraft = RecipeDraft(
        name = "Arroz con verduras",
        description = "Un arroz de domingo, con lo que haya en la nevera.",
        servings = 4.0,
        totalTime = "50 min",
        prepTime = "15 min",
        cookTime = "35 min",
        ingredients = listOf(
            DraftIngredient(
                quantity = 300.0,
                unit = "gramos",
                food = "arroz redondo",
                title = "Para el arroz",
                sourceId = "11111111-1111-4111-8111-111111111111",
            ),
            DraftIngredient(
                quantity = 2.0,
                unit = "cucharadas",
                food = "aceite de oliva",
                sourceId = "22222222-2222-4222-8222-222222222222",
            ),
            DraftIngredient(
                quantity = 1.5,
                food = "pimiento rojo",
                sourceId = "33333333-3333-4333-8333-333333333333",
            ),
        ),
        steps = listOf(
            DraftStep(
                title = "Sofrito",
                text = "Calienta el aceite y sofríe el pimiento.",
                sourceId = "44444444-4444-4444-8444-444444444444",
            ),
            DraftStep(
                text = "Añade el arroz y el caldo.",
                sourceId = "55555555-5555-4555-8555-555555555555",
            ),
        ),
        tags = listOf(
            DraftOrganizer(
                id = "66666666-6666-4666-8666-666666666666",
                name = "Vegetariano",
                slug = "vegetariano",
            ),
        ),
        categories = listOf(
            DraftOrganizer(
                id = "77777777-7777-4777-8777-777777777777",
                name = "Arroces",
                slug = "arroces",
            ),
        ),
    )

    val organizerTags: List<Organizer> = listOf(
        Organizer(
            id = "66666666-6666-4666-8666-666666666666",
            name = "Vegetariano",
            slug = "vegetariano",
        ),
        Organizer(
            id = "88888888-8888-4888-8888-888888888888",
            name = "Fácil",
            slug = "facil",
        ),
    )

    val organizerCategories: List<Organizer> = listOf(
        Organizer(
            id = "77777777-7777-4777-8777-777777777777",
            name = "Arroces",
            slug = "arroces",
        ),
        Organizer(
            id = "99999999-9999-4999-8999-999999999999",
            name = "Comidas",
            slug = "comidas",
        ),
    )

    fun editorState(
        mode: EditorMode = EditorMode.Edit("arroz-con-verduras"),
        errors: List<DraftError> = emptyList(),
        serverDetails: List<ValidationDetail> = emptyList(),
        saving: Boolean = false,
        showDiscardConfirm: Boolean = false,
        createdIncomplete: Boolean = false,
        photo: PhotoState = PhotoState(),
        maybeCreated: Boolean = false,
        showDuplicateConfirm: Boolean = false,
        catalogsTruncated: Boolean = false,
        recoveredDraft: RecipeDraft? = null,
        recoveredAtText: String = "",
        showDraftConfirm: Boolean = false,
        showDraftDiscardConfirm: Boolean = false,
        draftInvalid: Boolean = false,
    ): RecipeEditorState.Editing = RecipeEditorState.Editing(
        mode = mode,
        form = RecipeForm.from(draft),
        baseline = draft,
        errors = errors,
        serverDetails = serverDetails,
        catalogs = OrganizerCatalog(
            tags = organizerTags.map { it.toDraft() },
            categories = organizerCategories.map { it.toDraft() },
            tagsTruncated = catalogsTruncated,
            categoriesTruncated = catalogsTruncated,
            loading = false,
            problem = null,
        ),
        saving = saving,
        saveProblem = null,
        rejected = false,
        createdIncomplete = createdIncomplete,
        showDiscardConfirm = showDiscardConfirm,
        dirty = true,
        photo = photo,
        maybeCreated = maybeCreated,
        showDuplicateConfirm = showDuplicateConfirm,
        recoveredDraft = recoveredDraft,
        recoveredAtText = recoveredAtText,
        showDraftConfirm = showDraftConfirm,
        showDraftDiscardConfirm = showDraftDiscardConfirm,
        draftInvalid = draftInvalid,
    )

    /**
     * Bitmap de prueba para las previews de la foto: un color plano inventado. No es
     * ninguna foto real, no viene de ningún fichero y no lleva ningún dato de nadie.
     */
    fun photoPreviewBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(0xC8, 0xA2, 0x5A))
        return bitmap
    }
}
