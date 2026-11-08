package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing

/** Indicador de progreso dentro de un botón ocupado. */
@Composable
fun BusyIndicator() {
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
