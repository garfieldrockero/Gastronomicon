package io.github.garfieldrockero.gastronomicon.ui.mealplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanController
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDraft
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanMealView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanOperation
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanProblem
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanResult
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanSource
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanState
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.mealplan.PlannedMeal
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListController
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Pasarela entre la pantalla y el [MealPlanController]: no decide nada. Lanza
 * cada operación (bloqueante) en un hilo de fondo y publica el estado vigente
 * del controlador. Las reglas (semanas, validación, orden, escrituras) son del
 * núcleo; aquí solo se recuerda qué hoja está abierta y qué ha escrito la persona.
 *
 * - Cada operación pide una fuente nueva con [MealPlanAccess] y un cliente nuevo
 *   con [ClientAccess] (nunca se guardan), como el resto de pantallas.
 * - Mover y editar van **por id**: el núcleo busca la entrada vigente de la
 *   semana (con su JSON crudo, `groupId` y `userId`) y este ViewModel no guarda
 *   ninguna [PlannedMeal]. Si el id ya no está, el estado trae
 *   [MealPlanProblem.NotFound] y se avisa con [MealPlanNotice.EntryMissing].
 * - Mientras hay operaciones en marcha, un sondeo corto publica el estado para
 *   que «cargando» y «guardando» se vean antes de que acabe la petición.
 * - Elegir receta reutiliza [RecipeListController] (búsqueda, filtro y páginas
 *   del núcleo); el planificador no tiene un selector propio en `core/`.
 * - [reset] cancela todo, sube la época y empieza de cero: lo que ya estaba en
 *   vuelo no puede usar la sesión nueva (ver [epoch]).
 */
