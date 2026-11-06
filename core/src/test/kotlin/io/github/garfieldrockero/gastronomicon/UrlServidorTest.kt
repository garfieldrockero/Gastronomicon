package io.github.garfieldrockero.gastronomicon

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
class UrlServidorTest {

    // ---------------------------------------------------------------- acepta

    @Test
    fun `la URL simple se devuelve sin cambios`() {
        assertEquals(
            "https://mealie.example.com",
            UrlServidor.normalizar("https://mealie.example.com"),
        )
    }

    @Test
    fun `la barra final de la raíz se quita`() {
        assertEquals(
            "https://mealie.example.com",
            UrlServidor.normalizar("https://mealie.example.com/"),
        )
    }

    @Test
    fun `la subruta se conserva y queda sin barra final`() {
        assertEquals(
            "https://servidor.example.com/mealie",
            UrlServidor.normalizar("https://servidor.example.com/mealie/"),
        )
        // Sin barra final de origen: no se toca nada.
        assertEquals(
            "https://servidor.example.com/mealie",
            UrlServidor.normalizar("https://servidor.example.com/mealie"),
        )
        assertEquals(
            "https://servidor.example.com/cocina/postres",
            UrlServidor.normalizar("https://servidor.example.com/cocina/postres/"),
        )
    }

    @Test
    fun `las barras finales sobrantes se quitan todas`() {
        assertEquals(
            "https://servidor.example.com/mealie",
            UrlServidor.normalizar("https://servidor.example.com/mealie//"),
        )
    }

    @Test
    fun `el esquema en mayúsculas se acepta y se normaliza a minúsculas`() {
        assertEquals(
            "https://mealie.example.com",
            UrlServidor.normalizar("HTTPS://mealie.example.com/"),
        )
    }

    @Test
    fun `los espacios de los extremos se quitan`() {
        assertEquals(
            "https://mealie.example.com",
            UrlServidor.normalizar("   https://mealie.example.com   "),
        )
        assertEquals(
            "https://mealie.example.com",
            UrlServidor.normalizar("\t https://mealie.example.com \n"),
        )
    }

    @Test
    fun `el puerto explícito se conserva`() {
        assertEquals(
            "https://mealie.example.com:8443",
            UrlServidor.normalizar("https://mealie.example.com:8443"),
        )
        assertEquals(
            "https://mealie.example.com:8443",
            UrlServidor.normalizar("https://mealie.example.com:8443/"),
        )
        // El puerto por defecto no se añade ni se quita: si lo escribe la
        // persona, se respeta.
        assertEquals(
            "https://mealie.example.com:443",
            UrlServidor.normalizar("https://mealie.example.com:443"),
        )
    }

    @Test
    fun `esquema en mayúscula, puerto y subruta juntos`() {
        assertEquals(
            "https://servidor.example.com:8443/mealie",
            UrlServidor.normalizar("HTTPS://servidor.example.com:8443/mealie/"),
        )
    }

    @Test
    fun `un host IPv6 entre corchetes se conserva con su puerto`() {
        assertEquals(
            "https://[2001:db8::1]:8443",
            UrlServidor.normalizar("https://[2001:db8::1]:8443/"),
        )
        // Sin puerto también hay que devolver los corchetes.
        assertEquals(
            "https://[2001:db8::1]",
            UrlServidor.normalizar("https://[2001:db8::1]/"),
        )
    }

    @Test
    fun `se rechaza un puerto fuera de rango`() {
        seRechaza("https://mealie.example.com:99999/")
        seRechaza("https://mealie.example.com:65536/")
        seRechaza("https://mealie.example.com:0/")
        seRechaza("https://mealie.example.com:-1/")
    }

    @Test
    fun `el puerto máximo se acepta y los ceros a la izquierda se normalizan`() {
        assertEquals(
            "https://mealie.example.com:65535",
            UrlServidor.normalizar("https://mealie.example.com:65535"),
        )
        assertEquals(
            "https://mealie.example.com:80",
            UrlServidor.normalizar("https://mealie.example.com:00080"),
        )
    }

    @Test
    fun `un puerto vacío se descarta`() {
        assertEquals(
            "https://mealie.example.com",
            UrlServidor.normalizar("https://mealie.example.com:/"),
        )
    }

    @Test
    fun `se rechaza un identificador de zona IPv6`() {
        seRechaza("https://[fe80::1%25eth0]/")
        seRechaza("https://[fe80::1%eth0]/")
    }

