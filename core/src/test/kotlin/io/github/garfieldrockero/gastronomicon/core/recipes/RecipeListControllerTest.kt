package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlador y estado de la lista sin red: la fuente es un falso. */
class RecipeListControllerTest {

    private class FakeSource(var handler: (RecipeListQuery) -> Page<RecipeSummary>) : RecipeListSource {
        val queries: MutableList<RecipeListQuery> = Collections.synchronizedList(mutableListOf())

        override fun listRecipes(query: RecipeListQuery): Page<RecipeSummary> {
            queries += query
            return handler(query)
        }
    }

    private fun summary(slug: String, name: String = slug, tags: List<String> = emptyList()) = RecipeSummary(
        id = null, slug = slug, name = name, description = "", hasImage = false, servings = null, rating = null,
        times = RecipeTimes(), categories = emptyList(), tags = tags.map { Label(it, it) },
    )

    private fun page(slugs: List<String>, page: Int, perPage: Int = 2, total: Long = 5) =
        Page.of(slugs.map { summary(it) }, page, perPage, total)

    /** Cinco recetas r1..r5 en páginas de dos, como un servidor coherente. */
    private fun coherentServer(): FakeSource {
        val all = (1..5).map { "r$it" }
        return FakeSource { query ->
            val from = (query.page - 1) * query.perPage
            page(all.drop(from).take(query.perPage), query.page, query.perPage, all.size.toLong())
        }
    }

    private fun controller(source: RecipeListSource, perPage: Int = 2) = RecipeListController(source, perPage)

    private fun slugs(state: RecipeListState) = state.items.map { it.slug }

    // ------------------------------------------------------------ primera carga

    @Test
    fun `el estado inicial esta vacio y sin cargar`() {
        val state = controller(coherentServer()).state
        assertTrue(state.items.isEmpty())
        assertFalse(state.loadedOnce)
        assertFalse(state.isEmpty)
        assertFalse(state.hasMore)
        assertEquals(ListPhase.Idle, state.phase)
        assertNull(state.problem)
    }

    @Test
    fun `la primera carga pide la pagina 1 y deja hay mas`() {
        val source = coherentServer()
        val state = controller(source).load()
        assertEquals(listOf("r1", "r2"), slugs(state))
        assertTrue(state.hasMore)
        assertEquals(5L, state.total)
        assertEquals(1, state.lastPage)
        assertEquals(ListPhase.Idle, state.phase)
        assertTrue(state.loadedOnce)
        assertEquals("page=1&perPage=2", source.queries.single().toQueryString())
    }

