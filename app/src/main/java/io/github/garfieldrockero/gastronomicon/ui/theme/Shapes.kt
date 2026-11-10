package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Formas de la app, decididas una vez para todas las pantallas: esquinas
 * redondeadas de recetario, sin llegar a pastilla. `medium` (16 dp) es la que
 * usan las tarjetas y los avisos; `small` (8 dp), las etiquetas; `large` (24 dp),
 * las hojas y las tarjetas grandes.
 *
 * Son valores propios y no los de Material por defecto (4/8/12/16/28) para que
 * ningún componente se quede con el radio de plantilla.
 */
val GastronomiconShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
