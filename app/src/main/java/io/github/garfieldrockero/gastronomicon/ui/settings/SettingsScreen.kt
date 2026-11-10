package io.github.garfieldrockero.gastronomicon.ui.settings

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.ClearSessionDialog
import io.github.garfieldrockero.gastronomicon.ui.components.GastronomiconTopBar
import io.github.garfieldrockero.gastronomicon.ui.components.LocalHttpSwitch
import io.github.garfieldrockero.gastronomicon.ui.components.SectionTitle
import io.github.garfieldrockero.gastronomicon.ui.components.SecureWindowEffect
import io.github.garfieldrockero.gastronomicon.ui.components.StatusText
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.fieldError
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.LocalExtendedColors
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Ajustes con su [SettingsViewModel]. Es lo único que se conecta al ViewModel;
 * [SettingsScreen] es pura y se previsualiza con datos de ejemplo.
 *
 * El aviso de «sesión borrada» NO pasa por aquí: lo da el propio
 * [SettingsViewModel] (`onSessionCleared`), para que llegue aunque esta pantalla
 * ya no esté. Por eso mientras se borra se bloquea la salida (flecha y Atrás
 * del sistema); sí se puede salir mientras se prueba o se guarda.
 * [onBack] es la flecha «Atrás» de la barra; [onThemeChanged] avisa a la raíz para que aplique el tema elegido al momento.
 */
@Composable
fun SettingsRoute(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onThemeChanged: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // El permiso de notificaciones se puede conceder o revocar en los ajustes del
    // sistema y volver aquí sin cerrar la app: al volver a la pantalla se relee,
    // para que el aviso desaparezca al concederlo y vuelva a verse al revocarlo.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotificationsAllowed() }
    // Atrás del sistema: mientras se borra la sesión se consume sin salir (el
    // manejador sigue activo a propósito; si se desactivara, Atrás cerraría la app).
    val clearing = state.sessionStatus == SessionStatus.Clearing
    BackHandler { if (!clearing) onBack() }
    SettingsScreen(
        state = state,
        onServerChange = viewModel::onServerChange,
        onPortChange = viewModel::onPortChange,
        onAllowLocalHttpChange = viewModel::onAllowLocalHttpChange,
        onTokenChange = viewModel::onTokenChange,
        onTestConnection = viewModel::onTestConnection,
        onSave = viewModel::onSave,
        onThemeChange = { mode ->
            viewModel.onThemeChange(mode)
            onThemeChanged(mode)
        },
        onReminderEnabledChange = viewModel::onReminderEnabledChange,
        onReminderPermissionDenied = viewModel::onReminderPermissionDenied,
        onReminderMinuteChange = viewModel::onReminderMinuteChange,
        onClearSessionClick = viewModel::onClearSessionClick,
        onClearSessionConfirm = viewModel::onClearSessionConfirm,
        onClearSessionDismiss = viewModel::onClearSessionDismiss,
        onRetryLoad = viewModel::onRetryLoad,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Ajustes: servidor, puerto y token, tema, borrar la sesión y «Acerca de».
 * Pantalla aparte con flecha «Atrás» en la barra superior.
 *
 * El token está siempre a ciegas y nunca se muestra el guardado: el campo solo
 * sirve para pegar uno nuevo. La ventana prohíbe capturas mientras la pantalla
 * está abierta, porque se escribe un secreto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onServerChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAllowLocalHttpChange: (Boolean) -> Unit,
    onTokenChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onReminderEnabledChange: (Boolean) -> Unit,
    onReminderPermissionDenied: () -> Unit,
    onReminderMinuteChange: (Int) -> Unit,
    onClearSessionClick: () -> Unit,
    onClearSessionConfirm: () -> Unit,
    onClearSessionDismiss: () -> Unit,
    onRetryLoad: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureWindowEffect()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Con el teclado abierto, el contenido sube y el campo enfocado se ve.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            GastronomiconTopBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = state.sessionStatus != SessionStatus.Clearing) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
        ) {
            when {
                state.loading -> SettingsLoadingRow()
                state.loadFailed -> LoadFailedBlock(onRetry = onRetryLoad)
                else -> ServerSection(
                    state = state,
                    onServerChange = onServerChange,
                    onPortChange = onPortChange,
                    onAllowLocalHttpChange = onAllowLocalHttpChange,
                    onTokenChange = onTokenChange,
                    onTestConnection = onTestConnection,
                    onSave = onSave,
                )
            }

            AppearanceSection(selected = state.themeMode, onThemeChange = onThemeChange)

            if (!state.loading) {
                ReminderSection(
                    reminder = state.reminder,
                    available = !state.loadFailed && state.hasSavedToken,
                    blockedReason = when {
                        state.loadFailed -> R.string.reminder_reason_load_failed
                        !state.hasSavedToken -> R.string.reminder_reason_no_session
                        else -> null
                    },
                    onEnabledChange = onReminderEnabledChange,
                    onPermissionDenied = onReminderPermissionDenied,
                    onMinuteChange = onReminderMinuteChange,
                )
            }

            SessionSection(
                enabled = !state.loading && !state.busy,
                status = state.sessionStatus,
                onClick = onClearSessionClick,
            )

            AboutSection(version = state.appVersion)
        }
    }

    if (state.showClearConfirm) {
        ClearSessionDialog(onConfirm = onClearSessionConfirm, onDismiss = onClearSessionDismiss)
    }
}

