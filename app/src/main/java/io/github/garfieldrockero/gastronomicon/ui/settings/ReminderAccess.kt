package io.github.garfieldrockero.gastronomicon.ui.settings

import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSettings

/**
 * Lo que Ajustes necesita del recordatorio del planificador, sin Android a la
 * vista: la implementación real está en `platform/AndroidReminderAccess`.
 *
 * Así la pantalla y el ViewModel se pueden previsualizar y probar con una
 * versión en memoria, y en `core/` no entra nada de plataforma.
 *
 * Los ajustes se devuelven ya coaccionados por `core/` ([ReminderSettings]): un
 * minuto del día manipulado no llega nunca a la alarma.
 */
interface ReminderAccess {

    /** Ajustes guardados, ya validados. */
    fun settings(): ReminderSettings

    /**
     * Activa o desactiva el aviso. Al activar crea el canal de notificaciones y
     * programa la alarma; al desactivar la cancela. La preferencia se conserva
     * aunque no haya sesión (decisión D7).
     */
    fun setEnabled(enabled: Boolean)

    /** Cambia la hora (minuto del día) y reprograma si está activo. */
    fun setMinuteOfDay(minuteOfDay: Int)

    /** ¿El sistema deja publicar notificaciones de la app ahora mismo? */
    fun notificationsAllowed(): Boolean
}

/** Versión en memoria para previews y pruebas: no programa nada. */
class InMemoryReminderAccess(
    private var current: ReminderSettings = ReminderSettings.DEFAULT,
    private val allowed: Boolean = true,
) : ReminderAccess {

    override fun settings(): ReminderSettings = current

    override fun setEnabled(enabled: Boolean) {
        current = current.copy(enabled = enabled)
    }

    override fun setMinuteOfDay(minuteOfDay: Int) {
        current = current.copy(minuteOfDay = ReminderSettings.coerceMinuteOfDay(minuteOfDay))
    }

    override fun notificationsAllowed(): Boolean = allowed
}
