package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing

/**
 * Aviso con título, detalle opcional y sus acciones, encima del contenido y sin
 * taparlo. El título lleva región viva: TalkBack lo lee al aparecer.
 *
 * Con [maxHeight] el texto se desplaza **por dentro** y las acciones quedan
 * siempre a la vista: con el texto del sistema al 2× y 360 dp de ancho, el aviso
 * no se come la pantalla ni esconde sus botones. Sin tope (`null`), el aviso
 * crece con su contenido y no se le pone un desplazamiento propio, porque dentro
 * de una lista (que ya se desplaza y mide a sus hijos con altura infinita) un
 * segundo desplazamiento no es válido.
 */
@Composable
fun NoticeCard(
    title: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
    maxHeight: Dp? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val bounded = maxHeight != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Column(
                modifier = if (bounded) {
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                },
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (detail != null) {
                    Text(text = detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
            actions()
        }
    }
}
