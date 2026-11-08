package io.github.garfieldrockero.gastronomicon.core.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTest {
    private fun page(page: Int, perPage: Int, total: Long, count: Int = 1) =
        Page.of(List(count) { it }, page, perPage, total)

    @Test
    fun `calcula las páginas por redondeo hacia arriba`() {
        assertEquals(0, page(1, 20, 0, 0).totalPages)
        assertEquals(1, page(1, 20, 1).totalPages)
        assertEquals(1, page(1, 20, 20).totalPages)
        assertEquals(2, page(1, 20, 21).totalPages)
        assertEquals(5, page(1, 20, 100).totalPages)
    }

    @Test
    fun `hay siguiente mientras no se llegue a la última`() {
        assertTrue(page(1, 20, 45).hasNext)
        assertEquals(2, page(1, 20, 45).nextPage)
        assertTrue(page(2, 20, 45).hasNext)
        assertFalse(page(3, 20, 45).hasNext)
        assertNull(page(3, 20, 45).nextPage)
    }

    @Test
    fun `sin resultados no hay siguiente ni anterior`() {
        val empty = page(1, 20, 0, 0)
        assertFalse(empty.hasNext)
        assertFalse(empty.hasPrevious)
    }

    @Test
    fun `una página vacía no tiene siguiente aunque el total diga que sí`() {
        assertFalse(page(1, 20, 1000, 0).hasNext)
    }

    @Test
    fun `la anterior existe desde la página dos`() {
        assertFalse(page(1, 20, 45).hasPrevious)
        assertEquals(1, page(2, 20, 45).previousPage)
        assertNull(page(1, 20, 45).previousPage)
    }

    @Test
    fun `cifras imposibles del servidor se acotan`() {
        val odd = Page.of(listOf(1), 0, 0, -7)
        assertEquals(1, odd.page)
        assertEquals(1, odd.perPage)
        assertEquals(0L, odd.total)
        assertEquals(0, odd.totalPages)
    }

    @Test
    fun `un total enorme no desborda`() {
        val huge = Page.of(listOf(1), 1, 1, Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, huge.totalPages)
        assertTrue(huge.hasNext)
    }

    @Test
    fun `la consulta siguiente cambia solo la página`() {
        val current = RecipeListQuery(2, 20, "arroz", "name", SortDirection.ASCENDING)
        val next = page(2, 20, 100).nextQuery(current)!!
        assertEquals(3, next.page)
        assertEquals("arroz", next.search)
        assertNull(page(5, 20, 100).nextQuery(current))
    }

    @Test
    fun `la página guarda una copia de la lista`() {
        val source = mutableListOf(1, 2)
        val p = Page.of(source, 1, 20, 2)
        source.add(3)
        assertEquals(listOf(1, 2), p.items)
    }
}
