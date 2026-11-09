package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Pruebas del cliente del planificador contra el `HttpServer` del JDK:
 * consulta, paginación, cabecera del token, códigos de error, respuestas
 * enormes, textos hostiles e identificadores y fechas que no deben salir a la
 * red.
 */
class MealPlanClientTest {

    private lateinit var server: FakeMealPlanServer

    private val weekStart = LocalDate.of(2026, 11, 9)
    private val weekEnd = LocalDate.of(2026, 11, 15)

    @Before
    fun start() {
        server = FakeMealPlanServer()
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun entriesOf(result: MealPlanResult<List<PlannedMeal>>): List<PlannedMeal> =
        (result as? MealPlanResult.Success<List<PlannedMeal>>)?.value
            ?: throw AssertionError("Se esperaba un resultado correcto, no $result")

    private fun entryOf(result: MealPlanResult<PlannedMeal>): PlannedMeal =
        (result as? MealPlanResult.Success<PlannedMeal>)?.value
            ?: throw AssertionError("Se esperaba un resultado correcto, no $result")

    private fun assertNoSecrets(result: Any) {
        val text = result.toString()
        assertFalse(text, text.contains(server.token.value))
        assertFalse(text, text.contains("127.0.0.1"))
    }

    private fun bodyOf(request: FakeMealPlanServer.Seen): JsonObject =
        CoreJson.json.parseToJsonElement(request.body).jsonObject

    /** Una entrada con campo desconocidos, nota con salto de línea y datos que el modelo sanea. */
    private fun rawEntryJson(): String = """
        {"id":7,"date":"2026-11-10","entryType":"lunch","title":"  Sopa  ","text":"línea\nsalto",
         "groupId":"${MealPlanFixtures.GROUP}","userId":"${MealPlanFixtures.USER}",
         "recipeId":"no-es-uuid","recipeScale":150.0,
         "extra":{"a":1},"recipeReferences":[{"x":1}]}
    """.trimIndent()

    private fun rawPageJson(): String = """{"page":1,"per_page":10,"total":1,"items":[${rawEntryJson()}]}"""

    // ------------------------------------------------------------- lectura

    @Test
    fun `listar una semana manda las fechas, pagina y el token`() {
        server.route("GET", MealPlanClient.MEALPLANS_PATH) { seen, exchange ->
            val query = seen.query.orEmpty()
            val body = if (query.contains("page=1&")) {
                MealPlanFixtures.pageJson(
                    items = listOf(
                        MealPlanFixtures.entryJson(id = 1, type = "lunch"),
                        MealPlanFixtures.entryJson(id = 2, type = "dinner"),
                    ),
                    page = 1,
                    perPage = 2,
                    total = 3,
                )
            } else {
                MealPlanFixtures.pageJson(
                    items = listOf(MealPlanFixtures.entryJson(id = 3, type = "breakfast")),
                    page = 2,
                    perPage = 2,
                    total = 3,
                )
            }
            FakeMealPlanServer.reply(exchange, 200, body.toByteArray())
        }

        val entries = entriesOf(server.client().listEntries(weekStart, weekEnd))

        assertEquals(3, entries.size)
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.id })
        val requests = server.requestsTo("GET", MealPlanClient.MEALPLANS_PATH)
        assertEquals(2, requests.size)
        assertEquals(
            "start_date=2026-11-09&end_date=2026-11-15&page=1&perPage=100",
            requests[0].query,
        )
        assertEquals(
            "start_date=2026-11-09&end_date=2026-11-15&page=2&perPage=100",
            requests[1].query,
        )
        assertTrue(requests.all { it.authorization == "Bearer ${server.token.value}" })
        assertNoSecrets(entries)
    }

    @Test
    fun `si el servidor repite la pagina se corta y no se duplican entradas`() {
        // Contesta siempre «page: 1» aunque se le pida la 2: seguir daría lo mismo sin fin.
        server.fixed(
            "GET",
            MealPlanClient.MEALPLANS_PATH,
            200,
            MealPlanFixtures.pageJson(
                items = listOf(MealPlanFixtures.entryJson(id = 1, type = "lunch")),
                page = 1,
                perPage = 1,
                total = 100,
            ),
        )

        val entries = entriesOf(server.client().listEntries(weekStart, weekEnd))

        assertEquals(1, entries.size)
        val requests = server.requestsTo("GET", MealPlanClient.MEALPLANS_PATH)
        assertEquals(2, requests.size)
    }

    @Test
    fun `la paginacion se corta cuando una pagina no aporta ids nuevos`() {
        server.route("GET", MealPlanClient.MEALPLANS_PATH) { seen, exchange ->
            val page = Regex("page=(\\d+)").find(seen.query.orEmpty())?.groupValues?.get(1)?.toInt() ?: 1
            val items = listOf(MealPlanFixtures.entryJson(id = 1), MealPlanFixtures.entryJson(id = 2))
            val body = MealPlanFixtures.pageJson(items = items, page = page, perPage = 2, total = 100)
            FakeMealPlanServer.reply(exchange, 200, body.toByteArray())
        }

        val entries = entriesOf(server.client().listEntries(weekStart, weekEnd))

        // La segunda página repite los mismos ids: no hay una tercera petición.
        assertEquals(listOf(1L, 2L), entries.map { it.id })
        assertEquals(2, server.requestsTo("GET", MealPlanClient.MEALPLANS_PATH).size)
    }

    @Test
    fun `la lectura se acota al tope de entradas`() {
        val items = (1L..2_500L).map { MealPlanFixtures.entryJson(id = it) }
        server.fixed(
            "GET",
            MealPlanClient.MEALPLANS_PATH,
            200,
            MealPlanFixtures.pageJson(items = items, page = 1, perPage = 2_500, total = 2_500),
        )

        val entries = entriesOf(server.client(maxBytes = 2_000_000).listEntries(weekStart, weekEnd))

        assertEquals(MAX_MEAL_PLAN_ENTRIES, entries.size)
    }

    @Test
    fun `una respuesta enorme es un fallo y no revienta`() {
        server.fixed("GET", MealPlanClient.MEALPLANS_PATH, 200, "x".repeat(5_000))

        val result = server.client(maxBytes = 1_000).listEntries(weekStart, weekEnd)

        assertEquals(MealPlanResult.Failure(ConnectionProblem.Unknown), result)
        assertNoSecrets(result)
    }

    @Test
    fun `los codigos de error se distinguen y no se reintentan`() {
        val cases = listOf(
            401 to MealPlanResult.Unauthorized,
            403 to MealPlanResult.Forbidden,
            404 to MealPlanResult.NotFound,
            500 to MealPlanResult.Failure(ConnectionProblem.ServerError),
        )
        for ((status, expected) in cases) {
            server.seen.clear()
            server.fixed("GET", MealPlanClient.MEALPLANS_PATH, status, "{\"detail\":\"SECRETO\"}")
            val result = server.client().listEntries(weekStart, weekEnd)
            assertEquals(status.toString(), expected, result)
            assertEquals(status.toString(), 1, server.requestsTo("GET", MealPlanClient.MEALPLANS_PATH).size)
            assertNoSecrets(result)
        }
    }

    @Test
    fun `el 422 entrega mensajes saneados y acotados`() {
        server.fixed(
            "GET",
            MealPlanClient.MEALPLANS_PATH,
            422,
            "{\"detail\":[{\"loc\":[\"query\",\"start_date\"],\"msg\":\"valor no v\\u00e1lido\\u202e\"}," +
                "{\"msg\":\"<script>alert(1)</script>\"},{\"msg\":123}]}",
        )

        val result = server.client().listEntries(weekStart, weekEnd)

        assertTrue(result.toString(), result is MealPlanResult.Invalid)
        val messages = (result as MealPlanResult.Invalid).messages
        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("valor no válido"))
        assertFalse(messages[0].contains('\u202e'))
        // El HTML se queda como texto: nunca se ejecuta; aquí solo se comprueba que llega sin controles.
        assertTrue(messages[1].contains("<script>"))
        assertNoSecrets(result)
    }

    @Test
    fun `las entradas con fecha o id rotos se descartan y el tipo nuevo no rompe`() {
        server.fixed(
            "GET",
            MealPlanClient.MEALPLANS_PATH,
            200,
            MealPlanFixtures.pageJson(
                items = listOf(
                    MealPlanFixtures.entryJson(id = 1, date = "mañana"),
                    MealPlanFixtures.entryJson(id = 0),
                    MealPlanFixtures.entryJson(id = 99_999_999_999L),
                    MealPlanFixtures.entryJson(id = 2, date = "+10000-01-01"),
                    MealPlanFixtures.entryJson(id = 3, date = "2026-11-10", type = "brunch"),
                ),
                page = 1,
                perPage = 10,
                total = 5,
            ),
        )

        val entries = entriesOf(server.client().listEntries(weekStart, weekEnd))

        assertEquals(1, entries.size)
        assertEquals(3L, entries[0].id)
        assertEquals(MealType.UNKNOWN, entries[0].mealType)
    }

    @Test
    fun `los textos hostiles se sanean al entrar en el modelo`() {
        server.fixed(
            "GET",
            MealPlanClient.MEALPLANS_PATH,
            200,
            MealPlanFixtures.pageJson(
                items = listOf(
                    MealPlanFixtures.entryJson(
                        id = 1,
                        title = "Sopa\u202e\u0000 <script>alert(1)</script>",
                        text = "nota\u202d con\u0007 control",
                        recipeId = MealPlanFixtures.RECIPE,
                        recipeName = "Tarta\u202e <b>rica</b>",
                    ),
                ),
            ),
        )

        val entry = entriesOf(server.client().listEntries(weekStart, weekEnd)).single()

        assertFalse(entry.title.contains('\u202e'))
        assertFalse(entry.note.contains('\u202d'))
        assertFalse(entry.title.any { it.code < 0x20 })
        assertFalse(entry.note.any { it.code < 0x20 })
        assertTrue(entry.title.contains("<script>"))
        assertNotNull(entry.recipeName)
        assertFalse(entry.recipeName!!.contains('\u202e'))
        assertEquals(MealPlanFixtures.RECIPE, entry.recipeId)
        assertNoSecrets(entry)
    }

    @Test
    fun `hoy acepta una lista y el objeto paginado`() {
        server.fixed(
            "GET",
            MealPlanClient.TODAY_PATH,
            200,
            "[${MealPlanFixtures.entryJson(id = 1)}]",
        )
        assertEquals(listOf(1L), entriesOf(server.client().entriesToday()).map { it.id })

        server.fixed(
            "GET",
            MealPlanClient.TODAY_PATH,
            200,
            MealPlanFixtures.pageJson(listOf(MealPlanFixtures.entryJson(id = 2)), page = 1, total = 1),
        )
        assertEquals(listOf(2L), entriesOf(server.client().entriesToday()).map { it.id })

        val requests = server.requestsTo("GET", MealPlanClient.TODAY_PATH)
        assertEquals(2, requests.size)
        assertNull(requests[0].query)
        assertTrue(requests.all { it.authorization == "Bearer ${server.token.value}" })
    }

    @Test
    fun `hoy con una forma desconocida es un fallo, no una lista inventada`() {
        server.fixed("GET", MealPlanClient.TODAY_PATH, 200, "{\"hola\":1}")

        val result = server.client().entriesToday()

        assertEquals(MealPlanResult.Failure(ConnectionProblem.Unknown), result)
        assertNoSecrets(result)
    }

    @Test
    fun `leer una entrada por id y sus errores`() {
        server.fixed(
            "GET",
            "${MealPlanClient.MEALPLANS_PATH}/7",
            200,
            MealPlanFixtures.entryJson(id = 7, type = "dinner"),
        )

        val result = server.client().getEntry(7)

        assertEquals(7L, entryOf(result).id)

        server.fixed("GET", "${MealPlanClient.MEALPLANS_PATH}/8", 404, "")
        assertEquals(MealPlanResult.NotFound, server.client().getEntry(8))

        server.seen.clear()
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidEntryId),
            server.client().getEntry(0),
        )
        assertTrue(server.seen.isEmpty())
    }

    // ------------------------------------------------------------- escritura

    @Test
    fun `crear manda solo lo rellenado y el token`() {
        server.fixed(
            "POST",
            MealPlanClient.MEALPLANS_PATH,
            201,
            MealPlanFixtures.entryJson(
                id = 10,
                date = "2026-11-10",
                type = "dinner",
                title = "Tarta",
                recipeId = MealPlanFixtures.RECIPE,
                recipeName = "Tarta de queso",
            ),
        )
        val draft = MealPlanDraft(
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.DINNER,
            recipeId = MealPlanFixtures.RECIPE,
            title = "  Tarta  ",
        )

        val result = server.client().createEntry(draft)

        val created = entryOf(result)
        assertEquals(10L, created.id)
        assertEquals(MealType.DINNER, created.mealType)
        assertEquals("Tarta de queso", created.recipeName)

        val sent = server.requestsTo("POST", MealPlanClient.MEALPLANS_PATH).single()
        assertEquals("Bearer ${server.token.value}", sent.authorization)
        assertTrue(sent.body.contains("\"date\":\"2026-11-10\""))
        assertTrue(sent.body.contains("\"entryType\":\"dinner\""))
        assertTrue(sent.body.contains("\"title\":\"Tarta\""))
        assertTrue(sent.body.contains("\"recipeId\":\"${MealPlanFixtures.RECIPE}\""))
        assertTrue(sent.body.contains("\"recipeScale\":1.0"))
        assertFalse(sent.body.contains("\"text\""))
    }

    @Test
    fun `crear con receta y sin titulo no inventa titulo`() {
        server.fixed(
            "POST",
            MealPlanClient.MEALPLANS_PATH,
            201,
            MealPlanFixtures.entryJson(id = 11, recipeId = MealPlanFixtures.RECIPE, recipeName = "Sopa"),
        )
        val draft = MealPlanDraft(
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
            recipeId = MealPlanFixtures.RECIPE,
        )

        server.client().createEntry(draft)

        val sent = server.requestsTo("POST", MealPlanClient.MEALPLANS_PATH).single()
        assertFalse(sent.body.contains("\"title\""))
        assertTrue(sent.body.contains("\"recipeId\":\"${MealPlanFixtures.RECIPE}\""))
    }

    @Test
    fun `crear un borrador invalido no envia nada`() {
        val empty = MealPlanDraft(
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
        )
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.EmptyEntry),
            server.client().createEntry(empty),
        )

        val unknownType = MealPlanDraft(
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.UNKNOWN,
            title = "Sopa",
        )
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidMealType),
            server.client().createEntry(unknownType),
        )
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `crear con 401 y 422 devuelve el error tipado sin reintentar`() {
        val draft = MealPlanDraft(date = LocalDate.of(2026, 11, 10), mealType = MealType.LUNCH, title = "Sopa")

        server.fixed("POST", MealPlanClient.MEALPLANS_PATH, 401, "")
        assertEquals(MealPlanResult.Unauthorized, server.client().createEntry(draft))

        server.seen.clear()
        server.fixed(
            "POST",
            MealPlanClient.MEALPLANS_PATH,
            422,
            "{\"detail\":[{\"msg\":\"fecha no válida\"}]}",
        )
        val invalid = server.client().createEntry(draft)
        assertEquals(MealPlanResult.Invalid(listOf("fecha no válida")), invalid)
        assertEquals(1, server.requestsTo("POST", MealPlanClient.MEALPLANS_PATH).size)
        assertNoSecrets(invalid)
    }

    @Test
    fun `un plazo agotado al crear no afirma que no se creo y no reintenta`() {
        server.route("POST", MealPlanClient.MEALPLANS_PATH) { _, exchange ->
            Thread.sleep(800)
            runCatching { FakeMealPlanServer.reply(exchange, 201, MealPlanFixtures.entryJson().toByteArray()) }
        }
        val draft = MealPlanDraft(date = LocalDate.of(2026, 11, 10), mealType = MealType.LUNCH, title = "Sopa")

        val result = server.client(readTimeoutMs = 200).createEntry(draft)

        assertEquals(MealPlanResult.MaybeCreated(ConnectionProblem.ServerNotResponding), result)
        assertEquals(1, server.requestsTo("POST", MealPlanClient.MEALPLANS_PATH).size)
        assertNoSecrets(result)
    }

    @Test
    fun `crear con un 5xx es incierto y no se reintenta`() {
        server.fixed("POST", MealPlanClient.MEALPLANS_PATH, 502, "")
        val draft = MealPlanDraft(date = LocalDate.of(2026, 11, 10), mealType = MealType.LUNCH, title = "Sopa")

        val result = server.client().createEntry(draft)

        assertEquals(MealPlanResult.MaybeCreated(ConnectionProblem.ServerError), result)
        assertEquals(1, server.requestsTo("POST", MealPlanClient.MEALPLANS_PATH).size)
        assertNoSecrets(result)
    }

    @Test
    fun `crear con un 2xx ilegible es incierto`() {
        server.fixed("POST", MealPlanClient.MEALPLANS_PATH, 201, "no es json")
        val draft = MealPlanDraft(date = LocalDate.of(2026, 11, 10), mealType = MealType.LUNCH, title = "Sopa")

        val result = server.client().createEntry(draft)

        assertEquals(MealPlanResult.MaybeCreated(ConnectionProblem.Unknown), result)
        assertEquals(1, server.requestsTo("POST", MealPlanClient.MEALPLANS_PATH).size)
    }

    @Test
    fun `mover parte del json crudo y cambia solo fecha y tipo`() {
        server.fixed("GET", MealPlanClient.MEALPLANS_PATH, 200, rawPageJson())
        server.fixed(
            "PUT",
            "${MealPlanClient.MEALPLANS_PATH}/7",
            200,
            MealPlanFixtures.entryJson(id = 7, date = "2026-11-11", type = "dinner"),
        )
        val client = server.client()
        val entry = entriesOf(client.listEntries(weekStart, weekEnd)).single()

        val result = client.moveEntry(entry, LocalDate.of(2026, 11, 11), MealType.DINNER)

        assertTrue(result is MealPlanResult.Success)
        val body = bodyOf(server.requestsTo("PUT", "${MealPlanClient.MEALPLANS_PATH}/7").single())
        assertEquals("2026-11-11", body["date"]!!.jsonPrimitive.content)
        assertEquals("dinner", body["entryType"]!!.jsonPrimitive.content)
        // Lo no editado sale tal cual del JSON crudo: título con espacios, nota
        // con salto de línea, receta no normalizable y claves desconocidas.
        assertEquals("  Sopa  ", body["title"]!!.jsonPrimitive.content)
        assertEquals("línea\nsalto", body["text"]!!.jsonPrimitive.content)
        assertEquals("no-es-uuid", body["recipeId"]!!.jsonPrimitive.content)
        assertEquals(150.0, body["recipeScale"]!!.jsonPrimitive.double, 0.0)
        assertTrue(body.containsKey("extra"))
        assertTrue(body.containsKey("recipeReferences"))
        assertEquals(7L, body["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun `editar parte del json crudo y no pierde claves`() {
        server.fixed("GET", MealPlanClient.MEALPLANS_PATH, 200, rawPageJson())
        server.fixed(
            "PUT",
            "${MealPlanClient.MEALPLANS_PATH}/7",
            200,
            MealPlanFixtures.entryJson(id = 7, date = "2026-11-10", type = "lunch", title = "Tostada", text = "sin gluten"),
        )
        val client = server.client()
        val entry = entriesOf(client.listEntries(weekStart, weekEnd)).single()
        val draft = MealPlanDraft(
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
            title = "Tostada",
            text = "sin gluten",
            recipeScale = 2.0,
        )

        val result = client.editEntry(entry, draft)

        assertTrue(result is MealPlanResult.Success)
        val body = bodyOf(server.requestsTo("PUT", "${MealPlanClient.MEALPLANS_PATH}/7").single())
        assertEquals("Tostada", body["title"]!!.jsonPrimitive.content)
        assertEquals("sin gluten", body["text"]!!.jsonPrimitive.content)
        assertEquals(2.0, body["recipeScale"]!!.jsonPrimitive.double, 0.0)
        assertEquals(JsonNull, body["recipeId"])
        // El resto del elemento no se toca.
        assertTrue(body.containsKey("extra"))
        assertTrue(body.containsKey("recipeReferences"))
    }

    @Test
    fun `mover manda la entrada completa con la identidad y la fecha nueva`() {
        val target = LocalDate.of(2026, 11, 11)
        server.fixed(
            "PUT",
            "${MealPlanClient.MEALPLANS_PATH}/7",
            200,
            MealPlanFixtures.entryJson(id = 7, date = "2026-11-11", type = "dinner", title = "Sopa", text = "nota"),
        )
        val entry = MealPlanFixtures.plannedMeal(
            id = 7,
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
            title = "Sopa",
            note = "nota",
        )

        val result = server.client().moveEntry(entry, target, MealType.DINNER)

        val moved = entryOf(result)
        assertEquals(target, moved.date)
        assertEquals(MealType.DINNER, moved.mealType)

        val sent = server.requestsTo("PUT", "${MealPlanClient.MEALPLANS_PATH}/7").single()
        assertEquals("Bearer ${server.token.value}", sent.authorization)
        assertTrue(sent.body.contains("\"id\":7"))
        assertTrue(sent.body.contains("\"groupId\":\"${MealPlanFixtures.GROUP}\""))
        assertTrue(sent.body.contains("\"userId\":\"${MealPlanFixtures.USER}\""))
        assertTrue(sent.body.contains("\"date\":\"2026-11-11\""))
        assertTrue(sent.body.contains("\"entryType\":\"dinner\""))
        assertTrue(sent.body.contains("\"title\":\"Sopa\""))
        assertTrue(sent.body.contains("\"text\":\"nota\""))
    }

    @Test
    fun `mover sin identidad o con fecha absurda no envia nada`() {
        val withoutIdentity = MealPlanFixtures.plannedMeal(
            id = 7,
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
            groupId = null,
            userId = null,
        )
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.MissingEntryIdentity),
            server.client().moveEntry(withoutIdentity, LocalDate.of(2026, 11, 11)),
        )

        val entry = MealPlanFixtures.plannedMeal(
            id = 7,
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
        )
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidDate),
            server.client().moveEntry(entry, LocalDate.of(10000, 1, 1)),
        )

        val badId = entry.copy(id = 0)
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidEntryId),
            server.client().moveEntry(badId, LocalDate.of(2026, 11, 11)),
        )
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `editar sustituye titulo, texto y receta y permite quitar la receta`() {
        server.fixed(
            "PUT",
            "${MealPlanClient.MEALPLANS_PATH}/8",
            200,
            MealPlanFixtures.entryJson(id = 8, title = "Tostada", text = "sin gluten"),
        )
        val entry = MealPlanFixtures.plannedMeal(
            id = 8,
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.BREAKFAST,
            title = "Pan",
            recipeId = MealPlanFixtures.RECIPE,
            recipeName = "Pan casero",
        )
        val draft = MealPlanDraft(
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.BREAKFAST,
            title = "Tostada",
            text = "sin gluten",
        )

        val result = server.client().editEntry(entry, draft)

        assertEquals("Tostada", entryOf(result).title)
        val sent = server.requestsTo("PUT", "${MealPlanClient.MEALPLANS_PATH}/8").single()
        assertTrue(sent.body.contains("\"title\":\"Tostada\""))
        assertTrue(sent.body.contains("\"text\":\"sin gluten\""))
        assertTrue(sent.body.contains("\"recipeId\":null"))
    }

    @Test
    fun `editar con un borrador invalido no envia nada`() {
        val entry = MealPlanFixtures.plannedMeal(
            id = 8,
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.BREAKFAST,
        )
        val draft = MealPlanDraft(
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.BREAKFAST,
            title = "a".repeat(MealPlanDraftValidator.MAX_TITLE + 1),
        )

        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.TitleTooLong),
            server.client().editEntry(entry, draft),
        )
        assertTrue(server.seen.isEmpty())
    }

    @Test
    fun `borrar usa DELETE y distingue los errores`() {
        server.fixed("DELETE", "${MealPlanClient.MEALPLANS_PATH}/9", 200, MealPlanFixtures.entryJson(id = 9))
        assertEquals(MealPlanResult.Success(Unit), server.client().deleteEntry(9))
        assertEquals(1, server.requestsTo("DELETE", "${MealPlanClient.MEALPLANS_PATH}/9").size)

        server.fixed("DELETE", "${MealPlanClient.MEALPLANS_PATH}/10", 404, "")
        assertEquals(MealPlanResult.NotFound, server.client().deleteEntry(10))

        server.seen.clear()
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidEntryId),
            server.client().deleteEntry(-1),
        )
        assertTrue(server.seen.isEmpty())
    }

    // ---------------------------------------------------------------- fechas

    @Test
    fun `los rangos absurdos se rechazan sin enviar nada`() {
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidRange),
            server.client().listEntries(weekEnd, weekStart),
        )
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.RangeTooWide),
            server.client().listEntries(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 2)),
        )
        assertEquals(
            MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidDate),
            server.client().listEntries(LocalDate.of(2026, 1, 1), LocalDate.of(10000, 1, 1)),
        )
        assertTrue(server.seen.isEmpty())
    }
}
