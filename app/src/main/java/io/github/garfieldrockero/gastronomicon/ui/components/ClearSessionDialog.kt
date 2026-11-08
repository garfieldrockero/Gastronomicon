package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes

/**
 * Confirmación de «Borrar la sesión». La usan Ajustes y la pantalla de fallo
 * del almacén: un solo diálogo para que el texto no se desincronice.
 *
 * «Cancelar» a la izquierda y la acción destructiva en color de error, con su
 * verbo completo: no basta un «Sí» sin contexto.
 */
@Composable
fun ClearSessionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_dialog_title)) },
        text = { Text(stringResource(R.string.settings_clear_dialog_text)) },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.settings_clear_dialog_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.settings_clear_button))
            }
        },
    )
}
