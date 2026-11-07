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
    fun `se rechaza http`() {
        assertRejected("http://mealie.example.com")
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
    private fun assertRejected(entrada: String) {
        var mensaje: String? = null
        try {
            ServerUrl.normalize(entrada)
        } catch (esperada: IllegalArgumentException) {
            mensaje = esperada.message
        }
        assertTrue("Se esperaba que «$entrada» se rechazara", mensaje != null)
        assertTrue(
            "El rechazo de «$entrada» no trae un mensaje claro",
            !mensaje.isNullOrBlank(),
        )
    }

    private fun rejectionMessage(entrada: String): String {
        try {
            ServerUrl.normalize(entrada)
        } catch (esperada: IllegalArgumentException) {
            return esperada.message.orEmpty()
        }
        throw AssertionError("Se esperaba que «$entrada» se rechazara")
    }
}
