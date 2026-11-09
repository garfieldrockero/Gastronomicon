package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * El controlador del planificador con una fuente falsa: estado de la semana,
 * navegación, errores tipados y escrituras sin duplicados. No toca la red.
 */
class MealPlanControllerTest {

    private val today = LocalDate.of(2026, 11, 11)
    private val monday = LocalDate.of(2026, 11, 9)

    private class FakeSource : MealPlanSource {
        var listResult: MealPlanResult<List<PlannedMeal>> = MealPlanResult.Success(emptyList())
        var todayResult: MealPlanResult<List<PlannedMeal>> = MealPlanResult.Success(emptyList())
        var getResult: MealPlanResult<PlannedMeal> = MealPlanResult.NotFound
        var createResult: MealPlanResult<PlannedMeal> = MealPlanResult.Failure(ConnectionProblem.Unknown)
        var moveResult: MealPlanResult<PlannedMeal> = MealPlanResult.Failure(ConnectionProblem.Unknown)
        var editResult: MealPlanResult<PlannedMeal> = MealPlanResult.Failure(ConnectionProblem.Unknown)
        var deleteResult: MealPlanResult<Unit> = MealPlanResult.Success(Unit)

        // Fallos de red que la fuente puede lanzar en vez de devolver un
        // resultado: crear el cliente falla antes de que exista cliente.
        var listError: NetworkError? = null
        var todayError: NetworkError? = null
        var createError: NetworkError? = null
        var moveError: NetworkError? = null
        var editError: NetworkError? = null
        var deleteError: NetworkError? = null

        var listCalls = 0
        var todayCalls = 0
        var createCalls = 0
        var moveCalls = 0
        var editCalls = 0
        var deleteCalls = 0

        var lastStart: LocalDate? = null
        var lastEnd: LocalDate? = null
        var lastDraft: MealPlanDraft? = null
        var lastMovedEntry: PlannedMeal? = null
        var lastMoveDate: LocalDate? = null
        var lastMoveType: MealType? = null
        var lastEditedEntry: PlannedMeal? = null
        var lastDeletedId: Long? = null

        override fun listEntries(start: LocalDate, end: LocalDate): MealPlanResult<List<PlannedMeal>> {
            listCalls++
            lastStart = start
            lastEnd = end
            listError?.let { throw it }
            return listResult
        }

        override fun entriesToday(): MealPlanResult<List<PlannedMeal>> {
            todayCalls++
            todayError?.let { throw it }
            return todayResult
        }

        override fun getEntry(id: Long): MealPlanResult<PlannedMeal> = getResult

        override fun createEntry(draft: MealPlanDraft): MealPlanResult<PlannedMeal> {
            createCalls++
            lastDraft = draft
            createError?.let { throw it }
            return createResult
        }

        override fun moveEntry(
            entry: PlannedMeal,
            date: LocalDate,
            mealType: MealType,
        ): MealPlanResult<PlannedMeal> {
            moveCalls++
            lastMovedEntry = entry
            lastMoveDate = date
            lastMoveType = mealType
            moveError?.let { throw it }
            return moveResult
        }

        override fun editEntry(entry: PlannedMeal, draft: MealPlanDraft): MealPlanResult<PlannedMeal> {
            editCalls++
            lastEditedEntry = entry
            lastDraft = draft
            editError?.let { throw it }
            return editResult
        }

        override fun deleteEntry(id: Long): MealPlanResult<Unit> {
            deleteCalls++
            lastDeletedId = id
            deleteError?.let { throw it }
            return deleteResult
        }
    }

    private fun newController(source: FakeSource, initialWeekStart: LocalDate? = null): MealPlanController =
        MealPlanController(
            source = source,
            todayProvider = { today },
            initialWeekStart = initialWeekStart,
        )

    private fun week(state: MealPlanState): MealPlanWeekView =
        state.week ?: throw AssertionError("Se esperaba la semana cargada")

