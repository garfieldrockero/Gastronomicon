package io.github.garfieldrockero.gastronomicon.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSchedule
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSettings
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pone la alarma del recordatorio en [AlarmManager] (decisión D11: sin
 * `SCHEDULE_EXACT_ALARM`, se acepta ±15 min) y la cancela.
 *
 * - Alarma **de un solo tiro** con `setAndAllowWhileIdle(RTC_WAKEUP, …)`: la hora
 *   es de pared (lo que elige la persona) y el sistema la admite en Doze. El
 *   siguiente disparo lo pone [ReminderReceiver] al recibir el aviso, así que una
 *   repetición diaria inexacta no puede ir acumulando retraso.
 * - **Siempre se cancela antes de programar**: una sola alarma viva aunque se
 *   active dos veces o se cambie la hora.
 * - `PendingIntent` **explícito, inmutable y sin datos**: solo lleva la acción
 *   propia. Nadie de fuera puede llenarlo ni reutilizarlo.
 * - Sin sesión (`offlineScopeId` vacío o marcado para borrar) no hay nada que
 *   avisar: se cancela. La preferencia **no** se toca (decisión D7).
 *
 * No abre conexiones ni lee la sesión: solo preferencias y [AlarmManager].
 */
class ReminderScheduler(
    context: Context,
    private val preferences: AppPreferences,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    private val appContext: Context = context.applicationContext

    /** Instante actual, del reloj inyectable (las pruebas de la app no dependen de la hora real). */
    fun now(): Instant = clock.instant()

    /** Zona del sistema, la misma con la que se pinta la hora en Ajustes. */
    fun zone(): ZoneId = clock.zone

    /** Ajustes guardados, ya coaccionados por `core/` (`ReminderSettings`). */
    fun settings(): ReminderSettings = ReminderSettings.fromStored(
        preferences.reminderEnabled,
        preferences.reminderMinuteOfDay,
        preferences.reminderLastNotifiedDate,
    )

    /**
     * Programa el próximo aviso o cancela la alarma si no toca (apagado, sin
     * sesión o disparo imposible). Nunca lanza: un fallo de [AlarmManager] no
     * puede tumbar el guardado de la sesión ni la pantalla de Ajustes.
     */
    fun schedule() {
        try {
            scheduleInternal()
        } catch (ignored: RuntimeException) {
            cancel()
        }
    }

    /** Quita la alarma si la hay. No toca ninguna preferencia. */
    fun cancel() {
        try {
            alarmManager()?.cancel(alarmPendingIntent())
        } catch (ignored: RuntimeException) {
            // Sin alarma tampoco pasa nada: al abrir la app se vuelve a programar.
        }
    }

    /**
     * Instante del aviso de [date] a la hora [minuteOfDay], o `null` si esa fecha
     * o esa hora no se pueden usar (año fuera de rango, hora inexistente por el
     * cambio de horario). Falla cerrado: quien llama no avisa.
     */
    fun instantOn(date: LocalDate, minuteOfDay: Int): Instant? = try {
        ZonedDateTime.of(date, ReminderSchedule.timeOf(minuteOfDay), zone()).toInstant()
    } catch (ignored: DateTimeException) {
        null
    }

    private fun scheduleInternal() {
        val settings = settings()
        val manager = alarmManager()
        if (manager == null || !settings.enabled || preferences.reminderScopeOrNull() == null) {
            cancel()
            return
        }
        val trigger = ReminderSchedule.nextTrigger(now(), settings.minuteOfDay, zone())
        val pending = alarmPendingIntent()
        // Una sola alarma viva, aunque se llame dos veces seguidas.
        manager.cancel(pending)
        if (trigger == null) return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.at.toEpochMilli(), pending)
    }

    private fun alarmManager(): AlarmManager? =
        appContext.getSystemService(AlarmManager::class.java)

    private fun alarmPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMINDER_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** Código fijo: una sola alarma del recordatorio en toda la app. */
        const val REQUEST_CODE = 4100
    }
}

/**
 * Ámbito de la cuenta guardada tal como lo ve el segundo plano: solo
 * `offlineScopeId` y su marca de borrado pendiente, **nunca** la sesión ni el
 * token. Null = no se avisa (falla cerrado).
 */
internal fun AppPreferences.reminderScopeOrNull(): ServerScope? {
    val stored = offlineScopeId ?: return null
    if (offlineScopeClearPending) return null
    return ServerScope.fromStoredId(stored)
}
