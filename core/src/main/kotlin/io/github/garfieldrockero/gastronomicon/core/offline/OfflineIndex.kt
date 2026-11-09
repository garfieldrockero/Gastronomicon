package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.Serializable

/**
 * Índice de un ámbito: qué entradas hay, cuánto ocupan y cuándo se guardaron.
 *
 * Se guarda como un blob más (cifrado) y también es dato NO confiable al
 * leerlo. Si no cuadra, se descarta entero y se empieza de cero: es preferible
 * perder la caché a servir una entrada que el índice no respalda.
 *
 * Lleva el **ámbito** y la **versión del formato** dentro, y cada entrada lleva
 * el hash de su clave: así un índice copiado al ámbito de otra cuenta, uno de
 * una versión anterior o uno manipulado para apuntar a la clave de otra entrada
 * no se aceptan, y el llamante borra todo lo de ese ámbito (falla cerrado).
 */
@Serializable
internal class StoredIndexEntry(
    val key: String = "",
    val keyHash: String = "",
    val itemId: String = "",
    val bytes: Int = 0,
    val cachedAt: Long = 0,
)

@Serializable
internal class StoredIndex(
    val format: Int = 0,
    val scope: String = "",
    val entries: List<StoredIndexEntry> = emptyList(),
)

/** Entrada de índice ya validada. */
internal class IndexEntry(
    val key: String,
    val itemId: String,
    val bytes: Int,
    val cachedAt: Long,
) {
    // Sin la clave ni el identificador: el índice no debe arrastrar contenido a un log.
    override fun toString(): String = "IndexEntry(bytes=$bytes, cachedAt=$cachedAt)"
}

/**
 * Versión única de todos los datos offline. Un número distinto se descarta
 * entero.
 *
 * La 2 añade el ámbito y el id de usuario a las claves, y el ámbito, el hash de
 * la clave y la identidad del borrador a los blobs: lo escrito con la 1 se
 * descarta al leerlo, que es lo correcto al cambiar el significado de los datos.
 */
internal object OfflineFormat {

    /** Se sube a la vez en índice, receta y borrador: no hay combinaciones válidas. */
    const val CURRENT = 2
}

internal object OfflineIndex {

    /** Tope del propio índice: 50 entradas ocupan mucho menos. */
    const val MAX_BYTES = 256 * 1024

    /** Tope duro de entradas en un índice manipulado. */
    const val MAX_ENTRIES = 2_000

    /** Tope duro del tamaño declarado de una entrada (el real lo pone quien llama). */
    const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

    /**
     * Decodifica y valida un índice de [scope].
     *
     * Devuelve null ante cualquier incoherencia: versión distinta, ámbito
     * distinto, JSON roto, más entradas de las posibles, clave fuera del
     * prefijo, hash de clave que no corresponde, clave que no es la que toca
     * para ese identificador, identificador inválido, tamaño imposible o marca
     * de tiempo no positiva. No se repara a medias: quien llama borra el índice
     * y las entradas huérfanas del ámbito.
     */
    fun decode(
        bytes: ByteArray,
        scope: ServerScope,
        keyPrefix: String,
        itemValid: (String) -> Boolean,
    ): List<IndexEntry>? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val root = try {
            CoreJson.json.decodeFromString(StoredIndex.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (ignored: IllegalArgumentException) {
            return null
        } catch (ignored: StackOverflowError) {
            return null
        }
        if (root.format != OfflineFormat.CURRENT) return null
        // Un índice de otro ámbito (otro servidor u otra cuenta) no se usa nunca.
        if (root.scope != scope.id) return null
        if (root.entries.size > MAX_ENTRIES) return null

        val keys = HashSet<String>(root.entries.size * 2)
        val items = HashSet<String>(root.entries.size * 2)
        val result = ArrayList<IndexEntry>(root.entries.size)
        for (entry in root.entries) {
            if (!BlobKeys.isValid(entry.key) || !entry.key.startsWith(keyPrefix)) return null
            // El hash de la clave forma parte de lo que se verifica al leer.
            if (Hashing.sha256Hex(entry.key) != entry.keyHash) return null
            // La clave tiene que ser la que produce el identificador: si no, la
            // entrada apuntaría al blob de otra receta o de otro borrador.
            if (BlobName.item(keyPrefix, scope, entry.itemId) != entry.key) return null
            if (!keys.add(entry.key)) return null
            if (!items.add(entry.itemId)) return null
            if (!itemValid(entry.itemId)) return null
            if (entry.bytes <= 0 || entry.bytes > MAX_ENTRY_BYTES) return null
            if (entry.cachedAt <= 0) return null
            result += IndexEntry(entry.key, entry.itemId, entry.bytes, entry.cachedAt)
        }
        return result
    }

    /** Serializa el índice de [scope], o null si no cabe o no se puede escribir. */
    fun encode(scope: ServerScope, entries: List<IndexEntry>): ByteArray? {
        if (entries.size > MAX_ENTRIES) return null
        val storedEntries = ArrayList<StoredIndexEntry>(entries.size)
        for (entry in entries) {
            val hash = Hashing.sha256Hex(entry.key) ?: return null
            storedEntries += StoredIndexEntry(
                key = entry.key,
                keyHash = hash,
                itemId = entry.itemId,
                bytes = entry.bytes,
                cachedAt = entry.cachedAt,
            )
        }
        val stored = StoredIndex(
            format = OfflineFormat.CURRENT,
            scope = scope.id,
            entries = storedEntries,
        )
        val bytes = try {
            CoreJson.json.encodeToString(StoredIndex.serializer(), stored).toByteArray(Charsets.UTF_8)
        } catch (ignored: IllegalArgumentException) {
            return null
        }
        return if (bytes.size > MAX_BYTES) null else bytes
    }
}
