package io.github.garfieldrockero.gastronomicon.ui.mealplan

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanActionFailure
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDayView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanInputProblem
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanOperation
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanPhase
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanProblem
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanState
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanWeekView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.mealplan.PlannedMeal
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummary
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeTimes
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingOutcome
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingUi
import java.time.LocalDate

/**
 * Datos inventados para las previews del planificador. Nada de servidores, URL
 * ni tokens: los identificadores son de ejemplo.
 */
internal object MealPlanPreviewData {

    /** Miércoles; la semana de la preview va del lunes 9 al domingo 15 de noviembre de 2026. */
    val TODAY: LocalDate = LocalDate.of(2026, 11, 11)
    private val WEEK_START: LocalDate = LocalDate.of(2026, 11, 9)

    private const val RECIPE_LENTILS = "11111111-1111-4111-8111-111111111111"
    private const val RECIPE_RICE = "22222222-2222-4222-8222-222222222222"
    private const val RECIPE_OMELETTE = "33333333-3333-4333-8333-333333333333"

    private fun meal(
        id: Long,
        date: LocalDate,
        type: MealType,
        title: String = "",
        note: String = "",
        recipeId: String? = null,
        recipeName: String? = null,
        scale: Double = 1.0,
    ) = PlannedMeal(
        id = id,
        date = date,
        mealType = type,
        title = title,
        note = note,
        recipeId = recipeId,
        recipeName = recipeName,
        recipeSlug = null,
        recipeScale = scale,
        groupId = null,
        userId = null,
    )

    private val meals = listOf(
        meal(1, WEEK_START, MealType.LUNCH, recipeId = RECIPE_LENTILS, recipeName = "Lentejas de la abuela"),
        meal(2, WEEK_START, MealType.DINNER, title = "Sobras de las lentejas"),
        meal(3, TODAY, MealType.BREAKFAST, title = "Tostadas con tomate"),
        meal(4, TODAY, MealType.LUNCH, recipeId = RECIPE_RICE, recipeName = "Arroz con verduras", scale = 2.0),
        // La receta ya no existe en el servidor: la vista lo dice en vez de inventarse un nombre.
        meal(5, TODAY, MealType.DINNER, recipeId = "44444444-4444-4444-8444-444444444444"),
        meal(6, TODAY.plusDays(2), MealType.DINNER, title = "Cena fuera", note = "Reserva a las 21:00"),
    )

    private fun week(start: LocalDate, list: List<PlannedMeal>, today: LocalDate = TODAY) =
        MealPlanWeekView.of(start, list, today)

    private fun uiState(
        plan: MealPlanState,
        addToShopping: AddToShoppingUi = AddToShoppingUi(available = true),
    ) = MealPlanUiState(
        plan = plan,
        dialog = MealPlanDialog.None,
        picker = picker(),
        notice = null,
        noticeId = 0L,
        addToShopping = addToShopping,
    )

    /** Semana con comidas; los parámetros dejan probar la recarga fallida, un fallo al guardar o «guardando». */
    fun ready(
        phase: MealPlanPhase = MealPlanPhase.Idle,
        loadProblem: MealPlanProblem? = null,
        actionFailure: MealPlanActionFailure? = null,
        shoppingNotice: AddToShoppingOutcome? = null,
    ): MealPlanUiState = uiState(
        MealPlanState(
            weekStart = WEEK_START,
            today = TODAY,
            week = week(WEEK_START, meals),
            phase = phase,
            loadProblem = loadProblem,
            actionFailure = actionFailure,
        ),
        addToShopping = AddToShoppingUi(
            // En la app, «disponible» cuando está cableado el transporte de la compra.
            available = true,
            notice = shoppingNotice,
        ),
    )

    /** Otra semana distinta de la actual: enseña «Ir a esta semana». */
    fun otherWeek(): MealPlanUiState {
        val start = WEEK_START.plusWeeks(1)
        return uiState(
            MealPlanState(
                weekStart = start,
                today = TODAY,
                week = week(start, listOf(meal(7, start.plusDays(1), MealType.LUNCH, title = "Pizza casera"))),
            ),
        )
    }

    fun emptyWeek(): MealPlanUiState = uiState(
        MealPlanState(weekStart = WEEK_START, today = TODAY, week = week(WEEK_START, emptyList())),
    )

    // ------------------------------------------- secciones de comida (16f)

    /**
     * Comidas del día de hoy repartidas en las cuatro secciones principales
     * (Desayuno, Comida, Aperitivo y Cena), más un día suelto para que la semana no
     * parezca de una sola tarjeta.
     */
    private val sectionMeals = listOf(
        meal(11, TODAY, MealType.BREAKFAST, title = "Tostadas con tomate y aceite"),
        meal(12, TODAY, MealType.LUNCH, recipeId = RECIPE_RICE, recipeName = "Arroz con verduras", scale = 2.0),
        meal(13, TODAY, MealType.SNACK, title = "Fruta de media tarde"),
        meal(14, TODAY, MealType.DINNER, title = "Sobras de las lentejas", note = "Con un poco de pan"),
        meal(15, TODAY.plusDays(1), MealType.LUNCH, recipeId = RECIPE_LENTILS, recipeName = "Lentejas de la abuela"),
    )

