package io.github.garfieldrockero.gastronomicon.core.mealplan

import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Reglas de fecha del planificador, todas en el núcleo.
 *
 * Se trabaja con [LocalDate] y solo con [LocalDate]: la API usa `format: date`
 * (sin hora), así que una zona horaria o un instante con desfase del móvil no
 * deben cambiar el día de una comida. El texto que viaja a la consulta es
 * siempre `yyyy-MM-dd` de cuatro dígitos: un año de cinco cifras produciría
 * «+10000-01-01», que `HttpRequest` rechazaría por el «+» (y que el servidor
 * no espera), así que los años se acotan antes.
 */
object MealPlanDates {

    /** Primer y último año que se admiten en una fecha del planificador. */
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2999

    /**
     * Días como máximo de un rango pedido de una vez. Una semana son 7; un año
     * entero (366) cabe de sobra y deja fuera un rango que llenaría la memoria
     * o que el servidor tardaría en contestar.
     */
    const val MAX_RANGE_DAYS = 366

    /** Null si la fecha se puede usar; el motivo si no. */
    fun check(date: LocalDate): MealPlanInputProblem? =
        if (date.year in MIN_YEAR..MAX_YEAR) null else MealPlanInputProblem.InvalidDate

    /**
     * Null si el rango se puede pedir; el motivo si no. Se comprueba en este
     * orden: invertido, fecha fuera de los años admitidos y rango demasiado
     * largo.
     */
    fun check(start: LocalDate, end: LocalDate): MealPlanInputProblem? = when {
        start.isAfter(end) -> MealPlanInputProblem.InvalidRange
        check(start) != null || check(end) != null -> MealPlanInputProblem.InvalidDate
        ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS.toLong() ->
            MealPlanInputProblem.RangeTooWide
        else -> null
    }

    /** Lunes de la semana de [date]. Es el ancla de una semana del planificador. */
    fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Lunes de la semana de [date], o null si la fecha no se puede usar.
     *
     * [weekStart] puede lanzar [DateTimeException] con fechas extremas
     * ([LocalDate.MIN]) porque el lunes anterior se sale del rango de
     * `LocalDate`; aquí se comprueba el año antes y se atrapa el desbordamiento
     * para que el controlador no reciba una excepción.
     */
    fun weekStartOrNull(date: LocalDate): LocalDate? {
        if (check(date) != null) return null
        return try {
            weekStart(date)
        } catch (ignored: DateTimeException) {
            null
        }
    }
}
