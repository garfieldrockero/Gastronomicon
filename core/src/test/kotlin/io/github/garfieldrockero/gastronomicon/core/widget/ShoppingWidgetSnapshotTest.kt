package io.github.garfieldrockero.gastronomicon.core.widget

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la instantánea que pinta el widget: solo pendientes, orden de la
 * lista, tope de filas, contadores y saneado. Aquí se comprueba que la
 * interfaz no tiene que calcular nada.
 */
class ShoppingWidgetSnapshotTest {

    @Test
    fun `solo las pendientes en el orden de la lista y con el texto de la vista`() {
        val list = WidgetFixtures.list(
            items = listOf(
                WidgetFixtures.item(WidgetFixtures.ITEM_1, note = "Peras", checked = true, labelName = "Frutas"),
                WidgetFixtures.item(WidgetFixtures.ITEM_2, note = "Manzanas", labelName = "Frutas", position = 1),
                WidgetFixtures.item(WidgetFixtures.ITEM_3, note = "Leche", labelName = "Lácteos"),
                WidgetFixtures.item(WidgetFixtures.ITEM_4, note = "Detergente"),
            ),
        )

        val snapshot = ShoppingWidgetSnapshot.from(list, WidgetFixtures.NOW)!!
        assertNotNull(snapshot)

        assertEquals(WidgetFixtures.LIST_ID, snapshot.listId)
        assertEquals("Compra", snapshot.listName)
        assertEquals(3, snapshot.pendingCount)
        assertEquals(1, snapshot.checkedCount)
        assertEquals(listOf("Manzanas", "Leche", "Detergente"), snapshot.rows.map { it.text })
        assertEquals(
            listOf(WidgetFixtures.ITEM_2, WidgetFixtures.ITEM_3, WidgetFixtures.ITEM_4),
            snapshot.rows.map { it.itemId },
        )
        assertEquals(WidgetFixtures.NOW, snapshot.updatedAtMillis)
    }

    @Test
    fun `no guarda mas de ocho filas pero cuenta todas`() {
        val items = (1..10).map { index ->
            WidgetFixtures.item(
                id = itemId(index),
                note = "Producto $index",
                position = index,
            )
        }

        val snapshot = ShoppingWidgetSnapshot.from(WidgetFixtures.list(items = items), WidgetFixtures.NOW)!!

        assertEquals(ShoppingWidgetSnapshot.MAX_ROWS, snapshot.rows.size)
        assertEquals(10, snapshot.pendingCount)
        assertEquals(2, snapshot.hiddenCount)
        assertEquals("Producto 1", snapshot.rows.first().text)
        assertEquals("Producto 8", snapshot.rows.last().text)
    }

    @Test
    fun `los ids repetidos no duplican filas ni contadores`() {
        val list = WidgetFixtures.list(
            items = listOf(
                WidgetFixtures.item(WidgetFixtures.ITEM_1, note = "Leche"),
                WidgetFixtures.item(WidgetFixtures.ITEM_1, note = "Leche repetida"),
                WidgetFixtures.item(WidgetFixtures.ITEM_2, note = "Pan"),
            ),
        )

        val snapshot = ShoppingWidgetSnapshot.from(list, WidgetFixtures.NOW)!!

        assertEquals(2, snapshot.pendingCount)
        assertEquals(2, snapshot.rows.size)
        assertEquals(1, snapshot.rows.count { it.itemId == WidgetFixtures.ITEM_1 })
    }

    @Test
    fun `un id de elemento invalido no llega a una fila`() {
        val list = WidgetFixtures.list(
            items = listOf(
                WidgetFixtures.item(id = "no-es-un-uuid", note = "Rara"),
                WidgetFixtures.item(WidgetFixtures.ITEM_1, note = "Leche"),
            ),
        )

        val snapshot = ShoppingWidgetSnapshot.from(list, WidgetFixtures.NOW)!!

        assertEquals(listOf("Leche"), snapshot.rows.map { it.text })
    }

