package io.github.garfieldrockero.gastronomicon.core.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfflineWipe] es el borrado de cierre de sesión: tiene que barrer recetas y
 * borradores de todos los ámbitos sin descifrar nada, y no truncarse en el
 * primer listado.
 */
class OfflineWipeTest {

    private val store = FakeEncryptedBlobStore()
    private val scopeA = OfflineFixtures.scopeA()
    private val scopeB = OfflineFixtures.scopeB()

    @Test
    fun `clearAll borra recetas y borradores de todos los ambitos sin descifrar`() {
        val cache = RecipeCache(store)
        val drafts = DraftStore(store)
        cache.save(scopeA, OfflineFixtures.recipe())
        cache.save(scopeB, OfflineFixtures.recipe())
        drafts.save(scopeA, DraftTarget.New, OfflineFixtures.draft())
        drafts.save(scopeB, DraftTarget.Recipe(OfflineFixtures.SLUG), OfflineFixtures.draft())
        assertFalse(store.blobs.isEmpty())

        assertEquals(OfflineWrite.Saved, OfflineWipe.clearAll(store))

        assertTrue(store.blobs.isEmpty())
        assertEquals(0, store.gets)
    }

    @Test
    fun `clearAll con mas de 4096 claves las borra todas`() {
        val total = 4_100
        for (i in 0 until total) {
            val hash = i.toString(16).padStart(32, '0')
            store.blobs[BlobPrefix.RECIPE_ENTRY + scopeA.id + "." + hash] = byteArrayOf(1)
            store.blobs[BlobPrefix.DRAFT_ENTRY + scopeA.id + "." + hash] = byteArrayOf(1)
        }

        assertEquals(OfflineWrite.Saved, OfflineWipe.clearAll(store))

        assertTrue(store.blobs.isEmpty())
        assertEquals(0, store.gets)
    }

    @Test
    fun `clearAll con el almacen caido devuelve el problema`() {
        store.blobs[BlobPrefix.RECIPE_ENTRY + scopeA.id + ".abc"] = byteArrayOf(1)
        store.listProblem = StorageProblem.Unavailable

        assertTrue(OfflineWipe.clearAll(store) is OfflineWrite.Failed)
        assertEquals(1, store.blobs.size)
    }

    @Test
    fun `clearAll en un almacen vacio no falla`() {
        assertEquals(OfflineWrite.Saved, OfflineWipe.clearAll(store))
    }
}
