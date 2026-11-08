package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics

/**
 * Añade al campo la frase de error que lee TalkBack, solo si hay error: sin
 * error no debe quedar ninguna semántica de error colgando.
 */
fun Modifier.fieldError(hasError: Boolean, message: String): Modifier =
    if (hasError) this.semantics { error(message) } else this
