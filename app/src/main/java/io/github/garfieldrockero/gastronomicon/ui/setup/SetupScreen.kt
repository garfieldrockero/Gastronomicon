package io.github.garfieldrockero.gastronomicon.ui.setup

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.LocalHttpSwitch
import io.github.garfieldrockero.gastronomicon.ui.components.SectionTitle
import io.github.garfieldrockero.gastronomicon.ui.components.SecureWindowEffect
import io.github.garfieldrockero.gastronomicon.ui.components.StatusText
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.fieldError
import io.github.garfieldrockero.gastronomicon.ui.components.maskedServer
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.LocalExtendedColors
import io.github.garfieldrockero.gastronomicon.ui.theme.Motion
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode
import io.github.garfieldrockero.gastronomicon.ui.theme.motionTween
import io.github.garfieldrockero.gastronomicon.ui.theme.rememberAnimationsEnabled

/**
 * Estado que pinta la primera configuración. No lleva lógica de validación:
 * si el servidor o el puerto están mal, quien llame lo cuenta en
 * [SetupStatus.Error] con el [ConnectionProblem] que ya ha decidido el núcleo.
 *
 * Contrato para quien gestione este estado:
 * - [canSave] lo calcula quien posee el estado, no la pantalla: es la única
 *   señal de si «Guardar» se puede pulsar. La pantalla solo lo obedece.
 * - El token es un secreto: nunca en `rememberSaveable` ni `SavedStateHandle`
 *   (sobreviven al cierre del proceso y acaban en disco), ni en logs ni en
 *   capturas. Por eso `toString()` lo enmascara, el servidor deja solo su
 *   último dominio y la pantalla no lo enseña nunca completo ni deja
 *   fotografiarla ([io.github.garfieldrockero.gastronomicon.ui.components.SecureWindowEffect]).
 * - Cualquier edición de servidor, puerto o token devuelve `Success`/`Error`
 *   a `Idle` y recalcula [canSave], porque el resultado anterior deja de
 *   corresponder a lo escrito. Lo hace quien gestiona el estado.
 * - Al cambiar [allowLocalHttp] quien posee el estado también vuelve a `Idle`,
 *   recalcula [canSave] y, si el puerto sigue en el valor sugerido del modo
 *   anterior, lo cambia al de `ServerAddress.defaultPortFor(nuevoValor)` (443
 *   o 9000). La pantalla no calcula puertos.
 */
data class SetupUiState(
    val server: String,
    val port: String,
    val token: String,
    val allowLocalHttp: Boolean,
    val canSave: Boolean,
    val status: SetupStatus,
) {
    // El toString() que genera «data class» imprimiría el token tal cual, y
    // los estados de la UI acaban en logs y trazas de depuración. Del servidor
    // basta el último tramo para saber de qué instalación se habla.
    override fun toString(): String =
        "SetupUiState(server=${maskedServer(server)}, port=$port, token=***, " +
            "allowLocalHttp=$allowLocalHttp, canSave=$canSave, status=$status)"
}

/** Marcha de la pantalla de configuración. */
sealed interface SetupStatus {
    data object Idle : SetupStatus
    data object Testing : SetupStatus
    data object Success : SetupStatus
    data object Saving : SetupStatus

    /** Sesión guardada: quien navega debe llevar a la pantalla de Recetas. */
    data object Saved : SetupStatus

    /**
     * Fallo de la última acción. [problem] es un motivo cerrado, no un texto:
     * lo decide el núcleo y la pantalla solo le pone el mensaje de
     * `strings.xml`, así que nunca se cuela una excepción, la respuesta cruda
     * del servidor ni nada de lo que escribió la persona.
     *
     * [unsupportedPath] es el caso concreto de una dirección con carpeta
     * (`https://host/mealie`): el núcleo lo cuenta como
     * [ConnectionProblem.InvalidAddress], pero el mensaje genérico («revisa el
     * servidor y el puerto») no explica qué sobra. La pantalla usa entonces el
     * texto propio de la subruta.
     */
    data class Error(
        val problem: ConnectionProblem,
        val unsupportedPath: Boolean = false,
    ) : SetupStatus

