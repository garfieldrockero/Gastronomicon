package io.github.garfieldrockero.gastronomicon.core.widget

import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingList
import kotlinx.serialization.json.JsonObject

/**
 * Datos de ejemplo para las pruebas del widget y de su caché. Los ids son UUID
 * válidos porque el núcleo no acepta otra cosa.
 */
internal object WidgetFixtures {

    const val LIST_ID = "11111111-1111-4111-8111-111111111111"
    const val OTHER_LIST_ID = "99999999-9999-4999-8999-999999999999"

    const val ITEM_1 = "22222222-2222-4222-8222-222222222222"
    const val ITEM_2 = "33333333-3333-4333-8333-333333333333"
    const val ITEM_3 = "44444444-4444-4444-8444-444444444444"
    const val ITEM_4 = "55555555-5555-4555-8555-555555555555"
    const val ITEM_5 = "66666666-6666-4666-8666-666666666666"
    const val ITEM_6 = "77777777-7777-4777-8777-777777777777"
    const val ITEM_7 = "88888888-8888-4888-8888-888888888888"
    const val ITEM_8 = "99999999-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val ITEM_9 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val ITEM_10 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    const val LABEL_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

    /** Marca de tiempo fija: las pruebas no dependen del reloj de la máquina. */
    const val NOW = 1_700_000_000_000L

    fun item(
        id: String,
        note: String = "Leche",
        checked: Boolean = false,
        position: Int = 0,
        labelName: String = "",
        quantity: Double = 0.0,
        unitSingular: String = "",
        unitPlural: String = "",
        foodSingular: String = "",
        foodPlural: String = "",
        rawJson: JsonObject? = null,
    ): ShoppingItem = ShoppingItem(
        id = id,
        shoppingListId = LIST_ID,
        note = note,
        quantity = quantity,
        unitSingular = unitSingular,
        unitPlural = unitPlural,
        foodSingular = foodSingular,
        foodPlural = foodPlural,
        labelId = if (labelName.isEmpty()) null else LABEL_ID,
        labelName = labelName,
        checked = checked,
        position = position,
        rawJson = rawJson,
    )

    fun list(
        name: String = "Compra",
        items: List<ShoppingItem> = listOf(item(ITEM_1)),
        id: String = LIST_ID,
    ): ShoppingList = ShoppingList(id = id, name = name, items = items)

    /** Id de ejemplo por posición (1..10), para las pruebas de filas. */
    fun itemIdAt(index: Int): String = when (index) {
        1 -> ITEM_1
        2 -> ITEM_2
        3 -> ITEM_3
        4 -> ITEM_4
        5 -> ITEM_5
        6 -> ITEM_6
        7 -> ITEM_7
        8 -> ITEM_8
        9 -> ITEM_9
        else -> ITEM_10
    }

    /** Instantánea válida con filas por defecto, para la caché y la vista. */
    fun snapshot(
        listId: String = LIST_ID,
        listName: String = "Compra",
        pendingCount: Int = 3,
        checkedCount: Int = 1,
        updatedAtMillis: Long = NOW,
        rows: List<ShoppingWidgetRow> = listOf(
            ShoppingWidgetRow(ITEM_1, "Leche"),
            ShoppingWidgetRow(ITEM_2, "Pan"),
            ShoppingWidgetRow(ITEM_3, "Huevos"),
        ),
    ): ShoppingWidgetSnapshot = ShoppingWidgetSnapshot(
        listId = listId,
        listName = listName,
        pendingCount = pendingCount,
        checkedCount = checkedCount,
        updatedAtMillis = updatedAtMillis,
        rows = rows,
    )
}
