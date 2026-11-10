package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Preferencia de tema que se guardará en Ajustes. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Si el modo elegido se ve oscuro ahora: el del sistema solo cuando se elige «Sistema». */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Fondo del esquema efectivo, para pintar también el fondo de la ventana (ver MainActivity). */
fun themeBackground(dark: Boolean): Color = if (dark) DarkColors.background else LightColors.background

/**
 * Tema de Gastronomicón. Los colores viven en Colors.kt y la tipografía en
 * Type.kt; este archivo solo decide qué esquema toca según el modo elegido.
 * La raíz (MainActivity) sincroniza con ese modo el fondo de la ventana y la
 * apariencia de las barras del sistema, que los colors.xml y el tema XML solo
 * ligan al modo del sistema.
 */
@Composable
fun GastronomiconTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark()
    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = GastronomiconTypography,
            shapes = GastronomiconShapes,
            content = content,
        )
    }
}
