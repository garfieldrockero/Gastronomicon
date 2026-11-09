package io.github.garfieldrockero.gastronomicon.core.mealplan

import java.time.LocalDate

/**
 * Textos de fecha del planificador, en castellano y ya compuestos.
 *
 * Van aquí y no en la interfaz porque la interfaz no calcula: si dos pantallas
 * enseñan una semana, la escriben igual las dos. No se usa `DateTimeFormatter`
 * con un `Locale` del móvil a propósito: el idioma de la app es el castellano y
 * así la salida no cambia con la configuración regional del dispositivo.
 */
internal object MealPlanLabels {

    private val WEEKDAYS = arrayOf(
        "lunes",
        "martes",
        "miércoles",
        "jueves",
        "viernes",
        "sábado",
        "domingo",
    )

    private val MONTHS = arrayOf(
        "enero",
        "febrero",
        "marzo",
        "abril",
        "mayo",
        "junio",
        "julio",
        "agosto",
        "septiembre",
        "octubre",
        "noviembre",
        "diciembre",
    )

    /** «lunes 9 de noviembre», con el día de la semana delante. */
    fun day(date: LocalDate): String =
        "${WEEKDAYS[date.dayOfWeek.value - 1]} ${date.dayOfMonth} de ${MONTHS[date.monthValue - 1]}"

    /**
     * Igual que [day], pero con el año cuando no es el de [today]:
     * «miércoles 18 de noviembre» o «viernes 1 de enero de 2027». Sin el año,
     * un día de otra semana del mismo mes (o de otro año) se lee como si fuera
     * de este año; y en la app hay hojas que enseñan días de cualquier semana.
     */
    fun dayWithYear(date: LocalDate, today: LocalDate): String =
        if (date.year == today.year) day(date) else "${day(date)} de ${date.year}"

    /** «Semana del 9 al 15 de noviembre de 2026», también al cambiar de mes o de año. */
    fun week(start: LocalDate, end: LocalDate): String = when {
        start.year == end.year && start.monthValue == end.monthValue ->
            "Semana del ${start.dayOfMonth} al ${end.dayOfMonth} de " +
                "${MONTHS[start.monthValue - 1]} de ${start.year}"

        start.year == end.year ->
            "Semana del ${start.dayOfMonth} de ${MONTHS[start.monthValue - 1]} al " +
                "${end.dayOfMonth} de ${MONTHS[end.monthValue - 1]} de ${start.year}"

        else ->
            "Semana del ${start.dayOfMonth} de ${MONTHS[start.monthValue - 1]} de ${start.year} al " +
                "${end.dayOfMonth} de ${MONTHS[end.monthValue - 1]} de ${end.year}"
    }
}
