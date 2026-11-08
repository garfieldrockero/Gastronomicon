package io.github.garfieldrockero.gastronomicon.core.html

/**
 * Entidades HTML que se decodifican: las numéricas y un conjunto corto de
 * nombradas. Se decodifica una sola vez y el resultado es texto, nunca se
 * vuelve a analizar, así que «&amp;lt;» acaba en «&lt;» y no en «<».
 */
internal object HtmlEntities {

    /** [codePoint] es -1 si la entidad es válida pero su valor no se admite. */
    class Match(val codePoint: Int, val length: Int)

    private const val MAX_NAME_LENGTH = 10

    // Con 10 dígitos hexadecimales el valor cabe de sobra en un Long.
    private const val MAX_NUMERIC_DIGITS = 10
    private const val MAX_CODE_POINT = 0x10FFFF

    private val NAMED: Map<String, Int> = mapOf(
        "amp" to '&'.code, "lt" to '<'.code, "gt" to '>'.code, "quot" to '"'.code,
        "apos" to '\''.code, "nbsp" to 0xA0, "ndash" to 0x2013, "mdash" to 0x2014,
        "hellip" to 0x2026, "lsquo" to 0x2018, "rsquo" to 0x2019, "ldquo" to 0x201C,
        "rdquo" to 0x201D, "laquo" to 0xAB, "raquo" to 0xBB, "bull" to 0x2022,
        "middot" to 0xB7, "deg" to 0xB0, "times" to 0xD7, "divide" to 0xF7,
        "copy" to 0xA9, "reg" to 0xAE, "euro" to 0x20AC, "frac12" to 0xBD,
        "frac14" to 0xBC, "frac34" to 0xBE, "ordf" to 0xAA, "ordm" to 0xBA,
        "sup2" to 0xB2, "sup3" to 0xB3, "iexcl" to 0xA1, "iquest" to 0xBF,
        "aacute" to 0xE1, "eacute" to 0xE9, "iacute" to 0xED, "oacute" to 0xF3,
        "uacute" to 0xFA, "ntilde" to 0xF1, "uuml" to 0xFC, "ccedil" to 0xE7,
        "Aacute" to 0xC1, "Eacute" to 0xC9, "Iacute" to 0xCD, "Oacute" to 0xD3,
        "Uacute" to 0xDA, "Ntilde" to 0xD1, "Uuml" to 0xDC, "Ccedil" to 0xC7,
    )

    /**
     * Intenta leer una entidad que empieza en [start] (donde hay un '&').
     * Devuelve null si no es una entidad completa; entonces el '&' es texto.
     * Se exige el punto y coma: aceptar formas sin él abriría ambigüedades.
     */
    fun matchAt(s: String, start: Int): Match? {
        val n = s.length
        var i = start + 1
        if (i >= n) return null
        if (s[i] == '#') return matchNumeric(s, start)

        val nameStart = i
        while (i < n && i - nameStart <= MAX_NAME_LENGTH && isAsciiAlphanumeric(s[i])) i++
        if (i == nameStart || i >= n || s[i] != ';') return null
        val codePoint = NAMED[s.substring(nameStart, i)] ?: return null
        return Match(codePoint, i + 1 - start)
    }

    private fun matchNumeric(s: String, start: Int): Match? {
        val n = s.length
        var i = start + 2
        val hex = i < n && (s[i] == 'x' || s[i] == 'X')
        if (hex) i++
        val base = if (hex) 16 else 10

        val digitsStart = i
        var value = 0L
        while (i < n && i - digitsStart < MAX_NUMERIC_DIGITS) {
            val digit = digitValue(s[i], hex)
            if (digit < 0) break
            value = value * base + digit
            i++
        }
        if (i == digitsStart || i >= n || s[i] != ';') return null
        val codePoint = if (value in 1..MAX_CODE_POINT.toLong()) value.toInt() else -1
        return Match(codePoint, i + 1 - start)
    }

    private fun digitValue(c: Char, hex: Boolean): Int = when {
        c in '0'..'9' -> c - '0'
        hex && c in 'a'..'f' -> c - 'a' + 10
        hex && c in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun isAsciiAlphanumeric(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}
