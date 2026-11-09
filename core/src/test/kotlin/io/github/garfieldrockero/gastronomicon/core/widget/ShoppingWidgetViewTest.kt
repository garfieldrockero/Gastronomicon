package io.github.garfieldrockero.gastronomicon.core.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la vista del widget y del cálculo de filas: la interfaz solo copia
 * lo que sale de aquí en `RemoteViews`.
 */
class ShoppingWidgetViewTest {

    @Test
    fun `sin copia se pinta el estado vacio`() {
        val view = ShoppingWidgetView.from(snapshot = null, status = ShoppingWidgetStatus.NoCache)

        assertEquals("Lista de la compra", view.listName)
        assertTrue(view.rows.isEmpty())
        assertEquals(0, view.pendingCount)
        assertEquals(0, view.checkedCount)
        assertEquals(0, view.hiddenCount)
        assertNull(view.moreText)
        assertNull(view.updatedAtMillis)
        assertEquals("Abre Gastronomicón y elige tu lista", view.statusText)
        assertTrue(view.hasStatus)
    }

    @Test
    fun `respeta el maximo de filas y resume el resto`() {
        val snapshot = WidgetFixtures.snapshot().copySnapshot(
            pendingCount = 8,
            rows = (1..8).map { ShoppingWidgetRow(itemId = WidgetFixtures.itemIdAt(it), text = "Producto $it") },
        )

        val view = ShoppingWidgetView.from(snapshot, maxRows = 3)

        assertEquals(3, view.rows.size)
        assertEquals(8, view.pendingCount)
        assertEquals(5, view.hiddenCount)
        assertEquals("y 5 más", view.moreText)
        assertEquals("Producto 3", view.rows.last().text)
    }

    @Test
    fun `si caben todas no hay y N mas`() {
        val view = ShoppingWidgetView.from(WidgetFixtures.snapshot(), maxRows = 8)

        assertEquals(3, view.rows.size)
        assertEquals(0, view.hiddenCount)
        assertNull(view.moreText)
    }

    @Test
    fun `el maximo de filas se acota aunque llegue un numero absurdo`() {
        val many = WidgetFixtures.snapshot().copySnapshot(
            pendingCount = 20,
            rows = (1..8).map { ShoppingWidgetRow(itemId = WidgetFixtures.itemIdAt(it), text = "Producto $it") },
        )

        assertEquals(ShoppingWidgetSnapshot.MAX_ROWS, ShoppingWidgetView.from(many, maxRows = 999).rows.size)
        assertEquals(0, ShoppingWidgetView.from(many, maxRows = -4).rows.size)
    }

    @Test
    fun `los contadores son de la lista entera`() {
        val snapshot = WidgetFixtures.snapshot().copySnapshot(pendingCount = 12, checkedCount = 4, rows = emptyList())

        val view = ShoppingWidgetView.from(snapshot, maxRows = 8)

        assertEquals(12, view.pendingCount)
        assertEquals(4, view.checkedCount)
        assertEquals(12, view.hiddenCount)
        assertEquals("y 12 más", view.moreText)
        assertFalse(view.isEmpty)
    }

    @Test
    fun `una lista sin nada se marca como vacia`() {
        val view = ShoppingWidgetView.from(
            WidgetFixtures.snapshot().copySnapshot(pendingCount = 0, checkedCount = 0, rows = emptyList()),
        )

        assertTrue(view.isEmpty)
        assertFalse(view.hasRows)
    }

    @Test
    fun `el estado se pinta tal cual`() {
        val view = ShoppingWidgetView.from(WidgetFixtures.snapshot(), status = ShoppingWidgetStatus.Saving)

        assertEquals("Guardando…", view.statusText)
    }

