package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Lleva el planificador de comidas: carga una semana, cambia de semana y
 * añade, mueve, edita o borra entradas. La interfaz solo llama a estos
 * métodos y pinta [state].
 *
 * Los métodos que tocan red (`load`, `refresh`, `showWeek`, `previousWeek`,
 * `nextWeek`, `goToToday`, `retry`, `loadToday`, `add`, `move`, `edit`,
 * `delete`) son **bloqueantes**, como el resto del núcleo: `app/` los lanza
 * desde un hilo de fondo. El controlador está pensado para un solo hilo de
 * trabajo; [state] se puede leer desde cualquier hilo.
 *
 * Si mientras se carga una semana se pide otra, la respuesta vieja se descarta
 * al llegar (solo escribe la última carga lanzada). Mientras hay una carga o
 * una escritura en curso, las demás operaciones no hacen nada y devuelven el
 * estado vigente: no se duplican peticiones ni entradas.
 *
 * Las escrituras solo se permiten con una semana cargada. Después de crear,
 * mover, editar o borrar, la semana se recompone con la entrada que devuelve
 * el servidor, sin repetidos por id; una entrada que quede fuera de la semana
 * a la vista no se enseña hasta navegar a la suya.
 *
 * Mover y editar parten de la entrada vigente del controlador (buscada por id),
 * no de la copia que traiga la interfaz. Tras una creación dudosa
 * ([MealPlanResult.MaybeCreated]) o un fallo de red de mover/editar que pudo
 * ocurrir después del envío, la semana se vuelve a leer sin tapar el aviso.
 *
 * Aunque [MealPlanSource] no debería lanzar por red, crear el cliente sí puede
 * fallar antes de que exista cliente (HTTP local apagado con datos móviles,
 * sesión sin token, plazo agotado). Un [NetworkError] que llegue hasta aquí se
 * traduce con `MealPlanErrors.map`, igual que en `MealPlanClient`, para que la
 * interfaz reciba un resultado tipado y no una excepción.
 */
