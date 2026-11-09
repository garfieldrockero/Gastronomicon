package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * Lee una cantidad escrita por una persona: «2», «1,5», «1.5», «1/2», «1 1/2», «½», «1½».
 *
 * Devuelve `null` si el texto no es una cantidad válida (vacío incluido; quien llama decide
 * si «vacío» es un error). Nunca devuelve valores no finitos ni denominadores cero.
 */
object QuantityParser {
    private val UNICODE = mapOf(
        '½' to 0.5, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3, '¼' to 0.25, '¾' to 0.75,
        '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875,
    )
    private const val MAX_LENGTH = 32

    fun parse(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null

        val last = trimmed.last()
        UNICODE[last]?.let { fraction ->
            val head = trimmed.dropLast(1).trim()
            if (head.isEmpty()) return fraction
            val whole = head.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return whole + fraction
        }

        val parts = trimmed.split(Regex("\\s+"))
        return when (parts.size) {
            1 -> single(parts[0])
            2 -> {
                val whole = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val fraction = slash(parts[1]) ?: return null
                whole + fraction
            }
            else -> null
        }
    }

    private fun single(token: String): Double? =
        if (token.contains('/')) slash(token) else decimal(token)

    private fun slash(token: String): Double? {
        val pieces = token.split('/')
        if (pieces.size != 2) return null
        val numerator = pieces[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val denominator = pieces[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return numerator.toDouble() / denominator
    }

    private fun decimal(token: String): Double? {
        val value = token.replace(',', '.').toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() }
    }
}
