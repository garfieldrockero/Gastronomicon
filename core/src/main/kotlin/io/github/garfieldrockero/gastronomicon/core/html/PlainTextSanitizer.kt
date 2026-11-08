package io.github.garfieldrockero.gastronomicon.core.html

/**
 * Saneado de texto plano de un solo campo (nombre, descripción, nota…).
 *
 * No interpreta HTML: «<b>» llega como esos cuatro caracteres, porque la
 * interfaz pinta el texto tal cual y no hay nada que ejecutar. Lo que sí se
 * quita son los caracteres que sirven para engañar a la vista: control,
 * bidireccionales (que reordenan lo que se ve) y marcas invisibles.
 */
object PlainTextSanitizer {

    /** Límite por defecto de un campo; los llamadores pueden pedir uno menor. */
    const val DEFAULT_MAX_LENGTH = 5_000

    /** Marcas combinantes seguidas que se conservan; las que sobran forman el «zalgo». */
    const val MAX_COMBINING_RUN = 4

    private const val ZERO_WIDTH_NON_JOINER = 0x200C
    private const val ZERO_WIDTH_JOINER = 0x200D

    /**
     * Devuelve [raw] limpio y recortado a [maxLength] puntos de código.
     *
     * Con [allowNewlines] se conservan los saltos de línea (como mucho dos
     * seguidos); sin él, todo salto pasa a ser un espacio. Los espacios se
     * colapsan y se recortan los de los extremos.
     */
    fun sanitize(raw: String?, maxLength: Int = DEFAULT_MAX_LENGTH, allowNewlines: Boolean = false): String {
        require(maxLength > 0) { "La longitud máxima debe ser positiva." }
        if (raw.isNullOrEmpty()) return ""

        val out = StringBuilder(minOf(raw.length, maxLength))
        var count = 0
        var pendingSpace = false
        var pendingBreaks = 0
        var marks = 0
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            // CRLF cuenta como un solo salto, no como dos.
            if (cp == '\r'.code && i < raw.length && raw[i] == '\n') continue

            val mapped = normalizeCodePoint(cp)
            if (mapped < 0) continue
            if (mapped == ' '.code) {
                pendingSpace = true
                continue
            }
            if (mapped == '\n'.code) {
                if (allowNewlines) pendingBreaks = minOf(pendingBreaks + 1, 2) else pendingSpace = true
                continue
            }

            if (!acceptCombining(mapped, marks)) continue
            marks = nextRun(mapped, marks)

            // Los separadores solo se emiten delante de un carácter visible, y
            // cuentan para el límite: así nunca queda un separador colgando.
            val separators = if (out.isEmpty()) 0 else if (pendingBreaks > 0) pendingBreaks else if (pendingSpace) 1 else 0
            if (count + separators + 1 > maxLength) break
            if (separators > 0) {
                if (pendingBreaks > 0) repeat(pendingBreaks) { out.append('\n') } else out.append(' ')
                count += separators
            }
            out.appendCodePoint(mapped)
            count++
            pendingSpace = false
            pendingBreaks = 0
        }
        return out.toString()
    }

    /** Marcas combinantes: no espaciantes, de cierre y espaciantes (Mn, Me, Mc). */
    internal fun isCombiningMark(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** False si [cp] es una marca combinante y ya hay [run] seguidas del tope. */
    internal fun acceptCombining(cp: Int, run: Int): Boolean = !isCombiningMark(cp) || run < MAX_COMBINING_RUN

    /**
     * Longitud de la racha tras aceptar [cp]. ZWJ y ZWNJ no la reinician: si lo
     * hicieran, «marca ZWJ marca ZWJ…» esquivaría el tope.
     */
    internal fun nextRun(cp: Int, run: Int): Int = when {
        isCombiningMark(cp) -> run + 1
        cp == ZERO_WIDTH_JOINER || cp == ZERO_WIDTH_NON_JOINER -> run
        else -> 0
    }

    /**
     * Normaliza un punto de código: devuelve -1 si hay que descartarlo, ' ' o
     * '\n' si es un espacio o un salto de cualquier tipo, o el propio punto.
     *
     * Se descarta toda la categoría Unicode «formato» (bidi, ancho cero, soft
     * hyphen, etiquetas, BOM…) salvo ZWJ y ZWNJ: sin ellos se rompen los emoji
     * compuestos y varias escrituras (persa, indias). Las zonas de uso privado,
     * los sustitutos sueltos y los no-caracteres tampoco tienen sentido en un dato del servidor.
     * No se descarta toda la categoría «sin asignar» (Cn): depende de la versión de Unicode
     * del dispositivo y borraría los emoji y letras recientes en móviles antiguos.
     */
    internal fun normalizeCodePoint(cp: Int): Int {
        when (cp) {
            0x09, 0x0B, 0x0C -> return ' '.code
            0x0A, 0x0D, 0x85, 0x2028, 0x2029 -> return '\n'.code
            ZERO_WIDTH_NON_JOINER, ZERO_WIDTH_JOINER -> return cp
            // Rellenos que se pintan en blanco pese a ser letras.
            0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0 -> return -1
        }
        val type = Character.getType(cp)
        return when {
            type == Character.CONTROL.toInt() -> -1
            type == Character.FORMAT.toInt() -> -1
            type == Character.SURROGATE.toInt() -> -1
            type == Character.PRIVATE_USE.toInt() -> -1
            // No-caracteres: U+FDD0..U+FDEF y U+xxFFFE/U+xxFFFF de cada plano.
            cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE -> -1
            type == Character.SPACE_SEPARATOR.toInt() -> ' '.code
            else -> cp
        }
    }
}
