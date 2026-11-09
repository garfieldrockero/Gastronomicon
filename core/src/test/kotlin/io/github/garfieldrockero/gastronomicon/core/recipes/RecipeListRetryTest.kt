package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Operación fallida, reintento, carga automática y filas de la lista; sin red (fuente falsa). */
class RecipeListRetryTest {

    private class FakeSource(var handler: (RecipeListQuery) -> Page<RecipeSummary>) : RecipeListSource {
        val queries: MutableList<RecipeListQuery> = Collections.synchronizedList(mutableListOf())

        override fun listRecipes(query: RecipeListQuery): Page<RecipeSummary> {
            queries += query
            return handler(query)
        }
    }

    private fun summary(
        slug: String,
        name: String = slug,
        times: RecipeTimes = RecipeTimes(),
        rating: Double? = null,
    ) = RecipeSummary(
        id = null, slug = slug, name = name, description = "", hasImage = false, servings = null, rating = rating,
        times = times, categories = emptyList(), tags = emptyList(),
    )

    private fun coherentServer(): FakeSource {
        val all = (1..5).map { "r$it" }
        return FakeSource { query ->
            val from = (query.page - 1) * query.perPage
            Page.of(all.drop(from).take(query.perPage).map { summary(it) }, query.page, query.perPage, all.size.toLong())
        }
    }

    private fun controller(source: RecipeListSource) = RecipeListController(source, perPage = 2)

    private fun slugs(state: RecipeListState) = state.items.map { it.slug }

    // ------------------------------------------------------- operación fallida

    @Test
    fun `sin fallo no hay operacion fallida ni reintento`() {
        val controller = controller(coherentServer())
        val state = controller.load()
        assertNull(state.failedOperation)
        assertFalse(state.canRetry)
        assertEquals(state, controller.retry())
    }

    @Test
    fun `un fallo de recargar se marca Refresh y retry recarga`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        controller.loadMore()
        val good = source.handler
        source.handler = { throw NetworkError.Timeout }
        val failed = controller.refresh()
        assertEquals(ListOperation.Refresh, failed.failedOperation)
        assertTrue(failed.canRetry)
        assertEquals(listOf("r1", "r2", "r3", "r4"), slugs(failed))

