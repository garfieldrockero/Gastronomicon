package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * Detección de metadatos en una imagen ya codificada.
 *
 * Es una defensa en profundidad: `app/` recodifica la foto y quita los EXIF
 * antes de subirla, pero un fallo allí no debe mandar las coordenadas de casa
 * al servidor. Se mira el contenedor, no el contenido de cada metadato:
 *
 * - **JPEG**: APP1 con EXIF («Exif\0\0») o XMP
 *   («http://ns.adobe.com/xap/1.0/» y el XMP extendido), APP13
 *   (IPTC/Photoshop) y comentarios COM.
 * - **PNG**: chunk `eXIf` y cualquier chunk de texto (`iTXt`, `tEXt`, `zTXt`),
 *   que es donde va el XMP («XML:com.adobe.xmp») y los comentarios.
 * - **WebP**: chunks `EXIF` y `XMP `, y el bit de EXIF o XMP de `VP8X` aunque
 *   falte el chunk correspondiente.
 *
 * La comprobación falla CERRADA: además de los metadatos, devuelve `true` (no
 * subir) si el fichero está truncado o malformado, si se llega al tope de
 * segmentos o chunks sin alcanzar el final, o si hay bytes después del fin de
 * la imagen (EOI en JPEG, IEND en PNG, tamaño RIFF en WebP). Así no pasan un
 * motion photo con vídeo incrustado ni una cabecera mentirosa. `app/` solo sube
 * un JPEG recodificado por ella, así que el núcleo puede ser estricto.
 *
 * La función es pura y no lanza nunca; los recorridos están acotados por el
 * tamaño del fichero y por topes de iteraciones. Un formato que no sea JPEG,
 * PNG o WebP devuelve `false`: `MealieClient.uploadImage` ya lo rechaza antes
 * por los bytes mágicos.
 */
object ImageMetadata {

    /**
     * ¿La imagen lleva metadatos o no es de fiar?
     *
     * `true` significa «no se sube». Un `false` solo es posible en un fichero
     * completo y bien formado de uno de los tres formatos admitidos.
     */
    fun hasMetadata(bytes: ByteArray): Boolean = when {
        isJpeg(bytes) -> jpegHasMetadata(bytes)
        isPng(bytes) -> pngHasMetadata(bytes)
        isWebp(bytes) -> webpHasMetadata(bytes)
        else -> false
    }

    /**
     * Alias obsoleto de [hasMetadata], conservado para no romper a quien ya
     * llamaba a la función. El nombre se quedó corto: también rechaza XMP,
     * IPTC, comentarios, chunks de texto y ficheros truncados o con datos
     * después del final.
     */
    fun hasExif(bytes: ByteArray): Boolean = hasMetadata(bytes)

    // ------------------------------------------------------------------ JPEG

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8

    /**
     * Recorre marcadores hasta EOI. No distingue entre cabecera y datos
     * comprimidos: después de SOS los marcadores de reinicio, el escape
     * «0xFF 0x00» y los marcadores de verdad se reconocen igual, así que el
     * mismo bucle sirve para ambos tramos y para un JPEG con varias pasadas.
     *
     * Entre segmentos se tolera basura buscando el siguiente 0xFF, como hacen
     * los decodificadores; el avance siempre es hacia delante y el número de
     * marcadores está acotado.
     */
    private fun jpegHasMetadata(bytes: ByteArray): Boolean {
        var offset = 2
        var segments = 0
        var sawScan = false
        while (true) {
            // Llegar al tope sin haber visto EOI es un fichero hostil o
            // truncado: se rechaza (fallo cerrado).
            if (segments >= MAX_SEGMENTS) return true

            val markerStart = nextMarkerOffset(bytes, offset)
            if (markerStart < 0) return true
            // Puede haber varios 0xFF de relleno antes del marcador.
            var codeOffset = markerStart + 1
            while (codeOffset < bytes.size && u8(bytes, codeOffset) == 0xFF) codeOffset++
            if (codeOffset >= bytes.size) return true
            val code = u8(bytes, codeOffset)
            if (code == 0x00) {
                // «0xFF 0x00» es un byte de datos escapado, no un marcador.
                offset = codeOffset + 1
                continue
            }
            segments++
            offset = codeOffset + 1

            when {
                // Sin SOS no hay imagen: el fichero está malformado.
                code == MARKER_EOI -> return !sawScan || offset < bytes.size
                // Marcadores sin campo de longitud.
                code == MARKER_TEM || code in MARKER_RST_FIRST..MARKER_RST_LAST -> Unit
                else -> {
                    val lengthOffset = offset
                    val payloadEnd = segmentEnd(bytes, lengthOffset) ?: return true
                    if (code == MARKER_SOS) sawScan = true
                    if (jpegSegmentHasMetadata(code, bytes, lengthOffset + 2, payloadEnd)) return true
                    offset = payloadEnd
                }
            }
        }
    }

    /**
     * Índice del primer 0xFF seguido de algo distinto de 0x00 desde [from], o
     * -1 si no queda ninguno. Un 0xFF pegado al final no es un marcador.
     */
    private fun nextMarkerOffset(bytes: ByteArray, from: Int): Int {
        var index = from
        while (index + 1 < bytes.size) {
            if (u8(bytes, index) != 0xFF) {
                index++
                continue
            }
            if (u8(bytes, index + 1) != 0x00) return index
            // Escape de datos: se saltan los dos bytes.
            index += 2
        }
        return -1
    }

    /**
     * Fin del segmento cuya longitud empieza en [lengthOffset] (justo después
     * del marcador), o null si está truncado o la longitud no tiene sentido.
     * La longitud incluye sus propios dos bytes.
     */
    private fun segmentEnd(bytes: ByteArray, lengthOffset: Int): Int? {
        if (lengthOffset < 0 || lengthOffset + 1 >= bytes.size) return null
        val length = (u8(bytes, lengthOffset) shl 8) or u8(bytes, lengthOffset + 1)
        if (length < 2) return null
        val end = lengthOffset.toLong() + length.toLong()
        if (end > bytes.size.toLong()) return null
        return end.toInt()
    }

    /** Solo se mira la cabecera del segmento; el contenido no se interpreta. */
    private fun jpegSegmentHasMetadata(
        code: Int,
        bytes: ByteArray,
        payloadStart: Int,
        payloadEnd: Int,
    ): Boolean {
        val payloadLength = payloadEnd - payloadStart
        return when (code) {
            MARKER_APP1 ->
                (payloadLength >= EXIF_HEADER.size && matchesAt(bytes, payloadStart, EXIF_HEADER)) ||
                    (payloadLength >= XMP_HEADER.size && matchesAt(bytes, payloadStart, XMP_HEADER)) ||
                    (payloadLength >= XMP_EXTENSION_HEADER.size &&
                        matchesAt(bytes, payloadStart, XMP_EXTENSION_HEADER))
            MARKER_APP13, MARKER_COM -> true
            else -> false
        }
    }

    // ------------------------------------------------------------------- PNG

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size && matchesAt(bytes, 0, PNG_SIGNATURE)

    /**
     * Los chunks PNG son «longitud (4 bytes, big-endian), tipo (4), datos,
     * CRC (4)». Cualquier longitud que se salga del fichero, IEND con datos o
     * bytes detrás de IEND se rechazan; sin IEND el fichero está truncado.
     */
    private fun pngHasMetadata(bytes: ByteArray): Boolean {
        var offset = PNG_SIGNATURE.size
        var chunks = 0
        while (true) {
            if (chunks >= MAX_CHUNKS) return true
            if (offset.toLong() + CHUNK_HEADER_SIZE.toLong() > bytes.size.toLong()) return true
            val length = uint32BE(bytes, offset)
            val typeOffset = offset + 4
            val dataEnd = offset.toLong() + CHUNK_HEADER_SIZE.toLong() + length
            val next = dataEnd + 4L
            if (next > bytes.size.toLong()) return true
            chunks++
            // El primer chunk de un PNG válido es siempre IHDR y mide 13 bytes.
            if (chunks == 1 && (!matchesAt(bytes, typeOffset, PNG_IHDR_CHUNK) || length != 13L)) {
                return true
            }
            when {
                matchesAt(bytes, typeOffset, PNG_EXIF_CHUNK) -> return true
                matchesAt(bytes, typeOffset, PNG_ITXT_CHUNK) -> return true
                matchesAt(bytes, typeOffset, PNG_TEXT_CHUNK) -> return true
                matchesAt(bytes, typeOffset, PNG_ZTXT_CHUNK) -> return true
                matchesAt(bytes, typeOffset, PNG_IEND_CHUNK) -> {
                    // IEND no lleva datos y tiene que ser el último chunk.
                    if (length != 0L) return true
                    return next < bytes.size.toLong()
                }
                else -> offset = next.toInt()
            }
        }
    }

    // ------------------------------------------------------------------ WebP

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            matchesAt(bytes, 0, RIFF) &&
            matchesAt(bytes, 8, WEBP)

    /**
     * Chunks RIFF: «fourcc (4), tamaño (4, little-endian), datos, relleno a
     * par». El tamaño declarado en la cabecera RIFF es la frontera real del
     * contenedor: si el fichero trae más bytes de los declarados (un motion
     * photo con el vídeo detrás) o menos, se rechaza.
     */
    private fun webpHasMetadata(bytes: ByteArray): Boolean {
        val declaredEnd = 8L + uint32LE(bytes, 4)
        if (declaredEnd != bytes.size.toLong()) return true
        // Un contenedor sin ningún chunk completo no es una imagen.
        if (bytes.size.toLong() < 12L + CHUNK_HEADER_SIZE.toLong()) return true

        var offset = 12
        var chunks = 0
        while (offset < bytes.size) {
            if (chunks >= MAX_CHUNKS) return true
            if (offset.toLong() + CHUNK_HEADER_SIZE.toLong() > bytes.size.toLong()) return true
            val length = uint32LE(bytes, offset + 4)
            val padded = length + (length and 1L)
            val next = offset.toLong() + CHUNK_HEADER_SIZE.toLong() + padded
            if (next > bytes.size.toLong()) return true
            chunks++
            when {
                matchesAt(bytes, offset, WEBP_EXIF_CHUNK) -> return true
                matchesAt(bytes, offset, WEBP_XMP_CHUNK) -> return true
                matchesAt(bytes, offset, WEBP_VP8X_CHUNK) -> {
                    // Primer byte de VP8X: los bits 0x08 (EXIF) y 0x04 (XMP)
                    // avisan de metadatos aunque el chunk no esté.
                    if (
                        length >= 1L &&
                        (u8(bytes, offset + CHUNK_HEADER_SIZE) and VP8X_METADATA_FLAGS) != 0
                    ) {
                        return true
                    }
                }
            }
            offset = next.toInt()
        }
        return false
    }

    // ----------------------------------------------------------------- ayudas

    /** Byte sin signo; quien llama ha comprobado el índice antes. */
    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private fun matchesAt(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || bytes.size - offset < expected.size) return false
        for (index in expected.indices) {
            if (bytes[offset + index] != expected[index]) return false
        }
        return true
    }

    private fun uint32BE(bytes: ByteArray, offset: Int): Long =
        (u8(bytes, offset).toLong() shl 24) or
            (u8(bytes, offset + 1).toLong() shl 16) or
            (u8(bytes, offset + 2).toLong() shl 8) or
            u8(bytes, offset + 3).toLong()

    private fun uint32LE(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or
            (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or
            (u8(bytes, offset + 3).toLong() shl 24)

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val EXIF_HEADER = byteArrayOf(
        'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
    )
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)
    private val XMP_EXTENSION_HEADER = "http://ns.adobe.com/xmp/extension/".toByteArray(Charsets.US_ASCII)
    private val PNG_IHDR_CHUNK = "IHDR".toByteArray(Charsets.US_ASCII)
    private val PNG_EXIF_CHUNK = "eXIf".toByteArray(Charsets.US_ASCII)
    private val PNG_ITXT_CHUNK = "iTXt".toByteArray(Charsets.US_ASCII)
    private val PNG_TEXT_CHUNK = "tEXt".toByteArray(Charsets.US_ASCII)
    private val PNG_ZTXT_CHUNK = "zTXt".toByteArray(Charsets.US_ASCII)
    private val PNG_IEND_CHUNK = "IEND".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val WEBP_EXIF_CHUNK = "EXIF".toByteArray(Charsets.US_ASCII)
    private val WEBP_XMP_CHUNK = "XMP ".toByteArray(Charsets.US_ASCII)
    private val WEBP_VP8X_CHUNK = "VP8X".toByteArray(Charsets.US_ASCII)

    private const val CHUNK_HEADER_SIZE = 8

    /** Bits de EXIF (0x08) y XMP (0x04) del primer byte de un chunk VP8X. */
    private const val VP8X_METADATA_FLAGS = 0x0C

    /** Un JPEG normal gasta decenas de segmentos; esto es una red contra un fichero hostil. */
    private const val MAX_SEGMENTS = 1_024

    /** Un PNG o WebP normal no llega a miles de chunks; red contra ficheros hostiles. */
    private const val MAX_CHUNKS = 4_096

    private const val MARKER_TEM = 0x01

    /** Comentario JPEG. */
    private const val MARKER_COM = 0xFE

    private const val MARKER_EOI = 0xD9
    private const val MARKER_SOS = 0xDA
    private const val MARKER_RST_FIRST = 0xD0
    private const val MARKER_RST_LAST = 0xD7
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_APP13 = 0xED
}
