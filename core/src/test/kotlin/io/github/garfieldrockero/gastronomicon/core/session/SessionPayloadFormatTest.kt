package io.github.garfieldrockero.gastronomicon.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionPayloadFormatTest {

    @Test
    fun plainRoundTrip() {
        val bytes = SessionPayloadFormat.encodePlain("https://mealie.example:443", "abc.DEF-123")
        val fields = SessionPayloadFormat.parsePlain(bytes)
        assertNotNull(fields)
        assertEquals("https://mealie.example:443", fields!!.serverUrl)
        assertEquals("abc.DEF-123", fields.token)
    }

    @Test
    fun plainRejectsNewlinesOnEncode() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionPayloadFormat.encodePlain("https://a\nb", "t")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionPayloadFormat.encodePlain("https://a", "t\nx")
        }
    }

    @Test
    fun plainRejectsMalformed() {
        fun parse(s: String) = SessionPayloadFormat.parsePlain(s.toByteArray(Charsets.UTF_8))
        assertNull(parse(""))
        assertNull(parse("1\nhttps://a"))
        assertNull(parse("2\nhttps://a\ntoken"))
        assertNull(parse("1\nhttps://a\ntoken\nextra"))
        assertNull(parse("1\n\ntoken"))
        assertNull(parse("1\nhttps://a\n"))
    }

    @Test
    fun plainRejectsInvalidUtf8() {
        assertNull(SessionPayloadFormat.parsePlain(byteArrayOf(0x31, 0x0A, 0xC3.toByte(), 0x28, 0x0A, 0x41)))
    }

    @Test
    fun envelopeRoundTrip() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(40) { (it * 3).toByte() }
        val parsed = SessionPayloadFormat.parseEnvelope(SessionPayloadFormat.encodeEnvelope(iv, ct))
        assertNotNull(parsed)
        assertArrayEquals(iv, parsed!!.iv)
        assertArrayEquals(ct, parsed.cipherText)
    }

    @Test
    fun envelopeRejectsMalformed() {
        val ok = SessionPayloadFormat.encodeEnvelope(ByteArray(12), ByteArray(20))
        assertNull(SessionPayloadFormat.parseEnvelope(null))
        assertNull(SessionPayloadFormat.parseEnvelope(""))
        assertNull(SessionPayloadFormat.parseEnvelope(ok.replaceFirst("1:", "2:")))
        assertNull(SessionPayloadFormat.parseEnvelope("$ok:extra"))
        assertNull(SessionPayloadFormat.parseEnvelope("1:!!!:!!!"))
        // IV de longitud incorrecta
        assertNull(SessionPayloadFormat.parseEnvelope(SessionPayloadFormat.encodeEnvelope(ByteArray(8), ByteArray(20))))
        // Cifrado vacío o más corto que la etiqueta GCM (16 bytes)
        assertNull(SessionPayloadFormat.parseEnvelope(SessionPayloadFormat.encodeEnvelope(ByteArray(12), ByteArray(0))))
        assertNull(SessionPayloadFormat.parseEnvelope(SessionPayloadFormat.encodeEnvelope(ByteArray(12), ByteArray(15))))
        assertNull(SessionPayloadFormat.parseEnvelope(SessionPayloadFormat.encodeEnvelope(ByteArray(12), ByteArray(16))))
        assertNotNull(SessionPayloadFormat.parseEnvelope(SessionPayloadFormat.encodeEnvelope(ByteArray(12), ByteArray(17))))
    }

    @Test
    fun toStringDoesNotLeak() {
        val f = SessionPayloadFormat.Fields("https://secret.example", "tok-secret")
        assertFalse(f.toString().contains("secret"))
        val e = SessionPayloadFormat.Envelope(ByteArray(12), ByteArray(3))
        assertFalse(e.toString().contains("0"))
    }
}
