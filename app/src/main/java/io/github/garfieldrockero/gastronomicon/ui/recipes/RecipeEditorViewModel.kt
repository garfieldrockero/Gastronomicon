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
import io.github.garfieldrockero.gastronomicon.core.offline.CachedDraft
import io.github.garfieldrockero.gastronomicon.core.offline.DraftTarget
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineCopyDate
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineRead
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineWrite
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
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import io.github.garfieldrockero.gastronomicon.platform.ClientAccess
import io.github.garfieldrockero.gastronomicon.platform.ImageRecodeResult
import io.github.garfieldrockero.gastronomicon.platform.ImageRecoder
import io.github.garfieldrockero.gastronomicon.platform.OfflineRecipes
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

/**
 * Reparte los ids de interfaz del formulario ([IngredientForm.uiId] y
 * [StepForm.uiId]). Solo sirve para que Compose sepa qué tarjeta es cuál al subir,
 * bajar o quitarla; nunca entra en el borrador ni en el JSON.
 */
internal class UiIdSource {
    private var last = 0L

    fun next(): Long {
        last += 1
        return last
    }
}

/** Una línea de ingredientes tal como se edita: cantidad y nota; unidad y alimento solo se leen. */
data class IngredientForm(
    val quantity: String = "",
    val unit: String = "",
    val food: String = "",
    val note: String = "",
    val title: String = "",
    val sourceId: String? = null,
    /**
     * Id estable solo de interfaz (`key` de Compose): la tarjeta conserva su foco, su
     * scroll y su sitio en el orden de TalkBack al moverse. No viaja al borrador ni al
     * JSON ([RecipeForm.toDraft] lo ignora) y por eso tampoco cuenta para `dirty`.
     */
    val uiId: Long = 0L,
)

/** Un paso tal como se edita. */
data class StepForm(
    val title: String = "",
    val text: String = "",
    val sourceId: String? = null,
    /** Igual que [IngredientForm.uiId]: solo para la interfaz, fuera del borrador. */
    val uiId: Long = 0L,
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
        // Los `uiId` se quedan aquí a propósito: el borrador y `dirty` no los ven.
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
        /**
         * [uiIds] los pone el ViewModel; sin él (previews y pruebas) se numera aquí,
         * único dentro del formulario y sin ningún significado más.
         */
        internal fun from(draft: RecipeDraft, uiIds: UiIdSource = UiIdSource()): RecipeForm = RecipeForm(
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
                    uiId = uiIds.next(),
                )
            },
            steps = draft.steps.map { StepForm(it.title, it.text, it.sourceId, uiId = uiIds.next()) },
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
    /**
     * El servidor tiene más de las que caben en una página: el formulario lo dice en
     * vez de callarse las que no ha podido traer. Lo calcula el núcleo (`Page.hasNext`).
     */
    val tagsTruncated: Boolean = false,
    val categoriesTruncated: Boolean = false,
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

/**
 * Cómo ha ido la consulta del borrador guardado al abrir el formulario.
 *
 * Solo [Miss] —no había ninguno— autoriza a borrar al salir sin cambios: lo que la
 * persona no ha llegado a ver no se tira.
 */
enum class DraftLookup {
    /** Todavía no se sabe (consulta en curso): no se borra nada al salir. */
    Pending,

    /** No había borrador guardado para este formulario. */
    Miss,

    /** Hay uno guardado y se ha ofrecido: hasta que se decida, no se toca. */
    Hit,

