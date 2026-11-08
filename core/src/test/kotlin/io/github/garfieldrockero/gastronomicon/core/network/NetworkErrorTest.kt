package io.github.garfieldrockero.gastronomicon.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la jerarquía de errores de red.
 *
 * Lo que se comprueba aquí no es el mensaje, sino lo que el error *no* lleva:
 * ni traza ni causa original (podrían acabar en un log con la URL o el host) y
 * ni siquiera excepciones suprimidas, porque los subtipos «object» son
 * instancias compartidas por toda la app y las irían acumulando entre
 * peticiones.
 */
class NetworkErrorTest {

    @Test
    fun `el error no arrastra traza ni causa`() {
        val error = NetworkError.Timeout
        assertNull(error.cause)
        assertEquals(0, error.stackTrace.size)
        // La traza ni siquiera se puede rellenar: no hay nada que imprimir.
        error.fillInStackTrace()
        assertEquals(0, error.stackTrace.size)
    }

    @Test
    fun `una instancia compartida no acumula excepciones suprimidas`() {
        val error = NetworkError.NoConnection
        error.addSuppressed(IllegalStateException("primera"))
        error.addSuppressed(IllegalStateException("segunda"))
        assertEquals(0, error.suppressed.size)
    }

    @Test
    fun `HttpError guarda el codigo y nada mas`() {
        val error = NetworkError.HttpError(500)
        assertEquals(500, error.statusCode)
        assertNull(error.cause)
        assertEquals(0, error.stackTrace.size)
        // El mensaje se puede enseñar tal cual: es fijo y no trae datos de la
        // petición ni de lo que escribió la persona.
        assertTrue(error.message.orEmpty().contains("500"))
    }
}
