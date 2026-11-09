package io.github.garfieldrockero.gastronomicon.ui.settings

import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSchedule
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSettings
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Preferencias que no son secretas: el tema, el interruptor de la red local y el
 * ámbito de los datos sin conexión. Van en `SharedPreferences` normales; el token
 * NUNCA pasa por aquí (tiene su propio almacén cifrado, `SessionStore`).
 *
 * Es una interfaz para poder usar una versión en memoria en previews y
 * pruebas, y para que la raíz de la app lea el tema con la misma fuente que
 * Ajustes.
 */
interface AppPreferences : io.github.garfieldrockero.gastronomicon.core.session.LocalHttpSwitch {
    var themeMode: ThemeMode

    /**
     * Interruptor «Conectar sin HTTPS». Desactivado de inicio. Viene de
     * `LocalHttpSwitch` (core/) para que `SessionReset.clear` lo reinicie al
     * borrar la sesión.
     */
    override var allowLocalHttp: Boolean

    /**
     * Ámbito de los datos sin conexión, ya hasheado por `ServerScope`: **no**
     * lleva la URL, ni el id de usuario, ni el token. Se escribe al comprobar la
     * conexión y guardar la sesión, y se borra con ella (ver
     * `platform/OfflineScopeStore`). Null = nada sin conexión (falla cerrado).
     */
    var offlineScopeId: String?

    /**
     * Hay que borrar [offlineScopeId] y aún no se ha podido (un `commit()` que no
     * cuaja, el proceso que muere a media limpieza). Se escribe **antes** de
     * quitar el id y se reintenta al arrancar: mientras esté puesta, la app no
     * sirve el ámbito guardado, aunque el id siga en el archivo.
     */
    var offlineScopeClearPending: Boolean

    /**
     * Recordatorio del planificador activado (decisión D7: la preferencia se
     * conserva al cerrar o cambiar la sesión; sin sesión no hay aviso).
     */
    var reminderEnabled: Boolean

    /**
     * Minuto del día del aviso, 0..1439. Se lee **coaccionado** con
     * [ReminderSettings.coerceMinuteOfDay]: un valor manipulado se pinta como el
     * de por defecto (09:00), igual que un `themeMode` desconocido.
     */
    var reminderMinuteOfDay: Int

    /**
     * Última fecha (`yyyy-MM-dd`) en la que se avisó, para no repetir el aviso el
     * mismo día. Se guarda como texto y se lee con
     * [ReminderSettings.parseDate]: una marca ilegible se trata como «nunca».
     */
    var reminderLastNotifiedDate: String?
}

/** Preferencias en memoria para previews y pruebas: no persisten nada. */
class InMemoryAppPreferences(
    override var themeMode: ThemeMode = ThemeMode.SYSTEM,
    override var allowLocalHttp: Boolean = false,
) : AppPreferences {
    override var offlineScopeId: String? = null
    override var offlineScopeClearPending: Boolean = false
    override var reminderEnabled: Boolean = false

    private var reminderMinute: Int = ReminderSchedule.DEFAULT_MINUTE_OF_DAY

    override var reminderMinuteOfDay: Int
        get() = reminderMinute
        set(value) {
            reminderMinute = ReminderSettings.coerceMinuteOfDay(value)
        }

    override var reminderLastNotifiedDate: String? = null
}
