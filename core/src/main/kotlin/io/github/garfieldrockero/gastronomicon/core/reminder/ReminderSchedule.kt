package io.github.garfieldrockero.gastronomicon.core.reminder

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Cálculo del próximo aviso del recordatorio, en Kotlin puro y con `java.time`.
 *
 * La alarma real la pone `app/platform` con `AlarmManager`; aquí solo se decide
 * **cuándo** toca (decisión D2: 09:00 por defecto, un aviso al día) y **si un
 * disparo llega demasiado tarde** para avisar. Todo se prueba con reloj y zona
 * fijos, así que ninguna prueba depende de la hora del equipo.
 *
 * Reglas que fija el núcleo y no la interfaz:
 *
 * - Siempre devuelve un instante **estrictamente futuro**, con segundos y
 *   nanosegundos a cero. Si la hora de hoy ya pasó (o es exactamente «ahora»),
 *   el disparo es el de mañana.
 * - En el cambio de hora de primavera la hora local puede no existir: `java.time`
 *   la desplaza hacia delante (02:30 → 03:30) y el aviso de ese día no se pierde.
 * - En el cambio de otoño la hora local existe dos veces: se elige la primera
 *   ocurrencia y así no se avisa dos veces.
 * - Un año fuera de [MIN_YEAR]..[MAX_YEAR], una zona inválida o un minuto del
 *   día fuera de rango devuelven `null` en vez de lanzar: quien programa la
 *   alarma la cancela (falla cerrado).
 */
object ReminderSchedule {

    /** 09:00: un aviso al día, antes de empezar a cocinar (decisión D2). */
    const val DEFAULT_MINUTE_OF_DAY: Int = 9 * 60

    /** Minutos de un día: el minuto del día válido va de 0 a 1439. */
    const val MINUTES_PER_DAY: Int = 24 * 60

    /** Años que se admiten en un disparo. */
    const val MIN_YEAR: Int = 1900
    const val MAX_YEAR: Int = 2999

    /**
     * Retraso máximo que todavía se avisa. Con el móvil en Doze es normal un
     * retraso de minutos; pasadas [LATE_THRESHOLD] horas el «hoy toca» ya no
     * tiene sentido y solo se reprograma (sección 1.5 del diseño).
     */
    val LATE_THRESHOLD: Duration = Duration.ofHours(6)

    /** Longitud máxima de un identificador de zona horaria que se acepta. */
    private const val MAX_ZONE_ID_LENGTH = 64

    /** ¿El minuto del día está dentro de 0..1439? */
    fun isValidMinuteOfDay(value: Int): Boolean = value in 0 until MINUTES_PER_DAY

    /**
     * Hora local de [minuteOfDay]. Lanza [IllegalArgumentException] si el valor
     * no es válido: quien lo guarda ya lo ha coaccionado con
     * [ReminderSettings.coerceMinuteOfDay] o [textOf].
     */
    fun timeOf(minuteOfDay: Int): LocalTime {
        require(isValidMinuteOfDay(minuteOfDay)) {
            "El minuto del día debe estar entre 0 y ${MINUTES_PER_DAY - 1}."
        }
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
    }

    /**
     * Texto «HH:mm» del minuto del día, ya coaccionado: un valor inválido que
     * llegue de preferencias manipuladas se pinta como el de por defecto en vez
     * de romper la pantalla.
     */
    fun textOf(minuteOfDay: Int): String {
        val safe = if (isValidMinuteOfDay(minuteOfDay)) minuteOfDay else DEFAULT_MINUTE_OF_DAY
        return timeOf(safe).toString()
    }

    /**
     * Próximo disparo a la hora [minuteOfDay] en [zone], o `null` si no se puede
     * programar. [now] se toma como el instante actual: el resultado siempre es
     * posterior.
     */
    fun nextTrigger(now: Instant, minuteOfDay: Int, zone: ZoneId): ReminderTrigger? {
        if (!isValidMinuteOfDay(minuteOfDay)) return null
        return try {
            val time = timeOf(minuteOfDay)
            val today = now.atZone(zone).toLocalDate()
            if (today.year !in MIN_YEAR..MAX_YEAR) return null

            var candidate = ZonedDateTime.of(today, time, zone)
            if (!candidate.toInstant().isAfter(now)) {
                val nextDate = today.plusDays(1)
                if (nextDate.year !in MIN_YEAR..MAX_YEAR) return null
                candidate = ZonedDateTime.of(nextDate, time, zone)
            }

            // La fecha que se devuelve es la local ya resuelta: en un día que no
            // existió (Pacific/Apia, 2011) el salto cae en el día siguiente y el
            // aviso tiene que enseñar el plan de ese día, no el que no existió.
            val date = candidate.toLocalDate()
            if (date.year !in MIN_YEAR..MAX_YEAR) return null
            val at = candidate.toInstant()
            if (!at.isAfter(now)) return null
            ReminderTrigger(at = at, date = date)
        } catch (ignored: DateTimeException) {
            null
        } catch (ignored: ArithmeticException) {
            null
        }
    }

    /**
     * ¿Se avisó tan tarde que ya no merece la pena? Un retraso de hasta
     * [LATE_THRESHOLD] se notifica; uno mayor solo se reprograma.
     *
     * Si [now] es anterior a [scheduledAt] no es un disparo tardío (reloj movido
     * hacia atrás), y un rango de fechas imposible cuenta como tardío en vez de
     * lanzar.
     */
    fun isTooLate(scheduledAt: Instant, now: Instant): Boolean {
        if (!now.isAfter(scheduledAt)) return false
        return try {
            Duration.between(scheduledAt, now).compareTo(LATE_THRESHOLD) > 0
        } catch (ignored: ArithmeticException) {
            true
        }
    }

    /**
     * Zona horaria a partir del identificador guardado, o `null` si no es válido.
     *
     * La interfaz no llama a `ZoneId.of` a pelo: un valor manipulado en
     * preferencias se trata como «sin zona» y el llamante cancela la alarma.
     */
    fun zoneOrNull(id: String?): ZoneId? {
        if (id.isNullOrBlank() || id.length > MAX_ZONE_ID_LENGTH) return null
        return try {
            ZoneId.of(id)
        } catch (ignored: DateTimeException) {
            null
        }
    }
}

/** Instante del próximo aviso y fecha local a la que pertenece. */
data class ReminderTrigger(val at: Instant, val date: LocalDate) {
    // Ni el instante ni la fecha son secretos, pero no arrastran contenido.
    override fun toString(): String = "ReminderTrigger(at=$at, date=$date)"
}
