package io.github.garfieldrockero.gastronomicon.core.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeUrlTest {

    @Test
    fun `http y https absolutas pasan tal cual`() {
        for (url in listOf(
            "http://ejemplo.org", "https://ejemplo.org/a/b?c=d#e", "HTTPS://Ejemplo.ORG/", "https://ejemplo.org:8443/x",
            "http://192.168.1.5/x", "https://[2001:db8::1]/x", "https://xn--espaa-rta.org/",
        )) {
            assertEquals(url, SafeUrl.httpOrNull(url))
        }
    }

    @Test
    fun `se recortan los espacios de los extremos`() {
        assertEquals("https://ejemplo.org/", SafeUrl.httpOrNull("  https://ejemplo.org/\n"))
    }

    @Test
    fun `cualquier otra cosa da null`() {
        val bad = listOf(
            null, "", "   ", "javascript:alert(1)", "JaVaScRiPt:alert(1)", "java\tscript:alert(1)", "data:text/html,hola",
            "file:///etc/passwd", "content://x/y", "intent://x#Intent;end", "ftp://ejemplo.org", "mailto:a@b.c",
            "//ejemplo.org/x", "/ruta/relativa", "ejemplo.org", "https:ejemplo.org", "https://", "https:///x",
            "https://user@ejemplo.org/", "https://user:pass@ejemplo.org/", "https://ejemplo.org/con espacio",
            "https://ejemplo.org/\u0000", "https://ejemplo.org/‮", "https://ejemplo.org/ñ", "https://españa.org/",
            "https://ejemplo.org/<script>", "https://ejemplo.org/\"", "https://" + "a".repeat(SafeUrl.MAX_LENGTH),
        )
        for (url in bad) assertNull(url, SafeUrl.httpOrNull(url))
    }
}
