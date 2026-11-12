package io.github.garfieldrockero.gastronomicon.ui.mealplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanController
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDraft
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanInputProblem
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
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAddition
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAdditions
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerController
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerState
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShopping
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingOutcome
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingUi
import io.github.garfieldrockero.gastronomicon.ui.components.guardedBy
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingAccess
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
 * - Editar va **por id**: el núcleo busca la entrada vigente de la semana (con
 *   su JSON crudo, `groupId` y `userId`). Si el id ya no está, el estado trae
 *   [MealPlanProblem.NotFound] y se avisa con [MealPlanNotice.EntryMissing].
 * - Para **rellenar la hoja de editar** hace falta el `recipeId`, el título y
 *   la nota originales, que `MealPlanMealView` no lleva (solo el texto ya
 *   compuesto). La fuente recuerda la última lectura por id: son datos ya
 *   saneados que el propio núcleo acaba de entregar, no una regla de negocio.
 *   Borrar no depende de esa caché.
 * - Mientras hay operaciones en marcha, un sondeo corto publica el estado para
 *   que «cargando» y «guardando» se vean antes de que acabe la petición.
 * - Elegir receta reutiliza [RecipeListController] (búsqueda, filtro y páginas
 *   del núcleo); el planificador no tiene un selector propio en `core/`.
 * - [reset] cancela todo, sube la época y empieza de cero: lo que ya estaba en
 *   vuelo no puede usar la sesión nueva (ver [epoch]).
 *
 * Fase 9, extra 1: «añadir los ingredientes de esta semana a la compra». Qué
 * recetas entran (y cómo se suman las escalas de una receta repetida) lo decide
 * `RecipeAdditions`; aquí solo se pide el plan al abrir el diálogo y se manda al
 * confirmar, sin reintentos.
 */
