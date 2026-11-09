package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftErrorKind
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.core.recipes.IngredientFood
import io.github.garfieldrockero.gastronomicon.core.recipes.IngredientUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Catálogo de unidades y alimentos contra el servidor de pruebas.
 *
 * Es de frontera: el nombre que se crea sale hacia el servidor con el token, y
 * lo que vuelve (nombres, abreviaturas, plurales) se sanea antes de llegar a
 * la interfaz. Ningún resultado puede llevar token, URL ni cuerpos crudos.
 */
class MealieClientCatalogTest {

    private lateinit var mealie: FakeMealie

    private val unitId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val foodId = "b1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"

    @Before
    fun start() {
        mealie = FakeMealie()
    }

    @After
    fun stop() {
        mealie.stop()
    }

    private fun page(items: String, page: Int = 1, perPage: Int = 50, total: Long = 1): String =
        """{"page":$page,"per_page":$perPage,"total":$total,"total_pages":1,"items":[$items],"next":null,"previous":null}"""

    private fun assertNoSecrets(result: Any) {
        val text = result.toString()
        assertFalse(text, text.contains(mealie.token.value))
        assertFalse(text, text.contains("127.0.0.1"))
        assertFalse(text, text.contains("SECRETO-DEL-SERVIDOR"))
    }

    // ------------------------------------------------------------- listar

    @Test
    fun `listUnits pide pagina y busqueda y devuelve el catalogo saneado`() {
        mealie.fixed(
            "GET", "/api/units", 200,
            page(
                """{"id":"${unitId.uppercase()}","name":"  gra\u0007mo ","pluralName":"gramos\u202e",
                   "abbreviation":" g ","pluralAbbreviation":" gs\u0007 ","useAbbreviation":true,
                   "extras":{"x":1},"fraction":false}""",
                page = 2, perPage = 25, total = 26,
            ),
        )
        val result = mealie.client().listUnits(page = 2, perPage = 25, search = "gra mo")
        val catalog = (result as ApiResult.Success).value
        val request = mealie.seen.single()
        assertEquals("page=2&perPage=25&search=gra%20mo", request.query)
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
        // El catálogo no documenta los nombres de campo de `orderBy`: no se manda.
        assertFalse(request.query.orEmpty(), request.query.orEmpty().contains("orderBy"))
        assertEquals(2, catalog.page)
        assertEquals(25, catalog.perPage)
        assertEquals(26L, catalog.total)
        assertEquals(
            IngredientUnit(
                id = unitId,
                name = "gramo",
                pluralName = "gramos",
                abbreviation = "g",
                pluralAbbreviation = "gs",
                useAbbreviation = true,
            ),
            catalog.items.single(),
        )
        assertNoSecrets(result)
    }

    @Test
    fun `listUnits descarta lo que no se puede reenviar`() {
        val items = listOf(
            """{"id":"sin-uuid","name":"x"}""",
            """{"id":"$unitId","name":"   "}""",
            """{"name":"gramo"}""",
            "null",
            """{"id":"$unitId","name":"gramo"}""",
        ).joinToString(",")
        mealie.fixed("GET", "/api/units", 200, page(items, total = 5))
        val catalog = (mealie.client().listUnits() as ApiResult.Success).value
        assertEquals(listOf("gramo"), catalog.items.map { it.name })
    }

    @Test
    fun `una pagina del catalogo nunca trae mas de cien entradas`() {
        val many = (1..150).joinToString(",") { """{"id":"$unitId","name":"u$it"}""" }
        mealie.fixed("GET", "/api/units", 200, page(many, perPage = 150, total = 150))
        val catalog = (mealie.client().listUnits(perPage = 10_000) as ApiResult.Success).value
        assertEquals(MealieClient.MAX_CATALOG_PER_PAGE, catalog.items.size)
        assertTrue(mealie.seen.single().query.orEmpty().contains("perPage=100"))
    }

    @Test
    fun `listFoods tambien busca y sanea`() {
        mealie.fixed(
            "GET", "/api/foods", 200,
            page("""{"id":"$foodId","name":" Hari\u0007na ","pluralName":"harinas","label":null,"recipeCount":3}"""),
        )
        val catalog = (mealie.client().listFoods(search = "harina") as ApiResult.Success).value
        assertEquals("page=1&perPage=100&search=harina", mealie.seen.single().query)
        assertEquals(IngredientFood(id = foodId, name = "Harina", pluralName = "harinas"), catalog.items.single())
    }

    @Test
    fun `sin busqueda la consulta no lleva search`() {
        mealie.fixed("GET", "/api/units", 200, page("""{"id":"$unitId","name":"gramo"}"""))
        mealie.client().listUnits(search = "   ")
        assertEquals("page=1&perPage=100", mealie.seen.single().query)
    }

    @Test
    fun `los errores del catalogo se traducen a su resultado tipado sin reintentar`() {
        val expected = mapOf(
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            404 to ApiResult.NotFound,
            409 to ApiResult.Conflict,
            400 to ApiResult.Rejected(400),
            500 to ApiResult.Failure(ConnectionProblem.ServerError),
        )
        for ((status, result) in expected) {
            mealie.seen.clear()
            mealie.fixed("GET", "/api/units", status, """{"detail":["SECRETO-DEL-SERVIDOR"]}""")
            val actual = mealie.client().listUnits()
            assertEquals(status.toString(), result, actual)
            assertEquals(status.toString(), 1, mealie.requestsTo("GET", "/api/units").size)
            assertNoSecrets(actual)
        }
    }

