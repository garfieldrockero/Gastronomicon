package io.github.garfieldrockero.gastronomicon.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PlainTextSanitizerTest {

    private fun clean(raw: String?, max: Int = 100, newlines: Boolean = false) =
        PlainTextSanitizer.sanitize(raw, max, newlines)

    @Test
    fun `nulo y vacio dan cadena vacia`() {
        assertEquals("", clean(null))
        assertEquals("", clean(""))
        assertEquals("", clean("   "))
    }

    @Test
    fun `un texto normal no cambia`() {
        assertEquals("Tarta de queso", clean("Tarta de queso"))
        assertEquals("Niño éxito \u65E5\u672C", clean("Niño éxito \u65E5\u672C"))
    }

    @Test
    fun `no interpreta html`() {
        assertEquals("<b>x</b> &amp;", clean("<b>x</b> &amp;"))
    }

    @Test
    fun `quita control y recorta espacios`() {
        assertEquals("Tarta de queso", clean("  Tarta\u0000 de\u202E queso  "))
        assertEquals("ab", clean("a\u0001\u0007\u007Fb"))
        assertEquals("ab", clean("a\u0080\u009Fb"))
    }

    @Test
    fun `quita bidi e invisibles`() {
        assertEquals("ab", clean("a\u202A\u202B\u202C\u202D\u202Eb"))
        assertEquals("ab", clean("a\u2066\u2067\u2068\u2069b"))
        assertEquals("ab", clean("a\u200E\u200F\u061Cb"))
        assertEquals("ab", clean("a\u200B\u2060\uFEFF\u00AD\u180Eb"))
        assertEquals("ab", clean("a\u2061\u2062\u2063\u2064b"))
        assertEquals("ab", clean("a\u3164\uFFA0\u115F\u1160\u034Fb"))
    }

    @Test
    fun `quita los caracteres de etiqueta y el uso privado`() {
        val tag = String(Character.toChars(0xE0041))
        assertEquals("ab", clean("a${tag}b"))
        assertEquals("ab", clean("a\uE000b"))
    }

    @Test
    fun `conserva los emoji compuestos`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, clean(family))
        assertEquals("\u2764\uFE0F", clean("\u2764\uFE0F"))
    }

    @Test
    fun `quita sustitutos sueltos`() {
        assertEquals("ab", clean("a\uD800b"))
        assertEquals("ab", clean("a\uDC00b"))
    }

    @Test
    fun `los espacios raros pasan a espacio normal y se colapsan`() {
        assertEquals("a b", clean("a\u00A0\u00A0\u2003\u3000b"))
        assertEquals("a b", clean("a \t  b"))
    }

    @Test
    fun `sin saltos permitidos todo salto es un espacio`() {
        assertEquals("a b", clean("a\nb"))
        assertEquals("a b", clean("a\r\nb"))
        assertEquals("a b", clean("a\u2028b"))
        assertEquals("a b", clean("\n\na\n\n\nb\n"))
    }

    @Test
    fun `con saltos permitidos se conservan hasta dos`() {
        assertEquals("a\nb", clean("a\nb", newlines = true))
        assertEquals("a\nb", clean("a\r\nb", newlines = true))
        assertEquals("a\n\nb", clean("a\n\n\n\nb", newlines = true))
        assertEquals("a\nb", clean("\n a  \n  b \n", newlines = true))
        assertEquals("a\nb", clean("a\u2029b", newlines = true))
    }

    @Test
    fun `el limite cuenta puntos de codigo`() {
        assertEquals("abc", clean("abcdef", max = 3))
        assertEquals("ab\uD83D\uDE00", clean("ab\uD83D\uDE00cd", max = 3))
        assertEquals("ab", clean("ab\uD83D\uDE00cd", max = 2))
    }

    @Test
    fun `el recorte no deja separadores colgando`() {
        assertEquals("ab", clean("ab cd", max = 3))
        assertEquals("ab c", clean("ab cd", max = 4))
    }

    @Test
    fun `un texto enorme se recorta`() {
        val result = clean("a".repeat(1_000_000), max = 200)
        assertEquals(200, result.length)
    }

    @Test
    fun `un limite no positivo es un error de programacion`() {
        assertThrows(IllegalArgumentException::class.java) { PlainTextSanitizer.sanitize("x", 0) }
        assertThrows(IllegalArgumentException::class.java) { PlainTextSanitizer.sanitize("x", -1) }
    }

    @Test
    fun `el limite por defecto es el esperado`() {
        val result = PlainTextSanitizer.sanitize("a".repeat(PlainTextSanitizer.DEFAULT_MAX_LENGTH + 10))
        assertEquals(PlainTextSanitizer.DEFAULT_MAX_LENGTH, result.length)
        assertFalse(result.isEmpty())
    }

    @Test
    fun `las marcas combinantes se limitan a cuatro seguidas`() {
        assertEquals("a" + "\u0301".repeat(4), clean("a" + "\u0301".repeat(50)))
        assertEquals("a\u0301\u0302\u0303\u0304b", clean("a\u0301\u0302\u0303\u0304\u0305\u0306b"))
        // Cada letra recupera su cupo.
        assertEquals("a" + "\u0301".repeat(4) + "b" + "\u0301".repeat(2), clean("a" + "\u0301".repeat(9) + "b\u0301\u0301"))
        // Los acentos normales no se tocan.
        assertEquals("e\u0301", clean("e\u0301"))
        assertEquals("Niño", clean("Niño"))
    }

    @Test
    fun `las marcas fuera del plano basico tambien cuentan como puntos de codigo`() {
        val mark = "\uD834\uDD67" // U+1D167, marca combinante
        assertEquals("a" + mark.repeat(4), clean("a" + mark.repeat(30)))
        // Nunca queda un sustituto partido.
        val out = clean("a" + mark.repeat(30))
        assertEquals(5, out.codePointCount(0, out.length))
    }

    @Test
    fun `zwj y zwnj no esquivan el tope de marcas`() {
        val joiner = "\u200D"
        val out = clean("a" + "\u0301$joiner".repeat(20))
        assertEquals(4, out.count { it == '\u0301' })
    }

    @Test
    fun `los emoji compuestos y el selector de variacion se conservan`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, clean(family))
        assertEquals("\u2764\uFE0F", clean("\u2764\uFE0F"))
        assertEquals("a\u200Db\u200Cc", clean("a\u200Db\u200Cc"))
    }

    @Test
    fun `los no-caracteres se descartan`() {
        assertEquals("ab", clean("a\uFFFFb"))
        assertEquals("ab", clean("a\uFFFEb"))
        assertEquals("ab", clean("a\uFDD0b"))
        assertEquals("ab", clean("a\uFDEFb"))
        // U+2FFFE y U+10FFFF: no-caracteres fuera del plano básico.
        assertEquals("ab", clean("a\uD87F\uDFFEb"))
        assertEquals("ab", clean("a\uDBFF\uDFFFb"))
        // Los vecinos de los rangos de no-caracteres se conservan.
        assertEquals("a\uFDCFb\uFDF0c", clean("a\uFDCFb\uFDF0c"))
    }

    @Test
    fun `un emoji reciente no se pierde aunque el dispositivo no lo conozca`() {
        // U+1FAE8 (cara temblorosa, Unicode 15) y U+1FAC3: dependen de la versión del móvil.
        val recent = "\uD83E\uDEE8 \uD83E\uDEC3"
        assertEquals(recent, clean(recent))
    }

    @Test
    fun `el zalgo en html tambien se limita`() {
        val out = HtmlSanitizer.sanitize("<p>a" + "\u0301".repeat(100) + "</p>").toPlainText()
        assertEquals("a" + "\u0301".repeat(4), out)
    }
}
