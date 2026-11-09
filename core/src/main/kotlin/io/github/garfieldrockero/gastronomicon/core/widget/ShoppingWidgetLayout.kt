package io.github.garfieldrockero.gastronomicon.core.widget

/**
 * Cuántas filas caben en el widget según el alto que le haya dado el launcher y
 * el tamaño de letra del sistema.
 *
 * El widget no tiene scroll, así que el número de filas se decide antes de
 * pintar: se descuenta [CHROME_DP] (todo lo que no es fila: relleno, cabecera,
 * «y N más», línea de estado y hora de la copia) y cada fila mide
 * [ROW_HEIGHT_DP] ampliado con `fontScale` (a 2× caben menos). Nunca baja de
 * [MIN_ROWS] ni pasa de [MAX_ROWS]: un alto desconocido o absurdo no deja el
 * widget vacío ni pinta de más.
 *
 * Los dos números tienen que cuadrar con `widget_shopping.xml`, que es quien
 * decide de verdad los altos. Si allí cambia un relleno, un `textSize` o un
 * `minHeight`, aquí cambia [CHROME_DP] y en `xml/widget_shopping_info.xml`
 * cambia `minResizeHeight`; si no, el pie (la línea de estado y la hora) se
 * recorta por abajo.
 */
object ShoppingWidgetLayout {

    const val MIN_ROWS = 1

    const val MAX_ROWS = ShoppingWidgetSnapshot.MAX_ROWS

    /** Filas con las que se pinta cuando el launcher no dice el alto. */
    const val DEFAULT_ROWS = 3

    /**
     * Todo lo que no es una fila de la compra, en dp. **Se reserva sitio para
     * la línea de estado aunque no se pinte**: es la que avisa de los fallos
     * («Guardando…», «Sin conexión», «Puede que no se haya guardado…») y no
     * puede quedar fuera del widget.
     *
     * La cuenta, con `widget_shopping.xml` y el tamaño de letra normal:
     * - relleno de arriba y abajo: 10 + 10 = 20
     * - cabecera con el botón (objetivo táctil de 48): 48
     * - «y N más»: 16 de línea + 2 de separación = 18
     * - hora de la copia: 16 de línea + 2 de separación = 18
     * - línea de estado (dos líneas): 2 × 16 + 2 = 34, se redondea a 36
     *
     * Total: 140. Con eso, `CHROME_DP + ROW_HEIGHT_DP = 188`: por debajo de ese
     * alto no cabe ni una fila con su pie, y por eso `minResizeHeight` de la
     * ficha del widget son 190 dp.
     *
     * Con texto muy grande (`fontScale` alto) el pie crece en sp y esto no lo
     * cubre: se pinta una fila menos y el último renglón puede apretarse. Es lo
     * que hay que mirar en el móvil con la letra al máximo.
     */
    const val CHROME_DP = 140

    /**
     * Alto base de una fila, en dp: el objetivo táctil de 48 dp. No baja aunque
     * el texto sea pequeño; con texto grande crece con `fontScale`.
     */
    const val ROW_HEIGHT_DP = 48

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
