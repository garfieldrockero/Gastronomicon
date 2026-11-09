package io.github.garfieldrockero.gastronomicon.core.shopping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la vista de la compra: agrupación, orden, contadores y composición
 * del texto. Aquí se comprueba que la interfaz no tiene que calcular nada y que
 * una cantidad absurda no se pinta como un número.
 */
class ShoppingListViewTest {

    private fun item(
        id: String,
        note: String = "",
        labelName: String = "",
        checked: Boolean = false,
        position: Int = 0,
        quantity: Double = 0.0,
        unitSingular: String = "",
        unitPlural: String = "",
        foodSingular: String = "",
        foodPlural: String = "",
    ): ShoppingItem = ShoppingItem(
        id = id,
        shoppingListId = "11111111-1111-4111-8111-111111111111",
        note = note,
        quantity = quantity,
        unitSingular = unitSingular,
        unitPlural = unitPlural,
        foodSingular = foodSingular,
        foodPlural = foodPlural,
        labelId = if (labelName.isEmpty()) null else "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        labelName = labelName,
        checked = checked,
        position = position,
    )

    @Test
    fun `agrupa por etiqueta y deja los pendientes antes que los marcados`() {
        val view = ShoppingListView.from(
            "Compra",
            listOf(
                item("1", note = "Peras", labelName = "Frutas", checked = true, position = 0),
                item("2", note = "Manzanas", labelName = "Frutas", checked = false, position = 5),
                item("3", note = "Detergente", position = 0),
            ),
        )
        assertEquals(listOf("Frutas", "Sin sección"), view.sections.map { it.title })
        assertEquals(listOf("Manzanas", "Peras"), view.sections[0].lines.map { it.text })
        assertEquals(listOf("Detergente"), view.sections[1].lines.map { it.text })
        assertEquals(2, view.pendingCount)
        assertEquals(1, view.checkedCount)
        assertTrue(view.hasChecked)
        assertFalse(view.allChecked)
    }

    @Test
    fun `sin etiquetas hay un solo grupo sin titulo`() {
        val view = ShoppingListView.from("", listOf(item("1", note = "Leche")))
        assertEquals("Lista de la compra", view.listName)
        assertEquals(1, view.sections.size)
        assertEquals("", view.sections.single().title)
    }

    @Test
    fun `compone cantidad unidad y alimento con el plural`() {
        val view = ShoppingListView.from(
            "Compra",
            listOf(
                item(
                    "1",
                    quantity = 2.0,
                    unitSingular = "kg",
                    unitPlural = "kg",
                    foodSingular = "patata",
                    foodPlural = "patatas",
                    position = 0,
                ),
                item(
                    "2",
                    quantity = 1.0,
                    unitSingular = "kg",
                    unitPlural = "kg",
                    foodSingular = "patata",
                    foodPlural = "patatas",
                    position = 1,
                ),
            ),
        )
        assertEquals(listOf("2 kg patatas", "1 kg patata"), view.sections.single().lines.map { it.text })
    }

    @Test
    fun `la nota va entre parentesis si hay alimento y sola si no`() {
        val conAlimento = ShoppingListView.from(
            "Compra",
            listOf(
                item(
                    "1",
                    note = "para la cena",
                    quantity = 2.0,
                    unitSingular = "kg",
                    unitPlural = "kg",
                    foodSingular = "patata",
                    foodPlural = "patatas",
                ),
            ),
        )
        assertEquals("2 kg patatas (para la cena)", conAlimento.sections.single().lines.single().text)

        val sinAlimento = ShoppingListView.from("Compra", listOf(item("1", note = "Servilletas")))
        assertEquals("Servilletas", sinAlimento.sections.single().lines.single().text)
    }

    @Test
    fun `una cantidad absurda no se pinta`() {
        val grande = ShoppingListView.from(
            "Compra",
            listOf(item("1", note = "Leche", quantity = 10_000_000.0)),
        )
        assertEquals("Leche", grande.sections.single().lines.single().text)

        val noFinita = ShoppingListView.from(
            "Compra",
            listOf(item("1", note = "Leche", quantity = Double.NaN)),
        )
        assertEquals("Leche", noFinita.sections.single().lines.single().text)
    }

    @Test
    fun `una linea sin texto ni cantidad no se pinta`() {
        val view = ShoppingListView.from("Compra", listOf(item("1")))
        assertTrue(view.isEmpty)
        assertTrue(view.sections.isEmpty())
    }

    @Test
    fun `el orden es estable con posiciones repetidas`() {
        val view = ShoppingListView.from(
            "Compra",
            listOf(
                item("b", note = "Zanahoria", position = 0),
                item("a", note = "Acelga", position = 0),
            ),
        )
        assertEquals(listOf("Acelga", "Zanahoria"), view.sections.single().lines.map { it.text })
    }

    @Test
    fun `todos marcados se refleja en los contadores`() {
        val view = ShoppingListView.from(
            "Compra",
            listOf(item("1", note = "Leche", checked = true), item("2", note = "Pan", checked = true)),
        )
        assertEquals(0, view.pendingCount)
        assertEquals(2, view.checkedCount)
        assertTrue(view.allChecked)
        assertEquals(2, view.totalCount)
    }

    @Test
    fun `el texto de la linea no arrastra el contenido de la vista`() {
        val view = ShoppingListView.from("Compra", listOf(item("1", note = "Leche")))
        assertFalse(view.toString(), view.toString().contains("Leche"))
    }
}
