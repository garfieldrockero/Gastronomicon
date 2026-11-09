package io.github.garfieldrockero.gastronomicon.core.widget

/**
 * Cuántas filas caben en el widget según el alto que le haya dado el launcher y
 * el tamaño de letra del sistema.
 *
 * El widget no tiene scroll, así que el número de filas se decide antes de
 * pintar: se descuenta la cabecera y el pie y cada fila crece con `fontScale`
 * (a 2× caben menos). Nunca baja de [MIN_ROWS] ni pasa de [MAX_ROWS]: un alto
 * desconocido o absurdo no deja el widget vacío ni pinta de más.
 */
object ShoppingWidgetLayout {

    const val MIN_ROWS = 1

    const val MAX_ROWS = ShoppingWidgetSnapshot.MAX_ROWS

    /** Filas con las que se pinta cuando el launcher no dice el alto. */
    const val DEFAULT_ROWS = 3

    /** Cabecera, botón de actualizar y línea de estado, en dp. */
    const val CHROME_DP = 72

    /** Alto base de una fila, en dp (objetivo táctil). */
    const val ROW_HEIGHT_DP = 44

    fun rowsFor(heightDp: Int, fontScale: Float): Int {
        if (heightDp <= 0) return DEFAULT_ROWS
        // Un `fontScale` de 0, negativo o no finito no es un valor real de
        // Android: se pinta como el tamaño normal en vez de romper la cuenta.
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        val available = heightDp - CHROME_DP
        if (available <= 0) return MIN_ROWS
        val rows = (available / (ROW_HEIGHT_DP * scale)).toInt()
        return rows.coerceIn(MIN_ROWS, MAX_ROWS)
    }
}
