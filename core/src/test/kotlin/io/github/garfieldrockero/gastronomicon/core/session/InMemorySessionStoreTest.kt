package io.github.garfieldrockero.gastronomicon.core.session

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class InMemorySessionStoreTest {

    private val first = Session("https://uno.example.org", ApiToken("token-uno"))
    private val second = Session("https://dos.example.org", ApiToken("token-dos"))

    @Test
    fun `load sin nada guardado devuelve null`() {
        assertNull(InMemorySessionStore().load())
    }

    @Test
    fun `save y load devuelven la misma sesion`() {
        val store = InMemorySessionStore()
        store.save(first)
        assertSame(first, store.load())
    }

    @Test
    fun `save sobrescribe la anterior`() {
        val store = InMemorySessionStore()
        store.save(first)
        store.save(second)
        assertEquals(second, store.load())
    }

    @Test
    fun `clear borra la sesion`() {
        val store = InMemorySessionStore()
        store.save(first)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `clear sin nada guardado no falla`() {
        val store = InMemorySessionStore()
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `se puede guardar de nuevo tras borrar`() {
        val store = InMemorySessionStore()
        store.save(first)
        store.clear()
        store.save(second)
        assertEquals(second, store.load())
    }

    @Test
    fun `puede arrancar con una sesion inicial`() {
        assertEquals(first, InMemorySessionStore(first).load())
    }

    @Test
    fun `toString no enseña datos de la sesion`() {
        val text = InMemorySessionStore(first).toString()
        assertFalse(text.contains("token-uno"))
        assertFalse(text.contains("uno.example"))
    }
}
