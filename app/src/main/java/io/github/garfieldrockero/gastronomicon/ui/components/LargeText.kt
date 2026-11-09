package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/** A partir de este tamaño de letra del sistema las filas se recolocan (foto más pequeña, rótulos encima). */
const val LARGE_TEXT_SCALE = 1.5f

/** Texto grande del sistema (accesibilidad). Solo decide la disposición; no calcula nada de negocio. */
@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
