package io.github.garfieldrockero.gastronomicon.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del token de API: lo importante es que el valor real no se cuele
 * nunca por toString, que es la vía por la que acabaría en un log, en un
 * mensaje de error o en una captura de pantalla.
 */
class ApiTokenTest {

    private val token = ApiToken("supersecreto-1234")

    @Test
    fun `un token normal se acepta y guarda su valor`() {
        assertEquals("supersecreto-1234", token.value)
    }

    @Test
    fun `toString enmascara el valor`() {
        assertEquals("ApiToken(***)", token.toString())
    }

    @Test
    fun `dentro de un texto sigue enmascarado`() {
        assertFalse("El token configurado es $token".contains("supersecreto"))
    }

    @Test
    fun `dentro de una colección sigue enmascarado`() {
        // Las colecciones llaman a toString de cada elemento: tampoco por ahí.
        assertFalse(listOf(token).toString().contains("supersecreto"))
    }

    @Test
    fun `authorizationHeaderValue forma la cabecera completa`() {
        assertEquals("Bearer supersecreto-1234", token.authorizationHeaderValue())
    }

    @Test
    fun `se acepta el límite exacto de longitud`() {
        val exact = "a".repeat(ApiToken.MAX_LENGTH)
        assertEquals(exact, ApiToken(exact).value)
    }

    @Test
    fun `se rechaza el token vacío`() {
        assertRejected("")
    }

    @Test
    fun `se rechaza la palabra Bearer pegada delante`() {
        // Copiar la cabecera entera en vez de solo el token es el error más
        // humano; merece su propio mensaje en vez del genérico de espacios.
        assertRejected("Bearer abc123")
        assertRejected("bearer abc123")
    }

    @Test
    fun `se rechazan espacios y saltos de línea`() {
        assertRejected(" token")
        assertRejected("to ken")
        assertRejected("token\n")
        assertRejected("to\u0009ken")
    }

    @Test
    fun `fromInput quita espacio, tabulador y salto de línea de los extremos`() {
        // Es lo que arrastra un copiado de la interfaz de Mealie: el valor del
        // campo viene con basura alrededor y la persona no la ve.
        assertEquals("supersecreto", ApiToken.fromInput("  supersecreto  ").value)
        assertEquals("supersecreto", ApiToken.fromInput("\tsupersecreto\t").value)
        assertEquals("supersecreto", ApiToken.fromInput("\nsupersecreto\n").value)
        assertEquals("supersecreto", ApiToken.fromInput("\r\n \tsupersecreto\t \r\n").value)
        // Con la máscara de siempre y la cabecera bien formada.
        assertEquals("ApiToken(***)", ApiToken.fromInput(" supersecreto ").toString())
        assertEquals("Bearer supersecreto", ApiToken.fromInput(" supersecreto ").authorizationHeaderValue())
    }

    @Test
    fun `fromInput no perdona un espacio en medio`() {
        // Recortar los extremos no puede cambiar el token: uno con un espacio
        // dentro es otro token distinto y fallaría con un 401 sin explicación.
        try {
            ApiToken.fromInput("  super secreto  ")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "El mensaje tiene que hablar de espacios en medio",
                expected.message.orEmpty().contains("en medio"),
            )
            return
        }
        throw AssertionError("Se esperaba que el espacio interno se rechazara")
    }

    @Test
    fun `el mensaje de los espacios internos no repite la entrada`() {
        val message = rejectionMessage("super secreto")
        assertTrue("El rechazo no habla de espacios en medio", message.contains("en medio"))
        assertFalse("El mensaje repite el token", message.contains("super secreto"))
        assertFalse("El mensaje repite el token", message.contains("secreto"))
    }

    @Test
    fun `fromInput de solo espacios queda vacío y se rechaza`() {
        for (raw in listOf("   ", "\t", "\n", "\r\n \t\n")) {
            try {
                ApiToken.fromInput(raw)
                throw AssertionError("Se esperaba que un token en blanco se rechazara")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "El rechazo no trae un mensaje claro",
                    !expected.message.isNullOrBlank(),
                )
                // El mensaje del vacío no puede repetir lo pegado: son espacios.
                assertFalse("El mensaje repite la entrada", expected.message.orEmpty().contains(raw))
            }
        }
    }

    @Test
    fun `se rechazan los caracteres de control`() {
        assertRejected("to\u0000ken")
        assertRejected("to\u007Fken")
    }

    @Test
    fun `se rechazan los caracteres invisibles y el espacio duro`() {
        assertRejected("\uFEFFtoken")
        assertRejected("to\u200Bken")
        assertRejected("to\u202Eken")
        assertRejected("to\u00A0ken")
    }

    @Test
    fun `se rechaza lo que no es ASCII imprimible`() {
        // El token de Mealie es ASCII. Con acentos o guiones tipográficos, la
        // cabecera revienta en la capa de red de Android con una excepción que
        // puede repetir el «Bearer <token>» entero: mejor cortarlo aquí.
        assertRejected("tokén")
        assertRejected("a–b")
        assertRejected("token☃")
    }

    @Test
    fun `el rechazo de lo no ASCII no repite la entrada`() {
        val acentuado = rejectionMessage("tokén")
        assertFalse("El mensaje repite el token", acentuado.contains("tokén"))
        assertFalse("El mensaje lleva la letra acentuada", acentuado.contains("é"))
        val guion = rejectionMessage("a–b")
        assertFalse("El mensaje repite el token", guion.contains("a–b"))
        assertFalse("El mensaje lleva el guion tipográfico", guion.contains("–"))
    }

    @Test
    fun `se rechaza el token demasiado largo`() {
        assertRejected("a".repeat(ApiToken.MAX_LENGTH + 1))
    }

    @Test
    fun `el mensaje de rechazo no repite el token`() {
        val message = rejectionMessage("otro-supersecreto-9876")
        assertFalse("El mensaje de rechazo repite el token", message.contains("otro-supersecreto"))
    }

    // ---------------------------------------------------------------- ayuda

    private fun assertRejected(value: String) {
        val message = rejectionMessage(value)
        assertTrue("Se esperaba que el token se rechazara", message.isNotEmpty())
    }

    /** Mensaje del rechazo, o cadena vacía si el token se ha aceptado. */
    private fun rejectionMessage(value: String): String {
        return try {
            ApiToken(value)
            ""
        } catch (expected: IllegalArgumentException) {
            assertTrue("El rechazo no trae un mensaje claro", !expected.message.isNullOrBlank())
            expected.message.orEmpty()
        }
    }
}
