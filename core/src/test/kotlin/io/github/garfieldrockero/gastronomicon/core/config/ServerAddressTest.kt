package io.github.garfieldrockero.gastronomicon.core.config

import io.github.garfieldrockero.gastronomicon.core.NotLocalAddressException
import io.github.garfieldrockero.gastronomicon.core.ServerUrl
import io.github.garfieldrockero.gastronomicon.core.UnsupportedPathException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de cómo se combinan los dos campos de la primera pantalla
 * («servidor o IP» y «puerto») para construir la URL base.
 *
 * La política de la URL en sí (HTTPS, HTTP local, sin credenciales, forma canónica) ya está
 * probada en ServerUrlTest y aquí no se repite: se comprueba la combinación de
 * campos y que los errores de forma siguen rebotando en ServerUrl con mensajes
 * decentes.
 */
class ServerAddressTest {

    // ---------------------------------------------------------------- acepta

    @Test
    fun `sin esquema ni puerto se usa https y el 443 por defecto`() {
        assertEquals("https://mealie.example.com", ServerAddress.build("mealie.example.com", null))
    }

    @Test
    fun `el campo de puerto se añade al servidor sin esquema`() {
        assertEquals("https://mealie.example.com:8443", ServerAddress.build("mealie.example.com", 8443))
    }

    @Test
    fun `el 443 del campo de puerto se omite en la forma canónica`() {
        assertEquals("https://mealie.example.com", ServerAddress.build("mealie.example.com", 443))
    }

    @Test
    fun `con esquema y barra final se normaliza igual`() {
        assertEquals("https://mealie.example.com", ServerAddress.build("https://mealie.example.com/", null))
    }

    @Test
    fun `el esquema en mayúsculas se acepta`() {
        assertEquals(
            "https://mealie.example.com",
            ServerAddress.build("HTTPS://MEALIE.Example.com/", null),
        )
    }

