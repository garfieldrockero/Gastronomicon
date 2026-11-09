package io.github.garfieldrockero.gastronomicon.core.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageSize
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListQuery
import io.github.garfieldrockero.gastronomicon.core.recipes.SortDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Pruebas del cliente contra el HttpServer del JDK: cabeceras, códigos de
 * error, redirecciones y cuerpos grandes de verdad, sin dependencias nuevas.
 */
class MealieClientTest {

    private val token = ApiToken("tok-secreto-ABC123xyz")

    private class Seen(val path: String, val query: String?, val authorization: String?)

    private lateinit var server: HttpServer
    private lateinit var otherServer: HttpServer
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    private val seenByOther: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen.add(
                Seen(
                    exchange.requestURI.path,
                    exchange.requestURI.rawQuery,
                    exchange.requestHeaders.getFirst("Authorization"),
                ),
            )
            val handler: (HttpExchange) -> Unit = routes[exchange.requestURI.path] ?: { e -> respond(e, 404) }
            handler(exchange)
        }
        server.start()
        otherServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        otherServer.createContext("/") { exchange ->
            seenByOther.add(exchange.requestURI.path)
            respond(exchange, 200, "{}")
        }
        otherServer.start()
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
        runCatching { otherServer.stop(0) }
    }

    // ------------------------------------------------------- checkConnection

    @Test
    fun `comprobar conexion con exito devuelve la version y manda el token solo en el segundo paso`() {
        routes[ABOUT] = { e -> respond(e, 200, ABOUT_OK) }
        routes[SELF] = { e -> respond(e, 200, "{\"id\":\"x\"}") }
        val result = client().checkConnection()
        assertEquals(ConnectionCheck.Success("v3.29.0"), result)
        assertEquals(listOf(ABOUT, SELF), seen.map { it.path })
        assertNull("about no debe llevar token", seen[0].authorization)
        assertEquals("Bearer tok-secreto-ABC123xyz", seen[1].authorization)
    }

    @Test
    fun `la version del servidor se sanea`() {
        routes[ABOUT] = { e -> respond(e, 200, """{"production":true,"version":"v3.‮29.0​"}""") }
        routes[SELF] = { e -> respond(e, 200, "{}") }
        assertEquals(ConnectionCheck.Success("v3.29.0"), client().checkConnection())
    }

    @Test
    fun `si about no tiene forma de Mealie es dirección incorrecta y el token no sale`() {
        val bodies = listOf(
            "{\"hola\":1}",
            "{\"version\":\"1.0\"}", // sin «production»
            "{\"production\":true}", // sin versión
            "{\"production\":true,\"version\":\"\"}",
            "{\"production\":true,\"version\":42}",
            "<html><body>Bienvenido</body></html>",
            "[]",
            "null",
        )
        for (body in bodies) {
            seen.clear()
            routes[ABOUT] = { e -> respond(e, 200, body) }
            val result = client().checkConnection()
            assertEquals(body, ConnectionCheck.Failure(ConnectionProblem.InvalidAddress), result)
            assertEquals(body, listOf(ABOUT), seen.map { it.path })
        }
    }

    @Test
    fun `about enorme es direccion incorrecta`() {
        routes[ABOUT] = { e -> respond(e, 200, "x".repeat(10_000)) }
        val result = client(aboutMaxBytes = 2_000).checkConnection()
        assertEquals(ConnectionCheck.Failure(ConnectionProblem.InvalidAddress), result)
        assertEquals(1, seen.size)
    }

    @Test
    fun `los errores de about se traducen sin hablar del token`() {
        val expected = mapOf(
            404 to ConnectionProblem.InvalidAddress,
            // Sin token enviado, un 401 o 403 es un proxy o una pasarela, no «token malo».
            401 to ConnectionProblem.InvalidAddress,
            403 to ConnectionProblem.InvalidAddress,
            // Un código raro en about tampoco es un Mealie con esta API.
            422 to ConnectionProblem.InvalidAddress,
            500 to ConnectionProblem.ServerError,
            503 to ConnectionProblem.ServerError,
        )
        for ((status, problem) in expected) {
            seen.clear()
            routes[ABOUT] = { e -> respond(e, status) }
            assertEquals("about $status", ConnectionCheck.Failure(problem), client().checkConnection())
            assertEquals(1, seen.size)
        }
    }

    @Test
    fun `un token rechazado en users self es Unauthorized`() {
        routes[ABOUT] = { e -> respond(e, 200, ABOUT_OK) }
        for (status in listOf(401, 403)) {
            routes[SELF] = { e -> respond(e, status) }
            assertEquals(
                "self $status",
                ConnectionCheck.Failure(ConnectionProblem.Unauthorized),
                client().checkConnection(),
            )
        }
    }

    @Test
    fun `users self se lee con un limite pequeno y no con el de las recetas`() {
        routes[ABOUT] = { e -> respond(e, 200, ABOUT_OK) }
        routes[SELF] = { e -> respond(e, 200, "x".repeat(10_000)) }
        // Con el límite de las recetas (1 MB) pasa; con el de users/self (2 000 B) no.
        assertEquals(
            ConnectionCheck.Success("v3.29.0"),
            client().checkConnection(),
        )
        assertEquals(
            ConnectionCheck.Failure(ConnectionProblem.Unknown),
            client(selfMaxBytes = 2_000).checkConnection(),
        )
    }

    @Test
    fun `el limite de users self es de 64 KiB y el fallo no cita el token`() {
        assertEquals(64L * 1024, MealieClient.SELF_MAX_RESPONSE_BYTES)
        routes[ABOUT] = { e -> respond(e, 200, ABOUT_OK) }
        routes[SELF] = { e -> respond(e, 200, "x".repeat(10_000)) }
        val result = client(selfMaxBytes = 2_000).checkConnection()
        assertFalse(result.toString().contains(token.value))
    }

    @Test
    fun `otros errores de users self`() {
        routes[ABOUT] = { e -> respond(e, 200, ABOUT_OK) }
        val expected = mapOf(
            404 to ConnectionProblem.InvalidAddress,
            422 to ConnectionProblem.Unknown,
            500 to ConnectionProblem.ServerError,
        )
        for ((status, problem) in expected) {
            routes[SELF] = { e -> respond(e, status) }
            assertEquals("self $status", ConnectionCheck.Failure(problem), client().checkConnection())
        }
    }

    // ----------------------------------------------------------- currentUser

    @Test
    fun `currentUser devuelve el id normalizado y manda el token solo en self`() {
        routes[SELF] = { e ->
            respond(
                e,
                200,
                """{"id":"0F8FAD5B-D9CB-469F-A165-70867728950E","username":"pepe",
                    "email":"pepe@ejemplo.org","tokens":[]}""",
            )
        }

        val result = client().currentUser()

        val user = (result as ApiResult.Success<CurrentUser>).value
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", user.id)
        assertEquals(listOf(SELF), seen.map { it.path })
        assertEquals("Bearer tok-secreto-ABC123xyz", seen[0].authorization)
        assertFalse(result.toString().contains("0f8fad5b"))
        assertFalse(user.toString().contains("0f8fad5b"))
    }

    @Test
    fun `currentUser con un id que no es uuid falla cerrado`() {
        val bodies = listOf(
            "{}",
            """{"id":null}""",
            """{"id":"x"}""",
            """{"id":"../fuera"}""",
            """{"id":"0f8fad5b-d9cb-469f-a165-70867728950"}""",
        )
        for (body in bodies) {
            routes[SELF] = { e -> respond(e, 200, body) }
            assertEquals(body, ApiResult.Failure(ConnectionProblem.Unknown), client().currentUser())
        }
    }

    @Test
    fun `currentUser con un cuerpo que no es json es Failure`() {
        routes[SELF] = { e -> respond(e, 200, "<html>esto no es Mealie</html>") }

        assertEquals(ApiResult.Failure(ConnectionProblem.Unknown), client().currentUser())
    }

    @Test
    fun `currentUser mapea los errores del servidor`() {
        val expected = mapOf(
            401 to ApiResult.Unauthorized,
            403 to ApiResult.Forbidden,
            404 to ApiResult.NotFound,
            500 to ApiResult.Failure(ConnectionProblem.ServerError),
        )
        for ((status, result) in expected) {
            routes[SELF] = { e -> respond(e, status) }
            assertEquals("self $status", result, client().currentUser())
        }
    }

    @Test
    fun `currentUser no expone el token al imprimirse`() {
        routes[SELF] = { e -> respond(e, 401) }

        val result = client().currentUser()

        assertFalse(result.toString().contains(token.value))
    }

    @Test
    fun `sin servidor es ServerNotResponding por conexion rechazada`() {
        server.stop(0)
        assertEquals(ConnectionCheck.Failure(ConnectionProblem.ServerNotResponding), client().checkConnection())
    }

    @Test
    fun `una redireccion de about a otro origen se rechaza y no llega nada alli`() {
        routes[ABOUT] = { e ->
            e.responseHeaders.add("Location", "http://127.0.0.1:${otherServer.address.port}/api/app/about")
            respond(e, 302)
        }
        assertEquals(ConnectionCheck.Failure(ConnectionProblem.Unknown), client().checkConnection())
        assertTrue(seenByOther.isEmpty())
    }

    @Test
    fun `una redireccion de users self a otro origen no lleva el token alli`() {
        routes[ABOUT] = { e -> respond(e, 200, ABOUT_OK) }
        routes[SELF] = { e ->
            e.responseHeaders.add("Location", "http://127.0.0.1:${otherServer.address.port}/robado")
            respond(e, 307)
        }
        assertEquals(ConnectionCheck.Failure(ConnectionProblem.Unknown), client().checkConnection())
        assertTrue(seenByOther.isEmpty())
    }

    // ----------------------------------------------------------- listRecipes

    @Test
    fun `listar recetas construye la consulta en el nucleo y manda el token`() {
        routes[RECIPES] = { e -> respond(e, 200, LIST_OK) }
        val query = RecipeListQuery(
            page = 2,
            perPage = 10,
            search = "tomate frito",
            orderBy = "name",
            orderDirection = SortDirection.ASCENDING,
        )
        val page = client().listRecipes(query)
        assertEquals("page=2&perPage=10&search=tomate%20frito&orderBy=name&orderDirection=asc", seen[0].query)
        assertEquals("Bearer tok-secreto-ABC123xyz", seen[0].authorization)
        assertEquals(2, page.page)
        assertEquals(10, page.perPage)
        assertEquals(25L, page.total)
        assertEquals(3, page.totalPages)
        assertTrue(page.hasNext)
        assertEquals(listOf("tortilla", "gazpacho"), page.items.map { it.slug })
        val first = page.items[0]
        assertEquals("Tortilla", first.name)
        assertEquals(4.0, first.servings!!, 0.0)
        assertEquals("1 h", first.times.total)
        assertEquals(3600, first.times.totalSeconds)
        assertEquals(listOf("Cena"), first.categories.map { it.name })
        assertEquals(listOf("huevos"), first.tags.map { it.slug })
    }

    @Test
    fun `los next y previous del servidor no se siguen nunca`() {
        routes[RECIPES] = { e ->
            val other = "http://127.0.0.1:${otherServer.address.port}/api/recipes?page=3"
            respond(
                e, 200,
                """{"page":2,"per_page":1,"total":5,"total_pages":5,"next":"$other","previous":"$other","items":[{"slug":"a"}]}""",
            )
        }
        val page = client().listRecipes(RecipeListQuery(page = 2, perPage = 1))
        assertEquals(3, page.nextPage)
        assertEquals(1, page.previousPage)
        assertEquals(1, seen.size)
        assertTrue(seenByOther.isEmpty())
    }

    @Test
    fun `lista vacia o campos ausentes usan los valores de la consulta`() {
        routes[RECIPES] = { e -> respond(e, 200, "{}") }
        val page = client().listRecipes(RecipeListQuery(page = 4, perPage = 15))
        assertEquals(4, page.page)
        assertEquals(15, page.perPage)
        assertEquals(0L, page.total)
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `total ausente se deduce de las filas`() {
        routes[RECIPES] = { e -> respond(e, 200, """{"items":[{"slug":"a"},{"slug":"b"}]}""") }
        assertEquals(2L, client().listRecipes(RecipeListQuery()).total)
    }

    @Test
    fun `campos null y ausentes en las filas no rompen la lista`() {
        routes[RECIPES] = { e ->
            respond(
                e, 200,
                """{"page":null,"per_page":null,"total":null,"next":null,"items":[
                    null,
                    {"id":null,"slug":"a","name":null,"description":null,"image":null,"recipeServings":null,
                     "rating":null,"totalTime":null,"recipeCategory":null,"tags":null,"campoNuevo":{"x":[1,2]}},
                    {"slug":"b","recipeCategory":[null,{"name":null,"slug":null},{"name":"Postre"}],"tags":[]}
                ]}""",
            )
        }
        val page = client().listRecipes(RecipeListQuery())
        assertEquals(listOf("a", "b"), page.items.map { it.slug })
        assertEquals("", page.items[0].name)
        assertNull(page.items[0].id)
        assertNull(page.items[0].servings)
        assertFalse(page.items[0].hasImage)
        assertEquals(listOf("Postre"), page.items[1].categories.map { it.name })
        assertEquals("", page.items[1].categories[0].slug)
    }

    @Test
    fun `las filas con slug fuera de la lista blanca se descartan`() {
        val slugs = listOf("ok-1", "../x", "a%2fb", "", "con espacio", "a;b", "a/b", "añ", "a?b=1", "a\\nb")
        val items = slugs.joinToString(",") { """{"slug":"$it","name":"n"}""" } + "," + """{"name":"sin slug"},{"slug":null}"""
        routes[RECIPES] = { e -> respond(e, 200, """{"items":[$items]}""") }
        val page = client().listRecipes(RecipeListQuery())
        assertEquals(listOf("ok-1"), page.items.map { it.slug })
    }

    @Test
    fun `nombres y descripciones hostiles se sanean`() {
        routes[RECIPES] = { e ->
            respond(
                e, 200,
                """{"items":[{"slug":"a","name":"Tarta‮gnp.exe​\u0007 rica","description":"L1\r\n\r\n\r\n\r\nL2\u0000","recipeCategory":[{"name":"Ca‮t","slug":"cat"}]}]}""",
            )
        }
        val item = client().listRecipes(RecipeListQuery()).items[0]
        assertEquals("Tartagnp.exe rica", item.name)
        assertEquals("L1\n\nL2", item.description)
        assertEquals("Cat", item.categories[0].name)
    }

    @Test
    fun `la imagen se construye con el id y nunca con lo que dice el servidor`() {
        routes[RECIPES] = { e ->
            respond(
                e, 200,
                """{"items":[
                  {"slug":"a","id":"0F8FAD5B-D9CB-469F-A165-70867728950E","image":"http://evil.example/x.png"},
                  {"slug":"b","id":"no-es-un-uuid","image":"abcd"},
                  {"slug":"c","id":"0f8fad5b-d9cb-469f-a165-70867728950e","image":null},
                  {"slug":"d","id":"0f8fad5b-d9cb-469f-a165-70867728950e","image":""},
                  {"slug":"e","id":"0f8fad5b-d9cb-469f-a165-70867728950e","image":true},
                  {"slug":"f","id":"0f8fad5b-d9cb-469f-a165-70867728950e","image":{"url":"http://evil.example"}}
                ]}""",
            )
        }
        val items = client().listRecipes(RecipeListQuery()).items
        assertEquals(
            "/api/media/recipes/0f8fad5b-d9cb-469f-a165-70867728950e/images/min-original.webp",
            items[0].imagePath(),
        )
        assertEquals(
            "/api/media/recipes/0f8fad5b-d9cb-469f-a165-70867728950e/images/original.webp",
            items[0].imagePath(ImageSize.Original),
        )
        assertEquals("GET", items[0].imageRequest()!!.method.name)
        assertNull("id que no es UUID", items[1].imagePath())
        for (index in 2..5) assertNull("sin imagen válida #$index", items[index].imagePath())
    }

    @Test
    fun `una pagina con muchas mas filas que el tope se recorta`() {
        val items = (1..300).joinToString(",") { """{"slug":"r$it"}""" }
        routes[RECIPES] = { e -> respond(e, 200, """{"items":[$items]}""") }
        val page = client().listRecipes(RecipeListQuery())
        assertEquals(RecipeListQuery.MAX_PER_PAGE, page.items.size)
    }

    @Test
    fun `errores HTTP al listar llegan como HttpError sin token`() {
        for (status in listOf(401, 403, 404, 422, 500, 503)) {
            routes[RECIPES] = { e -> respond(e, status, "{\"detail\":\"${token.value}\"}") }
            val error = expectNetworkError { client().listRecipes(RecipeListQuery()) }
            assertTrue(error is NetworkError.HttpError)
            assertEquals(status, (error as NetworkError.HttpError).statusCode)
            assertNoSecret(error)
        }
    }

    @Test
    fun `respuesta enorme al listar se limita`() {
        routes[RECIPES] = { e -> respond(e, 200, "{\"items\":[" + "{\"slug\":\"a\"},".repeat(5_000) + "{\"slug\":\"b\"}]}") }
        val error = expectNetworkError { client(maxBytes = 10_000).listRecipes(RecipeListQuery()) }
        assertSame(NetworkError.ResponseTooLarge, error)
    }

    @Test
    fun `cuerpos que no son el JSON esperado dan InvalidResponse`() {
        val bodies = listOf("", "<html>hola</html>", "[]", "null", "\"texto\"", "{\"items\":5}", "{\"items\":[1,2]}", "{\"page\":\"x\"}", "{")
        for (body in bodies) {
            routes[RECIPES] = { e -> respond(e, 200, body) }
            val error = expectNetworkError { client().listRecipes(RecipeListQuery()) }
            assertSame(body, NetworkError.InvalidResponse, error)
        }
    }

    @Test
    fun `un anidado enorme en un campo no tipado nunca tumba al cliente`() {
        val depth = 100_000
        val nested = "[".repeat(depth) + "]".repeat(depth)
        routes[RECIPES] = { e ->
            respond(e, 200, """{"items":[{"slug":"a","image":$nested,"extras":$nested}]}""")
        }
        // Sale bien (la imagen no es un texto) o se rechaza como inválida; lo
        // que no puede pasar es otra excepción ni un StackOverflowError suelto.
        try {
            val page = client(maxBytes = 1_000_000).listRecipes(RecipeListQuery())
            assertFalse(page.items[0].hasImage)
        } catch (error: NetworkError) {
            assertSame(NetworkError.InvalidResponse, error)
        }
    }

    // ------------------------------------------------------------- getRecipe

    @Test
    fun `leer una receta completa`() {
        routes["$RECIPES/tortilla"] = { e -> respond(e, 200, RECIPE_FULL) }
        val recipe = client().getRecipe("tortilla")
        assertEquals("/api/recipes/tortilla", seen[0].path)
        assertEquals("Bearer tok-secreto-ABC123xyz", seen[0].authorization)
        assertNull(seen[0].query)

        assertEquals("tortilla", recipe.slug)
        assertEquals("Tortilla de patatas", recipe.name)
        assertEquals("Clásica.", recipe.description)
        assertEquals(4.0, recipe.servings!!, 0.0)
        assertEquals("4 raciones", recipe.yieldText)
        assertEquals(4.5, recipe.rating!!, 0.0)
        assertEquals("https://ejemplo.org/tortilla", recipe.sourceUrl)
        assertEquals("30 min", recipe.times.prep)
        assertEquals(1800, recipe.times.prepSeconds)
        assertEquals(listOf("Cena"), recipe.categories.map { it.name })
        assertEquals(listOf("huevos", "patatas"), recipe.tags.map { it.name })

        // La entrada nula y la línea vacía se descartan.
        assertEquals(3, recipe.ingredients.size)
        val oil = recipe.ingredients[0]
        assertEquals(2.0, oil.quantity!!, 0.0)
        assertEquals("cucharada", oil.unitSingular)
        assertEquals("cucharadas", oil.unitPlural)
        assertEquals("aceite", oil.foodSingular)
        assertEquals("aceite", oil.foodPlural)
        assertEquals("de oliva", oil.note)
        val grams = recipe.ingredients[1]
        assertEquals("g", grams.unitSingular)
        assertEquals("g", grams.unitPlural)
        val salt = recipe.ingredients[2]
        assertEquals(0.0, salt.quantity!!, 0.0)
        assertEquals("sal al gusto", salt.display)

        assertEquals(1, recipe.steps.size)
        assertEquals("Pela las patatas", recipe.steps[0].text.toPlainText())
        assertEquals(1, recipe.notes.size)
        assertEquals("Truco", recipe.notes[0].title)
        assertEquals("Guárdala en nevera", recipe.notes[0].text.toPlainText())

        assertTrue(recipe.hasImage)
        assertEquals(
            "/api/media/recipes/0f8fad5b-d9cb-469f-a165-70867728950e/images/min-original.webp",
            recipe.imagePath(),
        )
    }

    @Test
    fun `la imagen usa el id y no el slug`() {
        routes["$RECIPES/tortilla"] = { e -> respond(e, 200, RECIPE_FULL) }
        val path = client().getRecipe("tortilla").imagePath()!!
        assertFalse(path.contains("tortilla"))
    }

    @Test
    fun `instrucciones y notas hostiles quedan sin nada ejecutable`() {
        val hostile = "<p>Hola</p><script>alert(1)</script><iframe src=\\\"http://evil.example\\\"></iframe>" +
            "<style>body{display:none}</style><p onclick=\\\"robar()\\\">Dos <a href=\\\"javascript:alert(2)\\\">enlace</a></p>" +
            "<img src=x onerror=alert(3)><object data=x></object><embed src=x>"
        routes["$RECIPES/mala"] = { e ->
            respond(
                e, 200,
                """{"slug":"mala","name":"M","recipeInstructions":[{"text":"$hostile"}],
                    "notes":[{"title":"t","text":"$hostile"}]}""",
            )
        }
        val recipe = client().getRecipe("mala")
        for (text in listOf(recipe.steps[0].text.toPlainText(), recipe.notes[0].text.toPlainText())) {
            assertTrue(text, text.contains("Hola"))
            assertTrue(text, text.contains("Dos"))
            assertTrue(text, text.contains("enlace"))
            for (forbidden in listOf("alert", "script", "iframe", "evil", "javascript", "onclick", "onerror", "robar", "display", "<", ">")) {
                assertFalse("«$forbidden» en: $text", text.contains(forbidden, ignoreCase = true))
            }
        }
    }

    @Test
    fun `receta vacia o con todo null no rompe nada`() {
        routes["$RECIPES/vacia"] = { e -> respond(e, 200, "{}") }
        val recipe = client().getRecipe("vacia")
        assertEquals("vacia", recipe.slug)
        assertEquals("", recipe.name)
        assertNull(recipe.id)
        assertNull(recipe.servings)
        assertNull(recipe.rating)
        assertNull(recipe.sourceUrl)
        assertFalse(recipe.hasImage)
        assertNull(recipe.imagePath())
        assertTrue(recipe.ingredients.isEmpty() && recipe.steps.isEmpty() && recipe.notes.isEmpty())

        routes["$RECIPES/nulos"] = { e ->
            respond(
                e, 200,
                """{"id":null,"userId":null,"name":null,"slug":null,"image":null,"recipeServings":null,
                   "recipeYield":null,"totalTime":null,"prepTime":null,"cookTime":null,"performTime":null,
                   "totalTimeSeconds":null,"description":null,"recipeCategory":null,"tags":null,"tools":null,
                   "rating":null,"orgURL":null,"recipeIngredient":null,"recipeInstructions":null,
                   "nutrition":null,"settings":null,"assets":null,"notes":null,"extras":null,"comments":null}""",
            )
        }
        val nulls = client().getRecipe("nulos")
        assertEquals("nulos", nulls.slug)
        assertEquals("", nulls.description)
        assertTrue(nulls.tags.isEmpty())
        assertTrue(nulls.steps.isEmpty())
    }

    @Test
    fun `valores raros se acotan`() {
        routes["$RECIPES/raros"] = { e ->
            respond(
                e, 200,
                """{"slug":"../otra","recipeServings":0,"rating":9.5,"orgURL":"javascript:alert(1)",
                   "totalTimeSeconds":-5,
                   "recipeIngredient":[{"quantity":-2,"note":"a"},{"quantity":1e12,"note":"b"}]}""",
            )
        }
        val recipe = client().getRecipe("raros")
        assertEquals("slug inválido del servidor: se usa el pedido", "raros", recipe.slug)
        assertNull(recipe.servings)
        assertEquals(5.0, recipe.rating!!, 0.0)
        assertNull(recipe.sourceUrl)
        assertNull(recipe.times.totalSeconds)
        assertNull(recipe.ingredients[0].quantity)
        assertNull(recipe.ingredients[1].quantity)
    }

    @Test
    fun `urls de origen que no son http o https se descartan`() {
        val cases = mapOf(
            "https://ejemplo.org/a?b=c" to "https://ejemplo.org/a?b=c",
            "http://ejemplo.org" to "http://ejemplo.org",
            "javascript:alert(1)" to null,
            "data:text/html,<script>1</script>" to null,
            "file:///etc/passwd" to null,
            "intent://x#Intent;end" to null,
            "//ejemplo.org/x" to null,
            "https://user:pass@ejemplo.org/" to null,
            "https://ejemplo.org/con espacio" to null,
        )
        for ((raw, expected) in cases) {
            routes["$RECIPES/u"] = { e -> respond(e, 200, """{"orgURL":"$raw"}""") }
            assertEquals(raw, expected, client().getRecipe("u").sourceUrl)
        }
    }

    @Test
    fun `las listas enormes se recortan`() {
        val ingredients = (1..2_000).joinToString(",") { """{"note":"i$it"}""" }
        val steps = (1..2_000).joinToString(",") { """{"text":"<p>p$it</p>"}""" }
        val tags = (1..2_000).joinToString(",") { """{"name":"t$it","slug":"t$it"}""" }
        routes["$RECIPES/grande"] = { e ->
            respond(e, 200, """{"recipeIngredient":[$ingredients],"recipeInstructions":[$steps],"tags":[$tags]}""")
        }
        val recipe = client(maxBytes = 1_000_000).getRecipe("grande")
        assertEquals(500, recipe.ingredients.size)
        assertEquals(500, recipe.steps.size)
        assertEquals(100, recipe.tags.size)
    }

    @Test
    fun `un slug invalido ni siquiera sale de la app`() {
        val bad = listOf(
            "", " ", "../admin", "..", ".", "a/b", "a%2fb", "a%2Fb", "a%2e%2e", "a b", "a?x=1", "a#b", "a;b",
            "a\nb", "tortilla\n", "a\u0000b", "añ", "a\\b", "a:b", "a@b", "-".repeat(256), "a".repeat(256),
        )
        for (slug in bad) {
            val error = assertThrows(IllegalArgumentException::class.java) { client().getRecipe(slug) }
            assertFalse("el mensaje no repite el slug", slug.length > 3 && error.message!!.contains(slug))
        }
        assertTrue("no se envió ninguna petición", seen.isEmpty())
    }

    @Test
    fun `slugs validos pasan`() {
        for (slug in listOf("tortilla", "Tortilla-1", "a_b", "123", "a".repeat(255))) {
            routes["$RECIPES/$slug"] = { e -> respond(e, 200, "{}") }
            assertEquals(slug, client().getRecipe(slug).slug)
        }
    }

    @Test
    fun `errores HTTP al leer una receta`() {
        for (status in listOf(401, 403, 404, 422, 500)) {
            routes["$RECIPES/x"] = { e -> respond(e, status, "{\"detail\":[{\"msg\":\"${token.value}\"}]}") }
            val error = expectNetworkError { client().getRecipe("x") }
            assertEquals(status, (error as NetworkError.HttpError).statusCode)
            assertNoSecret(error)
        }
    }

    @Test
    fun `respuesta enorme al leer una receta se limita`() {
        routes["$RECIPES/x"] = { e -> respond(e, 200, "{\"description\":\"" + "a".repeat(50_000) + "\"}") }
        val error = expectNetworkError { client(maxBytes = 10_000).getRecipe("x") }
        assertSame(NetworkError.ResponseTooLarge, error)
    }

    @Test
    fun `receta con cuerpo invalido es InvalidResponse`() {
        for (body in listOf("", "<html></html>", "[]", "{\"recipeIngredient\":\"x\"}", "{\"recipeServings\":\"mucho\"}")) {
            routes["$RECIPES/x"] = { e -> respond(e, 200, body) }
            assertSame(body, NetworkError.InvalidResponse, expectNetworkError { client().getRecipe("x") })
        }
    }

    @Test
    fun `sin servidor al leer una receta es ConnectionRefused y sin token en el mensaje`() {
        server.stop(0)
        val error = expectNetworkError { client().getRecipe("x") }
        assertSame(NetworkError.ConnectionRefused, error)
        assertNoSecret(error)
    }

    @Test
    fun `una redireccion de una receta a otro origen se rechaza`() {
        routes["$RECIPES/x"] = { e ->
            e.responseHeaders.add("Location", "http://127.0.0.1:${otherServer.address.port}/api/recipes/x")
            respond(e, 301)
        }
        assertSame(NetworkError.RedirectRejected, expectNetworkError { client().getRecipe("x") })
        assertTrue(seenByOther.isEmpty())
    }

    @Test
    fun `los resultados no exponen el token al imprimirse`() {
        routes[ABOUT] = { e -> respond(e, 200, ABOUT_OK) }
        routes[SELF] = { e -> respond(e, 401) }
        val result = client().checkConnection()
        assertFalse(result.toString().contains(token.value))
        assertFalse(client().toString().contains(token.value))
    }

    // ----------------------------------------------------------------- ayudas

    private fun client(
        maxBytes: Long = 1_000_000,
        aboutMaxBytes: Long = 64 * 1024,
        selfMaxBytes: Long = maxBytes,
    ): MealieClient {
        val base = URI("http://127.0.0.1:${server.address.port}")
        return MealieClient(
            transport = UrlConnectionTransport(
                baseUri = base,
                token = token,
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
                maxResponseBytes = maxBytes,
            ),
            anonymousTransport = UrlConnectionTransport(
                baseUri = base,
                token = null,
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
                maxResponseBytes = aboutMaxBytes,
            ),
            selfTransport = UrlConnectionTransport(
                baseUri = base,
                token = token,
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
                maxResponseBytes = selfMaxBytes,
            ),
        )
    }

    private fun expectNetworkError(block: () -> Unit): NetworkError {
        try {
            block()
        } catch (error: NetworkError) {
            return error
        }
        fail("Debía lanzar un NetworkError")
        throw AssertionError()
    }

    private fun assertNoSecret(error: NetworkError) {
        assertFalse(error.message.orEmpty().contains(token.value))
        assertFalse(error.toString().contains(token.value))
        assertNull(error.cause)
        assertEquals(0, error.stackTrace.size)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String = "") {
        try {
            val payload = body.toByteArray(Charsets.UTF_8)
            if (payload.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        } catch (ignored: IOException) {
            // El cliente cortó a propósito (límite de bytes): no es un fallo de la prueba.
        } finally {
            exchange.close()
        }
    }

    private companion object {
        const val ABOUT = "/api/app/about"
        const val SELF = "/api/users/self"
        const val RECIPES = "/api/recipes"

        // Forma tomada de AppInfo en docs/openapi.json; los valores son de prueba.
        const val ABOUT_OK = """{"production":true,"version":"v3.29.0","demoStatus":false,"allowSignup":false,
            "allowPasswordLogin":true,"defaultGroupSlug":null,"enableOidc":false,"oidcRedirect":false,
            "oidcProviderName":"OAuth","enableReverseProxyAuth":false,"tokenTime":48,"allowedIframeHosts":[],
            "brandingName":"Mealie","brandingLogoUrl":null}"""

        const val LIST_OK = """{"page":2,"per_page":10,"total":25,"total_pages":3,"next":null,"previous":null,
            "items":[
              {"id":"0f8fad5b-d9cb-469f-a165-70867728950e","slug":"tortilla","name":"Tortilla","description":"d",
               "image":"abcd","recipeServings":4,"totalTime":"1 h","totalTimeSeconds":3600,
               "recipeCategory":[{"id":"c1","name":"Cena","slug":"cena"}],
               "tags":[{"id":"t1","name":"Huevos","slug":"huevos"}],"campoFuturo":true},
              {"slug":"gazpacho","name":"Gazpacho"}
            ]}"""

        const val RECIPE_FULL = """{
            "id":"0f8fad5b-d9cb-469f-a165-70867728950e","slug":"tortilla","name":"Tortilla de patatas",
            "description":"Clásica.","image":"abcd","recipeServings":4,"recipeYield":"4 raciones","rating":4.5,
            "orgURL":"https://ejemplo.org/tortilla","prepTime":"30 min","prepTimeSeconds":1800,
            "recipeCategory":[{"name":"Cena","slug":"cena"}],
            "tags":[{"name":"huevos","slug":"huevos"},{"name":"patatas","slug":"patatas"}],
            "recipeIngredient":[
              {"quantity":2.0,"unit":{"name":"cucharada","pluralName":"cucharadas","abbreviation":"cda","useAbbreviation":false},
               "food":{"name":"aceite","pluralName":null},"note":"de oliva","display":"2 cucharadas aceite de oliva"},
              null,
              {"quantity":300,"unit":{"name":"gramo","abbreviation":"g","useAbbreviation":true},"food":{"name":"patata"},"note":"","display":"300 g patata"},
              {"quantity":0,"unit":null,"food":null,"note":"sal al gusto","display":"sal al gusto"},
              {"quantity":null,"unit":null,"food":null,"note":null,"display":""}
            ],
            "recipeInstructions":[{"id":null,"title":null,"summary":"","text":"<p>Pela las patatas</p>"},{"text":""},null],
            "notes":[{"title":"Truco","text":"<p>Guárdala en nevera</p>"}],
            "nutrition":{"calories":"300"},"settings":{"public":false},"assets":[],"extras":{},"comments":[]}"""
    }
}