    @Test
    fun `sin nombre de lista se usa el de por defecto`() {
        val snapshot = ShoppingWidgetSnapshot.from(
            WidgetFixtures.list(name = "", items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_1))),
            WidgetFixtures.NOW,
        )!!

        assertEquals("Lista de la compra", snapshot.listName)
    }

    @Test
    fun `el nombre se sanea y no lleva controles`() {
        val nombre = "Compra\u0000\u0007 rara"
        val snapshot = ShoppingWidgetSnapshot.from(
            WidgetFixtures.list(name = nombre, items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_1))),
            WidgetFixtures.NOW,
        )!!

        assertEquals("Compra rara", snapshot.listName)
    }

    @Test
    fun `un texto largo se recorta al tope de la fila`() {
        val largo = "a".repeat(ShoppingWidgetSnapshot.MAX_ROW_TEXT + 60)
        val snapshot = ShoppingWidgetSnapshot.from(
            WidgetFixtures.list(items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_1, note = largo))),
            WidgetFixtures.NOW,
        )!!

        assertEquals(ShoppingWidgetSnapshot.MAX_ROW_TEXT, snapshot.rows.single().text.length)
    }

    @Test
    fun `los saltos de linea quedan en una sola linea`() {
        val snapshot = ShoppingWidgetSnapshot.from(
            WidgetFixtures.list(
                items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_1, note = "Leche\npara el café")),
            ),
            WidgetFixtures.NOW,
        )!!

        assertEquals("Leche para el café", snapshot.rows.single().text)
    }

    @Test
    fun `una lista vacia no tiene filas ni contadores`() {
        val snapshot = ShoppingWidgetSnapshot.from(WidgetFixtures.list(items = emptyList()), WidgetFixtures.NOW)!!

        assertTrue(snapshot.isEmpty)
        assertFalse(snapshot.hasRows)
        assertEquals(0, snapshot.pendingCount)
        assertEquals(0, snapshot.checkedCount)
        assertEquals(0, snapshot.hiddenCount)
    }

    @Test
    fun `con todo marcado no hay filas pendientes`() {
        val list = WidgetFixtures.list(
            items = listOf(
                WidgetFixtures.item(WidgetFixtures.ITEM_1, note = "Leche", checked = true),
                WidgetFixtures.item(WidgetFixtures.ITEM_2, note = "Pan", checked = true),
            ),
        )

        val snapshot = ShoppingWidgetSnapshot.from(list, WidgetFixtures.NOW)!!

        assertFalse(snapshot.hasRows)
        assertEquals(0, snapshot.pendingCount)
        assertEquals(2, snapshot.checkedCount)
        assertFalse(snapshot.isEmpty)
    }

    @Test
    fun `un id de lista invalido o una marca de tiempo nula no dan instantanea`() {
        assertNull(ShoppingWidgetSnapshot.from(WidgetFixtures.list(id = "no-es-uuid"), WidgetFixtures.NOW))
        assertNull(ShoppingWidgetSnapshot.from(WidgetFixtures.list(), 0L))
        assertNull(ShoppingWidgetSnapshot.from(WidgetFixtures.list(), -1L))
    }

    // ----------------------------------------------------------- desde estado

    private fun state(
        loadedOnce: Boolean = true,
        phase: ShoppingPhase = ShoppingPhase.Idle,
        problem: ShoppingProblem? = null,
        busyItemIds: Set<String> = emptySet(),
        adding: Boolean = false,
        clearingChecked: Boolean = false,
        listId: String? = WidgetFixtures.LIST_ID,
    ): ShoppingListState = ShoppingListState(
        listId = listId,
        listName = "Compra",
        items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_1)),
        phase = phase,
        problem = problem,
        loadedOnce = loadedOnce,
        busyItemIds = busyItemIds,
        adding = adding,
        clearingChecked = clearingChecked,
    )

    @Test
    fun `desde un estado final y bueno hay instantanea`() {
        val snapshot = ShoppingWidgetSnapshot.from(state(), WidgetFixtures.NOW)

        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.pendingCount)
    }

    @Test
    fun `sin haber cargado nunca no hay instantanea`() {
        assertNull(ShoppingWidgetSnapshot.from(state(loadedOnce = false), WidgetFixtures.NOW))
    }

    @Test
    fun `con una carga en marcha no hay instantanea`() {
        assertNull(ShoppingWidgetSnapshot.from(state(phase = ShoppingPhase.Refreshing), WidgetFixtures.NOW))
        assertNull(ShoppingWidgetSnapshot.from(state(phase = ShoppingPhase.LoadingFirst), WidgetFixtures.NOW))
    }

    @Test
    fun `con un fallo a la vista no se pisa la copia buena`() {
        val problem = ShoppingProblem.Failed(ConnectionProblem.NoNetwork)
        assertNull(ShoppingWidgetSnapshot.from(state(problem = problem), WidgetFixtures.NOW))
    }

    @Test
    fun `con una escritura en vuelo no hay instantanea`() {
        assertNull(
            ShoppingWidgetSnapshot.from(
                state(busyItemIds = setOf(WidgetFixtures.ITEM_1)),
                WidgetFixtures.NOW,
            ),
        )
        assertNull(ShoppingWidgetSnapshot.from(state(adding = true), WidgetFixtures.NOW))
        assertNull(ShoppingWidgetSnapshot.from(state(clearingChecked = true), WidgetFixtures.NOW))
    }

    @Test
    fun `sin lista abierta no hay instantanea`() {
        assertNull(ShoppingWidgetSnapshot.from(state(listId = null), WidgetFixtures.NOW))
    }

    @Test
    fun `el toString no lleva el nombre ni los textos`() {
        val snapshot = ShoppingWidgetSnapshot.from(
            WidgetFixtures.list(
                name = "Compra secreta",
                items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_1, note = "Medicamento")),
            ),
            WidgetFixtures.NOW,
        )!!

        val texto = snapshot.toString()

        assertFalse(texto.contains("Compra secreta"))
        assertFalse(texto.contains("Medicamento"))
        assertTrue(texto.contains("pending=1"))
    }

    private fun itemId(index: Int): String = when (index) {
        1 -> WidgetFixtures.ITEM_1
        2 -> WidgetFixtures.ITEM_2
        3 -> WidgetFixtures.ITEM_3
        4 -> WidgetFixtures.ITEM_4
        5 -> WidgetFixtures.ITEM_5
        6 -> WidgetFixtures.ITEM_6
        7 -> WidgetFixtures.ITEM_7
        8 -> WidgetFixtures.ITEM_8
        9 -> WidgetFixtures.ITEM_9
        else -> WidgetFixtures.ITEM_10
    }
}