    /** «Guardar» no ha guardado: [problem] es el motivo, distinto del aviso de «Probar». */
    data class SaveFailed(
        val problem: ConnectionProblem,
        val unsupportedPath: Boolean = false,
    ) : SetupStatus

    /** El almacén seguro del móvil ha fallado al guardar. */
    data object StorageError : SetupStatus
}

/**
 * Primera configuración: servidor, puerto y token de Mealie. Se muestra solo
 * cuando aún no hay sesión guardada; hasta entonces la app no habla con nadie.
 * Mientras se prueba la conexión o se guarda, los campos pasan a solo lectura
 * (lo escrito se sigue leyendo bien, sin el gris de deshabilitado) y los
 * botones se bloquean.
 *
 * El token se escribe a ciegas y la pantalla prohíbe las capturas mientras
 * está abierta: no hay forma de verlo entero ni de fotografiarlo.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    onServerChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onAllowLocalHttpChange: (Boolean) -> Unit,
    onTokenChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Sin capturas, sin grabación y sin miniatura en Recientes mientras haya un
    // token escrito aquí.
    SecureWindowEffect()

    val busy = state.status == SetupStatus.Testing || state.status == SetupStatus.Saving ||
        state.status == SetupStatus.Saved
    // Solo señala dónde mirar; el motivo lo ha decidido ya el núcleo y el
    // mensaje va en el aviso de abajo.
    val problem = when (val status = state.status) {
        is SetupStatus.Error -> status.problem
        is SetupStatus.SaveFailed -> status.problem
        else -> null
    }
    // NotLocalAddress marca servidor y puerto como InvalidAddress; en cambio
    // LocalHttpNeedsHomeNetwork no marca nada: la dirección está bien, falla la red.
    val addressHasError = problem == ConnectionProblem.InvalidAddress ||
        problem == ConnectionProblem.NotLocalAddress
    val tokenHasError = problem == ConnectionProblem.InvalidToken ||
        problem == ConnectionProblem.Unauthorized
    // isError por sí solo hace que TalkBack diga un «entrada no válida»
    // genérico; esta frase corta dice qué revisar. El motivo largo solo va en
    // el aviso con liveRegion, para no oírlo dos veces.
    val addressFieldError = stringResource(R.string.setup_field_error_address)
    val tokenFieldError = stringResource(R.string.setup_field_error_token)
    val addressFieldModifier = Modifier
        .fillMaxWidth()
        .fieldError(addressHasError, addressFieldError)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            // Título y explicación juntos (8 dp): se leen como una sola entrada a la
            // pantalla, y lo que viene después es el formulario. El título sale del
            // mismo componente que los de Ajustes (con su tamaño propio) y el orden
            // —título, campos, interruptor, probar, aviso, guardar— es el mismo que
            // en Ajustes → «Servidor»: si uno cambia, el otro se mira.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SectionTitle(
                    text = stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.setup_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                            }
                        )
                    )
                },
                // El texto de ayuda se queda: el motivo ya está en el aviso.
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

            // El interruptor va justo debajo del servidor y no junto al token:
            // es lo que cambia la ayuda del servidor y el puerto sugerido, así
            // que se decide antes de rellenar lo que viene después, y el aviso
            // queda pegado a la dirección que lo provoca.
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
                            }
                        )
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
                value = state.token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth().fieldError(tokenHasError, tokenFieldError),
                readOnly = busy,
                label = { Text(stringResource(R.string.setup_token_label)) },
                supportingText = { Text(stringResource(R.string.setup_token_supporting)) },
                isError = tokenHasError,
                singleLine = true,
                // Siempre a ciegas: el token no se enseña nunca completo.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(
                    // «Listo» cierra el teclado y ya está: guardar lo decide
                    // quien usa la app, no el teclado.
                    onDone = { keyboardController?.hide() },
                ),
            )

            // Botones a lo ancho y apilados. «Probar conexión» solo comprueba;
            // «Guardar» también prueba antes de guardar, y como es el objetivo
            // queda más abajo, al alcance del pulgar de quien usa el móvil con
            // una sola mano.
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !busy && state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (state.status == SetupStatus.Testing) {
                    BusyIndicator()
                }
                Text(stringResource(R.string.setup_test_connection))
            }

            // El mensaje ocupa sitio siempre, aunque esté vacío: es el contenedor
            // con liveRegion del que habla StatusMessage.
            StatusMessage(status = state.status, canSave = state.canSave)

            // «Guardar» depende de [SetupUiState.canSave], que calcula quien
            // posee el estado: la pantalla no decide si los datos valen.
            Button(
                onClick = onSave,
                enabled = !busy && state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (state.status == SetupStatus.Saving) {
                    BusyIndicator()
                }
                Text(stringResource(R.string.setup_save))
            }
            // Sin notas debajo de «Guardar»: el «solo HTTPS» va en la ayuda del
            // servidor (y el aviso del interruptor, en modo local) y el aviso de «cliente no oficial», en Ajustes → «Acerca de».
        }
    }
}

/** Aviso de marcha o resultado de la primera configuración. */
@Composable
private fun StatusMessage(status: SetupStatus, canSave: Boolean) {
    val text = when (status) {
        SetupStatus.Testing -> stringResource(R.string.setup_status_testing)
        SetupStatus.Saving -> stringResource(R.string.setup_status_saving)
        SetupStatus.Success -> stringResource(R.string.setup_result_success)
        is SetupStatus.Error -> stringResource(
            if (status.unsupportedPath) {
                R.string.setup_error_unsupported_path
            } else {
                errorTextRes(status.problem)
            },
        )
        is SetupStatus.SaveFailed -> stringResource(
            R.string.save_failed_format,
            stringResource(
                if (status.unsupportedPath) {
                    R.string.setup_error_unsupported_path
                } else {
                    errorTextRes(status.problem)
                },
            ),
        )
        SetupStatus.StorageError -> stringResource(R.string.settings_error_storage)
        // En reposo con «Guardar» apagado se dice qué falta; si ya se puede
        // guardar, no hay nada que avisar.
        SetupStatus.Idle -> if (canSave) "" else stringResource(R.string.setup_hint_fill_fields)
        // Ya guardado: quien navega se lleva a la persona a la pantalla siguiente.
        SetupStatus.Saved -> ""
    }
    val animations = rememberAnimationsEnabled()
    val color by animateColorAsState(
        targetValue = when (status) {
            SetupStatus.Success -> LocalExtendedColors.current.success
            is SetupStatus.Error, is SetupStatus.SaveFailed, SetupStatus.StorageError -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        // El aviso cambia de texto y de color a la vez: el color llega en 120 ms para
        // que el ojo lo registre sin un salto, y con las animaciones del sistema
        // quitadas cambia de golpe. El nodo vivo de [StatusText] no se toca.
        animationSpec = motionTween(animations, Motion.ShortMillis),
        label = "setup-status-color",
    )
    StatusText(text = text, color = color)
}

// Datos de ejemplo para las previews: servidor y token falsos y reconocibles,
// nunca unos reales.
private val ExampleState = SetupUiState(
    server = "mealie.example.com",
    port = "443",
    token = "token-de-ejemplo-1234",
    allowLocalHttp = false,
    canSave = true,
    status = SetupStatus.Idle,
)

/** Monta la pantalla con datos de ejemplo y callbacks que no hacen nada. */
@Composable
private fun ExampleSetupScreen(state: SetupUiState) {
    SetupScreen(
        state = state,
        onServerChange = {},
        onPortChange = {},
        onAllowLocalHttpChange = {},
        onTokenChange = {},
        onTestConnection = {},
        onSave = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
    GastronomiconTheme { ExampleSetupScreen(ExampleState) }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SetupScreenPreviewDark() {
    GastronomiconTheme(mode = ThemeMode.DARK) { ExampleSetupScreen(ExampleState) }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun SetupScreenPreviewLargeText() {
    GastronomiconTheme { ExampleSetupScreen(ExampleState) }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewError() {
    GastronomiconTheme {
        ExampleSetupScreen(
            ExampleState.copy(status = SetupStatus.Error(ConnectionProblem.NoNetwork))
        )
    }
}

// Sesión caducada: el aviso que más se ve cuando el token deja de valer.
@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewUnauthorized() {
    GastronomiconTheme {
        ExampleSetupScreen(
            ExampleState.copy(status = SetupStatus.Error(ConnectionProblem.Unauthorized))
        )
    }
}

// Servidor y puerto marcados en rojo: la dirección no lleva a un Mealie.
@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewInvalidAddress() {
    GastronomiconTheme {
        ExampleSetupScreen(
            ExampleState.copy(status = SetupStatus.Error(ConnectionProblem.InvalidAddress))
        )
    }
}

// Una carpeta al final de la dirección: el aviso propio dice qué sobra.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SetupScreenPreviewUnsupportedPath() {
    GastronomiconTheme {
        ExampleSetupScreen(
            ExampleState.copy(
                server = "mealie.example.com/mealie",
                status = SetupStatus.Error(
                    problem = ConnectionProblem.InvalidAddress,
                    unsupportedPath = true,
                ),
            )
        )
    }
}

// Sin HTTPS con un dominio: servidor y puerto en rojo y la salida en el texto.
@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewNotLocalAddress() {
    GastronomiconTheme {
        ExampleSetupScreen(
            LocalExampleState.copy(
                server = "mealie.example.com",
                status = SetupStatus.Error(ConnectionProblem.NotLocalAddress),
            )
        )
    }
}

// Sin HTTPS con datos móviles: la dirección es válida, así que no se marca ningún campo.
@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewLocalHttpNeedsHomeNetwork() {
    GastronomiconTheme {
        ExampleSetupScreen(
            LocalExampleState.copy(
                status = SetupStatus.Error(ConnectionProblem.LocalHttpNeedsHomeNetwork)
            )
        )
    }
}

// Solo el token marcado: lo pegado no tiene forma de token y ni se ha enviado.
@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewInvalidToken() {
    GastronomiconTheme {
        ExampleSetupScreen(
            ExampleState.copy(status = SetupStatus.Error(ConnectionProblem.InvalidToken))
        )
    }
}

// «Guardar» ha fallado: el servidor no contesta.
@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewSaveFailed() {
    GastronomiconTheme {
        ExampleSetupScreen(
            ExampleState.copy(status = SetupStatus.SaveFailed(ConnectionProblem.ServerNotResponding))
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewStorageError() {
    GastronomiconTheme { ExampleSetupScreen(ExampleState.copy(status = SetupStatus.StorageError)) }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewTesting() {
    GastronomiconTheme {
        ExampleSetupScreen(ExampleState.copy(status = SetupStatus.Testing))
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewSuccess() {
    GastronomiconTheme {
        ExampleSetupScreen(ExampleState.copy(status = SetupStatus.Success))
    }
}

// Modo sin HTTPS (solo IP de casa): aviso visible, ayudas y puerto sugerido del modo local.
private val LocalExampleState = ExampleState.copy(
    server = "192.168.1.50",
    port = "9000",
    allowLocalHttp = true,
)

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreviewLocalHttp() {
    GastronomiconTheme { ExampleSetupScreen(LocalExampleState) }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun SetupScreenPreviewLocalHttpLargeText() {
    GastronomiconTheme { ExampleSetupScreen(LocalExampleState) }
}

// Primera impresión real: nadie ha escrito nada todavía. «Guardar» está
// apagado porque [SetupUiState.canSave] viene en `false`.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SetupScreenPreviewEmpty() {
    GastronomiconTheme {
        ExampleSetupScreen(
            SetupUiState(
                server = "",
                port = "443",
                token = "",
                allowLocalHttp = false,
                canSave = false,
                status = SetupStatus.Idle,
            )
        )
    }
}

// La primera pantalla que se ve, con el texto del sistema al 2×: el aviso de
// qué falta tiene que seguir leyéndose y «Guardar» seguir al alcance.
@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun SetupScreenPreviewEmptyLargeText() {
    GastronomiconTheme {
        ExampleSetupScreen(
            SetupUiState(
                server = "",
                port = "443",
                token = "",
                allowLocalHttp = false,
                canSave = false,
                status = SetupStatus.Idle,
            )
        )
    }
}

// Sin cobertura y con el texto grande: el aviso crece y los botones no se pierden.
@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun SetupScreenPreviewNoNetworkLargeText() {
    GastronomiconTheme {
        ExampleSetupScreen(
            ExampleState.copy(status = SetupStatus.Error(ConnectionProblem.NoNetwork))
        )
    }
}