    private fun meal(
        id: Long,
        date: LocalDate = monday,
        type: MealType = MealType.LUNCH,
        title: String = "Sopa",
    ): PlannedMeal = MealPlanFixtures.plannedMeal(id = id, date = date, mealType = type, title = title)

    // ---------------------------------------------------------------- carga

    @Test
    fun `cargar la semana compone los siete dias y marca hoy`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(1, title = "Lentejas")))
        val controller = newController(source)

        assertNull(controller.state.week)
        val state = controller.load()

        assertEquals(1, source.listCalls)
        assertEquals(monday, source.lastStart)
        assertEquals(LocalDate.of(2026, 11, 15), source.lastEnd)
        assertEquals(7, week(state).days.size)
        assertEquals("Lentejas", week(state).days[0].meals.single().title)
        assertTrue(week(state).days[2].isToday)
        assertEquals(MealPlanPhase.Idle, state.phase)
        assertNull(state.loadProblem)
        assertFalse(state.canRetry)
    }

    @Test
    fun `cargar dos veces no repite la peticion`() {
        val source = FakeSource()
        val controller = newController(source)

        controller.load()
        controller.load()

        assertEquals(1, source.listCalls)
    }

    @Test
    fun `refresh vuelve a pedir la misma semana`() {
        val source = FakeSource()
        val controller = newController(source)

        controller.load()
        controller.refresh()

        assertEquals(2, source.listCalls)
        assertEquals(monday, source.lastStart)
    }

    @Test
    fun `showWeek normaliza al lunes y no repite la semana cargada`() {
        val source = FakeSource()
        val controller = newController(source)

        controller.load()
        controller.showWeek(LocalDate.of(2026, 11, 11))
        assertEquals(1, source.listCalls)

        controller.showWeek(LocalDate.of(2026, 11, 16))
        assertEquals(2, source.listCalls)
        assertEquals(LocalDate.of(2026, 11, 16), source.lastStart)
    }

    @Test
    fun `semana anterior, siguiente y hoy`() {
        val source = FakeSource()
        val controller = newController(source)
        controller.load()

        controller.previousWeek()
        assertEquals(LocalDate.of(2026, 11, 2), source.lastStart)

        controller.nextWeek()
        assertEquals(monday, source.lastStart)

        controller.showWeek(LocalDate.of(2026, 12, 1))
        controller.goToToday()
        assertEquals(monday, source.lastStart)
    }

    @Test
    fun `un fallo de carga queda tipado y retry lo repite`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Unauthorized
        val controller = newController(source)

        val failed = controller.load()

        assertEquals(MealPlanProblem.Unauthorized, failed.loadProblem)
        assertNull(failed.week)
        assertTrue(failed.canRetry)

        source.listResult = MealPlanResult.Success(listOf(meal(1)))
        val retried = controller.retry()

        assertNull(retried.loadProblem)
        assertNotNull(retried.week)
        assertEquals(2, source.listCalls)
    }

    @Test
    fun `si falla la semana nueva no se ensena la anterior`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(1)))
        val controller = newController(source)
        controller.load()

        source.listResult = MealPlanResult.Failure(ConnectionProblem.ServerError)
        val state = controller.showWeek(LocalDate.of(2026, 11, 16))

        assertEquals(LocalDate.of(2026, 11, 16), state.weekStart)
        assertNull(state.week)
        assertEquals(MealPlanProblem.Connection(ConnectionProblem.ServerError), state.loadProblem)
    }

    @Test
    fun `una semana fuera de los años admitidos no toca la red`() {
        val source = FakeSource()
        val controller = newController(source, LocalDate.of(1899, 12, 25))

        val state = controller.load()

        assertEquals(0, source.listCalls)
        assertEquals(MealPlanProblem.InvalidInput(MealPlanInputProblem.InvalidDate), state.loadProblem)
        assertNull(state.week)
    }

    @Test
    fun `una fecha extrema en el constructor no lanza ni deja cargando`() {
        val source = FakeSource()
        val controller = newController(source, LocalDate.MIN)

        val state = controller.showWeek(LocalDate.MIN)

        assertEquals(0, source.listCalls)
        assertEquals(LocalDate.MIN, state.weekStart)
        assertFalse(state.isLoading)
        assertEquals(MealPlanProblem.InvalidInput(MealPlanInputProblem.InvalidDate), state.loadProblem)
    }

    @Test
    fun `cargar desde una semana extrema termina con un problema y sin cargar`() {
        val source = FakeSource()
        val controller = newController(source, LocalDate.MAX)

        val state = controller.load()

        assertEquals(0, source.listCalls)
        assertFalse(state.isLoading)
        assertEquals(MealPlanProblem.InvalidInput(MealPlanInputProblem.InvalidDate), state.loadProblem)
    }

    @Test
    fun `navegar desde una semana extrema no lanza`() {
        val source = FakeSource()
        val controller = newController(source, LocalDate.MIN)

        val state = controller.nextWeek()

        assertEquals(0, source.listCalls)
        assertFalse(state.isLoading)
        assertEquals(MealPlanProblem.InvalidInput(MealPlanInputProblem.InvalidDate), state.loadProblem)
    }

    @Test
    fun `una semana que se sale del rango no deja la fase cargando`() {
        val source = FakeSource()
        val controller = newController(source)
        val extremo = LocalDate.of(2999, 12, 31)

        val state = controller.showWeek(extremo)

        assertEquals(0, source.listCalls)
        assertFalse(state.isLoading)
        assertEquals(MealPlanDates.weekStartOrNull(extremo), state.weekStart)
        assertEquals(MealPlanProblem.InvalidInput(MealPlanInputProblem.InvalidDate), state.loadProblem)
    }

    // ----------------------------------------------------------- escrituras

    @Test
    fun `anadir inserta la entrada sin duplicarla`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7, title = "Sopa")))
        val controller = newController(source)
        controller.load()

        source.createResult = MealPlanResult.Success(meal(7, title = "Sopa de ajo"))
        val state = controller.add(MealPlanDraft(monday, MealType.LUNCH, title = "Sopa de ajo"))

        assertEquals(1, source.createCalls)
        assertNotNull(source.lastDraft)
        assertEquals(1, week(state).mealCount)
        assertEquals("Sopa de ajo", week(state).days[0].meals.single().title)
    }

    @Test
    fun `anadir otra entrada la coloca en su dia`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        source.createResult = MealPlanResult.Success(
            meal(8, date = LocalDate.of(2026, 11, 10), type = MealType.DINNER, title = "Cena"),
        )
        val state = controller.add(
            MealPlanDraft(LocalDate.of(2026, 11, 10), MealType.DINNER, title = "Cena"),
        )

        assertEquals(2, week(state).mealCount)
        assertEquals("Cena", week(state).days[1].meals.single().title)
    }

    @Test
    fun `un fallo al anadir deja la semana y el error tipado`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        source.createResult = MealPlanResult.Invalid(listOf("fecha no válida"))
        val state = controller.add(MealPlanDraft(monday, MealType.LUNCH, title = "Sopa"))

        assertEquals(MealPlanOperation.Create, state.actionFailure?.operation)
        assertEquals(MealPlanProblem.Invalid(listOf("fecha no válida")), state.actionFailure?.problem)
        assertEquals(1, week(state).mealCount)
        assertFalse(state.isBusy)
    }

    @Test
    fun `mover cambia el dia y no duplica`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        val target = LocalDate.of(2026, 11, 10)
        source.moveResult = MealPlanResult.Success(original.copy(date = target))
        val state = controller.move(original, target)

        assertEquals(1, source.moveCalls)
        assertEquals(target, source.lastMoveDate)
        assertEquals(MealType.LUNCH, source.lastMoveType)
        assertEquals(1, week(state).mealCount)
        assertTrue(week(state).days[0].meals.isEmpty())
        assertEquals(1, week(state).days[1].meals.size)
    }

    @Test
    fun `un fallo al mover deja la entrada donde estaba`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        source.moveResult = MealPlanResult.Unauthorized
        val state = controller.move(original, LocalDate.of(2026, 11, 10))

        assertEquals(MealPlanOperation.Move, state.actionFailure?.operation)
        assertEquals(MealPlanProblem.Unauthorized, state.actionFailure?.problem)
        assertEquals(1, week(state).days[0].meals.size)
    }

    @Test
    fun `editar cambia los datos de la entrada`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        source.editResult = MealPlanResult.Success(original.copy(title = "Nueva sopa"))
        val draft = MealPlanDraft(monday, MealType.LUNCH, title = "Nueva sopa")
        val state = controller.edit(original, draft)

        assertEquals(1, source.editCalls)
        assertEquals(original, source.lastEditedEntry)
        assertEquals("Nueva sopa", week(state).days[0].meals.single().title)
        assertEquals(1, week(state).mealCount)
    }

    @Test
    fun `borrar quita la entrada y un 404 se distingue`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        source.deleteResult = MealPlanResult.Success(Unit)
        val deleted = controller.delete(7)
        assertEquals(1, source.deleteCalls)
        assertEquals(7L, source.lastDeletedId)
        assertTrue(week(deleted).days[0].meals.isEmpty())

        source.deleteResult = MealPlanResult.NotFound
        val failed = controller.delete(7)
        assertEquals(MealPlanOperation.Delete, failed.actionFailure?.operation)
        assertEquals(MealPlanProblem.NotFound, failed.actionFailure?.problem)
    }

    @Test
    fun `sin semana cargada no se escribe nada`() {
        val source = FakeSource()
        val controller = newController(source)

        val state = controller.add(MealPlanDraft(monday, MealType.LUNCH, title = "Sopa"))

        assertEquals(0, source.createCalls)
        assertNull(state.actionFailure)
        assertNull(state.week)
    }

    // ------------------------------------------------------------------ hoy

    @Test
    fun `hoy carga las comidas y las deduplica`() {
        val source = FakeSource()
        source.todayResult = MealPlanResult.Success(
            listOf(
                meal(5, date = today, type = MealType.BREAKFAST),
                meal(5, date = today, type = MealType.BREAKFAST),
                meal(6, date = today, type = MealType.DINNER),
            ),
        )
        val controller = newController(source)

        val state = controller.loadToday()

        assertEquals(1, source.todayCalls)
        assertEquals(2, state.todayMeals.size)
        assertEquals(MealPlanPhase.Idle, state.phase)
    }

    @Test
    fun `hoy con fallo deja el error tipado`() {
        val source = FakeSource()
        source.todayResult = MealPlanResult.Failure(ConnectionProblem.NoNetwork)
        val controller = newController(source)

        val state = controller.loadToday()

        assertEquals(MealPlanOperation.Today, state.actionFailure?.operation)
        assertEquals(MealPlanProblem.Connection(ConnectionProblem.NoNetwork), state.actionFailure?.problem)
        assertFalse(state.isBusy)
    }

    @Test
    fun `clearActionFailure quita el aviso sin tocar la semana`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()
        source.createResult = MealPlanResult.Unauthorized
        controller.add(MealPlanDraft(monday, MealType.LUNCH, title = "Sopa"))
        assertNotNull(controller.state.actionFailure)

        val cleared = controller.clearActionFailure()

        assertNull(cleared.actionFailure)
        assertEquals(1, week(cleared).mealCount)
    }

    @Test
    fun `el estado no imprime el contenido del servidor`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7, title = "Lentejas secretas")))
        val controller = newController(source)
        controller.load()

        val text = controller.state.toString()

        assertFalse(text, text.contains("Lentejas secretas"))
        assertFalse(text, text.contains(MealPlanFixtures.GROUP))
        assertFalse(week(controller.state).toString().contains("Lentejas secretas"))
    }

    // ------------------------------------------- ronda 2 de la revisión

    @Test
    fun `una creacion dudosa relee la semana y conserva el aviso`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        // El servidor sí llegó a crear la entrada aunque la respuesta se perdió.
        source.createResult = MealPlanResult.MaybeCreated(ConnectionProblem.ServerNotResponding)
        source.listResult = MealPlanResult.Success(
            listOf(meal(7), meal(8, date = LocalDate.of(2026, 11, 10), type = MealType.DINNER, title = "Cena")),
        )
        val state = controller.add(MealPlanDraft(LocalDate.of(2026, 11, 10), MealType.DINNER, title = "Cena"))

        assertEquals(1, source.createCalls)
        assertEquals(2, source.listCalls)
        assertEquals(2, week(state).mealCount)
        assertEquals(MealPlanOperation.Create, state.actionFailure?.operation)
        assertEquals(
            MealPlanProblem.MaybeCreated(ConnectionProblem.ServerNotResponding),
            state.actionFailure?.problem,
        )
        assertNull(state.loadProblem)
        assertEquals(MealPlanPhase.Idle, state.phase)
    }

    @Test
    fun `si la relectura tras una creacion dudosa falla se queda la semana y el aviso`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        source.createResult = MealPlanResult.MaybeCreated(ConnectionProblem.ServerNotResponding)
        source.listResult = MealPlanResult.Failure(ConnectionProblem.NoNetwork)
        val state = controller.add(MealPlanDraft(monday, MealType.LUNCH, title = "Sopa"))

        assertEquals(2, source.listCalls)
        assertEquals(1, week(state).mealCount)
        // El error de la relectura no tapa el aviso de la creación.
        assertNull(state.loadProblem)
        assertEquals(
            MealPlanProblem.MaybeCreated(ConnectionProblem.ServerNotResponding),
            state.actionFailure?.problem,
        )
        assertFalse(state.isBusy)
    }

    @Test
    fun `un rechazo definitivo al crear no relee la semana`() {
        val source = FakeSource()
        val controller = newController(source)
        controller.load()

        source.createResult = MealPlanResult.Invalid(listOf("no vale"))
        controller.add(MealPlanDraft(monday, MealType.LUNCH, title = "Sopa"))

        assertEquals(1, source.listCalls)
    }

    @Test
    fun `un fallo ambiguo al mover relee la semana y enseña donde esta de verdad`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        // El PUT llegó y el servidor movió la entrada, pero la respuesta se perdió.
        val target = LocalDate.of(2026, 11, 10)
        source.moveResult = MealPlanResult.Failure(ConnectionProblem.ServerNotResponding)
        source.listResult = MealPlanResult.Success(listOf(original.copy(date = target)))
        val state = controller.move(original, target)

        assertEquals(2, source.listCalls)
        assertTrue(week(state).days[0].meals.isEmpty())
        assertEquals(1, week(state).days[1].meals.size)
        assertEquals(MealPlanOperation.Move, state.actionFailure?.operation)
        assertNull(state.loadProblem)
    }

    @Test
    fun `un fallo ambiguo al editar relee la semana`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        source.editResult = MealPlanResult.Failure(ConnectionProblem.ServerError)
        source.listResult = MealPlanResult.Success(listOf(original.copy(title = "Nueva sopa")))
        val state = controller.edit(original, MealPlanDraft(monday, MealType.LUNCH, title = "Nueva sopa"))

        assertEquals(2, source.listCalls)
        assertEquals("Nueva sopa", week(state).days[0].meals.single().title)
        assertEquals(MealPlanOperation.Edit, state.actionFailure?.operation)
    }

    @Test
    fun `un fallo que prueba que no salio nada no relee la semana`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        source.moveResult = MealPlanResult.Failure(ConnectionProblem.SecureConnectionFailed)
        controller.move(original, LocalDate.of(2026, 11, 10))
        source.moveResult = MealPlanResult.Unauthorized
        controller.move(original, LocalDate.of(2026, 11, 10))

        assertEquals(1, source.listCalls)
    }

    @Test
    fun `mover y editar parten de la entrada vigente y no de la copia de la interfaz`() {
        val source = FakeSource()
        val vigente = meal(7).copy(rawJson = JsonObject(mapOf("version" to JsonPrimitive("nueva"))))
        source.listResult = MealPlanResult.Success(listOf(vigente))
        val controller = newController(source)
        controller.load()

        val obsoleta = meal(7, title = "Obsoleta").copy(
            rawJson = JsonObject(mapOf("version" to JsonPrimitive("vieja"))),
        )
        controller.move(obsoleta, LocalDate.of(2026, 11, 10))
        assertEquals(vigente, source.lastMovedEntry)

        controller.edit(obsoleta, MealPlanDraft(monday, MealType.LUNCH, title = "Otra"))
        assertEquals(vigente, source.lastEditedEntry)
    }

    @Test
    fun `una entrada que no esta en la semana cargada se envia tal cual`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        val ajena = meal(99, title = "Otra semana")
        controller.move(ajena, LocalDate.of(2026, 11, 10))

        assertEquals(ajena, source.lastMovedEntry)
    }

    // ------------------------------------------------ mover y editar por id

    @Test
    fun `mover por id parte de la entrada vigente y conserva su tipo`() {
        val source = FakeSource()
        val vigente = meal(7, type = MealType.DINNER)
        source.listResult = MealPlanResult.Success(listOf(vigente))
        val controller = newController(source)
        controller.load()

        val target = LocalDate.of(2026, 11, 10)
        source.moveResult = MealPlanResult.Success(vigente.copy(date = target))
        val state = controller.move(7L, target)

        assertEquals(1, source.moveCalls)
        assertEquals(vigente, source.lastMovedEntry)
        assertEquals(MealType.DINNER, source.lastMoveType)
        assertEquals(1, week(state).days[1].meals.size)
        assertNull(state.actionFailure)
    }

    @Test
    fun `mover por id con tipo explicito lo envia`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        val target = LocalDate.of(2026, 11, 10)
        source.moveResult = MealPlanResult.Success(original.copy(date = target, mealType = MealType.BREAKFAST))
        controller.move(7L, target, MealType.BREAKFAST)

        assertEquals(1, source.moveCalls)
        assertEquals(MealType.BREAKFAST, source.lastMoveType)
        assertEquals(LocalDate.of(2026, 11, 10), source.lastMoveDate)
    }

    @Test
    fun `editar por id parte de la entrada vigente`() {
        val source = FakeSource()
        val vigente = meal(7).copy(rawJson = JsonObject(mapOf("version" to JsonPrimitive("nueva"))))
        source.listResult = MealPlanResult.Success(listOf(vigente))
        val controller = newController(source)
        controller.load()

        source.editResult = MealPlanResult.Success(vigente.copy(title = "Nueva sopa"))
        val state = controller.edit(7L, MealPlanDraft(monday, MealType.LUNCH, title = "Nueva sopa"))

        assertEquals(1, source.editCalls)
        assertEquals(vigente, source.lastEditedEntry)
        assertEquals("Nueva sopa", week(state).days[0].meals.single().title)
    }

    @Test
    fun `mover o editar por un id ausente avisa sin tocar la red`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        val moved = controller.move(99L, LocalDate.of(2026, 11, 10))

        assertEquals(0, source.moveCalls)
        assertEquals(MealPlanOperation.Move, moved.actionFailure?.operation)
        assertEquals(MealPlanProblem.NotFound, moved.actionFailure?.problem)
        assertFalse(moved.isBusy)
        assertEquals(1, week(moved).mealCount)

        val edited = controller.edit(99L, MealPlanDraft(monday, MealType.LUNCH, title = "Otra"))

        assertEquals(0, source.editCalls)
        assertEquals(MealPlanOperation.Edit, edited.actionFailure?.operation)
        assertEquals(MealPlanProblem.NotFound, edited.actionFailure?.problem)
    }

    @Test
    fun `mover por id sin semana cargada no hace nada`() {
        val source = FakeSource()
        val controller = newController(source)

        val state = controller.move(7L, LocalDate.of(2026, 11, 10))

        assertEquals(0, source.moveCalls)
        assertNull(state.actionFailure)
        assertNull(state.week)
    }

    // ---------------------------- fallos de red que lanza la propia fuente

    @Test
    fun `un 401 de red al cargar la semana queda tipado y se puede reintentar`() {
        val source = FakeSource()
        source.listError = NetworkError.HttpError(401)
        val controller = newController(source)

        val state = controller.load()

        assertEquals(MealPlanProblem.Unauthorized, state.loadProblem)
        assertNull(state.week)
        assertTrue(state.canRetry)
        assertFalse(state.isLoading)
    }

    @Test
    fun `un 403 de red al mover se traduce sin releer la semana`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        source.moveError = NetworkError.HttpError(403)
        val state = controller.move(original, LocalDate.of(2026, 11, 10))

        assertEquals(1, source.moveCalls)
        assertEquals(MealPlanOperation.Move, state.actionFailure?.operation)
        assertEquals(MealPlanProblem.Forbidden, state.actionFailure?.problem)
        // Un 403 no deja dudas de lo que hay en el servidor: no se relee.
        assertEquals(1, source.listCalls)
        assertEquals(1, week(state).days[0].meals.size)
        assertFalse(state.isBusy)
    }

    @Test
    fun `un 5xx de red al borrar deja el aviso tipado`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        source.deleteError = NetworkError.HttpError(503)
        val state = controller.delete(7L)

        assertEquals(MealPlanOperation.Delete, state.actionFailure?.operation)
        assertEquals(
            MealPlanProblem.Connection(ConnectionProblem.ServerError),
            state.actionFailure?.problem,
        )
        assertEquals(1, week(state).mealCount)
    }

    @Test
    fun `un fallo de red al crear se traduce y no relee la semana`() {
        val source = FakeSource()
        val controller = newController(source)
        controller.load()

        source.createError = NetworkError.Timeout
        val state = controller.add(MealPlanDraft(monday, MealType.LUNCH, title = "Sopa"))

        assertEquals(MealPlanOperation.Create, state.actionFailure?.operation)
        assertEquals(
            MealPlanProblem.Connection(ConnectionProblem.ServerNotResponding),
            state.actionFailure?.problem,
        )
        // Lanzado crudo y sin «después del envío» no es dudoso: no se relee.
        assertEquals(1, source.listCalls)
    }

    @Test
    fun `un fallo despues del envio al crear conserva el pudo crearse y relee`() {
        val source = FakeSource()
        source.listResult = MealPlanResult.Success(listOf(meal(7)))
        val controller = newController(source)
        controller.load()

        // El servidor sí creó la entrada aunque la respuesta se perdió.
        source.createError = NetworkError.AfterSend(NetworkError.NoConnection)
        source.listResult = MealPlanResult.Success(
            listOf(meal(7), meal(8, date = LocalDate.of(2026, 11, 10), type = MealType.DINNER, title = "Cena")),
        )
        val state = controller.add(MealPlanDraft(LocalDate.of(2026, 11, 10), MealType.DINNER, title = "Cena"))

        assertEquals(2, source.listCalls)
        assertEquals(2, week(state).mealCount)
        assertEquals(MealPlanOperation.Create, state.actionFailure?.operation)
        assertEquals(
            MealPlanProblem.MaybeCreated(ConnectionProblem.NoNetwork),
            state.actionFailure?.problem,
        )
        assertFalse(state.isBusy)
    }

    @Test
    fun `un 404 de red al editar queda como NotFound`() {
        val source = FakeSource()
        val original = meal(7)
        source.listResult = MealPlanResult.Success(listOf(original))
        val controller = newController(source)
        controller.load()

        source.editError = NetworkError.HttpError(404)
        val state = controller.edit(original, MealPlanDraft(monday, MealType.LUNCH, title = "Otra"))

        assertEquals(MealPlanOperation.Edit, state.actionFailure?.operation)
        assertEquals(MealPlanProblem.NotFound, state.actionFailure?.problem)
        assertEquals(1, source.listCalls)
    }
}
