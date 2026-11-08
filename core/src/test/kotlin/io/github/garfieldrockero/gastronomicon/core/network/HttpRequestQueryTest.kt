package io.github.garfieldrockero.gastronomicon.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pruebas del campo `query` de la petición: solo entra consulta ya codificada. */
class HttpRequestQueryTest {

    @Test
    fun `sin consulta es nula`() {
        assertNull(HttpRequest(HttpMethod.GET, "/api").query)
    }

    @Test
    fun `una consulta bien codificada se acepta`() {
        val request = HttpRequest(HttpMethod.GET, "/api/recipes", query = "page=1&search=ni%C3%B1o")
        assertEquals("page=1&search=ni%C3%B1o", request.query)
    }

    @Test
    fun `la consulta vacia se rechaza para no enviar un signo de interrogacion suelto`() {
        assertRejected("")
    }

    @Test
    fun `la consulta de longitud maxima se acepta y una mas se rechaza`() {
        val max = HttpRequest.MAX_QUERY_LENGTH
        assertEquals(max, HttpRequest(HttpMethod.GET, "/api", query = "a".repeat(max)).query?.length)
        assertRejected("a".repeat(max + 1))
        assertRejected("a=%41".repeat(100_000))
    }

    @Test
    fun `caracteres sin codificar se rechazan`() {
        for (query in listOf("a b", "a#b", "a?b", "a+b", "a;b", "a,b", "ñ=1", "a\r\nb", "a/b", "a<b", "a\"b")) {
            assertRejected(query)
        }
    }

    @Test
    fun `escapes mal formados o de control se rechazan`() {
        for (query in listOf("a=%", "a=%4", "a=%zz", "a=%00", "a=%0A", "a=%0d", "a=%7F", "a=%٣٣")) {
            assertRejected(query)
        }
    }

    @Test
    fun `la ruta sigue sin admitir la consulta`() {
        try {
            HttpRequest(HttpMethod.GET, "/api?x=1")
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("La ruta con «?» debía rechazarse")
    }

    private fun assertRejected(query: String) {
        try {
            HttpRequest(HttpMethod.GET, "/api", query = query)
        } catch (expected: IllegalArgumentException) {
            assertTrue("Sin mensaje claro", !expected.message.isNullOrBlank())
            // El mensaje no debe devolver la consulta (puede llevar texto de la persona).
            assertTrue(!expected.message.orEmpty().contains(query) || query.length < 2)
            return
        }
        throw AssertionError("Se esperaba que la consulta «$query» se rechazara")
    }
}