    @Test
    fun `load no repite si ya esta cargada`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        controller.load()
        assertEquals(1, source.queries.size)
    }

    @Test
    fun `una lista vacia se distingue de una sin cargar`() {
        val source = FakeSource { page(emptyList(), 1, total = 0) }
        val state = controller(source).load()
        assertTrue(state.isEmpty)
        assertFalse(state.hasMore)
    }

    // ------------------------------------------------------------------ paginar

    @Test
    fun `cargar mas anade la pagina siguiente hasta el final`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        assertEquals(listOf("r1", "r2", "r3", "r4"), slugs(controller.loadMore()))
        val last = controller.loadMore()
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), slugs(last))
        assertFalse(last.hasMore)
        assertEquals(listOf(1, 2, 3), source.queries.map { it.page })
    }

    @Test
    fun `cargar mas sin mas paginas no llama al servidor`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        controller.loadMore()
        controller.loadMore()
        val calls = source.queries.size
        controller.loadMore()
        assertEquals(calls, source.queries.size)
    }

    @Test
    fun `cargar mas antes de la primera carga no hace nada`() {
        val source = coherentServer()
        controller(source).loadMore()
        assertTrue(source.queries.isEmpty())
    }

    @Test
    fun `la pagina siguiente conserva busqueda y tamaño`() {
        val source = coherentServer()
        val controller = controller(source, perPage = 2)
        controller.search("tortilla")
        controller.loadMore()
        assertEquals("page=2&perPage=2&search=tortilla", source.queries[1].toQueryString())
    }

    // --------------------------------------------------------------- duplicados

    @Test
    fun `una receta repetida en otra pagina no se duplica`() {
        // Entre pidiendo la 1 y la 2 se ha borrado una receta: r2 baja a la página 1... y vuelve a salir.
        val source = FakeSource { query ->
            when (query.page) {
                1 -> page(listOf("a", "b"), 1, total = 4)
                else -> page(listOf("b", "c"), 2, total = 4)
            }
        }
        val controller = controller(source)
        controller.load()
        assertEquals(listOf("a", "b", "c"), slugs(controller.loadMore()))
    }

    @Test
    fun `los repetidos dentro de una misma pagina se quedan en uno`() {
        val source = FakeSource { page(listOf("a", "a", "b", "a"), 1, perPage = 10, total = 4) }
        assertEquals(listOf("a", "b"), slugs(controller(source, perPage = 10).load()))
    }

    @Test
    fun `una pagina de solo repetidos corta la paginacion`() {
        val source = FakeSource { query ->
            if (query.page == 1) page(listOf("a", "b"), 1, total = 1_000) else page(listOf("a", "b"), query.page, total = 1_000)
        }
        val controller = controller(source)
        controller.load()
        val state = controller.loadMore()
        assertEquals(listOf("a", "b"), slugs(state))
        assertFalse("sin novedades no se sigue pidiendo", state.hasMore)
    }

    // --------------------------------------------------- servidor incoherente

    @Test
    fun `un servidor que devuelve otra pagina no hace avanzar la lista`() {
        // Ignora «page» y contesta siempre la 1.
        val source = FakeSource { page(listOf("a", "b"), 1, total = 100) }
        val controller = controller(source)
        controller.load()
        val state = controller.loadMore()
        assertEquals(listOf("a", "b"), slugs(state))
        assertFalse(state.hasMore)
        assertEquals(ConnectionProblem.Unknown, state.problem)
        assertEquals(ListPhase.Idle, state.phase)
        val calls = source.queries.size
        controller.loadMore()
        assertEquals("no queda nada que pedir", calls, source.queries.size)
    }

    @Test
    fun `un total enorme no hace pedir paginas para siempre`() {
        val source = FakeSource { query ->
            if (query.page == 1) page(listOf("a", "b"), 1, total = Long.MAX_VALUE) else page(emptyList(), query.page, total = Long.MAX_VALUE)
        }
        val controller = controller(source)
        assertTrue(controller.load().hasMore)
        val state = controller.loadMore()
        assertFalse("la pagina vacia corta", state.hasMore)
        assertEquals(2, source.queries.size)
    }

    @Test
    fun `un total cero o negativo con elementos no inventa paginas`() {
        for (total in listOf(0L, -5L)) {
            val source = FakeSource { page(listOf("a", "b"), 1, total = total) }
            val state = controller(source).load()
            assertEquals(listOf("a", "b"), slugs(state))
            assertFalse(total.toString(), state.hasMore)
        }
    }

    @Test
    fun `un perPage del servidor absurdo no rompe el estado`() {
        val source = FakeSource { Page.of(listOf(summary("a")), page = 1, perPage = 0, total = 3) }
        val state = controller(source).load()
        assertEquals(listOf("a"), slugs(state))
        assertTrue(state.hasMore)
    }

    @Test
    fun `hay un tope de recetas cargadas`() {
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val source = FakeSource { query ->
            val items = (1..100).map { summary("r${counter.incrementAndGet()}") }
            Page.of(items, query.page, 100, Long.MAX_VALUE)
        }
        val controller = controller(source, perPage = 100)
        var state = controller.load()
        var guard = 0
        while (state.hasMore && guard++ < 200) state = controller.loadMore()
        assertEquals(RecipeListState.MAX_ITEMS, state.items.size)
        assertFalse(state.hasMore)
    }

    // ------------------------------------------------------------------ errores

    @Test
    fun `un error en la primera carga queda como ConnectionProblem`() {
        val cases = listOf<Pair<Throwable, ConnectionProblem>>(
            NetworkError.Timeout to ConnectionProblem.ServerNotResponding,
            NetworkError.NoConnection to ConnectionProblem.NoNetwork,
            NetworkError.HttpError(401) to ConnectionProblem.Unauthorized,
            NetworkError.HttpError(500) to ConnectionProblem.ServerError,
            NetworkError.TlsError to ConnectionProblem.SecureConnectionFailed,
            NetworkError.InvalidResponse to ConnectionProblem.Unknown,
            IllegalStateException("fallo interno") to ConnectionProblem.Unknown,
        )
        for ((error, expected) in cases) {
            val state = controller(FakeSource { throw error }).load()
            assertEquals(error.toString(), expected, state.problem)
            assertEquals(ListPhase.Idle, state.phase)
            assertTrue(state.items.isEmpty())
            assertFalse("con error no es «vacía»", state.isEmpty)
        }
    }

    @Test
    fun `un error al cargar mas conserva la lista y se puede reintentar`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        val good = source.handler
        source.handler = { throw NetworkError.Timeout }
        val failed = controller.loadMore()
        assertEquals(listOf("r1", "r2"), slugs(failed))
        assertEquals(ConnectionProblem.ServerNotResponding, failed.problem)
        assertTrue("sigue habiendo mas para reintentar", failed.hasMore)
        assertEquals(ListPhase.Idle, failed.phase)

        source.handler = good
        val retried = controller.loadMore()
        assertEquals(listOf("r1", "r2", "r3", "r4"), slugs(retried))
        assertNull(retried.problem)
    }

    @Test
    fun `tras un fallo de la primera carga load vuelve a intentarlo`() {
        val source = coherentServer()
        val good = source.handler
        source.handler = { throw NetworkError.NoConnection }
        val controller = controller(source)
        assertEquals(ConnectionProblem.NoNetwork, controller.load().problem)
        source.handler = good
        val state = controller.load()
        assertNull(state.problem)
        assertEquals(listOf("r1", "r2"), slugs(state))
    }

    @Test
    fun `el mensaje de una excepcion rara no llega al estado`() {
        val state = controller(FakeSource { throw IllegalStateException("https://mi.servidor/ tok-secreto") }).load()
        assertFalse(state.toString().contains("tok-secreto"))
        assertFalse(state.toString().contains("mi.servidor"))
    }

    // ---------------------------------------------------------------- recargar

    @Test
    fun `recargar sustituye la lista y vuelve a la pagina 1`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        controller.loadMore()
        source.handler = { query -> page(listOf("nueva", "r1"), query.page, total = 2) }
        val state = controller.refresh()
        assertEquals(listOf("nueva", "r1"), slugs(state))
        assertEquals(1, state.lastPage)
        assertFalse(state.hasMore)
        assertEquals(1, source.queries.last().page)
    }

    @Test
    fun `recargar con error conserva lo que habia`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        source.handler = { throw NetworkError.ConnectionRefused }
        val state = controller.refresh()
        assertEquals(listOf("r1", "r2"), slugs(state))
        assertEquals(ConnectionProblem.ServerNotResponding, state.problem)
        assertTrue(state.hasMore)
    }

    @Test
    fun `recargar sin nada cargado es una primera carga`() {
        val source = coherentServer()
        val controller = controller(source)
        assertEquals(listOf("r1", "r2"), slugs(controller.refresh()))
    }

    // ----------------------------------------------------------------- búsqueda

    @Test
    fun `buscar empieza de nuevo con la consulta normalizada`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        controller.loadMore()
        val state = controller.search("  tortilla \u0007 ")
        assertEquals("tortilla", state.search)
        assertEquals(1, state.lastPage)
        assertEquals(listOf("r1", "r2"), slugs(state))
        assertEquals("page=1&perPage=2&search=tortilla", source.queries.last().toQueryString())
    }

    @Test
    fun `buscar lo mismo no repite la peticion`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.search("flan")
        controller.search("  flan ")
        assertEquals(1, source.queries.size)
    }

    @Test
    fun `buscar lo mismo tras un error si reintenta`() {
        val source = coherentServer()
        val good = source.handler
        source.handler = { throw NetworkError.Timeout }
        val controller = controller(source)
        controller.search("flan")
        source.handler = good
        assertNull(controller.search("flan").problem)
        assertEquals(2, source.queries.size)
    }

    @Test
    fun `una busqueda vacia o solo de controles equivale a no buscar`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.search("flan")
        val state = controller.search(" \u0000\n ")
        assertNull(state.search)
        assertEquals("page=1&perPage=2", source.queries.last().toQueryString())
    }

    @Test
    fun `una busqueda hostil viaja codificada y acotada`() {
        val source = coherentServer()
        val hostile = "a&perPage=100000#‮<script>" + "x".repeat(10_000)
        val state = controller(source).search(hostile)
        val query = source.queries.single().toQueryString()
        assertFalse(query, query.contains("#"))
        assertFalse(query, query.contains("<"))
        assertEquals(1, query.split("perPage=").size - 1)
        assertTrue(state.search!!.length <= RecipeListQuery.MAX_SEARCH_LENGTH)
    }

    // ------------------------------------------------------------ filtro local

    @Test
    fun `el filtro local no llama al servidor y no distingue acentos`() {
        val source = FakeSource {
            Page.of(
                listOf(summary("1", "Café con leche"), summary("2", "Niño envuelto", tags = listOf("Cena")), summary("3", "Tarta")),
                1, 10, 3,
            )
        }
        val controller = controller(source, perPage = 10)
        controller.load()
        assertEquals(listOf("1"), controller.setLocalFilter("CAFE").visibleItems.map { it.slug })
        assertEquals(listOf("2"), controller.setLocalFilter("nino cena").visibleItems.map { it.slug })
        assertEquals(3, controller.setLocalFilter("").visibleItems.size)
        assertEquals(1, source.queries.size)
    }

    @Test
    fun `el filtro local sobrevive a cargar mas`() {
        val source = coherentServer()
        val controller = controller(source)
        controller.load()
        controller.setLocalFilter("r3")
        val state = controller.loadMore()
        assertEquals("r3", state.localFilter)
        assertEquals(listOf("r3"), state.visibleItems.map { it.slug })
    }

    @Test
    fun `un filtro local hostil se limpia y se acota`() {
        val controller = controller(coherentServer())
        controller.load()
        assertEquals("r1", controller.setLocalFilter("r‮1\u0000").localFilter)
        assertEquals(listOf("r1"), controller.state.visibleItems.map { it.slug })
        val long = controller.setLocalFilter("a".repeat(100_000)).localFilter
        assertTrue(long.length <= RecipeListState.MAX_FILTER_LENGTH)
    }

    // ----------------------------------------------------------- configuración

    @Test
    fun `un orden invalido falla al crear el controlador`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecipeListController(coherentServer(), orderBy = "name; drop")
        }
    }

    @Test
    fun `el orden se pasa a todas las consultas`() {
        val source = coherentServer()
        val controller = RecipeListController(source, perPage = 2, orderBy = "name", orderDirection = SortDirection.ASCENDING)
        controller.load()
        controller.loadMore()
        for (query in source.queries) {
            assertTrue(query.toQueryString(), query.toQueryString().endsWith("&orderBy=name&orderDirection=asc"))
        }
    }

    // ------------------------------------------------------------- concurrencia

    @Test
    fun `el resultado de una peticion superada por otra se descarta`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = FakeSource { query ->
            if (query.page == 2) {
                started.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                page(listOf("vieja-1", "vieja-2"), 2, total = 100)
            } else {
                page(listOf("n1", "n2"), 1, total = 100)
            }
        }
        val controller = controller(source)
        controller.load()

        val background = Thread { controller.loadMore() }
        background.start()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertEquals(ListPhase.LoadingMore, controller.state.phase)

        // La persona busca otra cosa mientras llega la página 2 de la lista anterior.
        val state = controller.search("otra")
        release.countDown()
        background.join(10_000)

        assertEquals(listOf("n1", "n2"), slugs(state))
        assertEquals(listOf("n1", "n2"), slugs(controller.state))
        assertEquals("otra", controller.state.search)
        assertEquals(1, controller.state.lastPage)
    }

    @Test
    fun `cargar mas mientras ya se esta cargando no lanza otra peticion`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = FakeSource { query ->
            if (query.page == 2) {
                started.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
            page(listOf("p${query.page}a", "p${query.page}b"), query.page, total = 100)
        }
        val controller = controller(source)
        controller.load()
        val background = Thread { controller.loadMore() }
        background.start()
        assertTrue(started.await(10, TimeUnit.SECONDS))

        controller.loadMore()
        release.countDown()
        background.join(10_000)

        assertEquals(listOf(1, 2), source.queries.map { it.page })
        assertEquals(listOf("p1a", "p1b", "p2a", "p2b"), slugs(controller.state))
    }
}
