package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.client.InputProblem
import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.QuantityParser
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftErrorKind
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftIngredient
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftNote
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftOrganizer
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftStep
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftValidation
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import io.github.garfieldrockero.gastronomicon.platform.ImageRecodeResult
import io.github.garfieldrockero.gastronomicon.platform.ImageRecoder
import io.github.garfieldrockero.gastronomicon.platform.PhotoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Crear una receta nueva o editar una que ya existe. */
sealed interface EditorMode {
    data object Create : EditorMode

    data class Edit(val slug: String) : EditorMode
}

/** Una línea de ingredientes tal como se edita: cantidad y nota; unidad y alimento solo se leen. */
data class IngredientForm(
    val quantity: String = "",
    val unit: String = "",
    val food: String = "",
    val note: String = "",
    val title: String = "",
    val sourceId: String? = null,
)

/** Un paso tal como se edita. */
data class StepForm(
    val title: String = "",
    val text: String = "",
    val sourceId: String? = null,
)

/**
 * Lo que se escribe en el formulario, sin lógica de servidor. [notes] y
 * [performTime] no se editan en esta V1, pero viajan en el borrador para que
 * guardar no borre lo que ya tenía la receta.
 */
data class RecipeForm(
    val name: String = "",
    val description: String = "",
    val servings: String = "",
    val totalTime: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val performTime: String = "",
    val ingredients: List<IngredientForm> = emptyList(),
    val steps: List<StepForm> = emptyList(),
    val notes: List<DraftNote> = emptyList(),
    val tags: List<DraftOrganizer> = emptyList(),
    val categories: List<DraftOrganizer> = emptyList(),
) {
    /** Borrador para el núcleo, más los errores de formato que solo puede ver el formulario. */
    fun toDraft(): Pair<RecipeDraft, List<DraftError>> {
        val errors = ArrayList<DraftError>()
        val parsedServings = parseNumber(servings, DraftField.Servings, null, errors)
        val parsedIngredients = ingredients.mapIndexed { index, row ->
            DraftIngredient(
                quantity = parseNumber(row.quantity, DraftField.IngredientQuantity, index, errors),
                unit = row.unit,
                food = row.food,
                note = row.note,
                title = row.title,
                sourceId = row.sourceId,
            )
        }
        val parsedSteps = steps.map { DraftStep(title = it.title, text = it.text, sourceId = it.sourceId) }
        return RecipeDraft(
            name = name,
            description = description,
            servings = parsedServings,
            totalTime = totalTime,
            prepTime = prepTime,
            cookTime = cookTime,
            performTime = performTime,
            ingredients = parsedIngredients,
            steps = parsedSteps,
            notes = notes,
            tags = tags,
            categories = categories,
        ) to errors
    }

    companion object {
        fun from(draft: RecipeDraft): RecipeForm = RecipeForm(
            name = draft.name,
            description = draft.description,
            servings = draft.servings?.let(::formatNumber) ?: "",
            totalTime = draft.totalTime,
            prepTime = draft.prepTime,
            cookTime = draft.cookTime,
            performTime = draft.performTime,
            ingredients = draft.ingredients.map { row ->
                IngredientForm(
                    quantity = row.quantity?.let(::formatNumber) ?: "",
                    unit = row.unit,
                    food = row.food,
                    note = row.note,
                    title = row.title,
                    sourceId = row.sourceId,
                )
            },
            steps = draft.steps.map { StepForm(it.title, it.text, it.sourceId) },
            notes = draft.notes,
            tags = draft.tags,
            categories = draft.categories,
        )
    }
}

/** Etiquetas y categorías que ofrece el servidor, más las que ya lleva la receta. */
data class OrganizerCatalog(
    val tags: List<DraftOrganizer> = emptyList(),
    val categories: List<DraftOrganizer> = emptyList(),
    val loading: Boolean = false,
    val problem: ConnectionProblem? = null,
)

/**
 * Por qué no se ha podido preparar o subir la foto. Sin textos, sin rutas y sin
 * el Uri: la pantalla elige el mensaje de cada caso.
 */
sealed interface PhotoProblem {
    /** Fallo de red o del servidor; el motivo real viene del núcleo. */
    data class Network(val problem: ConnectionProblem) : PhotoProblem

    /** La foto de entrada o el JPEG resultante pasa del tope de tamaño. */
    data object TooLarge : PhotoProblem

    /** El fichero elegido no es una imagen que se pueda usar. */
    data object NotAnImage : PhotoProblem

    /**
     * El JPEG seguía llevando metadatos (EXIF, XMP…) y el núcleo no lo sube:
     * las coordenadas de casa no salen del móvil.
     */
    data object HasMetadata : PhotoProblem

    /** El móvil no tiene cámara o ninguna app que haga fotos. */
    data object NoCamera : PhotoProblem

    /** No se ha podido abrir la galería del sistema. */
    data object PickFailed : PhotoProblem

    /** El servidor no ha aceptado la foto (permisos del token, tipo…). */
    data object Rejected : PhotoProblem

