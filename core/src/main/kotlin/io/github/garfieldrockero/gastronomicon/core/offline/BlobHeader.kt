package io.github.garfieldrockero.gastronomicon.core.offline

/**
 * Formato en disco de una entrada del [EncryptedBlobStore].
 *
 * Es Kotlin puro (sin Android) para poder probarlo con JUnit, igual que
 * `SessionPayloadFormat`: aquí vive el formato y `app/platform` pone el Keystore
 * y el cifrado. Una entrada es:
 *
 * ```
 * [0..2] "GB1" | [3] versión 1 | [4] longitud de la clave | clave UTF-8
 * | longitud del IV | IV | cuerpo cifrado + etiqueta GCM
 * ```
 *
 * La cabecera va en claro a propósito: `listKeys` tiene que poder enumerar y
 * borrar entradas con el Keystore roto o la clave perdida, sin descifrar nada.
 * La clave del blob no es un secreto ([BlobKeys]): es un identificador opaco con
 * prefijo y hashes, sin URL, sin token y sin nombre de receta. Lo que protege el
 * contenido es el cifrado del cuerpo, y el AAD sigue impidiendo mover un cuerpo
 * de una entrada a otra.
 *
 * El IV mide **siempre** [IV_BYTES] bytes: es lo que exige AES-GCM con el
 * Keystore de Android. Cualquier otra longitud es un fichero roto y se rechaza
 * al leerlo, en vez de aceptarlo y fallar siempre al descifrar con un error que
 * parecería transitorio.
 */
object BlobHeader {

    /** Marca del fichero: dice que lo escribió esta implementación. */
    private val MAGIC: ByteArray = byteArrayOf('G'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    const val VERSION = 1
    const val VERSION_INDEX = 3
    const val KEY_LENGTH_INDEX = 4

    /** Cabecera: marca (3), versión, longitud de la clave, clave, longitud del IV, IV. */
    const val KEY_START = 5

    /** Longitud del IV: la que exige AES-GCM en el Keystore. Ni más ni menos. */
    const val IV_BYTES = 12

    /** Cabecera más corta posible: marca, versión, longitudes, clave de 1 byte e IV. */
    const val MIN_HEADER_BYTES = KEY_START + 1 + 1 + IV_BYTES

    /** Cabecera más larga posible: con la clave más larga que admite [BlobKeys]. */
    const val MAX_HEADER_BYTES = KEY_START + BlobKeys.MAX_LENGTH + 1 + IV_BYTES

    /** Cabecera ya separada: la clave, el IV y dónde empieza el cuerpo cifrado. */
    class Parsed(val key: String, val iv: ByteArray, val bodyOffset: Int) {
        // La clave no es un secreto, pero un resultado puede acabar en un informe.
        override fun toString(): String = "Parsed"
    }

    /**
     * Monta la entrada completa. Null si [key] no es válida o [iv] no mide
     * [IV_BYTES]: mejor no escribir nada que escribir algo que luego no se podrá
     * leer.
     */
    fun encode(key: String, iv: ByteArray, cipherText: ByteArray): ByteArray? {
        if (!BlobKeys.isValid(key) || iv.size != IV_BYTES) return null
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val bodyOffset = KEY_START + keyBytes.size + 1 + iv.size
        val out = ByteArray(bodyOffset + cipherText.size)
        MAGIC.copyInto(out, 0)
        out[VERSION_INDEX] = VERSION.toByte()
        // Longitudes sin signo: se escriben y se leen con `and 0xFF`.
        out[KEY_LENGTH_INDEX] = (keyBytes.size and 0xFF).toByte()
        keyBytes.copyInto(out, KEY_START)
        out[KEY_START + keyBytes.size] = (iv.size and 0xFF).toByte()
        iv.copyInto(out, KEY_START + keyBytes.size + 1)
        cipherText.copyInto(out, bodyOffset)
        return out
    }

    /**
     * Separa la cabecera de [bytes], de la que solo hay [available] bytes leídos
     * (se puede leer un trozo del principio del fichero para listar claves sin
     * leer el cuerpo). Null si no cuadra: marca, versión, clave o IV.
     */
    fun parse(bytes: ByteArray, available: Int): Parsed? {
        val limit = minOf(available, bytes.size)
        if (limit < MIN_HEADER_BYTES) return null
        for (index in MAGIC.indices) {
            if (bytes[index] != MAGIC[index]) return null
        }
        if ((bytes[VERSION_INDEX].toInt() and 0xFF) != VERSION) return null
        val keyLength = bytes[KEY_LENGTH_INDEX].toInt() and 0xFF
        if (keyLength < 1 || keyLength > BlobKeys.MAX_LENGTH) return null
        val keyEnd = KEY_START + keyLength
        if (limit < keyEnd + 1) return null
        val key = bytes.copyOfRange(KEY_START, keyEnd).toString(Charsets.UTF_8)
        if (!BlobKeys.isValid(key)) return null
        val ivLength = bytes[keyEnd].toInt() and 0xFF
        if (ivLength != IV_BYTES) return null
        val ivStart = keyEnd + 1
        val ivEnd = ivStart + ivLength
        if (limit < ivEnd) return null
        return Parsed(key, bytes.copyOfRange(ivStart, ivEnd), ivEnd)
    }
}