class MealPlanController(
    private val source: MealPlanSource,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    initialWeekStart: LocalDate? = null,
) {
    private val lock = Any()

    // Cuenta las cargas y las escrituras; solo la última lanzada escribe el estado.
    private var generation = 0L

    // Entradas de la semana cargada, ya saneadas y sin repetidos.
    private var entries: List<PlannedMeal> = emptyList()

    @Volatile
    var state: MealPlanState
        private set

    init {
        val today = todayProvider()
        // Si el ancla no se puede normalizar (año fuera de rango o una fecha
        // extrema), se guarda tal cual: las cargas la rechazarán con un problema
        // tipado en vez de lanzar DateTimeException desde el constructor.
        val anchor = initialWeekStart ?: today
        state = MealPlanState(
            weekStart = MealPlanDates.weekStartOrNull(anchor) ?: anchor,
            today = today,
        )
    }

    /** Primera carga; no hace nada si la semana ya está cargada o hay algo en curso. */
    fun load(): MealPlanState {
        val current = state
        if (current.phase != MealPlanPhase.Idle) return current
        if (current.week != null && current.loadProblem == null) return current
        return loadWeek(current.weekStart)
    }

    /** Vuelve a pedir la semana actual, aunque ya esté cargada. */
    fun refresh(): MealPlanState = loadWeek(state.weekStart)

    /**
     * Muestra la semana de [weekStart] (se normaliza al lunes). No repite la
     * petición si ya es la semana cargada y no hay fallo.
     */
    fun showWeek(weekStart: LocalDate): MealPlanState {
        val current = state
        if (current.phase != MealPlanPhase.Idle) return current
        // La normalización al lunes se hace antes de tocar la fase: una fecha
        // extrema no puede dejar el estado en «cargando» ni lanzar.
        val normalized = MealPlanDates.weekStartOrNull(weekStart)
            ?: return invalidInput(MealPlanInputProblem.InvalidDate)
        val alreadyLoaded = normalized == current.weekStart && current.week != null && current.loadProblem == null
        if (alreadyLoaded) return current
        return loadWeek(normalized)
    }

    fun previousWeek(): MealPlanState = stepWeek(-1L)

    fun nextWeek(): MealPlanState = stepWeek(1L)

    /** Vuelve a la semana de hoy. */
    fun goToToday(): MealPlanState = showWeek(todayProvider())

    /** Repite la carga que falló; sin fallo de carga (o con algo en curso) no hace nada. */
    fun retry(): MealPlanState {
        val current = state
        if (current.phase != MealPlanPhase.Idle || current.loadProblem == null) return current
        return loadWeek(current.weekStart)
    }

    /** Carga las comidas de hoy según `/today`, aparte de la semana. */
    fun loadToday(): MealPlanState {
        var ticket = 0L
        synchronized(lock) {
            val current = state
            if (current.phase != MealPlanPhase.Idle) return current
            generation++
            ticket = generation
            state = current.copy(phase = MealPlanPhase.Loading, actionFailure = null)
        }

        val result: MealPlanResult<List<PlannedMeal>> = try {
            source.entriesToday()
        } catch (error: NetworkError) {
            MealPlanErrors.map(error)
        } catch (error: RuntimeException) {
            MealPlanResult.Failure(ConnectionProblem.Unknown)
        }

        return synchronized(lock) {
            if (ticket != generation) return@synchronized state
            val updated = when (result) {
                is MealPlanResult.Success -> {
                    val today = todayProvider()
                    val meals = dedupe(result.value)
                        .map { MealPlanMealView.from(it) }
                    state.copy(phase = MealPlanPhase.Idle, today = today, todayMeals = meals)
                }
                else -> failureState(MealPlanOperation.Today, result)
            }
            state = updated
            updated
        }
    }

    /** Crea la entrada del borrador. Ver [MealPlanResult.MaybeCreated] para el fallo ambiguo. */
    fun add(draft: MealPlanDraft): MealPlanState =
        applyEntry(MealPlanOperation.Create) { source.createEntry(draft) }

    /** Mueve [entry] a otro día (y, si se indica, a otro tipo de comida). */
    fun move(
        entry: PlannedMeal,
        date: LocalDate,
        mealType: MealType = entry.mealType,
    ): MealPlanState =
        applyEntry(MealPlanOperation.Move) { source.moveEntry(currentOf(entry), date, mealType) }

    /**
     * Mueve por id la entrada vigente del controlador, conservando su tipo de
     * comida. Es lo que necesita la pantalla, que solo tiene el id que pinta la
     * vista: el [PlannedMeal] completo (JSON crudo, `groupId`, `userId`) se
     * busca aquí y no en la interfaz.
     *
     * Si la entrada ya no está en la semana cargada no se envía nada y queda
     * [MealPlanProblem.NotFound]: sin la entrada no hay cuerpo que componer.
     */
    fun move(entryId: Long, date: LocalDate): MealPlanState {
        val entry = currentOf(entryId) ?: return entryGone(MealPlanOperation.Move)
        return move(entry, date, entry.mealType)
    }

    /** Igual que [move], con el tipo de comida explícito. */
    fun move(entryId: Long, date: LocalDate, mealType: MealType): MealPlanState {
        val entry = currentOf(entryId) ?: return entryGone(MealPlanOperation.Move)
        return move(entry, date, mealType)
    }

    /** Sustituye los datos de [entry] por los del borrador. */
    fun edit(entry: PlannedMeal, draft: MealPlanDraft): MealPlanState =
        applyEntry(MealPlanOperation.Edit) { source.editEntry(currentOf(entry), draft) }

    /**
     * Edita por id la entrada vigente del controlador. Mismo criterio que
     * [move]: buscar por id aquí evita que la interfaz reenvíe una copia vieja,
     * y un id que ya no está se avisa con [MealPlanProblem.NotFound] sin tocar
     * la red.
     */
    fun edit(entryId: Long, draft: MealPlanDraft): MealPlanState {
        val entry = currentOf(entryId) ?: return entryGone(MealPlanOperation.Edit)
        return edit(entry, draft)
    }

    /** Borra la entrada [entryId]. */
    fun delete(entryId: Long): MealPlanState {
        val ticket = beginAction() ?: return state
        val result: MealPlanResult<Unit> = try {
            source.deleteEntry(entryId)
        } catch (error: NetworkError) {
            MealPlanErrors.map(error)
        } catch (error: RuntimeException) {
            MealPlanResult.Failure(ConnectionProblem.Unknown)
        }
        return synchronized(lock) {
            if (ticket != generation) {
                state
            } else {
                val updated = if (result is MealPlanResult.Success) {
                    entries = entries.filterNot { it.id == entryId }
                    readyState()
                } else {
                    failureState(MealPlanOperation.Delete, result)
                }
                state = updated
                updated
            }
        }
    }

    /** Quita el aviso de la última escritura fallida, sin tocar la semana cargada. */
    fun clearActionFailure(): MealPlanState = synchronized(lock) {
        if (state.actionFailure == null) {
            state
        } else {
            state = state.copy(actionFailure = null)
            state
        }
    }

    // ------------------------------------------------------------- privados

    private fun stepWeek(weeks: Long): MealPlanState {
        val current = state
        if (current.phase != MealPlanPhase.Idle) return current
        // plusWeeks desborda en los extremos de LocalDate: se comprueba antes.
        if (MealPlanDates.check(current.weekStart) != null) {
            return invalidInput(MealPlanInputProblem.InvalidDate)
        }
        val target = try {
            current.weekStart.plusWeeks(weeks)
        } catch (ignored: DateTimeException) {
            return invalidInput(MealPlanInputProblem.InvalidDate)
        }
        if (MealPlanDates.check(target) != null) return invalidInput(MealPlanInputProblem.InvalidDate)
        return loadWeek(target)
    }

    private fun loadWeek(weekStart: LocalDate): MealPlanState {
        // La semana se comprueba antes de tocar la fase: si no vale, el estado
        // no pasa por «cargando» ni se pide nada a la red.
        if (MealPlanDates.check(weekStart) != null) {
            return failedLoad(weekStart, MealPlanInputProblem.InvalidDate)
        }
        // Con el año ya acotado a 1900..2999, plusDays no puede desbordar LocalDate.
        val end = weekStart.plusDays(6)
        MealPlanDates.check(weekStart, end)?.let { return failedLoad(weekStart, it) }

        var ticket = 0L
        synchronized(lock) {
            val current = state
            if (current.phase != MealPlanPhase.Idle) return current
            generation++
            ticket = generation
            val sameWeek = current.weekStart == weekStart
            state = current.copy(
                weekStart = weekStart,
                today = todayProvider(),
                phase = MealPlanPhase.Loading,
                loadProblem = null,
                actionFailure = null,
                // Al cambiar de semana no se enseña la anterior: si la nueva
                // falla, la interfaz pinta el error y no datos de otra semana.
                week = if (sameWeek) current.week else null,
            )
        }

        val result: MealPlanResult<List<PlannedMeal>> = try {
            source.listEntries(weekStart, end)
        } catch (error: NetworkError) {
            MealPlanErrors.map(error)
        } catch (error: RuntimeException) {
            MealPlanResult.Failure(ConnectionProblem.Unknown)
        }

        return synchronized(lock) {
            if (ticket != generation) return@synchronized state
            val updated = when (result) {
                is MealPlanResult.Success -> {
                    entries = dedupe(result.value)
                    state.copy(
                        phase = MealPlanPhase.Idle,
                        week = MealPlanWeekView.of(weekStart, entries, todayProvider()),
                        loadProblem = null,
                    )
                }
                else -> state.copy(phase = MealPlanPhase.Idle, loadProblem = result.problemOrNull())
            }
            state = updated
            updated
        }
    }

    /** Estado con un fallo de fecha antes de pedir nada; no pasa por «cargando». */
    private fun failedLoad(weekStart: LocalDate, problem: MealPlanInputProblem): MealPlanState =
        synchronized(lock) {
            state = state.copy(
                weekStart = weekStart,
                today = todayProvider(),
                phase = MealPlanPhase.Idle,
                loadProblem = MealPlanProblem.InvalidInput(problem),
                week = null,
            )
            state
        }

    /** Estado con un dato de entrada rechazado, sin borrar la semana que ya había. */
    private fun invalidInput(problem: MealPlanInputProblem): MealPlanState =
        synchronized(lock) {
            state = state.copy(
                phase = MealPlanPhase.Idle,
                loadProblem = MealPlanProblem.InvalidInput(problem),
            )
            state
        }

    /**
     * Prepara una escritura. Null (sin cambiar nada) si hay algo en curso o no
     * hay semana cargada: no se escribe a ciegas sobre una semana que no se ha
     * podido leer.
     */
    private fun beginAction(): Long? = synchronized(lock) {
        val current = state
        if (current.phase != MealPlanPhase.Idle || current.week == null) {
            null
        } else {
            generation++
            state = current.copy(phase = MealPlanPhase.Saving, actionFailure = null)
            generation
        }
    }

    private fun applyEntry(
        operation: MealPlanOperation,
        call: () -> MealPlanResult<PlannedMeal>,
    ): MealPlanState {
        val ticket = beginAction() ?: return state
        val result: MealPlanResult<PlannedMeal> = try {
            call()
        } catch (error: NetworkError) {
            // Como el cliente: un fallo después de enviar el cuerpo al crear no
            // prueba que no se creara, así que se conserva el «pudo crearse» y
            // la semana se relee antes de repetir.
            if (operation == MealPlanOperation.Create && error is NetworkError.AfterSend) {
                MealPlanResult.MaybeCreated(error.original.toConnectionProblem())
            } else {
                MealPlanErrors.map(error)
            }
        } catch (error: RuntimeException) {
            MealPlanResult.Failure(ConnectionProblem.Unknown)
        }
        var verify = false
        val applied = synchronized(lock) {
            if (ticket != generation) {
                state
            } else {
                val updated = when (result) {
                    is MealPlanResult.Success -> {
                        val saved = result.value
                        // Se quita el id viejo antes de añadir: mover o editar
                        // devuelve la misma entrada y no puede quedar dos veces.
                        entries = dedupe(entries.filterNot { it.id == saved.id } + saved)
                        readyState()
                    }
                    else -> failureState(operation, result)
                }
                verify = needsVerification(operation, result)
                state = updated
                updated
            }
        }
        return if (verify) verifyWeek() else applied
    }

    /**
     * ¿El fallo deja dudas sobre lo que hay en el servidor? Crear con
     * [MealPlanResult.MaybeCreated] sí; mover o editar con un fallo de red que
     * pudo ocurrir después del envío también (el servidor puede haber movido la
     * entrada mientras la interfaz sigue enseñando el día antiguo). Los motivos
     * que prueban que la petición no salió no necesitan comprobación.
     */
    private fun needsVerification(operation: MealPlanOperation, result: MealPlanResult<*>): Boolean =
        when (result) {
            is MealPlanResult.MaybeCreated -> operation == MealPlanOperation.Create
            is MealPlanResult.Failure ->
                (operation == MealPlanOperation.Move || operation == MealPlanOperation.Edit) &&
                    result.problem !in NEVER_SENT
            else -> false
        }

    /**
     * Relee la semana que se está mirando sin tocar el aviso del fallo ni el
     * estado si la lectura también falla: el aviso ya dice lo que pasó y no
     * hay que taparlo con un error de carga. Si sale bien, la semana pasa a
     * mostrar lo que de verdad hay en el servidor, y así nadie repite a ciegas
     * una creación que sí llegó.
     */
    private fun verifyWeek(): MealPlanState {
        var ticket = 0L
        var weekStart = state.weekStart
        synchronized(lock) {
            val current = state
            if (current.phase != MealPlanPhase.Idle || current.week == null) return current
            weekStart = current.weekStart
            generation++
            ticket = generation
            state = current.copy(phase = MealPlanPhase.Loading)
        }
        val result: MealPlanResult<List<PlannedMeal>> = try {
            // La semana ya estaba cargada, así que su rango es válido.
            source.listEntries(weekStart, weekStart.plusDays(6))
        } catch (error: NetworkError) {
            MealPlanErrors.map(error)
        } catch (error: RuntimeException) {
            MealPlanResult.Failure(ConnectionProblem.Unknown)
        }
        return synchronized(lock) {
            if (ticket != generation) return@synchronized state
            state = if (result is MealPlanResult.Success) {
                entries = dedupe(result.value)
                state.copy(
                    phase = MealPlanPhase.Idle,
                    week = MealPlanWeekView.of(weekStart, entries, todayProvider()),
                )
            } else {
                state.copy(phase = MealPlanPhase.Idle)
            }
            state
        }
    }

    /**
     * La entrada vigente con ese id, o null si ya no está en la semana
     * cargada. Se lee bajo el cerrojo: [entries] lo escribe el hilo de trabajo.
     */
    private fun currentOf(entryId: Long): PlannedMeal? =
        synchronized(lock) { entries.firstOrNull { it.id == entryId } }

    /**
     * La entrada vigente con el id de [entry]. La interfaz puede traer una copia
     * anterior a una recarga, con un JSON crudo viejo que reenviaría datos
     * caducados; si la entrada ya no está en la semana cargada se usa la recibida.
     */
    private fun currentOf(entry: PlannedMeal): PlannedMeal =
        synchronized(lock) { entries.firstOrNull { it.id == entry.id } } ?: entry

    /**
     * La entrada pedida por id ya no está: no se manda nada (sin `groupId`,
     * `userId` ni JSON crudo no hay cuerpo que componer) y se avisa como
     * [MealPlanProblem.NotFound] para que la interfaz invite a recargar, en vez
     * de fallar en silencio sobre una entrada que la vista sigue enseñando.
     * Con una operación en curso no se toca el estado.
     */
    private fun entryGone(operation: MealPlanOperation): MealPlanState =
        synchronized(lock) {
            if (state.week == null || state.phase != MealPlanPhase.Idle) return@synchronized state
            state = failureState(operation, MealPlanResult.NotFound)
            state
        }

    /** Estado con la semana recompuesta a partir de [entries]; se llama con el cerrojo tomado. */
    private fun readyState(): MealPlanState {
        val today = todayProvider()
        return state.copy(
            phase = MealPlanPhase.Idle,
            today = today,
            actionFailure = null,
            week = MealPlanWeekView.of(state.weekStart, entries, today),
        )
    }

    /** Estado con el fallo de una escritura; se llama con el cerrojo tomado. */
    private fun failureState(
        operation: MealPlanOperation,
        result: MealPlanResult<*>,
    ): MealPlanState {
        val problem = result.problemOrNull()
            ?: MealPlanProblem.Connection(ConnectionProblem.Unknown)
        return state.copy(
            phase = MealPlanPhase.Idle,
            actionFailure = MealPlanActionFailure(operation, problem),
        )
    }

    /** Sin repetidos por id, en el orden en que llegan, con el tope de memoria. */
    private fun dedupe(items: List<PlannedMeal>): List<PlannedMeal> {
        val seen = HashSet<Long>()
        val result = ArrayList<PlannedMeal>(items.size)
        for (item in items) {
            if (result.size >= MAX_MEAL_PLAN_ENTRIES) break
            if (seen.add(item.id)) result += item
        }
        return result
    }

    private companion object {
        /** Motivos que prueban que la petición no llegó a salir del móvil. */
        val NEVER_SENT = setOf(
            ConnectionProblem.LocalHttpNeedsHomeNetwork,
            ConnectionProblem.SecureConnectionFailed,
            ConnectionProblem.InvalidAddress,
            ConnectionProblem.NotLocalAddress,
            ConnectionProblem.InvalidToken,
        )
    }
}
