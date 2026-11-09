package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val LISTA_UUID = "11111111-1111-4111-8111-111111111111"
private const val OTRA_UUID = "66666666-6666-4666-8666-666666666666"
private const val RECETA_UUID = "33333333-3333-4333-8333-333333333333"

/**
 * Error de mentira que no es una excepción: ninguna capa del controlador lo
 * atrapa. Sirve para comprobar que `adding` se cierra antes de que suba.
 */
private class RecipeFatalError(message: String) : Error(message)

/**
 * Pruebas del controlador al añadir los ingredientes de recetas: una sola
 * petición, relectura de la lista, resultado dudoso, fallos de red que la fuente
 * lanza y carreras con recargas y con la apertura de otra lista. No tocan la red.
 */
class ShoppingRecipeControllerTest {

    private fun item(id: String, note: String = "Tomate"): ShoppingItem = ShoppingItem(
        id = id,
        shoppingListId = LISTA_UUID,
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

    /** Fuente falsa: apunta las llamadas y deja fijar cada resultado. */
    private class FakeRecipeSource(var list: ShoppingList) : ShoppingListSource {
        var addRecipesResult: ApiResult<Unit> = ApiResult.Success(Unit)
        var addRecipesError: NetworkError? = null
        var addRecipesThrowable: Throwable? = null
        var onAddRecipes: (() -> Unit)? = null
        var onGet: (() -> Unit)? = null
        var addRecipesCalls = 0
        var sent: List<RecipeAddition> = emptyList()
        /** Listas que devuelve [getList] por id; si no está, [list]. */
        val lists = mutableMapOf<String, ShoppingList>()
        val calls = mutableListOf<String>()
        private var nextId = 0

        override fun getList(listId: String): ApiResult<ShoppingList> {
            calls += "get"
            onGet?.invoke()
            return ApiResult.Success(lists[listId] ?: list)
        }

        override fun addItem(listId: String, text: String): ApiResult<Unit> {
            calls += "add:$text"
            return ApiResult.Success(Unit)
        }

        override fun addRecipes(listId: String, additions: List<RecipeAddition>): ApiResult<Unit> {
            calls += "addRecipes:${additions.size}"
            addRecipesCalls++
            sent = additions
            addRecipesThrowable?.let { throw it }
            addRecipesError?.let { throw it }
            onAddRecipes?.invoke()
            if (addRecipesResult is ApiResult.Success) {
                list = list.copy(items = list.items + additions.map { newItem() })
            }
            return addRecipesResult
        }

        override fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit> {
            calls += "check:$checked"
            return ApiResult.Success(Unit)
        }

        override fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit> {
            calls += "edit:$note"
            return ApiResult.Success(Unit)
        }

        override fun deleteItem(itemId: String): ApiResult<Unit> {
            calls += "delete:$itemId"
            return ApiResult.Success(Unit)
        }

        override fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit> = ApiResult.Success(Unit)

        private fun newItem(): ShoppingItem {
            nextId++
            return ShoppingItem(
                id = "00000000-0000-4000-8000-" + nextId.toString().padStart(12, '0'),
                shoppingListId = LISTA_UUID,
                note = "Tomate",
                quantity = 1.0,
                unitSingular = "",
                unitPlural = "",
                foodSingular = "Tomate",
                foodPlural = "Tomates",
                labelId = null,
                labelName = "",
                checked = false,
                position = 0,
            )
        }
    }

    private fun source(items: List<ShoppingItem> = emptyList()): FakeRecipeSource =
        FakeRecipeSource(ShoppingList(LISTA_UUID, "Compra", items))

    // --------------------------------------------------------------- correcto

    @Test
    fun `anadir una receta usa una sola peticion y relee la lista`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 2.0)

