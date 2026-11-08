package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import java.util.Locale

/** Formatos de imagen que la app acepta del servidor. SVG y el resto quedan fuera a propósito. */
enum class ImageType(val mimeType: String) {
    Webp("image/webp"),
    Jpeg("image/jpeg"),
    Png("image/png"),
}

/**
 * Resultado de [MealieClient.fetchImage]. No lleva texto del servidor: ni
 * cuerpo de error, ni URL, ni cabeceras. Los fallos de red se reducen a
 * [ConnectionProblem], igual que en el resto del núcleo.
 */
sealed interface ImageResult {

    /** Imagen comprobada: el tipo declarado y el contenido real coinciden. */
    class Success(val bytes: ByteArray, val type: ImageType) : ImageResult {
        // Sin los bytes: un ByteArray imprime su identidad y una imagen no es para un log.
        override fun toString(): String = "ImageResult.Success(type=${type.mimeType}, bytes=${bytes.size})"
    }

    /** 404, o un id que no es un UUID (en ese caso ni siquiera se pide nada). */
    data object NotFound : ImageResult

    /** 401 o 403: el token no vale o no tiene permiso. */
    data object Unauthorized : ImageResult

    /** Tipo no permitido, contenido que no es lo que dice ser o cuerpo vacío. */
    data object InvalidImage : ImageResult

    /** Pasa de [MealieClient.MAX_IMAGE_BYTES]. */
    data object TooLarge : ImageResult

    /** Cualquier otro fallo (red, TLS, 5xx, redirección bloqueada…). */
    data class Failure(val problem: ConnectionProblem) : ImageResult
}

/** Reconocimiento del tipo de imagen: por la cabecera y por los primeros bytes. */
internal object ImageTypes {

    /** El tipo si la cabecera `Content-Type` es exactamente uno permitido (se ignoran parámetros y mayúsculas). */
    fun fromContentType(header: String?): ImageType? {
        val base = header?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        return ImageType.values().firstOrNull { it.mimeType == base }
    }

    /**
     * El tipo según los primeros bytes. La cabecera la pone el servidor (o un
     * proxy) y no basta: un SVG o un HTML con `image/png` sería otra cosa.
     */
    fun fromMagic(bytes: ByteArray): ImageType? = when {
        startsWith(bytes, PNG_MAGIC) -> ImageType.Png
        startsWith(bytes, JPEG_MAGIC) -> ImageType.Jpeg
        // RIFF + 4 bytes de tamaño + «WEBP»: sin lo último sería un WAV o un AVI.
        bytes.size >= 12 && startsWith(bytes, RIFF) && matchesAt(bytes, 8, WEBP) -> ImageType.Webp
        else -> null
    }

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean = matchesAt(bytes, 0, prefix)

    private fun matchesAt(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (bytes.size < offset + expected.size) return false
        for (index in expected.indices) {
            if (bytes[offset + index] != expected[index]) return false
        }
        return true
    }
}