    /** Semana con el día de hoy en las cuatro secciones principales. */
    fun sectionsWeek(today: LocalDate = TODAY): MealPlanUiState = uiState(
        MealPlanState(
            weekStart = WEEK_START,
            today = today,
            week = week(WEEK_START, sectionMeals, today),
        ),
    )

    /**
     * La misma semana y, además, guarnición, bebida, postre y un tipo que la app no
     * conoce: los cuatro caen en la sección «Otras», cada uno con su etiqueta.
     */
    fun othersWeek(): MealPlanUiState {
        val list = sectionMeals + listOf(
            meal(16, TODAY, MealType.SIDE, title = "Ensalada verde"),
            meal(17, TODAY, MealType.DRINK, title = "Agua con limón"),
            meal(18, TODAY, MealType.DESSERT, title = "Yogur con miel"),
            meal(19, TODAY, MealType.UNKNOWN, title = "Lo que quedaba en la nevera"),
        )
        return uiState(
            MealPlanState(weekStart = WEEK_START, today = TODAY, week = week(WEEK_START, list)),
        )
    }

    /** El día de hoy de una semana sin comidas: la tarjeta enseña «Sin comidas». */
    fun emptyDay(): MealPlanDayView = dayView(emptyWeek())

    /**
     * Un día ya montado por el núcleo, para las previews de una sola tarjeta. No se
     * construye a mano: sale de la misma vista que pinta la pantalla.
     */
    fun dayView(state: MealPlanUiState, date: LocalDate = TODAY): MealPlanDayView =
        requireNotNull(state.plan.week?.days?.firstOrNull { it.date == date }) {
            "La preview no tiene ese día"
        }

    fun loading(): MealPlanUiState = uiState(
        MealPlanState(weekStart = WEEK_START, today = TODAY, phase = MealPlanPhase.Loading),
    )

    fun loadFailed(problem: MealPlanProblem): MealPlanUiState = uiState(
        MealPlanState(weekStart = WEEK_START, today = TODAY, loadProblem = problem),
    )

    fun maybeCreated(): MealPlanActionFailure = MealPlanActionFailure(
        MealPlanOperation.Create,
        MealPlanProblem.MaybeCreated(ConnectionProblem.ServerNotResponding),
    )

    /** Hoja de editar una comida con receta: el nombre viene de la vista, ya saneado. */
    fun editRecipeForm(): AddMealForm = AddMealForm(
        date = TODAY,
        mealType = MealType.LUNCH,
        kind = AddKind.Recipe,
        recipe = PickedRecipe(RECIPE_RICE, "Arroz con verduras"),
        title = "",
        text = "",
        scaleText = "2",
        editingId = 4L,
    )

    /** Hoja de editar una nota: sin receta ni raciones. */
    fun editNoteForm(): AddMealForm = AddMealForm(
        date = WEEK_START,
        mealType = MealType.DINNER,
        kind = AddKind.Note,
        title = "Sobras de las lentejas",
        text = "",
        editingId = 2L,
    )

    /** La misma hoja con el día ya cambiado a otra semana y a otro año: enseña el día que se guardará. */
    fun editRecipeOtherWeekForm(): AddMealForm =
        editRecipeForm().copy(date = LocalDate.of(2027, 1, 6))

    /** Fallo de raciones al editar: el núcleo rechaza el número y la hoja marca el campo. */
    fun invalidScale(): MealPlanActionFailure = MealPlanActionFailure(
        MealPlanOperation.Edit,
        MealPlanProblem.InvalidInput(MealPlanInputProblem.InvalidRecipeScale),
    )

    private fun recipe(id: String, slug: String, name: String, total: String) = RecipeSummary(
        id = id,
        slug = slug,
        name = name,
        description = "",
        hasImage = false,
        servings = 4.0,
        rating = null,
        times = RecipeTimes(total = total),
        categories = emptyList(),
        tags = emptyList(),
    )

    /** Selector con tres recetas de ejemplo ya cargadas. */
    fun picker(): RecipePickerUiState = RecipePickerUiState(
        list = RecipeListState(
            items = listOf(
                recipe(RECIPE_LENTILS, "lentejas-de-la-abuela", "Lentejas de la abuela", "1 h 30 min"),
                recipe(RECIPE_RICE, "arroz-con-verduras", "Arroz con verduras", "50 min"),
                recipe(RECIPE_OMELETTE, "tortilla-de-patatas", "Tortilla de patatas", "35 min"),
            ),
            lastPage = 1,
            loadedOnce = true,
        ),
        query = "",
    )
}
