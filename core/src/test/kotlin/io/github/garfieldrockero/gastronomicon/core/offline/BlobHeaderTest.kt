package io.github.garfieldrockero.gastronomicon.core.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Formato en disco del almacén cifrado: cabecera en claro (clave + IV) y dónde
 * empieza el cuerpo. Es lo que decide si un fichero se sirve o se descarta, así
 * que tiene que rechazar cualquier cosa que no escribió esta implementación.
 */
class BlobHeaderTest {

    private val key = "rc1." + "a".repeat(64) + "." + "b".repeat(32)
    private val iv = ByteArray(BlobHeader.IV_BYTES) { it.toByte() }
    private val cipherText = ByteArray(40) { (it * 7).toByte() }

    /** Cabecera montada a mano, sin pasar por [BlobHeader.encode]. */
    private fun raw(keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val out = ByteArray(5 + keyBytes.size + 1 + ivBytes.size + 16)
        out[0] = 'G'.code.toByte()
        out[1] = 'B'.code.toByte()
        out[2] = '1'.code.toByte()
        out[3] = 1
        out[4] = keyBytes.size.toByte()
        keyBytes.copyInto(out, 5)
        out[5 + keyBytes.size] = ivBytes.size.toByte()
        ivBytes.copyInto(out, 5 + keyBytes.size + 1)
        return out
    }

    @Test
    fun `ida y vuelta conserva clave, IV y cuerpo`() {
        val bytes = BlobHeader.encode(key, iv, cipherText)!!
        val parsed = BlobHeader.parse(bytes, bytes.size)
        assertNotNull(parsed)
        val header = parsed!!
        assertEquals(key, header.key)
        assertArrayEquals(iv, header.iv)
        assertEquals(bytes.size - cipherText.size, header.bodyOffset)
        assertArrayEquals(cipherText, bytes.copyOfRange(header.bodyOffset, bytes.size))
    }

    @Test
    fun `la cabecera se lee con solo sus bytes de delante`() {
        // Es lo que hace el listado: lee un trozo del principio y no el cuerpo.
        val bytes = BlobHeader.encode(key, iv, cipherText)!!
        val parsed = BlobHeader.parse(bytes, BlobHeader.MAX_HEADER_BYTES)
        assertNotNull(parsed)
        val header = parsed!!
        assertEquals(key, header.key)
        assertNull(BlobHeader.parse(bytes, header.bodyOffset - 1))
    }

    @Test
    fun `rechaza marca y version desconocidas`() {
        val bytes = BlobHeader.encode(key, iv, cipherText)!!
        val otherMagic = bytes.copyOf()
        otherMagic[0] = 'X'.code.toByte()
        assertNull(BlobHeader.parse(otherMagic, otherMagic.size))
        val futureVersion = bytes.copyOf()
        futureVersion[BlobHeader.VERSION_INDEX] = 2
        assertNull(BlobHeader.parse(futureVersion, futureVersion.size))
    }

    @Test
    fun `rechaza un IV que no mide doce bytes`() {
        // Ni al escribir ni al leer: 13-16 bytes harian fallar el descifrado siempre.
        assertNull(BlobHeader.encode(key, ByteArray(13), cipherText))
        assertNull(BlobHeader.encode(key, ByteArray(11), cipherText))
        assertNull(BlobHeader.encode(key, ByteArray(16), cipherText))
        assertNull(BlobHeader.parse(raw("ok".toByteArray(Charsets.UTF_8), ByteArray(13)), 60))
        assertNull(BlobHeader.parse(raw("ok".toByteArray(Charsets.UTF_8), ByteArray(0)), 60))
    }

    @Test
    fun `rechaza una clave vacia, larga de mas o fuera de la lista blanca`() {
        assertNull(BlobHeader.encode("", iv, cipherText))
        assertNull(BlobHeader.encode("a".repeat(BlobKeys.MAX_LENGTH + 1), iv, cipherText))
        assertNull(BlobHeader.encode("../otra", iv, cipherText))
        assertNull(BlobHeader.parse(raw(ByteArray(0), iv), 60))
        assertNull(BlobHeader.parse(raw("../otra".toByteArray(Charsets.UTF_8), iv), 60))
        // La cabecera no se cree la longitud: la clave de dentro se valida igual.
        val bytes = raw("ok".toByteArray(Charsets.UTF_8), iv)
        bytes[BlobHeader.KEY_LENGTH_INDEX] = 0
        assertNull(BlobHeader.parse(bytes, bytes.size))
    }

    @Test
    fun `rechaza un fichero mas corto que la cabecera`() {
        val bytes = BlobHeader.encode(key, iv, cipherText)!!
        assertNull(BlobHeader.parse(bytes, 0))
        assertNull(BlobHeader.parse(bytes, BlobHeader.MIN_HEADER_BYTES - 1))
        assertNull(BlobHeader.parse(ByteArray(0), 0))
    }

    @Test
    fun `la cabecera montada a mano con clave valida se acepta`() {
        // Deja claro que el formato no depende de encode: es el contrato del fichero.
        val parsed = BlobHeader.parse(raw("ok".toByteArray(Charsets.UTF_8), iv), 60)
        assertNotNull(parsed)
        val header = parsed!!
        assertEquals("ok", header.key)
        assertArrayEquals(iv, header.iv)
    }
}
