package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingIds
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingText
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetRow
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot
import kotlinx.serialization.Serializable

/*
 * Formato persistido de la instantánea del widget.
 *
 * Solo guarda lo que se pinta: nombre de la lista, contadores y hasta ocho
 * filas pendientes. No hay `rawJson`, ni URL, ni usuario, ni token, ni el
 * detalle de los marcados. Al leerlo se trata como dato NO confiable y se
 * vuelve a sanear todo; cualquier incoherencia descarta el blob entero.
 */

@Serializable
internal class StoredWidgetSnapshot(
    val format: Int = 0,
    /** Ámbito al que pertenece el blob: si no coincide con el de la clave, se descarta. */
    val scope: String = "",
    val listId: String = "",
    val listName: String = "",
    val pendingCount: Int = 0,
    val checkedCount: Int = 0,
    val updatedAt: Long = 0,
    val items: List<StoredWidgetRow> = emptyList(),
)

@Serializable
internal class StoredWidgetRow(val id: String = "", val text: String = "")

/** Versión del formato de la caché del widget. Otra se descarta entera. */
internal object ShoppingWidgetFormat {

    /** La 1 es la primera y única: si cambia el significado, se sube y se descarta lo anterior. */
    const val CURRENT = 1
}

/**
 * Serialización y validación de la instantánea del widget.
 *
 * El mismo camino sirve para guardar y para leer. Al leer se comprueba versión,
 * ámbito, UUID de lista y de fila, contadores coherentes, ids sin repetir y
 * textos ya saneados y acotados: cualquier cosa rara devuelve `null` y quien
 * llama borra la entrada y pinta el estado vacío. Es preferible no enseñar
 * nada a enseñar una compra mezclada o manipulada.
 */
internal object ShoppingListCacheCodec {

    /**
     * Serializa [snapshot] para [scope], o null si no se puede guardar tal
     * cual. No recorta nada: la instantánea ya viene de
     * [ShoppingWidgetSnapshot.from], así que un exceso aquí significa que algo
     * no cuadra.
     */
    fun encode(snapshot: ShoppingWidgetSnapshot, scope: ServerScope): ByteArray? {
        if (snapshot.updatedAtMillis <= 0) return null
        val listId = ShoppingIds.valid(snapshot.listId) ?: return null
        val listName = nameOf(snapshot.listName) ?: return null
        if (snapshot.rows.size > ShoppingWidgetSnapshot.MAX_ROWS) return null
        if (!countsValid(snapshot.pendingCount, snapshot.checkedCount, snapshot.rows.size)) return null

        val ids = HashSet<String>(snapshot.rows.size * 2)
        val rows = ArrayList<StoredWidgetRow>(snapshot.rows.size)
        for (row in snapshot.rows) {
            val id = ShoppingIds.valid(row.itemId) ?: return null
            if (!ids.add(id)) return null
            val text = textOf(row.text) ?: return null
            rows += StoredWidgetRow(id = id, text = text)
        }

        val stored = StoredWidgetSnapshot(
            format = ShoppingWidgetFormat.CURRENT,
            scope = scope.id,
            listId = listId,
            listName = listName,
            pendingCount = snapshot.pendingCount,
            checkedCount = snapshot.checkedCount,
            updatedAt = snapshot.updatedAtMillis,
            items = rows,
        )
        return try {
            CoreJson.json.encodeToString(StoredWidgetSnapshot.serializer(), stored)
                .toByteArray(Charsets.UTF_8)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /**
     * Lee y valida un blob. [maxBytes] evita parsear algo desmesurado antes de
     * comprobar el tope de la caché. [scope] es el de la clave: si el blob
     * pertenece a otro servidor o a otra cuenta, no se sirve.
     */
    fun decode(bytes: ByteArray, scope: ServerScope, maxBytes: Int): ShoppingWidgetSnapshot? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        val stored = try {
            CoreJson.json.decodeFromString(StoredWidgetSnapshot.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (ignored: IllegalArgumentException) {
            // Incluye SerializationException, que hereda de IllegalArgumentException.
            return null
        } catch (ignored: StackOverflowError) {
            return null
        }
        if (stored.format != ShoppingWidgetFormat.CURRENT) return null
        if (stored.scope != scope.id) return null
        if (stored.updatedAt <= 0) return null
        val listId = ShoppingIds.valid(stored.listId) ?: return null
        val listName = nameOf(stored.listName) ?: return null
        if (stored.items.size > ShoppingWidgetSnapshot.MAX_ROWS) return null
        if (!countsValid(stored.pendingCount, stored.checkedCount, stored.items.size)) return null

        val ids = HashSet<String>(stored.items.size * 2)
        val rows = ArrayList<ShoppingWidgetRow>(stored.items.size)
        for (item in stored.items) {
            val id = ShoppingIds.valid(item.id) ?: return null
            if (!ids.add(id)) return null
            val text = textOf(item.text) ?: return null
            rows += ShoppingWidgetRow(itemId = id, text = text)
        }
        return ShoppingWidgetSnapshot(
            listId = listId,
            listName = listName,
            pendingCount = stored.pendingCount,
            checkedCount = stored.checkedCount,
            updatedAtMillis = stored.updatedAt,
            rows = rows,
        )
    }

    /**
     * Contadores coherentes: no negativos, al menos tantos pendientes como
     * filas guardadas, y la lista entera dentro del tope de
     * [ShoppingListState]. El total va en `Long` para que dos enteros
     * desmesurados no puedan desbordar y colarse.
     */
    private fun countsValid(pending: Int, checked: Int, rowCount: Int): Boolean {
        if (pending < 0 || checked < 0) return false
        if (pending < rowCount) return false
        if (pending > ShoppingListState.MAX_ITEMS || checked > ShoppingListState.MAX_ITEMS) return false
        return pending.toLong() + checked.toLong() <= ShoppingListState.MAX_ITEMS.toLong()
    }

    /** Nombre saneado y no vacío, o null si venía por encima del tope o sin nada visible. */
    private fun nameOf(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.codePointCount(0, raw.length) > ShoppingText.MAX_LIST_NAME) return null
        val clean = ShoppingText.listName(raw)
        return if (clean.isEmpty()) null else clean
    }

    /**
     * Texto ya saneado y sin saltos de línea, o null. Se exige que el saneado
     * no cambie nada: si el blob traía controles, espacios de más o saltos, no
     * lo escribió esta app (o alguien lo manipuló) y se descarta.
     */
    private fun textOf(raw: String): String? {
        if (raw.isEmpty()) return null
        if (raw.codePointCount(0, raw.length) > ShoppingWidgetSnapshot.MAX_ROW_TEXT) return null
        val clean = PlainTextSanitizer.sanitize(raw, ShoppingWidgetSnapshot.MAX_ROW_TEXT)
        if (clean.isEmpty() || clean != raw) return null
        return clean
    }
}
