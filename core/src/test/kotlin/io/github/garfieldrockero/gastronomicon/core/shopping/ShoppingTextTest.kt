package io.github.garfieldrockero.gastronomicon.core.shopping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de los límites y del saneado de los textos de la compra. Son de
 * frontera: lo que se escribe aquí es lo que luego sale hacia el servidor en una
 * nota, y lo que se lee del servidor pasa por el mismo saneador.
 */
class ShoppingTextTest {

    @Test
    fun `quita bidireccionales y controles pero deja el html como texto`() {
        val input = ShoppingText.item("Le\u202Eche\u0000 <b>entera</b>")
        assertNull(input.problem)
        assertEquals("Leche <b>entera</b>", input.text)
    }

    @Test
    fun `un texto vacio o solo espacios se rechaza`() {
        assertEquals(ShoppingInputProblem.EmptyText, ShoppingText.item("   ").problem)
        assertEquals(ShoppingInputProblem.EmptyText, ShoppingText.item(null).problem)
    }

    @Test
    fun `el tope se aplica sin recortar en silencio`() {
        val justo = "a".repeat(ShoppingText.MAX_ITEM_TEXT)
        assertNull(ShoppingText.item(justo).problem)
        assertEquals(justo, ShoppingText.item(justo).text)

        val largo = "a".repeat(ShoppingText.MAX_ITEM_TEXT + 1)
        assertEquals(ShoppingInputProblem.TextTooLong, ShoppingText.item(largo).problem)
        assertEquals(ShoppingText.MAX_ITEM_TEXT, ShoppingText.item(largo).text.length)
    }

    @Test
    fun `el nombre de lista se sanea y puede quedar vacio`() {
        assertEquals("Compra semanal", ShoppingText.listName("Compra\u202E semanal"))
        assertEquals("", ShoppingText.listName(null))
        assertTrue(ShoppingText.DEFAULT_LIST_NAME.isNotEmpty())
        assertTrue(ShoppingText.DEFAULT_NO_SECTION_TITLE.isNotEmpty())
    }

    @Test
    fun `el nombre de lista para crear se valida sin recortar en silencio`() {
        assertEquals(ShoppingInputProblem.EmptyText, ShoppingText.listNameInput("   ").problem)
        assertEquals(ShoppingInputProblem.EmptyText, ShoppingText.listNameInput(null).problem)

        val justo = "a".repeat(ShoppingText.MAX_LIST_NAME)
        assertNull(ShoppingText.listNameInput(justo).problem)
        assertEquals(justo, ShoppingText.listNameInput(justo).text)

        val largo = "a".repeat(ShoppingText.MAX_LIST_NAME + 1)
        assertEquals(ShoppingInputProblem.TextTooLong, ShoppingText.listNameInput(largo).problem)
        // Para el aviso se devuelve recortado; nunca se envía con problema.
        assertEquals(ShoppingText.MAX_LIST_NAME, ShoppingText.listNameInput(largo).text.length)
        assertEquals("Compra semanal", ShoppingText.listNameInput("Compra\u202E semanal").text)
    }
}