class MealPlanViewModel(
    private val planAccess: MealPlanAccess,
    private val recipeAccess: ClientAccess,
    /**
     * Transporte de la compra. Sin él (null) la acción de la semana ni se
     * ofrece: no hay de dónde sacar la sesión para el `POST`.
     */
    private val shoppingAccess: ShoppingAccess? = null,
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

    // La fuente de la sesión vigente; se cambia con ella y recuerda la última
    // lectura por id para poder rellenar la hoja de editar.
    private lateinit var planSource: PlanSource
    private var controller: MealPlanController = newController()
    private var picker: RecipeListController = newPicker()
    private var pickerQuery = ""
    private var dialog: MealPlanDialog = MealPlanDialog.None
    private var notice: MealPlanNotice? = null

    // Día del aviso «se fue a otra semana»: con él, «Ver esa semana» sabe a dónde ir.
    private var noticeDate: LocalDate? = null
    private var noticeSeq = 0L

    // «Añadir a la compra» (fase 9, extra 1): el diálogo, el envío y su aviso. Las
    // recetas y sus escalas las fija el núcleo al abrir el diálogo.
    private var addToShopping = AddToShoppingUi(available = shoppingAccess != null)
    private var shoppingPicker: ShoppingListPickerController? = null
    private var shoppingAdditions: List<RecipeAddition> = emptyList()

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<MealPlanUiState> = _state.asStateFlow()

    // Solo se tocan desde el hilo principal (viewModelScope).
    private var inFlight = 0
    private var poller: Job? = null
    private val running = mutableSetOf<Job>()

    private fun newController(): MealPlanController {
        // La fuente nace con la época de ahora: si reset() la supera, deja de pedir.
        val bornEpoch = epoch
        planSource = PlanSource(planAccess, isCurrent = { epoch == bornEpoch })
        return MealPlanController(planSource)
    }

    private fun newPicker() = RecipeListController(
        RecipeListSource { request -> recipeAccess.newClient().listRecipes(request) },
    )

    private fun snapshot() = MealPlanUiState(
        plan = controller.state,
        dialog = dialog,
        picker = RecipePickerUiState(picker.state, pickerQuery),
        notice = notice,
        noticeId = noticeSeq,
        noticeDate = noticeDate,
        addToShopping = addToShopping,
    )

    private fun publish() {
        _state.value = snapshot()
    }

    /** Apunta el aviso y sube el contador: la pantalla lo anuncia aunque repita tipo. */
    private fun showNotice(value: MealPlanNotice, date: LocalDate? = null) {
        notice = value
        noticeDate = date
        noticeSeq++
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

    /**
     * El aviso corto ya se ha visto: se consume (queda en `null`, el contador no cambia).
     * [id] dice cuál ha terminado; si no es el que está a la vista, no se toca nada: un
     * final que llega tarde (otro aviso emitido justo al cerrarse el anterior) no puede
     * comerse la confirmación que aún no se ha visto.
     */
    fun onNoticeShown(id: Long) {
        if (notice == null || id != noticeSeq) return
        notice = null
        publish()
    }

    /**
     * «Ver esa semana» del aviso: lleva a la semana del día al que se guardó la
     * comida. El núcleo normaliza el día al lunes de su semana.
     */
    fun onShowNoticeWeek() {
        val date = noticeDate ?: return
        noticeDate = null
        launchPlan { it.showWeek(date) }
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

    /** Raciones escritas; el núcleo decide si el número vale al confirmar. */
    fun onAddScaleChange(text: String) {
        // Mientras se corrige el número, el aviso de ese campo ya sobra.
        clearScaleFailure()
        updateForm { it.copy(scaleText = text.take(MAX_SCALE_INPUT)) }
    }

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

    /**
     * Confirma la hoja: crea la comida o guarda los cambios de la que se está
     * editando. El borrador lleva los textos tal cual y las raciones como
     * número; el núcleo es quien valida y quien decide el fallo.
     */
    fun onConfirmAdd() {
        val form = (dialog as? MealPlanDialog.Add)?.form ?: return
        val current = controller.state
        if (current.isBusy || current.week == null) return
        val hasRecipe = form.kind == AddKind.Recipe
        val draft = MealPlanDraft(
            date = form.date,
            mealType = form.mealType,
            recipeId = if (hasRecipe) form.recipe?.id else null,
            title = form.title,
            text = form.text,
            recipeScale = if (hasRecipe) scaleOf(form.scaleText) else 1.0,
        )
        val editingId = form.editingId
        launchPlan(onDone = { result ->
            when (val failure = result.actionFailure) {
                null -> {
                    dialog = MealPlanDialog.None
                    when {
                        editingId == null -> showNotice(MealPlanNotice.Added)
                        // Editada a un día de otra semana: la semana a la vista no
                        // cambia, así que el aviso ofrece ir a verla.
                        !weekContains(form.date) -> showNotice(MealPlanNotice.MovedOtherWeek, form.date)
                        else -> showNotice(MealPlanNotice.Edited)
                    }
                }
                else -> {
                    // Editar una entrada que ya no está: se cierra la hoja y se
                    // invita a recargar, sin dejar el fallo repetido en la semana.
                    val gone = editingId != null &&
                        failure.operation == MealPlanOperation.Edit &&
                        failure.problem == MealPlanProblem.NotFound
                    if (gone) {
                        controller.clearActionFailure()
                        dialog = MealPlanDialog.None
                        showNotice(MealPlanNotice.EntryMissing)
                    }
                }
            }
        }) {
            if (editingId != null) it.edit(editingId, draft) else it.add(draft)
        }
    }

    /**
     * Abre la misma hoja para editar [meal]. Los datos finos (receta, título y
     * nota sin componer) salen de la última lectura de la fuente; si la entrada
     * ya no está, se avisa y no se abre nada.
     */
    fun onEditClick(meal: MealPlanMealView) {
        if (controller.state.isBusy) return
        val entry = planSource.entry(meal.id)
        if (entry == null) {
            controller.clearActionFailure()
            showNotice(MealPlanNotice.EntryMissing)
            publish()
            return
        }
        controller.clearActionFailure()
        dialog = MealPlanDialog.Add(editForm(entry, meal))
        publish()
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
                showNotice(MealPlanNotice.Deleted)
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

    // ---------------------------------------- añadir la semana a la compra

    /**
     * Abre el diálogo de «añadir esta semana a la compra». Qué recetas entran,
     * cómo se suman las escalas de una receta repetida y el tope de 30 lo decide
     * el núcleo ([RecipeAdditions.plan]) sobre la última semana leída: aquí no se
     * cuenta nada. Sin ninguna receta el diálogo se abre explicándolo, sin pedir
     * listas.
     */
    fun onAddToShoppingClick() {
        val store = shoppingAccess ?: return
        if (controller.state.isBusy) return
        val plan = RecipeAdditions.plan(planSource.weekEntries())
        controller.clearActionFailure()
        shoppingPicker = null
        shoppingAdditions = plan.additions
        addToShopping = AddToShoppingUi(
            available = true,
            open = true,
            recipes = plan.additions.size,
            skipped = plan.skippedRecipes,
            truncated = plan.truncated,
            // El aviso de un envío anterior se queda hasta que la persona lo cierre.
            notice = addToShopping.notice,
            picker = if (plan.isEmpty) {
                ShoppingListPickerState()
            } else {
                ShoppingListPickerState(phase = ShoppingListPickerPhase.Loading)
            },
        )
        publish()
        if (!plan.isEmpty) loadShoppingLists(store)
    }

    /** Elige a qué lista van los ingredientes de la semana. No toca la red. */
    fun onAddToShoppingListSelected(listId: String) {
        if (!addToShopping.open) return
        val picker = shoppingPicker ?: return
        addToShopping = addToShopping.copy(picker = picker.rememberOpenList(listId), problem = null)
        publish()
    }

    /** «Volver a mirar» de las listas: las vuelve a leer. */
    fun onAddToShoppingRetryLists() {
        val store = shoppingAccess ?: return
        if (!addToShopping.open || addToShopping.sending) return
        addToShopping = addToShopping.copy(problem = null)
        publish()
        loadShoppingLists(store)
    }

    /** Cierra el diálogo; con el `POST` en vuelo no hace nada. */
    fun onAddToShoppingDismiss() {
        if (addToShopping.sending) return
        addToShopping = addToShopping.copy(open = false, problem = null, sending = false)
        publish()
    }

    /** Cierra el aviso de después de añadir. */
    fun onAddToShoppingNoticeDismiss() {
        addToShopping = addToShopping.copy(notice = null)
        publish()
    }

    /**
     * Confirma: manda las recetas que ya fijó el núcleo a la lista elegida. La
     * tanda se valida otra vez antes de enviar y no se reintenta nunca sola: un
     * desenlace dudoso es el aviso de mirar la lista antes de repetir.
     */
    fun onAddToShoppingConfirm() {
        val store = shoppingAccess ?: return
        val current = addToShopping
        if (!current.open || current.sending) return
        val listId = current.picker.openListId ?: return
        val additions = shoppingAdditions
        val invalid = RecipeAdditions.check(additions)
        if (invalid != null) {
            addToShopping = current.copy(problem = AddToShoppingOutcome.Invalid(invalid))
            publish()
            return
        }
        val listName = current.picker.openList?.displayName.orEmpty()
        addToShopping = current.copy(sending = true, problem = null)
        publish()
        val bornEpoch = epoch
        // La sesión se lee solo si sigue siendo la de este envío: tras `reset()`
        // no se abre conexión ni se usa el token nuevos.
        val session = store.guardedBy { epoch == bornEpoch }
        launchTracked<AddToShoppingOutcome>(
            isCurrent = { epoch == bornEpoch },
            onResult = { outcome ->
                addToShopping = when (outcome) {
                    is AddToShoppingOutcome.Added, is AddToShoppingOutcome.MaybeAdded ->
                        addToShopping.copy(open = false, sending = false, problem = null, notice = outcome)
                    else -> addToShopping.copy(sending = false, problem = outcome)
                }
            },
        ) {
            withContext(ioDispatcher) { AddToShopping.add(session, listId, additions, listName) }
        }
    }

    /** Lee la página de listas en segundo plano; el selector es del núcleo. */
    private fun loadShoppingLists(store: ShoppingAccess) {
        val bornEpoch = epoch
        val picker = AddToShopping.newPicker(store.guardedBy { epoch == bornEpoch })
        shoppingPicker = picker
        launchTracked<ShoppingListPickerState>(
            isCurrent = { shoppingPicker === picker },
            onResult = { loaded -> addToShopping = addToShopping.copy(picker = loaded) },
        ) {
            withContext(ioDispatcher) { picker.load() }
        }
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
        noticeDate = null
        shoppingPicker = null
        shoppingAdditions = emptyList()
        addToShopping = AddToShoppingUi(available = shoppingAccess != null)
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

    /**
     * Retira el fallo de raciones al escribir en su campo: el campo se quedaba
     * en rojo mientras la persona ya lo estaba arreglando. Aquí no se valida
     * nada; el núcleo vuelve a decidir al guardar.
     */
    private fun clearScaleFailure() {
        val problem = controller.state.actionFailure?.problem ?: return
        if (problem is MealPlanProblem.InvalidInput && problem.problem == MealPlanInputProblem.InvalidRecipeScale) {
            controller.clearActionFailure()
        }
    }

    /** ¿[date] cae en la semana a la vista? La regla la da el núcleo, no la pantalla. */
    private fun weekContains(date: LocalDate): Boolean =
        controller.state.week?.contains(date) == true

    /**
     * Hoja de editar rellenada con la entrada vigente. Se usan los textos sin
     * componer ([PlannedMeal.title] y [PlannedMeal.note]) porque la vista los
     * junta para pintarlos y editarlos así los separaría mal.
     */
    private fun editForm(entry: PlannedMeal, meal: MealPlanMealView): AddMealForm {
        val recipeId = entry.recipeId
        return AddMealForm(
            date = entry.date,
            mealType = entry.mealType,
            kind = if (recipeId != null) AddKind.Recipe else AddKind.Note,
            recipe = recipeId?.let { PickedRecipe(it, entry.recipeName ?: meal.title) },
            title = entry.title,
            text = entry.note,
            // La vista ya compone el número con la coma castellana; se quita el «×».
            scaleText = meal.scaleText?.removePrefix("×").orEmpty(),
            editingId = entry.id,
        )
    }

    /**
     * Texto de raciones a número. Aquí no se valida nada: un texto que no sea
     * número viaja como NaN y el núcleo lo rechaza con su propio mensaje. La
     * coma se acepta porque es como se escribe en castellano.
     */
    private fun scaleOf(text: String): Double {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 1.0
        return trimmed.replace(',', '.').toDoubleOrNull() ?: Double.NaN
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

        /** Tope del texto de raciones: el número útil no llega ni a cinco caracteres. */
        private const val MAX_SCALE_INPUT = 10

        /**
         * @param shoppingAccess transporte de la compra para «añadir la semana a
         *   la compra». Sin él la acción no se ofrece: quien monte el ViewModel
         *   tiene que pasar el `ShoppingAccess` de la app (en `MainActivity`,
         *   `container.shoppingAccess`). Pendiente de cablear: el alcance de esta
         *   tarea no incluía la actividad.
         */
        fun factory(
            planAccess: MealPlanAccess,
            recipeAccess: ClientAccess,
            shoppingAccess: ShoppingAccess? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MealPlanViewModel(planAccess, recipeAccess, shoppingAccess) as T
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
 *
 * También recuerda por id la última lectura, ya saneada: la hoja de editar
 * necesita el `recipeId` y los textos sin componer, que la vista no lleva.
 * Es una caché de presentación, con el tope que ya aplica el núcleo; no
 * decide nada ni sustituye al estado del controlador.
 */
private class PlanSource(
    private val access: MealPlanAccess,
    private val isCurrent: () -> Boolean,
) : MealPlanSource {

    private val remembered = HashMap<Long, PlannedMeal>()

    /**
     * Última semana leída, en el orden en que la mandó el servidor. La usa
     * «añadir la semana a la compra»: el plan de recetas lo compone el núcleo y
     * el orden de la lista tiene que ser estable.
     */
    private var week: List<PlannedMeal> = emptyList()

    /** La última entrada leída con ese id, o null si esta sesión no la ha visto. */
    fun entry(id: Long): PlannedMeal? = synchronized(remembered) { remembered[id] }

    /** La semana tal como se leyó (vacía si esta sesión no ha leído ninguna). */
    fun weekEntries(): List<PlannedMeal> = synchronized(remembered) { week }

    override fun listEntries(start: LocalDate, end: LocalDate): MealPlanResult<List<PlannedMeal>> =
        guarded { it.listEntries(start, end) }.also { rememberAll(it) }

    override fun entriesToday(): MealPlanResult<List<PlannedMeal>> =
        guarded { it.entriesToday() }.also { rememberSome(it) }

    override fun getEntry(id: Long): MealPlanResult<PlannedMeal> =
        guarded { it.getEntry(id) }.also { rememberOne(it) }

    override fun createEntry(draft: MealPlanDraft): MealPlanResult<PlannedMeal> =
        guarded { it.createEntry(draft) }.also { rememberOne(it) }

    override fun moveEntry(entry: PlannedMeal, date: LocalDate, mealType: MealType): MealPlanResult<PlannedMeal> =
        guarded { it.moveEntry(entry, date, mealType) }.also { rememberOne(it) }

    override fun editEntry(entry: PlannedMeal, draft: MealPlanDraft): MealPlanResult<PlannedMeal> =
        guarded { it.editEntry(entry, draft) }.also { rememberOne(it) }

    override fun deleteEntry(id: Long): MealPlanResult<Unit> =
        guarded { it.deleteEntry(id) }.also { result ->
            if (result is MealPlanResult.Success) synchronized(remembered) { remembered.remove(id) }
        }

    /** La semana leída sustituye a lo recordado: es lo que hay en el servidor ahora. */
    private fun rememberAll(result: MealPlanResult<List<PlannedMeal>>) {
        if (result !is MealPlanResult.Success) return
        synchronized(remembered) {
            remembered.clear()
            for (meal in result.value) remembered[meal.id] = meal
            week = result.value.toList()
        }
    }

    /** Lo de hoy no sustituye la semana: solo completa lo que esta sesión ha visto. */
    private fun rememberSome(result: MealPlanResult<List<PlannedMeal>>) {
        if (result !is MealPlanResult.Success) return
        synchronized(remembered) {
            for (meal in result.value) remembered[meal.id] = meal
        }
    }

    private fun rememberOne(result: MealPlanResult<PlannedMeal>) {
        if (result !is MealPlanResult.Success) return
        synchronized(remembered) { remembered[result.value.id] = result.value }
    }

    private fun <T> guarded(call: (MealPlanSource) -> MealPlanResult<T>): MealPlanResult<T> {
        if (!isCurrent()) return MealPlanResult.Failure(ConnectionProblem.Unknown)
        // Solo se envuelve la creación de la fuente: la llamada va fuera para no
        // perder el matiz de AfterSend al crear o editar.
        val source = try {
            access.newSource()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NetworkError) {
            return failureOf(error)
        } catch (error: RuntimeException) {
            // El almacén seguro sin respuesta lanza IllegalStateException. No ha
            // salido nada, así que es un fallo normal y no un MaybeCreated.
            return MealPlanResult.Failure(ConnectionProblem.Unknown)
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
