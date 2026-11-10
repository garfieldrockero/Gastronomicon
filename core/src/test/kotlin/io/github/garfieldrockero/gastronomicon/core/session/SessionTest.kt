package io.github.garfieldrockero.gastronomicon.core.session

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionTest {

    private val secret = "SECRETO-reconocible-123"
    private val url = "https://cocina-secreta.example.org"
    private val session = Session(url, ApiToken(secret))

    @Test
    fun `toString no muestra el token ni la URL`() {
        val text = session.toString()
        assertFalse(text.contains(secret))
        assertFalse(text.contains("cocina-secreta"))
        assertFalse(text.contains("example.org"))
        assertFalse(text.contains(url))
    }

    @Test
    fun `toString conserva el esquema como pista de diagnostico`() {
        assertEquals("Session(scheme=https, server=***, token=***)", session.toString())
        val local = Session("http://192.168.1.50:9000", ApiToken(secret))
        assertTrue(local.toString().startsWith("Session(scheme=http,"))
        assertFalse(local.toString().contains("192.168"))
    }

    @Test
    fun `no se filtra en plantillas ni colecciones`() {
        assertFalse("sesion: $session".contains(secret))
        assertFalse(listOf(session).toString().contains(secret))
        assertFalse(mapOf("k" to session).toString().contains("cocina-secreta"))
    }

    @Test
    fun `iguales con la misma URL y el mismo token`() {
        val other = Session(url, ApiToken(secret))
        assertEquals(session, other)
        assertEquals(session.hashCode(), other.hashCode())
    }

    @Test
    fun `distintas si cambia la URL o el token`() {
        assertNotEquals(session, Session("https://otro.example.org", ApiToken(secret)))
        assertNotEquals(session, Session(url, ApiToken("otro-token")))
        assertNotEquals(session, null)
    }

    @Test
    fun `rechaza una URL sin normalizar sin repetirla en el mensaje`() {
        val raw = "https://Cocina-Secreta.example.org/"
        try {
            Session(raw, ApiToken(secret))
            fail("Debería rechazar la URL sin normalizar")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains("ocina-"))
            assertFalse(e.message.orEmpty().contains(secret))
        }
    }

    @Test
    fun `rechaza esquemas no http ni https y credenciales embebidas`() {
        for (bad in listOf("ftp://example.org", "https://user:pass@example.org", "", "javascript:alert(1)")) {
            try {
                Session(bad, ApiToken(secret))
                fail("Debería rechazar: $bad")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.orEmpty().contains("pass"))
            }
        }
    }

    @Test
    fun `rechaza una URL con subruta sin repetirla`() {
        // Mealie no admite subpath: una sesión guardada así ya no vale.
        try {
            Session("https://cocina-secreta.example.org/mealie", ApiToken(secret))
            fail("Una URL con ruta no debería admitirse")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains("mealie"))
        }
    }

    @Test
    fun `http hacia un nombre publico se rechaza`() {
        // La frontera de ServerUrl para http sigue aplicando: solo IP privadas.
        try {
            Session("http://example.org", ApiToken(secret))
            fail("http a un nombre no debería admitirse")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
