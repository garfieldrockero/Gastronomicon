package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.recipes.QuantityFormatter
import java.util.Locale

/**
 * Una línea ya compuesta para pintar: «2 kg patatas (para la cena)».
 *
 * [quantity] y [note] se guardan aparte porque el diálogo de edición los
 * necesita tal cual; [text] es lo único que se pinta en la lista.
 */
data class ShoppingLine(
    val itemId: String,
    val text: String,
    val checked: Boolean,
    val quantity: Double,
    val note: String,
    /** Posición que puso Mealie; solo se usa para ordenar de forma estable. */
    val position: Int,
) {
    // Sin el texto ni la nota: son datos del servidor y una línea puede acabar en un log.
    override fun toString(): String =
        "ShoppingLine(itemId=$itemId, checked=$checked, quantity=$quantity)"
}

/** Un grupo de la lista (una etiqueta de Mealie, o los que no tienen ninguna). */
data class ShoppingSection(
    val title: String,
    val lines: List<ShoppingLine>,
    val pendingCount: Int,
    val checkedCount: Int,
) {
    // Sin el título: es una etiqueta del servidor.
    override fun toString(): String =
        "ShoppingSection(lines=${lines.size}, pending=$pendingCount, checked=$checkedCount)"
}

/**
 * Vista completa de la lista de la compra: la interfaz solo pinta esto.
 *
 * Reglas (todas aquí, ninguna en `app/`):
 * - Se agrupa por la etiqueta del elemento; los que no tienen van juntos en
 *   [ShoppingText.DEFAULT_NO_SECTION_TITLE] al final. Si ningún elemento lleva
 *   etiqueta no se pone título: el grupo único sale con [ShoppingSection.title]
 *   vacío para que la pantalla no enseñe una cabecera que no aporta nada.
 * - Dentro de cada grupo, primero los pendientes y después los marcados.
 * - El orden es estable: posición de Mealie, luego el texto y, por último, el
 *   id. Un servidor que repita posiciones no puede hacer bailar la lista.
 * - El texto se compone aquí: cantidad (con el formateador de siempre, que
 *   omite las absurdas), unidad, alimento o nota. La nota va entre paréntesis
 *   cuando hay alimento, para no confundirla con él.
 */
class ShoppingListView private constructor(
    val listName: String,
    val sections: List<ShoppingSection>,
    val pendingCount: Int,
    val checkedCount: Int,
) {
    val totalCount: Int get() = pendingCount + checkedCount

    val isEmpty: Boolean get() = totalCount == 0

    /** Hay algo marcado: la interfaz puede ofrecer «borrar los marcados». */
    val hasChecked: Boolean get() = checkedCount > 0

    /** Hay elementos y todos están marcados. */
    val allChecked: Boolean get() = totalCount > 0 && pendingCount == 0

    companion object {

        /**
         * Construye la vista. [listName] ya viene saneado del cliente; si está
         * vacío se usa [fallbackListName].
         */
        fun from(
            listName: String,
            items: List<ShoppingItem>,
            fallbackListName: String = ShoppingText.DEFAULT_LIST_NAME,
            noSectionTitle: String = ShoppingText.DEFAULT_NO_SECTION_TITLE,
        ): ShoppingListView {
            val name = listName.ifBlank { fallbackListName }
            if (items.isEmpty()) {
                return ShoppingListView(name, emptyList(), pendingCount = 0, checkedCount = 0)
            }

            // Sin ninguna etiqueta no hay nada que separar: un solo grupo sin
            // título, para no enseñar «Sin sección» cuando todo está sin ella.
            val hasLabels = items.any { it.labelName.isNotEmpty() }
            val groups = LinkedHashMap<String, MutableList<ShoppingItem>>()
            for (item in items) {
                val title = when {
                    !hasLabels -> ""
                    item.labelName.isNotEmpty() -> item.labelName
                    else -> noSectionTitle
                }
                groups.getOrPut(title) { mutableListOf() }.add(item)
            }

            val orderedTitles = groups.keys.sortedWith(
                compareBy<String>({ it == noSectionTitle }, { it.lowercase(Locale.ROOT) }, { it }),
            )

            var pending = 0
            var checked = 0
            val sections = ArrayList<ShoppingSection>(orderedTitles.size)
            for (title in orderedTitles) {
                val lines = groups[title].orEmpty()
                    .mapNotNull { line(it) }
                    .sortedWith(
                        compareBy<ShoppingLine>(
                            { it.checked },
                            { it.position },
                            { it.text.lowercase(Locale.ROOT) },
                            { it.itemId },
                        ),
                    )
                if (lines.isEmpty()) continue
                val sectionPending = lines.count { !it.checked }
                val sectionChecked = lines.size - sectionPending
                pending += sectionPending
                checked += sectionChecked
                sections += ShoppingSection(title, lines, sectionPending, sectionChecked)
            }
            return ShoppingListView(name, sections, pending, checked)
        }

        /** Texto de una línea, o null si el elemento no tiene nada visible. */
        private fun line(item: ShoppingItem): ShoppingLine? {
            val quantity = QuantityFormatter.format(item.quantity)
            val food = item.food
            val unit = item.unit
            val note = item.note
            // Con alimento, la nota va aparte entre paréntesis; sin él, la nota
            // es el texto principal y no se repite.
            val head = if (food.isNotEmpty()) {
                join(quantity, unit, food)
            } else {
                join(quantity, unit, note)
            }
            val text = when {
                food.isEmpty() -> head
                note.isEmpty() -> head
                head.isEmpty() -> note
                else -> "$head ($note)"
            }
            if (text.isEmpty()) return null
            return ShoppingLine(
                itemId = item.id,
                text = text,
                checked = item.checked,
                quantity = item.quantity,
                note = note,
                position = item.position,
            )
        }

        private fun join(vararg parts: String): String =
            parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    // Sin el contenido: la vista puede acabar en un log y los textos son del servidor.
    override fun toString(): String =
        "ShoppingListView(sections=${sections.size}, pending=$pendingCount, checked=$checkedCount)"
}
