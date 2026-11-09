package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val LIST_A = "11111111-1111-4111-8111-111111111111"
private const val LIST_B = "22222222-2222-4222-8222-222222222222"
private const val LIST_C = "33333333-3333-4333-8333-333333333333"

/**
 * Fallo no recuperable de mentira: no es una excepción, así que ninguna capa lo
 * atrapa. Sirve para comprobar que la fase se limpia igualmente antes de que
 * suba.
 */
private class PickerFatalError(message: String) : Error(message)

private fun summary(id: String, name: String): ShoppingListSummary =
    ShoppingListSummary(id = id, name = name)

/**
 * Pruebas del selector de listas con una fuente falsa: no tocan la red. Cubren
 * la lectura y su orden, la lista recordada, la creación con nombre validado,
 * la creación dudosa con relectura y las carreras (época de las lecturas y
 * escrituras que no se pisan).
 */
class ShoppingListPickerControllerTest {

    /** Fuente falsa: apunta las llamadas y deja fijar cada resultado. */
    private class FakeListsSource(
        var lists: List<ShoppingListSummary> = emptyList(),
    ) : ShoppingListSource {

        var listResult: ApiResult<Page<ShoppingListSummary>>? = null
        var createResult: ApiResult<ShoppingListSummary> =
            ApiResult.Success(summary(LIST_C, "Nueva"))
        var listError: NetworkError? = null
        var createError: NetworkError? = null
        var listRuntime: RuntimeException? = null
        var createRuntime: RuntimeException? = null
        // Lo que no es ni un resultado ni una excepción: un `Error` de la máquina
        // virtual o una cancelación de la corrutina que llamó.
        var listThrowable: Throwable? = null
        var createThrowable: Throwable? = null
        var onList: (() -> Unit)? = null
        var onCreate: (() -> Unit)? = null
        var listCalls = 0
        val createTexts = mutableListOf<String>()
        val calls = mutableListOf<String>()

        override fun listLists(page: Int, perPage: Int): ApiResult<Page<ShoppingListSummary>> {
            calls += "list"
            listCalls++
            listThrowable?.let { throw it }
            listRuntime?.let { throw it }
            listError?.let { throw it }
            // El resultado se decide antes del enganche: así una lectura que se
            // queda bloqueada devuelve la página que había al empezar.
            val result = listResult
                ?: ApiResult.Success(Page.of(lists, page = 1, perPage = perPage, total = lists.size.toLong()))
            onList?.invoke()
            return result
        }

        override fun createList(name: String): ApiResult<ShoppingListSummary> {
            calls += "create:$name"
            createTexts += name
            createThrowable?.let { throw it }
            createRuntime?.let { throw it }
            createError?.let { throw it }
            onCreate?.invoke()
            val result = createResult
            when (result) {
                is ApiResult.Success -> lists = lists + result.value
                else -> Unit
            }
            return result
        }

        override fun getList(listId: String): ApiResult<ShoppingList> =
            ApiResult.Failure(ConnectionProblem.Unknown)

        override fun addItem(listId: String, text: String): ApiResult<Unit> =
            ApiResult.Failure(ConnectionProblem.Unknown)

        override fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit> =
            ApiResult.Failure(ConnectionProblem.Unknown)

        override fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit> =
            ApiResult.Failure(ConnectionProblem.Unknown)

        override fun deleteItem(itemId: String): ApiResult<Unit> =
            ApiResult.Failure(ConnectionProblem.Unknown)

        override fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit> =
            ApiResult.Failure(ConnectionProblem.Unknown)
    }

    private fun source(vararg lists: ShoppingListSummary): FakeListsSource =
        FakeListsSource(lists.toList())

    // ------------------------------------------------------------------ lectura

    @Test
    fun `cargar ordena por nombre y deduplica por id`() {
        val fake = source(
            summary(LIST_C, "Zeta"),
            summary(LIST_A, "Compra"),
            summary(LIST_C, "Zeta repetida"),
            summary(LIST_B, "Despensa"),
        )
        val state = ShoppingListPickerController(fake).load()

        assertEquals(listOf(LIST_A, LIST_B, LIST_C), state.lists.map { it.id })
        assertEquals(listOf("Compra", "Despensa", "Zeta"), state.lists.map { it.name })
        assertEquals(LIST_A, state.openListId)
        assertEquals(LIST_A, state.openList?.id)
        assertTrue(state.loadedOnce)
        assertEquals(ShoppingListPickerPhase.Idle, state.phase)
        assertNull(state.problem)
        assertEquals(listOf("list"), fake.calls)
    }

    @Test
    fun `una pagina vacia olvida la lista abierta y queda vacia`() {
        val controller = ShoppingListPickerController(
            source(),
            initialOpenListId = LIST_B,
        )

        val state = controller.load()

        assertNull(state.openListId)
        assertTrue(state.isEmpty)
        assertFalse(state.hasOpenList)
    }

