package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.html.HtmlBlock
import io.github.garfieldrockero.gastronomicon.core.html.HtmlSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.InlineSpan
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCodecTest {

    private val scope = OfflineFixtures.scopeA()
    private val otroAmbito = OfflineFixtures.scopeB()

    private val maxBytes = 1024 * 1024

    // ----------------------------------------------------------- HTML

    @Test
    fun `el html escrito se vuelve a leer igual`() {
        val original = HtmlSanitizer.sanitize(
            "<p><b>Uno</b> y <i>dos</i>.<br>Tres</p><ul><li>Cuatro<ul><li>Cinco</li></ul></li></ul>",
        )

        val html = HtmlWriter.toHtml(original)!!
        assertEquals(original, HtmlSanitizer.sanitize(html))
    }

    @Test
    fun `el texto se escapa y no se convierte en etiqueta`() {
        val original = SanitizedHtml(
            listOf(HtmlBlock.Paragraph(listOf(InlineSpan.Text("<script>alert(1)</script>")))),
        )

        val html = HtmlWriter.toHtml(original)!!
        assertFalse(html.contains("<script>"))
        assertEquals(original, HtmlSanitizer.sanitize(html))
    }

    @Test
    fun `un html que no cabe en el tope no se guarda`() {
        val original = SanitizedHtml(
            listOf(HtmlBlock.Paragraph(listOf(InlineSpan.Text("x".repeat(500))))),
        )

        assertNull(HtmlWriter.toHtml(original, limit = 10))
    }

    // ------------------------------------------------------- recetas

    @Test
    fun `una receta se guarda y se lee igual`() {
        val recipe = OfflineFixtures.recipe()
        val bytes = RecipeCacheCodec.encode(recipe, 1_000L, scope)!!

        val cached = RecipeCacheCodec.decode(bytes, scope, recipe.slug, maxBytes)!!

        assertEquals(recipe, cached.recipe)
        assertEquals(1_000L, cached.cachedAtMillis)
    }

    @Test
    fun `una version distinta se rechaza`() {
        val recipe = OfflineFixtures.recipe()
        val bytes = RecipeCacheCodec.encode(recipe, 1_000L, scope)!!
        val cambiado = tamperJson(bytes) { it.with("format", JsonPrimitive(99)) }

        assertNull(RecipeCacheCodec.decode(cambiado, scope, recipe.slug, maxBytes))
    }

    @Test
    fun `un slug distinto del esperado se rechaza`() {
        val recipe = OfflineFixtures.recipe()
        val bytes = RecipeCacheCodec.encode(recipe, 1_000L, scope)!!

        assertNull(RecipeCacheCodec.decode(bytes, scope, OfflineFixtures.OTRO_SLUG, maxBytes))
    }

    @Test
    fun `una receta de otro ambito se rechaza`() {
        val recipe = OfflineFixtures.recipe()
        val bytes = RecipeCacheCodec.encode(recipe, 1_000L, scope)!!

        assertNull(RecipeCacheCodec.decode(bytes, otroAmbito, recipe.slug, maxBytes))
    }

    @Test
    fun `un blob por encima del tope no se parsea`() {
        val recipe = OfflineFixtures.recipe()
        val bytes = RecipeCacheCodec.encode(recipe, 1_000L, scope)!!

        assertNull(RecipeCacheCodec.decode(bytes, scope, recipe.slug, 10))
    }

    @Test
    fun `el html guardado se vuelve a sanear al leer`() {
        val recipe = OfflineFixtures.recipe()
        val bytes = RecipeCacheCodec.encode(recipe, 1_000L, scope)!!
        val cambiado = tamperJson(bytes) { root ->
            val steps = root["steps"] as JsonArray
            val first = steps[0] as JsonObject
            val conScript = first.with(
                "html",
                JsonPrimitive("<p>ok</p><script>alert(1)</script><iframe src=x></iframe>"),
            )
            root.with("steps", JsonArray(listOf<JsonElement>(conScript)))
        }

        val cached = RecipeCacheCodec.decode(cambiado, scope, recipe.slug, maxBytes)!!
        val text = cached.recipe.steps[0].text.toPlainText()
        assertEquals("ok", text)
    }

    // ------------------------------------------------------ borradores

    @Test
    fun `un borrador se guarda y se lee igual`() {
        val draft = OfflineFixtures.draft()
        val target = DraftTarget.New
        val bytes = DraftStoreCodec.encode(draft, 1_000L, scope, target)!!

        val cached = DraftStoreCodec.decode(bytes, scope, target, maxBytes)!!

        assertEquals(draft, cached.draft)
        assertEquals(1_000L, cached.savedAtMillis)
    }

    @Test
    fun `un borrador de version distinta se rechaza`() {
        val target = DraftTarget.New
        val bytes = DraftStoreCodec.encode(OfflineFixtures.draft(), 1_000L, scope, target)!!
        val cambiado = tamperJson(bytes) { it.with("format", JsonPrimitive(99)) }

        assertNull(DraftStoreCodec.decode(cambiado, scope, target, maxBytes))
    }

    @Test
    fun `un borrador de otro destino se rechaza`() {
        val bytes = DraftStoreCodec.encode(OfflineFixtures.draft(), 1_000L, scope, DraftTarget.New)!!

        assertNull(
            DraftStoreCodec.decode(
                bytes,
                scope,
                DraftTarget.Recipe(OfflineFixtures.SLUG),
                maxBytes,
            ),
        )
    }

    @Test
    fun `un borrador de otro ambito se rechaza`() {
        val target = DraftTarget.New
        val bytes = DraftStoreCodec.encode(OfflineFixtures.draft(), 1_000L, scope, target)!!

        assertNull(DraftStoreCodec.decode(bytes, otroAmbito, target, maxBytes))
    }

    @Test
    fun `el texto de un borrador manipulado se sanea y se recorta`() {
        val target = DraftTarget.New
        val bytes = DraftStoreCodec.encode(OfflineFixtures.draft(), 1_000L, scope, target)!!
        val cambiado = tamperJson(bytes) {
            it.with("name", JsonPrimitive("\u202E" + "a".repeat(400)))
        }

        val draft = DraftStoreCodec.decode(cambiado, scope, target, maxBytes)!!.draft
        assertEquals(300, draft.name.length)
        assertFalse(draft.name.contains('\u202E'))
    }

    // --------------------------------------------------------- índice

    @Test
    fun `el indice se guarda y se lee igual`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val entrada = IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)
        val bytes = OfflineIndex.encode(scope, listOf(entrada))!!

        val leidas = OfflineIndex.decode(bytes, scope, BlobPrefix.RECIPE_ENTRY) { it == "receta" }!!

        assertEquals(1, leidas.size)
        assertEquals(entrada.key, leidas[0].key)
        assertEquals(entrada.itemId, leidas[0].itemId)
        assertEquals(entrada.bytes, leidas[0].bytes)
        assertEquals(entrada.cachedAt, leidas[0].cachedAt)
    }

    @Test
    fun `un indice de otro ambito se rechaza`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)),
        )!!

        assertNull(OfflineIndex.decode(bytes, otroAmbito, BlobPrefix.RECIPE_ENTRY) { true })
    }

    @Test
    fun `un indice sin ambito se rechaza`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)),
        )!!
        val sinAmbito = tamperJson(bytes) { root ->
            JsonObject(root.filterKeys { it != "scope" })
        }

        assertNull(OfflineIndex.decode(sinAmbito, scope, BlobPrefix.RECIPE_ENTRY) { true })
    }

    @Test
    fun `un indice con clave fuera del prefijo se rechaza`() {
        val key = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)),
        )!!

        assertNull(OfflineIndex.decode(bytes, scope, BlobPrefix.RECIPE_ENTRY) { true })
    }

    @Test
    fun `un indice con la misma clave dos veces se rechaza`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(
                IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L),
                IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 2_000L),
            ),
        )!!

        assertNull(OfflineIndex.decode(bytes, scope, BlobPrefix.RECIPE_ENTRY) { true })
    }

    @Test
    fun `un indice de version futura se rechaza`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)),
        )!!
        val cambiado = tamperJson(bytes) { it.with("format", JsonPrimitive(99)) }

        assertNull(OfflineIndex.decode(cambiado, scope, BlobPrefix.RECIPE_ENTRY) { true })
    }

    @Test
    fun `un indice con el hash de la clave cambiado se rechaza`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)),
        )!!
        val cambiado = tamperEntry(bytes) { it.with("keyHash", JsonPrimitive("0".repeat(64))) }

        assertNull(OfflineIndex.decode(cambiado, scope, BlobPrefix.RECIPE_ENTRY) { true })
    }

    @Test
    fun `un indice que apunta a la clave de otra entrada se rechaza`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)),
        )!!
        val cambiado = tamperEntry(bytes) { it.with("itemId", JsonPrimitive("otra")) }

        assertNull(OfflineIndex.decode(cambiado, scope, BlobPrefix.RECIPE_ENTRY) { true })
    }

    @Test
    fun `un indice con un elemento invalido se rechaza`() {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, "receta")!!
        val bytes = OfflineIndex.encode(
            scope,
            listOf(IndexEntry(key = key, itemId = "receta", bytes = 100, cachedAt = 1_000L)),
        )!!

        assertNull(OfflineIndex.decode(bytes, scope, BlobPrefix.RECIPE_ENTRY) { false })
    }

    // --------------------------------------------------------- claves

    @Test
    fun `las claves validas son ASCII simple y sin puntos suspensivos`() {
        assertTrue(BlobKeys.isValid("rc1.abc_def-123"))
        assertTrue(BlobKeys.isValid("rci1." + "a".repeat(64)))
        assertTrue(BlobKeys.isValidPrefix("rc1."))
        assertTrue(BlobKeys.isValidPrefix("dr1."))
    }

    @Test
    fun `una clave con barra punto o espacio no vale`() {
        assertFalse(BlobKeys.isValid(""))
        assertFalse(BlobKeys.isValid("a/b"))
        assertFalse(BlobKeys.isValid("../fuera"))
        assertFalse(BlobKeys.isValid(".oculto"))
        assertFalse(BlobKeys.isValid("a b"))
        assertFalse(BlobKeys.isValid("a..b"))
        assertFalse(BlobKeys.isValid("a".repeat(BlobKeys.MAX_LENGTH + 1)))
        assertFalse(BlobKeys.isValidPrefix(""))
        assertFalse(BlobKeys.isValidPrefix("../"))
    }

    @Test
    fun `la clave de una receta no lleva el slug y es estable`() {
        val una = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, OfflineFixtures.SLUG)!!
        val otra = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, OfflineFixtures.SLUG)!!
        val otroServidor = BlobName.item(BlobPrefix.RECIPE_ENTRY, OfflineFixtures.scopeB(), OfflineFixtures.SLUG)!!
        val otraCuenta = BlobName.item(BlobPrefix.RECIPE_ENTRY, OfflineFixtures.scopeAOtraCuenta(), OfflineFixtures.SLUG)!!
        val otroSlug = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, OfflineFixtures.OTRO_SLUG)!!

        assertTrue(BlobKeys.isValid(una))
        assertEquals(una, otra)
        assertNotEquals(una, otroServidor)
        assertNotEquals(una, otraCuenta)
        assertNotEquals(una, otroSlug)
        assertFalse(una.contains(OfflineFixtures.SLUG))
        assertFalse(una.contains(OfflineFixtures.USER_A))
    }

    @Test
    fun `la clave del indice valida y cambia con el servidor y la cuenta`() {
        val una = BlobName.index(BlobPrefix.RECIPE_INDEX, scope)
        val otra = BlobName.index(BlobPrefix.RECIPE_INDEX, OfflineFixtures.scopeB())
        val otraCuenta = BlobName.index(BlobPrefix.RECIPE_INDEX, OfflineFixtures.scopeAOtraCuenta())

        assertTrue(BlobKeys.isValid(una))
        assertEquals(69, una.length)
        assertNotEquals(una, otra)
        assertNotEquals(una, otraCuenta)
    }

    // ------------------------------------------------------------ ayudas

    /** Reescribe un blob JSON como haría alguien con acceso al fichero. */
    private fun tamperJson(bytes: ByteArray, transform: (JsonObject) -> JsonObject): ByteArray {
        val root = CoreJson.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        return CoreJson.json
            .encodeToString(JsonObject.serializer(), transform(root))
            .toByteArray(Charsets.UTF_8)
    }

    /** Reescribe la primera entrada del array `entries` de un índice. */
    private fun tamperEntry(bytes: ByteArray, transform: (JsonObject) -> JsonObject): ByteArray =
        tamperJson(bytes) { root ->
            val entries = (root["entries"] as JsonArray).map { it as JsonObject }
            val first = transform(entries[0])
            root.with("entries", JsonArray(listOf<JsonElement>(first)))
        }
}