class MealPlanViewModel(
    private val planAccess: MealPlanAccess,
    private val recipeAccess: ClientAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /**
     * Sube con cada [reset]. Una operación lanzada antes no puede pedir nada con
     * la sesión nueva: su fuente compara la época con la que nació y, si ya no
     * es la vigente, devuelve un fallo tipado sin abrir conexión. El resultado
     * se descarta igualmente al llegar (`isCurrent`), pero así tampoco se usa
     * el servidor ni el token nuevos. Se escribe desde el hilo principal y se
     * lee desde el de fondo, de ahí `@Volatile`.
     */
    @Volatile
    private var epoch = 0L

    private var controller: MealPlanController = newController()
    private var picker: RecipeListController = newPicker()
    private var pickerQuery = ""
    private var dialog: MealPlanDialog = MealPlanDialog.None
    private var notice: MealPlanNotice? = null

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<MealPlanUiState> = _state.asStateFlow()

    // Solo se tocan desde el hilo principal (viewModelScope).
    private var inFlight = 0
    private var poller: Job? = null
    private val running = mutableSetOf<Job>()

    private fun newController(): MealPlanController {
        // La fuente nace con la época de ahora: si reset() la supera, deja de pedir.
        val bornEpoch = epoch
        return MealPlanController(PlanSource(planAccess, isCurrent = { epoch == bornEpoch }))
    }

    private fun newPicker() = RecipeListController(
        RecipeListSource { request -> recipeAccess.newClient().listRecipes(request) },
    )

    private fun snapshot() = MealPlanUiState(
        plan = controller.state,
        dialog = dialog,
        picker = RecipePickerUiState(picker.state, pickerQuery),
        notice = notice,
    )

    private fun publish() {
        _state.value = snapshot()
    }

    // ------------------------------------------------------------- semana

    /** Al mostrar la pantalla: primera carga; no hace nada si la semana ya está cargada. */
    fun onShown() = launchPlan { it.load() }

    fun onRefresh() = launchPlan { it.refresh() }

    fun onPreviousWeek() = launchPlan { it.previousWeek() }

    fun onNextWeek() = launchPlan { it.nextWeek() }

    fun onToday() = launchPlan { it.goToToday() }

    /** «Volver a intentar» tras un fallo de carga. */
    fun onRetry() = launchPlan { if (it.state.canRetry) it.retry() else it.refresh() }

    /** Quita el aviso de fallo de una escritura sin cerrar nada. */
    fun onDismissActionFailure() {
        controller.clearActionFailure()
        publish()
    }

    fun onNoticeShown() {
        notice = null
        publish()
    }

    // -------------------------------------------------------------- añadir

    /**
     * Abre la hoja de «Añadir comida». [date] es el día de la tarjeta que se
     * pulsó; sin él (botón flotante) se propone hoy si está en la semana, y si
     * no, el primer día de la semana.
     */
    fun onAddClick(date: LocalDate? = null) {
        val week = controller.state.week ?: return
        if (controller.state.isBusy) return
        val day = date ?: week.days.firstOrNull { it.isToday }?.date ?: week.weekStart
        controller.clearActionFailure()
        dialog = MealPlanDialog.Add(AddMealForm(date = day))
        publish()
    }

    fun onAddDateChange(date: LocalDate) = updateForm { it.copy(date = date) }

    fun onAddTypeChange(type: MealType) = updateForm { it.copy(mealType = type) }

    fun onAddKindChange(kind: AddKind) = updateForm { it.copy(kind = kind) }

    fun onAddTitleChange(text: String) = updateForm { it.copy(title = text) }

    fun onAddTextChange(text: String) = updateForm { it.copy(text = text) }

    fun onPickRecipeOpen() {
        updateForm { it.copy(picking = true) }
        launchPicker { it.load() }
    }

    fun onPickRecipeBack() = updateForm { it.copy(picking = false) }

    fun onRecipePicked(id: String, name: String) =
        updateForm { it.copy(recipe = PickedRecipe(id, name), picking = false) }

    /** Cada letra filtra lo ya cargado, sin red. */
    fun onPickerQueryChange(text: String) {
        pickerQuery = text.take(RecipeListState.MAX_FILTER_LENGTH)
        picker.setLocalFilter(pickerQuery)
        publish()
    }

    /** «Buscar en todo Mealie»: pide al servidor las que coinciden y quita el filtro local. */
    fun onPickerSearch() {
        val text = pickerQuery.trim()
        picker.setLocalFilter("")
        launchPicker { it.search(text.ifEmpty { null }) }
    }

    fun onPickerRetry() = launchPicker { if (it.state.canRetry) it.retry() else it.refresh() }

    fun onPickerLoadMore() {
        if (!picker.state.canLoadMore) return
        launchPicker { it.loadMore() }
    }

    fun onConfirmAdd() {
        val form = (dialog as? MealPlanDialog.Add)?.form ?: return
        val current = controller.state
        if (current.isBusy || current.week == null) return
        val draft = when (form.kind) {
            AddKind.Recipe -> MealPlanDraft(
                date = form.date,
                mealType = form.mealType,
                recipeId = form.recipe?.id,
            )
            AddKind.Note -> MealPlanDraft(
                date = form.date,
                mealType = form.mealType,
                title = form.title,
                text = form.text,
            )
        }
        launchPlan(onDone = { result ->
            if (result.actionFailure == null) {
                dialog = MealPlanDialog.None
                notice = MealPlanNotice.Added
            }
        }) { it.add(draft) }
    }

    // --------------------------------------------------------------- mover

    fun onMoveClick(meal: MealPlanMealView) {
        if (controller.state.isBusy) return
        controller.clearActionFailure()
        dialog = MealPlanDialog.Move(meal, meal.date, meal.mealType)
        publish()
    }

    fun onMoveDateChange(date: LocalDate) {
        val current = dialog as? MealPlanDialog.Move ?: return
        dialog = current.copy(date = date)
        publish()
    }

    fun onMoveTypeChange(type: MealType) {
        val current = dialog as? MealPlanDialog.Move ?: return
        dialog = current.copy(mealType = type)
        publish()
    }

    fun onConfirmMove() {
        val move = dialog as? MealPlanDialog.Move ?: return
        if (controller.state.isBusy) return
        launchPlan(onDone = { result ->
            when (val failure = result.actionFailure) {
                null -> {
                    dialog = MealPlanDialog.None
                    notice = MealPlanNotice.Moved
                }
                // El núcleo no encontró la entrada por id (o el servidor contesta
                // 404): ya no está. Se cierra la hoja y se avisa con la acción de
                // recargar, sin repetir el mismo aviso en la semana.
                else -> {
                    val gone = failure.operation == MealPlanOperation.Move &&
                        failure.problem == MealPlanProblem.NotFound
                    if (gone) {
                        controller.clearActionFailure()
                        dialog = MealPlanDialog.None
                        notice = MealPlanNotice.EntryMissing
                    }
                }
            }
        }) { it.move(move.meal.id, move.date, move.mealType) }
    }

    // -------------------------------------------------------------- borrar

    fun onDeleteClick(meal: MealPlanMealView) {
        if (controller.state.isBusy) return
        controller.clearActionFailure()
        dialog = MealPlanDialog.ConfirmDelete(meal)
        publish()
    }

    fun onConfirmDelete() {
        val confirm = dialog as? MealPlanDialog.ConfirmDelete ?: return
        if (controller.state.isBusy) return
        launchPlan(onDone = { result ->
            if (result.actionFailure == null) {
                dialog = MealPlanDialog.None
                notice = MealPlanNotice.Deleted
            }
        }) { it.delete(confirm.meal.id) }
    }

    /** Cierra la hoja o el diálogo abierto; con un guardado en curso no hace nada. */
    fun onDismissDialog() {
        if (controller.state.isSaving) return
        controller.clearActionFailure()
        dialog = MealPlanDialog.None
        publish()
    }

    /** Cancela lo que corra y empieza de cero: sesión cambiada, borrada o primera configuración. */
    fun reset() {
        running.forEach { it.cancel() }
        running.clear()
        poller?.cancel()
        poller = null
        inFlight = 0
        // Nueva época: lo que siga en vuelo no puede pedir con la sesión nueva.
        epoch++
        pickerQuery = ""
        dialog = MealPlanDialog.None
        notice = null
        controller = newController()
        picker = newPicker()
        publish()
    }

    // ------------------------------------------------------------ privados

    private fun updateForm(change: (AddMealForm) -> AddMealForm) {
        val current = dialog as? MealPlanDialog.Add ?: return
        dialog = current.copy(form = change(current.form))
        publish()
    }

    private fun launchPlan(
        onDone: (MealPlanState) -> Unit = {},
        operation: (MealPlanController) -> MealPlanState,
    ) {
        val target = controller
        launchTracked(isCurrent = { target === controller }, onResult = onDone) {
            withContext(ioDispatcher) { operation(target) }
        }
    }

    private fun launchPicker(operation: (RecipeListController) -> RecipeListState) {
        val target = picker
        launchTracked<RecipeListState>(isCurrent = { target === picker }, onResult = {}) {
            withContext(ioDispatcher) { operation(target) }
        }
    }

    private fun <T> launchTracked(
        isCurrent: () -> Boolean,
        onResult: (T) -> Unit,
        block: suspend () -> T,
    ) {
        viewModelScope.launch {
            // El propio trabajo, para poder cancelarlo en reset().
            val self = coroutineContext[Job]
            if (self != null) running.add(self)
            inFlight++
            if (poller == null) {
                poller = viewModelScope.launch {
                    while (true) {
                        delay(POLL_MILLIS)
                        publish()
                    }
                }
            }
            try {
                // El núcleo devuelve fallos tipados; esto es solo una red de
                // seguridad para que nada raro tumbe la corrutina de la pantalla.
                val result = try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (unexpected: Exception) {
                    null
                }
                // Una sesión reiniciada mientras corría: el resultado viejo se descarta.
                if (result != null && isCurrent()) onResult(result)
            } finally {
                if (self != null) running.remove(self)
                // Tras reset() el contador ya se puso a cero: solo cuenta quien sigue vigente.
                if (isCurrent()) {
                    inFlight--
                    if (inFlight <= 0) {
                        inFlight = 0
                        poller?.cancel()
                        poller = null
                    }
                }
                publish()
            }
        }
    }

    companion object {
        private const val POLL_MILLIS = 120L

        fun factory(planAccess: MealPlanAccess, recipeAccess: ClientAccess): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MealPlanViewModel(planAccess, recipeAccess) as T
            }
    }
}

