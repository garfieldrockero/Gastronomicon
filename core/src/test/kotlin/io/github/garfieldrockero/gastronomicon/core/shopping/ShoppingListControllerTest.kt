package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val LIST_UUID = "11111111-1111-4111-8111-111111111111"
private const val NEW_UUID = "55555555-5555-4555-8555-555555555555"
private const val OTHER_UUID = "66666666-6666-4666-8666-666666666666"

/**
 * Pruebas del estado de la compra con una fuente falsa: no tocan la red.
 * Cubren la carga, el marcado optimista con reversión, el rechazo local de
 * textos, añadir sin duplicar, borrar y las carreras entre escrituras y
 * recargas (la fase nunca se queda en «cargando»).
 */
class ShoppingListControllerTest {

    private val id1 = "22222222-2222-4222-8222-222222222222"
    private val id2 = "33333333-3333-4333-8333-333333333333"
    private val id3 = "44444444-4444-4444-8444-444444444444"

    private fun item(
        id: String,
        note: String = "Leche",
        checked: Boolean = false,
        position: Int = 0,
    ): ShoppingItem = ShoppingItem(
        id = id,
        shoppingListId = LIST_UUID,
        note = note,
        quantity = 0.0,
        unitSingular = "",
        unitPlural = "",
        foodSingular = "",
        foodPlural = "",
        labelId = null,
        labelName = "",
        checked = checked,
        position = position,
    )

    /** Fuente falsa: apunta las llamadas y deja fijar cada resultado. */
    private class FakeSource(var list: ShoppingList) : ShoppingListSource {
        var getResult: ApiResult<ShoppingList>? = null
        var addResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var setResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var editResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var deleteCheckedResult: ApiResult<Unit> = ApiResult.Success(Unit)
        // Fallos de red que la fuente lanza en vez de devolver un resultado:
        // crear el cliente puede fallar antes de que exista cliente.
        var getError: NetworkError? = null
        var addError: NetworkError? = null
        var setError: NetworkError? = null
        var editError: NetworkError? = null
        var deleteError: NetworkError? = null
        var deleteCheckedError: NetworkError? = null
        var onGet: (() -> Unit)? = null
        var onAdd: (() -> Unit)? = null
        var onSetChecked: (() -> Unit)? = null
        var onDeleteChecked: (() -> Unit)? = null
        /** Listas que devuelve [getList] por id; si no está, [list]. */
        val lists = mutableMapOf<String, ShoppingList>()
        var setCheckedCalls = 0
        var deleteCheckedCalls = 0
        var deleted: List<ShoppingItem> = emptyList()
        val calls = mutableListOf<String>()

        override fun getList(listId: String): ApiResult<ShoppingList> {
            calls += "get"
            getError?.let { throw it }
            onGet?.invoke()
            return getResult ?: ApiResult.Success(lists[listId] ?: list)
        }

        override fun addItem(listId: String, text: String): ApiResult<Unit> {
            calls += "add:$text"
            addError?.let { throw it }
            onAdd?.invoke()
            if (addResult is ApiResult.Success) {
                list = list.copy(items = list.items + newItem(text))
            }
            return addResult
        }

        override fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit> {
            calls += "check:$checked"
            setCheckedCalls++
            setError?.let { throw it }
            onSetChecked?.invoke()
            return setResult
        }

        override fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit> {
            calls += "edit:$note:$quantity"
            editError?.let { throw it }
            return editResult
        }

        override fun deleteItem(itemId: String): ApiResult<Unit> {
            calls += "delete:$itemId"
            deleteError?.let { throw it }
            return deleteResult
        }

        override fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit> {
            calls += "deleteChecked:${items.size}"
            deleteCheckedCalls++
            deleted = items
            deleteCheckedError?.let { throw it }
            onDeleteChecked?.invoke()
            return deleteCheckedResult
        }

        private fun newItem(note: String): ShoppingItem = ShoppingItem(
            id = NEW_UUID,
            shoppingListId = LIST_UUID,
            note = note,
            quantity = 0.0,
            unitSingular = "",
            unitPlural = "",
            foodSingular = "",
            foodPlural = "",
            labelId = null,
            labelName = "",
            checked = false,
            position = 0,
        )
    }

    private fun source(items: List<ShoppingItem> = emptyList()): FakeSource =
        FakeSource(ShoppingList(LIST_UUID, "Compra", items))

