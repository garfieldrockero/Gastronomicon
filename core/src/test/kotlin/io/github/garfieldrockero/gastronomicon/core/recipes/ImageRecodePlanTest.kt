package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuentas del recodificado: lado máximo, `inSampleSize` potencia de dos,
 * dimensiones finales, tope de píxeles de entrada, escalera de calidad y tope
 * de bytes. Incluye entradas absurdas: cero, negativas y `Int.MAX_VALUE`.
 */
class ImageRecodePlanTest {

    private fun ready(width: Int, height: Int): RecodePlan.Ready =
        ImageRecodePlan.plan(width, height) as RecodePlan.Ready

    @Test
    fun `una foto pequena no se toca`() {
        val plan = ready(800, 600)
        assertEquals(1, plan.inSampleSize)
        assertEquals(800, plan.width)
        assertEquals(600, plan.height)
    }

    @Test
    fun `el lado maximo es 2048 y una foto de 4 MP se reduce a la mitad`() {
        val plan = ready(4096, 3072)
        assertEquals(2, plan.inSampleSize)
        assertEquals(2048, plan.width)
        assertEquals(1536, plan.height)
    }

    @Test
    fun `el lado largo manda y el corto se redondea sin ampliar`() {
        val horizontal = ready(3000, 1000)
        assertEquals(1, horizontal.inSampleSize)
        assertEquals(2048, horizontal.width)
        assertEquals(683, horizontal.height)

        val vertical = ready(1000, 3000)
        assertEquals(1, vertical.inSampleSize)
        assertEquals(683, vertical.width)
        assertEquals(2048, vertical.height)

        // 2049 no llega a duplicar el muestreo; se ajusta al escalar.
        val justOver = ready(2049, 1000)
        assertEquals(1, justOver.inSampleSize)
        assertEquals(2048, justOver.width)
        assertEquals(1000, justOver.height)

        // Clavado en el máximo, sin escalar.
        val exact = ready(2048, 1000)
        assertEquals(1, exact.inSampleSize)
        assertEquals(2048, exact.width)
        assertEquals(1000, exact.height)
    }

    @Test
    fun `el plan trae la escalera de calidad y el tope de bytes de la subida`() {
        val plan = ready(4096, 3072)
        assertEquals(listOf(85, 75, 65, 55), plan.jpegQualities)
        assertEquals(5L * 1024 * 1024, plan.maxOutputBytes)
        // No puede separarse del tope que acepta la subida.
        assertEquals(MealieClient.MAX_IMAGE_BYTES, plan.maxOutputBytes)
        assertEquals(2048, ImageRecodePlan.MAX_SIDE)
    }

    @Test
    fun `el tope de pixeles de entrada rechaza lo que no se puede decodificar`() {
        // Justo en el tope: 50 megapíxeles se aceptan.
        val atLimit = ImageRecodePlan.plan(10_000, 5_000)
        assertTrue(atLimit is RecodePlan.Ready)
        // Un píxel más, no.
        val over = ImageRecodePlan.plan(10_001, 5_000) as RecodePlan.TooManyPixels
        assertEquals(50_005_000L, over.pixels)
    }

    @Test
    fun `dimensiones cero o negativas no son un plan`() {
        val cases = listOf(
            0 to 100,
            100 to 0,
            -1 to 100,
            100 to -1,
            Int.MIN_VALUE to Int.MIN_VALUE,
        )
        for ((width, height) in cases) {
            assertEquals(
                "$width x $height",
                RecodePlan.InvalidDimensions,
                ImageRecodePlan.plan(width, height),
            )
        }
        assertEquals(1, ImageRecodePlan.inSampleSize(0, 0))
        assertEquals(1, ImageRecodePlan.inSampleSize(Int.MIN_VALUE, 10))
    }

    @Test
    fun `Int MAX no desborda en el tope de pixeles`() {
        val result = ImageRecodePlan.plan(Int.MAX_VALUE, Int.MAX_VALUE) as RecodePlan.TooManyPixels
        // 2147483647 × 2147483647 cabe en Long y es lo que se compara.
        assertEquals(4_611_686_014_132_420_609L, result.pixels)
    }

    @Test
    fun `inSampleSize siempre es una potencia de dos y no desborda`() {
        val cases = listOf(
            1 to 1,
            2048 to 2048,
            4096 to 4096,
            8192 to 8192,
            5000 to 4000,
            Int.MAX_VALUE to 1,
            Int.MAX_VALUE to Int.MAX_VALUE,
        )
        for ((width, height) in cases) {
            val sample = ImageRecodePlan.inSampleSize(width, height)
            assertTrue("$width x $height -> $sample", sample > 0)
            assertTrue("$width x $height -> $sample no es potencia de dos", (sample and (sample - 1)) == 0)
            // Con el muestreo elegido, el lado largo decodificado no baja del
            // máximo si la foto era mayor; una pequeña no se amplía.
            val longest = maxOf(width, height).toLong()
            val decoded = (longest + sample - 1) / sample
            if (longest >= ImageRecodePlan.MAX_SIDE) {
                assertTrue("$width x $height -> $sample deja $decoded", decoded >= ImageRecodePlan.MAX_SIDE)
            } else {
                assertEquals("$width x $height -> $sample", longest, decoded)
            }
        }
        assertEquals(1, ImageRecodePlan.inSampleSize(2048, 2048))
        assertEquals(2, ImageRecodePlan.inSampleSize(4096, 4096))
        assertEquals(4, ImageRecodePlan.inSampleSize(8192, 8192))
        assertEquals(2, ImageRecodePlan.inSampleSize(5000, 4000))
    }
}
