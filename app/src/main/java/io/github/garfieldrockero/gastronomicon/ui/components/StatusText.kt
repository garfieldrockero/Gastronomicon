package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Aviso de marcha o resultado. El contenedor existe siempre y solo cambia el
 * texto: así el liveRegion se mantiene estable y el lector de pantalla
 * anuncia los cambios en vez de ver nacer y morir nodos.
 *
 * Una línea reservada aunque no haya aviso, medida con el propio estilo:
 * escala con el texto del sistema y el botón de debajo no salta al aparecer
 * una línea. Con dos, el botón sí baja, y es lo correcto: manda el aviso.
 */
@Composable
fun StatusText(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        minLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
