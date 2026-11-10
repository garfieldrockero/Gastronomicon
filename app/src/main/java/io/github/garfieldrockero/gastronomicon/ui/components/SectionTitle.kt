package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

/**
 * Título de una sección de una pantalla larga (Ajustes, detalle, formulario).
 *
 * Uno solo para todas: mismo tamaño que un título de tarjeta y en `onSurface`,
 * no en el color de acento, para que la jerarquía la marque el tamaño y no el
 * color, y en seminegrita para que se distinga del texto que introduce. Va marcado como encabezado para que TalkBack pueda saltar de sección
 * en sección.
 */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}
