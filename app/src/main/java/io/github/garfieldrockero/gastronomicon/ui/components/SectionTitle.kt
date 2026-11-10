package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Título de una sección de una pantalla (Ajustes, detalle, formulario, cabecera de
 * la semana del planificador y secciones de la compra).
 *
 * Uno solo para todas: por defecto `titleLarge` y en `onSurface`, no en el color de
 * acento, para que la jerarquía la marque el tamaño y no el color, y en seminegrita
 * para que se distinga del texto que introduce. Va marcado como encabezado para que
 * TalkBack pueda saltar de sección en sección. [style] permite un tamaño distinto
 * donde la jerarquía de esa pantalla lo pide; la semántica extra (una región viva,
 * por ejemplo) va en [modifier].
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        // El encabezado va aquí, no en quien llama: así ningún título de sección se
        // queda sin él. El `modifier` de fuera se suma (región viva del planificador).
        modifier = modifier.semantics { heading() },
    )
}