    @Test
    fun `una respuesta que no es una pagina es un fallo sin reintento`() {
        for (body in listOf("[]", "\"x\"", "null", "no es json", "", "{\"items\":\"x\"}")) {
            mealie.seen.clear()
            mealie.fixed("GET", "/api/foods", 200, body)
            val result = mealie.client().listFoods()
            assertEquals(body, ApiResult.Failure(ConnectionProblem.Unknown), result)
            assertEquals(body, 1, mealie.requestsTo("GET", "/api/foods").size)
            assertNoSecrets(result)
        }
    }

    // ------------------------------------------------------------- crear

    @Test
    fun `createUnit envia solo lo escrito y devuelve la unidad creada`() {
        mealie.fixed(
            "POST", "/api/units", 201,
            """{"id":"$unitId","name":"gramo","pluralName":"gramos","abbreviation":"g",
               "fraction":true,"extras":{"x":1},"campoFuturo":2}""",
        )
        val result = mealie.client().createUnit(
            name = "  gra\u0007mo ",
            pluralName = " gramos ",
            abbreviation = " g ",
        )
        assertEquals(
            ApiResult.Success(
                IngredientUnit(id = unitId, name = "gramo", pluralName = "gramos", abbreviation = "g"),
            ),
            result,
        )
        val request = mealie.seen.single()
        assertEquals(
            """{"name":"gramo","pluralName":"gramos","abbreviation":"g"}""",
            String(request.body, Charsets.UTF_8),
        )
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("Bearer ${mealie.token.value}", request.headers["authorization"])
        assertNoSecrets(result)
    }

    @Test
    fun `createFood envia el nombre y el plural y devuelve el alimento creado`() {
        mealie.fixed("POST", "/api/foods", 201, """{"id":"$foodId","name":"harina","pluralName":"harinas"}""")
        val result = mealie.client().createFood(name = "harina", pluralName = "harinas")
        assertEquals(
            ApiResult.Success(IngredientFood(id = foodId, name = "harina", pluralName = "harinas")),
            result,
        )
        assertEquals(
            """{"name":"harina","pluralName":"harinas"}""",
            String(mealie.seen.single().body, Charsets.UTF_8),
        )
    }

    @Test
    fun `un nombre vacio o demasiado largo no sale de la app`() {
        assertEquals(
            ApiResult.DraftInvalid(listOf(DraftError(DraftField.IngredientUnit, DraftErrorKind.Required))),
            mealie.client().createUnit("  "),
        )
        assertEquals(
            ApiResult.DraftInvalid(listOf(DraftError(DraftField.IngredientUnit, DraftErrorKind.TooLong))),
            mealie.client().createUnit("a".repeat(101)),
        )
        assertEquals(
            ApiResult.DraftInvalid(listOf(DraftError(DraftField.IngredientFood, DraftErrorKind.Required))),
            mealie.client().createFood(""),
        )
        assertEquals(
            ApiResult.DraftInvalid(listOf(DraftError(DraftField.IngredientUnit, DraftErrorKind.TooLong))),
            mealie.client().createUnit(name = "gramo", abbreviation = "a".repeat(21)),
        )
        assertTrue(mealie.seen.isEmpty())
    }

    @Test
    fun `un 2xx que no se puede leer deja la creacion en duda y no se reintenta`() {
        for (body in listOf("", "{}", "[]", "no es json", """{"id":"sin-uuid","name":"x"}""", """{"id":"$unitId"}""")) {
            mealie.seen.clear()
            mealie.fixed("POST", "/api/units", 201, body)
            val result = mealie.client().createUnit("gramo")
            assertEquals(body, ApiResult.MaybeCreated(ConnectionProblem.Unknown), result)
            assertEquals(body, 1, mealie.requestsTo("POST", "/api/units").size)
            assertNoSecrets(result)
        }
    }

    @Test
    fun `crear una unidad traduce los errores del servidor`() {
        val expected = mapOf(
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            409 to ApiResult.Conflict,
            400 to ApiResult.Rejected(400),
            422 to ApiResult.Invalid(emptyList()),
            500 to ApiResult.MaybeCreated(ConnectionProblem.ServerError),
            502 to ApiResult.MaybeCreated(ConnectionProblem.ServerError),
        )
        for ((status, result) in expected) {
            mealie.seen.clear()
            mealie.fixed("POST", "/api/foods", status, """{"detail":"SECRETO-DEL-SERVIDOR"}""")
            val actual = mealie.client().createFood("harina")
            assertEquals(status.toString(), result, actual)
            // Un rechazo definitivo no se repite solo; un 5xx tampoco (es ambiguo).
            assertEquals(status.toString(), 1, mealie.requestsTo("POST", "/api/foods").size)
            assertNoSecrets(actual)
        }
    }

    @Test
    fun `crear con el servidor cerrado es un fallo de red sin datos del servidor`() {
        val client = mealie.client()
        mealie.stop()
        val result = client.createUnit("gramo")
        assertTrue(result.toString(), result is ApiResult.Failure)
        assertNoSecrets(result)
    }
}
