package io.github.garfieldrockero.gastronomicon.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.NotificationManagerCompat
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.ui.components.SectionTitle
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Sección «Recordatorio» de Ajustes: interruptor, hora del aviso, estado del
 * permiso de notificaciones y una línea que avisa de que puede llegar unos
 * minutos tarde (decisión D11: sin alarma exacta).
 *
 * Reglas de la persona que se ven aquí:
 * - **D6**: si el permiso de notificaciones se deniega, el interruptor **no** se
 *   queda encendido; se explica y se ofrece el botón a los ajustes del sistema.
 *   El diálogo del permiso se pide al activar, nunca al arrancar la app.
 * - **D7**: sin sesión guardada no se puede activar: el aviso lee la copia del
 *   plan, y sin servidor no hay copia.
 *
 * Accesibilidad: la fila del interruptor entera es el objetivo (Role.Switch; el
 * estado lo dice el propio [Switch], sin repetirlo con `stateDescription`), la
 * fila de la hora se lee como un ajuste con su valor a la derecha, todo mide al
 * menos [Sizes.MinTouchTarget] y el aviso del permiso vive en un contenedor con
 * `liveRegion` para que TalkBack lo anuncie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSection(
    reminder: ReminderUiState,
    available: Boolean,
    @StringRes blockedReason: Int?,
    onEnabledChange: (Boolean) -> Unit,
    onPermissionDenied: () -> Unit,
    onMinuteChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    // La preview no tiene registro de resultados de actividad: sin este control,
    // `rememberLauncherForActivityResult` reventaría al pintar los ejemplos.
    val registryOwner = LocalActivityResultRegistryOwner.current
    val permissionLauncher = if (registryOwner != null) {
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) onEnabledChange(true) else onPermissionDenied()
        }
    } else {
        null
    }

    // Se comprueba al pulsar, no con el estado del ViewModel: el permiso pudo
    // cambiar en el sistema mientras Ajustes estaba abierto.
    fun tryEnable() {
        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        when {
            needsRuntimePermission && permissionLauncher != null ->
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> onPermissionDenied()
            else -> onEnabledChange(true)
        }
    }

    val switchEnabled = available
    val checked = available && reminder.enabled

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        // El mismo título de sección que el resto de Ajustes (marcado como encabezado
        // dentro del componente): antes se pintaba aquí a mano y se distinguía del
        // resto de la pantalla.
        SectionTitle(text = stringResource(R.string.reminder_section_title))

        // Fila del interruptor: un solo nodo para TalkBack. El estado lo anuncia el
        // Switch (Role.Switch en `toggleable`): repetirlo con `stateDescription`
        // lo diría dos veces.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget)
                .toggleable(
                    value = checked,
                    enabled = switchEnabled,
                    role = Role.Switch,
                    onValueChange = { wanted -> if (wanted) tryEnable() else onEnabledChange(false) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.reminder_switch_label),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!available && blockedReason != null) {
                    Text(
                        text = stringResource(blockedReason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Sin su propio onClick: la fila entera manda y TalkBack ve un nodo.
            Switch(checked = checked, onCheckedChange = null, enabled = switchEnabled)
        }

        // Fila de la hora con aspecto de ajuste: nombre a la izquierda y la hora a
        // la derecha, que es donde se mira. Toda la fila es pulsable.
        val timeDescription = stringResource(R.string.reminder_time_description, reminder.timeText)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget)
                .clickable(
                    enabled = available,
                    role = Role.Button,
                    onClick = { showTimePicker = true },
                )
                .semantics { contentDescription = timeDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                text = stringResource(R.string.reminder_time_label),
                style = MaterialTheme.typography.bodyLarge,
                color = if (available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = reminder.timeText,
                style = MaterialTheme.typography.titleMedium,
                color = if (available) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // Una fila apagada sin explicación parece rota: se dice por qué no se puede
        // tocar (el motivo concreto ya está bajo el interruptor).
        if (!available) {
            Text(
                text = stringResource(R.string.reminder_time_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.reminder_late_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Contenedor estable (liveRegion): TalkBack anuncia el aviso cuando
        // aparece, al denegar el permiso o al revocarlo en el sistema.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            if (available && !reminder.notificationsAllowed) {
                PermissionWarning(
                    onOpenNotificationSettings = {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        try {
                            context.startActivity(intent)
                        } catch (ignored: ActivityNotFoundException) {
                            // El móvil no tiene esa pantalla: el texto ya dice dónde mirar.
                        }
                    },
                )
            }
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = reminder.minuteOfDay / 60,
            initialMinute = reminder.minuteOfDay % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.reminder_time_dialog_title)) },
            text = { TimePickerContent(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMinuteChange(pickerState.hour * 60 + pickerState.minute)
                        showTimePicker = false
                    },
                ) {
                    // «Guardar» ya existe en la app: no se inventa otra clave.
                    Text(stringResource(R.string.setup_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    // «Cancelar», igual: la misma palabra que en el resto de diálogos.
                    Text(stringResource(R.string.plan_cancel))
                }
            },
        )
    }
}

