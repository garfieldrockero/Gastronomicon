package io.github.garfieldrockero.gastronomicon.core.widget

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.InputProblem
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingList
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Pruebas del toque del widget: `GET` fresco + `PUT` de `checked`, idempotencia
 * (un doble toque o un reintento no duplican), elemento o nota cambiados en otro
 * sitio y traducción de todos los desenlaces sin inventar estados.
 */
class ShoppingWidgetMarkTest {

    private fun freshList(
        note: String = "Leche",
        checked: Boolean = false,
        itemId: String = WidgetFixtures.ITEM_1,
        rawJson: kotlinx.serialization.json.JsonObject? = null,
    ): ShoppingList = WidgetFixtures.list(
        items = listOf(
            WidgetFixtures.item(itemId, note = note, checked = checked, rawJson = rawJson),
            WidgetFixtures.item(WidgetFixtures.ITEM_2, note = "Pan", position = 1),
        ),
    )

    private class FakeSource(var list: ShoppingList) : ShoppingListSource {
        var getResult: ApiResult<ShoppingList>? = null
        var setResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var getThrowable: Throwable? = null
        var setThrowable: Throwable? = null
        var onGet: (() -> Unit)? = null
        var onSet: (() -> Unit)? = null
        val calls = mutableListOf<String>()
        var checkedItem: ShoppingItem? = null
        var checkedValue: Boolean? = null

        override fun getList(listId: String): ApiResult<ShoppingList> {
            calls += "get:$listId"
            getThrowable?.let { throw it }
            onGet?.invoke()
            return getResult ?: ApiResult.Success(list)
        }

        override fun addItem(listId: String, text: String): ApiResult<Unit> =
            throw UnsupportedOperationException("no se usa en el widget")

        override fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit> {
            calls += "set:${item.id}:$checked"
            setThrowable?.let { throw it }
            checkedItem = item
            checkedValue = checked
            onSet?.invoke()
            return setResult
        }

        override fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit> =
            throw UnsupportedOperationException("no se usa en el widget")

        override fun deleteItem(itemId: String): ApiResult<Unit> =
            throw UnsupportedOperationException("no se usa en el widget")

        override fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit> =
            throw UnsupportedOperationException("no se usa en el widget")
    }

    private fun newMarker(source: ShoppingListSource, canWrite: Boolean = true): ShoppingWidgetMarker =
        ShoppingWidgetMarker(source, ShoppingWidgetWriteGuard { canWrite })

    // ------------------------------------------------------------- feliz

    @Test
    fun `marca con get fresco y put del elemento recien leido`() {
        val source = FakeSource(freshList(note = "Leche entera"))
        val marker = newMarker(source)

        val result = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)

