package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetRow
import io.github.garfieldrockero.gastronomicon.core.widget.WidgetFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas del códec de la caché del widget: un blob manipulado, de otra versión
 * o de otro ámbito no se sirve nunca. Aquí se comprueba todo lo que se valida al
 * leer datos que vienen del disco.
 */
class ShoppingListCacheCodecTest {

    private val scopeA = OfflineFixtures.scopeA()
    private val scopeB = OfflineFixtures.scopeB()
    private val maxBytes = 64 * 1024

    private fun encode(
        snapshot: io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot = WidgetFixtures.snapshot(),
        scope: ServerScope = scopeA,
    ): ByteArray = requireNotNull(ShoppingListCacheCodec.encode(snapshot, scope))

    private fun decode(bytes: ByteArray, scope: ServerScope = scopeA): io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot? =
        ShoppingListCacheCodec.decode(bytes, scope, maxBytes)

    @Test
    fun `ida y vuelta conserva los campos`() {
        val snapshot = WidgetFixtures.snapshot()

        val decoded = decode(encode(snapshot))

        assertNotNull(decoded)
        assertEquals(snapshot.listId, decoded!!.listId)
        assertEquals(snapshot.listName, decoded.listName)
        assertEquals(snapshot.pendingCount, decoded.pendingCount)
        assertEquals(snapshot.checkedCount, decoded.checkedCount)
        assertEquals(snapshot.updatedAtMillis, decoded.updatedAtMillis)
        assertEquals(snapshot.rows, decoded.rows)
    }