        assertEquals(listOf("addRecipes:1", "get"), fake.calls)
        assertEquals(listOf(RecipeAddition(RECETA_UUID, 2.0)), fake.sent)
        assertEquals(1, state.items.size)
        assertNull(state.problem)
        assertFalse(state.adding)
        assertFalse(state.isLoading)
    }

    @Test
    fun `anadir varias recetas va en una sola llamada`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipes(
            listOf(RecipeAddition(RECETA_UUID, 1.0), RecipeAddition(OTRA_UUID, 3.0)),
        )

        assertEquals(listOf("addRecipes:2", "get"), fake.calls)
        assertEquals(2, fake.sent.size)
        assertEquals(2, state.items.size)
        assertNull(state.problem)
    }

    @Test
    fun `la misma receta repetida se manda una sola vez con la escala sumada`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipes(
            listOf(
                RecipeAddition(RECETA_UUID, 1.0),
                RecipeAddition(OTRA_UUID, 1.0),
                RecipeAddition(RECETA_UUID.uppercase(), 2.0),
            ),
        )

        assertEquals(listOf("addRecipes:2", "get"), fake.calls)
        assertEquals(
            listOf(RecipeAddition(RECETA_UUID, 3.0), RecipeAddition(OTRA_UUID, 1.0)),
            fake.sent,
        )
        assertEquals(2, state.items.size)
        assertNull(state.problem)
    }

    // ---------------------------------------------------------------- dudoso

    @Test
    fun `un resultado dudoso relee la lista y avisa`() {
        val fake = source()
        fake.addRecipesResult = ApiResult.MaybeCreated(ConnectionProblem.ServerNotResponding)
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        // Se relee para saber si los ingredientes llegaron, pero queda el aviso.
        assertEquals(listOf("addRecipes:1", "get"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding), state.problem)
        assertFalse(state.adding)
    }

    // ------------------------------------------------------ fallos de la fuente

    @Test
    fun `un fallo antes del envio no relee`() {
        val fake = source()
        fake.addRecipesError = NetworkError.ConnectionRefused
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        // El servidor no llegó a escuchar: no hay nada que comprobar.
        assertEquals(listOf("addRecipes:1"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `un fallo despues del envio es dudoso y relee`() {
        val fake = source()
        fake.addRecipesError = NetworkError.AfterSend(NetworkError.Timeout)
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        assertEquals(listOf("addRecipes:1", "get"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `una excepcion suelta antes del envio es definitiva y no relee`() {
        val fake = source()
        fake.addRecipesThrowable = IllegalStateException("fallo raro")
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        // El transporte no deja escapar sus excepciones: saltó antes de enviar,
        // así que no hay nada que comprobar ni aviso de «quizá creado».
        assertEquals(listOf("addRecipes:1"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.Unknown), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `una excepcion de validacion del origen tampoco se vuelve dudosa`() {
        val fake = source()
        fake.addRecipesThrowable = IllegalArgumentException("lista inválida")
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        assertEquals(listOf("addRecipes:1"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.Unknown), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `una respuesta ilegible es dudosa y relee`() {
        val fake = source()
        fake.addRecipesError = NetworkError.InvalidResponse
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        assertEquals(listOf("addRecipes:1", "get"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.Unknown), state.problem)
    }

    @Test
    fun `un rechazo definitivo no relee`() {
        val fake = source()
        fake.addRecipesError = NetworkError.HttpError(422)
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        assertEquals(listOf("addRecipes:1"), fake.calls)
        assertEquals(ShoppingProblem.InvalidData(emptyList()), state.problem)
        assertFalse(state.adding)
    }

    @Test
    fun `un 502 es dudoso y relee`() {
        val fake = source()
        fake.addRecipesError = NetworkError.HttpError(502)
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        // Un proxy puede contestar 502 mientras Mealie sigue añadiendo.
        assertEquals(listOf("addRecipes:1", "get"), fake.calls)
        assertEquals(ShoppingProblem.Failed(ConnectionProblem.ServerError), state.problem)
    }

    @Test
    fun `un Error se relanza y cierra el estado`() {
        val fake = source()
        fake.addRecipesThrowable = RecipeFatalError("se acabó la memoria")
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)

        assertThrows(RecipeFatalError::class.java) { controller.addRecipe(RECETA_UUID, 1.0) }

        // `adding` no puede quedarse encendido: el botón de añadir seguiría muerto.
        assertFalse(controller.state.adding)
        assertEquals(ShoppingPhase.Idle, controller.state.phase)
    }

    @Test
    fun `una cancelacion se relanza y cierra el estado`() {
        val fake = source()
        fake.addRecipesThrowable = CancellationException("cancelado")
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)

        assertThrows(CancellationException::class.java) { controller.addRecipe(RECETA_UUID, 1.0) }

        assertFalse(controller.state.adding)
        assertNull(controller.state.problem)
    }

    // ------------------------------------------------------------- validación

    @Test
    fun `un dato invalido no llega a la fuente`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()

        controller.addRecipes(emptyList())
        controller.addRecipe("../../fuera", 1.0)
        controller.addRecipe("no-es-uuid", 1.0)
        controller.addRecipe(RECETA_UUID, 0.0)
        controller.addRecipe(RECETA_UUID, -1.0)
        controller.addRecipe(RECETA_UUID, Double.NaN)
        controller.addRecipe(RECETA_UUID, RecipeAdditions.MAX_SCALE + 1.0)
        controller.addRecipes(
            (1..RecipeAdditions.MAX_RECIPES_PER_OPERATION + 1).map { RecipeAddition(uuid(it), 1.0) },
        )

        assertTrue(fake.calls.isEmpty())
        assertEquals(0, fake.addRecipesCalls)
    }

    /** UUID distinto por índice, para probar el tope con recetas diferentes. */
    private fun uuid(index: Int): String =
        index.toString(16).padStart(8, '0') + "-0000-4000-8000-000000000000"

    @Test
    fun `sin lista abierta no se envia nada`() {
        val fake = source()
        val controller = ShoppingListController(fake)

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        assertTrue(fake.calls.isEmpty())
        assertNull(state.listId)
        assertNull(state.problem)
    }

    @Test
    fun `marcar editar o borrar no empieza mientras se anaden recetas`() {
        val fake = source(listOf(item("22222222-2222-4222-8222-222222222222")))
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()
        var durante: ShoppingListState? = null
        fake.onAddRecipes = {
            controller.toggle("22222222-2222-4222-8222-222222222222")
            controller.editNote("22222222-2222-4222-8222-222222222222", "Pan", 1.0)
            controller.delete("22222222-2222-4222-8222-222222222222")
            durante = controller.state
        }

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        // Ninguna de las tres escrituras llegó a la fuente.
        assertEquals(listOf("addRecipes:1", "get"), fake.calls)
        assertTrue(durante!!.adding)
        assertNull(state.problem)
        assertFalse(state.adding)
        assertEquals(2, state.items.size)
    }

    // -------------------------------------------------------------- carreras

    @Test
    fun `no se anade mientras se recarga`() {
        val fake = source(listOf(item("22222222-2222-4222-8222-222222222222")))
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()
        var duranteLaRecarga: ShoppingListState? = null
        fake.onGet = { duranteLaRecarga = controller.addRecipe(RECETA_UUID, 1.0) }

        val state = controller.refresh()

        // La escritura no se envía (la carga manda) y la fase vuelve a Idle.
        assertEquals(listOf("get"), fake.calls)
        assertTrue(duranteLaRecarga!!.isLoading)
        assertFalse(state.isLoading)
        assertEquals(0, fake.addRecipesCalls)
    }

    @Test
    fun `no se anade texto mientras se anaden recetas`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()
        fake.onAddRecipes = { controller.add("Pan") }

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        assertEquals(listOf("addRecipes:1", "get"), fake.calls)
        assertFalse(state.adding)
        assertNull(state.problem)
    }

    @Test
    fun `un segundo intento con el POST en vuelo no lanza otra peticion`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()
        fake.onAddRecipes = { controller.addRecipe(RECETA_UUID, 1.0) }

        controller.addRecipe(RECETA_UUID, 1.0)

        assertEquals(1, fake.addRecipesCalls)
    }

    @Test
    fun `abrir otra lista durante el POST no la pisa`() {
        val fake = source()
        fake.lists[OTRA_UUID] = ShoppingList(
            OTRA_UUID,
            "Otra",
            listOf(item("44444444-4444-4444-8444-444444444444", note = "Pan")),
        )
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.onAddRecipes = { controller.open(OTRA_UUID) }

        val state = controller.addRecipe(RECETA_UUID, 1.0)

        // El POST salió, pero su resultado se descarta porque ya hay otra lista abierta.
        assertEquals(1, fake.addRecipesCalls)
        assertEquals(OTRA_UUID, state.listId)
        assertEquals("Otra", state.listName)
        assertEquals(1, state.items.size)
        assertFalse(state.isLoading)
    }

    @Test
    fun `dos hilos a la vez solo mandan un POST`() {
        val fake = source()
        val controller = ShoppingListController(fake)
        controller.open(LISTA_UUID)
        fake.calls.clear()
        val postEnVuelo = CountDownLatch(1)
        val soltarPost = CountDownLatch(1)
        fake.onAddRecipes = {
            postEnVuelo.countDown()
            soltarPost.await(5, TimeUnit.SECONDS)
        }

        var fallo: Throwable? = null
        val primero = thread {
            try {
                controller.addRecipe(RECETA_UUID, 1.0)
            } catch (error: Throwable) {
                fallo = error
            }
        }
        // El segundo intento llega con el POST del primero ya en vuelo.
        assertTrue(postEnVuelo.await(5, TimeUnit.SECONDS))
        val segundo = thread {
            try {
                controller.addRecipe(RECETA_UUID, 1.0)
            } catch (error: Throwable) {
                fallo = error
            }
        }
        segundo.join(5_000)
        assertFalse(segundo.isAlive)
        soltarPost.countDown()
        primero.join(5_000)
        assertFalse(primero.isAlive)

        assertNull(fallo)
        assertEquals(1, fake.addRecipesCalls)
        assertFalse(controller.state.adding)
    }
}
