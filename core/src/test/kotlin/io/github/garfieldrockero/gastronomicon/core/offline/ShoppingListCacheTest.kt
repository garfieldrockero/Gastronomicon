package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot
import io.github.garfieldrockero.gastronomicon.core.widget.WidgetFixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la caché cifrada del widget con el almacén falso: ámbito
 * servidor+cuenta, caducidad, fallos que no borran, datos manipulados que se
 * descartan y borrado con la sesión.
 */
class ShoppingListCacheTest {

    private val store = FakeEncryptedBlobStore()
    private val scopeA = OfflineFixtures.scopeA()
    private val scopeB = OfflineFixtures.scopeB()
    private var clock = WidgetFixtures.NOW

    private fun newCache(
        maxBytes: Int = ShoppingListCache.DEFAULT_MAX_BYTES,
        ttlMillis: Long = ShoppingListCache.DEFAULT_TTL_MILLIS,
    ): ShoppingListCache = ShoppingListCache(store, maxBytes, ttlMillis) { clock }

    private fun key(scope: ServerScope): String = BlobPrefix.WIDGET + scope.id

    private fun assertWriteFailed(expected: StorageProblem, result: OfflineWrite) {
        assertTrue("Se esperaba un fallo y llegó $result", result is OfflineWrite.Failed)
        assertEquals(expected, (result as OfflineWrite.Failed).problem)
    }

    private fun assertReadFailed(expected: StorageProblem, result: OfflineRead<*>) {
        assertTrue("Se esperaba un fallo y llegó $result", result is OfflineRead.Failed)
        assertEquals(expected, (result as OfflineRead.Failed).problem)
    }

    // ------------------------------------------------------------ básico

    @Test
    fun `guarda y devuelve la misma instantanea`() {
        val cache = newCache()
        val snapshot = WidgetFixtures.snapshot()

        assertEquals(OfflineWrite.Saved, cache.save(scopeA, snapshot))

        val saved = cache.load(scopeA).hitOrFail()
        assertEquals(snapshot.listId, saved.listId)
        assertEquals(snapshot.listName, saved.listName)
        assertEquals(snapshot.pendingCount, saved.pendingCount)
        assertEquals(snapshot.checkedCount, saved.checkedCount)
        assertEquals(snapshot.updatedAtMillis, saved.updatedAtMillis)
        assertEquals(snapshot.rows, saved.rows)
        assertEquals(setOf(key(scopeA)), store.blobs.keys)
    }

    @Test
    fun `sin copia devuelve Miss`() {
        assertTrue(newCache().load(scopeA) is OfflineRead.Miss)
    }

