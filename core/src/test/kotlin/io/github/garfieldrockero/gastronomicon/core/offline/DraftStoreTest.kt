package io.github.garfieldrockero.gastronomicon.core.offline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftStoreTest {

    private val fake = FakeEncryptedBlobStore()
    private var clock = 1_000_000L
    private val scope = OfflineFixtures.scopeA()
    private val nueva = DraftTarget.New
    private val receta = DraftTarget.Recipe(OfflineFixtures.SLUG)

    private fun draftStore(
        maxEntries: Int = DraftStore.DEFAULT_MAX_ENTRIES,
        maxEntryBytes: Int = DraftStore.DEFAULT_MAX_ENTRY_BYTES,
        maxTotalBytes: Int = DraftStore.DEFAULT_MAX_TOTAL_BYTES,
        ttlMillis: Long = DraftStore.DEFAULT_TTL_MILLIS,
    ): DraftStore = DraftStore(fake, maxEntries, maxEntryBytes, maxTotalBytes, ttlMillis) { clock }

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
    fun `guarda y devuelve el borrador de receta nueva`() {
        val store = draftStore()
        val draft = OfflineFixtures.draft()

        assertEquals(OfflineWrite.Saved, store.save(scope, nueva, draft))
        val hit = store.load(scope, nueva).hitOrFail()

        assertEquals(draft, hit.draft)
        assertEquals(clock, hit.savedAtMillis)
    }

    @Test
    fun `guarda y devuelve el borrador de una receta`() {
        val store = draftStore()
        val draft = OfflineFixtures.draft(name = "Tortilla de patatas")

        assertEquals(OfflineWrite.Saved, store.save(scope, receta, draft))
        assertEquals(draft, store.load(scope, receta).hitOrFail().draft)
    }

    @Test
    fun `nueva y una receta no comparten entrada`() {
        val store = draftStore()
        val nuevo = OfflineFixtures.draft(name = "Nueva")
        val edicion = OfflineFixtures.draft(name = "Edicion")

        store.save(scope, nueva, nuevo)
        store.save(scope, receta, edicion)

        assertEquals("Nueva", store.load(scope, nueva).hitOrFail().draft.name)
        assertEquals("Edicion", store.load(scope, receta).hitOrFail().draft.name)
        assertEquals(2, store.list(scope).itemsOrFail().size)
    }

    @Test
    fun `sin nada guardado devuelve Miss`() {
        assertTrue(draftStore().load(scope, nueva) is OfflineRead.Miss)
    }

    @Test
    fun `un borrador de otro servidor no se mezcla`() {
        val store = draftStore()
        store.save(OfflineFixtures.scopeA(), nueva, OfflineFixtures.draft())

        assertTrue(store.load(OfflineFixtures.scopeB(), nueva) is OfflineRead.Miss)
    }

    @Test
    fun `guardar dos veces el mismo destino actualiza`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft(name = "Primera"))
        clock += 10
        store.save(scope, nueva, OfflineFixtures.draft(name = "Segunda"))

        assertEquals("Segunda", store.load(scope, nueva).hitOrFail().draft.name)
        assertEquals(1, store.list(scope).itemsOrFail().size)
    }

    // ------------------------------------------------------------ borrado

    @Test
    fun `delete borra el borrador guardado`() {
        val store = draftStore()
        store.save(scope, receta, OfflineFixtures.draft())

        assertEquals(OfflineWrite.Saved, store.delete(scope, receta))

        assertTrue(store.load(scope, receta) is OfflineRead.Miss)
        assertTrue(store.list(scope).itemsOrFail().isEmpty())
    }

    @Test
    fun `delete no toca otros destinos`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft(name = "Nueva"))
        store.save(scope, receta, OfflineFixtures.draft(name = "Receta"))

        store.delete(scope, nueva)

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
        assertEquals("Receta", store.load(scope, receta).hitOrFail().draft.name)
    }

    @Test
    fun `delete sin nada guardado no falla`() {
        assertEquals(OfflineWrite.Saved, draftStore().delete(scope, receta))
    }

    // ------------------------------------------------------------ caducidad

    @Test
    fun `un borrador caducado no se sirve y se borra`() {
        val store = draftStore(ttlMillis = 1_000L)
        store.save(scope, nueva, OfflineFixtures.draft())

        clock += 1_001

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
        assertFalse(fake.blobs.keys.any { it.startsWith("dr1.") })
    }

    @Test
    fun `una marca de tiempo futura se descarta`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())

        clock -= 10L * 60 * 1000

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
    }

    // ------------------------------------------------------------ topes

    @Test
    fun `el tope de numero expulsa el mas antiguo`() {
        val store = draftStore(maxEntries = 2)
        val uno = DraftTarget.Recipe("uno")
        val dos = DraftTarget.Recipe("dos")
        val tres = DraftTarget.Recipe("tres")

        store.save(scope, uno, OfflineFixtures.draft())
        clock++
        store.save(scope, dos, OfflineFixtures.draft())
        clock++
        store.save(scope, tres, OfflineFixtures.draft())

        assertTrue(store.load(scope, uno) is OfflineRead.Miss)
        assertTrue(store.load(scope, dos) is OfflineRead.Hit)
        assertTrue(store.load(scope, tres) is OfflineRead.Hit)
    }

    @Test
    fun `el tope total expulsa el mas antiguo`() {
        val completo = draftStore()
        assertEquals(OfflineWrite.Saved, completo.save(scope, nueva, OfflineFixtures.draft()))
        val tamano = fake.blobs.entries.first { it.key.startsWith("dr1.") }.value.size

        val limitado = draftStore(maxEntryBytes = tamano + 200, maxTotalBytes = tamano + 20)
        clock++
        assertEquals(OfflineWrite.Saved, limitado.save(scope, receta, OfflineFixtures.draft()))

        assertTrue(limitado.load(scope, nueva) is OfflineRead.Miss)
        assertTrue(limitado.load(scope, receta) is OfflineRead.Hit)
    }

    @Test
    fun `un borrador demasiado grande no se guarda y borra el anterior`() {
        val completo = draftStore()
        assertEquals(OfflineWrite.Saved, completo.save(scope, nueva, OfflineFixtures.draft()))

        val pequeno = draftStore(maxEntryBytes = 64)
        assertEquals(OfflineWrite.TooLarge, pequeno.save(scope, nueva, OfflineFixtures.draft()))

        assertTrue(pequeno.load(scope, nueva) is OfflineRead.Miss)
        assertFalse(fake.blobs.keys.any { it.startsWith("dr1.") })
    }

    // ------------------------------------------------- datos no confiables

    @Test
    fun `un json corrupto se descarta sin lanzar`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        val key = fake.draftKey()
        fake.blobs[key] = "esto no es json".toByteArray(Charsets.UTF_8)

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
        assertFalse(fake.blobs.containsKey(key))
    }

    @Test
    fun `una version futura se descarta`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        fake.tamper("dr1.") { it.with("format", JsonPrimitive(99)) }

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
    }

    @Test
    fun `los caracteres de control se quitan y el texto se recorta`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        fake.tamper("dr1.") { root ->
            root.with("name", JsonPrimitive("Tortilla\u202E"))
                .with("description", JsonPrimitive("d".repeat(6_000)))
        }

        val draft = store.load(scope, nueva).hitOrFail().draft
        assertEquals("Tortilla", draft.name)
        assertEquals(5_000, draft.description.length)
    }

    @Test
    fun `un identificador de linea manipulado se cae a null`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        fake.tamper("dr1.") { root ->
            val steps = (root["steps"] as JsonArray).map { it as JsonObject }
            val changed = steps[0].with("sourceId", JsonPrimitive("../../otra"))
            root.with("steps", JsonArray(listOf<JsonElement>(changed)))
        }

        assertNull(store.load(scope, nueva).hitOrFail().draft.steps[0].sourceId)
    }

    @Test
    fun `un organizador manipulado pierde id y slug invalidos`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        fake.tamper("dr1.") { root ->
            val tags = (root["tags"] as JsonArray).map { it as JsonObject }
            val changed = tags[0]
                .with("id", JsonPrimitive("no-es-uuid"))
                .with("slug", JsonPrimitive("../fuera"))
            root.with("tags", JsonArray(listOf<JsonElement>(changed)))
        }

        val organizer = store.load(scope, nueva).hitOrFail().draft.tags[0]
        assertNull(organizer.id)
        assertNull(organizer.slug)
        assertEquals("Huevos", organizer.name)
    }

    @Test
    fun `un indice corrupto se reconstruye`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        fake.blobs[fake.draftIndexKey()] = "basura".toByteArray(Charsets.UTF_8)

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
        assertEquals(OfflineWrite.Saved, store.save(scope, nueva, OfflineFixtures.draft()))
        assertTrue(store.load(scope, nueva) is OfflineRead.Hit)
    }

    // --------------------------------------------------------- almacén roto

    @Test
    fun `si el almacen no escribe se devuelve un fallo`() {
        val store = draftStore()
        fake.writeProblem = StorageProblem.Unavailable

        assertWriteFailed(StorageProblem.Unavailable, store.save(scope, nueva, OfflineFixtures.draft()))
    }

    @Test
    fun `si el almacen no esta disponible al leer se devuelve un fallo`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        fake.readProblem = StorageProblem.Unavailable

        assertReadFailed(StorageProblem.Unavailable, store.load(scope, nueva))
    }

    @Test
    fun `una implementacion que lanza no rompe la interfaz`() {
        val store = draftStore()
        fake.throwOnGet = true

        assertReadFailed(StorageProblem.Unavailable, store.load(scope, nueva))
    }

    // ------------------------------------------------------------ listado

    @Test
    fun `list devuelve del mas reciente al mas antiguo y omite caducados`() {
        val store = draftStore(ttlMillis = 1_000L)
        store.save(scope, nueva, OfflineFixtures.draft())
        clock += 100
        store.save(scope, receta, OfflineFixtures.draft())

        val items = store.list(scope).itemsOrFail()
        assertEquals(listOf(receta, nueva), items.map { it.target })

        clock += 1_000
        assertEquals(listOf(receta), store.list(scope).itemsOrFail().map { it.target })
    }

    @Test
    fun `clear borra todo de todos los servidores`() {
        val store = draftStore()
        store.save(OfflineFixtures.scopeA(), nueva, OfflineFixtures.draft())
        store.save(OfflineFixtures.scopeB(), receta, OfflineFixtures.draft())

        assertEquals(OfflineWrite.Saved, store.clear())

        assertTrue(fake.blobs.isEmpty())
    }

    @Test
    fun `clear de un servidor no toca el otro`() {
        val store = draftStore()
        store.save(OfflineFixtures.scopeA(), nueva, OfflineFixtures.draft())
        store.save(OfflineFixtures.scopeB(), nueva, OfflineFixtures.draft())

        store.clear(OfflineFixtures.scopeA())

        assertTrue(store.load(OfflineFixtures.scopeA(), nueva) is OfflineRead.Miss)
        assertTrue(store.load(OfflineFixtures.scopeB(), nueva) is OfflineRead.Hit)
    }

    // ------------------------------------------------------------ destino

    @Test
    fun `DraftTarget acepta new y slugs validos y rechaza el resto`() {
        assertEquals(DraftTarget.New, DraftTarget.of("new"))
        assertEquals(DraftTarget.Recipe("tortilla"), DraftTarget.of("tortilla"))
        assertNull(DraftTarget.of("../fuera"))
        assertNull(DraftTarget.of("con espacio"))
        assertThrows(IllegalArgumentException::class.java) { DraftTarget.Recipe("../fuera") }
    }

    @Test
    fun `una cuenta distinta del mismo servidor no ve los borradores`() {
        val store = draftStore()
        val otraCuenta = OfflineFixtures.scopeAOtraCuenta()
        store.save(otraCuenta, nueva, OfflineFixtures.draft())

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
        assertTrue(store.load(otraCuenta, nueva) is OfflineRead.Hit)
    }

    @Test
    fun `un indice de otro ambito se descarta`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft())
        val indexA = BlobName.index(BlobPrefix.DRAFT_INDEX, scope)
        val indexB = BlobName.index(BlobPrefix.DRAFT_INDEX, OfflineFixtures.scopeB())

        fake.blobs[indexB] = fake.blobs.getValue(indexA).copyOf()

        assertTrue(store.load(OfflineFixtures.scopeB(), nueva) is OfflineRead.Miss)
        assertFalse(fake.blobs.containsKey(indexB))
        assertTrue(store.load(scope, nueva) is OfflineRead.Hit)
    }

    @Test
    fun `un blob movido a otra clave se rechaza`() {
        val store = draftStore()
        store.save(scope, nueva, OfflineFixtures.draft(name = "Nueva"))
        store.save(scope, receta, OfflineFixtures.draft(name = "Receta"))
        val keyNueva = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, nueva.id)!!
        val keyReceta = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, receta.id)!!
        val blobNueva = fake.blobs.getValue(keyNueva)
        fake.blobs[keyNueva] = fake.blobs.getValue(keyReceta)
        fake.blobs[keyReceta] = blobNueva

        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
        assertTrue(store.load(scope, receta) is OfflineRead.Miss)
    }

    @Test
    fun `al guardar se borran los caducados`() {
        val store = draftStore(ttlMillis = 1_000L)
        val keyNueva = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, nueva.id)!!
        store.save(scope, nueva, OfflineFixtures.draft())

        clock += 2_000
        store.save(scope, receta, OfflineFixtures.draft())

        assertFalse(fake.blobs.containsKey(keyNueva))
        assertTrue(store.load(scope, nueva) is OfflineRead.Miss)
        assertTrue(store.load(scope, receta) is OfflineRead.Hit)
    }

    @Test
    fun `al guardar se borran los blobs huerfanos`() {
        val store = draftStore()
        val huerfano = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, "fantasma")!!
        fake.blobs[huerfano] = "basura".toByteArray(Charsets.UTF_8)

        assertEquals(OfflineWrite.Saved, store.save(scope, nueva, OfflineFixtures.draft()))

        assertFalse(fake.blobs.containsKey(huerfano))
        assertTrue(store.load(scope, nueva) is OfflineRead.Hit)
    }

    @Test
    fun `clear vacia mas de 4096 entradas`() {
        val store = draftStore()
        val total = 4_100
        for (i in 0 until total) {
            val hash = i.toString(16).padStart(32, '0')
            fake.blobs[BlobPrefix.DRAFT_ENTRY + scope.id + "." + hash] = byteArrayOf(1)
        }

        assertEquals(OfflineWrite.Saved, store.clear(scope))

        assertTrue(fake.blobs.keys.none { it.startsWith(BlobPrefix.DRAFT_ENTRY) })
    }

    @Test
    fun `los resultados no arrastran contenido en toString`() {
        val store = draftStore()
        store.save(scope, receta, OfflineFixtures.draft())
        val result = store.load(scope, receta)

        assertEquals("Hit", result.toString())
        assertFalse(result.hitOrFail().toString().contains("Tortilla"))
        assertFalse(store.list(scope).itemsOrFail().first().toString().contains("tortilla"))
        assertFalse(receta.toString().contains("tortilla"))
    }
}
