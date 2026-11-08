package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServingScalerTest {

    @Test
    fun `dobla y reduce a la mitad`() {
        assertEquals(400.0, ServingScaler.scale(200.0, 2.0, 4.0)!!, 1e-9)
        assertEquals(100.0, ServingScaler.scale(200.0, 4.0, 2.0)!!, 1e-9)
        assertEquals(200.0, ServingScaler.scale(200.0, 4.0, 4.0)!!, 1e-9)
    }

    @Test
    fun `cantidad nula da nulo`() {
        assertNull(ServingScaler.scale(null, 2.0, 4.0))
    }

    @Test
    fun `cantidad cero se queda en cero`() {
        assertEquals(0.0, ServingScaler.scale(0.0, 2.0, 4.0)!!, 0.0)
        assertEquals(0.0, ServingScaler.scale(0.0, null, null)!!, 0.0)
    }

    @Test
    fun `cantidad negativa o rota da nulo`() {
        assertNull(ServingScaler.scale(-1.0, 2.0, 4.0))
        assertNull(ServingScaler.scale(Double.NaN, 2.0, 4.0))
        assertNull(ServingScaler.scale(Double.POSITIVE_INFINITY, 2.0, 4.0))
    }

    @Test
    fun `fracciones se escalan sin perder precisión útil`() {
        assertEquals(1.5, ServingScaler.scale(0.5, 2.0, 6.0)!!, 1e-9)
        assertEquals("1 1/2", ServingScaler.scaleAndFormat(0.5, 2.0, 6.0))
        assertEquals("1/3", ServingScaler.scaleAndFormat(1.0, 3.0, 1.0))
    }

    @Test
    fun `raciones originales inválidas devuelven la cantidad sin escalar`() {
        assertEquals(200.0, ServingScaler.scale(200.0, null, 4.0)!!, 0.0)
        assertEquals(200.0, ServingScaler.scale(200.0, 0.0, 4.0)!!, 0.0)
        assertEquals(200.0, ServingScaler.scale(200.0, -2.0, 4.0)!!, 0.0)
        assertEquals(200.0, ServingScaler.scale(200.0, Double.NaN, 4.0)!!, 0.0)
    }

    @Test
    fun `raciones objetivo inválidas dan nulo`() {
        assertNull(ServingScaler.scale(200.0, 2.0, null))
        assertNull(ServingScaler.scale(200.0, 2.0, 0.0))
        assertNull(ServingScaler.scale(200.0, 2.0, -1.0))
        assertNull(ServingScaler.scale(200.0, 2.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `un desborde da nulo y no infinito`() {
        assertNull(ServingScaler.scale(1e300, 1.0, 1e300))
        assertNull(ServingScaler.scale(500_000.0, 1.0, 10.0))
        assertNull(ServingScaler.scale(2.0, Double.MIN_VALUE, 1e300))
        assertEquals("", ServingScaler.scaleAndFormat(500_000.0, 1.0, 10.0))
    }

    @Test
    fun `un resultado diminuto no es cero`() {
        assertEquals("< 0,01", ServingScaler.scaleAndFormat(1.0, 1000.0, 1.0))
    }
}
