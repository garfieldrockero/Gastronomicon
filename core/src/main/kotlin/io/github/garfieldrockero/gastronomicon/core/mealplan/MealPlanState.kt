package io.github.garfieldrockero.gastronomicon.core.mealplan

import java.time.LocalDate

/** Qué está haciendo el planificador ahora mismo. */
enum class MealPlanPhase {
    Idle,

    /** Cargando una semana o las comidas de hoy. */
    Loading,

    /** Hay una escritura (crear, mover, editar o borrar) en curso. */
    Saving,
}

/** Operación de escritura que puede fallar, para que la interfaz reintente la que toca. */
enum class MealPlanOperation {
    Today,
    Create,
    Move,
    Edit,
    Delete,
}

/** Fallo de una operación concreta: se borra al empezar otra o al tener éxito. */
data class MealPlanActionFailure(
    val operation: MealPlanOperation,
    val problem: MealPlanProblem,
) {
    // Sin el detalle: aunque los mensajes del 422 vienen saneados, un estado puede acabar en un log.
    override fun toString(): String = "MealPlanActionFailure(operation=$operation)"
}

/**
 * Estado del planificador. Es inmutable: cada paso devuelve otro estado y la
 * interfaz solo lo pinta. No lleva textos crudos del servidor (la vista ya
 * está compuesta y saneada).
 *
 * [loadProblem] es el fallo de la carga de la semana (se reintenta con
 * `MealPlanController.retry`); [actionFailure] es el de la última escritura o
 * de la carga de «hoy» (se reintenta repitiendo esa operación). Nunca se
 * guarda el mensaje de una excepción.
 */
data class MealPlanState(
    /** Lunes de la semana que se está mirando. */
    val weekStart: LocalDate,
    /** Día de hoy según el núcleo; decide el indicador de «hoy». */
    val today: LocalDate,
    /** Semana cargada; null mientras no haya nada que enseñar. */
    val week: MealPlanWeekView? = null,
    /** Comidas de hoy según `/today`, ya saneadas. */
    val todayMeals: List<MealPlanMealView> = emptyList(),
    val phase: MealPlanPhase = MealPlanPhase.Idle,
    val loadProblem: MealPlanProblem? = null,
    val actionFailure: MealPlanActionFailure? = null,
) {
    val isLoading: Boolean get() = phase == MealPlanPhase.Loading

    val isSaving: Boolean get() = phase == MealPlanPhase.Saving

    val isBusy: Boolean get() = phase != MealPlanPhase.Idle

    val isReady: Boolean get() = week != null

    /** Hay un fallo de carga que se arregla con `MealPlanController.retry`. */
    val canRetry: Boolean get() = loadProblem != null && phase == MealPlanPhase.Idle

    // Sin el contenido: son datos del servidor y un estado puede acabar en un log.
    override fun toString(): String =
        "MealPlanState(weekStart=$weekStart, today=$today, phase=$phase, loadProblem=$loadProblem, " +
            "actionFailure=$actionFailure)"
}
