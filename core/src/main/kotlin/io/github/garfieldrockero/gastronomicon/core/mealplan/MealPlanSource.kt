package io.github.garfieldrockero.gastronomicon.core.mealplan

import java.time.LocalDate

/**
 * De dónde salen y adónde van las entradas del planificador. Lo implementa
 * `MealPlanClient`; las pruebas del controlador ponen una versión falsa y no
 * necesitan red.
 *
 * Ningún método lanza por red: todo desenlace es un [MealPlanResult]. Es
 * síncrono, como el resto del núcleo: quien llama decide el hilo.
 */
interface MealPlanSource {

    /** Entradas entre [start] y [end], ambos incluidos. Lee todas las páginas necesarias. */
    fun listEntries(start: LocalDate, end: LocalDate): MealPlanResult<List<PlannedMeal>>

    /**
     * Entradas de hoy, según `GET /api/households/mealplans/today`.
     *
     * Ojo: el esquema de esa respuesta **no está declarado** en
     * `docs/openapi.json` (viene vacío). El cliente acepta una lista de
     * entradas o el objeto paginado con `items`; cualquier otra forma es
     * [MealPlanResult.Failure] en vez de una lista inventada.
     */
    fun entriesToday(): MealPlanResult<List<PlannedMeal>>

    /** Una entrada por su id. */
    fun getEntry(id: Long): MealPlanResult<PlannedMeal>

    /** Crea una entrada. Ver [MealPlanResult.MaybeCreated] para el fallo ambiguo. */
    fun createEntry(draft: MealPlanDraft): MealPlanResult<PlannedMeal>

    /** Mueve [entry] a [date] (y, si se indica, a otro [mealType]); conserva lo demás. */
    fun moveEntry(
        entry: PlannedMeal,
        date: LocalDate,
        mealType: MealType = entry.mealType,
    ): MealPlanResult<PlannedMeal>

    /** Sustituye los datos de [entry] por los del borrador [draft]. */
    fun editEntry(entry: PlannedMeal, draft: MealPlanDraft): MealPlanResult<PlannedMeal>

    /** Borra la entrada [id]. */
    fun deleteEntry(id: Long): MealPlanResult<Unit>
}