    @Test
    fun `el toString no lleva el nombre ni los textos`() {
        val snapshot = WidgetFixtures.snapshot().copySnapshot(
            listName = "Compra secreta",
            rows = listOf(ShoppingWidgetRow(WidgetFixtures.ITEM_1, "Medicamento")),
        )

        val texto = ShoppingWidgetView.from(snapshot).toString()

        assertFalse(texto.contains("Compra secreta"))
        assertFalse(texto.contains("Medicamento"))
    }

    // --------------------------------------------------------------- filas

    @Test
    fun `sin alto de launcher se usan las filas por defecto`() {
        assertEquals(ShoppingWidgetLayout.DEFAULT_ROWS, ShoppingWidgetLayout.rowsFor(0, 1f))
        assertEquals(ShoppingWidgetLayout.DEFAULT_ROWS, ShoppingWidgetLayout.rowsFor(-50, 1f))
    }

    @Test
    fun `mas alto da mas filas hasta el maximo`() {
        assertEquals(ShoppingWidgetLayout.MAX_ROWS, ShoppingWidgetLayout.rowsFor(1_000, 1f))
        assertEquals(ShoppingWidgetLayout.MAX_ROWS, ShoppingWidgetLayout.rowsFor(10_000, 1f))
    }

    @Test
    fun `un widget muy bajo sigue enseñando una fila`() {
        assertEquals(ShoppingWidgetLayout.MIN_ROWS, ShoppingWidgetLayout.rowsFor(80, 1f))
        assertEquals(ShoppingWidgetLayout.MIN_ROWS, ShoppingWidgetLayout.rowsFor(72, 1f))
    }

    @Test
    fun `el texto grande reduce las filas`() {
        val normal = ShoppingWidgetLayout.rowsFor(500, 1f)
        val doble = ShoppingWidgetLayout.rowsFor(500, 2f)

        assertTrue(doble < normal)
        assertTrue(doble >= ShoppingWidgetLayout.MIN_ROWS)
    }

    @Test
    fun `un fontScale imposible se trata como el normal`() {
        val normal = ShoppingWidgetLayout.rowsFor(500, 1f)

        assertEquals(normal, ShoppingWidgetLayout.rowsFor(500, 0f))
        assertEquals(normal, ShoppingWidgetLayout.rowsFor(500, -2f))
        assertEquals(normal, ShoppingWidgetLayout.rowsFor(500, Float.NaN))
        assertEquals(normal, ShoppingWidgetLayout.rowsFor(500, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `el numero de filas nunca sale de rango`() {
        val alturas = listOf(-1, 0, 1, 50, 72, 73, 200, 500, 2_000, Int.MAX_VALUE)
        val escalas = listOf(-1f, 0f, 0.1f, 1f, 1.5f, 2f, 3.5f, 10f, Float.NaN, Float.NEGATIVE_INFINITY)

        for (altura in alturas) {
            for (escala in escalas) {
                val filas = ShoppingWidgetLayout.rowsFor(altura, escala)
                assertTrue("altura=$altura escala=$escala dio $filas", filas in 1..ShoppingWidgetLayout.MAX_ROWS)
            }
        }
    }
}

/*
 * `ShoppingWidgetSnapshot` no es un data class (su constructor es interno para
 * que solo lo construyan [ShoppingWidgetSnapshot.from] y el códec). Para las
 * pruebas de la vista se necesita variar campos sin pasar por la lista, así que
 * se copia a mano aquí.
 */
private fun ShoppingWidgetSnapshot.copySnapshot(
    listId: String = this.listId,
    listName: String = this.listName,
    pendingCount: Int = this.pendingCount,
    checkedCount: Int = this.checkedCount,
    updatedAtMillis: Long = this.updatedAtMillis,
    rows: List<ShoppingWidgetRow> = this.rows,
): ShoppingWidgetSnapshot = ShoppingWidgetSnapshot(
    listId = listId,
    listName = listName,
    pendingCount = pendingCount,
    checkedCount = checkedCount,
    updatedAtMillis = updatedAtMillis,
    rows = rows,
)