    /** La receta ya no existe en el servidor (404). */
    data object NotFound : PhotoProblem

    /** Cualquier otra cosa. */
    data object Unknown : PhotoProblem
}

/**
 * La foto de la receta en el formulario. Solo existe en modo edición: una
 * receta nueva se guarda antes de tener foto.
 *
 * No guarda el Uri, la ruta ni los bytes: solo una vista previa pequeña ya
 * recodificada ([preview]) y lo que hace falta para pintar el aviso. Los bytes
 * que se suben viven en el ViewModel, fuera del estado.
 *
 * [uploadedCount] sube con cada subida que sale bien; la ruta lo usa para
 * refrescar la foto del detalle y la caché de imágenes de esa receta.
 */
data class PhotoState(
    val preview: Bitmap? = null,
    val preparing: Boolean = false,
    val uploading: Boolean = false,
    val problem: PhotoProblem? = null,
    val done: Boolean = false,
    val uploadedCount: Int = 0,
) {
    /** Hay una foto en marcha (recodificando o subiendo): no se toca nada más. */
    val busy: Boolean get() = preparing || uploading
}

/** Estado del formulario. La interfaz solo lo pinta y avisa de lo que toca la persona. */
sealed interface RecipeEditorState {
    data object Closed : RecipeEditorState

    data class Loading(val mode: EditorMode) : RecipeEditorState

    data object NotFound : RecipeEditorState

    data class LoadFailed(val mode: EditorMode, val problem: ConnectionProblem) : RecipeEditorState

    data class Editing(
        val mode: EditorMode,
        val form: RecipeForm,
        val baseline: RecipeDraft,
        val errors: List<DraftError> = emptyList(),
        /** Sube en cada «Guardar» que no pasa la validación: la pantalla vuelve a subir a los avisos. */
        val failedSaveAttempts: Int = 0,
        val serverDetails: List<ValidationDetail> = emptyList(),
        val catalogs: OrganizerCatalog = OrganizerCatalog(),
        val saving: Boolean = false,
        val saveProblem: ConnectionProblem? = null,
        val rejected: Boolean = false,
        val createdIncomplete: Boolean = false,
        val showDiscardConfirm: Boolean = false,
        val dirty: Boolean = false,
        /** Solo en modo edición: la receta ya tiene slug. */
        val photo: PhotoState = PhotoState(),
        /**
         * El núcleo no sabe si la receta se llegó a crear. Se avisa y «Guardar»
         * pide confirmación antes de repetir, para no dejar dos recetas.
         */
        val maybeCreated: Boolean = false,
        /** Diálogo de «¿guardar otra vez?» tras [maybeCreated]. */
        val showDuplicateConfirm: Boolean = false,
    ) : RecipeEditorState

    /**
     * El `PUT` se aceptó. [warning] no nulo = no se pudo releer (los cambios
     * están guardados). [created] dice si la receta es nueva, para poder abrirla
     * aunque el slug viejo ya no sirva.
     */
    data class Saved(
        val slug: String,
        val warning: ConnectionProblem?,
        val created: Boolean,
    ) : RecipeEditorState
}

/**
 * Formulario de receta: crear y editar. No calcula reglas de servidor: arma el
 * [RecipeDraft] y se lo pasa a [RecipeDraftValidator] y a [MealieClient].
 *
 * - Cada operación crea su cliente con [ClientAccess] y corre en un hilo de fondo.
 * - [openEdit] lee el borrador con `loadDraft` y conserva los `sourceId` para que
 *   el núcleo no pierda lo que el formulario no muestra (unidad, alimento, notas).
 * - Crear son dos pasos: `createRecipe(name)` y luego `updateRecipe` con el resto.
 *   Si el segundo falla, la receta ya existe: se avisa y se sigue editando.
 * - Si crear queda en el aire ([ApiResult.MaybeCreated]), se avisa de que puede
 *   existir y «Guardar» pide confirmación antes de repetir: nunca solo.
 * - La foto ([ImageRecoder] + `uploadImage`) solo existe en modo edición. Se
 *   prepara fuera del hilo principal, se puede cancelar y no guarda ni el Uri,
 *   ni la ruta, ni los bytes en el estado.
 * - Sin token, sin URL del servidor ni mensajes de excepción en el estado.
 */
