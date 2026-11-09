package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerScopeTest {

    private val hex64 = Regex("[0-9a-f]{64}")

    @Test
    fun `el identificador es un sha256 hexadecimal de 64 caracteres`() {
        val scope = ServerScope.of(OfflineFixtures.SERVER_A, OfflineFixtures.USER_A)
        assertNotNull(scope)
        assertTrue(hex64.matches(scope!!.id))
    }

    @Test
    fun `la misma url con otra forma da el mismo ambito`() {
        val canonica = ServerScope.of("https://mealie.example.org", OfflineFixtures.USER_A)
        val conBarra = ServerScope.of("https://mealie.example.org/", OfflineFixtures.USER_A)
        val conMayusculas = ServerScope.of("https://Mealie.Example.ORG", OfflineFixtures.USER_A)
        assertEquals(canonica, conBarra)
        assertEquals(canonica, conMayusculas)
    }

    @Test
    fun `servidores distintos dan ambitos distintos`() {
        assertNotEquals(ServerScope.of(OfflineFixtures.SERVER_A, OfflineFixtures.USER_A), OfflineFixtures.scopeB())
    }

    @Test
    fun `el puerto y el esquema forman parte del ambito`() {
        val base = ServerScope.of("https://mealie.example.org", OfflineFixtures.USER_A)
        val puerto = ServerScope.of("https://mealie.example.org:8443", OfflineFixtures.USER_A)
        assertNotEquals(base, puerto)
    }

    @Test
    fun `dos cuentas del mismo servidor no comparten ambito`() {
        val una = ServerScope.of(OfflineFixtures.SERVER_A, OfflineFixtures.USER_A)
        val otra = ServerScope.of(OfflineFixtures.SERVER_A, OfflineFixtures.USER_B)

        assertNotNull(una)
        assertNotNull(otra)
        assertNotEquals(una, otra)
        assertTrue(hex64.matches(una!!.id))
        assertTrue(hex64.matches(otra!!.id))
    }

    @Test
    fun `el id de usuario se compara tal cual`() {
        val minusculas = ServerScope.of(OfflineFixtures.SERVER_A, "abcdefab-cdef-abcd-efab-cdefabcdefab")
        val mayusculas = ServerScope.of(OfflineFixtures.SERVER_A, "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB")

        assertNotNull(minusculas)
        assertNotNull(mayusculas)
        // No se normaliza aquí: el id ya lo entrega saneado `currentUser()`.
        assertNotEquals(minusculas, mayusculas)
    }

    @Test
    fun `una url invalida no da ambito`() {
        assertNull(ServerScope.of("", OfflineFixtures.USER_A))
        assertNull(ServerScope.of("mealie.example.org", OfflineFixtures.USER_A))
        assertNull(ServerScope.of("ftp://mealie.example.org", OfflineFixtures.USER_A))
        assertNull(ServerScope.of("https://mealie.example.org?x=1", OfflineFixtures.USER_A))
    }

    @Test
    fun `un id de usuario invalido no da ambito`() {
        assertNull(ServerScope.of(OfflineFixtures.SERVER_A, ""))
        assertNull(ServerScope.of(OfflineFixtures.SERVER_A, "con espacio"))
        assertNull(ServerScope.of(OfflineFixtures.SERVER_A, "con/barra"))
        assertNull(ServerScope.of(OfflineFixtures.SERVER_A, "con\u0000nulo"))
        assertNull(ServerScope.of(OfflineFixtures.SERVER_A, "x".repeat(129)))
    }

    @Test
    fun `el http local admitido da ambito`() {
        val scope = ServerScope.of("http://192.168.1.10:8080", OfflineFixtures.USER_A)
        assertNotNull(scope)
        assertTrue(hex64.matches(scope!!.id))
    }

    @Test
    fun `la sesion da el mismo ambito que su url y su usuario`() {
        val session = Session("https://mealie.example.org", ApiToken("token-de-prueba"))
        assertEquals(
            ServerScope.of(session.serverUrl, OfflineFixtures.USER_A),
            ServerScope.of(session, OfflineFixtures.USER_A),
        )
    }

    @Test
    fun `toString no enseña la url ni el hash`() {
        val scope = OfflineFixtures.scopeA()
        val text = scope.toString()
        assertFalse(text.contains("mealie"))
        assertFalse(text.contains("http"))
        assertFalse(text.contains(scope.id))
        assertFalse(text.contains(OfflineFixtures.USER_A))
    }
}
