package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * Plan de recodificado de una foto antes de subirla.
 *
 * La parte de Android (decodificar el bitmap, girarlo, comprimirlo y borrar
 * los EXIF) vive en `app/`; aquí solo están las cuentas, que son lógica y se
 * pueden probar sin emulador. El orden que espera `app/` es:
 *
 * 1. `BitmapFactory.Options.inSampleSize = plan.inSampleSize` al decodificar
 *    (evita cargar en memoria los megapíxeles de una foto de móvil).
 * 2. Escalar el bitmap decodificado a `plan.width` × `plan.height` (nunca
 *    hacia arriba: si la foto ya era pequeña, se queda como está).
 * 3. Comprimir con las calidades de [RecodePlan.Ready.jpegQualities] en ese
 *    orden y quedarse con la primera que quepa en
 *    [RecodePlan.Ready.maxOutputBytes]; si ninguna cabe, no se sube.
 *
 * Se descarta una foto de entrada por encima de [MAX_INPUT_PIXELS]: decodificar
 * 50 megapíxeles en un móvil modesto puede agotar la memoria antes de llegar a
 * escalar.
 */
object ImageRecodePlan {

    /** Lado máximo de la imagen que se sube, en píxeles. */
    const val MAX_SIDE = 2048

    /** Tope de píxeles de la imagen de ENTRADA; por encima ni se intenta decodificar. */
    const val MAX_INPUT_PIXELS = 50_000_000L

    /**
     * Tope de bytes del JPEG de salida. Es el mismo que acepta la subida
     * (`MealieClient.MAX_IMAGE_BYTES`); `ImageRecodePlanTest` comprueba que no
     * se separen.
     */
    const val MAX_OUTPUT_BYTES = 5L * 1024 * 1024

    /** Calidades JPEG que prueba `app/`, de mejor a peor. */
    val JPEG_QUALITIES: List<Int> = listOf(85, 75, 65, 55)

    /**
     * Cuentas para una imagen de [sourceWidth] × [sourceHeight].
     *
     * [sourceWidth] y [sourceHeight] son los que se leen de la cabecera con
     * `inJustDecodeBounds = true`; no pueden ser cero ni negativos.
     */
    fun plan(sourceWidth: Int, sourceHeight: Int): RecodePlan {
        if (sourceWidth <= 0 || sourceHeight <= 0) return RecodePlan.InvalidDimensions
        // En Long: Int.MAX_VALUE × Int.MAX_VALUE no cabe en Int.
        val pixels = sourceWidth.toLong() * sourceHeight.toLong()
        if (pixels > MAX_INPUT_PIXELS) return RecodePlan.TooManyPixels(pixels)
        val sample = inSampleSize(sourceWidth, sourceHeight)
        val decodedWidth = ceilDiv(sourceWidth, sample)
        val decodedHeight = ceilDiv(sourceHeight, sample)
        val (width, height) = scaledDimensions(decodedWidth, decodedHeight)
        return RecodePlan.Ready(
            inSampleSize = sample,
            width = width,
            height = height,
            jpegQualities = JPEG_QUALITIES,
            maxOutputBytes = MAX_OUTPUT_BYTES,
        )
    }

    /**
     * El mayor divisor potencia de dos que deja el lado largo decodificado en
     * [MAX_SIDE] o por encima: así se decodifica lo mínimo imprescindible y
     * luego se ajusta exacto, sin perder resolución de golpe.
     *
     * Con dimensiones no válidas devuelve 1 (no se puede subir, pero la cuenta
     * no revienta). El bucle termina siempre: cada vuelta duplica el divisor y
     * la condición deja de cumplirse mucho antes de desbordar.
     */
    fun inSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val longest = maxOf(width, height).toLong()
        var sample = 1
        while (ceilDiv(longest, sample.toLong() * 2L) >= MAX_SIDE) {
            sample *= 2
        }
        return sample
    }

    // ------------------------------------------------------------- privadas

    /** División entera hacia arriba, sin desbordar: todo va en Long. */
    private fun ceilDiv(value: Int, divisor: Int): Int =
        ceilDiv(value.toLong(), divisor.toLong()).toInt()

    private fun ceilDiv(value: Long, divisor: Long): Long =
        if (divisor <= 0) value else (value + divisor - 1) / divisor

    /**
     * Escala para que el lado largo quede en [MAX_SIDE], como mucho. Si la
     * imagen ya es más pequeña, no se toca (no se amplía).
     */
    private fun scaledDimensions(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= MAX_SIDE) return width to height
        return if (width >= height) {
            MAX_SIDE to proportional(height, width)
        } else {
            proportional(width, height) to MAX_SIDE
        }
    }

    /**
     * El lado corto proporcional al largo, redondeado al píxel más cercano y
     * con un mínimo de 1. En Long: ni el producto ni la suma desbordan.
     */
    private fun proportional(shortSide: Int, longSide: Int): Int {
        if (longSide <= 0) return 1
        val scaled = (shortSide.toLong() * MAX_SIDE + longSide / 2L) / longSide.toLong()
        return scaled.coerceIn(1L, MAX_SIDE.toLong()).toInt()
    }
}

/** Resultado de [ImageRecodePlan.plan]. */
sealed interface RecodePlan {

    /** Hay que decodificar con [inSampleSize] y escalar a [width] × [height]. */
    data class Ready(
        val inSampleSize: Int,
        val width: Int,
        val height: Int,
        val jpegQualities: List<Int>,
        val maxOutputBytes: Long,
    ) : RecodePlan

    /** Ancho o alto cero o negativo: la cabecera no se ha leído bien. */
    data object InvalidDimensions : RecodePlan

    /** La imagen de entrada pasa de [ImageRecodePlan.MAX_INPUT_PIXELS]. */
    data class TooManyPixels(val pixels: Long) : RecodePlan
}