    @Test
    fun `otra version se descarta`() {
        val cambiado = rewrite(encode()) { root -> root.con("format", JsonPrimitive(99)) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `otro ambito se descarta`() {
        assertNull(decode(encode(scope = scopeA), scopeB))
    }

    @Test
    fun `un id de lista invalido se descarta`() {
        val cambiado = rewrite(encode()) { root -> root.con("listId", JsonPrimitive("no-es-uuid")) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `menos pendientes que filas se descarta`() {
        val cambiado = rewrite(encode()) { root -> root.con("pendingCount", JsonPrimitive(0)) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `contadores negativos se descartan`() {
        val negativo = rewrite(encode()) { root -> root.con("pendingCount", JsonPrimitive(-1)) }
        val checked = rewrite(encode()) { root -> root.con("checkedCount", JsonPrimitive(-3)) }

        assertNull(decode(negativo))
        assertNull(decode(checked))
    }

    @Test
    fun `un total por encima del tope se descarta`() {
        val cambiado = rewrite(encode()) { root -> root.con("checkedCount", JsonPrimitive(2_500)) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `ids de fila repetidos se descartan`() {
        val cambiado = rewrite(encode()) { root -> root.con("items", items(root) { list ->
            list[1] = (list[1] as JsonObject).con("id", JsonPrimitive(WidgetFixtures.ITEM_1))
            list
        }) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `un id de fila invalido se descarta`() {
        val cambiado = rewrite(encode()) { root -> root.con("items", items(root) { list ->
            list[0] = (list[0] as JsonObject).con("id", JsonPrimitive("no-es-uuid"))
            list
        }) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `un texto por encima del tope se descarta`() {
        val largo = "a".repeat(241)
        val cambiado = rewrite(encode()) { root -> root.con("items", items(root) { list ->
            list[0] = (list[0] as JsonObject).con("text", JsonPrimitive(largo))
            list
        }) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `un texto con controles o saltos se descarta`() {
        val control = rewrite(encode()) { root -> root.con("items", items(root) { list ->
            list[0] = (list[0] as JsonObject).con("text", JsonPrimitive("Leche\u0000"))
            list
        }) }
        val salto = rewrite(encode()) { root -> root.con("items", items(root) { list ->
            list[0] = (list[0] as JsonObject).con("text", JsonPrimitive("Leche\nPan"))
            list
        }) }

        assertNull(decode(control))
        assertNull(decode(salto))
    }

    @Test
    fun `un nombre vacio se descarta`() {
        val cambiado = rewrite(encode()) { root -> root.con("listName", JsonPrimitive("")) }

        assertNull(decode(cambiado))
    }

    @Test
    fun `un json roto se descarta`() {
        assertNull(decode("no es json".toByteArray(Charsets.UTF_8)))
        assertNull(decode(ByteArray(0)))
    }

    @Test
    fun `un blob demasiado grande se descarta antes de parsear`() {
        assertNull(ShoppingListCacheCodec.decode(encode(), scopeA, 10))
    }

    @Test
    fun `una clave desconocida no rompe la lectura`() {
        val cambiado = rewrite(encode()) { root ->
            root.con("campoDeOtraVersion", JsonPrimitive(true))
                .con("items", items(root) { list ->
                    list[0] = (list[0] as JsonObject).con("extra", JsonPrimitive(7))
                    list
                })
        }

        assertNotNull(decode(cambiado))
    }

    @Test
    fun `el toString no lleva el nombre ni los textos`() {
        val snapshot = WidgetFixtures.snapshot(
            listName = "Compra secreta",
            rows = listOf(ShoppingWidgetRow(WidgetFixtures.ITEM_1, "Medicamento")),
            pendingCount = 1,
            checkedCount = 0,
        )

        val texto = snapshot.toString()

        assertFalse(texto.contains("Compra secreta"))
        assertFalse(texto.contains("Medicamento"))
        assertTrue(texto.contains("pending=1"))
    }

    // --------------------------------------------------------------- encode

    @Test
    fun `no se codifica una instantanea con datos que no cuadran`() {
        assertNull(ShoppingListCacheCodec.encode(WidgetFixtures.snapshot(listId = "no-es-uuid"), scopeA))
        assertNull(ShoppingListCacheCodec.encode(WidgetFixtures.snapshot(updatedAtMillis = 0), scopeA))
        assertNull(ShoppingListCacheCodec.encode(WidgetFixtures.snapshot(listName = ""), scopeA))
        assertNull(
            ShoppingListCacheCodec.encode(
                WidgetFixtures.snapshot(pendingCount = 0),
                scopeA,
            ),
        )
    }

    @Test
    fun `no se codifican filas repetidas ni textos sin sanear`() {
        val repetidas = WidgetFixtures.snapshot(
            pendingCount = 2,
            checkedCount = 0,
            rows = listOf(
                ShoppingWidgetRow(WidgetFixtures.ITEM_1, "Leche"),
                ShoppingWidgetRow(WidgetFixtures.ITEM_1, "Pan"),
            ),
        )
        val sinSanear = WidgetFixtures.snapshot(
            pendingCount = 1,
            checkedCount = 0,
            rows = listOf(ShoppingWidgetRow(WidgetFixtures.ITEM_1, "Leche  con   huecos")),
        )
        val invalida = WidgetFixtures.snapshot(
            pendingCount = 1,
            checkedCount = 0,
            rows = listOf(ShoppingWidgetRow("no-es-uuid", "Leche")),
        )

        assertNull(ShoppingListCacheCodec.encode(repetidas, scopeA))
        assertNull(ShoppingListCacheCodec.encode(sinSanear, scopeA))
        assertNull(ShoppingListCacheCodec.encode(invalida, scopeA))
    }

    // ------------------------------------------------------------ utilidades

    private fun rewrite(bytes: ByteArray, transform: (JsonObject) -> JsonObject): ByteArray {
        val root = CoreJson.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        return CoreJson.json.encodeToString(JsonObject.serializer(), transform(root))
            .toByteArray(Charsets.UTF_8)
    }

    private fun items(root: JsonObject, transform: (MutableList<JsonElement>) -> List<JsonElement>): JsonArray =
        JsonArray(transform((root["items"] as JsonArray).toMutableList()))
}

/** Copia del objeto con un campo más, sin depender de ayudas de otras pruebas. */
private fun JsonObject.con(key: String, value: JsonElement): JsonObject =
    JsonObject(this + (key to value))