    @Test
    fun `una lista recordada que no esta en la pagina se conserva`() {
        val controller = ShoppingListPickerController(
            source(summary(LIST_A, "Compra")),
            initialOpenListId = LIST_B,
        )

        val state = controller.load()

        assertEquals(LIST_B, state.openListId)
        assertNull(state.openList)
        assertEquals(listOf(LIST_A), state.lists.map { it.id })
    }

    @Test
    fun `un id inicial invalido no se recuerda`() {
        val controller = ShoppingListPickerController(source(), initialOpenListId = "../../fuera")
        assertNull(controller.state.openListId)
    }

    @Test
    fun `load no repite una lectura buena y refresh si`() {
        val fake = source(summary(LIST_A, "Compra"))
        val controller = ShoppingListPickerController(fake)

        controller.load()
        assertEquals(1, fake.listCalls)

        controller.load()
        assertEquals(1, fake.listCalls)

        controller.refresh()
        assertEquals(2, fake.listCalls)
    }

    @Test
    fun `retry solo repite tras un fallo`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.listResult = ApiResult.Failure(ConnectionProblem.NoNetwork)
        val controller = ShoppingListPickerController(fake)

        val failed = controller.load()
        assertEquals(ShoppingListPickerProblem.Failed(ConnectionProblem.NoNetwork), failed.problem)
        assertEquals(1, fake.listCalls)

        fake.listResult = null
        val ready = controller.retry()
        assertEquals(2, fake.listCalls)
        assertEquals(1, ready.lists.size)
        assertNull(ready.problem)

