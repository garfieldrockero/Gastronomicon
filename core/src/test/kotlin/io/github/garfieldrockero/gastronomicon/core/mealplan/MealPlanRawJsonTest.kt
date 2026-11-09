package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * El JSON crudo de una entrada del planificador: mover y editar parten de él,
 * así que pasar de un tamaño no puede tirar `recipeReferences` ni las claves
 * que esta versión no conoce. Fijan qué se reduce y cuándo se cae al cuerpo
 * mínimo.
 */
class MealPlanRawJsonTest {

    private lateinit var server: FakeMealPlanServer

    private val weekStart = LocalDate.of(2026, 11, 9)
    private val weekEnd = LocalDate.of(2026, 11, 15)
    private val entryPath = "${MealPlanClient.MEALPLANS_PATH}/7"

    @Before
    fun start() {
        server = FakeMealPlanServer()
    }

    @After
    fun stop() {
        server.stop()
    }

    /** Una entrada de receta con un resumen de receta enorme (descripción larga). */
    private fun bigEntryJson(recipePadding: Int, extrasPadding: Int = 0): String = """
        {"id":7,"date":"2026-11-10","entryType":"lunch","title":"Sopa","text":"línea\nsalto",
         "groupId":"${MealPlanFixtures.GROUP}","userId":"${MealPlanFixtures.USER}",
         "recipeId":"${MealPlanFixtures.RECIPE}","recipeScale":2.0,
         "recipe":{"id":"${MealPlanFixtures.RECIPE}","slug":"sopa","name":"Sopa",
                   "description":"${"d".repeat(recipePadding)}"},
         "extra":{"a":1,"relleno":"${"y".repeat(extrasPadding)}"},
         "recipeReferences":[{"x":1}]}
    """.trimIndent()

    private fun bodyOf(request: FakeMealPlanServer.Seen): JsonObject =
        CoreJson.json.parseToJsonElement(request.body).jsonObject

    private fun read(json: String): PlannedMeal {
        server.fixed("GET", MealPlanClient.MEALPLANS_PATH, 200, MealPlanFixtures.pageJson(listOf(json)))
        val result = server.client().listEntries(weekStart, weekEnd)
        return (result as MealPlanResult.Success<List<PlannedMeal>>).value.single()
    }

    // ------------------------------------------------------- reducción del crudo

    @Test
    fun `una entrada pequena guarda el crudo entero`() {
        val raw = JsonObject(mapOf("id" to JsonPrimitive(7), "recipe" to JsonObject(emptyMap())))
        assertSame(raw, MealPlanMapper.boundedRaw(raw))
    }

    @Test
    fun `en el limite exacto no se quita nada y un caracter mas quita la receta`() {
        fun withLength(total: Int): JsonObject {
            // `{"k":"…","recipe":{}}`: la parte fija se descuenta del total pedido.
            val padding = total - """{"k":""}""".length - ""","recipe":{}""".length
            return JsonObject(
                linkedMapOf(
                    "k" to JsonPrimitive("z".repeat(padding)),
                    "recipe" to JsonObject(emptyMap()),
                ),
            )
        }
        val exacto = withLength(MealPlanMapper.MAX_RAW_ENTRY_CHARS)
        assertEquals(MealPlanMapper.MAX_RAW_ENTRY_CHARS, exacto.toString().length)
        assertTrue(MealPlanMapper.boundedRaw(exacto)!!.containsKey("recipe"))

        val pasado = withLength(MealPlanMapper.MAX_RAW_ENTRY_CHARS + 1)
        val reducido = MealPlanMapper.boundedRaw(pasado)!!
        assertFalse(reducido.containsKey("recipe"))
        assertTrue(reducido.containsKey("k"))
    }

    @Test
    fun `si aun sin la receta pasa del tope duro se descarta`() {
        val raw = CoreJson.json.parseToJsonElement(
            bigEntryJson(recipePadding = 10, extrasPadding = MealPlanMapper.MAX_RAW_ENTRY_HARD_CHARS),
        ).jsonObject
        assertNull(MealPlanMapper.boundedRaw(raw))
    }

    // ---------------------------------------------------------- PUT por la red

