package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeListQueryTest {

    @Test
    fun `por defecto solo lleva página y tamaño`() {
        assertEquals("page=1&perPage=20", RecipeListQuery().toQueryString())
    }

    @Test
    fun `lleva todos los parámetros en orden fijo`() {
        val query = RecipeListQuery(3, 50, "tortilla", "name", SortDirection.ASCENDING)
        assertEquals("page=3&perPage=50&search=tortilla&orderBy=name&orderDirection=asc", query.toQueryString())
    }

    @Test
    fun `la página menor que uno sube a uno`() {
        assertEquals(1, RecipeListQuery(page = 0).page)
        assertEquals(1, RecipeListQuery(page = -5).page)
        assertEquals(1, RecipeListQuery(page = Int.MIN_VALUE).page)
    }

    @Test
    fun `el tamaño de página se acota`() {
        assertEquals(1, RecipeListQuery(perPage = 0).perPage)
        assertEquals(1, RecipeListQuery(perPage = -1).perPage)
        assertEquals(RecipeListQuery.MAX_PER_PAGE, RecipeListQuery(perPage = Int.MAX_VALUE).perPage)
    }

    @Test
    fun `la búsqueda se codifica con porcentaje en UTF-8`() {
        val query = RecipeListQuery(search = "niño & ají #1?=a+b/c%")
        assertEquals(
            "page=1&perPage=20&search=ni%C3%B1o%20%26%20aj%C3%AD%20%231%3F%3Da%2Bb%2Fc%25",
            query.toQueryString(),
        )
    }

    @Test
    fun `caracteres sin reservar pasan tal cual`() {
        assertEquals("page=1&perPage=20&search=a-b.c_d~E9", RecipeListQuery(search = "a-b.c_d~E9").toQueryString())
    }

    @Test
    fun `un intento de inyectar parámetros queda escapado`() {
        val text = RecipeListQuery(search = "x&perPage=100000&queryFilter=1").toQueryString()
        assertEquals("page=1&perPage=20&search=x%26perPage%3D100000%26queryFilter%3D1", text)
    }

    @Test
    fun `los saltos de línea y controles se quitan de la búsqueda`() {
        assertEquals("ab", RecipeListQuery(search = "a\r\nb").search)
        assertEquals("ab", RecipeListQuery(search = "a\u0000b").search)
    }

    @Test
    fun `la búsqueda vacía o en blanco se omite`() {
        assertNull(RecipeListQuery(search = "").search)
        assertNull(RecipeListQuery(search = "   ").search)
        assertNull(RecipeListQuery(search = "\n\t").search)
        assertEquals("page=1&perPage=20", RecipeListQuery(search = " ").toQueryString())
    }

    @Test
    fun `la búsqueda larga se recorta sin partir un par sustituto`() {
        val long = "a".repeat(199) + "\uD83C\uDF72" + "zzz"
        val search = RecipeListQuery(search = long).search!!
        assertTrue(search.length <= RecipeListQuery.MAX_SEARCH_LENGTH)
        assertEquals("a".repeat(199), search)
        assertEquals(200, RecipeListQuery(search = "b".repeat(5000)).search!!.length)
    }

    @Test
    fun `un sustituto suelto no rompe la codificación`() {
        assertEquals("page=1&perPage=20&search=%3F", RecipeListQuery(search = "\uD83C").toQueryString())
    }

    @Test
    fun `orderBy solo admite nombres de campo simples`() {
        for (bad in listOf("", "name desc", "name&x=1", "nañe", "1name", "a.b", "name\n", "../x", "a".repeat(65))) {
            try {
                RecipeListQuery(orderBy = bad)
            } catch (expected: IllegalArgumentException) {
                assertTrue(!expected.message.isNullOrBlank())
                continue
            }
            throw AssertionError("Se esperaba rechazar orderBy «$bad»")
        }
        assertEquals("dateAdded", RecipeListQuery(orderBy = "dateAdded").orderBy)
        assertEquals("date_added", RecipeListQuery(orderBy = "date_added").orderBy)
    }

    @Test
    fun `la petición es un GET a la ruta fija con la consulta aparte`() {
        val request = RecipeListQuery(2, 10, "sopa").toRequest()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("/api/recipes", request.path)
        assertEquals("page=2&perPage=10&search=sopa", request.query)
        assertNull(request.body)
    }

    @Test
    fun `la petición generada con cualquier texto es válida para HttpRequest`() {
        val nasty = "a b%\"<>\\{}|^`ñ\u202E\uD83C\uDF72;,+/?#"
        RecipeListQuery(search = nasty).toRequest()
    }

    @Test
    fun `withPage conserva el resto de parámetros`() {
        val next = RecipeListQuery(1, 30, "pan", "name", SortDirection.DESCENDING).withPage(4)
        assertEquals("page=4&perPage=30&search=pan&orderBy=name&orderDirection=desc", next.toQueryString())
    }
}