@Composable
private fun SettingsLoadingRow() {
    // Un solo nodo para TalkBack: el texto se anuncia al aparecer.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.settings_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** El Keystore no contestó: se dice qué pasa, que no se ha perdido nada y cómo seguir. */
@Composable
private fun LoadFailedBlock(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Text(
            text = stringResource(R.string.settings_load_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.settings_retry))
        }
    }
}

@Composable
private fun ServerSection(
    state: SettingsUiState,
    onServerChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAllowLocalHttpChange: (Boolean) -> Unit,
    onTokenChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
) {
    val busy = state.busy
    // Solo señala dónde mirar; el motivo ya lo ha decidido el núcleo y su texto
    // va en el aviso de abajo.
    val problem = when (val status = state.status) {
        is SettingsStatus.Error -> status.problem
        is SettingsStatus.SaveFailed -> status.problem
        else -> null
    }
    val addressHasError = problem == ConnectionProblem.InvalidAddress ||
        problem == ConnectionProblem.NotLocalAddress
    val tokenHasError = problem == ConnectionProblem.InvalidToken ||
        problem == ConnectionProblem.Unauthorized
    val addressFieldError = stringResource(R.string.setup_field_error_address)
    val tokenFieldError = stringResource(R.string.setup_field_error_token)
    val addressFieldModifier = Modifier
        .fillMaxWidth()
        .fieldError(addressHasError, addressFieldError)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.settings_section_server))

        OutlinedTextField(
            value = state.server,
            onValueChange = onServerChange,
            modifier = addressFieldModifier,
            readOnly = busy,
            label = { Text(stringResource(R.string.setup_server_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (state.allowLocalHttp) {
                            R.string.setup_server_supporting_local
                        } else {
                            R.string.setup_server_supporting
                        },
                    ),
                )
            },
            isError = addressHasError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
        )

        LocalHttpSwitch(
            checked = state.allowLocalHttp,
            enabled = !busy,
            onCheckedChange = onAllowLocalHttpChange,
        )

        OutlinedTextField(
            value = state.port,
            onValueChange = onPortChange,
            modifier = addressFieldModifier,
            readOnly = busy,
            label = { Text(stringResource(R.string.setup_port_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (state.allowLocalHttp) {
                            R.string.setup_port_supporting_local
                        } else {
                            R.string.setup_port_supporting
                        },
                    ),
                )
            },
            isError = addressHasError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
        )

        OutlinedTextField(
            value = state.tokenInput,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth().fieldError(tokenHasError, tokenFieldError),
            readOnly = busy,
            label = { Text(stringResource(R.string.setup_token_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (state.hasSavedToken) {
                            R.string.settings_token_supporting_saved
                        } else {
                            R.string.setup_token_supporting
                        },
                    ),
                )
            },
            isError = tokenHasError,
            singleLine = true,
            // Siempre a ciegas, y el guardado ni siquiera llega a la pantalla.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
        )

        OutlinedButton(
            onClick = onTestConnection,
            enabled = !busy && state.canSave,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            if (state.status == SettingsStatus.Testing) {
                BusyIndicator()
            }
            Text(stringResource(R.string.setup_test_connection))
        }

        // Contenedor siempre presente (con liveRegion): solo cambia el texto,
        // y TalkBack anuncia el resultado de probar o guardar.
        StatusMessage(status = state.status, canSave = state.canSave)

        Button(
            onClick = onSave,
            // Sin cambios no hay nada que guardar (y no se diría «Cambios guardados»).
            enabled = !busy && state.canSave && state.dirty,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            if (state.status == SettingsStatus.Saving) {
                BusyIndicator()
            }
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun StatusMessage(status: SettingsStatus, canSave: Boolean) {
    val text = when (status) {
        SettingsStatus.Testing -> stringResource(R.string.setup_status_testing)
        SettingsStatus.Saving -> stringResource(R.string.setup_status_saving)
        SettingsStatus.Success -> stringResource(R.string.setup_result_success)
        SettingsStatus.Saved -> stringResource(R.string.settings_saved)
        SettingsStatus.StorageError -> stringResource(R.string.settings_error_storage)
        is SettingsStatus.Error -> stringResource(
            if (status.unsupportedPath) {
                R.string.setup_error_unsupported_path
            } else {
                errorTextRes(status.problem)
            },
        )
        is SettingsStatus.SaveFailed -> stringResource(
            R.string.save_failed_format,
            stringResource(
                if (status.unsupportedPath) {
                    R.string.setup_error_unsupported_path
                } else {
                    errorTextRes(status.problem)
                },
            ),
        )
        // En reposo con los botones apagados se dice qué falta.
        SettingsStatus.Idle ->
            if (canSave) "" else stringResource(R.string.setup_hint_fill_fields)
    }
    val color = when (status) {
        SettingsStatus.Success, SettingsStatus.Saved -> LocalExtendedColors.current.success
        SettingsStatus.StorageError, is SettingsStatus.Error, is SettingsStatus.SaveFailed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusText(text = text, color = color)
}

@Composable
private fun AppearanceSection(selected: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SectionTitle(stringResource(R.string.settings_section_theme))
        // selectableGroup: TalkBack dice «1 de 3» y trata las opciones como un grupo.
        Column(modifier = Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onThemeChange(mode) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    // Sin su propio onClick: la fila entera es el objetivo y un solo nodo.
                    RadioButton(selected = isSelected, onClick = null)
                    Text(
                        text = stringResource(themeLabelRes(mode)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// Sin «else»: un modo nuevo no compila hasta tener su texto.
private fun themeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@Composable
private fun SessionSection(enabled: Boolean, status: SessionStatus, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.settings_section_session))
        Text(
            text = stringResource(R.string.settings_clear_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            if (status == SessionStatus.Clearing) {
                BusyIndicator()
            }
            Text(stringResource(R.string.settings_clear_button))
        }
        // Aviso propio, justo bajo el botón y aparte del de guardar: es donde
        // se mira después de pulsar, también cuando la lectura de la sesión
        // falló y la sección del servidor ni se dibuja.
        val text = when (status) {
            SessionStatus.Clearing -> stringResource(R.string.settings_status_clearing)
            SessionStatus.StorageError -> stringResource(R.string.settings_error_clear_storage)
            SessionStatus.Idle, SessionStatus.Cleared -> ""
        }
        StatusText(
            text = text,
            color = if (status == SessionStatus.StorageError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun AboutSection(version: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SectionTitle(stringResource(R.string.settings_section_about))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource(R.string.settings_about_notice),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // Sin número de versión no se enseña la línea.
                if (version.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.settings_about_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_about_license),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// Datos de ejemplo para las previews: servidor falso y reconocible; el token
// guardado ni existe en el estado, solo se sabe que lo hay.
private val ExampleState = SettingsUiState(
    loading = false,
    loadFailed = false,
    server = "mealie.example.com",
    port = "443",
    allowLocalHttp = false,
    tokenInput = "",
    hasSavedToken = true,
    canSave = true,
    dirty = true,
    themeMode = ThemeMode.SYSTEM,
    appVersion = "0.1.0",
    status = SettingsStatus.Idle,
    sessionStatus = SessionStatus.Idle,
    showClearConfirm = false,
    reminder = ReminderUiState(enabled = true, minuteOfDay = 9 * 60),
)

@Composable
private fun ExampleSettingsScreen(state: SettingsUiState, mode: ThemeMode = ThemeMode.SYSTEM) {
    GastronomiconTheme(mode = mode) {
        SettingsScreen(
            state = state,
            onServerChange = {},
            onPortChange = {},
            onAllowLocalHttpChange = {},
            onTokenChange = {},
            onTestConnection = {},
            onSave = {},
            onThemeChange = {},
            onReminderEnabledChange = {},
            onReminderPermissionDenied = {},
            onReminderMinuteChange = {},
            onClearSessionClick = {},
            onClearSessionConfirm = {},
            onClearSessionDismiss = {},
            onRetryLoad = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    ExampleSettingsScreen(ExampleState)
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenPreviewDark() {
    ExampleSettingsScreen(ExampleState.copy(themeMode = ThemeMode.DARK), ThemeMode.DARK)
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun SettingsScreenPreviewLargeText() {
    ExampleSettingsScreen(ExampleState)
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewLoading() {
    ExampleSettingsScreen(ExampleState.copy(loading = true, hasSavedToken = false, canSave = false))
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewLoadFailed() {
    ExampleSettingsScreen(ExampleState.copy(loadFailed = true, canSave = false))
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewTesting() {
    ExampleSettingsScreen(ExampleState.copy(status = SettingsStatus.Testing))
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewSuccess() {
    ExampleSettingsScreen(ExampleState.copy(status = SettingsStatus.Success))
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewSaved() {
    ExampleSettingsScreen(ExampleState.copy(status = SettingsStatus.Saved))
}

// Sin cobertura.
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewNoNetwork() {
    ExampleSettingsScreen(
        ExampleState.copy(status = SettingsStatus.Error(ConnectionProblem.NoNetwork)),
    )
}

// Sesión caducada: el token ya no vale.
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewUnauthorized() {
    ExampleSettingsScreen(
        ExampleState.copy(status = SettingsStatus.Error(ConnectionProblem.Unauthorized)),
    )
}

// Dirección con carpeta: el aviso propio dice que Mealie va en la raíz.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenPreviewUnsupportedPath() {
    ExampleSettingsScreen(
        ExampleState.copy(
            server = "mealie.example.com/mealie",
            status = SettingsStatus.Error(
                problem = ConnectionProblem.InvalidAddress,
                unsupportedPath = true,
            ),
        ),
    )
}

// «Guardar» ha fallado porque el servidor no contesta.
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewSaveFailed() {
    ExampleSettingsScreen(
        ExampleState.copy(status = SettingsStatus.SaveFailed(ConnectionProblem.ServerNotResponding)),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewStorageError() {
    ExampleSettingsScreen(ExampleState.copy(status = SettingsStatus.StorageError))
}

// Sin sesión guardada y con la red local activada.
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewLocalHttp() {
    ExampleSettingsScreen(
        ExampleState.copy(
            server = "192.168.1.50",
            port = "9000",
            allowLocalHttp = true,
            hasSavedToken = false,
            canSave = false,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewClearDialog() {
    ExampleSettingsScreen(ExampleState.copy(showClearConfirm = true))
}

// Borrando la sesión: aviso y rueda bajo el botón.
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewClearing() {
    ExampleSettingsScreen(ExampleState.copy(sessionStatus = SessionStatus.Clearing))
}

// El borrado falló con la lectura también fallida: el aviso sale bajo «Borrar la sesión».
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewClearFailed() {
    ExampleSettingsScreen(
        ExampleState.copy(loadFailed = true, canSave = false, sessionStatus = SessionStatus.StorageError),
    )
}

// Sin cambios: «Guardar cambios» apagado.
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewNoChanges() {
    ExampleSettingsScreen(ExampleState.copy(dirty = false))
}

// Sin número de versión: la línea no aparece.
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewNoVersion() {
    ExampleSettingsScreen(ExampleState.copy(appVersion = ""))
}