    @Test
    fun `los espacios de los extremos se quitan`() {
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("   mealie.example.com   ", 8443),
        )
    }

    @Test
    fun `el puerto propio del servidor se conserva sin campo de puerto`() {
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("https://mealie.example.com:8443/", null),
        )
        // También sin esquema: «host:puerto» en el campo servidor es un
        // servidor con puerto, no un esquema raro.
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("mealie.example.com:8443", null),
        )
    }

    @Test
    fun `el mismo puerto en los dos campos se acepta`() {
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("https://mealie.example.com:8443", 8443),
        )
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("mealie.example.com:8443", 8443),
        )
    }

    @Test
    fun `el 443 propio del servidor coincide con el 443 del campo`() {
        assertEquals("https://mealie.example.com", ServerAddress.build("mealie.example.com:443", 443))
    }

    @Test
    fun `el 443 del campo de puerto cede ante el puerto propio del servidor`() {
        // La pantalla rellena el campo de puerto con 443: quien escribe
        // «host:8443» en el servidor sin tocar el otro campo quiere el 8443,
        // no un error de conflicto.
        assertEquals("https://mealie.example.com:8443", ServerAddress.build("mealie.example.com:8443", 443))
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("https://mealie.example.com:8443/", ServerAddress.DEFAULT_HTTPS_PORT),
        )
        assertEquals("https://[2001:db8::1]:8443", ServerAddress.build("[2001:db8::1]:8443", 443))
    }

    @Test
    fun `el puerto vacío del servidor se sustituye por el del campo`() {
        // «host:» con el puerto en el otro campo: el campo servidor gana el
        // nombre y el campo puerto el número.
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("mealie.example.com:", 8443),
        )
    }

    @Test
    fun `una IPv4 admite el campo de puerto`() {
        assertEquals("https://1.2.3.4", ServerAddress.build("1.2.3.4", null))
        assertEquals("https://1.2.3.4:8443", ServerAddress.build("1.2.3.4", 8443))
    }

    @Test
    fun `una IPv6 entre corchetes admite el campo de puerto`() {
        assertEquals("https://[2001:db8::1]:8443", ServerAddress.build("[2001:db8::1]", 8443))
        assertEquals(
            "https://[2001:db8::1]:9000",
            ServerAddress.build("https://[2001:db8::1]:9000", 9000),
        )
    }

    @Test
    fun `los extremos del rango de puerto se aceptan`() {
        assertEquals("https://mealie.example.com:1", ServerAddress.build("mealie.example.com", 1))
        assertEquals("https://mealie.example.com:65535", ServerAddress.build("mealie.example.com", 65535))
    }

    // ---------------------------------------------------------------- rechaza

    @Test
    fun `el puerto fuera de rango se rechaza`() {
        assertRejected("mealie.example.com", 0)
        assertRejected("mealie.example.com", 65536)
        assertRejected("mealie.example.com", -1)
    }

    @Test
    fun `una subruta en el campo servidor se rechaza con su motivo`() {
        // Decidido el 10-11-2026: Mealie no admite subpath, así que una
        // subcarpeta tras el dominio no lleva a su API. El motivo lo pone
        // ServerUrl y aquí se comprueba que llega tal cual, sin convertirse en
        // «no es local» ni en un error de puerto.
        assertUnsupportedPath("servidor.example.com/mealie", 8443)
        assertUnsupportedPath("https://servidor.example.com/mealie/", 8443)
        assertUnsupportedPath("servidor.example.com/mealie/", null)
        assertUnsupportedPath("servidor.example.com:8443/mealie", 443)
    }

    @Test
    fun `dos puertos distintos en los dos campos se rechazan`() {
        assertRejected("https://mealie.example.com:8443", 9000)
        assertRejected("mealie.example.com:8443", 9000)
        assertRejected("[2001:db8::1]:8443", 9000)
    }

    @Test
    fun `el servidor vacío se rechaza`() {
        assertRejected("", null)
        assertRejected("   ", null)
    }

    @Test
    fun `http se sigue rechazando sin la opción de red local`() {
        assertRejected("http://mealie.example.com", null)
        assertRejected("http://192.168.1.50", 9000)
        assertRejected("http://mealie.local", null)
    }

    // ---------------------------------------------------------------- HTTP en la red local

    @Test
    fun `sin esquema y con la opción se usa http`() {
        assertEquals("http://192.168.1.50:9000", ServerAddress.build("192.168.1.50", 9000, allowLocalHttp = true))
        assertEquals("http://[fd00::5]:9000", ServerAddress.build("[fd00::5]", 9000, allowLocalHttp = true))
    }

    @Test
    fun `sin esquema ni puerto y con la opción se usa el 80`() {
        assertEquals("http://192.168.1.50", ServerAddress.build("192.168.1.50", null, allowLocalHttp = true))
        assertEquals("http://192.168.1.50", ServerAddress.build("192.168.1.50", 80, allowLocalHttp = true))
    }

    @Test
    fun `con https explícito y la opción se respeta https`() {
        assertEquals(
            "https://mealie.example.com",
            ServerAddress.build("https://mealie.example.com", null, allowLocalHttp = true),
        )
        // El 9000 que sugiere la pantalla es de http: con https explícito se
        // trata como «no tocado» y manda el 443.
        assertEquals(
            "https://192.168.1.50",
            ServerAddress.build("https://192.168.1.50", 9000, allowLocalHttp = true),
        )
        // El 443 sigue cediendo en https aunque la opción esté activada.
        assertEquals(
            "https://mealie.example.com:8443",
            ServerAddress.build("https://mealie.example.com:8443", 443, allowLocalHttp = true),
        )
    }

    @Test
    fun `con https explícito y la opción el 9000 escrito en el servidor se respeta`() {
        // Quien de verdad tiene https en el 9000 lo dice en el propio servidor.
        assertEquals(
            "https://192.168.1.50:9000",
            ServerAddress.build("https://192.168.1.50:9000", 9000, allowLocalHttp = true),
        )
        assertEquals(
            "https://192.168.1.50:9000",
            ServerAddress.build("https://192.168.1.50:9000", null, allowLocalHttp = true),
        )
    }

    @Test
    fun `con https y otro puerto en el campo ese puerto se inserta`() {
        // Solo el 9000 sugerido se ignora: cualquier otro lo ha escrito la persona.
        assertEquals(
            "https://192.168.1.50:8443",
            ServerAddress.build("https://192.168.1.50", 8443, allowLocalHttp = true),
        )
    }

    @Test
    fun `sin la opción un 9000 en https lo ha escrito la persona y se respeta`() {
        // Sin modo local el campo viene con 443: el 9000 no es la sugerencia.
        assertEquals("https://mealie.example.com:9000", ServerAddress.build("mealie.example.com", 9000))
        assertEquals("https://mealie.example.com:9000", ServerAddress.build("https://mealie.example.com", 9000))
    }

    @Test
    fun `con http el 9000 del campo se sigue insertando`() {
        assertEquals("http://192.168.1.50:9000", ServerAddress.build("http://192.168.1.50", 9000, allowLocalHttp = true))
    }

    @Test
    fun `los nombres local ya no valen con la opción`() {
        assertRejected("mealie.local", 9000, allowLocalHttp = true)
        assertRejected("http://mealie.local:8080", 9000, allowLocalHttp = true)
    }

    @Test
    fun `el rechazo por servidor no local se distingue por tipo`() {
        assertNotLocal("mealie.example.com", 9000)
        assertNotLocal("mealie.local", 9000)
        assertNotLocal("8.8.8.8", null)
        assertNotLocal("127.0.0.1", 9000)
        // Otros rechazos con la opción siguen siendo IllegalArgumentException a secas.
        assertNotNotLocal("192.168.1.50:8080", 7000)
        assertNotNotLocal("", 9000)
        assertNotNotLocal("192.168.1.50", 70000)
    }

    @Test
    fun `con http explícito y la opción se acepta hacia la red local`() {
        assertEquals(
            "http://192.168.1.50:9000",
            ServerAddress.build("HTTP://192.168.1.50:9000/", null, allowLocalHttp = true),
        )
    }

    @Test
    fun `con la opción el 80 del campo cede ante el puerto del servidor`() {
        assertEquals("http://192.168.1.50:9000", ServerAddress.build("192.168.1.50:9000", 80, allowLocalHttp = true))
        assertEquals(
            "http://192.168.1.50:9000",
            ServerAddress.build("http://192.168.1.50:9000", ServerAddress.DEFAULT_HTTP_PORT, allowLocalHttp = true),
        )
        assertEquals("http://[fd00::5]:9000", ServerAddress.build("[fd00::5]:9000", 80, allowLocalHttp = true))
    }

    @Test
    fun `con la opción el 443 del campo ya no cede en http`() {
        // En http el puerto por defecto es el 80: un 443 en el campo es una
        // intención distinta del 9000 del servidor y no se adivina.
        assertRejected("192.168.1.50:9000", 443, allowLocalHttp = true)
    }

    @Test
    fun `con la opción el 9000 sugerido del campo cede ante el puerto del servidor`() {
        // Es el valor con el que la interfaz rellena el campo en modo local:
        // quien escribe otro puerto en el servidor no ha tocado el campo.
        assertEquals("http://192.168.1.50:8080", ServerAddress.build("192.168.1.50:8080", 9000, allowLocalHttp = true))
        assertEquals("http://[fd00::5]:8080", ServerAddress.build("[fd00::5]:8080", 9000, allowLocalHttp = true))
    }

    @Test
    fun `con la opción dos puertos distintos que no son los por defecto se rechazan`() {
        assertRejected("192.168.1.50:8080", 7000, allowLocalHttp = true)
        val message = rejectionMessage("192.168.1.50:8080", 7000, allowLocalHttp = true)
        assertFalse(message.contains("192.168.1.50"))
        assertFalse(message.contains("8080"))
        assertFalse(message.contains("7000"))
    }

    @Test
    fun `con la opción se rechazan los servidores que no son locales`() {
        assertRejected("mealie.example.com", 9000, allowLocalHttp = true)
        assertRejected("8.8.8.8", null, allowLocalHttp = true)
        assertRejected("localhost", 9000, allowLocalHttp = true)
        assertRejected("127.0.0.1", 9000, allowLocalHttp = true)
        assertRejected("http://mealie.example.com", null, allowLocalHttp = true)
        val message = rejectionMessage("mealie.example.com", 9000, allowLocalHttp = true)
        assertFalse(message.contains("mealie.example.com"))
    }

    @Test
    fun `el puerto sugerido depende del modo`() {
        assertEquals(443, ServerAddress.defaultPortFor(allowLocalHttp = false))
        assertEquals(9000, ServerAddress.defaultPortFor(allowLocalHttp = true))
        assertEquals(ServerAddress.DEFAULT_HTTPS_PORT, ServerAddress.defaultPortFor(false))
        assertEquals(ServerAddress.DEFAULT_LOCAL_HTTP_PORT, ServerAddress.defaultPortFor(true))
    }

    @Test
    fun `la salida en modo local ya es canónica para ServerUrl`() {
        val cases = listOf(
            "192.168.1.50" to 9000,
            "192.168.1.50" to 80,
            "https://192.168.1.50" to 9000,
            "[fd00::5]" to 9000,
        )
        for ((input, port) in cases) {
            val built = ServerAddress.build(input, port, allowLocalHttp = true)
            assertEquals(built, ServerUrl.normalize(built, allowLocalHttp = true))
        }
    }

    @Test
    fun `el usuario y contraseña embebidos se rechazan sin repetirlos`() {
        // Si alguien pega una credencial en el campo servidor, el mensaje que
        // se ve en la pantalla no puede enseñarla.
        val message = rejectionMessage("usuario:secreto@mealie.example.com", null)
        assertFalse(message.contains("secreto"))
    }

    @Test
    fun `el host inválido se rechaza`() {
        assertRejected("mi_host", null)
        assertRejected("mealie.example.com..", null)
    }

    @Test
    fun `un puerto con letras dentro del servidor se rechaza`() {
        assertRejected("https://mealie.example.com:puerto", null)
    }

    @Test
    fun `el mensaje de conflicto de puertos no repite la entrada`() {
        val message = rejectionMessage("https://mealie.example.com:8443", 9000)
        assertFalse(message.contains("8443"))
        assertFalse(message.contains("9000"))
        assertFalse(message.contains("mealie.example.com"))
    }

    @Test
    fun `la salida ya es canónica para ServerUrl`() {
        // La app guarda la cadena que sale de build y la relee en cada arranque:
        // tiene que valer tal cual como entrada de normalize, sin sorpresas.
        val cases = listOf(
            "mealie.example.com" to null,
            "mealie.example.com" to 8443,
            "https://MEALIE.example.com/" to null,
            "[2001:db8::1]" to 8443,
        )
        for ((input, port) in cases) {
            val built = ServerAddress.build(input, port)
            assertEquals(built, ServerUrl.normalize(built))
        }
    }

    // ---------------------------------------------------------------- ayuda

    /**
     * Comprueba que la combinación se rechaza y que el mensaje explica el
     * motivo: en la primera pantalla, un error mudo no ayuda a corregir nada.
     */
    private fun assertRejected(server: String, port: Int?, allowLocalHttp: Boolean = false) {
        val message = rejectionMessage(server, port, allowLocalHttp)
        assertTrue("Se esperaba que «$server» (puerto $port) se rechazara", message.isNotEmpty())
    }

    private fun assertNotLocal(server: String, port: Int?) {
        try {
            ServerAddress.build(server, port, allowLocalHttp = true)
        } catch (expected: NotLocalAddressException) {
            return
        } catch (other: IllegalArgumentException) {
            throw AssertionError("«$server» se ha rechazado por otro motivo: ${other.message}")
        }
        throw AssertionError("Se esperaba que «$server» se rechazara")
    }

    private fun assertNotNotLocal(server: String, port: Int?) {
        try {
            ServerAddress.build(server, port, allowLocalHttp = true)
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected is NotLocalAddressException)
            return
        }
        throw AssertionError("Se esperaba que «$server» se rechazara")
    }

    /** Rechazo por subruta: el motivo lo pone `ServerUrl`, no esta capa. */
    private fun assertUnsupportedPath(server: String, port: Int?) {
        try {
            ServerAddress.build(server, port)
        } catch (expected: UnsupportedPathException) {
            assertFalse(expected.message.isNullOrBlank())
            return
        } catch (other: IllegalArgumentException) {
            throw AssertionError("«$server» se ha rechazado por otro motivo: ${other.message}")
        }
        throw AssertionError("Se esperaba que «$server» se rechazara por la subruta")
    }

    /** Mensaje del rechazo, o cadena vacía si la entrada se ha aceptado. */
    private fun rejectionMessage(server: String, port: Int?, allowLocalHttp: Boolean = false): String {
        return try {
            ServerAddress.build(server, port, allowLocalHttp)
            ""
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "El rechazo de «$server» no trae un mensaje claro",
                !expected.message.isNullOrBlank(),
            )
            expected.message.orEmpty()
        }
    }
}
