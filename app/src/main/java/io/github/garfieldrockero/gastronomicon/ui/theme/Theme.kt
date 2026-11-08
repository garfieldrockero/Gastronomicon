package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** Preferencia de tema que se guardará en Ajustes. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Tema de Gastronomicón. Los colores viven en Colors.kt y la tipografía en
 * Type.kt; este archivo solo decide qué esquema toca según el modo elegido.
 */
// TODO(ajustes): al permitir Claro/Oscuro manual en Ajustes, sincronizar el
// fondo de ventana (los colors.xml siguen al modo del sistema, no al elegido)
// y la apariencia de las barras del sistema con el tema efectivo.
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
    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = GastronomiconTypography,
            content = content,
        )
    }
}
