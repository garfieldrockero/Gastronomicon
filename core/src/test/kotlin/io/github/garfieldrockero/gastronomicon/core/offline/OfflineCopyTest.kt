package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Lo que decide la interfaz al enseñar una copia guardada: cuándo se puede
 * y con qué fecha, más el ámbito reconstruido al arrancar sin red.
 */
class OfflineCopyTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    // ------------------------------------------------------------ el ámbito

    @Test
    fun `el ambito guardado se reconstruye igual`() {
        val original = OfflineFixtures.scopeA()
        val reconstruido = ServerScope.fromStoredId(original.id)

        assertNotNull(reconstruido)
        assertEquals(original, reconstruido)
        assertEquals(original.id, reconstruido!!.id)
    }

    @Test
    fun `un identificador guardado con otra forma no da ambito`() {
        val id = OfflineFixtures.scopeA().id
        assertNull(ServerScope.fromStoredId(""))
        assertNull(ServerScope.fromStoredId(id.uppercase()))
        assertNull(ServerScope.fromStoredId(id.dropLast(1)))
        assertNull(ServerScope.fromStoredId(id + "0"))
        assertNull(ServerScope.fromStoredId("z".repeat(64)))
        assertNull(ServerScope.fromStoredId("con espacio" + id.drop(12)))
        assertNull(ServerScope.fromStoredId(" $id"))
        assertNull(ServerScope.fromStoredId("$id\n"))
    }

    @Test
    fun `el ambito reconstruido no enseña nada en toString`() {
        val scope = ServerScope.fromStoredId(OfflineFixtures.scopeA().id)
        assertNotNull(scope)
        assertFalse(scope!!.toString().contains(scope.id))
    }

    // --------------------------------------------------- cuándo hay copia

    @Test
    fun `los fallos de red ofrecen la copia`() {
        assertTrue(ConnectionProblem.NoNetwork.allowsOfflineCopy())
        assertTrue(ConnectionProblem.ServerNotResponding.allowsOfflineCopy())
        assertTrue(ConnectionProblem.SecureConnectionFailed.allowsOfflineCopy())
        assertTrue(ConnectionProblem.LocalHttpNeedsHomeNetwork.allowsOfflineCopy())
    }

    @Test
    fun `el resto de fallos no ofrecen la copia`() {
        // 401/403, 5xx, dirección que contesta otra cosa, configuración y lo desconocido.
        assertFalse(ConnectionProblem.Unauthorized.allowsOfflineCopy())
        assertFalse(ConnectionProblem.ServerError.allowsOfflineCopy())
        assertFalse(ConnectionProblem.InvalidAddress.allowsOfflineCopy())
        assertFalse(ConnectionProblem.NotLocalAddress.allowsOfflineCopy())
        assertFalse(ConnectionProblem.InvalidToken.allowsOfflineCopy())
        assertFalse(ConnectionProblem.Unknown.allowsOfflineCopy())
    }

    // -------------------------------------------------------------- la fecha

    @Test
    fun `una copia de hoy se lee con la hora`() {
        val now = 1_760_000_000_000L // 17-10-2025, 08:53 UTC
        val cached = now - 3_600_000L

        assertEquals("hoy a las 7:53", OfflineCopyDate.format(cached, now, utc))
    }

    @Test
    fun `una copia de ayer se dice asi`() {
        val now = 1_760_000_000_000L
        val cached = now - 24 * 3_600_000L

        assertEquals("ayer a las 8:53", OfflineCopyDate.format(cached, now, utc))
    }

    @Test
    fun `una copia mas vieja lleva el dia completo`() {
        val now = 1_760_000_000_000L
        val cached = now - 40L * 24 * 3_600_000L

        assertEquals("el 30 de agosto de 2025", OfflineCopyDate.format(cached, now, utc))
    }

    @Test
    fun `una marca imposible no da fecha`() {
        assertEquals("", OfflineCopyDate.format(0L, 1_760_000_000_000L, utc))
        assertEquals("", OfflineCopyDate.format(-5L, 1_760_000_000_000L, utc))
    }

    // ------------------------------------------------- lista sin conexión

    @Test
    fun `la lista sin conexion lleva el nombre y la fecha de cada copia`() {
        val store = FakeEncryptedBlobStore()
        var clock = 1_000_000L
        val cache = RecipeCache(store, now = { clock })
        val scope = OfflineFixtures.scopeA()
        cache.save(scope, OfflineFixtures.recipe())
        clock += 5_000L
        cache.save(scope, OfflineFixtures.recipe(slug = OfflineFixtures.OTRO_SLUG, name = "Paella"))

        val items = cache.listSummaries(scope).itemsOrFail()

        assertEquals(2, items.size)
        // De la más reciente a la más antigua.
        assertEquals("Paella", items[0].summary.name)
        assertEquals(OfflineFixtures.OTRO_SLUG, items[0].summary.slug)
        assertEquals(1_005_000L, items[0].cachedAtMillis)
        assertEquals(OfflineFixtures.RECIPE_NAME, items[1].summary.name)
        assertEquals(1_000_000L, items[1].cachedAtMillis)
    }

    @Test
    fun `sin copias la lista sale vacia`() {
        val cache = RecipeCache(FakeEncryptedBlobStore())
        assertTrue(cache.listSummaries(OfflineFixtures.scopeA()).itemsOrFail().isEmpty())
    }

    @Test
    fun `otra cuenta no ve las copias`() {
        val cache = RecipeCache(FakeEncryptedBlobStore())
        cache.save(OfflineFixtures.scopeA(), OfflineFixtures.recipe())

        assertTrue(cache.listSummaries(OfflineFixtures.scopeAOtraCuenta()).itemsOrFail().isEmpty())
    }

    @Test
    fun `la lista acata el tope`() {
        val cache = RecipeCache(FakeEncryptedBlobStore())
        val scope = OfflineFixtures.scopeA()
        cache.save(scope, OfflineFixtures.recipe())
        cache.save(scope, OfflineFixtures.recipe(slug = OfflineFixtures.OTRO_SLUG))

        assertEquals(1, cache.listSummaries(scope, max = 1).itemsOrFail().size)
        assertTrue(cache.listSummaries(scope, max = 0).itemsOrFail().isEmpty())
    }

    @Test
    fun `el resultado de la lista no lleva el nombre`() {
        val store = FakeEncryptedBlobStore()
        val cache = RecipeCache(store, now = { 1_000_000L })
        cache.save(OfflineFixtures.scopeA(), OfflineFixtures.recipe())

        val item = cache.listSummaries(OfflineFixtures.scopeA()).itemsOrFail().first()
        val text = item.toString()

        assertFalse(text.contains(OfflineFixtures.RECIPE_NAME))
        assertFalse(text.contains(OfflineFixtures.SLUG))
        assertTrue(text.contains("1000000"))
    }
}
