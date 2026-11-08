package io.github.garfieldrockero.gastronomicon.ui.start

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.ClearSessionDialog
import io.github.garfieldrockero.gastronomicon.ui.components.StatusText
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/** Mientras se lee la sesión al arrancar (descifrar puede tardar un instante). */
@Composable
fun StartLoadingScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(Spacing.large)
                // Un solo nodo para TalkBack: el texto se anuncia al aparecer.
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium, Alignment.CenterHorizontally),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.start_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * El Keystore no contestó al arrancar. No se trata como «sin sesión»: se dice
 * qué pasa, que no se ha perdido nada y se ofrecen las dos salidas. Borrar no
 * descifra, así que funciona aunque la lectura falle; pide confirmación porque
 * es destructivo.
 */
@Composable
fun StorageProblemScreen(
    clearing: Boolean,
    clearFailed: Boolean,
    onRetry: () -> Unit,
    onClearSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize()) {
        // Caja que centra y columna que se desplaza: `Arrangement.Center` dentro
        // de un `verticalScroll` no centra (la altura es ilimitada). Con texto
        // grande la columna crece y se desplaza.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(Spacing.large),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.start_storage_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.start_storage_text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = onRetry,
                    enabled = !clearing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.start_storage_retry))
                }
                OutlinedButton(
                    onClick = { confirming = true },
                    enabled = !clearing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    if (clearing) {
                        BusyIndicator()
                    }
                    Text(stringResource(R.string.start_storage_clear))
                }
                StatusText(
                    text = when {
                        clearing -> stringResource(R.string.settings_status_clearing)
                        clearFailed -> stringResource(R.string.settings_error_clear_storage)
                        else -> ""
                    },
                    color = if (clearFailed && !clearing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (clearFailed && !clearing) {
                    Text(
                        text = stringResource(R.string.start_storage_clear_failed_help),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (confirming) {
        ClearSessionDialog(
            onConfirm = {
                confirming = false
                onClearSession()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StartLoadingPreview() {
    GastronomiconTheme { StartLoadingScreen() }
}

@Preview(showBackground = true)
@Composable
private fun StorageProblemPreview() {
    GastronomiconTheme {
        StorageProblemScreen(clearing = false, clearFailed = false, onRetry = {}, onClearSession = {})
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StorageProblemPreviewDark() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        StorageProblemScreen(clearing = false, clearFailed = true, onRetry = {}, onClearSession = {})
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun StorageProblemPreviewClearFailedLargeText() {
    GastronomiconTheme {
        StorageProblemScreen(clearing = false, clearFailed = true, onRetry = {}, onClearSession = {})
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun StorageProblemPreviewLargeText() {
    GastronomiconTheme {
        StorageProblemScreen(clearing = true, clearFailed = false, onRetry = {}, onClearSession = {})
    }
}