        source.handler = good
        val retried = controller.retry()
        assertNull(retried.failedOperation)
        assertNull(retried.problem)
        assertEquals("recargar vuelve a la página 1", listOf("r1", "r2"), slugs(retried))
        assertEquals(1, source.queries.last().page)
    }

    @Test
    fun `un fallo de cargar mas se marca LoadMore y retry pide la siguiente`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        val good = source.handler
        source.handler = { throw NetworkError.NoConnection }
        val failed = controller.loadMore()
        assertEquals(ListOperation.LoadMore, failed.failedOperation)

        source.handler = good
        val retried = controller.retry()
        assertEquals(listOf("r1", "r2", "r3", "r4"), slugs(retried))
        assertEquals(2, source.queries.last().page)
        assertNull(retried.failedOperation)
    }

    @Test
    fun `un fallo de la primera carga se marca FirstLoad y retry la repite con la busqueda`() {
        val source = coherentServer()
        val good = source.handler
        source.handler = { throw NetworkError.ConnectionRefused }
        val controller = controller(source)
        val failed = controller.search("flan")
        assertEquals(ListOperation.FirstLoad, failed.failedOperation)

        source.handler = good
        val retried = controller.retry()
        assertEquals("flan", retried.search)
        assertEquals("page=1&perPage=2&search=flan", source.queries.last().toQueryString())
        assertNull(retried.failedOperation)
        assertEquals(listOf("r1", "r2"), slugs(retried))
    }

    @Test
    fun `recargar una lista vacia que falla se reintenta como primera carga`() {
        val source = coherentServer()
        val good = source.handler
        source.handler = { throw NetworkError.Timeout }
        val controller = controller(source)
        assertEquals(ListOperation.FirstLoad, controller.refresh().failedOperation)
        source.handler = good
        assertEquals(listOf("r1", "r2"), slugs(controller.retry()))
    }

    @Test
    fun `si el reintento vuelve a fallar sigue la misma operacion`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        source.handler = { throw NetworkError.Timeout }
        controller.loadMore()
        val again = controller.retry()
        assertEquals(ListOperation.LoadMore, again.failedOperation)
        assertEquals(ConnectionProblem.ServerNotResponding, again.problem)
        assertEquals(listOf("r1", "r2"), slugs(again))
    }

    @Test
    fun `un fallo con otra operacion posterior exitosa borra la marca`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        val good = source.handler
        source.handler = { throw NetworkError.Timeout }
        controller.loadMore()
        source.handler = good
        val state = controller.refresh()
        assertNull(state.failedOperation)
        assertNull(state.problem)
    }

    @Test
    fun `una pagina equivocada al pedir mas no se ofrece como reintento`() {
        val source = FakeSource { Page.of(listOf(summary("a"), summary("b")), 1, 2, 100) }
        val controller = controller(source)
        controller.load()
        val state = controller.loadMore()
        assertEquals(ConnectionProblem.Unknown, state.problem)
        assertNull(state.failedOperation)
        assertFalse(state.canRetry)
        val calls = source.queries.size
        controller.retry()
        assertEquals(calls, source.queries.size)
    }

    @Test
    fun `una pagina equivocada al recargar se puede reintentar`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        val good = source.handler
        source.handler = { Page.of(listOf(summary("x")), 7, 2, 10) }
        val state = controller.refresh()
        assertEquals(ListOperation.Refresh, state.failedOperation)
        source.handler = good
        assertNull(controller.retry().problem)
    }

    @Test
    fun `una excepcion rara tambien deja la operacion marcada`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        source.handler = { throw IllegalStateException("tok-secreto") }
        val state = controller.loadMore()
        assertEquals(ListOperation.LoadMore, state.failedOperation)
        assertFalse(state.toString().contains("tok-secreto"))
    }

    // ------------------------------------------------------------- canAutoLoad

    @Test
    fun `canAutoLoad es verdadero solo con mas paginas, sin filtro y sin fallo`() {
        val controller = controller(coherentServer())
        assertFalse("sin cargar", controller.state.canAutoLoad)
        val loaded = controller.load()
        assertTrue(loaded.canAutoLoad)
        assertFalse(controller.setLocalFilter("r").canAutoLoad)
        assertTrue(controller.setLocalFilter("").canAutoLoad)
    }

    @Test
    fun `canAutoLoad es falso tras un fallo y sin mas paginas`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        source.handler = { throw NetworkError.Timeout }
        val failed = controller.loadMore()
        assertTrue(failed.canLoadMore)
        assertFalse("no insiste sola", failed.canAutoLoad)

        val full = FakeSource { Page.of(listOf(summary("a")), 1, 2, 1) }
        assertFalse(controller(full).load().canAutoLoad)
    }

    @Test
    fun `un filtro de solo espacios o controles no cuenta como filtro`() {
        val controller = controller(coherentServer())
        controller.load()
        assertTrue(controller.setLocalFilter("  \u0000 ").canAutoLoad)
    }

    @Test
    fun `canAutoLoad es falso mientras carga`() {
        val state = RecipeListState(hasMore = true).startLoadMore()
        assertFalse(state.canAutoLoad)
    }

    // ----------------------------------------------------------- buscar en todo

    @Test
    fun `showSearchAllHint pide filtro y paginas pendientes`() {
        val controller = controller(coherentServer())
        controller.load()
        assertFalse(controller.state.showSearchAllHint)
        assertTrue(controller.setLocalFilter("r3").showSearchAllHint)
        controller.setLocalFilter("")
        controller.loadMore()
        controller.loadMore()
        assertFalse("sin más páginas no hay nada que buscar en el servidor", controller.setLocalFilter("r3").showSearchAllHint)
    }

    // ------------------------------------------------------------------- filas

    @Test
    fun `las filas traen el tiempo total ya formateado y las estrellas`() {
        val source = FakeSource {
            Page.of(
                listOf(
                    summary("a", times = RecipeTimes(totalSeconds = 5400), rating = 4.4),
                    summary("b", times = RecipeTimes(total = "una horita", totalSeconds = 60), rating = 4.5),
                    summary("c", rating = Double.NaN),
                    summary("d", times = RecipeTimes(totalSeconds = 0), rating = 99.0),
                ),
                1, 10, 4,
            )
        }
        val rows = RecipeListController(source, perPage = 10).also { it.load() }.state.visibleRows
        assertEquals(listOf("1 h 30 min", "una horita", "", ""), rows.map { it.totalTimeText })
        assertEquals(listOf(4, 5, null, 5), rows.map { it.ratingStars })
        assertEquals(listOf("a", "b", "c", "d"), rows.map { it.slug })
    }

    @Test
    fun `las filas respetan el filtro local y no imprimen el nombre`() {
        val source = FakeSource { Page.of(listOf(summary("a", "Flan"), summary("b", "Sopa")), 1, 10, 2) }
        val controller = RecipeListController(source, perPage = 10)
        controller.load()
        val rows = controller.setLocalFilter("flan").visibleRows
        assertEquals(listOf("a"), rows.map { it.slug })
        assertFalse(rows.single().toString().contains("Flan"))
    }

    @Test
    fun `el texto de tiempo hostil de una fila sale limpio y acotado`() {
        val hostile = "30\u0007 min‮" + "x".repeat(1_000)
        val row = RecipeSummaryRow.from(summary("a", times = RecipeTimes(total = hostile)))
        assertTrue(row.totalTimeText.length <= 100)
        assertFalse(row.totalTimeText.any { Character.isISOControl(it) || it == '‮' })
    }
}
