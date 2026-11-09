package io.github.garfieldrockero.gastronomicon.core.widget

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingIds
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingList
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListView
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingText

/**
 * Una fila pendiente del widget: el id (que viaja en el data URI del
 * `PendingIntent`) y el texto ya compuesto y saneado.
 *
 * El texto es contenido del servidor y solo se pinta en el widget: el
 * `toString` no lo repite para que una fila no acabe en un log con la compra
 * de la persona.
 */
data class ShoppingWidgetRow(val itemId: String, val text: String) {
    override fun toString(): String = "ShoppingWidgetRow(itemId=$itemId, chars=${text.length})"
}

/**
 * Instantánea de la lista de la compra que pinta el widget.
 *
 * Es lo único que se guarda en la caché cifrada (`ShoppingListCache` en
 * `core/offline`) y lo único que llega a `RemoteViews`. Por eso **no** lleva
 * `rawJson`, ni URL, ni usuario, ni token: solo la lista abierta, los
 * contadores y hasta [MAX_ROWS] filas pendientes.
 *
 * Se construye siempre con [from]; el constructor es interno para que la
 * lectura de la caché (que revalida todo) sea el único otro camino.
 *
 * Reglas:
 * - Solo filas **pendientes**, en el orden de [ShoppingListView]: primero las
 *   etiquetas por nombre, los sin sección al final; dentro, por posición y
 *   texto. Nunca se reordena aquí.
 * - Como mucho [MAX_ROWS] filas. El resto de pendientes se cuenta en
 *   [pendingCount] y la vista calcula el «y N más».
 * - El texto de cada fila va saneado una segunda vez con
 *   [PlainTextSanitizer], sin saltos de línea y acotado a [MAX_ROW_TEXT].
 * - Los ids repetidos no duplican filas ni contadores.
 * - Los marcados no se guardan: el widget solo enseña lo que queda por
 *   comprar.
 */
class ShoppingWidgetSnapshot internal constructor(
    val listId: String,
    val listName: String,
    val pendingCount: Int,
    val checkedCount: Int,
    val updatedAtMillis: Long,
    val rows: List<ShoppingWidgetRow>,
) {
    val hasRows: Boolean get() = rows.isNotEmpty()

    /** La lista existe pero no tiene nada: ni pendiente ni marcado. */
    val isEmpty: Boolean get() = pendingCount == 0 && checkedCount == 0

    /** Pendientes que no caben en [rows]. La vista compone «y N más». */
    val hiddenCount: Int get() = (pendingCount - rows.size).coerceAtLeast(0)

    // Sin el nombre ni los textos: son datos del servidor y una instantánea
    // puede acabar en un log o en un informe.
    override fun toString(): String =
        "ShoppingWidgetSnapshot(rows=${rows.size}, pending=$pendingCount, checked=$checkedCount, " +
            "updatedAt=$updatedAtMillis, hasName=${listName.isNotEmpty()})"

    companion object {

        /** Filas que caben en la caché y en el peor caso del widget. */
        const val MAX_ROWS = 8

        /**
         * Tope del texto de una fila. La fila del widget es de una o dos líneas
         * y la interfaz elide; el texto completo está en la app. Se elige un
         * número de pantalla, no un límite de la API (Mealie no lo documenta).
         */
        const val MAX_ROW_TEXT = 240

        /**
         * Instantánea de una lista cargada y en reposo, o null si el estado no
         * es final y bueno: sin lista abierta, sin haber cargado nunca, con una
         * carga o escritura en vuelo o con el último fallo todavía a la vista.
         * Así un refresco fallido no pisa la última copia buena.
         */
        fun from(state: ShoppingListState, nowMillis: Long = System.currentTimeMillis()): ShoppingWidgetSnapshot? {
            if (!state.loadedOnce) return null
            if (state.phase != ShoppingPhase.Idle) return null
            if (state.problem != null) return null
            if (state.isBusy) return null
            val listId = state.listId ?: return null
            val list = ShoppingList(id = listId, name = state.listName, items = state.items)
            return from(list, nowMillis)
        }

        /**
         * Instantánea de una lista recién leída del servidor (o de una copia
         * con un elemento ya marcado), o null si el id no es un UUID o la marca
         * de tiempo no es válida.
         */
        fun from(list: ShoppingList, nowMillis: Long = System.currentTimeMillis()): ShoppingWidgetSnapshot? {
            val listId = ShoppingIds.valid(list.id) ?: return null
            if (nowMillis <= 0) return null

            // El mapeador ya deduplica, pero un `ShoppingList` construido a mano
            // (o por una versión futura) no tiene por qué. Sin esto, dos filas
            // con el mismo id romperían la validación de la caché entera.
            val unique = ArrayList<ShoppingItem>(minOf(list.items.size, ShoppingListState.MAX_ITEMS))
            val seen = HashSet<String>(unique.size * 2)
            for (item in list.items) {
                if (unique.size >= ShoppingListState.MAX_ITEMS) break
                if (seen.add(item.id)) unique += item
            }

            val view = ShoppingListView.from(list.name, unique)
            val rows = ArrayList<ShoppingWidgetRow>(MAX_ROWS)
            val rowIds = HashSet<String>(MAX_ROWS * 2)
            for (section in view.sections) {
                for (line in section.lines) {
                    if (rows.size >= MAX_ROWS) break
                    if (line.checked) continue
                    val itemId = ShoppingIds.valid(line.itemId) ?: continue
                    if (!rowIds.add(itemId)) continue
                    val text = PlainTextSanitizer.sanitize(line.text, MAX_ROW_TEXT)
                    if (text.isEmpty()) continue
                    rows += ShoppingWidgetRow(itemId = itemId, text = text)
                }
                if (rows.size >= MAX_ROWS) break
            }

            // `ShoppingListView` ya pone el nombre por defecto, pero no sanea lo
            // que le llega: la lista puede venir de una copia manipulada.
            val listName = ShoppingText.listName(view.listName).ifEmpty { ShoppingText.DEFAULT_LIST_NAME }

            return ShoppingWidgetSnapshot(
                listId = listId,
                listName = listName,
                pendingCount = view.pendingCount,
                checkedCount = view.checkedCount,
                updatedAtMillis = nowMillis,
                rows = rows,
            )
        }
    }
}
