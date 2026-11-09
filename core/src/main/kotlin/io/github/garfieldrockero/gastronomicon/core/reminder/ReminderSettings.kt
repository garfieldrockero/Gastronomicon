package io.github.garfieldrockero.gastronomicon.core.reminder

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Preferencias del recordatorio, con la coacción centralizada.
 *
 * No son secretos: `app/platform` las guarda en `SharedPreferences` privadas,
 * igual que el tema. Lo que aporta este modelo es que ningún valor manipulado o
 * de otra versión llega a la alarma: un minuto del día fuera de rango cae al de
 * por defecto y una fecha en un formato raro se trata como «nunca se ha
 * avisado» (se vuelve a avisar, que es preferible a callarse para siempre).
 *
 * [lastNotifiedDate] es la mejora de la revisión de seguridad: evita que dos
 * disparos del mismo día (reprogramaciones, reloj movido, un reintento) enseñen
 * el aviso dos veces.
 */
data class ReminderSettings(
    val enabled: Boolean = false,
    val minuteOfDay: Int = ReminderSchedule.DEFAULT_MINUTE_OF_DAY,
    val lastNotifiedDate: LocalDate? = null,
) {

    /** «09:00»; si el valor guardado no vale, el de por defecto. */
    val timeText: String get() = ReminderSchedule.textOf(minuteOfDay)

    val hasValidMinuteOfDay: Boolean get() = ReminderSchedule.isValidMinuteOfDay(minuteOfDay)

    /**
     * ¿Toca avisar el [date]? Solo con el recordatorio activado y si ese día no
     * se ha avisado ya. La comparación por fecha (no por instante) es lo que
     * impide el doble aviso tras una reprogramación.
     */
    fun shouldNotify(date: LocalDate): Boolean = enabled && lastNotifiedDate != date

    /** Copia con la marca de que ya se ha avisado el [date]. */
    fun notifiedOn(date: LocalDate): ReminderSettings = copy(lastNotifiedDate = date)

    // Sin contenido del plan: aquí no hay nombres ni notas, solo el ajuste.
    override fun toString(): String =
        "ReminderSettings(enabled=$enabled, minuteOfDay=$minuteOfDay, lastNotifiedDate=$lastNotifiedDate)"

    companion object {

        /** Ajuste de fábrica: apagado y a las 09:00. */
        val DEFAULT: ReminderSettings = ReminderSettings()

        /** Un minuto del día válido, o el de por defecto si el guardado no lo es. */
        fun coerceMinuteOfDay(raw: Int?): Int =
            if (raw != null && ReminderSchedule.isValidMinuteOfDay(raw)) {
                raw
            } else {
                ReminderSchedule.DEFAULT_MINUTE_OF_DAY
            }

        /** Formato guardado de la fecha: `yyyy-MM-dd`, o null si no hay marca. */
        fun dateText(date: LocalDate?): String? = date?.toString()

        /**
         * Lee la marca guardada. Null ante cualquier cosa que no sea una fecha
         * ISO canónica y dentro de los años admitidos: no se repara a medias.
         */
        fun parseDate(raw: String?): LocalDate? {
            if (raw == null || raw.length != 10) return null
            val date = try {
                LocalDate.parse(raw)
            } catch (ignored: DateTimeParseException) {
                return null
            }
            if (date.year !in ReminderSchedule.MIN_YEAR..ReminderSchedule.MAX_YEAR) return null
            // LocalDate.parse ya es estricto con el formato, pero se comprueba
            // que el texto sea exactamente el canónico (sin ceros de más).
            if (date.toString() != raw) return null
            return date
        }

        /**
         * Ajuste a partir de lo leído del almacén de preferencias. Cualquier
         * null o valor raro se coacciona; nunca lanza.
         */
        fun fromStored(
            enabled: Boolean?,
            minuteOfDay: Int?,
            lastNotifiedDate: String?,
        ): ReminderSettings = ReminderSettings(
            enabled = enabled == true,
            minuteOfDay = coerceMinuteOfDay(minuteOfDay),
            lastNotifiedDate = parseDate(lastNotifiedDate),
        )
    }
}
