package io.github.garfieldrockero.gastronomicon.ui.shopping

import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem

/**
 * Datos de ejemplo para las previews. Inventados: sin servidor, token ni nada
 * que salga de uno real. Los ids son UUID de mentira con la forma que exige el núcleo.
 */
internal object ShoppingPreviewData {

    private const val LIST_ID = "00000000-0000-4000-8000-000000000001"

    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n + 100)

    private fun item(
        n: Int,
        note: String = "",
        quantity: Double = 0.0,
        unit: String = "",
        unitPlural: String = unit,
        food: String = "",
        foodPlural: String = food,
        label: String = "",
        checked: Boolean = false,
    ) = ShoppingItem(
        id = id(n),
        shoppingListId = LIST_ID,
        note = note,
        quantity = quantity,
        unitSingular = unit,
        unitPlural = unitPlural,
        foodSingular = food,
        foodPlural = foodPlural,
        labelId = null,
        labelName = label,
        checked = checked,
        position = n,
    )

    val withSections = listOf(
        item(1, quantity = 2.0, unit = "kg", food = "patata", foodPlural = "patatas", label = "Verdura"),
        item(2, quantity = 1.0, food = "cebolla", foodPlural = "cebollas", label = "Verdura", checked = true),
        item(3, quantity = 6.0, food = "tomate", foodPlural = "tomates", label = "Verdura"),
        item(4, quantity = 1.0, unit = "l", food = "leche", label = "Lácteos"),
        item(5, note = "Yogures naturales, los de siempre", label = "Lácteos", checked = true),
        item(6, note = "Papel de cocina"),
    )

    val withoutSections = listOf(
        item(1, note = "Pan", quantity = 1.0),
        item(2, quantity = 500.0, unit = "g", food = "arroz", checked = true),
        item(3, note = "Huevos camperos"),
        item(4, note = "Bolsas de basura grandes, las de 100 litros que se acaban siempre en mitad de la semana"),
    )

    val allChecked = withoutSections.map { it.copy(checked = true) }

    /** Un elemento que existe para abrir el diálogo de edición en la preview. */
    val editable: ShoppingItem = withSections[5]
}
