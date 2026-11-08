package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * Escalado de cantidades por raciones. Las cifras vienen del servidor y no
 * son de fiar: nada de dividir entre cero ni de devolver NaN o infinitos.
 */
object ServingScaler {
    /** Tope de lo que se admite como cantidad: más allá es un dato roto, no una receta. */
    const val MAX_QUANTITY = 1_000_000.0

    /**
     * Cantidad para `targetServings` raciones a partir de la receta para
     * `originalServings`.
     *
     * - Cantidad nula, negativa o no finita: null (no hay nada que mostrar).
     * - Cantidad 0: 0, porque en Mealie significa «sin cantidad» y no se escala.
     * - Raciones originales nulas o no positivas: no se puede calcular el
     *   factor, así que la cantidad se devuelve sin escalar.
     * - Raciones objetivo nulas o no positivas: null.
     * - Resultado fuera de [MAX_QUANTITY]: null, en vez de un desborde.
     */
    fun scale(quantity: Double?, originalServings: Double?, targetServings: Double?): Double? {
        if (quantity == null || !quantity.isFinite() || quantity < 0.0) return null
        if (quantity == 0.0) return 0.0
        if (quantity > MAX_QUANTITY) return null
        if (originalServings == null || !originalServings.isFinite() || originalServings <= 0.0) {
            return quantity
        }
        if (targetServings == null || !targetServings.isFinite() || targetServings <= 0.0) return null
        val result = quantity * (targetServings / originalServings)
        return if (result.isFinite() && result <= MAX_QUANTITY) result else null
    }

    /** Escala y da formato en un solo paso, para que la interfaz no calcule nada. */
    fun scaleAndFormat(quantity: Double?, originalServings: Double?, targetServings: Double?): String =
        QuantityFormatter.format(scale(quantity, originalServings, targetServings))
}