/**
 * El reloj de aguja mide unos 256 dp y no cabe en pantallas bajas (en horizontal,
 * o con el texto del sistema muy grande). Ahí se usa la entrada de texto, que es
 * más baja; en cualquier caso el contenido se puede desplazar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerContent(state: TimePickerState) {
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < 640 || configuration.fontScale > 1.3f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (compact) {
            TimeInput(state = state)
        } else {
            TimePicker(state = state)
        }
    }
}

@Composable
private fun PermissionWarning(onOpenNotificationSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource(R.string.reminder_permission_denied),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onOpenNotificationSettings,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.reminder_open_notification_settings))
            }
        }
    }
}

@Composable
private fun ExampleReminder(
    reminder: ReminderUiState,
    available: Boolean = true,
    @StringRes blockedReason: Int? = null,
    mode: ThemeMode = ThemeMode.SYSTEM,
) {
    GastronomiconTheme(mode = mode) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ReminderSection(
                reminder = reminder,
                available = available,
                blockedReason = blockedReason,
                onEnabledChange = {},
                onPermissionDenied = {},
                onMinuteChange = {},
                modifier = Modifier.padding(Spacing.large),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ReminderSectionOffPreview() {
    ExampleReminder(ReminderUiState())
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReminderSectionOnDarkPreview() {
    ExampleReminder(
        reminder = ReminderUiState(enabled = true, minuteOfDay = 9 * 60),
        mode = ThemeMode.DARK,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ReminderSectionDeniedPreview() {
    ExampleReminder(
        reminder = ReminderUiState(
            enabled = false,
            notificationsAllowed = false,
            permissionDenied = true,
        ),
    )
}

/**
 * Segundo paso del mismo recorrido que [ReminderSectionDeniedPreview]: se denegó
 * el permiso, se concedió en los ajustes del sistema y se volvió a activar. Aquí
 * el interruptor está encendido y el aviso ya no aparece (lo que se ve al volver
 * a la pantalla, porque el ViewModel relee el permiso en ON_RESUME, y también al
 * activar).
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ReminderSectionGrantedPreview() {
    ExampleReminder(
        reminder = ReminderUiState(
            enabled = true,
            minuteOfDay = 9 * 60,
            notificationsAllowed = true,
            permissionDenied = false,
        ),
    )
}

/**
 * El permiso se revocó desde el sistema con el interruptor encendido: el aviso
 * vuelve a verse y el interruptor conserva la preferencia (la persona no lo ha
 * apagado). Para que el aviso llegue a verse, `SettingsViewModel` relee el
 * permiso al volver a la pantalla.
 */
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReminderSectionRevokedPreview() {
    ExampleReminder(
        reminder = ReminderUiState(
            enabled = true,
            minuteOfDay = 9 * 60,
            notificationsAllowed = false,
        ),
        mode = ThemeMode.DARK,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ReminderSectionNoSessionPreview() {
    ExampleReminder(
        reminder = ReminderUiState(),
        available = false,
        blockedReason = R.string.reminder_reason_no_session,
    )
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun ReminderSectionLargeTextPreview() {
    ExampleReminder(
        reminder = ReminderUiState(enabled = true, minuteOfDay = 21 * 60 + 5),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ReminderSectionTimeDialogPreview() {
    GastronomiconTheme(mode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val pickerState = rememberTimePickerState(
                initialHour = 9,
                initialMinute = 0,
                is24Hour = true,
            )
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.reminder_time_dialog_title)) },
                text = { TimePickerContent(state = pickerState) },
                confirmButton = {
                    TextButton(onClick = {}) { Text(stringResource(R.string.setup_save)) }
                },
                dismissButton = {
                    TextButton(onClick = {}) { Text(stringResource(R.string.plan_cancel)) }
                },
            )
        }
    }
}