class RecipeEditorViewModel(
    private val access: ClientAccess,
    private val recoder: ImageRecoder,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<RecipeEditorState>(RecipeEditorState.Closed)
    val state: StateFlow<RecipeEditorState> = _state.asStateFlow()

    private var generation = 0
    private var loadJob: Job? = null
    private var catalogJob: Job? = null
    private var saveJob: Job? = null
    private var photoJob: Job? = null

    /**
     * JPEG ya recodificado y sin metadatos, listo para subir o para repetir la
     * subida. Fuera del estado a propósito: no se pinta, no se copia y no acaba
     * en ningún `toString`.
     */
    private var pendingPhoto: ByteArray? = null

    /** Abre el formulario vacío para crear una receta. */
    fun openCreate() {
        val gen = start()
        val draft = RecipeDraft()
        _state.value = RecipeEditorState.Editing(
            mode = EditorMode.Create,
            form = RecipeForm.from(draft),
            baseline = draft,
            catalogs = OrganizerCatalog(loading = true),
        )
        loadCatalogs(gen)
    }

    /** Abre el formulario de una receta existente, leyendo su borrador del servidor. */
    fun openEdit(slug: String) {
        val gen = start()
        val mode = EditorMode.Edit(slug)
        _state.value = RecipeEditorState.Loading(mode)
        loadJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) { loadDraft(slug) }
            if (gen != generation) return@launch
            when (result) {
                is LoadDraftResult.Loaded -> {
                    val draft = result.draft
                    _state.value = RecipeEditorState.Editing(
                        mode = mode,
                        form = RecipeForm.from(draft),
                        baseline = draft,
                        catalogs = OrganizerCatalog(
                            tags = draft.tags,
                            categories = draft.categories,
                            loading = true,
                        ),
                    )
                    loadCatalogs(gen)
                }
                LoadDraftResult.NotFound -> _state.value = RecipeEditorState.NotFound
                is LoadDraftResult.Problem -> _state.value = RecipeEditorState.LoadFailed(mode, result.problem)
            }
        }
    }

    /** «Volver a intentar» de la carga del borrador. */
    fun onRetryLoad() {
        when (val current = _state.value) {
            is RecipeEditorState.LoadFailed -> when (val mode = current.mode) {
                EditorMode.Create -> openCreate()
                is EditorMode.Edit -> openEdit(mode.slug)
            }
            RecipeEditorState.NotFound -> close()
            else -> Unit
        }
    }

    // ------------------------------------------------------------- campos

    fun onNameChange(value: String) = updateForm { it.copy(name = value) }

    fun onDescriptionChange(value: String) = updateForm { it.copy(description = value) }

    fun onServingsChange(value: String) = updateForm { it.copy(servings = value) }

    fun onTotalTimeChange(value: String) = updateForm { it.copy(totalTime = value) }

    fun onPrepTimeChange(value: String) = updateForm { it.copy(prepTime = value) }

    fun onCookTimeChange(value: String) = updateForm { it.copy(cookTime = value) }

    fun onIngredientQuantityChange(index: Int, value: String) =
        updateIngredient(index) { it.copy(quantity = value) }

    fun onIngredientNoteChange(index: Int, value: String) =
        updateIngredient(index) { it.copy(note = value) }

    fun onAddIngredient() = updateForm { it.copy(ingredients = it.ingredients + IngredientForm()) }

    fun onRemoveIngredient(index: Int) = updateForm { form ->
        if (index in form.ingredients.indices) {
            form.copy(ingredients = form.ingredients.filterIndexed { i, _ -> i != index })
        } else {
            form
        }
    }

    fun onMoveIngredientUp(index: Int) = updateForm { form ->
        form.copy(ingredients = move(form.ingredients, index, index - 1))
    }

    fun onMoveIngredientDown(index: Int) = updateForm { form ->
        form.copy(ingredients = move(form.ingredients, index, index + 1))
    }

    fun onStepTitleChange(index: Int, value: String) = updateStep(index) { it.copy(title = value) }

    fun onStepTextChange(index: Int, value: String) = updateStep(index) { it.copy(text = value) }

    fun onAddStep() = updateForm { it.copy(steps = it.steps + StepForm()) }

    fun onRemoveStep(index: Int) = updateForm { form ->
        if (index in form.steps.indices) {
            form.copy(steps = form.steps.filterIndexed { i, _ -> i != index })
        } else {
            form
        }
    }

    fun onMoveStepUp(index: Int) = updateForm { form ->
        form.copy(steps = move(form.steps, index, index - 1))
    }

    fun onMoveStepDown(index: Int) = updateForm { form ->
        form.copy(steps = move(form.steps, index, index + 1))
    }

    fun onToggleTag(organizer: DraftOrganizer) = updateForm { it.copy(tags = toggle(it.tags, organizer)) }

    fun onToggleCategory(organizer: DraftOrganizer) =
        updateForm { it.copy(categories = toggle(it.categories, organizer)) }

    /** Reintenta cargar las etiquetas y categorías sin perder lo escrito. */
    fun onRetryCatalogs() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.catalogs.loading) return
        _state.value = current.copy(catalogs = current.catalogs.copy(loading = true, problem = null))
        loadCatalogs(generation)
    }

    private fun updateForm(change: (RecipeForm) -> RecipeForm) {
        _state.update { current ->
            if (current !is RecipeEditorState.Editing || current.saving) return@update current
            val form = change(current.form)
            current.copy(
                form = form,
                errors = emptyList(),
                serverDetails = emptyList(),
                saveProblem = null,
                rejected = false,
                dirty = form.toDraft().first != current.baseline,
            )
        }
    }

    private fun updateIngredient(index: Int, change: (IngredientForm) -> IngredientForm) {
        updateForm { form ->
            if (index !in form.ingredients.indices) {
                form
            } else {
                form.copy(
                    ingredients = form.ingredients.mapIndexed { i, row ->
                        if (i == index) change(row) else row
                    },
                )
            }
        }
    }

    private fun updateStep(index: Int, change: (StepForm) -> StepForm) {
        updateForm { form ->
            if (index !in form.steps.indices) {
                form
            } else {
                form.copy(
                    steps = form.steps.mapIndexed { i, row ->
                        if (i == index) change(row) else row
                    },
                )
            }
        }
    }

    // -------------------------------------------------------------- guardar

    /**
     * Guarda. Si antes se quedó en el aire un «puede que ya se haya creado», no
     * guarda a ciegas: pide confirmación una vez más ([confirmDuplicateSave]).
     * Tampoco guarda mientras se prepara o se sube una foto, que dejaría la
     * petición de la foto a medias al cerrar el formulario.
     */
    fun onSave() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.saving || current.photo.busy) return
        if (current.maybeCreated) {
            _state.value = current.copy(showDuplicateConfirm = true)
            return
        }
        saveNow()
    }

    /** «Guardar de todas formas»: repetir puede dejar dos recetas, pero es lo que ha pedido la persona. */
    fun confirmDuplicateSave() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.saving) return
        _state.value = current.copy(showDuplicateConfirm = false)
        saveNow()
    }

    fun dismissDuplicateSave() {
        _state.update { current ->
            if (current is RecipeEditorState.Editing) {
                current.copy(showDuplicateConfirm = false)
            } else {
                current
            }
        }
    }

    private fun saveNow() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.saving) return
        val (draft, parseErrors) = current.form.toDraft()
        val validation = RecipeDraftValidator.validate(draft)
        val validationErrors = when (validation) {
            is DraftValidation.Valid -> emptyList()
            is DraftValidation.Invalid -> validation.errors
        }
        val errors = parseErrors + validationErrors
        if (errors.isNotEmpty()) {
            _state.value = current.copy(
                errors = errors,
                failedSaveAttempts = current.failedSaveAttempts + 1,
                serverDetails = emptyList(),
                saveProblem = null,
                rejected = false,
                saving = false,
            )
            return
        }
        val clean = (validation as DraftValidation.Valid).draft
        _state.value = current.copy(
            saving = true,
            errors = emptyList(),
            serverDetails = emptyList(),
            saveProblem = null,
            rejected = false,
            maybeCreated = false,
        )
        val gen = generation
        saveJob = viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { save(clean, current.mode) }
            if (gen != generation) return@launch
            applySaveOutcome(outcome)
        }
    }

    private fun applySaveOutcome(outcome: SaveOutcome) {
        when (outcome) {
            is SaveOutcome.Saved -> {
                _state.value = RecipeEditorState.Saved(outcome.slug, outcome.warning, outcome.created)
            }
            is SaveOutcome.CreatedIncomplete -> {
                val slug = outcome.slug
                val problem = outcome.problem
                val rejected = outcome.rejected
                val errors = outcome.errors
                val details = outcome.details
                _state.update { current ->
                    if (current !is RecipeEditorState.Editing) {
                        current
                    } else {
                        current.copy(
                            mode = EditorMode.Edit(slug),
                            saving = false,
                            createdIncomplete = true,
                            saveProblem = problem,
                            rejected = rejected,
                            errors = errors,
                            serverDetails = details,
                            dirty = true,
                        )
                    }
                }
            }
            is SaveOutcome.Invalid -> {
                val errors = outcome.errors
                val details = outcome.details
                val rejected = errors.isEmpty() && details.isEmpty()
                _state.update { current ->
                    if (current !is RecipeEditorState.Editing) {
                        current
                    } else {
                        current.copy(
                            saving = false,
                            errors = errors,
                            serverDetails = details,
                            rejected = rejected,
                        )
                    }
                }
            }
            is SaveOutcome.Problem -> {
                val problem = if (outcome.rejected) null else outcome.problem
                val rejected = outcome.rejected
                _state.update { current ->
                    if (current !is RecipeEditorState.Editing) {
                        current
                    } else {
                        current.copy(
                            saving = false,
                            saveProblem = problem,
                            rejected = rejected,
                        )
                    }
                }
            }
            SaveOutcome.MaybeCreated -> {
                // El aviso es fijo y no lleva el cuerpo ni el motivo del servidor: lo que
                // importa es que no se reintente a ciegas y que se mire la lista antes.
                _state.update { current ->
                    if (current !is RecipeEditorState.Editing) {
                        current
                    } else {
                        current.copy(
                            saving = false,
                            saveProblem = null,
                            rejected = false,
                            maybeCreated = true,
                        )
                    }
                }
            }
        }
    }

    private fun save(draft: RecipeDraft, mode: EditorMode): SaveOutcome {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return SaveOutcome.Problem(networkProblem(e), false)
        } catch (e: Exception) {
            return SaveOutcome.Problem(ConnectionProblem.Unknown, false)
        }

        val (slug, created) = when (val createResult = if (mode is EditorMode.Create) createSlug(client, draft.name) else null) {
            null -> (mode as EditorMode.Edit).slug to false
            is CreateResult.Created -> createResult.slug to true
            is CreateResult.Failed -> return createResult.outcome
        }

        val updated = try {
            client.updateRecipe(slug, draft)
        } catch (e: IllegalArgumentException) {
            return SaveOutcome.Problem(ConnectionProblem.Unknown, false)
        }
        return when (updated) {
            is ApiResult.Success -> SaveOutcome.Saved(updated.value.slug, null, created)
            is ApiResult.SavedButNotReloaded -> SaveOutcome.Saved(slug, updated.problem, created)
            is ApiResult.DraftInvalid ->
                if (created) SaveOutcome.CreatedIncomplete(slug, null, false, updated.errors, emptyList())
                else SaveOutcome.Invalid(updated.errors, emptyList())
            is ApiResult.Invalid ->
                if (created) SaveOutcome.CreatedIncomplete(slug, null, true, emptyList(), updated.details)
                else SaveOutcome.Invalid(emptyList(), updated.details)
            ApiResult.Unauthorized ->
                if (created) SaveOutcome.CreatedIncomplete(slug, ConnectionProblem.Unauthorized, false, emptyList(), emptyList())
                else SaveOutcome.Problem(ConnectionProblem.Unauthorized, false)
            ApiResult.Forbidden, ApiResult.Conflict, ApiResult.NotFound,
            is ApiResult.Rejected, is ApiResult.InputRejected ->
                if (created) SaveOutcome.CreatedIncomplete(slug, null, true, emptyList(), emptyList())
                else SaveOutcome.Problem(ConnectionProblem.Unknown, true)
            is ApiResult.Failure ->
                if (created) SaveOutcome.CreatedIncomplete(slug, updated.problem, false, emptyList(), emptyList())
                else SaveOutcome.Problem(updated.problem, false)
            // `updateRecipe` es un `PUT` idempotente y el núcleo no lo devuelve; si llegara,
            // repetir no duplica nada: se trata como un fallo con «volver a intentar».
            is ApiResult.MaybeCreated ->
                if (created) SaveOutcome.CreatedIncomplete(slug, updated.problem, false, emptyList(), emptyList())
                else SaveOutcome.Problem(updated.problem, false)
        }
    }

    private fun createSlug(client: MealieClient, name: String): CreateResult =
        when (val result = client.createRecipe(name)) {
            is ApiResult.Success -> CreateResult.Created(result.value)
            is ApiResult.DraftInvalid -> CreateResult.Failed(SaveOutcome.Invalid(result.errors, emptyList()))
            is ApiResult.Invalid -> CreateResult.Failed(SaveOutcome.Invalid(emptyList(), result.details))
            ApiResult.Unauthorized ->
                CreateResult.Failed(SaveOutcome.Problem(ConnectionProblem.Unauthorized, false))
            ApiResult.Forbidden, ApiResult.Conflict, ApiResult.NotFound,
            is ApiResult.Rejected, is ApiResult.InputRejected, is ApiResult.SavedButNotReloaded ->
                CreateResult.Failed(SaveOutcome.Problem(ConnectionProblem.Unknown, true))
            is ApiResult.Failure ->
                CreateResult.Failed(SaveOutcome.Problem(result.problem, false))
            // El servidor no ha dicho que no, pero tampoco se sabe si se creó: repetir a
            // ciegas dejaría dos recetas. Se avisa y solo se repite con confirmación.
            is ApiResult.MaybeCreated -> CreateResult.Failed(SaveOutcome.MaybeCreated)
        }

    // ------------------------------------------------------------------- foto

    /**
     * La galería o la cámara han dejado una foto: se recodifica (sin EXIF, reducida)
     * y se sube sola. Solo en modo edición, porque hace falta el `slug` de una receta
     * que ya existe. El temporal de la cámara se borra siempre, salga bien o mal.
     */
    fun onPhotoPicked(uri: Uri, source: PhotoSource) {
        val current = _state.value as? RecipeEditorState.Editing
        val mode = current?.mode as? EditorMode.Edit
        if (current == null || mode == null || current.saving || current.photo.busy) {
            // No se va a usar: la foto de la cámara (con ubicación) no se queda en la caché.
            if (source == PhotoSource.Camera) recoder.discard(uri)
            return
        }
        photoJob?.cancel()
        pendingPhoto = null
        _state.value = current.copy(
            photo = current.photo.copy(
                preview = null,
                preparing = true,
                uploading = false,
                problem = null,
                done = false,
            ),
        )
        val gen = generation
        photoJob = viewModelScope.launch {
            // Recodifica en un hilo de fondo y comprueba entre pasos que sigue viva.
            val recoded = recoder.recode(uri, discardSource = source == PhotoSource.Camera)
            if (gen != generation) return@launch
            when (recoded) {
                is ImageRecodeResult.Ok -> {
                    val bytes = recoded.bytes
                    val preview = withContext(ioDispatcher) { previewOf(bytes) }
                    if (gen != generation) return@launch
                    pendingPhoto = bytes
                    _state.update { latest ->
                        if (latest is RecipeEditorState.Editing) {
                            latest.copy(photo = latest.photo.copy(preparing = false, preview = preview))
                        } else {
                            latest
                        }
                    }
                    uploadPending(mode.slug, gen)
                }
                ImageRecodeResult.TooLarge -> setPhotoProblem(PhotoProblem.TooLarge)
                ImageRecodeResult.NotAnImage -> setPhotoProblem(PhotoProblem.NotAnImage)
                ImageRecodeResult.Failed -> setPhotoProblem(PhotoProblem.NotAnImage)
            }
        }
    }

    /** El selector no ha dado foto (no hay cámara, la galería no abre). */
    fun onPhotoProblem(problem: PhotoProblem) {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.photo.busy) return
        _state.value = current.copy(photo = current.photo.copy(problem = problem, done = false))
    }

    /**
     * «Volver a intentar» una subida que falló. Subir la foto es un `PUT`: repetirlo
     * no crea nada, así que se reenvían los mismos bytes sin volver a recodificar.
     */
    fun onRetryPhoto() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        val mode = current.mode as? EditorMode.Edit ?: return
        if (current.saving || current.photo.busy) return
        val retryable = current.photo.problem.let { it is PhotoProblem.Network || it == PhotoProblem.Unknown }
        if (!retryable) return
        val bytes = pendingPhoto ?: return
        val gen = generation
        photoJob?.cancel()
        _state.value = current.copy(
            photo = current.photo.copy(problem = null, done = false, uploading = true),
        )
        photoJob = viewModelScope.launch { submitPhoto(mode.slug, bytes, gen) }
    }

    private suspend fun uploadPending(slug: String, gen: Int) {
        val bytes = pendingPhoto ?: return
        if (gen != generation) return
        _state.update { latest ->
            if (latest is RecipeEditorState.Editing) {
                latest.copy(photo = latest.photo.copy(preparing = false, uploading = true))
            } else {
                latest
            }
        }
        submitPhoto(slug, bytes, gen)
    }

    private suspend fun submitPhoto(slug: String, bytes: ByteArray, gen: Int) {
        val result = withContext(ioDispatcher) { upload(slug, bytes) }
        if (gen != generation) return
        applyPhotoResult(result)
    }

    private fun applyPhotoResult(result: PhotoUploadResult) {
        if (result is PhotoUploadResult.Done) pendingPhoto = null
        _state.update { latest ->
            if (latest !is RecipeEditorState.Editing) {
                latest
            } else {
                val photo = latest.photo.copy(preparing = false, uploading = false)
                when (result) {
                    PhotoUploadResult.Done -> latest.copy(
                        photo = photo.copy(
                            problem = null,
                            done = true,
                            uploadedCount = photo.uploadedCount + 1,
                        ),
                    )
                    is PhotoUploadResult.Failed -> {
                        // Solo se guardan los bytes si se puede reintentar; si no, se sueltan ya.
                        val problem = result.problem
                        if (!(problem is PhotoProblem.Network || problem == PhotoProblem.Unknown)) {
                            pendingPhoto = null
                        }
                        latest.copy(photo = photo.copy(problem = problem, done = false))
                    }
                }
            }
        }
    }

    /** Fallo antes de subir: no hay bytes que reintentar, toca elegir otra foto. */
    private fun setPhotoProblem(problem: PhotoProblem) {
        pendingPhoto = null
        _state.update { latest ->
            if (latest is RecipeEditorState.Editing) {
                latest.copy(
                    photo = latest.photo.copy(
                        preparing = false,
                        uploading = false,
                        problem = problem,
                        done = false,
                    ),
                )
            } else {
                latest
            }
        }
    }

    private fun upload(slug: String, bytes: ByteArray): PhotoUploadResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return PhotoUploadResult.Failed(PhotoProblem.Network(networkProblem(e)))
        } catch (e: Exception) {
            return PhotoUploadResult.Failed(PhotoProblem.Unknown)
        }
        // `ImageRecoder.EXTENSION` es `jpeg`: es el único nombre que acepta el núcleo
        // para un JPEG (compara la extensión con el tipo real de los bytes).
        val result = try {
            client.uploadImage(slug, bytes, ImageRecoder.EXTENSION)
        } catch (e: IllegalArgumentException) {
            // Slug fuera de la lista blanca: para el servidor esa receta no existe.
            return PhotoUploadResult.Failed(PhotoProblem.NotFound)
        }
        return when (result) {
            is ApiResult.Success -> PhotoUploadResult.Done
            is ApiResult.InputRejected -> PhotoUploadResult.Failed(
                when (result.reason) {
                    InputProblem.ImageHasMetadata -> PhotoProblem.HasMetadata
                    InputProblem.ImageTooLarge -> PhotoProblem.TooLarge
                    InputProblem.InvalidImage -> PhotoProblem.NotAnImage
                    InputProblem.InvalidUrl, InputProblem.UnsupportedImageType -> PhotoProblem.Rejected
                },
            )
            ApiResult.Unauthorized ->
                PhotoUploadResult.Failed(PhotoProblem.Network(ConnectionProblem.Unauthorized))
            ApiResult.Forbidden -> PhotoUploadResult.Failed(PhotoProblem.Rejected)
            ApiResult.NotFound -> PhotoUploadResult.Failed(PhotoProblem.NotFound)
            ApiResult.Conflict, is ApiResult.Invalid, is ApiResult.Rejected,
            is ApiResult.DraftInvalid -> PhotoUploadResult.Failed(PhotoProblem.Rejected)
            is ApiResult.SavedButNotReloaded ->
                PhotoUploadResult.Failed(PhotoProblem.Network(result.problem))
            // No lo devuelve subir una foto, pero si llegara: es un `PUT` y repetirlo no
            // duplica nada, así que se ofrece «volver a intentar».
            is ApiResult.MaybeCreated ->
                PhotoUploadResult.Failed(PhotoProblem.Network(result.problem))
            is ApiResult.Failure ->
                PhotoUploadResult.Failed(PhotoProblem.Network(result.problem))
        }
    }

    /**
     * Vista previa pequeña del JPEG ya recodificado. Solo para enseñarlo en el
     * formulario: los bytes que se suben no se tocan. Null si no se puede decodificar
     * (se sube igual, sin vista previa).
     */
    private fun previewOf(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) {
            null
        } else {
            var sample = 1
            while (longest / (sample * 2) >= PREVIEW_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    } catch (ignored: OutOfMemoryError) {
        null
    } catch (ignored: RuntimeException) {
        null
    }

    // -------------------------------------------------------------- catálogos

    private fun loadCatalogs(gen: Int) {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) { loadOrganizers() }
            if (gen != generation) return@launch
            when (result) {
                is CatalogsResult.Loaded -> {
                    val tags = result.tags
                    val categories = result.categories
                    _state.update { current ->
                        if (current !is RecipeEditorState.Editing) {
                            current
                        } else {
                            current.copy(
                                catalogs = OrganizerCatalog(
                                    tags = mergeChoices(tags, current.form.tags),
                                    categories = mergeChoices(categories, current.form.categories),
                                    loading = false,
                                    problem = null,
                                ),
                            )
                        }
                    }
                }
                is CatalogsResult.Problem -> {
                    val problem = result.problem
                    _state.update { current ->
                        if (current !is RecipeEditorState.Editing) {
                            current
                        } else {
                            current.copy(
                                catalogs = OrganizerCatalog(
                                    tags = mergeChoices(emptyList(), current.form.tags),
                                    categories = mergeChoices(emptyList(), current.form.categories),
                                    loading = false,
                                    problem = problem,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadOrganizers(): CatalogsResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return CatalogsResult.Problem(networkProblem(e))
        } catch (e: Exception) {
            return CatalogsResult.Problem(ConnectionProblem.Unknown)
        }
        val tags = client.listTags()
        if (tags !is ApiResult.Success) return CatalogsResult.Problem(problemOf(tags))
        val categories = client.listCategories()
        if (categories !is ApiResult.Success) return CatalogsResult.Problem(problemOf(categories))
        return CatalogsResult.Loaded(
            tags = tags.value.items.map { it.toDraft() },
            categories = categories.value.items.map { it.toDraft() },
        )
    }

    // ------------------------------------------------------------- navegación

    /** Atrás: si hay cambios sin guardar, pide confirmación; si no, cierra. */
    fun requestClose() {
        val current = _state.value
        if (current is RecipeEditorState.Editing) {
            if (current.saving) return
            if (current.dirty) {
                _state.value = current.copy(showDiscardConfirm = true)
                return
            }
        }
        close()
    }

    fun confirmDiscard() {
        val current = _state.value as? RecipeEditorState.Editing
        if (current?.saving == true) return
        close()
    }

    fun dismissDiscard() {
        _state.update { current ->
            if (current is RecipeEditorState.Editing) current.copy(showDiscardConfirm = false) else current
        }
    }

    /** Cierra el formulario y cancela cualquier carga o guardado en marcha. */
    fun close() {
        start()
        _state.value = RecipeEditorState.Closed
    }

    /** Al cambiar de sesión: nada de lo cargado vale. */
    fun reset() = close()

    private fun start(): Int {
        loadJob?.cancel()
        catalogJob?.cancel()
        saveJob?.cancel()
        photoJob?.cancel()
        loadJob = null
        catalogJob = null
        saveJob = null
        photoJob = null
        pendingPhoto = null
        return ++generation
    }

    // -------------------------------------------------------------- privados

    private fun loadDraft(slug: String): LoadDraftResult {
        val client = try {
            access.newClient()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            return LoadDraftResult.Problem(networkProblem(e))
        } catch (e: Exception) {
            return LoadDraftResult.Problem(ConnectionProblem.Unknown)
        }
        val result = try {
            client.loadDraft(slug)
        } catch (e: IllegalArgumentException) {
            // Slug fuera de la lista blanca: no se envió nada y no hay receta que abrir.
            return LoadDraftResult.NotFound
        }
        return when (result) {
            is ApiResult.Success -> LoadDraftResult.Loaded(result.value)
            ApiResult.NotFound -> LoadDraftResult.NotFound
            ApiResult.Unauthorized, ApiResult.Forbidden ->
                LoadDraftResult.Problem(ConnectionProblem.Unauthorized)
            else -> LoadDraftResult.Problem(problemOf(result))
        }
    }

    private fun networkProblem(error: NetworkError): ConnectionProblem = error.toConnectionProblem()

    private fun problemOf(result: ApiResult<*>): ConnectionProblem = when (result) {
        is ApiResult.Failure -> result.problem
        // Ninguna de estas operaciones devuelve «quizá creada», pero si llegara, el motivo
        // real es el que trae; no se inventa uno.
        is ApiResult.MaybeCreated -> result.problem
        ApiResult.Unauthorized, ApiResult.Forbidden -> ConnectionProblem.Unauthorized
        else -> ConnectionProblem.Unknown
    }

    private sealed interface LoadDraftResult {
        data class Loaded(val draft: RecipeDraft) : LoadDraftResult
        data object NotFound : LoadDraftResult
        data class Problem(val problem: ConnectionProblem) : LoadDraftResult
    }

    private sealed interface CatalogsResult {
        data class Loaded(val tags: List<DraftOrganizer>, val categories: List<DraftOrganizer>) : CatalogsResult
        data class Problem(val problem: ConnectionProblem) : CatalogsResult
    }

    private sealed interface CreateResult {
        data class Created(val slug: String) : CreateResult
        data class Failed(val outcome: SaveOutcome) : CreateResult
    }

    private sealed interface SaveOutcome {
        data class Saved(val slug: String, val warning: ConnectionProblem?, val created: Boolean) : SaveOutcome
        data class CreatedIncomplete(
            val slug: String,
            val problem: ConnectionProblem?,
            val rejected: Boolean,
            val errors: List<DraftError>,
            val details: List<ValidationDetail>,
        ) : SaveOutcome
        data class Invalid(val errors: List<DraftError>, val details: List<ValidationDetail>) : SaveOutcome
        data class Problem(val problem: ConnectionProblem, val rejected: Boolean) : SaveOutcome

        /**
         * `createRecipe` no ha dicho que no, pero no se sabe si llegó a crear la
         * receta. Nunca se reintenta solo: se avisa y se pide confirmación.
         */
        data object MaybeCreated : SaveOutcome
    }

    private sealed interface PhotoUploadResult {
        data object Done : PhotoUploadResult
        data class Failed(val problem: PhotoProblem) : PhotoUploadResult
    }

    companion object {
        /** Lado máximo de la vista previa que se guarda en el estado, en píxeles. */
        private const val PREVIEW_SIDE = 480

        fun factory(access: ClientAccess, recoder: ImageRecoder): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeEditorViewModel(access, recoder) as T
            }
    }
}

/** Misma etiqueta o categoría: por id, por slug o, si no hay, por nombre sin distinguir mayúsculas. */
internal fun sameOrganizer(a: DraftOrganizer, b: DraftOrganizer): Boolean {
    if (a.id != null && b.id != null) return a.id == b.id
    if (a.slug != null && b.slug != null) return a.slug == b.slug
    return a.name.trim().lowercase(Locale.ROOT) == b.name.trim().lowercase(Locale.ROOT)
}

private fun mergeChoices(available: List<DraftOrganizer>, selected: List<DraftOrganizer>): List<DraftOrganizer> {
    val merged = ArrayList<DraftOrganizer>(available.size + selected.size)
    merged.addAll(available)
    for (item in selected) {
        if (merged.none { sameOrganizer(it, item) }) merged.add(item)
    }
    return merged
}

private fun toggle(list: List<DraftOrganizer>, item: DraftOrganizer): List<DraftOrganizer> =
    if (list.any { sameOrganizer(it, item) }) {
        list.filterNot { sameOrganizer(it, item) }
    } else {
        list + item
    }

private fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    val copy = list.toMutableList()
    val item = copy.removeAt(from)
    copy.add(to, item)
    return copy
}

private fun parseNumber(
    text: String,
    field: DraftField,
    index: Int?,
    errors: MutableList<DraftError>,
): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val value = QuantityParser.parse(trimmed)
    if (value == null) {
        errors += DraftError(field, DraftErrorKind.Invalid, index)
        return null
    }
    return value
}

private fun formatNumber(value: Double): String =
    if (value == Math.floor(value) && value.isFinite() && Math.abs(value) < 1e15) {
        value.toLong().toString()
    } else {
        value.toString().replace('.', ',')
    }
