package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.html.HtmlSanitizer
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftNote
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftOrganizer
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftStep
import io.github.garfieldrockero.gastronomicon.core.recipes.Ingredient
import io.github.garfieldrockero.gastronomicon.core.recipes.Label
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeNote
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeTimes
import io.github.garfieldrockero.gastronomicon.core.recipes.Step

/** Datos de ejemplo ya saneados, como los dejaría `RecipeMapper`. */
internal object OfflineFixtures {

    const val SERVER_A = "https://mealie.example.org"
    const val SERVER_B = "https://otros.example.org"
    const val SLUG = "tortilla-de-patatas"
    const val OTRO_SLUG = "paella-valenciana"

    /** Ids de cuenta como los devuelve `GET /api/users/self` (uuid4). */
    const val USER_A = "0f8fad5b-d9cb-469f-a165-70867728950e"
    const val USER_B = "9a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d"

    const val RECIPE_ID = "11111111-2222-3333-4444-555555555555"
    const val INGREDIENT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    const val STEP_ID = "12345678-1234-1234-1234-123456789abc"
    const val NOTE_ID = "87654321-4321-4321-4321-cba987654321"
    const val TAG_ID = "abcdefab-cdef-abcd-efab-cdefabcdefab"
    const val CATEGORY_ID = "fedcbafe-dcba-fedc-bafe-dcbafedcbafe"
    const val RECIPE_NAME = "Tortilla de patatas"

    fun scopeA(): ServerScope = requireNotNull(ServerScope.of(SERVER_A, USER_A))
    fun scopeB(): ServerScope = requireNotNull(ServerScope.of(SERVER_B, USER_B))

    /** Mismo servidor que [scopeA], pero otra cuenta: no debe ver nada de la primera. */
    fun scopeAOtraCuenta(): ServerScope = requireNotNull(ServerScope.of(SERVER_A, USER_B))

    fun recipe(
        slug: String = SLUG,
        name: String = RECIPE_NAME,
        stepHtml: String = "<p><b>Batir</b> los huevos.</p>",
    ): Recipe = Recipe(
        id = RECIPE_ID,
        slug = slug,
        name = name,
        description = "Una receta de casa",
        hasImage = true,
        servings = 4.0,
        yieldText = "4 raciones",
        rating = 4.5,
        sourceUrl = "https://recetas.example.org/tortilla",
        times = RecipeTimes(
            total = "45 minutos",
            prep = "15 minutos",
            cook = "30 minutos",
            perform = "",
            totalSeconds = 2700,
            prepSeconds = 900,
            performSeconds = null,
        ),
        categories = listOf(Label("Cena", "cena")),
        tags = listOf(Label("Huevos", "huevos")),
        ingredients = listOf(
            Ingredient(
                title = "",
                quantity = 6.0,
                unitSingular = "huevo",
                unitPlural = "huevos",
                foodSingular = "huevo",
                foodPlural = "huevos",
                note = "",
                display = "6 huevos",
            ),
        ),
        steps = listOf(
            Step(
                title = "",
                summary = "",
                text = HtmlSanitizer.sanitize(stepHtml),
            ),
        ),
        notes = listOf(
            RecipeNote(
                title = "Truco",
                text = HtmlSanitizer.sanitize("<p>Cuajar despacio.</p>"),
            ),
        ),
    )

    fun draft(name: String = "Tortilla"): RecipeDraft = RecipeDraft(
        name = name,
        description = "Cena de aprovechamiento",
        servings = 4.0,
        totalTime = "45 minutos",
        prepTime = "15 minutos",
        cookTime = "30 minutos",
        performTime = "",
        ingredients = listOf(
            DraftIngredient(
                quantity = 6.0,
                unit = "huevo",
                food = "huevo",
                note = "",
                title = "",
                sourceId = INGREDIENT_ID,
            ),
        ),
        steps = listOf(DraftStep(title = "", text = "Batir los huevos.", sourceId = STEP_ID)),
        notes = listOf(DraftNote(title = "Truco", text = "Cuajar despacio.", sourceId = NOTE_ID)),
        tags = listOf(DraftOrganizer(id = TAG_ID, name = "Huevos", slug = "huevos")),
        categories = listOf(DraftOrganizer(id = CATEGORY_ID, name = "Cena", slug = "cena")),
    )
}
