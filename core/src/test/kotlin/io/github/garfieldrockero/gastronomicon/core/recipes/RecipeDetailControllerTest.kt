package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Controlador de detalle sin red: la fuente es un falso. */
class RecipeDetailControllerTest {

    private fun recipe(slug: String = "tortilla", name: String = "Tortilla", servings: Double? = 4.0) = Recipe(
        id = null, slug = slug, name = name, description = "", hasImage = false, servings = servings, yieldText = "",
        rating = null, sourceUrl = null, times = RecipeTimes(), categories = emptyList(), tags = emptyList(),
        ingredients = listOf(
            Ingredient(quantity = 2.0, unitSingular = "cucharada", unitPlural = "cucharadas", foodSingular = "aceite"),
        ),
        steps = emptyList(), notes = emptyList(),
    )

    private fun ready(state: RecipeDetailState): RecipeDetailView = (state as RecipeDetailState.Ready).view

    @Test
    fun `empieza cerrado`() {
        assertSame(RecipeDetailState.Closed, RecipeDetailController { recipe() }.state)
    }

    @Test
    fun `open carga la receta y construye la vista con las raciones originales`() {
        val requested = mutableListOf<String>()
        val controller = RecipeDetailController { slug -> requested += slug; recipe(slug) }
        val view = ready(controller.open("tortilla"))
        assertEquals(listOf("tortilla"), requested)
        assertEquals("Tortilla", view.name)
        assertEquals(4.0, view.selectedServings!!, 0.0)
        assertEquals("2", view.ingredients.single().quantityText)
    }

    @Test
    fun `las raciones se recalculan desde la receta original`() {
        val controller = RecipeDetailController { recipe() }
        controller.open("tortilla")
        assertEquals("4", ready(controller.setServings(8.0)).ingredients.single().quantityText)
        assertEquals(8.0, ready(controller.setServings(8.0)).selectedServings!!, 0.0)
        assertEquals(9.0, ready(controller.moreServings()).selectedServings!!, 0.0)
        assertEquals(8.0, ready(controller.fewerServings()).selectedServings!!, 0.0)
        assertEquals(4.0, ready(controller.setServings(Double.NaN)).selectedServings!!, 0.0)
        assertEquals(1000.0, ready(controller.setServings(1e12)).selectedServings!!, 0.0)
    }

    @Test
    fun `cambiar raciones sin receta lista no hace nada`() {
        val controller = RecipeDetailController { throw NetworkError.Timeout }
        assertSame(RecipeDetailState.Closed, controller.moreServings())
        controller.open("a")
        assertTrue(controller.fewerServings() is RecipeDetailState.Failed)
    }

    @Test
    fun `un 401 o 403 es Unauthorized aparte`() {
        for (code in listOf(401, 403)) {
            val controller = RecipeDetailController { throw NetworkError.HttpError(code) }
            assertSame(code.toString(), RecipeDetailState.Unauthorized, controller.open("a"))
        }
    }

    @Test
    fun `un 404 o un slug invalido es NotFound`() {
        assertSame(RecipeDetailState.NotFound, RecipeDetailController { throw NetworkError.HttpError(404) }.open("a"))
        assertSame(
            RecipeDetailState.NotFound,
            RecipeDetailController { throw IllegalArgumentException("slug") }.open("../x"),
        )
    }

    @Test
    fun `el resto de fallos son Failed con su motivo y nunca Unauthorized`() {
        val cases = listOf<Pair<Throwable, ConnectionProblem>>(
            NetworkError.Timeout to ConnectionProblem.ServerNotResponding,
            NetworkError.NoConnection to ConnectionProblem.NoNetwork,
            NetworkError.HttpError(500) to ConnectionProblem.ServerError,
            NetworkError.TlsError to ConnectionProblem.SecureConnectionFailed,
            NetworkError.InvalidResponse to ConnectionProblem.Unknown,
            IllegalStateException("fallo interno") to ConnectionProblem.Unknown,
        )
        for ((error, expected) in cases) {
            val state = RecipeDetailController { throw error }.open("a")
            assertEquals(error.toString(), expected, (state as RecipeDetailState.Failed).problem)
        }
    }

    @Test
    fun `el mensaje de una excepcion rara no llega al estado`() {
        val state = RecipeDetailController { throw IllegalStateException("https://mi.servidor/ tok-secreto") }.open("a")
        assertFalse(state.toString().contains("tok-secreto"))
        assertFalse(state.toString().contains("mi.servidor"))
    }

    @Test
    fun `el estado listo no imprime los textos de la receta`() {
        val state = RecipeDetailController { recipe(name = "Secreto-123") }.open("a")
        assertFalse(state.toString().contains("Secreto-123"))
    }

    @Test
    fun `retry repite la carga tras un fallo`() {
        var fail = true
        var calls = 0
        val controller = RecipeDetailController {
            calls++
            if (fail) throw NetworkError.Timeout
            recipe()
        }
        assertTrue(controller.open("tortilla") is RecipeDetailState.Failed)
        fail = false
        assertEquals("Tortilla", ready(controller.retry()).name)
        assertEquals(2, calls)
        controller.retry()
        assertEquals("con la receta lista no vuelve a pedir", 2, calls)
    }

    @Test
    fun `retry tras Unauthorized y NotFound vuelve a pedir`() {
        var calls = 0
        val controller = RecipeDetailController {
            calls++
            if (calls == 1) throw NetworkError.HttpError(401) else recipe()
        }
        assertSame(RecipeDetailState.Unauthorized, controller.open("a"))
        assertTrue(controller.retry() is RecipeDetailState.Ready)
    }

    @Test
    fun `retry sin receta abierta no hace nada`() {
        var calls = 0
        val controller = RecipeDetailController { calls++; recipe() }
        assertSame(RecipeDetailState.Closed, controller.retry())
        controller.open("a")
        controller.close()
        assertSame(RecipeDetailState.Closed, controller.retry())
        assertEquals(1, calls)
    }

    @Test
    fun `close vuelve a cerrado`() {
        val controller = RecipeDetailController { recipe() }
        controller.open("a")
        assertSame(RecipeDetailState.Closed, controller.close())
        assertSame(RecipeDetailState.Closed, controller.state)
    }

    @Test
    fun `una respuesta lenta tras close se descarta`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = RecipeDetailController {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            recipe()
        }
        val result = AtomicReference<RecipeDetailState>()
        val thread = Thread { result.set(controller.open("a")) }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertSame(RecipeDetailState.Loading, controller.state)
        controller.close()
        release.countDown()
        thread.join(5_000)
        assertSame(RecipeDetailState.Closed, result.get())
        assertSame(RecipeDetailState.Closed, controller.state)
    }

    @Test
    fun `abrir otra receta descarta la respuesta de la anterior`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = RecipeDetailController { slug ->
            if (slug == "lenta") {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            recipe(slug, name = slug)
        }
        val result = AtomicReference<RecipeDetailState>()
        val thread = Thread { result.set(controller.open("lenta")) }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals("rapida", ready(controller.open("rapida")).name)
        release.countDown()
        thread.join(5_000)
        assertEquals("rapida", ready(result.get()).name)
        assertEquals("rapida", ready(controller.state).name)
    }

    @Test
    fun `una receta hostil se sanea al construir la vista`() {
        val state = RecipeDetailController { recipe(name = "Pan\u0007‮" + "x".repeat(5_000)) }.open("a")
        val name = ready(state).name
        assertTrue(name.length <= 300)
        assertFalse(name.any { Character.isISOControl(it) || it == '‮' })
    }
}