    /** No se pudo consultar (Keystore ocupado, almacén roto): no se toca. */
    Failed,
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
        /**
         * Borrador guardado de esta misma receta que aún no se ha aplicado, con
         * su fecha ya en texto. Se ofrece «Recuperar» / «Descartar»: **nunca** se
         * aplica solo. Se borra al recuperarlo, al descartarlo o al guardar.
         * La oferta **no** desaparece al teclear: sigue ahí hasta que la persona
         * decida. Si sale del formulario con cambios nuevos, lo escrito se guarda
         * y sustituye a este borrador (lo nuevo gana).
         */
        val recoveredDraft: RecipeDraft? = null,
        val recoveredAtText: String = "",
        /**
         * Cómo fue la consulta del borrador al abrir: solo con
         * [DraftLookup.Miss] se borra el borrador guardado al salir sin cambios.
         * Lo que no se ha llegado a ofrecer (consulta en curso, fallida u oferta
         * viva) no se toca nunca.
         */
        val draftLookup: DraftLookup = DraftLookup.Pending,
        /** «Recuperar» con algo ya escrito en el formulario: pide confirmación. */
        val showDraftConfirm: Boolean = false,
        /** «Descartar» el borrador ofrecido: borra de verdad, así que también pregunta. */
        val showDraftDiscardConfirm: Boolean = false,
        /** El borrador ofrecido no pasa el validador: se dice en la propia caja. */
        val draftInvalid: Boolean = false,
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
 *
 * Fase 8 (borradores): lo escrito se guarda en [offline] al salir del
 * formulario y al pasar a segundo plano (nunca con retardo mientras se escribe;
 * el almacén es bloqueante). Al abrir el formulario de esa receta, si hay
 * borrador, se ofrece recuperarlo o descartarlo: **nunca** se aplica solo. Se
 * borra al guardar bien, al descartarlo, al salir sin cambios **solo si la
 * consulta al abrir dijo que no había ninguno** y al cerrar la sesión (eso último
 * lo hace el vaciado del almacén). Con una oferta viva, salir con cambios nuevos
 * guarda lo escrito y sustituye al borrador; sin cambios, no se toca. El borrador
 * pasa por [RecipeDraftValidator] antes de volver al formulario.
 */
class RecipeEditorViewModel(
    private val access: ClientAccess,
    private val recoder: ImageRecoder,
    private val offline: OfflineRecipes? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<RecipeEditorState>(RecipeEditorState.Closed)
    val state: StateFlow<RecipeEditorState> = _state.asStateFlow()

    private var generation = 0
    private var loadJob: Job? = null
    private var catalogJob: Job? = null
    private var saveJob: Job? = null
    private var photoJob: Job? = null
    private var draftJob: Job? = null

    /**
     * Cambia solo al reiniciar por otra sesión ([reset]). Los guardados de
     * borrador que salen al cerrar el formulario comprueban que siga igual: si
     * la sesión cambió por el camino, no escriben nada de la anterior.
     */
    @Volatile
    private var sessionEpoch = 0L

    /**
     * Ids de interfaz del formulario. No se reinicia al abrir ni al cerrar: así dos
     * filas de dos formularios seguidos nunca comparten id.
     */
    private val uiIds = UiIdSource()

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
            form = RecipeForm.from(draft, uiIds),
            baseline = draft,
            catalogs = OrganizerCatalog(loading = true),
        )
        loadCatalogs(gen)
        loadRecoveredDraft(gen, DraftTarget.New)
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
                        form = RecipeForm.from(draft, uiIds),
                        baseline = draft,
                        catalogs = OrganizerCatalog(
                            tags = draft.tags,
                            categories = draft.categories,
                            loading = true,
                        ),
                    )
                    loadCatalogs(gen)
                    loadRecoveredDraft(gen, targetOf(mode) ?: return@launch)
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

    // ------------------------------------------------- borradores (fase 8)

    /**
     * Mira si hay un borrador guardado de este formulario y lo ofrece. No lo
     * aplica: eso es [onRecoverDraft], cuando la persona lo pide.
     *
     * Apunta en `draftLookup` cómo ha ido: solo un [DraftLookup.Miss] permite
     * borrar al salir sin cambios. Si el almacén no responde o el ámbito no se
     * conoce, queda [DraftLookup.Failed] y no se toca nada.
     */
    private fun loadRecoveredDraft(gen: Int, target: DraftTarget) {
        val store = offline ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { lookupDraft(store, target) }
            if (gen != generation) return@launch
            _state.update { current ->
                if (current !is RecipeEditorState.Editing || current.recoveredDraft != null) {
                    current
                } else {
                    when (outcome) {
                        is DraftLookupOutcome.Found -> current.copy(
                            recoveredDraft = outcome.draft.draft,
                            recoveredAtText = OfflineCopyDate.format(outcome.draft.savedAtMillis),
                            draftLookup = DraftLookup.Hit,
                        )
                        DraftLookupOutcome.None -> current.copy(draftLookup = DraftLookup.Miss)
                        DraftLookupOutcome.Failed -> current.copy(draftLookup = DraftLookup.Failed)
                    }
                }
            }
        }
    }

    /** Lo que ha dicho el almacén al mirar si había borrador. Sin textos ni fechas. */
    private sealed interface DraftLookupOutcome {
        class Found(val draft: CachedDraft) : DraftLookupOutcome
        data object None : DraftLookupOutcome
        data object Failed : DraftLookupOutcome
    }

    /** Consulta bloqueante al almacén (hilo de fondo): no lanza por fallos normales. */
    private fun lookupDraft(store: OfflineRecipes, target: DraftTarget): DraftLookupOutcome {
        val scope = store.scope() ?: return DraftLookupOutcome.Failed
        return try {
            when (val read = store.loadDraft(scope, target)) {
                is OfflineRead.Hit -> DraftLookupOutcome.Found(read.value)
                is OfflineRead.Miss -> DraftLookupOutcome.None
                is OfflineRead.Failed -> DraftLookupOutcome.Failed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DraftLookupOutcome.Failed
        }
    }

    /**
     * Pasa el borrador ofrecido al formulario, **solo** si la persona lo pide.
     * Antes pasa por el validador del núcleo, igual que si se fuera a guardar:
     * lo que se recupere tiene que ser tan válido como lo escrito a mano. Los
     * errores se enseñan y el formulario no se toca.
     *
     * Si el formulario ya tiene algo escrito (en modo edición, la propia receta),
     * recuperar lo sustituiría: primero se pregunta ([showDraftConfirm]).
     */
    fun onRecoverDraft() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.recoveredDraft == null || current.saving) return
        if (current.form.hasContent()) {
            _state.value = current.copy(showDraftConfirm = true)
            return
        }
        applyRecoveredDraft(current)
    }

    /** «Sustituir» del aviso de que se va a pisar lo escrito. */
    fun confirmRecoverDraft() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.recoveredDraft == null || current.saving) return
        if (!current.showDraftConfirm) return
        applyRecoveredDraft(current.copy(showDraftConfirm = false))
    }

    fun dismissRecoverDraft() {
        _state.update { current ->
            if (current is RecipeEditorState.Editing && current.showDraftConfirm) {
                current.copy(showDraftConfirm = false)
            } else {
                current
            }
        }
    }

    private fun applyRecoveredDraft(current: RecipeEditorState.Editing) {
        val saved = current.recoveredDraft ?: return
        when (val validation = RecipeDraftValidator.validate(saved)) {
            is DraftValidation.Valid -> {
                val form = RecipeForm.from(validation.draft, uiIds)
                _state.value = current.copy(
                    form = form,
                    errors = emptyList(),
                    serverDetails = emptyList(),
                    saveProblem = null,
                    rejected = false,
                    recoveredDraft = null,
                    recoveredAtText = "",
                    draftInvalid = false,
                    dirty = form.toDraft().first != current.baseline,
                )
            }
            is DraftValidation.Invalid -> _state.value = current.copy(
                errors = validation.errors,
                failedSaveAttempts = current.failedSaveAttempts + 1,
                serverDetails = emptyList(),
                saveProblem = null,
                rejected = false,
                draftInvalid = true,
            )
        }
    }

    /** «Descartar» el borrador ofrecido: se pide confirmación antes de borrarlo de verdad. */
    fun onDiscardRecoveredDraft() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.recoveredDraft == null) return
        _state.value = current.copy(showDraftDiscardConfirm = true)
    }

    fun confirmDiscardRecoveredDraft() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.recoveredDraft == null) return
        _state.value = current.copy(
            recoveredDraft = null,
            recoveredAtText = "",
            showDraftDiscardConfirm = false,
            draftInvalid = false,
            // Se acaba de borrar: al salir sin cambios ya no hay nada que conservar.
            draftLookup = DraftLookup.Miss,
        )
        val target = targetOf(current.mode) ?: return
        deleteDraft(target)
    }

    fun dismissDiscardRecoveredDraft() {
        _state.update { current ->
            if (current is RecipeEditorState.Editing && current.showDraftDiscardConfirm) {
                current.copy(showDraftDiscardConfirm = false)
            } else {
                current
            }
        }
    }

    /**
     * Lo escrito se guarda como borrador: la pantalla pasa a segundo plano
     * (`onStop`) o la persona sale del formulario.
     *
     * Sin cambios nuevos no hay nada que ofrecer al volver: se quita el borrador
     * **solo** si la consulta al abrir dijo que no había ninguno ([DraftLookup.Miss]).
     * Lo que no se ha llegado a ofrecer (consulta en curso, fallida o con una oferta
     * viva sin decidir) no se toca: perder un borrador que la persona no ha visto
     * sería peor que dejar uno de más.
     *
     * Con cambios nuevos **lo nuevo gana**: se guarda lo escrito y sustituye al
     * borrador que estuviera ofrecido (el diálogo de salida lo dice). Así «Salir y
     * guardar» y el guardado en segundo plano no mienten.
     */
    fun saveDraftNow() {
        val current = _state.value as? RecipeEditorState.Editing ?: return
        if (current.saving) return
        val target = targetOf(current.mode) ?: return
        val store = offline ?: return
        val bornEpoch = sessionEpoch
        val gen = generation
        if (!current.dirty) {
            if (current.draftLookup == DraftLookup.Miss) deleteDraft(target)
            return
        }
        val draft = current.form.toDraft().first
        // Había una oferta viva: al escribir encima, la oferta deja de existir en el
        // almacén y no se puede seguir enseñando.
        val replacesOffer = current.recoveredDraft != null
        viewModelScope.launch(ioDispatcher) {
            if (bornEpoch != sessionEpoch) return@launch
            val scope = store.scope() ?: return@launch
            val written = try {
                store.saveDraft(scope, target, draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin borrador se sigue pudiendo escribir; no se avisa de esto.
                return@launch
            }
            if (!replacesOffer || written != OfflineWrite.Saved) return@launch
            if (gen != generation) return@launch
            _state.update { latest ->
                if (latest is RecipeEditorState.Editing && latest.recoveredDraft != null) {
                    latest.copy(recoveredDraft = null, recoveredAtText = "", draftInvalid = false)
                } else {
                    latest
                }
            }
        }
    }

    /** Borra el borrador guardado de [target] en segundo plano (guardar bien o descartar). */
    private fun deleteDraft(target: DraftTarget) {
        val store = offline ?: return
        val bornEpoch = sessionEpoch
        viewModelScope.launch(ioDispatcher) {
            if (bornEpoch != sessionEpoch) return@launch
            val scope = store.scope() ?: return@launch
            try {
                store.deleteDraft(scope, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Queda un borrador que ya no vale; caduca solo con los días.
            }
        }
    }

    private fun targetOf(mode: EditorMode): DraftTarget? = when (mode) {
        EditorMode.Create -> DraftTarget.New
        is EditorMode.Edit -> if (RecipeSlug.isValid(mode.slug)) DraftTarget.Recipe(mode.slug) else null
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

    fun onAddIngredient() {
        // El id se pide fuera del cambio de estado: si el flujo reintenta la actualización,
        // la fila es la misma en todos los intentos.
        val row = IngredientForm(uiId = uiIds.next())
        updateForm { it.copy(ingredients = it.ingredients + row) }
    }

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

    fun onAddStep() {
        val row = StepForm(uiId = uiIds.next())
        updateForm { it.copy(steps = it.steps + row) }
    }

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
                // La oferta del borrador **no** se quita al teclear: sigue hasta que la
                // persona pulse «Recuperar» o «Descartar». Si sale con estos cambios, se
                // guardan y sustituyen al borrador ofrecido (lo nuevo gana).
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
        val target = targetOf(current.mode)
        saveJob = viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { save(clean, current.mode) }
            if (gen != generation) return@launch
            when (outcome) {
                // Guardado bien: el borrador ya no hace falta.
                is SaveOutcome.Saved -> if (target != null) deleteDraft(target)
                // La receta ya existe con su nombre: el borrador de «nueva» sobra. Lo que
                // quede por escribir se guardará como borrador de la receta al salir.
                is SaveOutcome.CreatedIncomplete ->
                    if (current.mode == EditorMode.Create) deleteDraft(DraftTarget.New)
                else -> Unit
            }
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
                    val tagsTruncated = result.tagsTruncated
                    val categoriesTruncated = result.categoriesTruncated
                    _state.update { current ->
                        if (current !is RecipeEditorState.Editing) {
                            current
                        } else {
                            current.copy(
                                catalogs = OrganizerCatalog(
                                    tags = mergeChoices(tags, current.form.tags),
                                    categories = mergeChoices(categories, current.form.categories),
                                    tagsTruncated = tagsTruncated,
                                    categoriesTruncated = categoriesTruncated,
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
            // `hasNext` lo decide el núcleo con el total del servidor: si hay más de una
            // página, el formulario avisa de que solo se ven las primeras.
            tagsTruncated = tags.value.hasNext,
            categoriesTruncated = categories.value.hasNext,
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
        // Al salir se apunta lo escrito como borrador (si hay algo que apuntar).
        saveDraftNow()
        close()
    }

    /**
     * «Descartar» del diálogo de salida: lo escrito **no** se pierde, se guarda
     * como borrador y se ofrece al volver a abrir el formulario. Por eso se
     * guarda antes de cerrar.
     */
    fun confirmDiscard() {
        val current = _state.value as? RecipeEditorState.Editing
        if (current?.saving == true) return
        saveDraftNow()
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

    /** Al cambiar de sesión: nada de lo cargado vale, ni los borradores en curso. */
    fun reset() {
        sessionEpoch++
        close()
    }

    private fun start(): Int {
        loadJob?.cancel()
        catalogJob?.cancel()
        saveJob?.cancel()
        photoJob?.cancel()
        draftJob?.cancel()
        loadJob = null
        catalogJob = null
        saveJob = null
        photoJob = null
        draftJob = null
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
        data class Loaded(
            val tags: List<DraftOrganizer>,
            val categories: List<DraftOrganizer>,
            /** Hay más de una página en el servidor: el formulario avisa de que faltan. */
            val tagsTruncated: Boolean,
            val categoriesTruncated: Boolean,
        ) : CatalogsResult

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

        fun factory(
            access: ClientAccess,
            recoder: ImageRecoder,
            offline: OfflineRecipes? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeEditorViewModel(access, recoder, offline) as T
            }
    }
}

/** Misma etiqueta o categoría: por id, por slug o, si no hay, por nombre sin distinguir mayúsculas. */
internal fun sameOrganizer(a: DraftOrganizer, b: DraftOrganizer): Boolean {
    if (a.id != null && b.id != null) return a.id == b.id
    if (a.slug != null && b.slug != null) return a.slug == b.slug
    return a.name.trim().lowercase(Locale.ROOT) == b.name.trim().lowercase(Locale.ROOT)
}

/**
 * ¿Hay algo escrito que un borrador recuperado vaya a sustituir? En modo edición
 * el formulario ya trae la receta del servidor, así que recuperar siempre pide
 * confirmación (también sustituye lo que se haya cambiado).
 *
 * Las filas en blanco no cuentan: «Añadir ingrediente» sin escribir nada no es
 * contenido, y preguntar por ellas solo estorbaría.
 */
private fun RecipeForm.hasContent(): Boolean =
    name.isNotBlank() || description.isNotBlank() || servings.isNotBlank() ||
        totalTime.isNotBlank() || prepTime.isNotBlank() || cookTime.isNotBlank() ||
        performTime.isNotBlank() ||
        ingredients.any { it.hasContent() } || steps.any { it.hasContent() } ||
        notes.isNotEmpty() || tags.isNotEmpty() || categories.isNotEmpty()

/** Una fila de ingrediente con algo escrito (la unidad y el alimento no se editan). */
private fun IngredientForm.hasContent(): Boolean =
    quantity.isNotBlank() || note.isNotBlank() || title.isNotBlank()

/** Un paso con algo escrito. */
private fun StepForm.hasContent(): Boolean = title.isNotBlank() || text.isNotBlank()

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
