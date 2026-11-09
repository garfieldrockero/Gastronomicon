package io.github.garfieldrockero.gastronomicon.core.widget

import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingText

/**
 * Lo que el widget pinta, ya decidido en el núcleo: la interfaz de Android solo
 * copia estos valores en `RemoteViews` y no calcula nada.
 *
 * - [rows] son las pendientes que caben en el alto disponible (nunca más de
 *   [ShoppingWidgetSnapshot.MAX_ROWS]); el resto se resume en [moreText].
 * - [pendingCount] y [checkedCount] son los contadores de la lista entera, no
 *   los de las filas visibles.
 * - [status] es una línea de texto fijo; [statusText] está vacío cuando no hay
 *   nada que decir.
 * - [updatedAtMillis] es la fecha de la copia, para estamparla tal cual (en
 *   formato absoluto: así no hace falta repintar cada minuto).
 *
 * No lleva URL, usuario, token ni `rawJson`: es lo único que viaja al proceso
 * del launcher.
 */
class ShoppingWidgetView private constructor(
    val listName: String,
    val rows: List<ShoppingWidgetRow>,
    val pendingCount: Int,
    val checkedCount: Int,
    val hiddenCount: Int,
    val status: ShoppingWidgetStatus,
    val updatedAtMillis: Long?,
) {
    val hasRows: Boolean get() = rows.isNotEmpty()

    /** La lista no tiene nada: ni pendiente ni marcado. */
    val isEmpty: Boolean get() = pendingCount == 0 && checkedCount == 0

    /** «y N más» para los pendientes que no caben, o null si caben todos. */
    val moreText: String? get() = if (hiddenCount > 0) "y $hiddenCount más" else null

    /** Texto de la línea de estado; vacío si no hay ninguna. */
    val statusText: String get() = status.text

    val hasStatus: Boolean get() = statusText.isNotEmpty()

    // Sin el nombre ni las filas: son datos del servidor.
    override fun toString(): String =
        "ShoppingWidgetView(rows=${rows.size}, pending=$pendingCount, checked=$checkedCount, " +
            "hidden=$hiddenCount, status=${status.javaClass.simpleName})"

    companion object {

        /**
         * Vista para [snapshot], o la vista vacía si todavía no hay copia.
         *
         * [maxRows] lo calcula `ShoppingWidgetLayout.rowsFor` con el alto del
         * widget y el tamaño de letra; se acota aquí otra vez para que una
         * llamada con un número absurdo no pueda pintar más de la cuenta.
         */
        fun from(
            snapshot: ShoppingWidgetSnapshot?,
            maxRows: Int = ShoppingWidgetSnapshot.MAX_ROWS,
            status: ShoppingWidgetStatus = ShoppingWidgetStatus.Hidden,
        ): ShoppingWidgetView {
            if (snapshot == null) {
                return ShoppingWidgetView(
                    listName = ShoppingText.DEFAULT_LIST_NAME,
                    rows = emptyList(),
                    pendingCount = 0,
                    checkedCount = 0,
                    hiddenCount = 0,
                    status = status,
                    updatedAtMillis = null,
                )
            }
            val limit = maxRows.coerceIn(0, ShoppingWidgetSnapshot.MAX_ROWS)
            val shown = snapshot.rows.take(limit)
            return ShoppingWidgetView(
                listName = snapshot.listName,
                rows = shown,
                pendingCount = snapshot.pendingCount,
                checkedCount = snapshot.checkedCount,
                hiddenCount = (snapshot.pendingCount - shown.size).coerceAtLeast(0),
                status = status,
                updatedAtMillis = snapshot.updatedAtMillis,
            )
        }
    }
}
