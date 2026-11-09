package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanFixtures
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * La copia cifrada del plan: ida y vuelta, sustitución, ámbito, caducidad,
 * topes y, sobre todo, que la nota libre no se guarda nunca.
 */
class MealPlanCacheTest {

    private val store = FakeEncryptedBlobStore()
    private var clock = 1_000_000L
    private val scope = OfflineFixtures.scopeA()
    private val scopeB = OfflineFixtures.scopeB()
    private val day = LocalDate.of(2026, 11, 9)

    private fun newCache(
        maxBytes: Int = MealPlanCache.DEFAULT_MAX_BYTES,
        ttlMillis: Long = MealPlanCache.DEFAULT_TTL_MILLIS,
    ): MealPlanCache = MealPlanCache(store, maxBytes, ttlMillis) { clock }

    private fun meal(
        id: Long,
        type: MealType = MealType.LUNCH,
        title: String = "Sopa",
        note: String = "",
        recipeId: String? = null,
        recipeName: String? = null,
        date: LocalDate = day,
    ) = MealPlanFixtures.plannedMeal(
        id = id,
        date = date,
        mealType = type,
        title = title,
        note = note,
        recipeId = recipeId,
        recipeName = recipeName,
    )

    private fun assertWriteFailed(expected: StorageProblem, result: OfflineWrite) {
        assertTrue("Se esperaba un fallo y llegó $result", result is OfflineWrite.Failed)
        assertEquals(expected, (result as OfflineWrite.Failed).problem)
    }

    private fun assertReadFailed(expected: StorageProblem, result: OfflineRead<*>) {
        assertTrue("Se esperaba un fallo y llegó $result", result is OfflineRead.Failed)
        assertEquals(expected, (result as OfflineRead.Failed).problem)
    }

    private fun rawBlob(): String = store.blobs.values.single().toString(Charsets.UTF_8)

    // ------------------------------------------------------------- básico

    @Test
    fun `guarda y devuelve las comidas del dia`() {
        val cache = newCache()

        assertEquals(
            OfflineWrite.Saved,
            cache.save(
                scope,
                listOf(
                    meal(1, MealType.LUNCH, "Lentejas"),
                    meal(2, MealType.DINNER, "Tortilla"),
                ),
            ),
        )

        val plan = cache.load(scope).hitOrFail()
        assertEquals(clock, plan.savedAtMillis)
        val meals = plan.mealsOn(day)
        assertEquals(listOf("Lentejas", "Tortilla"), meals.map { it.title })
        assertEquals(listOf(MealType.LUNCH, MealType.DINNER), meals.map { it.mealType })
        assertTrue(plan.hasMealsOn(day))
        assertTrue(plan.mealsOn(day.plusDays(1)).isEmpty())
    }

