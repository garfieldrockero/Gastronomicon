package io.github.garfieldrockero.gastronomicon.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Un aviso corto: lo que ya está hecho y no deja nada pendiente.
 *
 * Regla de la casa (fase 16h): a Snackbar va **solo la confirmación de una acción
 * terminada y sin decisión pendiente** («Receta borrada», «3 cosas quitadas»,
 * «Comida añadida»). Los errores, la sesión caducada, la falta de cobertura, las
 * dudas del tipo «puede que se haya creado», los avisos de seguridad y la copia
 * sin conexión **no** pasan por aquí: se quedan en su tarjeta, con su acción, y no
 * desaparecen solos.
 *
 * [id] es un contador del estado de la pantalla, uno por aviso: así dos comidas
 * añadidas seguidas se anuncian las dos. Nunca un booleano (`deleted = true`), que
 * volvería a dispararse en cada recomposición y en cada giro del móvil.
 *
 * [message] y [actionLabel] llegan ya resueltos con `stringResource` desde la ruta,
 * de modo que el ViewModel sigue sin conocer el contexto ni los recursos.
 */
@Immutable
data class SnackbarNotice(
    val id: Long,
    val message: String,
    val actionLabel: String? = null,
)

/**
 * Pinta [notice] en su franja inferior y lo da por visto al terminar ([onShown],
 * una sola vez por [SnackbarNotice.id]). Si la persona toca la acción se llama
 * [onAction] y después [onShown].
 *
 * Va **un host por `Scaffold`** (no uno único en `MainTabs`): así el `Scaffold` de
 * cada pantalla coloca el aviso por encima de su barra inferior y de su botón
 * flotante sin medir nada a mano. Hay un host por pantalla visible y solo hay una
 * pantalla visible, así que en la práctica hay **un aviso a la vez**: el nuevo
 * sustituye al anterior en vez de encolarse (tres avisos de 10 s encolados son
 * medio minuto anunciando algo que ya no importa).
 *
 * Duración **siempre explícita**: 4 s sin acción y 10 s con acción. Nunca
 * `Indefinite`: un aviso indefinido no se va solo y bloquea los siguientes.
 *
 * Se da por visto al **terminar**, no al empezar: si la pantalla se va a medio
 * aviso (cambio de pestaña), al volver se ve otra vez. [onShown] tiene que
 * **consumir** el aviso (dejarlo en `null` manteniendo el `id`), no fabricar otro.
 *
 * Para TalkBack: el `Snackbar` de Material 3 se anuncia él solo y no roba el foco;
 * el título de panel («Aviso») le da contexto, porque el texto llega por sorpresa
 * mientras la persona mira otra cosa. Si en el móvil resultara que esta versión no
 * lo anuncia, el arreglo es una línea aquí: `semantics { liveRegion =
 * LiveRegionMode.Polite }` en el `modifier` del host (como `NoticeCard`). No se
 * pone de entrada para no anunciarlo dos veces.
 *
 * Con el teclado abierto el aviso sube por encima del campo: el `Scaffold` de las
 * pestañas no reparte el inset del teclado (`contentWindowInsets = WindowInsets(0)`
 * y `tabInsets` sin borde inferior), así que el hueco lo pone el propio host con
 * `imePadding()` y no un `navigationBarsPadding()` que ya está consumido.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GastronomiconSnackbarHost(
    notice: SnackbarNotice?,
    onShown: () -> Unit,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    val noticeTitle = stringResource(R.string.a11y_notice)

    // La clave es el contador, no el texto ni el tipo: dos avisos distintos seguidos
    // se anuncian los dos; el mismo id no se repite en cada recomposición.
    LaunchedEffect(notice?.id) {
        // Sin aviso no se pinta nada. Un aviso con id `0` sí se pinta: el número no
        // significa nada por sí solo, lo que importa es que **cambie** entre dos avisos.
        val shown = notice ?: return@LaunchedEffect
        // Uno a la vez: el nuevo sustituye al que estuviera a la vista.
        hostState.currentSnackbarData?.dismiss()
        val result = hostState.showSnackbar(
            message = shown.message,
            actionLabel = shown.actionLabel,
            // Explícita siempre: con `actionLabel` el valor por defecto sería Indefinite.
            duration = if (shown.actionLabel == null) {
                SnackbarDuration.Short
            } else {
                SnackbarDuration.Long
            },
        )
        if (result == SnackbarResult.ActionPerformed) onAction()
        onShown()
    }

    SnackbarHost(
        hostState = hostState,
        // Sin hueco propio: el `Scaffold` ya lo sube por encima de su barra y su botón.
        modifier = modifier
            .imePadding()
            .semantics { paneTitle = noticeTitle },
        snackbar = { data -> GastronomiconSnackbarVisual(data) },
    )
}

/**
 * El aviso tal cual se ve, con la forma del tema y la acción en línea aparte con
 * texto grande (a 2× el botón al lado del texto deja la frase en una columna de
 * tres caracteres). Los colores son los de `SnackbarDefaults`
 * (`inverseSurface`/`inverseOnSurface`), que `ui/theme/Colors.kt` ya define para
 * claro y oscuro; no se pisan aquí.
 */
@Composable
private fun GastronomiconSnackbarVisual(data: SnackbarData) {
    Snackbar(
        snackbarData = data,
        actionOnNewLine = isLargeText(),
        shape = MaterialTheme.shapes.medium,
    )
}

// --- Previews -----------------------------------------------------------------
// Se pinta la misma pieza que en producción ([GastronomiconSnackbarVisual]) para no
// enseñar un aviso que luego no sea igual. No se usa el host real porque `showSnackbar`
// suspende 4 s y en la vista previa no llegaría a verse nunca.

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun SnackbarNoticePreview() {
    GastronomiconTheme {
        SnackbarPreviewFrame(
            message = stringResource(R.string.recipes_deleted_notice),
            actionLabel = null,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun SnackbarNoticeActionPreview() {
    GastronomiconTheme {
        SnackbarPreviewFrame(
            message = pluralStringResource(R.plurals.shopping_cleared_notice, 3, 3),
            actionLabel = stringResource(R.string.extras_compra_notice_open),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun SnackbarNoticeActionLargeTextPreview() {
    GastronomiconTheme {
        SnackbarPreviewFrame(
            message = pluralStringResource(R.plurals.shopping_cleared_notice, 12, 12),
            actionLabel = stringResource(R.string.extras_compra_notice_open),
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SnackbarNoticeActionDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        SnackbarPreviewFrame(
            message = pluralStringResource(R.plurals.shopping_cleared_notice, 1, 1),
            actionLabel = stringResource(R.string.extras_compra_notice_open),
        )
    }
}

@Composable
private fun SnackbarPreviewFrame(message: String, actionLabel: String?) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.medium),
            contentAlignment = Alignment.BottomCenter,
        ) {
            GastronomiconSnackbarVisual(PreviewSnackbarData(message, actionLabel))
        }
    }
}

/** Datos de ejemplo solo para la vista previa: nunca llegan a producción. */
private class PreviewSnackbarData(
    message: String,
    actionLabel: String?,
) : SnackbarData {
    override val visuals: SnackbarVisuals = PreviewSnackbarVisuals(message, actionLabel)
    override fun performAction() = Unit
    override fun dismiss() = Unit
}

private class PreviewSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Short
}