        controller.retry()
        assertEquals(2, fake.listCalls)
    }

    @Test
    fun `rememberOpenList no toca la red y null la olvida`() {
        val fake = source(summary(LIST_A, "Compra"), summary(LIST_B, "Despensa"))
        val controller = ShoppingListPickerController(fake)
        controller.load()

        val state = controller.rememberOpenList(LIST_B)

        assertEquals(LIST_B, state.openListId)
        assertEquals(LIST_B, state.openList?.id)
        assertEquals(1, fake.listCalls)

        controller.rememberOpenList(null)
        assertNull(controller.state.openListId)
        assertEquals(1, fake.listCalls)
    }

    @Test
    fun `rememberOpenList ignora un id que no es uuid`() {
        val controller = ShoppingListPickerController(
            source(summary(LIST_A, "Compra")),
            initialOpenListId = LIST_B,
        )

        controller.rememberOpenList("../../fuera")

        assertEquals(LIST_B, controller.state.openListId)
    }

    // ------------------------------------------------------------------- crear

    @Test
    fun `crear valida el nombre antes de la red`() {
        val fake = source()
        val controller = ShoppingListPickerController(fake)

        val empty = controller.create("   ")
        assertEquals(
            ShoppingListPickerProblem.InputRejected(ShoppingInputProblem.EmptyText),
            empty.problem,
        )

        val long = controller.create("a".repeat(ShoppingText.MAX_LIST_NAME + 1))
        assertEquals(
            ShoppingListPickerProblem.InputRejected(ShoppingInputProblem.TextTooLong),
            long.problem,
        )

        assertTrue(fake.calls.isEmpty())
        assertFalse(controller.state.isCreating)
    }

    @Test
    fun `crear con exito anade la lista la abre y no relee`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createResult = ApiResult.Success(summary(LIST_B, "Despensa"))
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("  Despensa\u202E  ")

        // Solo el POST: el resumen que devuelve el servidor basta para pintarla.
        assertEquals(listOf("create:Despensa"), fake.calls)
        assertEquals(LIST_B, state.openListId)
        assertEquals(listOf(LIST_A, LIST_B), state.lists.map { it.id })
        assertEquals("Despensa", state.openList?.name)
        assertFalse(state.isCreating)
        assertNull(state.problem)
    }

    @Test
    fun `crear sustituye una lista con el mismo id sin duplicar`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createResult = ApiResult.Success(summary(LIST_A, "Compra nueva"))
        val controller = ShoppingListPickerController(fake)
        controller.load()

        val state = controller.create("Compra nueva")

        assertEquals(1, state.lists.size)
        assertEquals("Compra nueva", state.lists.single().name)
        assertEquals(LIST_A, state.openListId)
    }

    @Test
    fun `crear con resultado dudoso relee y conserva el aviso`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createResult = ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding)
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("Leche")

        // Se relee para ver si la lista llegó a crearse, pero el aviso se queda.
        assertEquals(listOf("create:Leche", "list"), fake.calls)
        assertEquals(
            ShoppingListPickerProblem.MaybeCreated(ConnectionProblem.ServerNotResponding),
            state.problem,
        )
        assertFalse(state.isCreating)
        assertEquals(LIST_A, state.openListId)
        assertEquals(listOf(LIST_A), state.lists.map { it.id })
    }

    @Test
    fun `crear con un id ilegible se trata como dudoso y no lo abre`() {
        val fake = source()
        fake.createResult = ApiResult.Success(ShoppingListSummary("no-es-uuid", "Rara"))
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("Rara")

        assertEquals(listOf("create:Rara", "list"), fake.calls)
        assertEquals(
            ShoppingListPickerProblem.MaybeCreated(ConnectionProblem.Unknown),
            state.problem,
        )
        assertNull(state.openListId)
        assertTrue(state.lists.isEmpty())
        assertFalse(state.isCreating)
    }

    @Test
    fun `un 401 al crear deja el aviso y conserva la lista abierta`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createResult = ApiResult.Unauthorized
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("Compra")

        assertEquals(listOf("create:Compra"), fake.calls)
        assertEquals(ShoppingListPickerProblem.Unauthorized, state.problem)
        assertEquals(LIST_A, state.openListId)
        assertFalse(state.isCreating)
    }

    @Test
    fun `un 422 al crear deja el detalle saneado`() {
        val fake = source()
        val detail = ValidationDetail(field = null, index = null, message = "Mal nombre")
        fake.createResult = ApiResult.Invalid(listOf(detail))
        val controller = ShoppingListPickerController(fake)

        val state = controller.create("Compra")

        assertEquals(ShoppingListPickerProblem.InvalidData(listOf(detail)), state.problem)
        // El problema no repite el mensaje del servidor al convertirse en texto.
        assertFalse(state.problem.toString().contains("Mal nombre"))
    }

    @Test
    fun `un fallo de red despues del envio al crear es dudoso`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createError = NetworkError.AfterSend(NetworkError.Timeout)
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("Compra")

        assertEquals(listOf("create:Compra", "list"), fake.calls)
        assertEquals(
            ShoppingListPickerProblem.MaybeCreated(ConnectionProblem.ServerNotResponding),
            state.problem,
        )
        assertFalse(state.isCreating)
    }

    // ----------------------------------------------------------------- carreras

    @Test
    fun `una lectura lenta no pisa la ultima`() {
        val fake = source(summary(LIST_A, "Compra"))
        val controller = ShoppingListPickerController(fake)
        val lentaEnVuelo = CountDownLatch(1)
        val soltarLenta = CountDownLatch(1)
        val lecturas = AtomicInteger(0)
        fake.onList = {
            if (lecturas.getAndIncrement() == 0) {
                lentaEnVuelo.countDown()
                soltarLenta.await(5, TimeUnit.SECONDS)
            }
        }

        var fallo: Throwable? = null
        val hiloLento = thread {
            try {
                controller.load()
            } catch (e: Throwable) {
                fallo = e
            }
        }
        assertTrue(lentaEnVuelo.await(5, TimeUnit.SECONDS))

        // La segunda lectura sustituye a la primera y se aplica en el acto.
        fake.listResult = ApiResult.Success(
            Page.of(listOf(summary(LIST_B, "Despensa")), page = 1, perPage = 50, total = 1),
        )
        val rapida = controller.refresh()
        assertEquals(listOf(LIST_B), rapida.lists.map { it.id })
        assertEquals(ShoppingListPickerPhase.Idle, rapida.phase)

        soltarLenta.countDown()
        hiloLento.join(5_000)
        assertFalse(hiloLento.isAlive)
        assertNull(fallo)

        // La respuesta vieja ya no manda.
        assertEquals(listOf(LIST_B), controller.state.lists.map { it.id })
        assertEquals(ShoppingListPickerPhase.Idle, controller.state.phase)
    }

    @Test
    fun `crear durante una carga no se envia`() {
        val fake = source(summary(LIST_A, "Compra"))
        val controller = ShoppingListPickerController(fake)
        var duranteLaCarga: ShoppingListPickerState? = null
        fake.onList = { duranteLaCarga = controller.create("Leche") }

        val state = controller.load()

        assertTrue(duranteLaCarga!!.isLoading)
        assertTrue(fake.createTexts.isEmpty())
        assertEquals(listOf("list"), fake.calls)
        assertEquals(ShoppingListPickerPhase.Idle, state.phase)
        assertEquals(listOf(LIST_A), state.lists.map { it.id })
    }

    @Test
    fun `una carga durante una creacion no se envia`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createResult = ApiResult.Success(summary(LIST_B, "Despensa"))
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()
        fake.onCreate = {
            controller.refresh()
            controller.load()
            controller.retry()
        }

        val state = controller.create("Leche")

        assertEquals(listOf("create:Leche"), fake.calls)
        assertEquals(LIST_B, state.openListId)
        assertEquals(listOf(LIST_A, LIST_B), state.lists.map { it.id })
        assertFalse(state.isCreating)
    }

    @Test
    fun `dos creaciones seguidas solo mandan una`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createResult = ApiResult.Success(summary(LIST_B, "Despensa"))
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()
        var duranteLaCreacion: ShoppingListPickerState? = null
        fake.onCreate = { duranteLaCreacion = controller.create("Otra") }

        val state = controller.create("Leche")

        assertTrue(duranteLaCreacion!!.isCreating)
        assertEquals(listOf("create:Leche"), fake.calls)
        assertEquals(LIST_B, state.openListId)
    }

    @Test
    fun `un fallo inesperado de la fuente no deja la fase cargando`() {
        val fake = source()
        fake.listRuntime = IllegalStateException("fallo raro")
        val controller = ShoppingListPickerController(fake)

        val state = controller.load()

        assertEquals(ShoppingListPickerProblem.Failed(ConnectionProblem.Unknown), state.problem)
        assertEquals(ShoppingListPickerPhase.Idle, state.phase)
        assertFalse(state.loadedOnce)
    }

    @Test
    fun `una excepcion suelta al crear es definitiva y no relee`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createRuntime = IllegalStateException("fallo raro")
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("Compra")

        // El transporte no deja escapar sus excepciones: saltó antes de enviar,
        // así que no se avisa de «quizá creada» ni se relee la página.
        assertEquals(listOf("create:Compra"), fake.calls)
        assertEquals(
            ShoppingListPickerProblem.Failed(ConnectionProblem.Unknown),
            state.problem,
        )
        assertEquals(ShoppingListPickerPhase.Idle, state.phase)
        assertFalse(state.isCreating)
    }

    @Test
    fun `un 502 al crear queda en duda y relee`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createError = NetworkError.HttpError(502)
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("Compra")

        // Un proxy puede contestar 502 mientras Mealie sigue creando la lista.
        assertEquals(listOf("create:Compra", "list"), fake.calls)
        assertEquals(
            ShoppingListPickerProblem.MaybeCreated(ConnectionProblem.ServerError),
            state.problem,
        )
        assertFalse(state.isCreating)
    }

    @Test
    fun `un fallo antes del envio al crear no relee`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createError = NetworkError.ConnectionRefused
        val controller = ShoppingListPickerController(fake)
        controller.load()
        fake.calls.clear()

        val state = controller.create("Compra")

        // El servidor no llegó a escuchar: no hay nada que comprobar.
        assertEquals(listOf("create:Compra"), fake.calls)
        assertEquals(
            ShoppingListPickerProblem.Failed(ConnectionProblem.ServerNotResponding),
            state.problem,
        )
        assertEquals(ShoppingListPickerPhase.Idle, state.phase)
    }

    @Test
    fun `un Error al leer devuelve la fase a Idle y se relanza`() {
        val fake = source()
        fake.listThrowable = PickerFatalError("se acabó la memoria")
        val controller = ShoppingListPickerController(fake)

        assertThrows(PickerFatalError::class.java) { controller.load() }

        // La pantalla no se queda cargando para siempre.
        assertEquals(ShoppingListPickerPhase.Idle, controller.state.phase)
        assertFalse(controller.state.isLoading)
        assertFalse(controller.state.loadedOnce)
    }

    @Test
    fun `un Error al crear devuelve la fase a Idle y se relanza`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createThrowable = PickerFatalError("se acabó la memoria")
        val controller = ShoppingListPickerController(fake)
        controller.load()

        assertThrows(PickerFatalError::class.java) { controller.create("Compra") }

        // Ni «creando» ni botón de crear bloqueado para siempre.
        assertEquals(ShoppingListPickerPhase.Idle, controller.state.phase)
        assertFalse(controller.state.isCreating)
        assertFalse(controller.state.isBusy)
    }

    @Test
    fun `una cancelacion al crear no se traga y deja la fase en Idle`() {
        val fake = source(summary(LIST_A, "Compra"))
        fake.createThrowable = CancellationException("cancelado")
        val controller = ShoppingListPickerController(fake)
        controller.load()

        assertThrows(CancellationException::class.java) { controller.create("Compra") }

        assertEquals(ShoppingListPickerPhase.Idle, controller.state.phase)
        assertFalse(controller.state.isCreating)
    }

    // ------------------------------------------------------------------ toString

    @Test
    fun `toString no lleva nombres ni ids`() {
        val fake = source(summary(LIST_A, "Compra secreta"))
        val state = ShoppingListPickerController(fake).load()

        val text = state.toString()
        assertFalse(text, text.contains("Compra"))
        assertFalse(text, text.contains(LIST_A))
        assertTrue(text.contains("lists=1"))

        val summaryText = state.lists.single().toString()
        assertFalse(summaryText, summaryText.contains("Compra"))
    }
}
