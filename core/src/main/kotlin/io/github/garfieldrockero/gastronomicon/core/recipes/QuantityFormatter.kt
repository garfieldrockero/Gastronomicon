package io.github.garfieldrockero.gastronomicon.core.recipes

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Formatea cantidades de ingredientes en castellano: «1/2», «1 1/2» o
 * decimales con coma. Devuelve cadena vacía si no hay cantidad que mostrar
 * (nula, cero, negativa, no finita o mayor que [ServingScaler.MAX_QUANTITY]): Mealie usa 0 para «sin cantidad».
 */
object QuantityFormatter {
    // Fracciones de cocina habituales; el resto cae a decimales.
    private val FRACTIONS = listOf(
        0.125 to "1/8", 0.25 to "1/4", 1.0 / 3 to "1/3", 0.375 to "3/8", 0.5 to "1/2",
        0.625 to "5/8", 2.0 / 3 to "2/3", 0.75 to "3/4", 0.875 to "7/8",
    )
    private const val TOLERANCE = 0.02
    private const val MIN_SHOWN = 0.01

    // Por encima de esto una fracción ya no aporta: se redondea a entero.
    private const val INTEGER_FROM = 100.0

    fun format(quantity: Double?): String {
        if (quantity == null || !quantity.isFinite() || quantity <= 0.0) return ""
        // Un valor absurdo del servidor no debe pintar cientos de cifras en pantalla.
        if (quantity > ServingScaler.MAX_QUANTITY) return ""
        if (quantity < MIN_SHOWN) return "< 0,01"
        if (quantity >= INTEGER_FROM) return decimal(quantity, 0)

        val whole = Math.floor(quantity)
        val fraction = quantity - whole
        // Entre «sin fracción» (0 y 1) y las fracciones, gana la más cercana.
        var bestName: String? = null
        var bestDiff = minOf(fraction, 1.0 - fraction)
        val roundedUp = fraction > 0.5
        var chosenZero = bestDiff <= TOLERANCE
        for ((value, name) in FRACTIONS) {
            val diff = Math.abs(fraction - value)
            if (diff <= TOLERANCE && diff < bestDiff - 1e-12) {
                bestName = name
                bestDiff = diff
                chosenZero = false
            }
        }
        val wholeNumber = whole.toLong()
        if (bestName != null) {
            return if (wholeNumber == 0L) bestName else "$wholeNumber $bestName"
        }
        if (chosenZero) {
            val shown = if (roundedUp) wholeNumber + 1 else wholeNumber
            // Un valor entre 0,01 y 0,02 no se redondea a 0: iría contra MIN_SHOWN.
            if (shown > 0) return shown.toString()
        }
        return decimal(quantity, if (quantity < 10.0) 2 else 1)
    }

    private fun decimal(value: Double, digits: Int): String {
        val rounded = BigDecimal.valueOf(value).setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros()
        return rounded.toPlainString().replace('.', ',')
    }
}
