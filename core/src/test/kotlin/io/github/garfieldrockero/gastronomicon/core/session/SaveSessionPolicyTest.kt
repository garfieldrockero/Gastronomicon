package io.github.garfieldrockero.gastronomicon.core.session

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.config.ServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class SaveSessionPolicyTest {

    private val saved = "https://mealie.example.com:8443/app"

    private fun decide(new: String, empty: Boolean, savedUrl: String? = saved) =
        SaveSessionPolicy.decide(savedUrl, new, empty)

    @Test
    fun `mismo origen y token vacio conserva el guardado sin probar`() {
        assertEquals(SaveDecision.KeepSavedToken, decide("https://mealie.example.com:8443/app", true))
        // La ruta no cuenta, ni las mayúsculas, ni el puerto por defecto explícito.
        assertEquals(SaveDecision.KeepSavedToken, decide("HTTPS://Mealie.Example.com:8443/otra", true))
        assertEquals(
            SaveDecision.KeepSavedToken,
            decide("https://a.example.com", true, "https://a.example.com:443"),
        )
    }

    @Test
    fun `cualquier cambio de origen con token vacio exige probar`() {
        val changes = listOf(
            "https://mealie.example.org:8443", // host
            "https://mealie.example.com", // puerto (443 frente a 8443)
            "https://mealie.example.com:9443", // puerto
            "https://mealie.example.com.evil.com:8443", // host con sufijo
            "http://192.168.1.5:8443", // esquema y host
        )
        for (new in changes) {
            assertEquals(new, SaveDecision.TestThenSaveWithSavedToken, decide(new, true))
        }
    }

    @Test
    fun `cambio solo de esquema exige probar`() {
        assertEquals(
            SaveDecision.TestThenSaveWithSavedToken,
            decide("https://10.0.0.2:443", true, "http://10.0.0.2:443"),
        )
    }

    @Test
    fun `con token escrito se usa el escrito`() {
        assertEquals(SaveDecision.UseTypedToken, decide("https://mealie.example.com:8443", false))
        assertEquals(SaveDecision.UseTypedToken, decide("https://otro.example.com", false))
        assertEquals(SaveDecision.UseTypedToken, decide("https://otro.example.com", false, null))
    }

    @Test
    fun `sin sesion guardada y token vacio hace falta el token`() {
        assertEquals(SaveDecision.TokenRequired, decide("https://mealie.example.com", true, null))
    }

    @Test
    fun `una sesion guardada ilegible cuenta como sin sesion`() {
        assertEquals(SaveDecision.TokenRequired, decide("https://mealie.example.com", true, "basura"))
    }

    @Test
    fun `una url nueva invalida lanza sin repetir la entrada`() {
        val secret = "http://u:tok-secreto-123@x.example.com"
        try {
            decide(secret, true)
            fail("Debía lanzar")
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message.orEmpty().contains("tok-secreto-123"))
        }
    }

    @Test
    fun `la version con origenes se comporta igual`() {
        val a = ServerOrigin.of("https://a.example.com")!!
        val b = ServerOrigin.of("https://b.example.com")!!
        assertEquals(SaveDecision.KeepSavedToken, SaveSessionPolicy.decide(a, a, true))
        assertEquals(SaveDecision.TestThenSaveWithSavedToken, SaveSessionPolicy.decide(a, b, true))
        assertEquals(SaveDecision.TokenRequired, SaveSessionPolicy.decide(null, b, true))
    }

    // ------------------------------------------------- borrado e interruptor

    private class Switch(override var allowLocalHttp: Boolean) : LocalHttpSwitch

    @Test
    fun `borrar la sesion apaga el interruptor`() {
        val store = InMemorySessionStore(Session("http://192.168.1.50:9000", ApiToken("t-123")))
        val switch = Switch(true)
        SessionReset.clear(store, switch)
        assertFalse(switch.allowLocalHttp)
        assertEquals(null, store.load())
    }

    @Test
    fun `si el almacen falla el interruptor ya esta apagado y el error se propaga`() {
        val failing = object : SessionStore {
            override fun load(): Session? = null
            override fun save(session: Session) = Unit
            override fun clear() {
                throw IllegalStateException("fallo")
            }
        }
        val switch = Switch(true)
        try {
            SessionReset.clear(failing, switch)
            fail("Debía lanzar")
        } catch (expected: IllegalStateException) {
            // esperado
        }
        assertFalse(switch.allowLocalHttp)
    }
}
