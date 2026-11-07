package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Preferencia de tema que se guardará en Ajustes. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

// Paleta provisional de la fase 1, para no dejar nunca el morado de plantilla de
// Material. La identidad visual definitiva se decide en la fase 3.
private val Terracotta = Color(0xFF9C4A2F)
private val TerracottaDark = Color(0xFFE8A587)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    secondary = Color(0xFF5F6B3B),
    tertiary = Color(0xFF7A5636),
)

private val DarkColors = darkColorScheme(
    primary = TerracottaDark,
    secondary = Color(0xFFC0CBA0),
    tertiary = Color(0xFFE0BFA2),
)

@Composable
fun GastronomiconTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
