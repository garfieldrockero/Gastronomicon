package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSettings
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.settings.ReminderAccess

/**
 * Implementación de [ReminderAccess] sobre las preferencias privadas, el canal
 * de notificaciones y [ReminderScheduler]. Sin lógica: guarda, crea el canal y
 * programa o cancela.
 *
 * - Activar crea el canal (decisión del diseño: no al arrancar la app) y
 *   programa; desactivar cancela. La preferencia se conserva al cerrar la
 *   sesión: quien la tenía activada la quiere seguir teniendo (D7).
 * - `notificationsAllowed` pregunta al sistema en cada llamada, porque la
 *   persona puede revocar el permiso sin salir de la app.
 */
class AndroidReminderAccess(
    context: Context,
    private val preferences: AppPreferences,
) : ReminderAccess {

    private val appContext: Context = context.applicationContext
    private val scheduler = ReminderScheduler(appContext, preferences)

    override fun settings(): ReminderSettings = scheduler.settings()

    override fun setEnabled(enabled: Boolean) {
        preferences.reminderEnabled = enabled
        if (enabled) {
            ReminderNotifier.ensureChannel(appContext)
            scheduler.schedule()
        } else {
            scheduler.cancel()
        }
    }

    override fun setMinuteOfDay(minuteOfDay: Int) {
        preferences.reminderMinuteOfDay = minuteOfDay
        scheduler.schedule()
    }

    override fun notificationsAllowed(): Boolean =
        ReminderNotifier.notificationsAllowed(appContext)
}