    @Test
    fun `guardar dos veces sustituye la copia`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot(listName = "Antigua"))
        clock += 1_000
        cache.save(scopeA, WidgetFixtures.snapshot(listName = "Nueva", updatedAtMillis = clock))

        val saved = (cache.load(scopeA) as OfflineRead.Hit<ShoppingWidgetSnapshot>).value

        assertEquals("Nueva", saved.listName)
        assertEquals(1, store.blobs.size)
    }

    @Test
    fun `la copia de otro ambito no se sirve`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())

        assertTrue(cache.load(scopeB) is OfflineRead.Miss)
    }

    @Test
    fun `un blob que no descifra se borra`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())
        store.corruptKeys += key(scopeA)

        assertTrue(cache.load(scopeA) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key(scopeA)))
    }

    @Test
    fun `un blob manipulado se borra`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())
        val cambiado = CoreJson.json.parseToJsonElement(
            store.blobs.getValue(key(scopeA)).toString(Charsets.UTF_8),
        ) as JsonObject
        val roto = JsonObject(cambiado + ("listId" to JsonPrimitive("no-es-uuid")))
        store.blobs[key(scopeA)] = CoreJson.json.encodeToString(JsonObject.serializer(), roto)
            .toByteArray(Charsets.UTF_8)

        assertTrue(cache.load(scopeA) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key(scopeA)))
    }

    @Test
    fun `un blob de otra version se borra`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())
        val root = CoreJson.json.parseToJsonElement(
            store.blobs.getValue(key(scopeA)).toString(Charsets.UTF_8),
        ) as JsonObject
        val otra = JsonObject(root + ("format" to JsonPrimitive(99)))
        store.blobs[key(scopeA)] = CoreJson.json.encodeToString(JsonObject.serializer(), otra)
            .toByteArray(Charsets.UTF_8)

        assertTrue(cache.load(scopeA) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key(scopeA)))
    }

    @Test
    fun `un json roto se borra`() {
        val cache = newCache()
        store.blobs[key(scopeA)] = "no es json".toByteArray(Charsets.UTF_8)

        assertTrue(cache.load(scopeA) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key(scopeA)))
    }

    @Test
    fun `el blob no lleva token ni url`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())

        val texto = store.blobs.getValue(key(scopeA)).toString(Charsets.UTF_8)

        assertFalse(texto.contains("token", ignoreCase = true))
        assertFalse(texto.contains("http", ignoreCase = true))
        assertFalse(texto.contains("Bearer"))
        assertFalse(texto.contains("Authorization"))
        assertFalse(texto.contains("usuario"))
    }

    // ------------------------------------------------------------- fallos

    @Test
    fun `si el almacen no responde al leer no se borra nada`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())
        store.readProblem = StorageProblem.Unavailable

        assertReadFailed(StorageProblem.Unavailable, cache.load(scopeA))
        assertTrue(store.blobs.containsKey(key(scopeA)))
    }

    @Test
    fun `si el almacen no deja escribir se devuelve el problema`() {
        val cache = newCache()
        store.writeProblem = StorageProblem.Unavailable

        assertWriteFailed(StorageProblem.Unavailable, cache.save(scopeA, WidgetFixtures.snapshot()))
        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `una instantanea invalida no toca la copia anterior`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot(listName = "Buena"))

        assertEquals(
            OfflineWrite.Rejected,
            cache.save(scopeA, WidgetFixtures.snapshot(listId = "no-es-uuid")),
        )
        val saved = cache.load(scopeA).hitOrFail()
        assertEquals("Buena", saved.listName)
    }

    @Test
    fun `una instantanea demasiado grande borra la copia anterior`() {
        newCache().save(scopeA, WidgetFixtures.snapshot())
        val pequena = newCache(maxBytes = 10)

        assertEquals(OfflineWrite.TooLarge, pequena.save(scopeA, WidgetFixtures.snapshot()))
        assertFalse(store.blobs.containsKey(key(scopeA)))
    }

    // ------------------------------------------------------------ caducidad

    @Test
    fun `una copia caducada no se sirve y se borra`() {
        val cache = newCache(ttlMillis = 1_000L)
        cache.save(scopeA, WidgetFixtures.snapshot())

        clock += 1_001

        assertTrue(cache.load(scopeA) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key(scopeA)))
    }

    @Test
    fun `justo antes de la caducidad se sirve`() {
        val cache = newCache(ttlMillis = 1_000L)
        cache.save(scopeA, WidgetFixtures.snapshot())

        clock += 1_000

        assertTrue(cache.load(scopeA) is OfflineRead.Hit)
    }

    @Test
    fun `una marca de tiempo futura se descarta`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())

        clock -= 10L * 60 * 1000

        assertTrue(cache.load(scopeA) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key(scopeA)))
    }

    // -------------------------------------------------------------- borrado

    @Test
    fun `clear de un ambito no toca el otro`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())
        cache.save(scopeB, WidgetFixtures.snapshot())

        assertEquals(OfflineWrite.Saved, cache.clear(scopeA))

        assertTrue(cache.load(scopeA) is OfflineRead.Miss)
        assertTrue(cache.load(scopeB) is OfflineRead.Hit)
    }

    @Test
    fun `clear total borra todos los ambitos`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())
        cache.save(scopeB, WidgetFixtures.snapshot())

        assertEquals(OfflineWrite.Saved, cache.clear())

        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `el borrado con la sesion barre la cache del widget`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())
        cache.save(scopeB, WidgetFixtures.snapshot())

        assertEquals(OfflineWrite.Saved, OfflineWipe.clearAll(store))

        assertTrue(store.blobs.isEmpty())
        assertEquals(0, store.gets)
    }

    @Test
    fun `la clave usa el prefijo y el ambito`() {
        val cache = newCache()
        cache.save(scopeA, WidgetFixtures.snapshot())

        val esperada = "wg1." + scopeA.id

        assertEquals(esperada, store.blobs.keys.single())
        assertTrue(BlobKeys.isValid(esperada))
    }

    @Test
    fun `una lista vacia tambien se guarda`() {
        val cache = newCache()
        val snapshot = WidgetFixtures.snapshot(pendingCount = 0, checkedCount = 0, rows = emptyList())

        assertEquals(OfflineWrite.Saved, cache.save(scopeA, snapshot))

        val saved: ShoppingWidgetSnapshot = cache.load(scopeA).hitOrFail()
        assertTrue(saved.isEmpty)
    }
}
