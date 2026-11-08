package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Medidas fijas de la app que no son huecos. Viven aquí para que todas las
 * pantallas usen la misma y nadie la rebaje en una de ellas.
 */
object Sizes {
    /**
     * Alto mínimo de cualquier cosa que se pulse: la app se usa de pie, con
     * prisa y a veces con los dedos mojados, y 48 dp es lo que pide la guía
     * de accesibilidad de Android.
     */
    val MinTouchTarget = 48.dp
}
