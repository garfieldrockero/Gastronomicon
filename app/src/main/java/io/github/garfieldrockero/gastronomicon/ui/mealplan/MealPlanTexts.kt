package io.github.garfieldrockero.gastronomicon.ui.mealplan

import androidx.annotation.StringRes
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanInputProblem
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanOperation
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanProblem
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes

/*
 * El núcleo decide el motivo y aquí solo se le pone texto, como en `errorTextRes`.
 * Los textos viven en `strings_planificador.xml` y nunca llevan lo que mande el
 * servidor (ni siquiera los mensajes de un 422): así no se cuela nada ajeno.
 * Sin «else» a propósito: un motivo nuevo en el núcleo no compila sin su texto.
 */

@StringRes
fun planInputTextRes(problem: MealPlanInputProblem): Int = when (problem) {
    MealPlanInputProblem.InvalidDate -> R.string.plan_input_date
    MealPlanInputProblem.InvalidRange, MealPlanInputProblem.RangeTooWide -> R.string.plan_input_range
    MealPlanInputProblem.InvalidMealType -> R.string.plan_input_meal_type
    MealPlanInputProblem.EmptyEntry -> R.string.plan_input_empty
    MealPlanInputProblem.TitleTooLong -> R.string.plan_input_title_long
    MealPlanInputProblem.TextTooLong -> R.string.plan_input_text_long
    MealPlanInputProblem.InvalidRecipeId -> R.string.plan_input_recipe
    MealPlanInputProblem.InvalidRecipeScale -> R.string.plan_input_scale
    MealPlanInputProblem.InvalidEntryId -> R.string.plan_input_entry
    MealPlanInputProblem.MissingEntryIdentity -> R.string.plan_input_identity
}

/** Texto de un fallo de escritura (añadir, mover o borrar). */
@StringRes
fun planActionTextRes(problem: MealPlanProblem): Int = when (problem) {
    MealPlanProblem.Unauthorized -> R.string.problem_text_unauthorized
    MealPlanProblem.Forbidden -> R.string.plan_action_forbidden
    MealPlanProblem.NotFound -> R.string.plan_action_not_found
    is MealPlanProblem.InvalidInput -> planInputTextRes(problem.problem)
    is MealPlanProblem.Invalid -> R.string.plan_action_invalid
    is MealPlanProblem.Rejected -> R.string.plan_action_rejected
    is MealPlanProblem.MaybeCreated -> R.string.plan_action_maybe_created
    is MealPlanProblem.Connection ->
        if (problem.problem == ConnectionProblem.Unauthorized) {
            R.string.problem_text_unauthorized
        } else {
            errorTextRes(problem.problem)
        }
}

@StringRes
fun planActionTitleRes(operation: MealPlanOperation): Int = when (operation) {
    MealPlanOperation.Create -> R.string.plan_action_failed_create
    MealPlanOperation.Move -> R.string.plan_action_failed_move
    MealPlanOperation.Delete -> R.string.plan_action_failed_delete
    MealPlanOperation.Edit, MealPlanOperation.Today -> R.string.plan_action_failed_other
}

/** El fallo se arregla entrando en Ajustes (token caducado o sin permiso). */
fun MealPlanProblem.needsSettings(): Boolean = when (this) {
    MealPlanProblem.Unauthorized, MealPlanProblem.Forbidden -> true
    is MealPlanProblem.Connection -> problem == ConnectionProblem.Unauthorized
    else -> false
}

/** Hay que mirar la semana antes de volver a intentarlo (puede que la comida ya exista). */
fun MealPlanProblem.needsReload(): Boolean = when (this) {
    is MealPlanProblem.MaybeCreated, MealPlanProblem.NotFound -> true
    else -> false
}
