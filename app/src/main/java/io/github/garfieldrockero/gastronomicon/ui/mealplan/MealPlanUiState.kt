package io.github.garfieldrockero.gastronomicon.ui.mealplan

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanMealView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanState
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import java.time.LocalDate

/** Qué se apunta en una comida nueva: una receta del servidor o texto libre. */
enum class AddKind { Recipe, Note }

/** Receta elegida para la comida. [id] es el UUID que pide el núcleo; [name] ya viene saneado. */
class PickedRecipe(val id: String, val name: String) {
    // Sin el nombre: es un dato del servidor y un estado puede acabar en un log.
    override fun toString(): String = "PickedRecipe()"
}

/**
 * Lo que la persona va rellenando al añadir una comida. Solo guarda lo escrito
 * o elegido; nada se valida aquí: lo hace el núcleo al pulsar «Añadir».
 */
data class AddMealForm(
    val date: LocalDate,
    val mealType: MealType = MealType.LUNCH,
    val kind: AddKind = AddKind.Recipe,
    val recipe: PickedRecipe? = null,
    val title: String = "",
    val text: String = "",
    /** Se está eligiendo receta: la hoja enseña el buscador en vez del formulario. */
    val picking: Boolean = false,
) {
    // Sin los textos: son de la persona y un estado puede acabar en un log.
    override fun toString(): String = "AddMealForm(date=$date, mealType=$mealType, kind=$kind, picking=$picking)"
}

/** Hoja o diálogo abierto encima de la semana; solo hay uno a la vez. */
sealed interface MealPlanDialog {
    data object None : MealPlanDialog

    data class Add(val form: AddMealForm) : MealPlanDialog

    /** Mover [meal]: [date] y [mealType] son el destino elegido. */
    data class Move(val meal: MealPlanMealView, val date: LocalDate, val mealType: MealType) : MealPlanDialog

    data class ConfirmDelete(val meal: MealPlanMealView) : MealPlanDialog
}

/** Aviso corto al terminar algo; la pantalla lo anuncia y lo da por visto con [MealPlanViewModel.onNoticeShown]. */
enum class MealPlanNotice {
    Added,
    Moved,
    Deleted,

    /**
     * La comida a mover ya no está: el núcleo no la encontró en la semana
     * cargada (o el servidor contestó 404). Hay que recargar la semana.
     */
    EntryMissing,
}

/** Recetas entre las que elegir: la lista del núcleo y el texto del buscador. */
class RecipePickerUiState(val list: RecipeListState, val query: String)

/**
 * Todo lo que pinta el planificador. Sin token, sin URL del servidor y sin
 * mensajes de excepción: los fallos son tipos del núcleo dentro de [plan].
 */
class MealPlanUiState(
    val plan: MealPlanState,
    val dialog: MealPlanDialog,
    val picker: RecipePickerUiState,
    val notice: MealPlanNotice?,
)
