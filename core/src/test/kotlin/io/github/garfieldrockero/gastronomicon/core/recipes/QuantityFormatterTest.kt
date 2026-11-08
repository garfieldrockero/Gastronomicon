package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityFormatterTest {
    private fun f(x: Double?) = QuantityFormatter.format(x)

    @Test
    fun `nula cero y negativa no muestran nada`() {
        assertEquals("", f(null))
        assertEquals("", f(0.0))
        assertEquals("", f(-0.0))
        assertEquals("", f(-1.5))
    }

    @Test
    fun `no finitos no muestran nada`() {
        assertEquals("", f(Double.NaN))
        assertEquals("", f(Double.POSITIVE_INFINITY))
        assertEquals("", f(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `enteros sin decimales`() {
        assertEquals("1", f(1.0))
        assertEquals("3", f(3.0))
        assertEquals("250", f(250.0))
    }

    @Test
    fun `fracciones simples`() {
        assertEquals("1/2", f(0.5))
        assertEquals("1/4", f(0.25))
        assertEquals("3/4", f(0.75))
        assertEquals("1/3", f(1.0 / 3))
        assertEquals("2/3", f(2.0 / 3))
        assertEquals("1/8", f(0.125))
    }

    @Test
    fun `números mixtos`() {
        assertEquals("1 1/2", f(1.5))
        assertEquals("2 1/4", f(2.25))
        assertEquals("12 1/2", f(12.5))
    }

    @Test
    fun `se tolera el error de coma flotante`() {
        assertEquals("1/3", f(0.33))
        assertEquals("1/3", f(0.3333333))
        assertEquals("2", f(1.999))
        assertEquals("1", f(1.001))
    }

    @Test
    fun `decimales con coma y sin ceros sobrantes`() {
        assertEquals("0,1", f(0.1))
        assertEquals("0,95", f(0.95))
        assertEquals("1,15", f(1.15))
        assertEquals("0,06", f(0.06))
        assertEquals("12,1", f(12.1))
        assertEquals("12,3", f(12.3))
    }

    @Test
    fun `cantidades grandes se redondean a entero`() {
        assertEquals("100", f(99.97))
        assertEquals("150", f(150.4))
        assertEquals("151", f(150.5))
        assertEquals("1000000", f(1_000_000.0))
    }

    @Test
    fun `cantidades diminutas no se quedan en cero`() {
        assertEquals("< 0,01", f(0.004))
        assertEquals("< 0,01", f(Double.MIN_VALUE))
        assertEquals("0,01", f(0.01))
        assertEquals("0,02", f(0.02))
    }

    @Test
    fun `nunca usa punto decimal ni notación científica`() {
        for (x in listOf(0.1, 1.15, 12.3, 1e-9, 1e6, 123456.789, Double.MAX_VALUE)) {
            val text = f(x)
            assertEquals(text, false, text.contains('.') || text.contains('E') || text.contains('e'))
        }
    }

    @Test
    fun `por encima del maximo no muestra nada y en el maximo es corto`() {
        assertEquals("1000000", f(ServingScaler.MAX_QUANTITY))
        assertEquals("", f(Math.nextUp(ServingScaler.MAX_QUANTITY)))
        assertEquals("", f(Double.MAX_VALUE))
        assertEquals("", f(1e300))
    }

    @Test
    fun `nunca devuelve notacion cientifica ni mas de unas pocas cifras`() {
        val samples = listOf(
            Double.MIN_VALUE, 1e-300, 1e-7, 0.009, 0.0123456789, 1.0 / 3, 99.999999, 123456.789,
            999_999.99, 1e6, 1e7, 1e20, -1e20, Double.NaN, Double.MAX_VALUE,
        )
        for (x in samples) {
            val out = f(x)
            assertTrue("Demasiado largo para $x: $out", out.length <= 12)
            assertTrue("Notación en $x: $out", 'E' !in out && 'e' !in out)
        }
    }
}
