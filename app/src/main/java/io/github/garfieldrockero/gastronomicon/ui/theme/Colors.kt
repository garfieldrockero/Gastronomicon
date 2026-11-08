package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Paleta provisional de Gastronomicón: papel crema y terracota, como el icono.
 *
 * - La semilla es la terracota #9C4A2F de la tapa del recetario del icono
 *   adaptativo (res/drawable/ic_launcher_*.xml). Si esta paleta cambia, el
 *   icono hay que retocarlo también.
 * - Van rellenos todos los roles de Material 3, incluidos los fijos, para que
 *   ningún componente caiga nunca en el morado de plantilla.
 * - Las parejas contenedor/texto cumplen contraste AA, casi todas con holgura.
 * - El verde de «ha ido bien» no es un rol de Material 3: vive en
 *   [ExtendedColors], al final del archivo.
 * - res/values/colors.xml y res/values-night/colors.xml copian «background»
 *   para el fondo de ventana: mantenerlos sincronizados con este archivo.
 */

// Colores tomados directamente del icono; el resto de la paleta se deriva de
// ellos con tonos Material (10/20/30/40/80/90).
private val Terracotta = Color(0xFF9C4A2F)     // tapa del recetario (semilla)
private val TerracottaDeep = Color(0xFF7A3622) // lomo del recetario
private val TerracottaSoft = Color(0xFFE8A587) // terracota para modo oscuro
private val Olive = Color(0xFF5F6B3B)          // cinta marcapáginas
private val OliveSoft = Color(0xFFC0CBA0)      // oliva para modo oscuro
private val WarmBrown = Color(0xFF7A5636)      // marrón cálido de apoyo
private val Cream = Color(0xFFF3E6D3)          // papel crema del fondo del icono

// Variante fija del primario: el lomo tal cual (#7A3622) sobre la terracota
// clara de primaryFixedDim se queda en 4,27:1; oscurecido llega a 4,71:1 (AA).
private val TerracottaFixedVariant = Color(0xFF72301F)

val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3F1206),
    inversePrimary = TerracottaSoft,
    secondary = Olive,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EACB),
    onSecondaryContainer = Color(0xFF1A2205),
    tertiary = WarmBrown,
    onTertiary = Color.White,
    tertiaryContainer = Cream,
    onTertiaryContainer = Color(0xFF2B1708),
    background = Color(0xFFFFF8F0),
    onBackground = Color(0xFF221A12),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF221A12),
    surfaceVariant = Color(0xFFF3E0D5),
    onSurfaceVariant = Color(0xFF52443A),
    surfaceDim = Color(0xFFE8D9CC),
    surfaceBright = Color(0xFFFFF8F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF6EC),
    surfaceContainer = Color(0xFFFAF1E5),
    surfaceContainerHigh = Color(0xFFF6EBDD),
    surfaceContainerHighest = Color(0xFFF2E5D4),
    outline = Color(0xFF85736A),
    outlineVariant = Color(0xFFD8C2B5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF2F2115),
    inverseOnSurface = Color(0xFFF3EAE3),
    scrim = Color(0xFF000000),
    // Roles fijos: mismos valores en claro y en oscuro, porque los componentes
    // que los usan deben verse igual en los dos modos.
    primaryFixed = Color(0xFFFFDBCF),
    onPrimaryFixed = Color(0xFF3F1206),
    primaryFixedDim = TerracottaSoft,
    onPrimaryFixedVariant = TerracottaFixedVariant,
    secondaryFixed = Color(0xFFE4EACB),
    onSecondaryFixed = Color(0xFF1A2205),
    secondaryFixedDim = OliveSoft,
    onSecondaryFixedVariant = Color(0xFF414C28),
    tertiaryFixed = Cream,
    onTertiaryFixed = Color(0xFF2B1708),
    tertiaryFixedDim = Color(0xFFE0BFA2),
    onTertiaryFixedVariant = Color(0xFF5C4028),
)

val DarkColors = darkColorScheme(
    primary = TerracottaSoft,
    onPrimary = Color(0xFF5C2413),
    primaryContainer = TerracottaDeep,
    onPrimaryContainer = Color(0xFFFFDBCF),
    inversePrimary = Terracotta,
    secondary = OliveSoft,
    onSecondary = Color(0xFF2B3316),
    secondaryContainer = Color(0xFF414C28),
    onSecondaryContainer = Color(0xFFE4EACB),
    tertiary = Color(0xFFE0BFA2),
    onTertiary = Color(0xFF452A15),
    tertiaryContainer = Color(0xFF5C4028),
    onTertiaryContainer = Cream,
    background = Color(0xFF1C1512),
    onBackground = Color(0xFFF2E5D8),
    surface = Color(0xFF1C1512),
    onSurface = Color(0xFFF2E5D8),
    surfaceVariant = Color(0xFF4E443C),
    onSurfaceVariant = Color(0xFFD7C7BC),
    surfaceDim = Color(0xFF120D09),
    surfaceBright = Color(0xFF3D3128),
    surfaceContainerLowest = Color(0xFF0D0907),
    surfaceContainerLow = Color(0xFF1F1813),
    surfaceContainer = Color(0xFF241C16),
    surfaceContainerHigh = Color(0xFF2A211A),
    surfaceContainerHighest = Color(0xFF332A22),
    outline = Color(0xFF9C8779),
    outlineVariant = Color(0xFF4E463F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFF2E5D8),
    inverseOnSurface = Color(0xFF2F2115),
    scrim = Color(0xFF000000),
    // Roles fijos: idénticos a los del modo claro.
    primaryFixed = Color(0xFFFFDBCF),
    onPrimaryFixed = Color(0xFF3F1206),
    primaryFixedDim = TerracottaSoft,
    onPrimaryFixedVariant = TerracottaFixedVariant,
    secondaryFixed = Color(0xFFE4EACB),
    onSecondaryFixed = Color(0xFF1A2205),
    secondaryFixedDim = OliveSoft,
    onSecondaryFixedVariant = Color(0xFF414C28),
    tertiaryFixed = Cream,
    onTertiaryFixed = Color(0xFF2B1708),
    tertiaryFixedDim = Color(0xFFE0BFA2),
    onTertiaryFixedVariant = Color(0xFF5C4028),
)

/**
 * Colores que Material 3 no tiene como rol. Ahora mismo solo el verde de «ha
 * ido bien»: `MaterialTheme.colorScheme` trae `error` pero no su contrario, y
 * sin esto cada pantalla acabaría inventándose un verde suelto.
 *
 * Se leen con `LocalExtendedColors.current`, que [GastronomiconTheme] rellena
 * según el modo.
 */
@Immutable
data class ExtendedColors(
    /** Verde de éxito: sirve de relleno y también de texto sobre el fondo. */
    val success: Color,
    /** Contenido que va encima de [success]. */
    val onSuccess: Color,
)

// Claro: 6,07:1 sobre el fondo crema y 6,39:1 del blanco encima (AA con holgura).
val LightExtendedColors = ExtendedColors(
    success = Color(0xFF2F6B3A),
    onSuccess = Color(0xFFFFFFFF),
)

// Oscuro: 11,08:1 sobre el fondo y 8,76:1 del verde oscuro encima.
val DarkExtendedColors = ExtendedColors(
    success = Color(0xFFA6D89A),
    onSuccess = Color(0xFF17301A),
)

// Con valor por defecto para que cualquier preview funcione sin envolver nada.
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
