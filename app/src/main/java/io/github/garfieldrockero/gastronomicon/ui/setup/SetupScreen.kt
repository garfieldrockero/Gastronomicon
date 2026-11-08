package io.github.garfieldrockero.gastronomicon.ui.setup

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.ui.components.SecureWindowEffect
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.LocalExtendedColors
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

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

/**
 * Deja del servidor solo el último dominio («mealie.example.com» → «***.com»);
 * con una IP, nada («192.168.1.50» → «***»).
 * No es un dato para mostrar: existe únicamente para que un log diga de qué
 * servidor se hablaba sin publicar la dirección completa de la persona.
 */
private fun maskedServer(server: String): String {
    val host = server.trim()
        .substringAfter("://", server.trim())
        .substringBefore('/')
        .substringBefore(':')
    if (host.isEmpty()) return ""
    val domain = host.substringAfterLast('.', "")
    // Con una IP (v4 o v6) el último tramo es un número del propio equipo de
    // la persona: no se enseña nada. Con un nombre, solo su último dominio.
    // Un último tramo que empieza por dígito (también con basura detrás, como
    // «192.168.1.50?x») es de una IP, no de un dominio: ninguno acaba en cifra.
    val isIp = host.startsWith("[") || domain.firstOrNull()?.isDigit() == true ||
        host.all { it.isDigit() || it == '.' }
    return if (domain.isEmpty() || isIp) "***" else "***.$domain"
}

/** Marcha de la pantalla de configuración. */
sealed interface SetupStatus {
    data object Idle : SetupStatus
    data object Testing : SetupStatus
    data object Success : SetupStatus
    data object Saving : SetupStatus

    /**
     * Fallo de la última acción. [problem] es un motivo cerrado, no un texto:
     * lo decide el núcleo y la pantalla solo le pone el mensaje de
     * `strings.xml`, así que nunca se cuela una excepción, la respuesta cruda
     * del servidor ni nada de lo que escribió la persona.
     */
    data class Error(val problem: ConnectionProblem) : SetupStatus
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

    val busy = state.status == SetupStatus.Testing || state.status == SetupStatus.Saving
    // Solo señala dónde mirar; el motivo lo ha decidido ya el núcleo y el
    // mensaje va en el aviso de abajo.
    val problem = (state.status as? SetupStatus.Error)?.problem
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
            Text(
                text = stringResource(R.string.setup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

            // Botones a lo ancho y apilados: «Probar conexión» es el paso
            // opcional y «Guardar», el objetivo, queda más abajo, al alcance
            // del pulgar de quien usa el móvil con una sola mano.
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !busy,
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

/**
 * Interruptor del modo de red local. La fila entera es el objetivo táctil y el
 * `Switch` va sin su propio `onCheckedChange` para que TalkBack vea un solo
 * nodo («interruptor, activado») y no dos.
 *
 * El aviso usa `errorContainer`/`onErrorContainer` (contraste AA con holgura)
 * en un contenedor y no solo texto rojo: avisa de un riesgo asumido, no de un
 * campo mal rellenado, y no debe confundirse con un error de validación.
 */
@Composable
private fun LocalHttpSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                text = stringResource(R.string.setup_local_http_label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        if (checked) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.setup_local_http_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(Spacing.medium)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Indicador de progreso dentro de un botón ocupado. */
@Composable
private fun BusyIndicator() {
    // Color primario porque el botón, al deshabilitarse, pierde su tinte y el
    // indicador tendría poco contraste con el color de contenido apagado.
    CircularProgressIndicator(
        modifier = Modifier.size(BusyIndicatorSize),
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = BusyIndicatorStroke,
    )
    Spacer(Modifier.width(Spacing.small))
}

// Más pequeño que el texto del botón para que no lo empuje ni lo tape.
private val BusyIndicatorSize = 18.dp
private val BusyIndicatorStroke = 2.dp

/**
 * Añade al campo la frase de error que lee TalkBack, solo si hay error: sin
 * error no debe quedar ninguna semántica de error colgando.
 */
private fun Modifier.fieldError(hasError: Boolean, message: String): Modifier =
    if (hasError) this.semantics { error(message) } else this

/**
 * Aviso de marcha o resultado. El contenedor existe siempre y solo cambia el
 * texto: así el liveRegion se mantiene estable y el lector de pantalla
 * anuncia los cambios en vez de ver nacer y morir nodos.
 */
@Composable
private fun StatusMessage(status: SetupStatus, canSave: Boolean) {
    val text = when (status) {
        SetupStatus.Testing -> stringResource(R.string.setup_status_testing)
        SetupStatus.Saving -> stringResource(R.string.setup_status_saving)
        SetupStatus.Success -> stringResource(R.string.setup_result_success)
        is SetupStatus.Error -> stringResource(errorTextRes(status.problem))
        // En reposo con «Guardar» apagado se dice qué falta; si ya se puede
        // guardar, no hay nada que avisar.
        SetupStatus.Idle -> if (canSave) "" else stringResource(R.string.setup_hint_fill_fields)
    }
    val color = when (status) {
        SetupStatus.Success -> LocalExtendedColors.current.success
        is SetupStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        // Una línea reservada aunque no haya aviso, medida con el propio
        // estilo: escala con el texto del sistema y «Guardar» no salta al
        // aparecer una línea. Con dos, «Guardar» sí baja, y es lo correcto:
        // manda el aviso.
        minLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * El núcleo decide el motivo y aquí solo se le pone texto. Los textos viven en
 * `strings.xml` y no en el estado: así no puede colarse una excepción, la
 * respuesta cruda del servidor ni nada de lo que escribió la persona en un
 * mensaje que acaba en pantalla, en un log o en una captura. Sin «else» a
 * propósito: un motivo nuevo en el núcleo no compila hasta tener su texto.
 */
private fun errorTextRes(problem: ConnectionProblem): Int = when (problem) {
    ConnectionProblem.NoNetwork -> R.string.setup_error_no_network
    ConnectionProblem.Unauthorized -> R.string.setup_error_unauthorized
    ConnectionProblem.ServerError -> R.string.setup_error_server
    ConnectionProblem.InvalidAddress -> R.string.setup_error_invalid_address
    ConnectionProblem.NotLocalAddress -> R.string.setup_error_not_local_address
    ConnectionProblem.LocalHttpNeedsHomeNetwork -> R.string.setup_error_local_http_needs_home_network
    ConnectionProblem.SecureConnectionFailed -> R.string.setup_error_secure_connection
    ConnectionProblem.InvalidToken -> R.string.setup_error_invalid_token
    ConnectionProblem.Unknown -> R.string.setup_error_unknown
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
