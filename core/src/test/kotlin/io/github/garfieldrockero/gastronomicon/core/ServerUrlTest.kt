package io.github.garfieldrockero.gastronomicon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la validación de la URL del servidor Mealie.
 *
 * Solo se comprueba sintaxis: la normalización no hace red ni resuelve DNS,
 * así que todo se puede probar aquí sin servidor.
 */
class ServerUrlTest {

    // ---------------------------------------------------------------- acepta

    @Test
    fun `la URL simple se devuelve sin cambios`() {
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("https://mealie.example.com"),
        )
    }

    @Test
    fun `la barra final de la raíz se quita`() {
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("https://mealie.example.com/"),
        )
    }

    @Test
    fun `la subruta se conserva y queda sin barra final`() {
        assertEquals(
            "https://servidor.example.com/mealie",
            ServerUrl.normalize("https://servidor.example.com/mealie/"),
        )
        // Sin barra final de origen: no se toca nada.
        assertEquals(
            "https://servidor.example.com/mealie",
            ServerUrl.normalize("https://servidor.example.com/mealie"),
        )
        assertEquals(
            "https://servidor.example.com/cocina/postres",
            ServerUrl.normalize("https://servidor.example.com/cocina/postres/"),
        )
    }

    @Test
    fun `las barras finales sobrantes se quitan todas`() {
        assertEquals(
            "https://servidor.example.com/mealie",
            ServerUrl.normalize("https://servidor.example.com/mealie//"),
        )
    }

    @Test
    fun `el esquema en mayúsculas se acepta y se normaliza a minúsculas`() {
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("HTTPS://mealie.example.com/"),
        )
    }

    @Test
    fun `los espacios de los extremos se quitan`() {
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("   https://mealie.example.com   "),
        )
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("\t https://mealie.example.com \n"),
        )
    }

    @Test
    fun `el puerto explícito se conserva salvo el 443`() {
        assertEquals(
            "https://mealie.example.com:8443",
            ServerUrl.normalize("https://mealie.example.com:8443"),
        )
        assertEquals(
            "https://mealie.example.com:8443",
            ServerUrl.normalize("https://mealie.example.com:8443/"),
        )
        // 443 es el puerto por defecto de HTTPS: se omite en la forma canónica.
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("https://mealie.example.com:443"),
        )
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("https://mealie.example.com:0443/"),
        )
    }

    @Test
    fun `esquema en mayúscula, puerto y subruta juntos`() {
        assertEquals(
            "https://servidor.example.com:8443/mealie",
            ServerUrl.normalize("HTTPS://servidor.example.com:8443/mealie/"),
        )
    }

    @Test
    fun `un host IPv6 entre corchetes se conserva con su puerto`() {
        assertEquals(
            "https://[2001:db8::1]:8443",
            ServerUrl.normalize("https://[2001:db8::1]:8443/"),
        )
        // Sin puerto también hay que devolver los corchetes.
        assertEquals(
            "https://[2001:db8::1]",
            ServerUrl.normalize("https://[2001:db8::1]/"),
        )
    }

    @Test
    fun `se rechaza un puerto fuera de rango`() {
        assertRejected("https://mealie.example.com:99999/")
        assertRejected("https://mealie.example.com:65536/")
        assertRejected("https://mealie.example.com:0/")
        assertRejected("https://mealie.example.com:-1/")
    }

    @Test
    fun `el puerto máximo se acepta y los ceros a la izquierda se normalizan`() {
        assertEquals(
            "https://mealie.example.com:65535",
            ServerUrl.normalize("https://mealie.example.com:65535"),
        )
        assertEquals(
            "https://mealie.example.com:80",
            ServerUrl.normalize("https://mealie.example.com:00080"),
        )
    }

    @Test
    fun `un puerto vacío se descarta`() {
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("https://mealie.example.com:/"),
        )
    }

    @Test
    fun `se rechaza un identificador de zona IPv6`() {
        assertRejected("https://[fe80::1%25eth0]/")
        assertRejected("https://[fe80::1%eth0]/")
    }

    @Test
    fun `se rechaza una IPv6 sin corchetes`() {
        assertRejected("https://2001:db8::1/")
    }

    @Test
    fun `la ruta escapada se conserva tal cual, sin descodificarla`() {
        // rawPath y no path: si se descodificara, «caf%C3%A9» volvería a tener
        // un carácter no ASCII y la URL dejaría de ser válida.
        assertEquals(
            "https://servidor.example.com/caf%C3%A9",
            ServerUrl.normalize("https://servidor.example.com/caf%C3%A9/"),
        )
    }

    // ---------------------------------------------------------------- rechaza

    @Test
    fun `se rechaza http sin la opción de red local`() {
        assertRejected("http://mealie.example.com")
        // Ni siquiera hacia la red local: sin el interruptor no hay HTTP.
        assertRejected("http://192.168.1.50:9000")
        assertRejected("http://[fd00::5]:9000")
        assertRejected("http://mealie.local")
        assertRejected("HTTP://192.168.1.50")
    }

    @Test
    fun `el rechazo de http sin la opción explica cómo activarla`() {
        val message = rejectionMessage("http://192.168.1.50:9000")
        assertTrue(message.contains("red local"))
        assertFalse(message.contains("192.168.1.50"))
        assertFalse(message.contains("9000"))
    }

    @Test
    fun `se rechaza cualquier otro esquema`() {
        assertRejected("ftp://mealie.example.com")
        assertRejected("javascript://mealie.example.com")
    }

    @Test
    fun `se rechaza la cadena vacía o solo espacios`() {
        assertRejected("")
        assertRejected("   ")
        assertRejected("\t \n")
    }

    @Test
    fun `se rechazan los espacios internos`() {
        assertRejected("https://mealie .example.com")
        assertRejected("https://mealie.example.com/pa th")
        assertRejected("https://mealie.example.com\tpath")
        assertRejected("https://mealie.example.com\n/algo")
    }

    @Test
    fun `se rechaza usuario o contraseña embebidos`() {
        assertRejected("https://usuario:secreto@mealie.example.com")
        assertRejected("https://usuario@mealie.example.com")
    }

    @Test
    fun `se rechaza la consulta`() {
        assertRejected("https://mealie.example.com/?receta=1")
        assertRejected("https://mealie.example.com/recetas?buscar=tortilla")
        // Una interrogación suelta también es una consulta (vacía).
        assertRejected("https://mealie.example.com/?")
    }

    @Test
    fun `se rechaza el fragmento`() {
        assertRejected("https://mealie.example.com/#seccion")
        assertRejected("https://mealie.example.com/recetas#paso-2")
        // Una almohadilla suelta también es un fragmento (vacío).
        assertRejected("https://mealie.example.com/#")
    }

    @Test
    fun `se rechaza una URL sin host`() {
        assertRejected("https:///recetas")
        assertRejected("https://")
    }

    @Test
    fun `se rechaza una URL sin esquema`() {
        assertRejected("mealie.example.com")
        assertRejected("www.mealie.example.com/recetas")
    }

    @Test
    fun `se rechaza una URL malformada`() {
        // Un puerto con letras deja el authority sin partir en host:puerto.
        assertRejected("https://mealie.example.com:puerto/")
        // Caracteres que no son legales en una ruta.
        assertRejected("https://mealie.example.com/<script>")
    }

    @Test
    fun `el mensaje de error no repite credenciales ni la entrada`() {
        // Si alguien pega una contraseña o un token en el campo de la URL, el mensaje
        // que se muestra en Ajustes no puede repetirlo.
        val conCredenciales = rejectionMessage("https://usuario:secreto@mealie.example.com")
        assertFalse(conCredenciales.contains("secreto"))

        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtZWFsaWUifQ.firma"
        val sinEsquema = rejectionMessage(token)
        assertFalse(sinEsquema.contains(token))

        val malformada = rejectionMessage("https://usuario:secreto^x@mealie.example.com")
        assertFalse(malformada.contains("secreto"))
    }

    // ---------------------------------------------------------------- HTTP en la red local

    @Test
    fun `con la opción se acepta http hacia una IPv4 privada`() {
        assertEquals("http://192.168.1.50:9000", ServerUrl.normalize("http://192.168.1.50:9000", allowLocalHttp = true))
        assertEquals("http://10.0.0.2:9000", ServerUrl.normalize("http://10.0.0.2:9000/", allowLocalHttp = true))
        assertEquals("http://172.16.0.1", ServerUrl.normalize("http://172.16.0.1", allowLocalHttp = true))
        assertEquals("http://172.31.255.254", ServerUrl.normalize("http://172.31.255.254", allowLocalHttp = true))
    }

    @Test
    fun `con la opción se acepta http hacia una IPv6 ULA`() {
        assertEquals("http://[fd00::5]:9000", ServerUrl.normalize("http://[fd00::5]:9000", allowLocalHttp = true))
        assertEquals("http://[fd00::5]:9000", ServerUrl.normalize("http://[FD00::5]:9000/", allowLocalHttp = true))
        assertEquals("http://[fc00::1]", ServerUrl.normalize("http://[fc00::1]", allowLocalHttp = true))
    }

    @Test
    fun `con la opción se rechaza http hacia un nombre local`() {
        // Decidido el 7-11-2026: solo IP literales. Un nombre lo resuelve la red
        // en la que esté el móvil, y fuera de casa puede llevar a cualquiera.
        assertNotLocalRejection("http://mealie.local")
        assertNotLocalRejection("http://mealie.local:9000/")
        assertNotLocalRejection("HTTP://MEALIE.LOCAL./")
        assertNotLocalRejection("http://cocina.casa.local")
    }

    @Test
    fun `el rechazo por host no local es NotLocalAddressException`() {
        assertNotLocalRejection("http://mealie.example.com")
        assertNotLocalRejection("http://8.8.8.8:9000")
        assertNotLocalRejection("http://127.0.0.1")
        assertNotLocalRejection("http://localhost")
        assertNotLocalRejection("http://[::ffff:192.168.1.1]")
        assertNotLocalRejection("http://[2001:db8::1]")
    }

    @Test
    fun `los demás rechazos no son NotLocalAddressException`() {
        // Sin la opción, http es «activa la opción», no «no es local».
        assertOtherRejection("http://mealie.example.com", allowLocalHttp = false)
        assertOtherRejection("http://192.168.1.50", allowLocalHttp = false)
        // Una forma mal escrita recibe su mensaje concreto, no el de «no es local».
        assertOtherRejection("http://010.0.0.1", allowLocalHttp = true)
        assertOtherRejection("http://192.168.1", allowLocalHttp = true)
        assertOtherRejection("http://mi_host", allowLocalHttp = true)
        assertOtherRejection("http://usuario:secreto@192.168.1.50", allowLocalHttp = true)
        assertOtherRejection("ftp://192.168.1.50", allowLocalHttp = true)
        assertOtherRejection("", allowLocalHttp = true)
    }

    @Test
    fun `el mensaje de no local no menciona nombres local`() {
        val message = rejectionMessage("http://mealie.local", allowLocalHttp = true)
        assertTrue(message.contains("red local"))
        assertFalse(message.contains(".local"))
        assertFalse(message.contains("mealie"))
    }

    @Test
    fun `con la opción se conserva la subruta en http`() {
        assertEquals(
            "http://192.168.1.50:9000/mealie",
            ServerUrl.normalize("http://192.168.1.50:9000/mealie/", allowLocalHttp = true),
        )
    }

    @Test
    fun `con la opción se rechaza http hacia dominios públicos`() {
        assertRejectedLocal("http://mealie.example.com")
        assertRejectedLocal("http://mealie.example.com:9000")
        assertRejectedLocal("http://mealie.local.evil.com")
        assertRejectedLocal("http://mealielocal")
        assertRejectedLocal("http://192.168.1.50.nip.io")
        assertRejectedLocal("http://mealie.lan")
        assertRejectedLocal("http://mealie.home.arpa")
    }

    @Test
    fun `con la opción se rechaza http hacia IP públicas`() {
        assertRejectedLocal("http://8.8.8.8")
        assertRejectedLocal("http://172.32.0.1:9000")
        assertRejectedLocal("http://192.169.0.1")
        assertRejectedLocal("http://11.0.0.1")
        assertRejectedLocal("http://[2001:db8::1]:9000")
        assertRejectedLocal("http://[fe80::1]")
    }

    @Test
    fun `con la opción se rechaza http hacia el propio móvil`() {
        assertRejectedLocal("http://127.0.0.1")
        assertRejectedLocal("http://127.0.0.1:9000")
        assertRejectedLocal("http://localhost")
        assertRejectedLocal("http://localhost:9000")
        assertRejectedLocal("http://mealie.localhost")
        assertRejectedLocal("http://[::1]:9000")
        assertRejectedLocal("http://0.0.0.0")
        assertRejectedLocal("http://169.254.1.1")
    }

    @Test
    fun `con la opción se rechaza la IPv4 mapeada en IPv6`() {
        assertRejectedLocal("http://[::ffff:192.168.1.1]")
        assertRejectedLocal("http://[::ffff:c0a8:101]:9000")
    }

    @Test
    fun `con la opción se rechazan las formas raras de IPv4 privada`() {
        // Sin la forma decimal estricta, «012.0.0.1» o «3232235826» serían otras
        // direcciones disfrazadas de privadas.
        assertRejectedLocal("http://010.0.0.1")
        assertRejectedLocal("http://192.168.01.50")
        assertRejectedLocal("http://3232235826")
        assertRejectedLocal("http://192.168.1")
    }

    @Test
    fun `con la opción http sigue rechazando credenciales, consulta y fragmento`() {
        assertRejectedLocal("http://usuario:secreto@192.168.1.50")
        assertRejectedLocal("http://192.168.1.50/?a=1")
        assertRejectedLocal("http://192.168.1.50/#a")
        assertRejectedLocal("http://192.168.1.50/a/../b")
        assertRejectedLocal("http://192.168.1.50:99999")
        assertRejectedLocal("http://[fd00::1%25eth0]")
        assertRejectedLocal("http://mealie\u200B.local")
        assertRejectedLocal("http://192.168.1.50/café")
    }

    @Test
    fun `con la opción el 80 se omite en http y el 443 se conserva`() {
        assertEquals("http://192.168.1.50", ServerUrl.normalize("http://192.168.1.50:80", allowLocalHttp = true))
        assertEquals("http://192.168.1.50", ServerUrl.normalize("http://192.168.1.50:80/", allowLocalHttp = true))
        // 443 no es el por defecto de http: se conserva.
        assertEquals("http://192.168.1.50:443", ServerUrl.normalize("http://192.168.1.50:443", allowLocalHttp = true))
        // Y 80 no es el por defecto de https: se conserva.
        assertEquals("https://mealie.example.com:80", ServerUrl.normalize("https://mealie.example.com:80"))
    }

    @Test
    fun `https no cambia con la opción`() {
        val cases = listOf(
            "https://mealie.example.com/",
            "HTTPS://MEALIE.Example.COM:8443/mealie/",
            "https://mealie.example.com:443",
            "https://[2001:db8::1]/",
            "https://8.8.8.8/",
            "https://192.168.1.50:9000",
            "https://localhost",
        )
        for (input in cases) {
            assertEquals(
                "https cambia con la opción: $input",
                ServerUrl.normalize(input),
                ServerUrl.normalize(input, allowLocalHttp = true),
            )
        }
    }

    @Test
    fun `con la opción se siguen rechazando los otros esquemas`() {
        assertRejectedLocal("ftp://192.168.1.50")
        assertRejectedLocal("ws://192.168.1.50")
        assertRejectedLocal("192.168.1.50:9000")
    }

    @Test
    fun `el rechazo por host no local no repite la entrada`() {
        val message = rejectionMessage("http://mealie.example.com:9000/secreto", allowLocalHttp = true)
        assertTrue(message.contains("red local"))
        assertFalse(message.contains("mealie.example.com"))
        assertFalse(message.contains("9000"))
        assertFalse(message.contains("secreto"))

        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtZWFsaWUifQ.firma"
        val withToken = rejectionMessage("http://8.8.8.8/$token", allowLocalHttp = true)
        assertFalse(withToken.contains(token))
        assertFalse(withToken.contains("8.8.8.8"))
    }

    @Test
    fun `http local es idempotente`() {
        val cases = listOf(
            "http://192.168.1.50:9000/",
            "HTTP://192.168.1.50:80/mealie/",
            "http://[FD00::5]:9000",
        )
        for (input in cases) {
            val once = ServerUrl.normalize(input, allowLocalHttp = true)
            assertEquals("No es idempotente: $input", once, ServerUrl.normalize(once, allowLocalHttp = true))
        }
    }

    // ---------------------------------------------------------------- forma canónica

    @Test
    fun `el host se normaliza a minúsculas`() {
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("HTTPS://MEALIE.Example.COM/"),
        )
    }

    @Test
    fun `el punto final del host se quita`() {
        assertEquals(
            "https://mealie.example.com",
            ServerUrl.normalize("https://mealie.example.com./"),
        )
    }

    @Test
    fun `se rechazan los puntos sobrantes en el host`() {
        assertRejected("https://mealie.example.com../")
        assertRejected("https://.mealie.example.com/")
        assertRejected("https://mealie..example.com/")
    }

    @Test
    fun `se rechazan hosts con etiquetas inválidas`() {
        assertRejected("https://-mealie.example.com/")
        assertRejected("https://mealie-.example.com/")
        assertRejected("https://mi_host.example.com/")
        assertRejected("https://" + "a".repeat(64) + ".example.com/")
    }

    @Test
    fun `se rechazan las IPv4 con formas raras`() {
        assertRejected("https://2130706433/")
        assertRejected("https://010.0.0.1/")
        assertRejected("https://1.2.3/")
        assertRejected("https://1.2.3.256/")
        assertEquals("https://1.2.3.4", ServerUrl.normalize("https://1.2.3.4/"))
    }

    // ---------------------------------------------------------------- caracteres invisibles

    @Test
    fun `se rechazan los caracteres invisibles y el BOM`() {
        assertRejected("https://mealie.example.com/\u200B")
        assertRejected("https://mealie.example.com/\u202Eabc")
        assertRejected("https://mealie\u200B.example.com/")
        assertRejected("\uFEFFhttps://mealie.example.com/")
    }

    @Test
    fun `se rechaza una ruta no ASCII sin escapar`() {
        assertRejected("https://mealie.example.com/café")
    }

    // ---------------------------------------------------------------- rutas

    @Test
    fun `se rechazan los segmentos vacios y los puntos`() {
        assertRejected("https://mealie.example.com/a//b")
        assertRejected("https://mealie.example.com/a/./b")
        assertRejected("https://mealie.example.com/a/../b")
        assertRejected("https://mealie.example.com/a/%2e/b")
        assertRejected("https://mealie.example.com/a/%2E%2E/b")
    }

    @Test
    fun `se rechazan los escapes de control y de barra`() {
        assertRejected("https://mealie.example.com/%00")
        assertRejected("https://mealie.example.com/%1f")
        assertRejected("https://mealie.example.com/%7f")
        assertRejected("https://mealie.example.com/a%2fb")
    }

    // ---------------------------------------------------------------- formas malformadas

    @Test
    fun `se rechazan las formas malformadas de authority`() {
        assertRejected("https:mealie.example.com")
        assertRejected("https:/mealie.example.com")
        assertRejected("https://@mealie.example.com")
        assertRejected("https://mealie.example.com@")
        assertRejected("https://mealie.example.com\\@evil.com")
    }

    @Test
    fun `normalize es idempotente en las entradas válidas`() {
        val validas = listOf(
            "https://mealie.example.com",
            "https://mealie.example.com/",
            "https://MEALIE.Example.COM/mealie/",
            "https://mealie.example.com:8443/",
            "https://mealie.example.com./",
            "https://[2001:db8::1]/",
        )
        for (entrada in validas) {
            val unaVez = ServerUrl.normalize(entrada)
            assertEquals("No es idempotente: $entrada", unaVez, ServerUrl.normalize(unaVez))
        }
    }

    // ---------------------------------------------------------------- ayuda

    /**
     * Comprueba que la entrada se rechaza y que el mensaje explica el motivo:
     * en Ajustes, un error sin texto no ayuda a corregir la URL.
     */
    private fun assertRejected(entrada: String, allowLocalHttp: Boolean = false) {
        var mensaje: String? = null
        try {
            ServerUrl.normalize(entrada, allowLocalHttp)
        } catch (esperada: IllegalArgumentException) {
            mensaje = esperada.message
        }
        assertTrue("Se esperaba que «$entrada» se rechazara", mensaje != null)
        assertTrue(
            "El rechazo de «$entrada» no trae un mensaje claro",
            !mensaje.isNullOrBlank(),
        )
    }

    /** Rechazo con la opción activada y por el motivo concreto de «no es local». */
    private fun assertNotLocalRejection(entrada: String) {
        try {
            ServerUrl.normalize(entrada, allowLocalHttp = true)
        } catch (esperada: NotLocalAddressException) {
            assertFalse(esperada.message.isNullOrBlank())
            return
        } catch (otra: IllegalArgumentException) {
            throw AssertionError("«$entrada» se ha rechazado por otro motivo: ${otra.message}")
        }
        throw AssertionError("Se esperaba que «$entrada» se rechazara")
    }

    /** Rechazo por un motivo que no es «no es local». */
    private fun assertOtherRejection(entrada: String, allowLocalHttp: Boolean) {
        try {
            ServerUrl.normalize(entrada, allowLocalHttp)
        } catch (esperada: IllegalArgumentException) {
            assertFalse(
                "«$entrada» no debería ser NotLocalAddressException",
                esperada is NotLocalAddressException,
            )
            return
        }
        throw AssertionError("Se esperaba que «$entrada» se rechazara")
    }

    /** Igual que [assertRejected], pero con la opción de red local activada. */
    private fun assertRejectedLocal(entrada: String) = assertRejected(entrada, allowLocalHttp = true)

    private fun rejectionMessage(entrada: String, allowLocalHttp: Boolean = false): String {
        try {
            ServerUrl.normalize(entrada, allowLocalHttp)
        } catch (esperada: IllegalArgumentException) {
            return esperada.message.orEmpty()
        }
        throw AssertionError("Se esperaba que «$entrada» se rechazara")
    }
}
