package io.github.garfieldrockero.gastronomicon.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas de [ServerAddress.split], [SplitAddress] y [ServerOrigin]. */
class ServerAddressSplitTest {

    private fun split(url: String) = ServerAddress.split(url)!!

    @Test
    fun `https sin puerto da el 443 explicito`() {
        assertEquals(SplitAddress("https", "mealie.example.com", 443), split("https://mealie.example.com"))
    }

    @Test
    fun `https con puerto raro`() {
        assertEquals(SplitAddress("https", "mealie.example.com", 8443), split("https://mealie.example.com:8443"))
        assertEquals(1, split("https://a.example.com:1").port)
        assertEquals(65535, split("https://a.example.com:65535").port)
    }

    @Test
    fun `el 443 escrito se conserva como 443`() {
        assertEquals(443, split("https://mealie.example.com:443").port)
    }

    @Test
    fun `http local sin puerto da el 80 y con puerto el suyo`() {
        assertEquals(SplitAddress("http", "192.168.1.50", 80), split("http://192.168.1.50"))
        assertEquals(SplitAddress("http", "192.168.1.50", 9000), split("http://192.168.1.50:9000"))
        assertTrue(split("http://10.0.0.2:9000").isLocalHttp)
        assertFalse(split("https://x.example.com").isLocalHttp)
    }

    @Test
    fun `http con puerto 443 no se confunde con https`() {
        val parts = split("http://192.168.1.50:443")
        assertEquals(443, parts.port)
        assertEquals("http", parts.scheme)
    }

    @Test
    fun `ipv6 conserva los corchetes y el puerto`() {
        assertEquals(SplitAddress("http", "[fd00::1]", 9000), split("http://[fd00::1]:9000"))
        assertEquals(SplitAddress("http", "[fd00::1]", 80), split("http://[fd00::1]"))
        assertEquals(SplitAddress("https", "[2001:db8::1]", 8443), split("https://[2001:db8::1]:8443"))
    }

    @Test
    fun `una url con subruta no se puede partir`() {
        // Mealie no admite subpath: si hubiera una URL guardada así (de una
        // versión anterior), no se ofrece editarla, se vuelve a configurar.
        assertNull(ServerAddress.split("https://example.com:8443/mealie"))
    }

    @Test
    fun `se normaliza antes de partir`() {
        assertEquals(SplitAddress("https", "mealie.example.com", 443), split("HTTPS://Mealie.Example.com/"))
    }

    @Test
    fun `ida y vuelta con build`() {
        val urls = listOf(
            "https://mealie.example.com",
            "https://mealie.example.com:8443",
            "https://[2001:db8::1]:8443",
            "https://[2001:db8::1]",
            "http://192.168.1.50",
            "http://192.168.1.50:9000",
            "http://192.168.1.50:443",
            "http://10.0.0.2:8080",
            "http://[fd00::1]:9000",
            "http://[fd00::1]",
            "https://a.example.com:1",
            "https://a.example.com:65535",
        )
        for (url in urls) {
            assertEquals(url, split(url).toUrl())
        }
    }

    @Test
    fun `urls no validas dan null`() {
        val bad = listOf(
            "", "   ", "mealie.example.com", "ftp://x.example.com", "https://",
            "https://user:pass@x.example.com", "https://x.example.com?a=1", "https://x.example.com#f",
            "https://x.example.com:0", "https://x.example.com:65536", "https://x.example.com:abc",
            "https://x .example.com", "javascript:alert(1)",
            // Una ruta no es una URL de servidor válida (Mealie no admite subpath).
            "https://x.example.com/mealie", "https://x.example.com//",
            // http hacia fuera de la red local
            "http://example.com", "http://8.8.8.8", "http://nas.local",
        )
        for (url in bad) {
            assertNull("«$url»", ServerAddress.split(url))
        }
    }

    // ---------------------------------------------------------------- origen

    @Test
    fun `el origen usa puertos por defecto explicitos`() {
        assertEquals(ServerOrigin.of("https://a.example.com"), ServerOrigin.of("https://a.example.com:443"))
        assertEquals(ServerOrigin.of("http://10.0.0.2"), ServerOrigin.of("http://10.0.0.2:80"))
    }

    @Test
    fun `la url con ruta no tiene origen`() {
        // ServerUrl rechaza la ruta, así que no hay origen que comparar: falla
        // cerrado y SaveSessionPolicy pedirá el token o probar la conexión.
        assertNull(ServerOrigin.of("https://a.example.com/x"))
        assertEquals(ServerOrigin.of("https://a.example.com"), ServerOrigin.of("HTTPS://A.Example.com/"))
        assertEquals(
            ServerOrigin.of("https://a.example.com")!!.hashCode(),
            ServerOrigin.of("https://A.example.com")!!.hashCode(),
        )
    }