    @Test
    fun `guardar otra vez sustituye la copia`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1, title = "Lentejas")))
        clock += 10

        cache.save(scope, listOf(meal(2, title = "Tortilla")))

        val meals = cache.load(scope).hitOrFail().mealsOn(day)
        assertEquals(listOf("Tortilla"), meals.map { it.title })
    }

    @Test
    fun `un plan vacio se guarda y no da comidas`() {
        val cache = newCache()

        assertEquals(OfflineWrite.Saved, cache.save(scope, emptyList()))

        val plan = cache.load(scope).hitOrFail()
        assertTrue(plan.days.isEmpty())
        assertTrue(plan.mealsOn(day).isEmpty())
    }

    @Test
    fun `otro servidor u otra cuenta no ven la copia`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))

        assertTrue(cache.load(scopeB) is OfflineRead.Miss)
        assertTrue(cache.load(OfflineFixtures.scopeAOtraCuenta()) is OfflineRead.Miss)
        assertTrue(cache.load(scope) is OfflineRead.Hit)
    }

    // ------------------------------------------- bloqueante: nota libre

    @Test
    fun `una entrada con solo nota no se guarda`() {
        val cache = newCache()
        val marker = "RECADO_SECRETO"

        assertEquals(
            OfflineWrite.Saved,
            cache.save(scope, listOf(meal(1, title = "", note = marker))),
        )

        assertFalse(rawBlob().contains(marker))
        assertTrue(cache.load(scope).hitOrFail().mealsOn(day).isEmpty())
    }

    @Test
    fun `la nota de una entrada con receta tampoco se guarda`() {
        val cache = newCache()
        val marker = "RECADO_SECRETO"

        cache.save(
            scope,
            listOf(
                meal(
                    1,
                    title = "",
                    note = marker,
                    recipeId = MealPlanFixtures.RECIPE,
                    recipeName = "Lentejas",
                ),
            ),
        )

        assertFalse(rawBlob().contains(marker))
        val meals = cache.load(scope).hitOrFail().mealsOn(day)
        assertEquals(listOf("Lentejas"), meals.map { it.title })
    }

    @Test
    fun `una entrada en blanco se descarta`() {
        val cache = newCache()

        cache.save(scope, listOf(meal(1, title = "", note = "")))

        assertTrue(cache.load(scope).hitOrFail().mealsOn(day).isEmpty())
    }

    @Test
    fun `una receta borrada queda como texto generico`() {
        val cache = newCache()

        cache.save(
            scope,
            listOf(
                meal(
                    1,
                    title = "",
                    recipeId = MealPlanFixtures.RECIPE,
                    recipeName = null,
                ),
            ),
        )

        assertEquals(
            listOf("Receta no disponible"),
            cache.load(scope).hitOrFail().mealsOn(day).map { it.title },
        )
    }

    // ------------------------------------------------- tipos y orden

    @Test
    fun `las comidas se guardan ordenadas por tipo`() {
        val cache = newCache()

        cache.save(
            scope,
            listOf(
                meal(1, MealType.DINNER, "Cena"),
                meal(2, MealType.BREAKFAST, "Desayuno"),
                meal(3, MealType.LUNCH, "Comida"),
            ),
        )

        assertEquals(
            listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER),
            cache.load(scope).hitOrFail().mealsOn(day).map { it.mealType },
        )
    }

    @Test
    fun `un tipo de comida desconocido no se convierte en desayuno`() {
        val cache = newCache()

        cache.save(scope, listOf(meal(1, MealType.UNKNOWN, "Sopa")))

        assertEquals(
            listOf(MealType.UNKNOWN),
            cache.load(scope).hitOrFail().mealsOn(day).map { it.mealType },
        )
    }

    @Test
    fun `una entrada repetida cuenta una vez`() {
        val cache = newCache()

        cache.save(scope, listOf(meal(1, title = "Sopa"), meal(1, title = "Otra")))

        assertEquals(
            listOf("Sopa"),
            cache.load(scope).hitOrFail().mealsOn(day).map { it.title },
        )
    }

    // ---------------------------------------------------------- topes

    @Test
    fun `un nombre larguisimo se acota a 120 puntos de codigo`() {
        val cache = newCache()

        cache.save(scope, listOf(meal(1, title = "x".repeat(5_000))))

        val stored = cache.load(scope).hitOrFail().mealsOn(day).single().title
        assertEquals(MealPlanCacheCodec.MAX_TITLE_CODE_POINTS, stored.length)
        assertEquals("x".repeat(MealPlanCacheCodec.MAX_TITLE_CODE_POINTS), stored)
    }

    @Test
    fun `solo se guardan siete dias`() {
        val cache = newCache()
        val meals = (0..8).map { offset ->
            meal(id = offset.toLong() + 1, title = "Día $offset", date = day.plusDays(offset.toLong()))
        }

        cache.save(scope, meals)

        val plan = cache.load(scope).hitOrFail()
        assertEquals(MealPlanCacheCodec.MAX_DAYS, plan.days.size)
        assertEquals(day, plan.days.first().date)
        assertEquals(day.plusDays(6), plan.days.last().date)
        assertTrue(plan.mealsOn(day.plusDays(7)).isEmpty())
    }

    @Test
    fun `solo se guardan cincuenta comidas por dia`() {
        val cache = newCache()
        val meals = (1..60).map { id -> meal(id.toLong(), title = "Plato $id") }

        cache.save(scope, meals)

        val stored = cache.load(scope).hitOrFail().mealsOn(day)
        assertEquals(MealPlanCacheCodec.MAX_MEALS_PER_DAY, stored.size)
        assertEquals("Plato 1", stored.first().title)
        assertEquals("Plato 50", stored.last().title)
    }

    @Test
    fun `un plan que no cabe no se guarda y borra el anterior`() {
        val cache = newCache(maxBytes = 64)

        assertEquals(OfflineWrite.TooLarge, cache.save(scope, listOf(meal(1))))

        assertTrue(store.blobs.isEmpty())
    }

    // ------------------------------------------------------- caducidad

    @Test
    fun `justo antes de caducar se sirve`() {
        val cache = newCache(ttlMillis = 1_000)
        cache.save(scope, listOf(meal(1)))

        clock += 1_000

        assertTrue(cache.load(scope) is OfflineRead.Hit)
    }

    @Test
    fun `una copia caducada no se sirve y se borra`() {
        val cache = newCache(ttlMillis = 1_000)
        cache.save(scope, listOf(meal(1)))

        clock += 1_001

        assertTrue(cache.load(scope) is OfflineRead.Miss)
        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `una marca de tiempo en el futuro se descarta`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))

        clock -= 10L * 60 * 1000

        assertTrue(cache.load(scope) is OfflineRead.Miss)
        assertTrue(store.blobs.isEmpty())
    }

    // ------------------------------------------------- datos no confiables

    @Test
    fun `un blob corrupto se descarta sin lanzar`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        val key = store.keyWithPrefix(BlobPrefix.MEAL_PLAN_ENTRY)
        store.blobs[key] = "esto no es json".toByteArray(Charsets.UTF_8)

        assertTrue(cache.load(scope) is OfflineRead.Miss)
        assertFalse(store.blobs.containsKey(key))
    }

    @Test
    fun `una version futura se descarta`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        store.tamper(BlobPrefix.MEAL_PLAN_ENTRY) { it.with("format", JsonPrimitive(99)) }

        assertTrue(cache.load(scope) is OfflineRead.Miss)
        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `un ambito cambiado dentro del blob se descarta`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        store.tamper(BlobPrefix.MEAL_PLAN_ENTRY) {
            it.with("scope", JsonPrimitive("0".repeat(64)))
        }

        assertTrue(cache.load(scope) is OfflineRead.Miss)
    }

    @Test
    fun `una fecha fuera de rango se descarta`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        store.tamper(BlobPrefix.MEAL_PLAN_ENTRY) { root ->
            val days = root["days"] as JsonArray
            val first = days[0] as JsonObject
            root.with("days", JsonArray(listOf(first.with("date", JsonPrimitive("1800-01-01")))))
        }

        assertTrue(cache.load(scope) is OfflineRead.Miss)
    }

    @Test
    fun `un titulo por encima del tope descarta el blob`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        store.tamper(BlobPrefix.MEAL_PLAN_ENTRY) { root ->
            val days = root["days"] as JsonArray
            val firstDay = days[0] as JsonObject
            val meals = firstDay["meals"] as JsonArray
            val firstMeal = meals[0] as JsonObject
            val changedMeal = firstMeal.with("title", JsonPrimitive("x".repeat(121)))
            val changedDay = firstDay.with("meals", JsonArray(listOf(changedMeal)))
            root.with("days", JsonArray(listOf(changedDay)))
        }

        assertTrue(cache.load(scope) is OfflineRead.Miss)
    }

    @Test
    fun `un blob gigante se descarta antes de parsearlo`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        store.blobs[store.keyWithPrefix(BlobPrefix.MEAL_PLAN_ENTRY)] =
            ByteArray(MealPlanCache.DEFAULT_MAX_BYTES + 1) { 0x20.toByte() }

        assertTrue(cache.load(scope) is OfflineRead.Miss)
        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `el texto guardado se vuelve a sanear al leer`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1, title = "Sopa")))
        store.tamper(BlobPrefix.MEAL_PLAN_ENTRY) { root ->
            val days = root["days"] as JsonArray
            val firstDay = days[0] as JsonObject
            val meals = firstDay["meals"] as JsonArray
            val firstMeal = meals[0] as JsonObject
            val changedMeal = firstMeal.with("title", JsonPrimitive("Sopa\u202E\u0007"))
            val changedDay = firstDay.with("meals", JsonArray(listOf(changedMeal)))
            root.with("days", JsonArray(listOf(changedDay)))
        }

        assertEquals(
            listOf("Sopa"),
            cache.load(scope).hitOrFail().mealsOn(day).map { it.title },
        )
    }

    // ------------------------------------------------------ almacén roto

    @Test
    fun `si el almacen no esta disponible al leer se devuelve un fallo`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        store.readProblem = StorageProblem.Unavailable

        assertReadFailed(StorageProblem.Unavailable, cache.load(scope))
    }

    @Test
    fun `un blob que no descifra se trata como Miss y se borra`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        store.readProblem = StorageProblem.Corrupt

        assertTrue(cache.load(scope) is OfflineRead.Miss)
        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `una implementacion que lanza no rompe la interfaz`() {
        val cache = newCache()
        store.throwOnGet = true

        assertReadFailed(StorageProblem.Unavailable, cache.load(scope))
    }

    @Test
    fun `si el almacen no escribe se devuelve un fallo`() {
        val cache = newCache()
        store.writeProblem = StorageProblem.Unavailable

        assertWriteFailed(StorageProblem.Unavailable, cache.save(scope, listOf(meal(1))))
    }

    // ----------------------------------------------------------- borrado

    @Test
    fun `clear de un ambito no toca el otro`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        cache.save(scopeB, listOf(meal(1)))

        assertEquals(OfflineWrite.Saved, cache.clear(scope))

        assertTrue(cache.load(scope) is OfflineRead.Miss)
        assertTrue(cache.load(scopeB) is OfflineRead.Hit)
    }

    @Test
    fun `clear sin ambito borra todas las copias`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1)))
        cache.save(scopeB, listOf(meal(1)))

        assertEquals(OfflineWrite.Saved, cache.clear())

        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `los resultados no arrastran contenido en toString`() {
        val cache = newCache()
        cache.save(scope, listOf(meal(1, title = "Lentejas de la abuela")))
        val plan = cache.load(scope).hitOrFail()

        assertFalse(plan.toString().contains("Lentejas"))
        assertFalse(plan.days.toString().contains("Lentejas"))
        assertFalse(plan.mealsOn(day).toString().contains("Lentejas"))
        assertEquals("Hit", cache.load(scope).toString())
    }

    @Test
    fun `una marca de tiempo no positiva no se guarda`() {
        val cache = newCache()
        clock = 0

        assertEquals(OfflineWrite.Rejected, cache.save(scope, listOf(meal(1))))
        assertTrue(store.blobs.isEmpty())
    }

    @Test
    fun `una fecha imposible del plan no llega al almacen`() {
        val cache = newCache()
        val fuera = LocalDate.of(1800, 1, 1)

        cache.save(scope, listOf(meal(1, date = fuera)))

        assertTrue(cache.load(scope).hitOrFail().days.isEmpty())
        assertNull(cache.load(scope).hitOrFail().mealsOn(fuera).firstOrNull())
    }
}