    @Test
    fun `se rechaza una IPv6 sin corchetes`() {
        seRechaza("https://2001:db8::1/")
    }

    @Test
    fun `la ruta escapada se conserva tal cual, sin descodificarla`() {
        // rawPath y no path: si se descodificara, «caf%C3%A9» volvería a tener
        // un carácter no ASCII y la URL dejaría de ser válida.
        assertEquals(
            "https://servidor.example.com/caf%C3%A9",
            UrlServidor.normalizar("https://servidor.example.com/caf%C3%A9/"),
        )
    }

    // ---------------------------------------------------------------- rechaza

    @Test
    fun `se rechaza http`() {
        seRechaza("http://mealie.example.com")
    }

    @Test
    fun `se rechaza cualquier otro esquema`() {
        seRechaza("ftp://mealie.example.com")
        seRechaza("javascript://mealie.example.com")
    }

    @Test
    fun `se rechaza la cadena vacía o solo espacios`() {
        seRechaza("")
        seRechaza("   ")
        seRechaza("\t \n")
    }

    @Test
    fun `se rechazan los espacios internos`() {
        seRechaza("https://mealie .example.com")
        seRechaza("https://mealie.example.com/pa th")
        seRechaza("https://mealie.example.com\tpath")
        seRechaza("https://mealie.example.com\n/algo")
    }

    @Test
    fun `se rechaza usuario o contraseña embebidos`() {
        seRechaza("https://usuario:secreto@mealie.example.com")
        seRechaza("https://usuario@mealie.example.com")
    }

    @Test
    fun `se rechaza la consulta`() {
        seRechaza("https://mealie.example.com/?receta=1")
        seRechaza("https://mealie.example.com/recetas?buscar=tortilla")
        // Una interrogación suelta también es una consulta (vacía).
        seRechaza("https://mealie.example.com/?")
    }

    @Test
    fun `se rechaza el fragmento`() {
        seRechaza("https://mealie.example.com/#seccion")
        seRechaza("https://mealie.example.com/recetas#paso-2")
        // Una almohadilla suelta también es un fragmento (vacío).
        seRechaza("https://mealie.example.com/#")
    }

    @Test
    fun `se rechaza una URL sin host`() {
        seRechaza("https:///recetas")
        seRechaza("https://")
    }

    @Test
    fun `se rechaza una URL sin esquema`() {
        seRechaza("mealie.example.com")
        seRechaza("www.mealie.example.com/recetas")
    }

    @Test
    fun `se rechaza una URL malformada`() {
        // Un puerto con letras deja el authority sin partir en host:puerto.
        seRechaza("https://mealie.example.com:puerto/")
        // Caracteres que no son legales en una ruta.
        seRechaza("https://mealie.example.com/<script>")
    }

    @Test
    fun `el mensaje de error no repite credenciales ni la entrada`() {
        // Si alguien pega una contraseña o un token en el campo de la URL, el mensaje
        // que se muestra en Ajustes no puede repetirlo.
        val conCredenciales = mensajeDeRechazo("https://usuario:secreto@mealie.example.com")
        assertFalse(conCredenciales.contains("usuario"))
        assertFalse(conCredenciales.contains("secreto"))

        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtZWFsaWUifQ.firma"
        val sinEsquema = mensajeDeRechazo(token)
        assertFalse(sinEsquema.contains(token))

        val malformada = mensajeDeRechazo("https://usuario:secreto^x@mealie.example.com")
        assertFalse(malformada.contains("secreto"))
    }

    // ---------------------------------------------------------------- ayuda

    /**
     * Comprueba que la entrada se rechaza y que el mensaje explica el motivo:
     * en Ajustes, un error sin texto no ayuda a corregir la URL.
     */
    private fun seRechaza(entrada: String) {
        var mensaje: String? = null
        try {
            UrlServidor.normalizar(entrada)
        } catch (esperada: IllegalArgumentException) {
            mensaje = esperada.message
        }
        assertTrue("Se esperaba que «$entrada» se rechazara", mensaje != null)
        assertTrue(
            "El rechazo de «$entrada» no trae un mensaje claro",
            !mensaje.isNullOrBlank(),
        )
    }

    private fun mensajeDeRechazo(entrada: String): String {
        try {
            UrlServidor.normalizar(entrada)
        } catch (esperada: IllegalArgumentException) {
            return esperada.message.orEmpty()
        }
        throw AssertionError("Se esperaba que «$entrada» se rechazara")
    }
}
