package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDraft
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanResult
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanSource
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.mealplan.PlannedMeal
import io.github.garfieldrockero.gastronomicon.core.offline.CachedMealPlan
import io.github.garfieldrockero.gastronomicon.core.offline.MealPlanCache
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineRead
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.ui.mealplan.MealPlanAccess
import java.time.Clock
import java.time.LocalDate

/**
 * Decorador de [MealPlanAccess] que, además de leer el planificador, deja una
 * **copia cifrada** de lo leído para el recordatorio (decisión D1: el receptor
 * del aviso no hace red ni descifra el token).
 *
 * - Se escribe al abrir el planificador: tras un `listEntries` correcto de la
 *   semana y tras un `entriesToday` correcto. Nunca al fallar la red.
 * - **Solo fechas de hoy en adelante** (indicación de la revisión de seguridad
 *   del núcleo): el aviso es para hoy, y guardar días pasados podría empujar el
 *   día de hoy fuera del tope de 7 fechas de [MealPlanCache].
 * - La copia se **fusiona**: `save` sustituye el plan entero, así que lo que no
 *   viene en esta lectura (otros días ya guardados, siempre de hoy en adelante)
 *   se conserva. Los días del rango leído se sustituyen enteros, también si el
 *   servidor ya no devuelve nada para ellos: si no, se avisaría con un plan
 *   borrado.
 * - Nada de esto cambia lo que se enseña en pantalla: el resultado se devuelve
 *   tal cual. Las escrituras no lanzan (los resultados del almacén son valores)
 *   y el resultado se ignora aquí a propósito.
 * - El **ámbito** (servidor y cuenta) se toma antes de cada lectura y se vuelve
 *   a comparar con el vigente justo antes de escribir: si la sesión se cierra o
 *   cambia mientras tanto, lo leído con el token anterior no se guarda bajo el
 *   ámbito nuevo.
 *
 * No calcula reglas de negocio: filtra por fecha y compone el plan que el
 * almacén del núcleo ya valida y acota.
 */
class CachingMealPlanAccess(
    private val delegate: MealPlanAccess,
    private val cache: MealPlanCache,
    private val scope: () -> ServerScope?,
    private val clock: Clock = Clock.systemDefaultZone(),
) : MealPlanAccess {

    override fun newSource(): MealPlanSource =
        CachingSource(delegate.newSource(), cache, scope, clock)
}

private class CachingSource(
    private val delegate: MealPlanSource,
    private val cache: MealPlanCache,
    private val scope: () -> ServerScope?,
    private val clock: Clock,
) : MealPlanSource {

    override fun listEntries(start: LocalDate, end: LocalDate): MealPlanResult<List<PlannedMeal>> {
        // El ámbito se toma ANTES de pedir los datos: es la cuenta a la que
        // pertenece lo que se va a leer y bajo la que se puede guardar.
        val account = scope()
        val result = delegate.listEntries(start, end)
        if (account != null && result is MealPlanResult.Success) {
            record(account, result.value, start, end, trustEmptyDays = true)
        }
        return result
    }

    override fun entriesToday(): MealPlanResult<List<PlannedMeal>> {
        val account = scope()
        val result = delegate.entriesToday()
        if (account != null && result is MealPlanResult.Success) {
            val today = LocalDate.now(clock)
            // Un día vacío de `/today` no borra la copia: si el servidor va en otra
            // zona horaria, su «hoy» puede no ser el del móvil y no se distingue de
            // una respuesta rara. La semana sí sustituye sus días enteros.
            record(account, result.value, today, today, trustEmptyDays = false)
        }
        return result
    }

    override fun getEntry(id: Long): MealPlanResult<PlannedMeal> = delegate.getEntry(id)

    override fun createEntry(draft: MealPlanDraft): MealPlanResult<PlannedMeal> = delegate.createEntry(draft)

    override fun moveEntry(
        entry: PlannedMeal,
        date: LocalDate,
        mealType: MealType,
    ): MealPlanResult<PlannedMeal> = delegate.moveEntry(entry, date, mealType)

    override fun editEntry(entry: PlannedMeal, draft: MealPlanDraft): MealPlanResult<PlannedMeal> =
        delegate.editEntry(entry, draft)

    override fun deleteEntry(id: Long): MealPlanResult<Unit> = delegate.deleteEntry(id)

    /**
     * Sustituye lo leído para [start]..[end] por [meals] y conserva el resto de
     * días guardados de hoy en adelante. Una vista anterior a hoy no toca nada.
     * Con [trustEmptyDays] en `false`, una lectura sin comidas para el rango deja
     * la copia como estaba (ver `entriesToday`).
     *
     * [account] es el ámbito que había **antes** de lanzar la petición. Si la
     * sesión se ha cerrado o cambiado mientras se leía, lo que ha llegado lleva
     * el token anterior y no se guarda bajo el ámbito nuevo: la copia se queda
     * como estaba (falla cerrado, no se mezclan dos cuentas).
     */
    private fun record(
        account: ServerScope,
        meals: List<PlannedMeal>,
        start: LocalDate,
        end: LocalDate,
        trustEmptyDays: Boolean,
    ) {
        val today = LocalDate.now(clock)
        if (end.isBefore(today)) return

        val replaceFrom = if (start.isAfter(today)) start else today
        val fresh = meals.filter { !it.date.isBefore(replaceFrom) && !it.date.isAfter(end) }
        if (fresh.isEmpty() && !trustEmptyDays) return
        val cached = when (val read = cache.load(account)) {
            is OfflineRead.Hit -> read.value
            else -> null
        }
        val kept = cached?.let { keptMeals(it, today, replaceFrom, end) }.orEmpty()
        val merged = fresh + kept
        if (merged.isEmpty() && cached == null) return
        // Última comprobación antes de escribir: si el ámbito vigente ya no es el
        // de la lectura, lo leído con la sesión anterior no se guarda.
        if (scope() != account) return
        cache.save(account, merged)
    }

    /**
     * Comidas guardadas que esta lectura no ha sustituido. Se reconstruyen como
     * [PlannedMeal] con ids **negativos y únicos** (solo sirven para que el
     * almacén no deduplique dos comidas del mismo tipo): no son entradas del
     * servidor ni viajan a ninguna ruta.
     */
    private fun keptMeals(
        plan: CachedMealPlan,
        today: LocalDate,
        replaceFrom: LocalDate,
        end: LocalDate,
    ): List<PlannedMeal> {
        var syntheticId = -1L
        val result = ArrayList<PlannedMeal>()
        for (day in plan.days) {
            if (day.date.isBefore(today)) continue
            if (!day.date.isBefore(replaceFrom) && !day.date.isAfter(end)) continue
            for (meal in day.meals) {
                result += PlannedMeal(
                    id = syntheticId--,
                    date = day.date,
                    mealType = meal.mealType,
                    title = meal.title,
                    note = "",
                    recipeId = null,
                    recipeName = null,
                    recipeSlug = null,
                    recipeScale = 1.0,
                    groupId = null,
                    userId = null,
                    rawJson = null,
                )
            }
        }
        return result
    }
}
