package io.github.garfieldrockero.gastronomicon.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la validación de la petición: la ruta y las cabeceras se curran
 * aquí para que ningún transporte, ni siquiera uno de mentira para vistas
 * previas de la interfaz, reciba algo que no deba salir de la app.
 */
class HttpRequestTest {

    @Test
    fun `la petición mínima se acepta`() {
        val request = HttpRequest(HttpMethod.GET, "/api")
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/api", request.path)
        assertNull(request.body)
        assertTrue(request.headers.isEmpty())
    }

    @Test
    fun `la barra final se conserva`() {
        // Mealie (FastAPI) distingue /api/x de /api/x/ y redirige entre ellas:
        // la ruta se manda tal cual y decide el servidor, no el cliente.
        assertEquals("/recetas/", HttpRequest(HttpMethod.GET, "/recetas/").path)
    }

    @Test
    fun `la raíz se acepta`() {
        assertEquals("/", HttpRequest(HttpMethod.GET, "/").path)
    }

    @Test
    fun `la ruta sin barra inicial se rechaza`() {
        assertRejected("api/recetas")
    }

    @Test
    fun `la consulta y el fragmento se rechazan`() {
        // La consulta llegará como campo propio cuando la API esté verificada;
        // pegarla a mano en la ruta sería imposible de validar luego.
        assertRejected("/recetas?buscar=tortilla")
        assertRejected("/recetas#paso-2")
    }

    @Test
    fun `un GET con cuerpo se rechaza`() {
        // En HttpURLConnection, marcar salida o pedir el flujo de salida cambia
        // el método a POST, aunque el cuerpo esté vacío: el servidor recibiría
        // otra petición distinta de la que se cree enviar.
        try {
            HttpRequest(HttpMethod.GET, "/api", body = ByteArray(0))
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "El rechazo no dice por qué",
                expected.message.orEmpty().contains("cuerpo"),
            )
            return
        }
        throw AssertionError("Se esperaba que un GET con cuerpo se rechazara")
    }

    @Test
    fun `un cuerpo vacío se acepta en los métodos que lo llevan`() {
        // Un POST sin datos (por ejemplo, el que dispara una importación) es
        // legítimo: lo que se rechaza es el cuerpo en un GET.
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            val request = HttpRequest(method, "/api", body = ByteArray(0))
            assertEquals(0, request.body?.size)
        }
        // Y sin cuerpo también, que es como viaja la mayoría de peticiones.
        assertNull(HttpRequest(HttpMethod.PUT, "/api").body)
    }

    @Test
    fun `los puntos y las barras dobles se rechazan`() {
        assertRejected("/a/./b")
        assertRejected("/a/../b")
        assertRejected("/a//b")
        assertRejected("/a/%2e%2e/b")
    }

    @Test
    fun `la raíz doble y los segmentos vacíos se rechazan`() {
        // «//» no es la raíz: deja un segmento vacío, igual que «/a//b», y el
        // servidor (o un proxy) lo normalizaría a su manera.
        assertRejected("//")
        assertRejected("//a")
        assertRejected("/a//")
        assertRejected("/a///b")
    }

    @Test
    fun `lo que no es ASCII se rechaza`() {
        assertRejected("/café")
        assertRejected("/recetas con espacios")
    }

    @Test
    fun `los escapes de control se rechazan`() {
        assertRejected("/recetas/%00")
        assertRejected("/a%2fb")
    }

    @Test
    fun `los caracteres que no son pchar se rechazan`() {
        // Nada de esto puede llegar al parser de URI: una URISyntaxException
        // arrastraría la URL entera en su mensaje.
        assertRejected("/a\"b")
        assertRejected("/a<b")
        assertRejected("/a>b")
        assertRejected("/a{b")
        assertRejected("/a}b")
        assertRejected("/a|b")
        assertRejected("/a\\b")
        assertRejected("/a b")
        assertRejected("/a^b")
        assertRejected("/a`b")
        assertRejected("/a[b")
        assertRejected("/a]b")
    }

    @Test
    fun `un escape mal formado se rechaza`() {
        assertRejected("/a%zz")
        assertRejected("/a%2")
        assertRejected("/a%")
        assertRejected("/100%")
        // Un dígito hexadecimal de otro alfabeto (Ａ = U+FF21) no vale como
        // escape: el «%» tiene que ser ASCII para que el parser de URI no falle.
        assertRejected("/a%Ａ1")
    }

    @Test
    fun `los pchar y los escapes bien formados se aceptan`() {
        val path = "/api/recetas:2024/x(y),z;w=1!\$&'*+@a~b_c-d.e%20f"
        assertEquals(path, HttpRequest(HttpMethod.GET, path).path)
    }

    @Test
    fun `Authorization a mano se rechaza`() {
        try {
            HttpRequest(HttpMethod.GET, "/api", mapOf("Authorization" to "Bearer lo-que-sea"))
        } catch (expected: IllegalArgumentException) {
            // El mensaje tiene que decir quién manda en esa cabecera.
            assertTrue(expected.message.orEmpty().contains("transporte"))
            return
        }
        throw AssertionError("Se esperaba que se rechazara la cabecera Authorization manual")
    }

    @Test
    fun `caller Authorization header is rejected with any casing`() {
        // La comparación no puede depender de la grafía: una variante distinta
        // sustituiría después a la cabecera que pone el transporte.
        for (name in listOf("aUtHoRiZaTiOn", "AUTHORIZATION", "authorization")) {
            assertRejectedHeader(name, "Bearer senhuelo")
        }
    }

    @Test
    fun `las cabeceras que gestiona el transporte se rechazan`() {
        assertRejectedHeader("Content-Length")
        assertRejectedHeader("Transfer-Encoding")
        assertRejectedHeader("Host")
        assertRejectedHeader("Connection")
    }

    @Test
    fun `un valor de cabecera con CR o LF se rechaza`() {
        // Un salto de línea en una cabecera es el clásico «header injection»:
        // mejor que no exista la posibilidad de construir la petición.
        assertRejectedHeader("X-Prueba", "valor\r\nX-Otra: mal")
        assertRejectedHeader("X-Prueba", "valor\u0000")
    }

    @Test
    fun `las cabeceras normales pasan`() {
        val request = HttpRequest(
            HttpMethod.POST,
            "/api/recetas",
            mapOf("Content-Type" to "application/json"),
            "{}".toByteArray(),
        )
        assertEquals("application/json", request.headers["Content-Type"])
    }

    // ---------------------------------------------------------------- ayuda

    private fun assertRejected(path: String) {
        try {
            HttpRequest(HttpMethod.GET, path)
        } catch (expected: IllegalArgumentException) {
            assertTrue("El rechazo no trae un mensaje claro", !expected.message.isNullOrBlank())
            return
        }
        throw AssertionError("Se esperaba que la ruta «$path» se rechazara")
    }

    private fun assertRejectedHeader(name: String, value: String = "x") {
        try {
            HttpRequest(HttpMethod.GET, "/api", mapOf(name to value))
        } catch (expected: IllegalArgumentException) {
            assertTrue("El rechazo no trae un mensaje claro", !expected.message.isNullOrBlank())
            return
        }
        throw AssertionError("Se esperaba que la cabecera «$name» se rechazara")
    }
}