    @Test
    fun `abrir carga la lista y la deja lista para pintar`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        val state = controller.open(LIST_UUID)
        assertEquals("Compra", state.listName)
        assertEquals(1, state.items.size)
        assertEquals(1, state.pendingCount)
        assertTrue(state.loadedOnce)
        assertFalse(state.isLoading)
        assertEquals(listOf("get"), fake.calls)
    }

    @Test
    fun `un id de lista invalido no llega a la fuente`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        val state = controller.open("../../fuera")
        assertEquals(ShoppingProblem.NotFound, state.problem)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `un 401 deja el problema tipado y la lista vacia`() {
        val fake = source()
        fake.getResult = ApiResult.Unauthorized
        val state = ShoppingListController(fake).open(LIST_UUID)
        assertEquals(ShoppingProblem.Unauthorized, state.problem)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `retry repite la carga que fallo`() {
        val fake = source(listOf(item(id1)))
        fake.getResult = ApiResult.Failure(ConnectionProblem.NoNetwork)
        val controller = ShoppingListController(fake)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.NoNetwork), controller.open(LIST_UUID).problem)

        fake.getResult = null
        val state = controller.retry()
        assertEquals(1, state.items.size)
        assertNull(state.problem)
    }

    @Test
    fun `marcar es optimista y se confirma`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        var vistoEnLaLlamada: Boolean? = null
        fake.onSetChecked = { vistoEnLaLlamada = controller.state.items.single().checked }

        val state = controller.toggle(id1)
        assertEquals(true, vistoEnLaLlamada)
        assertTrue(state.items.single().checked)
        assertTrue(state.busyItemIds.isEmpty())
        assertNull(state.problem)
    }

    @Test
    fun `si el marcado falla se revierte`() {
        val fake = source(listOf(item(id1)))
        fake.setResult = ApiResult.Failure(ConnectionProblem.NoNetwork)
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)

        val state = controller.toggle(id1)
        assertFalse(state.items.single().checked)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.NoNetwork), state.problem)
        assertTrue(state.busyItemIds.isEmpty())
    }

    @Test
    fun `un segundo toque mientras se guarda no lanza otra escritura`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.onSetChecked = { controller.toggle(id1) }

        val state = controller.toggle(id1)
        assertEquals(1, fake.setCheckedCalls)
        assertTrue(state.items.single().checked)
    }

    @Test
    fun `anadir relee la lista y no duplica`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        val state = controller.add("Leche")
        assertEquals(listOf("add:Leche", "get"), fake.calls)
        assertEquals(1, state.items.size)
        assertEquals("Leche", state.items.single().note)
        assertEquals(NEW_UUID, state.items.single().id)
    }

    @Test
    fun `anadir con resultado dudoso relee y avisa`() {
        val fake = source()
        fake.addResult = ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding)
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        val state = controller.add("Leche")

        // Se relee para saber si el elemento llegó a crearse, pero queda el aviso.
        assertEquals(listOf("add:Leche", "get"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `un texto vacio o demasiado largo no sale de la app`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        assertEquals(
            ShoppingProblem.InputRejected(ShoppingInputProblem.EmptyText),
            controller.add("   ").problem,
        )
        assertEquals(
            ShoppingProblem.InputRejected(ShoppingInputProblem.TextTooLong),
            controller.add("a".repeat(ShoppingText.MAX_ITEM_TEXT + 1)).problem,
        )
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `editar cambia nota y cantidad y la linea se recompone`() {
        val fake = source(listOf(item(id1, note = "Leche")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)

        val state = controller.editNote(id1, "Leche entera", 2.0)
        assertEquals("Leche entera", state.items.single().note)
        assertEquals(2.0, state.items.single().quantity, 0.0)
        assertEquals("2 Leche entera", state.view.sections.single().lines.single().text)
        assertNull(state.problem)
    }

    @Test
    fun `una cantidad invalida no sale de la app`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        val state = controller.editNote(id1, "Leche", -1.0)
        assertEquals(ShoppingProblem.InputRejected(ShoppingInputProblem.InvalidQuantity), state.problem)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `borrar quita el elemento solo si el servidor lo acepta`() {
        val fake = source(listOf(item(id1), item(id2, note = "Pan")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)

        val borrado = controller.delete(id1)
        assertEquals(1, borrado.items.size)
        assertEquals(id2, borrado.items.single().id)

        fake.deleteResult = ApiResult.Failure(ConnectionProblem.NoNetwork)
        val fallido = controller.delete(id2)
        assertEquals(1, fallido.items.size)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.NoNetwork), fallido.problem)
        assertTrue(fallido.busyItemIds.isEmpty())
    }

    @Test
    fun `borrar un elemento que el servidor ya no tiene lo quita sin aviso`() {
        val fake = source(listOf(item(id1), item(id2, note = "Pan")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.deleteResult = ApiResult.NotFound

        val state = controller.delete(id1)

        // Un 404 es «ya no existe»: seguir enseñándolo no tiene sentido.
        assertEquals(listOf(id2), state.items.map { it.id })
        assertNull(state.problem)
        assertTrue(state.busyItemIds.isEmpty())
    }

    @Test
    fun `borrar los marcados quita solo los que habia al empezar`() {
        val fake = source(
            listOf(
                item(id1, checked = true),
                item(id2, note = "Pan", checked = true),
                item(id3, note = "Servilletas"),
            ),
        )
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)

        val state = controller.deleteChecked()
        assertEquals(2, fake.deleted.size)
        assertEquals(1, state.items.size)
        assertEquals(id3, state.items.single().id)
        assertFalse(state.clearingChecked)
        assertTrue(state.clearingItemIds.isEmpty())
    }

    @Test
    fun `sin marcados no se llama a borrar`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        controller.deleteChecked()
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `editar un elemento que ya no esta no llama a la fuente`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        val state = controller.editNote(id2, "Pan", 1.0)
        assertEquals(1, state.items.size)
        assertTrue(fake.calls.isEmpty())
    }

    // --------------------------------------------------- carreras con recargas

    @Test
    fun `borrar durante una recarga no deja la lista cargando`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()
        var duranteRecarga: ShoppingListState? = null
        fake.onGet = { duranteRecarga = controller.delete(id1) }

        val state = controller.refresh()

        // La escritura no se envía (la carga manda) y la fase vuelve a Idle.
        assertEquals(listOf("get"), fake.calls)
        assertTrue(duranteRecarga!!.isLoading)
        assertFalse(state.isLoading)
        assertEquals(1, state.items.size)
    }

    @Test
    fun `anadir durante una recarga no deja la lista cargando`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()
        fake.onGet = { controller.add("Pan") }

        val state = controller.refresh()

        assertEquals(listOf("get"), fake.calls)
        assertFalse(state.isLoading)
        assertFalse(state.adding)
    }

    @Test
    fun `borrar los marcados no empieza con una escritura en vuelo`() {
        val fake = source(listOf(item(id1), item(id2, note = "Pan")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        var duranteElMarcado: ShoppingListState? = null
        fake.onSetChecked = { duranteElMarcado = controller.deleteChecked() }

        val state = controller.toggle(id1)

        assertEquals(0, fake.deleteCheckedCalls)
        assertTrue(fake.deleted.isEmpty())
        assertFalse(duranteElMarcado!!.clearingChecked)
        assertEquals(2, state.items.size)
        assertTrue(state.items.first { it.id == id1 }.checked)
    }

    @Test
    fun `mientras se borran los marcados no se acepta otra escritura`() {
        val fake = source(
            listOf(
                item(id1, checked = true),
                item(id2, note = "Pan", checked = true),
                item(id3, note = "Servilletas"),
            ),
        )
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        var lote: Set<String> = emptySet()
        var ocupados: Set<String> = emptySet()
        var contador = -1
        var duranteElBorrado: ShoppingListState? = null
        fake.onDeleteChecked = {
            lote = controller.state.clearingItemIds
            ocupados = controller.state.busyItemIds
            contador = controller.state.clearingCount
            // Las tres escrituras tienen que rebotar mientras dura el lote.
            controller.toggle(id3)
            controller.editNote(id3, "Otra", 1.0)
            controller.delete(id3)
            duranteElBorrado = controller.state
        }

        val state = controller.deleteChecked()

        assertEquals(setOf(id1, id2), lote)
        assertTrue(ocupados.containsAll(lote))
        assertEquals(2, contador)
        assertTrue(duranteElBorrado!!.clearingChecked)
        // Solo se ha borrado el lote: ni toggle, ni edición, ni borrado de id3.
        assertEquals(listOf("deleteChecked:2"), fake.calls)
        assertFalse(state.clearingChecked)
        assertTrue(state.clearingItemIds.isEmpty())
        assertTrue(state.busyItemIds.isEmpty())
        assertEquals(1, state.items.size)
        assertEquals(id3, state.items.single().id)
        assertFalse(state.items.single().checked)
    }

    @Test
    fun `abrir otra lista durante un POST no la pisa`() {
        val fake = source(listOf(item(id1)))
        fake.lists[OTHER_UUID] = ShoppingList(OTHER_UUID, "Otra", listOf(item(id3, note = "Otra cosa")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.onAdd = { controller.open(OTHER_UUID) }

        val state = controller.add("Leche")

        assertEquals(OTHER_UUID, state.listId)
        assertEquals("Otra", state.listName)
        assertEquals(1, state.items.size)
        assertFalse(state.isLoading)
    }

    @Test
    fun `abrir otra lista durante un marcado no la pisa`() {
        val fake = source(listOf(item(id1)))
        fake.lists[OTHER_UUID] = ShoppingList(OTHER_UUID, "Otra", listOf(item(id3, note = "Otra cosa")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.onSetChecked = { controller.open(OTHER_UUID) }

        val state = controller.toggle(id1)

        // El PUT salió, pero su resultado se descarta porque ya hay otra lista abierta.
        assertEquals(1, fake.setCheckedCalls)
        assertEquals(OTHER_UUID, state.listId)
        assertEquals(id3, state.items.single().id)
        assertFalse(state.isLoading)
    }

    @Test
    fun `un fallo al borrar los marcados relee la lista y conserva el aviso`() {
        val fake = source(listOf(item(id1, checked = true), item(id2, note = "Pan")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()
        fake.deleteCheckedResult = ApiResult.Failure(ConnectionProblem.NoNetwork)
        // El servidor sí borró el primero antes de fallar el resto.
        fake.list = ShoppingList(LIST_UUID, "Compra", listOf(item(id2, note = "Pan")))

        val state = controller.deleteChecked()

        assertEquals(listOf("deleteChecked:1", "get"), fake.calls)
        assertEquals(1, state.items.size)
        assertEquals(id2, state.items.single().id)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.NoNetwork), state.problem)
        assertFalse(state.clearingChecked)
        assertTrue(state.clearingItemIds.isEmpty())
    }

    // ------------------------------------------------- ronda 2 de la revisión

    @Test
    fun `anadir no pisa la carga de otra lista que se abre con el POST en vuelo`() {
        val fake = source(listOf(item(id1)))
        fake.lists[OTHER_UUID] = ShoppingList(OTHER_UUID, "Otra", listOf(item(id3, note = "Otra cosa")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)

        val postEnVuelo = CountDownLatch(1)
        val soltarPost = CountDownLatch(1)
        val getDeBEnVuelo = CountDownLatch(1)
        val soltarGetDeB = CountDownLatch(1)
        val lecturas = AtomicInteger(0)
        fake.onAdd = {
            postEnVuelo.countDown()
            soltarPost.await(5, TimeUnit.SECONDS)
        }
        // Solo la primera lectura (la de B) espera; si add relee A, no se bloquea.
        fake.onGet = {
            if (lecturas.getAndIncrement() == 0) {
                getDeBEnVuelo.countDown()
                soltarGetDeB.await(5, TimeUnit.SECONDS)
            }
        }

        var fallo: Throwable? = null
        val hiloAdd = thread {
            try {
                controller.add("Leche")
            } catch (e: Throwable) {
                fallo = e
            }
        }
        assertTrue(postEnVuelo.await(5, TimeUnit.SECONDS))
        val hiloOpen = thread {
            try {
                controller.open(OTHER_UUID)
            } catch (e: Throwable) {
                fallo = e
            }
        }
        // B está cargando (su getList en vuelo) cuando el POST de A termina.
        assertTrue(getDeBEnVuelo.await(5, TimeUnit.SECONDS))
        soltarPost.countDown()
        hiloAdd.join(5_000)
        // Si el join venciera, la prueba seguiría con el POST en vuelo y el
        // estado podría cambiar después: el hilo tiene que haber terminado.
        assertFalse(hiloAdd.isAlive)
        soltarGetDeB.countDown()
        hiloOpen.join(5_000)

        assertNull(fallo)
        val state = controller.state
        assertEquals(OTHER_UUID, state.listId)
        assertEquals("Otra", state.listName)
        assertEquals(listOf(id3), state.items.map { it.id })
        assertTrue(state.loadedOnce)
        assertFalse(state.isLoading)
        assertNull(state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `una lectura tras cerrar la lista no la reabre ni toca la fase`() {
        val fake = source(listOf(item(id1)))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.onAdd = { controller.close() }

        val state = controller.add("Leche")

        assertNull(state.listId)
        assertEquals(ShoppingPhase.Idle, state.phase)
        assertTrue(state.items.isEmpty())
        // No hubo segunda lectura de una lista que ya no está abierta.
        assertEquals(listOf("get", "add:Leche"), fake.calls)
    }

    @Test
    fun `no se anade mientras se borran los marcados`() {
        val fake = source(listOf(item(id1, checked = true), item(id2, note = "Pan")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()
        var duranteElBorrado: ShoppingListState? = null
        fake.onDeleteChecked = { duranteElBorrado = controller.add("Leche") }

        val state = controller.deleteChecked()

        assertTrue(duranteElBorrado!!.clearingChecked)
        assertFalse(duranteElBorrado!!.adding)
        assertEquals(listOf("deleteChecked:1"), fake.calls)
        assertEquals(listOf(id2), state.items.map { it.id })
    }

    @Test
    fun `revertir un marcado no pisa la nota que trajo una recarga`() {
        val fake = source(listOf(item(id1, note = "Leche")))
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.setResult = ApiResult.Failure(ConnectionProblem.NoNetwork)
        // Durante el PUT otra sesión edita la nota y la marca; una recarga lo trae.
        fake.onSetChecked = {
            fake.list = ShoppingList(LIST_UUID, "Compra", listOf(item(id1, note = "Leche entera", checked = true)))
            controller.refresh()
        }

        val state = controller.toggle(id1)

        val elemento = state.items.single()
        assertEquals("Leche entera", elemento.note)
        assertFalse(elemento.checked)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.NoNetwork), state.problem)
        assertTrue(state.busyItemIds.isEmpty())
    }

    // --------------------------- fallos de red que lanza la propia fuente

    @Test
    fun `un 401 de red al abrir la lista no se escapa`() {
        val fake = source()
        fake.getError = NetworkError.HttpError(401)

        val state = ShoppingListController(fake).open(LIST_UUID)

        assertEquals(ShoppingProblem.Unauthorized, state.problem)
        assertFalse(state.isLoading)
        assertFalse(state.loadedOnce)
    }

    @Test
    fun `un 403 de red al marcar se revierte`() {
        val fake = source(listOf(item(id1)))
        fake.setError = NetworkError.HttpError(403)
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)

        val state = controller.toggle(id1)

        assertFalse(state.items.single().checked)
        assertEquals(ShoppingProblem.Unauthorized, state.problem)
        assertTrue(state.busyItemIds.isEmpty())
    }

    @Test
    fun `un fallo de red al anadir cierra el estado y no relee`() {
        val fake = source()
        fake.addError = NetworkError.Timeout
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        val state = controller.add("Leche")

        // Sin «después del envío» no hay duda: no se vuelve a leer.
        assertEquals(listOf("add:Leche"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `un fallo despues del envio al anadir se cuenta como dudoso`() {
        val fake = source()
        fake.addError = NetworkError.AfterSend(NetworkError.Timeout)
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        val state = controller.add("Leche")

        // El elemento puede estar creado: se relee antes de que nadie repita.
        assertEquals(listOf("add:Leche", "get"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `un 404 de red al borrar los marcados relee y conserva el aviso`() {
        val fake = source(listOf(item(id1, checked = true), item(id2, note = "Pan")))
        fake.deleteCheckedError = NetworkError.HttpError(404)
        val controller = ShoppingListController(fake)
        controller.open(LIST_UUID)
        fake.calls.clear()

        val state = controller.deleteChecked()

        assertEquals(listOf("deleteChecked:1", "get"), fake.calls)
        assertEquals(ShoppingProblem.NotFound, state.problem)
        assertFalse(state.clearingChecked)
        assertTrue(state.busyItemIds.isEmpty())
        assertTrue(state.clearingItemIds.isEmpty())
    }
}
