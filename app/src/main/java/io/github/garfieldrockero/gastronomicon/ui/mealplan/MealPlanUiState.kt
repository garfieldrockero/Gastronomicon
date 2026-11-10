package io.github.garfieldrockero.gastronomicon.ui.mealplan

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanMealView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanState
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingUi
import java.time.LocalDate

/** Qué se apunta en una comida nueva: una receta del servidor o texto libre. */
enum class AddKind { Recipe, Note }

/** Receta elegida para la comida. [id] es el UUID que pide el núcleo; [name] ya viene saneado. */
class PickedRecipe(val id: String, val name: String) {
    // Sin el nombre: es un dato del servidor y un estado puede acabar en un log.
    override fun toString(): String = "PickedRecipe()"
}

/**
 * Lo que la persona va rellenando al añadir o editar una comida. Solo guarda lo
 * escrito o elegido; nada se valida aquí: lo hace el núcleo al confirmar.
 *
 * [editingId] distingue las dos caras de la misma hoja: null es una comida
 * nueva y un id es la entrada que se está editando. [scaleText] es el número
 * tal como se escribe (con coma o con punto); el núcleo decide si vale.
 */
data class AddMealForm(
    val date: LocalDate,
    val mealType: MealType = MealType.LUNCH,
    val kind: AddKind = AddKind.Recipe,
    val recipe: PickedRecipe? = null,
    val title: String = "",
    val text: String = "",
    /** Raciones escritas para una comida con receta; vacío es la receta tal cual. */
    val scaleText: String = "",
    /** Entrada que se está editando; null si es una comida nueva. */
    val editingId: Long? = null,
    /** Se está eligiendo receta: la hoja enseña el buscador en vez del formulario. */
    val picking: Boolean = false,
) {
    // Sin los textos: son de la persona y un estado puede acabar en un log.
    override fun toString(): String =
        "AddMealForm(date=$date, mealType=$mealType, kind=$kind, picking=$picking, " +
            "editing=${editingId != null})"
}

/** Hoja o diálogo abierto encima de la semana; solo hay uno a la vez. */
sealed interface MealPlanDialog {
    data object None : MealPlanDialog

    /**
     * La misma hoja para añadir o editar: [AddMealForm.editingId] dice cuál.
     * Al editar cambia el día (cualquier fecha), el tipo de comida, las
     * raciones y la nota; no hay hoja aparte para mover.
     */
    data class Add(val form: AddMealForm) : MealPlanDialog

    data class ConfirmDelete(val meal: MealPlanMealView) : MealPlanDialog
}

/** Aviso corto al terminar algo; la pantalla lo anuncia y lo da por visto con [MealPlanViewModel.onNoticeShown]. */
enum class MealPlanNotice {
    Added,
    Edited,

    /**
     * Se guardó en un día de otra semana: la semana a la vista no cambia y la
     * pantalla ofrece «Ver esa semana».
     */
    MovedOtherWeek,
    Deleted,

    /**
     * La comida a editar o a quitar ya no está: el núcleo no la encontró en la
     * semana cargada (o el servidor contestó 404). Hay que recargar la semana.
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
    /**
     * Sube con cada aviso nuevo, aunque sea del mismo tipo: es la clave con la
     * que la pantalla vuelve a lanzar la confirmación (dos comidas añadidas
     * seguidas se anuncian las dos, no una).
     */
    val noticeId: Long = 0L,
    /**
     * Día del aviso [MealPlanNotice.MovedOtherWeek]: la pantalla ofrece «Ver esa
     * semana» cuando no es null. Sin él, el aviso no lleva acción.
     */
    val noticeDate: LocalDate? = null,
    /**
     * Fase 9, extra 1: «añadir los ingredientes de esta semana a la compra». El
     * recuento de recetas distintas, el tope y las escalas los da el núcleo; aquí
     * solo se pinta el diálogo y su aviso.
     */
    val addToShopping: AddToShoppingUi = AddToShoppingUi(),
)
