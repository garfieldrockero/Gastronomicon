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
 * `imePadding()` y no un `navigationBarsPadding()` que ya está consumido. Se apaga
 * con [imePadding] `false` en **cualquier pantalla cuya barra inferior ya sume el
 * teclado** (`safeDrawing`, que incluye el IME: el buscador de la lista de recetas,
 * la barra de la semana del planificador, la barra de añadir de la compra y el
 * «Guardar» del formulario). Ahí el `Scaffold` ya coloca el aviso por encima de esa
 * barra medida; sumar el teclado otra vez lo subiría dos teclados, a media pantalla.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GastronomiconSnackbarHost(
    notice: SnackbarNotice?,
    /**
     * El aviso ya se ha visto: llega con su [SnackbarNotice.id]. Quien lo recibe
     * tiene que **ignorar los ids que no sean el suyo**: este callback puede llegar
     * tarde (un aviso nuevo emitido justo al cerrarse el anterior) y, sin esa
     * comprobación, se comería la confirmación que nadie ha llegado a ver.
     */
    onShown: (Long) -> Unit,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Hueco para el teclado. Por defecto sí (`true`); se pasa `false` cuando la
     * pantalla ya mete el teclado en su barra inferior.
     */
    imePadding: Boolean = true,
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
        // Con su id: el receptor lo compara con el aviso que tiene delante y, si no
        // coincide, no toca nada (llegó tarde y ya hay otro aviso esperando).
        onShown(shown.id)
    }

    SnackbarHost(
        hostState = hostState,
        // Sin hueco propio: el `Scaffold` ya lo sube por encima de su barra y su botón.
        // El del teclado solo si la pantalla no lo ha metido ya en su barra inferior.
        modifier = modifier
            .then(if (imePadding) Modifier.imePadding() else Modifier)
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
//
// El par mensaje/acción de las vistas previas con acción es uno que **sí existe** en
// producción (el éxito de «añadir a la compra» del detalle de una receta): una acción
// de otra pantalla con un mensaje de la compra enseñaría una combinación que nadie va
// a ver y, además, ocultaría el caso real del 2× (frase larga + acción en línea aparte).

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
            message = previewAddedText(2),
            actionLabel = stringResource(R.string.extras_compra_notice_open),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun SnackbarNoticeActionLargeTextPreview() {
    GastronomiconTheme {
        SnackbarPreviewFrame(
            message = previewAddedText(12),
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
            message = previewAddedText(1),
            actionLabel = stringResource(R.string.extras_compra_notice_open),
        )
    }
}

/**
 * Texto de ejemplo del éxito de «añadir a la compra». Usa el **mismo** texto que
 * producción ([addToShoppingAddedText], en `AddToShopping.kt`, mismo paquete), con un
 * nombre de lista inventado: así la vista previa no puede enseñar una frase que la
 * app no dice.
 */
@Composable
private fun previewAddedText(recipes: Int): String =
    addToShoppingAddedText(AddToShoppingOutcome.Added(listName = "Compra", recipes = recipes))

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