    @Test
    fun `mover una entrada de mas de 8 KiB conserva extras referencias y la identidad`() {
        val json = bigEntryJson(recipePadding = MealPlanMapper.MAX_RAW_ENTRY_CHARS)
        assertTrue(json.length > MealPlanMapper.MAX_RAW_ENTRY_CHARS)
        val entry = read(json)
        assertNotNull(entry.rawJson)
        // Lo que se enseña sigue saliendo del DTO.
        assertEquals("Sopa", entry.recipeName)

        server.fixed("PUT", entryPath, 200, MealPlanFixtures.entryJson(id = 7, date = "2026-11-11"))
        val result = server.client().moveEntry(entry, LocalDate.of(2026, 11, 11))

        assertTrue(result is MealPlanResult.Success)
        val body = bodyOf(server.requestsTo("PUT", entryPath).single())
        assertEquals("2026-11-11", body["date"]!!.jsonPrimitive.content)
        assertEquals(7L, body["id"]!!.jsonPrimitive.long)
        assertEquals(MealPlanFixtures.RECIPE, body["recipeId"]!!.jsonPrimitive.content)
        assertEquals("línea\nsalto", body["text"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("extra"))
        assertTrue(body.containsKey("recipeReferences"))
        // El resumen de receta lo recompone el servidor desde `recipeId`.
        assertFalse(body.containsKey("recipe"))
    }

    @Test
    fun `editar una entrada grande tampoco pierde claves`() {
        val entry = read(bigEntryJson(recipePadding = MealPlanMapper.MAX_RAW_ENTRY_CHARS))
        server.fixed("PUT", entryPath, 200, MealPlanFixtures.entryJson(id = 7, title = "Otra"))

        val draft = MealPlanDraft(LocalDate.of(2026, 11, 10), MealType.LUNCH, title = "Otra")
        val result = server.client().editEntry(entry, draft)

        assertTrue(result is MealPlanResult.Success)
        val body = bodyOf(server.requestsTo("PUT", entryPath).single())
        assertEquals("Otra", body["title"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("extra"))
        assertTrue(body.containsKey("recipeReferences"))
    }

    @Test
    fun `una entrada desmesurada cae al cuerpo minimo con la identidad`() {
        val entry = read(bigEntryJson(recipePadding = 10, extrasPadding = MealPlanMapper.MAX_RAW_ENTRY_HARD_CHARS))
        assertNull(entry.rawJson)
        server.fixed("PUT", entryPath, 200, MealPlanFixtures.entryJson(id = 7, date = "2026-11-11"))

        val result = server.client().moveEntry(entry, LocalDate.of(2026, 11, 11))

        assertTrue(result is MealPlanResult.Success)
        val body = bodyOf(server.requestsTo("PUT", entryPath).single())
        assertEquals(
            setOf("id", "groupId", "userId", "date", "entryType", "title", "text", "recipeId", "recipeScale"),
            body.keys,
        )
        assertEquals("2026-11-11", body["date"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sin json crudo el cuerpo de editar lleva solo lo que conoce la app`() {
        server.fixed("PUT", entryPath, 200, MealPlanFixtures.entryJson(id = 7, title = "Tostada"))
        val entry = MealPlanFixtures.plannedMeal(
            id = 7,
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
        )
        assertNull(entry.rawJson)

        val draft = MealPlanDraft(LocalDate.of(2026, 11, 10), MealType.LUNCH, title = "Tostada")
        val result = server.client().editEntry(entry, draft)

        assertTrue(result is MealPlanResult.Success)
        val body = bodyOf(server.requestsTo("PUT", entryPath).single())
        assertEquals(
            setOf("id", "groupId", "userId", "date", "entryType", "title", "text", "recipeId", "recipeScale"),
            body.keys,
        )
        assertEquals("Tostada", body["title"]!!.jsonPrimitive.content)
    }

    // --------------------------------------------------------------- toString

    @Test
    fun `toString de la entrada no saca titulo ni nota del servidor`() {
        val entry = MealPlanFixtures.plannedMeal(
            id = 7,
            date = LocalDate.of(2026, 11, 10),
            mealType = MealType.LUNCH,
            title = "TITULO-SECRETO",
            note = "NOTA-SECRETA",
            recipeName = "RECETA-SECRETA",
        ).copy(rawJson = JsonObject(mapOf("text" to JsonPrimitive("CRUDO-SECRETO"))))

        val texto = entry.toString()

        assertFalse(texto, texto.contains("SECRETO"))
        assertFalse(texto, texto.contains(MealPlanFixtures.GROUP))
        assertTrue(texto, texto.contains("hasRaw=true"))
    }
}
