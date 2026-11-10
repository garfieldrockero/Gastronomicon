package io.github.garfieldrockero.gastronomicon.ui.settings

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSchedule
import io.github.garfieldrockero.gastronomicon.ui.components.maskedServer
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Estado que pinta Ajustes. Sin lógica de negocio: lo calcula
 * [SettingsViewModel] apoyándose en `core/`.
 *
 * El token guardado NO está aquí: el estado solo sabe que existe
 * ([hasSavedToken]). [tokenInput] es únicamente lo que la persona escribe o
 * pega para cambiarlo; vacío significa «conservar el actual». Nunca va en
 * `rememberSaveable` ni `SavedStateHandle`, ni en logs, y `toString()` lo
 * enmascara igual que el servidor.
 *
 * [canSave] lo calcula quien posee el estado; la pantalla solo lo obedece.
 */
data class SettingsUiState(
    /** Leyendo la sesión guardada (descifrar puede tardar un instante). */
    val loading: Boolean,
    /** El Keystore no contestó; se puede reintentar sin perder nada. */
    val loadFailed: Boolean,
    val server: String,
    val port: String,
    val allowLocalHttp: Boolean,
    val tokenInput: String,
    val hasSavedToken: Boolean,
    val canSave: Boolean,
    /** Hay algo distinto de lo guardado (o un token nuevo escrito): solo entonces «Guardar cambios» se enciende. */
    val dirty: Boolean,
    val themeMode: ThemeMode,
    val appVersion: String,
    /** Marcha de probar y guardar; el aviso va bajo esos botones. */
    val status: SettingsStatus,
    /** Marcha de borrar la sesión; su aviso va aparte, bajo «Borrar la sesión». */
    val sessionStatus: SessionStatus,
    val showClearConfirm: Boolean,
    /** Recordatorio del planificador: interruptor, hora y estado del permiso. */
    val reminder: ReminderUiState = ReminderUiState(),
) {
    /** Hay una acción en marcha: los campos y los botones se bloquean. */
    val busy: Boolean
        get() = status == SettingsStatus.Testing || status == SettingsStatus.Saving ||
            sessionStatus == SessionStatus.Clearing

    override fun toString(): String =
        "SettingsUiState(loading=$loading, loadFailed=$loadFailed, server=${maskedServer(server)}, " +
            "port=$port, allowLocalHttp=$allowLocalHttp, tokenInput=***, " +
            "hasSavedToken=$hasSavedToken, canSave=$canSave, dirty=$dirty, themeMode=$themeMode, " +
            "status=$status, sessionStatus=$sessionStatus, showClearConfirm=$showClearConfirm, " +
            "reminder=$reminder)"
}

/** Marcha de la pantalla de Ajustes. */
sealed interface SettingsStatus {
    data object Idle : SettingsStatus
    data object Testing : SettingsStatus
    data object Success : SettingsStatus
    data object Saving : SettingsStatus
    data object Saved : SettingsStatus

    /** El almacén seguro del móvil ha fallado al leer, guardar o borrar. */
    data object StorageError : SettingsStatus

    /**
     * «Guardar» no ha guardado: [problem] es el motivo, distinto del aviso de «Probar».
     *
     * [unsupportedPath] marca la dirección con carpeta (`https://host/mealie`): el
     * núcleo la cuenta como [ConnectionProblem.InvalidAddress], pero el texto
     * genérico no dice qué sobra. Se distingue por el tipo de la excepción del
     * núcleo, nunca por su mensaje.
     */
    data class SaveFailed(
        val problem: ConnectionProblem,
        val unsupportedPath: Boolean = false,
    ) : SettingsStatus

    /** Motivo cerrado decidido por el núcleo; la pantalla solo le pone texto. */
    data class Error(
        val problem: ConnectionProblem,
        val unsupportedPath: Boolean = false,
    ) : SettingsStatus
}

/** Marcha del borrado de la sesión, separada de la de guardar para que cada aviso salga junto a su botón. */
sealed interface SessionStatus {
    data object Idle : SessionStatus
    data object Clearing : SessionStatus

    /** El almacén seguro del móvil ha fallado al borrar. */
    data object StorageError : SessionStatus

    /** La sesión ya está borrada; quien navega debe llevar a la primera configuración. */
    data object Cleared : SessionStatus
}

/**
 * Estado de la sección «Recordatorio» de Ajustes. Sin lógica: el minuto del día
 * y su texto («09:05») los coacciona y compone `core/reminder`.
 *
 * - [enabled] es lo que se pinta en el interruptor; sin sesión se pinta apagado
 *   aunque la preferencia siga guardada (decisión D7: se conserva, no se avisa).
 * - [notificationsAllowed] es lo que dice el sistema ahora mismo. Si es `false`,
 *   el interruptor no puede quedarse encendido y se enseña el aviso con el botón
 *   a los ajustes del sistema (decisión D6).
 * - [permissionDenied] distingue «acaba de denegar el permiso» de «las
 *   notificaciones estaban bloqueadas de antes»; la pantalla usa el mismo aviso.
 */
data class ReminderUiState(
    val enabled: Boolean = false,
    val minuteOfDay: Int = ReminderSchedule.DEFAULT_MINUTE_OF_DAY,
    val notificationsAllowed: Boolean = true,
    val permissionDenied: Boolean = false,
) {
    /** «09:00», calculado por el núcleo. */
    val timeText: String get() = ReminderSchedule.textOf(minuteOfDay)

    override fun toString(): String =
        "ReminderUiState(enabled=$enabled, minuteOfDay=$minuteOfDay, " +
            "notificationsAllowed=$notificationsAllowed, permissionDenied=$permissionDenied)"
}
