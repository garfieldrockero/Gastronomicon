package io.github.garfieldrockero.gastronomicon.core.offline

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeCacheTest {

    private val store = FakeEncryptedBlobStore()
    private var clock = 1_000_000L
    private val scope = OfflineFixtures.scopeA()

    private fun newCache(
        maxEntries: Int = RecipeCache.DEFAULT_MAX_ENTRIES,
        maxEntryBytes: Int = RecipeCache.DEFAULT_MAX_ENTRY_BYTES,
        maxTotalBytes: Int = RecipeCache.DEFAULT_MAX_TOTAL_BYTES,
        ttlMillis: Long = RecipeCache.DEFAULT_TTL_MILLIS,
    ): RecipeCache = RecipeCache(store, maxEntries, maxEntryBytes, maxTotalBytes, ttlMillis) { clock }

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
    fun `guarda y devuelve la misma receta`() {
        val cache = newCache()
        val recipe = OfflineFixtures.recipe()

        assertEquals(OfflineWrite.Saved, cache.save(scope, recipe))
        val hit = cache.load(scope, recipe.slug).hitOrFail()

        assertEquals(recipe, hit.recipe)
        assertEquals(clock, hit.cachedAtMillis)
    }

    @Test
    fun `sin nada guardado devuelve Miss`() {
        assertTrue(newCache().load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `guardar dos veces el mismo slug actualiza la receta`() {
        val cache = newCache()
        val primera = OfflineFixtures.recipe(name = "Tortilla")
        val segunda = OfflineFixtures.recipe(name = "Tortilla de patatas")

        cache.save(scope, primera)
        clock += 10
        cache.save(scope, segunda)

        assertEquals("Tortilla de patatas", cache.load(scope, segunda.slug).hitOrFail().recipe.name)
        assertEquals(1, cache.list(scope).itemsOrFail().size)
    }

    @Test
    fun `una receta de otro servidor no se mezcla`() {
        val cache = newCache()
        cache.save(OfflineFixtures.scopeA(), OfflineFixtures.recipe())

        assertTrue(cache.load(OfflineFixtures.scopeB(), OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `otro slug no devuelve la receta`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())

        assertTrue(cache.load(scope, OfflineFixtures.OTRO_SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `un slug invalido no se guarda`() {
        val cache = newCache()

        assertEquals(OfflineWrite.Rejected, cache.save(scope, OfflineFixtures.recipe(slug = "../fuera")))
        assertTrue(store.blobs.isEmpty())
    }

    // ------------------------------------------------------------ caducidad

    @Test
    fun `una entrada caducada no se sirve y se borra`() {
        val cache = newCache(ttlMillis = 1_000L)
        cache.save(scope, OfflineFixtures.recipe())

        clock += 1_001

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
        assertFalse(store.blobs.keys.any { it.startsWith("rc1.") })
    }

    @Test
    fun `justo antes de la caducidad se sirve`() {
        val cache = newCache(ttlMillis = 1_000L)
        cache.save(scope, OfflineFixtures.recipe())

        clock += 1_000

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Hit)
    }

    @Test
    fun `una marca de tiempo futura se descarta`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())

        clock -= 10L * 60 * 1000

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    // ------------------------------------------------------------ topes

    @Test
    fun `el tope de numero expulsa la mas antigua`() {
        val cache = newCache(maxEntries = 2)
        val una = OfflineFixtures.recipe(slug = "una")
        val dos = OfflineFixtures.recipe(slug = "dos")
        val tres = OfflineFixtures.recipe(slug = "tres")

        cache.save(scope, una)
        clock++
        cache.save(scope, dos)
        clock++
        cache.save(scope, tres)

        assertTrue(cache.load(scope, "una") is OfflineRead.Miss)
        assertTrue(cache.load(scope, "dos") is OfflineRead.Hit)
        assertTrue(cache.load(scope, "tres") is OfflineRead.Hit)
    }

    @Test
    fun `el tope total expulsa la mas antigua`() {
        val completo = newCache()
        assertEquals(OfflineWrite.Saved, completo.save(scope, OfflineFixtures.recipe(slug = "una")))
        val tamano = store.blobs.entries.first { it.key.startsWith("rc1.") }.value.size

        val limitado = newCache(maxEntryBytes = tamano + 200, maxTotalBytes = tamano + 20)
        clock++
        assertEquals(OfflineWrite.Saved, limitado.save(scope, OfflineFixtures.recipe(slug = "dos")))

        assertTrue(limitado.load(scope, "una") is OfflineRead.Miss)
        assertTrue(limitado.load(scope, "dos") is OfflineRead.Hit)
    }

    @Test
    fun `una receta demasiado grande no se guarda y borra la anterior`() {
        val completo = newCache()
        assertEquals(OfflineWrite.Saved, completo.save(scope, OfflineFixtures.recipe()))

        val pequeno = newCache(maxEntryBytes = 64)
        assertEquals(OfflineWrite.TooLarge, pequeno.save(scope, OfflineFixtures.recipe()))

        assertTrue(pequeno.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
        assertFalse(store.blobs.keys.any { it.startsWith("rc1.") })
    }

    // ------------------------------------------------- datos no confiables

    @Test
    fun `un json corrupto se descarta sin lanzar`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        val key = store.entryKey()
        store.blobs[key] = "esto no es json".toByteArray(Charsets.UTF_8)

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key))
    }

    @Test
    fun `una version futura se descarta`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.tamper("rc1.") { it.with("format", JsonPrimitive(99)) }

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `un slug cambiado dentro de la entrada se descarta`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.tamper("rc1.") { it.with("slug", JsonPrimitive(OfflineFixtures.OTRO_SLUG)) }

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `un blob gigante se descarta antes de parsearlo`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.blobs[store.entryKey()] = ByteArray(RecipeCache.DEFAULT_MAX_ENTRY_BYTES + 1) { 0x20.toByte() }

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `una url de origen manipulada se cae a null`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.tamper("rc1.") { it.with("sourceUrl", JsonPrimitive("javascript:alert(1)")) }

        assertNull(cache.load(scope, OfflineFixtures.SLUG).hitOrFail().recipe.sourceUrl)
    }

    @Test
    fun `el html manipulado se vuelve a sanear`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.tamper("rc1.") { root ->
            val steps = root["steps"] as JsonArray
            val first = steps[0] as JsonObject
            val changed = first.with(
                "html",
                JsonPrimitive("<p>ok</p><script>alert(1)</script><img src=x onerror=insertar()>"),
            )
            root.with("steps", JsonArray(listOf(changed)))
        }

        val text = cache.load(scope, OfflineFixtures.SLUG).hitOrFail().recipe.steps[0].text.toPlainText()
        assertTrue(text.contains("ok"))
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("onerror"))
    }

    @Test
    fun `los caracteres de control se quitan del texto`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.tamper("rc1.") { it.with("name", JsonPrimitive("Tortilla\u202E")) }

        assertEquals("Tortilla", cache.load(scope, OfflineFixtures.SLUG).hitOrFail().recipe.name)
    }

    @Test
    fun `un texto por encima del maximo descarta la entrada`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.tamper("rc1.") { it.with("name", JsonPrimitive("x".repeat(301))) }

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `un indice corrupto se reconstruye`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.blobs[store.indexKey()] = "basura".toByteArray(Charsets.UTF_8)

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
        assertEquals(OfflineWrite.Saved, cache.save(scope, OfflineFixtures.recipe()))
        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Hit)
    }

    @Test
    fun `una entrada sin indice no se sirve`() {
        val cache = newCache()
        val recipe = OfflineFixtures.recipe()
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, recipe.slug)!!
        val bytes = RecipeCacheCodec.encode(recipe, clock, scope)!!

        store.put(key, bytes).valueOrFail()

        assertTrue(cache.load(scope, recipe.slug) is OfflineRead.Miss)
    }

    // --------------------------------------------------------- almacén roto

    @Test
    fun `si el almacen no escribe se devuelve un fallo`() {
        val cache = newCache()
        store.writeProblem = StorageProblem.Unavailable

        assertWriteFailed(StorageProblem.Unavailable, cache.save(scope, OfflineFixtures.recipe()))
    }

    @Test
    fun `si el almacen no lee se devuelve un fallo`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.readProblem = StorageProblem.Corrupt

        // Un índice que no descifra es una corrupción, no una indisponibilidad:
        // se borra y se responde Miss.
        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
    }

    @Test
    fun `si el almacen no esta disponible al leer se devuelve un fallo`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.readProblem = StorageProblem.Unavailable

        assertReadFailed(StorageProblem.Unavailable, cache.load(scope, OfflineFixtures.SLUG))
    }

    @Test
    fun `una implementacion que lanza no rompe la interfaz`() {
        val cache = newCache()
        store.throwOnGet = true

        assertReadFailed(StorageProblem.Unavailable, cache.load(scope, OfflineFixtures.SLUG))
    }

    @Test
    fun `un borrado que falla se devuelve como problema`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        store.deleteProblem = StorageProblem.Unavailable

        assertWriteFailed(StorageProblem.Unavailable, cache.clear())
    }

    // ------------------------------------------------------------ listado

    @Test
    fun `list devuelve de la mas reciente a la mas antigua y omite caducadas`() {
        val cache = newCache(ttlMillis = 1_000L)
        cache.save(scope, OfflineFixtures.recipe(slug = "una"))
        clock += 100
        cache.save(scope, OfflineFixtures.recipe(slug = "dos"))

        val items = cache.list(scope).itemsOrFail()
        assertEquals(listOf("dos", "una"), items.map { it.slug })

        clock += 1_000
        val despues = cache.list(scope).itemsOrFail()
        assertEquals(listOf("dos"), despues.map { it.slug })
    }

    @Test
    fun `clear borra todo de todos los servidores`() {
        val cache = newCache()
        cache.save(OfflineFixtures.scopeA(), OfflineFixtures.recipe())
        cache.save(OfflineFixtures.scopeB(), OfflineFixtures.recipe())

        assertEquals(OfflineWrite.Saved, cache.clear())

        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `clear de un servidor no toca el otro`() {
        val cache = newCache()
        cache.save(OfflineFixtures.scopeA(), OfflineFixtures.recipe())
        cache.save(OfflineFixtures.scopeB(), OfflineFixtures.recipe())

        cache.clear(OfflineFixtures.scopeA())

        assertTrue(cache.load(OfflineFixtures.scopeA(), OfflineFixtures.SLUG) is OfflineRead.Miss)
        assertTrue(cache.load(OfflineFixtures.scopeB(), OfflineFixtures.SLUG) is OfflineRead.Hit)
    }

    @Test
    fun `una cuenta distinta del mismo servidor no ve la cache`() {
        val cache = newCache()
        val otraCuenta = OfflineFixtures.scopeAOtraCuenta()
        cache.save(otraCuenta, OfflineFixtures.recipe())

        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Miss)
        assertTrue(cache.load(otraCuenta, OfflineFixtures.SLUG) is OfflineRead.Hit)
    }

    @Test
    fun `un indice de otro ambito se descarta`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        val indexA = BlobName.index(BlobPrefix.RECIPE_INDEX, scope)
        val indexB = BlobName.index(BlobPrefix.RECIPE_INDEX, OfflineFixtures.scopeB())

        // Copia del índice de A en la clave del índice de B: no se puede usar.
        store.blobs[indexB] = store.blobs.getValue(indexA).copyOf()

        assertTrue(cache.load(OfflineFixtures.scopeB(), OfflineFixtures.SLUG) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(indexB))
        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Hit)
    }

    @Test
    fun `un blob movido a otra clave se rechaza`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe(slug = "una"))
        clock++
        cache.save(scope, OfflineFixtures.recipe(slug = "dos"))
        val keyUna = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "una")!!
        val keyDos = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "dos")!!
        val blobUna = store.blobs.getValue(keyUna)
        store.blobs[keyUna] = store.blobs.getValue(keyDos)
        store.blobs[keyDos] = blobUna

        assertTrue(cache.load(scope, "una") is OfflineRead.Miss)
        assertTrue(cache.load(scope, "dos") is OfflineRead.Miss)
    }

    @Test
    fun `al guardar se borran las caducadas`() {
        val cache = newCache(ttlMillis = 1_000L)
        val keyUna = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "una")!!
        cache.save(scope, OfflineFixtures.recipe(slug = "una"))

        clock += 2_000
        cache.save(scope, OfflineFixtures.recipe(slug = "dos"))

        assertFalse(store.blobs.containsKey(keyUna))
        assertTrue(cache.load(scope, "una") is OfflineRead.Miss)
        assertTrue(cache.load(scope, "dos") is OfflineRead.Hit)
    }

    @Test
    fun `al guardar se borran los blobs huerfanos`() {
        val cache = newCache()
        val huerfano = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "fantasma")!!
        store.blobs[huerfano] = "basura".toByteArray(Charsets.UTF_8)

        assertEquals(OfflineWrite.Saved, cache.save(scope, OfflineFixtures.recipe()))

        assertFalse(store.blobs.containsKey(huerfano))
        assertTrue(cache.load(scope, OfflineFixtures.SLUG) is OfflineRead.Hit)
    }

    @Test
    fun `la purga de huerfanos tiene un tope por guardado`() {
        val cache = newCache()
        val huerfanos = (1..RecipeCache.MAX_PURGE_WORK + 5).map { i ->
            BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "fantasma-$i")!!
        }
        for (key in huerfanos) store.blobs[key] = byteArrayOf(1)

        cache.save(scope, OfflineFixtures.recipe())

        val restantes = huerfanos.count { store.blobs.containsKey(it) }
        assertEquals(5, restantes)
    }

    @Test
    fun `clear vacia mas de 4096 entradas`() {
        val cache = newCache()
        val total = 4_100
        for (i in 0 until total) {
            val hash = i.toString(16).padStart(32, '0')
            store.blobs[BlobPrefix.RECIPE_ENTRY + scope.id + "." + hash] = byteArrayOf(1)
        }

        assertEquals(OfflineWrite.Saved, cache.clear(scope))

        assertTrue(store.blobs.keys.none { it.startsWith(BlobPrefix.RECIPE_ENTRY) })
    }

    @Test
    fun `los resultados no arrastran contenido en toString`() {
        val cache = newCache()
        cache.save(scope, OfflineFixtures.recipe())
        val result = cache.load(scope, OfflineFixtures.SLUG)

        assertEquals("Hit", result.toString())
        assertFalse(result.hitOrFail().toString().contains(OfflineFixtures.RECIPE_NAME))
        assertFalse(cache.list(scope).toString().contains(OfflineFixtures.SLUG))
        assertFalse(cache.list(scope).itemsOrFail().first().toString().contains(OfflineFixtures.SLUG))
    }
}
