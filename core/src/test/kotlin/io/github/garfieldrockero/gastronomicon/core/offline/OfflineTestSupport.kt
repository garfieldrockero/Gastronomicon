package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue

/*
 * Utilidades compartidas por las pruebas del paquete offline.
 *
 * Las aserciones imprimen el resultado; por eso los resultados de lectura
 * redactan su `toString` (no deben arrastrar recetas ni borradores a un log).
 */

internal fun <T> OfflineRead<T>.hitOrFail(): T {
    assertTrue("Se esperaba un acierto y llegó $this", this is OfflineRead.Hit)
    @Suppress("UNCHECKED_CAST")
    val hit = this as OfflineRead.Hit<T>
    return hit.value
}

internal fun <T> OfflineList<T>.itemsOrFail(): List<T> {
    assertTrue("Se esperaba una lista y llegó $this", this is OfflineList.Ok)
    @Suppress("UNCHECKED_CAST")
    val ok = this as OfflineList.Ok<T>
    return ok.items
}

internal fun <T> StoreResult<T>.valueOrFail(): T {
    assertTrue("Se esperaba un valor y llegó $this", this is StoreResult.Ok)
    @Suppress("UNCHECKED_CAST")
    val ok = this as StoreResult.Ok<T>
    return ok.value
}

internal fun JsonObject.with(key: String, value: JsonElement): JsonObject =
    JsonObject(this + (key to value))

/** Primera clave guardada con [prefix]; falla si no hay ninguna. */
internal fun FakeEncryptedBlobStore.keyWithPrefix(prefix: String): String =
    blobs.keys.first { it.startsWith(prefix) }

internal fun FakeEncryptedBlobStore.entryKey(): String = keyWithPrefix("rc1.")
internal fun FakeEncryptedBlobStore.indexKey(): String = keyWithPrefix("rci1.")
internal fun FakeEncryptedBlobStore.draftKey(): String = keyWithPrefix("dr1.")
internal fun FakeEncryptedBlobStore.draftIndexKey(): String = keyWithPrefix("dri1.")

/** Reescribe un blob JSON como haría alguien con acceso al fichero. */
/**
 * Reescribe un blob JSON como haría alguien con acceso al fichero.
 *
 * Con [syncIndex] (por defecto) el tamaño que anota el índice se ajusta al nuevo, para que la
 * prueba compruebe el saneado del contenido y no la comprobación de tamaño del índice (que ya
 * tiene sus propias pruebas). Las pruebas del índice manipulado lo reescriben aparte.
 */
internal fun FakeEncryptedBlobStore.tamper(
    prefix: String,
    syncIndex: Boolean = true,
    transform: (JsonObject) -> JsonObject,
) {
    val key = keyWithPrefix(prefix)
    val root = CoreJson.json.parseToJsonElement(blobs.getValue(key).toString(Charsets.UTF_8)) as JsonObject
    val rewritten = CoreJson.json
        .encodeToString(JsonObject.serializer(), transform(root))
        .toByteArray(Charsets.UTF_8)
    blobs[key] = rewritten
    if (!syncIndex) return
    val indexPrefix = when (prefix) {
        "rc1." -> "rci1."
        "dr1." -> "dri1."
        else -> return
    }
    val indexKey = keyWithPrefix(indexPrefix)
    val index = CoreJson.json.parseToJsonElement(blobs.getValue(indexKey).toString(Charsets.UTF_8)) as JsonObject
    val entries = (index["entries"] as JsonArray).map { element ->
        val entry = element as JsonObject
        if ((entry["key"] as? JsonPrimitive)?.content == key) {
            entry.with("bytes", JsonPrimitive(rewritten.size))
        } else {
            entry
        }
    }
    blobs[indexKey] = CoreJson.json
        .encodeToString(JsonObject.serializer(), index.with("entries", JsonArray(entries)))
        .toByteArray(Charsets.UTF_8)
}
