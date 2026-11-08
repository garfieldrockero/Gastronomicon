package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing

/**
 * Interruptor del modo de red local. La fila entera es el objetivo táctil y el
 * `Switch` va sin su propio `onCheckedChange` para que TalkBack vea un solo
 * nodo («interruptor, activado») y no dos.
 *
 * El aviso usa `errorContainer`/`onErrorContainer` (contraste AA con holgura)
 * en un contenedor y no solo texto rojo: avisa de un riesgo asumido, no de un
 * campo mal rellenado, y no debe confundirse con un error de validación.
 *
 * Lo usan la primera configuración y Ajustes.
 */
@Composable
fun LocalHttpSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
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
        // El contenedor del aviso existe siempre (con liveRegion) y solo cambia
        // su contenido, como en StatusText: así TalkBack anuncia el aviso al
        // activar el interruptor en vez de ver nacer y morir el nodo.
        Box(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            if (checked) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.small),
                ) {
                    Text(
                        text = stringResource(R.string.setup_local_http_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.medium),
                    )
                }
            }
        }
    }
}
