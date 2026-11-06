package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta provisional de la fase 1, para no dejar nunca el morado de plantilla de
// Material. La identidad visual definitiva se decide en la fase 3.
private val Terracota = Color(0xFF9C4A2F)
private val TerracotaOscura = Color(0xFFE8A587)

private val ColoresClaros = lightColorScheme(
    primary = Terracota,
    secondary = Color(0xFF5F6B3B),
    tertiary = Color(0xFF7A5636),
)

private val ColoresOscuros = darkColorScheme(
    primary = TerracotaOscura,
    secondary = Color(0xFFC0CBA0),
    tertiary = Color(0xFFE0BFA2),
)

@Composable
fun TemaGastronomicon(
    oscuro: Boolean = isSystemInDarkTheme(),
    contenido: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (oscuro) ColoresOscuros else ColoresClaros,
        content = contenido,
    )
}
