package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFilterTest {
    private val names = listOf("Café con leche", "Niño envuelto", "Tarta de Santiago", "CREMA catalana", "Ají picante")

    private fun filter(query: String?) = TextFilter.filter(names, query) { listOf(it) }

    @Test
    fun `ignora acentos y mayúsculas en los dos lados`() {
        assertEquals(listOf("Café con leche"), filter("cafe"))
        assertEquals(listOf("Café con leche"), filter("CAFÉ"))
        assertEquals(listOf("Niño envuelto"), filter("nino"))
        assertEquals(listOf("Niño envuelto"), filter("niño"))
        assertEquals(listOf("CREMA catalana"), filter("crema"))
        assertEquals(listOf("Ají picante"), filter("aji"))
    }

    @Test
    fun `también con letras ya descompuestas`() {
        assertEquals(listOf("Café con leche"), filter("cafe\u0301"))
    }

    @Test
    fun `todas las palabras deben aparecer en cualquier orden`() {
        assertEquals(listOf("Tarta de Santiago"), filter("santiago tarta"))
        assertEquals(emptyList<String>(), filter("tarta crema"))
    }

    @Test
    fun `sin búsqueda devuelve todo`() {
        assertEquals(names, filter(null))
        assertEquals(names, filter(""))
        assertEquals(names, filter("   "))
    }

    @Test
    fun `sin coincidencias devuelve vacío`() {
        assertEquals(emptyList<String>(), filter("sushi"))
    }

    @Test
    fun `busca en varios campos y tolera nulos`() {
        data class R(val name: String?, val description: String?)
        val items = listOf(R("Sopa", "de pescado"), R(null, "Gazpacho andaluz"), R("Flan", null))
        val result = TextFilter.filter(items, "andaluz") { listOf(it.name, it.description) }
        assertEquals(listOf(items[1]), result)
        assertEquals(listOf(items[0]), TextFilter.filter(items, "sopa pescado") { listOf(it.name, it.description) })
    }

    @Test
    fun `los caracteres de expresión regular se tratan como texto`() {
        assertEquals(emptyList<String>(), filter(".*"))
        assertEquals(emptyList<String>(), filter("(["))
    }

    @Test
    fun `un campo gigante no se procesa entero`() {
        val huge = "x".repeat(1_000_000) + "tesoro"
        assertEquals(emptyList<String>(), TextFilter.filter(listOf(huge), "tesoro") { listOf(it) })
    }

    @Test
    fun `normalize colapsa espacios`() {
        assertEquals("a b", TextFilter.normalize("  A \t\n B "))
    }
}
