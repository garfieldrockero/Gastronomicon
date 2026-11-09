package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import android.content.SharedPreferences
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSchedule
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSettings
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Tema, modo de red local y ámbito de los datos sin conexión, en
 * `SharedPreferences` privadas y sin cifrar: no son secretos (el token tiene su
 * propio almacén con el Keystore). Archivo distinto del de la sesión. Excluido
 * de las copias de seguridad igual que todo lo demás (`allowBackup=false`).
 */
class SharedPreferencesAppPreferences(context: Context) : AppPreferences {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var themeMode: ThemeMode
        get() {
            val stored = prefs.getString(KEY_THEME, null)
            // Un valor desconocido (versión futura, archivo manipulado) vuelve al sistema.
            return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    override var allowLocalHttp: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_HTTP, false)
        set(value) {
            // Si no se pudo escribir se lanza: `SessionReset` apaga el interruptor
            // ANTES de borrar, y sin esto un fallo de disco pasaría por «apagado»
            // y se borraría la sesión con el interruptor aún encendido.
            if (!trySetAllowLocalHttp(value)) throw IllegalStateException("No se pudo guardar el modo de red.")
        }

    /**
     * Escribe el interruptor y dice si `commit()` lo aceptó. `commit()` y no
     * `apply()`: bloquea hasta que el sistema responde, pero no garantiza por sí
     * solo que el dato llegue al disco si el proceso muere justo después; lo que
     * sí da es el resultado, que aquí se devuelve en vez de ignorarlo.
     */
    fun trySetAllowLocalHttp(value: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_LOCAL_HTTP, value).commit()

    /**
     * Ámbito de los datos sin conexión: solo el hash de `ServerScope`, nunca la
     * URL ni el id de usuario. `commit()` para que el borrado al cerrar la
     * sesión no se quede en el aire (si falla, el resultado se ignora: al no
     * haber ámbito no se ofrece nada offline, que es el lado seguro).
     */
    override var offlineScopeId: String?
        get() = prefs.getString(KEY_OFFLINE_SCOPE, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_OFFLINE_SCOPE) else editor.putString(KEY_OFFLINE_SCOPE, value)
            editor.commit()
        }

    /**
     * Marca de borrado del ámbito pendiente. `commit()` como el id: tiene que
     * estar escrita **antes** de quitar el id, para que un corte a media limpieza
     * se repita al arrancar en vez de dar el borrado por hecho.
     */
    override var offlineScopeClearPending: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_SCOPE_CLEAR, false)
        set(value) {
            val editor = prefs.edit()
            if (value) editor.putBoolean(KEY_OFFLINE_SCOPE_CLEAR, true) else editor.remove(KEY_OFFLINE_SCOPE_CLEAR)
            editor.commit()
        }

    /**
     * Recordatorio activado. No es un secreto: va con el tema, no con el token.
     * `apply()` basta: si el proceso muere justo después, como mucho se pierde un
     * cambio de interruptor que la persona puede repetir.
     */
    override var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()
        }

    /** Minuto del día coaccionado al leer y al escribir: nunca sale de 0..1439. */
    override var reminderMinuteOfDay: Int
        get() = ReminderSettings.coerceMinuteOfDay(
            prefs.getInt(KEY_REMINDER_MINUTE, ReminderSchedule.DEFAULT_MINUTE_OF_DAY),
        )
        set(value) {
            prefs.edit()
                .putInt(KEY_REMINDER_MINUTE, ReminderSettings.coerceMinuteOfDay(value))
                .apply()
        }

    /** Fecha de la última vez que se avisó; una marca ilegible se lee como «nunca». */
    override var reminderLastNotifiedDate: String?
        get() = prefs.getString(KEY_REMINDER_LAST_NOTIFIED, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_REMINDER_LAST_NOTIFIED)
            } else {
                editor.putString(KEY_REMINDER_LAST_NOTIFIED, value)
            }
            editor.apply()
        }

    private companion object {
        const val PREFS_NAME = "gastronomicon_preferencias"
        const val KEY_THEME = "tema"
        const val KEY_LOCAL_HTTP = "http_local"
        const val KEY_OFFLINE_SCOPE = "ambito_offline"
        const val KEY_OFFLINE_SCOPE_CLEAR = "ambito_offline_pendiente"
        const val KEY_REMINDER_ENABLED = "recordatorio_activo"
        const val KEY_REMINDER_MINUTE = "recordatorio_minuto"
        const val KEY_REMINDER_LAST_NOTIFIED = "recordatorio_ultimo_aviso"
    }
}