    @Test
    fun `esquema host o puerto distintos son otro origen`() {
        val base = ServerOrigin.of("https://a.example.com")
        assertNotEquals(base, ServerOrigin.of("https://b.example.com"))
        assertNotEquals(base, ServerOrigin.of("https://a.example.com:8443"))
        assertNotEquals(base, ServerOrigin.of("https://a.example.com.evil.com"))
        assertNotEquals(ServerOrigin.of("http://10.0.0.2:443"), ServerOrigin.of("https://10.0.0.2:443"))
        assertNotEquals(ServerOrigin.of("http://10.0.0.2"), ServerOrigin.of("http://10.0.0.3"))
    }

    @Test
    fun `origen ipv6 y url invalida`() {
        assertNotNull(ServerOrigin.of("http://[fd00::1]:9000"))
        assertEquals(ServerOrigin.of("http://[fd00::1]:9000"), ServerOrigin.of("http://[FD00::1]:9000"))
        assertNull(ServerOrigin.of("http://[fd00::1]:9000/x"))
        assertNull(ServerOrigin.of("nada"))
        assertNull(ServerOrigin.of("https://u:p@a.example.com"))
    }

    // Las formas equivalentes de una misma IP NO se unifican a propósito. ServerOrigin
    // compara el host como texto canónico (minúsculas, sin punto final) y no interpreta
    // direcciones. Fallar así es seguro: dos escrituras de la misma IPv6 cuentan como
    // servidores distintos y «Guardar» con token vacío obliga a probar la conexión antes
    // de reutilizar el token guardado. Lo inseguro sería lo contrario (unir orígenes
    // que no son el mismo), y eso es lo que estas pruebas vigilan.
    @Test
    fun `dos formas de la misma ipv6 son orígenes distintos y falla cerrado`() {
        val corta = ServerOrigin.of("http://[fd00::1]")
        val conCero = ServerOrigin.of("http://[fd00:0::1]")
        val completa = ServerOrigin.of("http://[fd00:0:0:0:0:0:0:1]")
        assertNotNull(corta)
        assertNotNull(conCero)
        assertNotNull(completa)
        assertNotEquals(corta, conCero)
        assertNotEquals(corta, completa)
        assertNotEquals(conCero, completa)
        // Y la política lo traduce en probar antes de guardar, no en conservar el token.
        assertEquals(
            io.github.garfieldrockero.gastronomicon.core.session.SaveDecision.TestThenSaveWithSavedToken,
            io.github.garfieldrockero.gastronomicon.core.session.SaveSessionPolicy.decide(
                "http://[fd00::1]",
                "http://[fd00:0::1]",
                typedTokenIsEmpty = true,
            ),
        )
    }

    @Test
    fun `la misma ipv6 solo se unifica cuando el texto es igual salvo mayusculas`() {
        assertEquals(ServerOrigin.of("http://[fd00::1]"), ServerOrigin.of("http://[FD00::1]"))
        assertEquals(ServerOrigin.of("http://[fd00::1]:80"), ServerOrigin.of("http://[fd00::1]"))
    }

    // 010.0.0.1 no es otro origen: ni siquiera es un origen. ServerUrl rechaza los ceros a la
    // izquierda en IPv4 porque algunas pilas (inet_aton) leen 010 como octal, es decir 8.0.0.1,
    // y la dirección que se ve no sería la que se contacta. Al ser null, SaveSessionPolicy lo
    // trata como URL no válida (nueva) o como «sin sesión» (guardada): nunca como igual a 10.0.0.1.
    @Test
    fun `ipv4 con ceros a la izquierda no tiene origen y no iguala al canonico`() {
        assertNotNull(ServerOrigin.of("http://10.0.0.1"))
        assertNull(ServerOrigin.of("http://010.0.0.1"))
        assertNull(ServerOrigin.of("http://10.0.0.01"))
        assertNull(ServerOrigin.of("http://010.000.000.001"))
        assertNull(ServerOrigin.of("https://010.0.0.1"))
        assertNotEquals(ServerOrigin.of("http://10.0.0.1"), ServerOrigin.of("http://010.0.0.1"))
    }

    @Test
    fun `una sesion guardada con ceros a la izquierda no permite conservar el token`() {
        assertEquals(
            io.github.garfieldrockero.gastronomicon.core.session.SaveDecision.TokenRequired,
            io.github.garfieldrockero.gastronomicon.core.session.SaveSessionPolicy.decide(
                "http://010.0.0.1",
                "http://10.0.0.1",
                typedTokenIsEmpty = true,
            ),
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            io.github.garfieldrockero.gastronomicon.core.session.SaveSessionPolicy.decide(
                "http://10.0.0.1",
                "http://010.0.0.1",
                typedTokenIsEmpty = true,
            )
        }
    }

    @Test
    fun `un cero a la izquierda en el puerto si se normaliza al mismo origen`() {
        // El puerto es un número, no una dirección: 0080 es 80 sin ambigüedad.
        assertEquals(ServerOrigin.of("http://10.0.0.1:0080"), ServerOrigin.of("http://10.0.0.1"))
    }
}