/**
 * Fuente de UNA sesión. Crea el cliente en cada llamada con [MealPlanAccess] y
 * no deja que un fallo al crearlo salga como excepción: sin sesión guardada,
 * almacén seguro sin respuesta o HTTP local apagado con datos móviles, el
 * controlador recibe un [MealPlanResult] de fallo tipado. La creación se
 * envuelve sola; la llamada va fuera para no perder el matiz de
 * [NetworkError.AfterSend] (el controlador ya lo traduce a
 * [MealPlanResult.MaybeCreated] al crear).
 *
 * [isCurrent] compara la época de la sesión con la que nació esta fuente: si
 * `reset()` la ha superado, no se pide nada (ni se lee la sesión nueva) y se
 * devuelve un fallo que el ViewModel descarta.
 */
private class PlanSource(
    private val access: MealPlanAccess,
    private val isCurrent: () -> Boolean,
) : MealPlanSource {

    override fun listEntries(start: LocalDate, end: LocalDate): MealPlanResult<List<PlannedMeal>> =
        guarded { it.listEntries(start, end) }

    override fun entriesToday(): MealPlanResult<List<PlannedMeal>> = guarded { it.entriesToday() }

    override fun getEntry(id: Long): MealPlanResult<PlannedMeal> = guarded { it.getEntry(id) }

    override fun createEntry(draft: MealPlanDraft): MealPlanResult<PlannedMeal> =
        guarded { it.createEntry(draft) }

    override fun moveEntry(entry: PlannedMeal, date: LocalDate, mealType: MealType): MealPlanResult<PlannedMeal> =
        guarded { it.moveEntry(entry, date, mealType) }

    override fun editEntry(entry: PlannedMeal, draft: MealPlanDraft): MealPlanResult<PlannedMeal> =
        guarded { it.editEntry(entry, draft) }

    override fun deleteEntry(id: Long): MealPlanResult<Unit> = guarded { it.deleteEntry(id) }

    private fun <T> guarded(call: (MealPlanSource) -> MealPlanResult<T>): MealPlanResult<T> {
        if (!isCurrent()) return MealPlanResult.Failure(ConnectionProblem.Unknown)
        val source = try {
            access.newSource()
        } catch (error: NetworkError) {
            return failureOf(error)
        }
        return call(source)
    }

    /** 401 y 403 se distinguen; el resto se queda en el motivo de conexión de siempre. */
    private fun failureOf(error: NetworkError): MealPlanResult<Nothing> = when {
        error is NetworkError.HttpError && error.statusCode == 401 -> MealPlanResult.Unauthorized
        error is NetworkError.HttpError && error.statusCode == 403 -> MealPlanResult.Forbidden
        else -> MealPlanResult.Failure(error.toConnectionProblem())
    }
}