        assertTrue(result is ShoppingWidgetMarkResult.Marked)
        val marked = result as ShoppingWidgetMarkResult.Marked
        assertFalse(marked.alreadyChecked)
        assertEquals("Leche entera", marked.itemText)
        assertEquals(1, marked.snapshot.pendingCount)
        assertEquals(1, marked.snapshot.checkedCount)
        assertEquals(listOf(WidgetFixtures.ITEM_2), marked.snapshot.rows.map { it.itemId })
        assertEquals(listOf("get:${WidgetFixtures.LIST_ID}", "set:${WidgetFixtures.ITEM_1}:true"), source.calls)
        assertEquals("Leche entera", source.checkedItem?.note)
        assertEquals(true, source.checkedValue)
    }

    @Test
    fun `si ya estaba marcado no manda put`() {
        val source = FakeSource(freshList(checked = true))
        val marker = newMarker(source)

        val result = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)

        assertTrue(result is ShoppingWidgetMarkResult.Marked)
        assertTrue((result as ShoppingWidgetMarkResult.Marked).alreadyChecked)
        assertEquals(listOf("get:${WidgetFixtures.LIST_ID}"), source.calls)
    }

    @Test
    fun `si el elemento ya no esta en la lista no manda put`() {
        val source = FakeSource(WidgetFixtures.list(items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_2))))
        val marker = newMarker(source)

        val result = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)

        assertTrue(result is ShoppingWidgetMarkResult.ItemGone)
        assertEquals(listOf("get:${WidgetFixtures.LIST_ID}"), source.calls)
    }

    @Test
    fun `la nota cambiada en otro sitio se envia tal cual`() {
        val raw = buildJsonObject { put("note", JsonPrimitive("nueva")) }
        val source = FakeSource(freshList(note = "nueva", rawJson = raw))
        val marker = newMarker(source)

        marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)

        // El PUT parte del objeto recién leído: no se reenvía una nota vieja.
        assertEquals("nueva", source.checkedItem?.note)
        assertSame(raw, source.checkedItem?.rawJson)
        assertEquals(true, source.checkedValue)
    }

    @Test
    fun `un put que pudo aplicarse no se reintenta solo`() {
        val source = FakeSource(freshList())
        source.setResult = ApiResult.Failure(ConnectionProblem.ServerError)
        val marker = newMarker(source)

        val result = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)

        assertEquals(ShoppingWidgetMarkResult.Unconfirmed(ConnectionProblem.ServerError), result)
        assertEquals(1, source.calls.count { it.startsWith("set:") })
    }

    @Test
    fun `una excepcion rara del put no se cuenta como enviada`() {
        val source = FakeSource(freshList())
        source.setThrowable = IllegalStateException("falló al componer el cuerpo")
        val marker = newMarker(source)

        assertEquals(
            ShoppingWidgetMarkResult.NotApplied(ConnectionProblem.Unknown),
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1),
        )
    }

    // --------------------------------------------------------- sin red

    @Test
    fun `la lista ya no esta`() {
        val source = FakeSource(freshList())
        source.getResult = ApiResult.NotFound
        val marker = newMarker(source)

        assertEquals(ShoppingWidgetMarkResult.ListGone, marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1))
        assertEquals(0, source.calls.count { it.startsWith("set:") })
    }

    @Test
    fun `sin permiso en la lectura`() {
        val source = FakeSource(freshList())
        source.getResult = ApiResult.Unauthorized
        val marker = newMarker(source)

        assertEquals(
            ShoppingWidgetMarkResult.Unauthorized,
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1),
        )
    }

    @Test
    fun `un fallo de red al leer se cuenta como no aplicado`() {
        val source = FakeSource(freshList())
        source.getThrowable = NetworkError.NoConnection
        val marker = newMarker(source)

        assertEquals(
            ShoppingWidgetMarkResult.ReadFailed(ConnectionProblem.NoNetwork),
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1),
        )
        assertEquals(0, source.calls.count { it.startsWith("set:") })
    }

    @Test
    fun `una excepcion rara al leer no se disfraza de otra cosa`() {
        val source = FakeSource(freshList())
        source.getThrowable = IllegalStateException("fallo simulado")
        val marker = newMarker(source)

        assertEquals(
            ShoppingWidgetMarkResult.ReadFailed(ConnectionProblem.Unknown),
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1),
        )
    }

    @Test
    fun `una lista distinta de la pedida no se toca`() {
        val source = FakeSource(
            WidgetFixtures.list(
                id = WidgetFixtures.OTHER_LIST_ID,
                items = listOf(WidgetFixtures.item(WidgetFixtures.ITEM_1)),
            ),
        )
        val marker = newMarker(source)

        assertEquals(ShoppingWidgetMarkResult.Invalid, marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1))
        assertEquals(0, source.calls.count { it.startsWith("set:") })
    }

    // ----------------------------------------------------------- el PUT

    @Test
    fun `un 404 del put quita la fila`() {
        val source = FakeSource(freshList())
        source.setResult = ApiResult.NotFound
        val marker = newMarker(source)

        val result = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)

        assertTrue(result is ShoppingWidgetMarkResult.ItemGone)
        val gone = result as ShoppingWidgetMarkResult.ItemGone
        assertEquals(listOf(WidgetFixtures.ITEM_2), gone.snapshot.rows.map { it.itemId })
        assertEquals(1, gone.snapshot.pendingCount)
    }

    @Test
    fun `401 en el put no cambia la lista`() {
        val source = FakeSource(freshList())
        source.setResult = ApiResult.Forbidden
        val marker = newMarker(source)

        assertEquals(
            ShoppingWidgetMarkResult.Unauthorized,
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1),
        )
    }

    @Test
    fun `un rechazo definitivo del servidor no se reintenta`() {
        val source = FakeSource(freshList())
        source.setResult = ApiResult.Invalid(emptyList())
        val marker = newMarker(source)

        assertEquals(
            ShoppingWidgetMarkResult.ServerRejected,
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1),
        )
        assertEquals(1, source.calls.count { it.startsWith("set:") })
    }

    // -------------------------------------------------- id / bloqueo / vuelo

    @Test
    fun `un id invalido no toca la red`() {
        val source = FakeSource(freshList())
        val marker = newMarker(source)

        assertEquals(ShoppingWidgetMarkResult.Invalid, marker.mark("no-es-uuid", WidgetFixtures.ITEM_1))
        assertEquals(ShoppingWidgetMarkResult.Invalid, marker.mark(WidgetFixtures.LIST_ID, "no-es-uuid"))
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `con el movil bloqueado no se hace ninguna peticion`() {
        val source = FakeSource(freshList())
        val marker = newMarker(source, canWrite = false)

        assertEquals(
            ShoppingWidgetMarkResult.Locked,
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1),
        )
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `un doble toque solo lanza una peticion`() {
        val source = FakeSource(freshList())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        source.onGet = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val marker = newMarker(source)

        var first: ShoppingWidgetMarkResult? = null
        val worker = thread { first = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val second = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)

        release.countDown()
        worker.join(5_000)

        assertEquals(ShoppingWidgetMarkResult.InFlight, second)
        assertTrue(first is ShoppingWidgetMarkResult.Marked)
        assertEquals(1, source.calls.count { it.startsWith("get:") })
        assertEquals(1, source.calls.count { it.startsWith("set:") })
    }

    @Test
    fun `tras un fallo se puede volver a tocar`() {
        val source = FakeSource(freshList())
        source.getResult = ApiResult.Failure(ConnectionProblem.NoNetwork)
        val marker = newMarker(source)

        assertTrue(marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1) is ShoppingWidgetMarkResult.ReadFailed)

        source.getResult = null
        assertTrue(marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1) is ShoppingWidgetMarkResult.Marked)
    }

    @Test
    fun `una cancelacion libera el id en vuelo`() {
        val source = FakeSource(freshList())
        source.getThrowable = CancellationException("cancelado")
        val marker = newMarker(source)

        assertThrows(CancellationException::class.java) {
            marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)
        }

        source.getThrowable = null
        assertTrue(marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1) is ShoppingWidgetMarkResult.Marked)
    }

    // ------------------------------------------------- traducción de todo

    @Test
    fun `todos los resultados de la lectura se traducen`() {
        val list = freshList()
        assertTrue(readOutcome(ApiResult.Success(list)) is WidgetRead.Loaded)
        assertEquals(WidgetRead.Unauthorized, readOutcome(ApiResult.Unauthorized))
        assertEquals(WidgetRead.Unauthorized, readOutcome(ApiResult.Forbidden))
        assertEquals(WidgetRead.Gone, readOutcome(ApiResult.NotFound))
        assertTrue(readOutcome(ApiResult.Conflict) is WidgetRead.Failed)
        assertTrue(readOutcome(ApiResult.Invalid(emptyList())) is WidgetRead.Failed)
        assertTrue(readOutcome(ApiResult.Rejected(413)) is WidgetRead.Failed)
        assertTrue(readOutcome(ApiResult.DraftInvalid(emptyList())) is WidgetRead.Failed)
        assertTrue(readOutcome(ApiResult.InputRejected(InputProblem.InvalidUrl)) is WidgetRead.Failed)
        assertTrue(readOutcome(ApiResult.SavedButNotReloaded(ConnectionProblem.Unknown)) is WidgetRead.Failed)
        assertTrue(readOutcome(ApiResult.MaybeCreated(ConnectionProblem.Unknown)) is WidgetRead.Failed)
        assertTrue(readOutcome(ApiResult.Failure(ConnectionProblem.Unknown)) is WidgetRead.Failed)
    }

    @Test
    fun `todos los resultados de la escritura se traducen`() {
        assertEquals(WidgetWrite.Applied, writeOutcome(ApiResult.Success(Unit)))
        assertEquals(WidgetWrite.Unauthorized, writeOutcome(ApiResult.Unauthorized))
        assertEquals(WidgetWrite.Unauthorized, writeOutcome(ApiResult.Forbidden))
        assertEquals(WidgetWrite.Gone, writeOutcome(ApiResult.NotFound))
        assertEquals(WidgetWrite.Rejected, writeOutcome(ApiResult.Conflict))
        assertEquals(WidgetWrite.Rejected, writeOutcome(ApiResult.Invalid(emptyList())))
        assertEquals(WidgetWrite.Rejected, writeOutcome(ApiResult.Rejected(400)))
        assertEquals(WidgetWrite.Rejected, writeOutcome(ApiResult.DraftInvalid(emptyList())))
        assertEquals(
            WidgetWrite.Rejected,
            writeOutcome(ApiResult.InputRejected(InputProblem.InvalidUrl)),
        )
        assertEquals(
            WidgetWrite.Unconfirmed(ConnectionProblem.Unknown),
            writeOutcome(ApiResult.SavedButNotReloaded(ConnectionProblem.Unknown)),
        )
        assertEquals(
            WidgetWrite.Unconfirmed(ConnectionProblem.Unknown),
            writeOutcome(ApiResult.MaybeCreated(ConnectionProblem.Unknown)),
        )
        assertEquals(
            WidgetWrite.NotApplied(ConnectionProblem.NoNetwork),
            writeOutcome(ApiResult.Failure(ConnectionProblem.NoNetwork)),
        )
    }

    @Test
    fun `un fallo del put se clasifica sin dejar huecos`() {
        val noAplicados = listOf(
            ConnectionProblem.NoNetwork,
            ConnectionProblem.ServerNotResponding,
            ConnectionProblem.InvalidAddress,
            ConnectionProblem.SecureConnectionFailed,
            ConnectionProblem.LocalHttpNeedsHomeNetwork,
            ConnectionProblem.NotLocalAddress,
        )
        for (problem in noAplicados) {
            assertTrue("$problem debería ser no aplicado", failureAfterPut(problem) is WidgetWrite.NotApplied)
        }
        val dudosos = listOf(ConnectionProblem.ServerError, ConnectionProblem.Unknown)
        for (problem in dudosos) {
            assertTrue("$problem debería ser dudoso", failureAfterPut(problem) is WidgetWrite.Unconfirmed)
        }
        assertEquals(WidgetWrite.Unauthorized, failureAfterPut(ConnectionProblem.Unauthorized))
        assertEquals(WidgetWrite.Unauthorized, failureAfterPut(ConnectionProblem.InvalidToken))
    }

    // ------------------------------------------------------------- estados

    @Test
    fun `cada desenlace tiene su linea de estado fija`() {
        assertEquals(ShoppingWidgetStatus.Saving, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.InFlight))
        assertEquals(ShoppingWidgetStatus.Locked, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.Locked))
        assertEquals(ShoppingWidgetStatus.SessionExpired, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.Unauthorized))
        assertEquals(ShoppingWidgetStatus.ListGone, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.ListGone))
        assertEquals(ShoppingWidgetStatus.ItemGone, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.ItemGone(WidgetFixtures.snapshot())))
        assertEquals(ShoppingWidgetStatus.Offline, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.ReadFailed(ConnectionProblem.NoNetwork)))
        assertEquals(ShoppingWidgetStatus.Unconfirmed, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.Unconfirmed(ConnectionProblem.ServerError)))
        assertEquals(ShoppingWidgetStatus.Rejected, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.ServerRejected))
        assertEquals(ShoppingWidgetStatus.Rejected, ShoppingWidgetStatus.of(ShoppingWidgetMarkResult.Invalid))
    }

    @Test
    fun `el aviso de marcado lleva el nombre y se redacta en toString`() {
        val source = FakeSource(freshList(note = "Medicamento"))
        val marker = newMarker(source)

        val result = marker.mark(WidgetFixtures.LIST_ID, WidgetFixtures.ITEM_1)
        val status = ShoppingWidgetStatus.of(result)

        assertEquals("En el carro: «Medicamento»", status.text)
        assertFalse(result.toString().contains("Medicamento"))
    }

    @Test
    fun `cada problema de conexion tiene su texto fijo`() {
        assertEquals(ShoppingWidgetStatus.Offline, ShoppingWidgetStatus.of(ConnectionProblem.NoNetwork))
        assertEquals(ShoppingWidgetStatus.Offline, ShoppingWidgetStatus.of(ConnectionProblem.ServerNotResponding))
        assertEquals(ShoppingWidgetStatus.NeedsHomeNetwork, ShoppingWidgetStatus.of(ConnectionProblem.LocalHttpNeedsHomeNetwork))
        assertEquals(ShoppingWidgetStatus.Settings, ShoppingWidgetStatus.of(ConnectionProblem.NotLocalAddress))
        assertEquals(ShoppingWidgetStatus.SessionExpired, ShoppingWidgetStatus.of(ConnectionProblem.Unauthorized))
        assertEquals(ShoppingWidgetStatus.SessionExpired, ShoppingWidgetStatus.of(ConnectionProblem.InvalidToken))
        assertEquals(ShoppingWidgetStatus.ServerNotResponding, ShoppingWidgetStatus.of(ConnectionProblem.ServerError))
        assertEquals(ShoppingWidgetStatus.ServerNotResponding, ShoppingWidgetStatus.of(ConnectionProblem.InvalidAddress))
        assertEquals(ShoppingWidgetStatus.ServerNotResponding, ShoppingWidgetStatus.of(ConnectionProblem.SecureConnectionFailed))
        assertEquals(ShoppingWidgetStatus.ServerNotResponding, ShoppingWidgetStatus.of(ConnectionProblem.Unknown))
    }
}
