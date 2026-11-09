package io.github.garfieldrockero.gastronomicon.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** El cuerpo multipart sale a una red con el token: su estructura no puede romperla nada del contenido. */
class MultipartBodyTest {

    private val boundary = "limiteDePrueba1234567890"

    private fun latin1(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Debía lanzar IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // bien
        }
    }

    @Test
    fun `el cuerpo tiene la estructura exacta de RFC 7578`() {
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2, 0x0D, 0x0A, 3)
        val built = MultipartBody.build(
            listOf(
                MultipartBody.Part.File("image", "image.png", "image/png", image),
                MultipartBody.Part.Field("extension", "png"),
            ),
            boundary,
        )
        assertEquals("multipart/form-data; boundary=$boundary", built.contentType)
        val expected = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"image.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n" +
            latin1(image) + "\r\n" +
            "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"extension\"\r\n\r\n" +
            "png\r\n" +
            "--$boundary--\r\n"
        assertEquals(expected, latin1(built.bytes))
    }

    @Test
    fun `un campo de texto se escribe en UTF-8`() {
        val built = MultipartBody.build(listOf(MultipartBody.Part.Field("extension", "ñ")), boundary)
        val text = String(built.bytes, Charsets.UTF_8)
        assertTrue(text, text.contains("\r\n\r\nñ\r\n--$boundary--\r\n"))
    }

    @Test
    fun `nombres de campo y de fichero fuera de la lista blanca se rechazan`() {
        val bad = listOf("a\"b", "a\r\nb", "a b", "", "a;b", "ñ", "a".repeat(65), "a\nContent-Type: x")
        for (name in bad) {
            rejects { MultipartBody.build(listOf(MultipartBody.Part.Field(name, "x")), boundary) }
            rejects {
                MultipartBody.build(listOf(MultipartBody.Part.File("image", name, "image/png", ByteArray(1))), boundary)
            }
        }
    }

    @Test
    fun `el tipo del fichero tiene que ser un tipo MIME simple`() {
        for (mime in listOf("image/png\r\nX: y", "png", "image/", "IMAGE/PNG", "image/png; x=y", "")) {
            rejects {
                MultipartBody.build(listOf(MultipartBody.Part.File("image", "a.png", mime, ByteArray(1))), boundary)
            }
        }
    }

    @Test
    fun `sin partes o con delimitador invalido se rechaza`() {
        rejects { MultipartBody.build(emptyList(), boundary) }
        for (bad in listOf("", "corto", "con espacio 1234567890", "con\r\nsalto123456789012", "a".repeat(71), "ñandú1234567890123")) {
            rejects { MultipartBody.build(listOf(MultipartBody.Part.Field("a", "b")), bad) }
        }
    }

    @Test
    fun `un contenido que lleva el delimitador se rechaza en vez de romper el cuerpo`() {
        val hostile = "xx--$boundary--yy".toByteArray()
        rejects { MultipartBody.build(listOf(MultipartBody.Part.File("image", "a.png", "image/png", hostile)), boundary) }
        rejects { MultipartBody.build(listOf(MultipartBody.Part.Field("a", "--$boundary")), boundary) }
    }

    @Test
    fun `el delimitador por defecto es distinto cada vez y valido`() {
        val one = MultipartBody.build(listOf(MultipartBody.Part.Field("a", "b")))
        val two = MultipartBody.build(listOf(MultipartBody.Part.Field("a", "b")))
        assertNotEquals(one.contentType, two.contentType)
        assertTrue(one.contentType, Regex("^multipart/form-data; boundary=[A-Za-z0-9]{16,70}$").matches(one.contentType))
    }

    @Test
    fun `toString no enseña los bytes`() {
        val built = MultipartBody.build(listOf(MultipartBody.Part.File("image", "a.png", "image/png", "SECRETO".toByteArray())), boundary)
        assertTrue(!built.toString().contains("SECRETO"))
    }
}
