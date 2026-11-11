package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftErrorKind
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftOrganizer
import io.github.garfieldrockero.gastronomicon.platform.PhotoPickResult
import io.github.garfieldrockero.gastronomicon.platform.PhotoSource
import io.github.garfieldrockero.gastronomicon.platform.rememberPhotoPicker
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.GastronomiconSnackbarHost
import io.github.garfieldrockero.gastronomicon.ui.components.GastronomiconTopBar
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBlock
import io.github.garfieldrockero.gastronomicon.ui.components.SectionTitle
import io.github.garfieldrockero.gastronomicon.ui.components.SnackbarNotice
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.fieldError
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/** Lo que la pantalla avisa al ViewModel. Todo opcional para las previews. */
class RecipeEditorActions(
    val onRequestClose: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onDescriptionChange: (String) -> Unit = {},
    val onServingsChange: (String) -> Unit = {},
    val onTotalTimeChange: (String) -> Unit = {},
    val onPrepTimeChange: (String) -> Unit = {},
    val onCookTimeChange: (String) -> Unit = {},
    val onIngredientQuantityChange: (Int, String) -> Unit = { _, _ -> },
    val onIngredientNoteChange: (Int, String) -> Unit = { _, _ -> },
    /** Abre el selector de unidades (o de alimentos) de ese ingrediente. */
    val onOpenUnitPicker: (Int) -> Unit = {},
    val onOpenFoodPicker: (Int) -> Unit = {},
    /** Cierra el selector sin tocar la línea. */
    val onCloseCatalogPicker: () -> Unit = {},
    /** Lo que se escribe en la búsqueda del selector (el ViewModel espera antes de preguntar). */
    val onCatalogQueryChange: (String) -> Unit = {},
    /** Repite la búsqueda del catálogo (también vale para mirar antes de repetir una creación). */
    val onRetryCatalogPicker: () -> Unit = {},
    /** Elige una entrada del catálogo; `null` quita la unidad o el alimento de la línea. */
    val onPickCatalogEntry: (CatalogEntry?) -> Unit = {},
    /** Crea en el catálogo el nombre escrito: nunca se reintenta solo. */
    val onCreateCatalogEntry: (String) -> Unit = {},
    val onAddIngredient: () -> Unit = {},
    val onRemoveIngredient: (Int) -> Unit = {},
    val onMoveIngredientUp: (Int) -> Unit = {},
    val onMoveIngredientDown: (Int) -> Unit = {},
    val onStepTitleChange: (Int, String) -> Unit = { _, _ -> },
    val onStepTextChange: (Int, String) -> Unit = { _, _ -> },
    val onAddStep: () -> Unit = {},
    val onRemoveStep: (Int) -> Unit = {},
    val onMoveStepUp: (Int) -> Unit = {},
    val onMoveStepDown: (Int) -> Unit = {},
    val onToggleTag: (DraftOrganizer) -> Unit = {},
    val onToggleCategory: (DraftOrganizer) -> Unit = {},
    val onRetryCatalogs: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onConfirmDuplicateSave: () -> Unit = {},
    val onDismissDuplicateSave: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onConfirmDiscard: () -> Unit = {},
    val onDismissDiscard: () -> Unit = {},
    val onReviewList: () -> Unit = {},
    val onOpenSavedRecipe: (String) -> Unit = {},
    /** La galería o la cámara han dejado una foto (Uri y de dónde viene). */
    val onPhotoPicked: (Uri, PhotoSource) -> Unit = { _, _ -> },
    /** El selector no ha dado foto: sin cámara o la galería no ha abierto. */
    val onPhotoProblem: (PhotoProblem) -> Unit = {},
    /** Repetir la subida de la misma foto, sin volver a recodificarla. */
    val onRetryPhoto: () -> Unit = {},
    /** Pasa el borrador guardado al formulario (solo cuando la persona lo pide). */
    val onRecoverDraft: () -> Unit = {},
    /** «Sustituir» cuando el borrador va a pisar lo que ya hay escrito. */
    val onConfirmRecoverDraft: () -> Unit = {},
    val onDismissRecoverDraft: () -> Unit = {},
    /** Tira el borrador guardado y no lo vuelve a ofrecer (pide confirmación). */
    val onDiscardRecoveredDraft: () -> Unit = {},
    val onConfirmDiscardRecoveredDraft: () -> Unit = {},
    val onDismissDiscardRecoveredDraft: () -> Unit = {},
    /** La pantalla pasa a segundo plano (`onStop`): se guarda lo escrito como borrador. */
    val onEnterBackground: () -> Unit = {},
)

/**
 * Formulario de receta, para crear y para editar. Sin lógica: pinta
 * [RecipeEditorState] y avisa de lo que toca la persona.
 *
 * - Nombre, descripción, raciones y tiempos; ingredientes y pasos con botones
 *   de subir, bajar y quitar; etiquetas y categorías elegidas de las que ya
 *   tiene el servidor.
 * - Los errores por campo vienen del núcleo ([DraftError]) o del 422 ya saneado
 *   ([ValidationDetail]); se pintan junto al campo y TalkBack los anuncia.
 * - «Guardar» abajo, al alcance del pulgar, y apagado mientras se guarda.
 * - Salir con cambios pide confirmación; guardar con éxito lo resuelve la ruta.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(
    state: RecipeEditorState,
    actions: RecipeEditorActions,
    modifier: Modifier = Modifier,
) {
    BackHandler { actions.onRequestClose() }

    // Al pasar a segundo plano se guarda el borrador de lo escrito (sin retardo y sin
    // bloquear la pantalla). Se engancha aquí y no en la ruta porque el formulario
    // solo está montado mientras se ve.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) actions.onEnterBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Quitar una fila con texto dentro pide confirmación: un toque perdido no puede
    // borrar un paso largo. Vacía, se quita directo. Es estado de la pantalla, no del
    // formulario: no viaja al ViewModel, pero sobrevive al giro ([PendingRemovalSaver]).
    var pendingRemoval by rememberSaveable(stateSaver = PendingRemovalSaver) {
        mutableStateOf<PendingRemoval?>(null)
    }

    val editing = state as? RecipeEditorState.Editing

    // «Foto subida» (fase 16h): lo que ya está hecho y no deja nada pendiente se dice en
    // un aviso corto abajo, no en una línea fija dentro de la sección. El id es el
    // contador de subidas del estado —uno por foto, sube con cada subida buena y
    // sobrevive al giro—, y el último aviso enseñado se recuerda aquí con
    // `rememberSaveable`: así no se repite al recomponer ni al girar, y si el aviso se
    // corta a medias se vuelve a ver entero. Los **fallos** de subida no pasan por aquí:
    // siguen en su tarjeta, con «Reintentar».
    var shownPhotoNoticeId by rememberSaveable { mutableStateOf(0L) }
    val uploadedPhoto = editing?.photo
    val photoNoticeId = when {
        uploadedPhoto == null -> 0L
        !uploadedPhoto.done -> 0L
        else -> uploadedPhoto.uploadedCount.toLong()
    }
    val notice = if (photoNoticeId != 0L && photoNoticeId != shownPhotoNoticeId) {
        SnackbarNotice(
            id = photoNoticeId,
            message = stringResource(R.string.editor_photo_done),
        )
    } else {
        null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        // Un host por `Scaffold`: el aviso se coloca solo por encima de la barra de
        // guardar y del teclado, sin tapar el botón. Nada de Deshacer (fase 16h).
        snackbarHost = {
            GastronomiconSnackbarHost(
                notice = notice,
                onShown = { notice?.let { shownPhotoNoticeId = it.id } },
            )
        },
        topBar = {
            GastronomiconTopBar(
                title = {
                    Text(
                        text = stringResource(titleRes(state)),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    // Mientras se guarda, atrás no hace nada: que se vea apagado en vez
                    // de dar la sensación de que la pantalla se ha colgado.
                    IconButton(
                        onClick = actions.onRequestClose,
                        enabled = (state as? RecipeEditorState.Editing)?.saving != true,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (editing != null) {
                SaveBar(state = editing, actions = actions)
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (state) {
            RecipeEditorState.Closed -> LoadingBlock(
                text = stringResource(R.string.editor_loading),
                modifier = contentModifier,
            )
            is RecipeEditorState.Loading -> LoadingBlock(
                text = stringResource(R.string.editor_loading),
                modifier = contentModifier,
            )
            RecipeEditorState.NotFound -> MessageBlock(
                title = stringResource(R.string.editor_not_found_title),
                text = stringResource(R.string.editor_not_found_text),
                primaryLabel = stringResource(R.string.editor_not_found_action),
                onPrimary = actions.onReviewList,
                modifier = contentModifier,
            )
            is RecipeEditorState.LoadFailed -> ProblemBlock(
                problem = state.problem,
                defaultTitle = R.string.editor_load_failed_title,
                onRetry = actions.onRetryLoad,
                onOpenSettings = actions.onOpenSettings,
                modifier = contentModifier,
            )
            is RecipeEditorState.Editing -> EditorContent(
                state = state,
                actions = actions,
                onAskRemove = { pendingRemoval = it },
                modifier = contentModifier,
            )
            is RecipeEditorState.Saved -> SavedContent(
                state = state,
                actions = actions,
                modifier = contentModifier,
            )
        }
    }

    if (editing != null && editing.showDiscardConfirm) {
        DiscardDialog(
            createdIncomplete = editing.createdIncomplete,
            // Con una oferta de borrador viva, lo escrito la sustituye: el texto lo dice.
            replacesDraft = editing.recoveredDraft != null,
            onConfirm = actions.onConfirmDiscard,
            onDismiss = actions.onDismissDiscard,
        )
    }
    if (editing != null && editing.showDuplicateConfirm) {
        DuplicateSaveDialog(
            onConfirm = actions.onConfirmDuplicateSave,
            onDismiss = actions.onDismissDuplicateSave,
        )
    }
    if (editing != null && editing.showDraftConfirm) {
        RecoverDraftDialog(
            onConfirm = actions.onConfirmRecoverDraft,
            onDismiss = actions.onDismissRecoverDraft,
        )
    }
    if (editing != null && editing.showDraftDiscardConfirm) {
        DiscardDraftDialog(
            onConfirm = actions.onConfirmDiscardRecoveredDraft,
            onDismiss = actions.onDismissDiscardRecoveredDraft,
        )
    }

    // Selector de unidad o de alimento: se pinta encima del formulario y no cambia
    // nada de la línea hasta que la persona elige una entrada.
    val picker = editing?.catalogPicker
    if (editing != null && picker != null) {
        val row = editing.form.ingredients.getOrNull(picker.index)
        CatalogPickerDialog(
            picker = picker,
            ingredientNumber = picker.index + 1,
            currentValue = when (picker.kind) {
                IngredientCatalogKind.Unit -> row?.unit.orEmpty()
                IngredientCatalogKind.Food -> row?.food.orEmpty()
            },
            currentId = when (picker.kind) {
                IngredientCatalogKind.Unit -> row?.unitId
                IngredientCatalogKind.Food -> row?.foodId
            },
            actions = actions,
        )
    }

    val form = editing?.form
    val pending = pendingRemoval
    // Tras girar, el índice guardado puede haber dejado de existir (la fila se quitó
    // mientras tanto): se cierra el diálogo y no se quita nada a ciegas. Mientras el
    // formulario aún no está (cargando), se espera sin soltar el aviso.
    val formLoaded = form != null
    val pendingExists = when {
        pending == null || form == null -> false
        pending.isStep -> pending.index in form.steps.indices
        else -> pending.index in form.ingredients.indices
    }
    LaunchedEffect(pending, formLoaded, pendingExists) {
        if (pending != null && formLoaded && !pendingExists) pendingRemoval = null
    }
    if (form != null && pending != null && pendingExists) {
        RemoveRowDialog(
            isStep = pending.isStep,
            onConfirm = {
                pendingRemoval = null
                if (pending.isStep) {
                    actions.onRemoveStep(pending.index)
                } else {
                    actions.onRemoveIngredient(pending.index)
                }
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@StringRes
private fun titleRes(state: RecipeEditorState): Int = when (state) {
    is RecipeEditorState.Editing ->
        if (state.mode is EditorMode.Create) R.string.editor_title_create else R.string.editor_title_edit
    is RecipeEditorState.Loading ->
        if (state.mode is EditorMode.Create) R.string.editor_title_create else R.string.editor_title_edit
    is RecipeEditorState.LoadFailed ->
        if (state.mode is EditorMode.Create) R.string.editor_title_create else R.string.editor_title_edit
    RecipeEditorState.NotFound -> R.string.editor_title_edit
    is RecipeEditorState.Saved -> R.string.editor_title_saved
    RecipeEditorState.Closed -> R.string.editor_title_create
}

// ------------------------------------------------------------------ contenido

/** Fila de ingrediente o de paso esperando a que la persona confirme que la quita. */
private data class PendingRemoval(val isStep: Boolean, val index: Int)

/**
 * Guarda el diálogo de quitar para que el giro no lo cierre: si es paso o ingrediente y
 * la posición. Al restaurar, la pantalla comprueba que la fila siga existiendo.
 */
private val PendingRemovalSaver: Saver<PendingRemoval?, Any> = Saver(
    save = { pending -> pending?.let { arrayListOf<Any>(it.isStep, it.index) } },
    restore = { saved ->
        val parts = saved as? List<*>
        val isStep = parts?.getOrNull(0) as? Boolean
        val index = parts?.getOrNull(1) as? Int
        if (isStep != null && index != null) PendingRemoval(isStep, index) else null
    },
)

/**
 * Qué tarjeta debe quedar a la vista, por su id de interfaz: la posición cambia al
 * mover y la tarjeta se lleva el foco con ella. [focus] pide además el foco en su
 * primer campo (al añadir); tras subir o bajar solo se asegura que se vea. [token]
 * cambia en cada petición para que dos seguidas se noten.
 */
private data class CardFocus(val uiId: Long, val focus: Boolean, val token: Int)

@Composable
private fun EditorContent(
    state: RecipeEditorState.Editing,
    actions: RecipeEditorActions,
    onAskRemove: (PendingRemoval) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // Tras un guardado con avisos o errores, subir a donde se ven (están arriba del todo).
    val problemKey = listOf(
        state.failedSaveAttempts, state.saveProblem, state.rejected, state.serverDetails, state.maybeCreated,
    )
    LaunchedEffect(problemKey) {
        val any = state.saveProblem != null || state.rejected || state.errors.isNotEmpty() ||
            state.serverDetails.isNotEmpty() || state.maybeCreated
        if (any) scroll.animateScrollTo(0)
    }
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
    ) {
        EditorMessages(state = state, actions = actions)
        BasicSection(state = state, actions = actions)
        IngredientsSection(
            state = state,
            actions = actions,
            onAskRemove = { index -> onAskRemove(PendingRemoval(isStep = false, index = index)) },
        )
        StepsSection(
            state = state,
            actions = actions,
            onAskRemove = { index -> onAskRemove(PendingRemoval(isStep = true, index = index)) },
        )
        PhotoSection(state = state, actions = actions)
        OrganizersSection(state = state, actions = actions)
    }
}

@Composable
private fun EditorMessages(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    // Borrador guardado de la última vez: se ofrece, nunca se aplica solo. La oferta
    // sigue a la vista aunque se escriba: solo se va con «Recuperar» o «Descartar».
    val recovered = state.recoveredDraft
    if (recovered != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource(R.string.editor_draft_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = stringResource(R.string.editor_draft_text, state.recoveredAtText),
                    style = MaterialTheme.typography.bodyLarge,
                )
                // En edición, el borrador es de una versión anterior del formulario: la
                // receta del servidor pudo cambiar desde entonces.
                if (state.mode is EditorMode.Edit) {
                    Text(
                        text = stringResource(R.string.editor_draft_edit_warning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.draftInvalid) {
                    Text(
                        text = stringResource(R.string.editor_draft_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(
                    onClick = actions.onRecoverDraft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.editor_draft_recover))
                }
                OutlinedButton(
                    onClick = actions.onDiscardRecoveredDraft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.editor_draft_discard))
                }
            }
        }
    }

    // Lo primero: si el servidor no ha dicho si la receta se creó, que se vea antes
    // que cualquier otro aviso. Guardar otra vez pedirá confirmación (ver el ViewModel).
    if (state.maybeCreated) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource(R.string.editor_maybe_created),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                // Pasa por el diálogo de descartar: salir pierde lo escrito.
                OutlinedButton(
                    onClick = actions.onRequestClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.editor_maybe_created_review))
                }
            }
        }
    }

    val problem = state.saveProblem
    if (problem != null) {
        ProblemBanner(
            problem = problem,
            message = stringResource(R.string.editor_save_failed),
            onRetry = actions.onSave,
            onOpenSettings = actions.onOpenSettings,
            // El formulario está abierto y con texto: ir a Ajustes lo cierra y lo tira.
            unauthorizedTextRes = R.string.problem_text_unauthorized_form,
        )
    }

    if (state.createdIncomplete) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                text = stringResource(R.string.editor_created_incomplete),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(Spacing.medium)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (state.rejected) {
        ErrorBox(text = stringResource(R.string.editor_save_rejected))
    }

    // Resumen de TODOS los fallos con su sitio («Ingrediente 12: …»), para que lo que se ve
    // arriba explique por qué no se guarda aunque el campo esté más abajo.
    val summary = state.errors.filter { !isListField(it.field) && it.index != null } +
        state.errors.filter { isListField(it.field) }
    if (summary.isNotEmpty()) {
        ErrorBox(title = stringResource(R.string.editor_list_errors_title)) {
            for (error in summary) {
                val place = errorPlace(error)
                val text = draftErrorText(error)
                Text(
                    text = if (place != null) "$place: $text" else text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (state.serverDetails.isNotEmpty()) {
        ErrorBox(title = stringResource(R.string.editor_server_details_title)) {
            for (detail in state.serverDetails) {
                Text(text = detail.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ErrorBox(title: String? = null, text: String? = null, content: @Composable () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            if (text != null) {
                Text(text = text, style = MaterialTheme.typography.bodyLarge)
            }
            content()
        }
    }
}

@Composable
private fun errorPlace(error: DraftError): String? {
    val number = (error.index ?: return null) + 1
    return when (error.field) {
        DraftField.IngredientQuantity, DraftField.IngredientNote, DraftField.IngredientTitle,
        DraftField.IngredientUnit, DraftField.IngredientFood ->
            stringResource(R.string.editor_error_place_ingredient, number)
        DraftField.StepTitle, DraftField.StepText ->
            stringResource(R.string.editor_error_place_step, number)
        else -> null
    }
}

private fun isListField(field: DraftField): Boolean = when (field) {
    DraftField.Ingredients, DraftField.Steps, DraftField.Notes, DraftField.Tags, DraftField.Categories -> true
    else -> false
}

// ------------------------------------------------------------------- básico

@Composable
private fun BasicSection(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    val form = state.form
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.editor_section_basic))

        val nameError = firstError(state, DraftField.Name, null)
        OutlinedTextField(
            value = form.name,
            onValueChange = actions.onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .fieldError(nameError != null, nameError.orEmpty()),
            readOnly = state.saving,
            label = { Text(stringResource(R.string.editor_name_label)) },
            isError = nameError != null,
            supportingText = if (nameError != null) {
                { Text(nameError) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
        )

        val descriptionError = firstError(state, DraftField.Description, null)
        OutlinedTextField(
            value = form.description,
            onValueChange = actions.onDescriptionChange,
            modifier = Modifier
                .fillMaxWidth()
                .fieldError(descriptionError != null, descriptionError.orEmpty()),
            readOnly = state.saving,
            label = { Text(stringResource(R.string.editor_description_label)) },
            isError = descriptionError != null,
            supportingText = if (descriptionError != null) {
                { Text(descriptionError) }
            } else {
                null
            },
            minLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next,
            ),
        )

        val servingsError = firstError(state, DraftField.Servings, null)
        OutlinedTextField(
            value = form.servings,
            onValueChange = actions.onServingsChange,
            modifier = Modifier
                .fillMaxWidth()
                .fieldError(servingsError != null, servingsError.orEmpty()),
            readOnly = state.saving,
            label = { Text(stringResource(R.string.editor_servings_label)) },
            isError = servingsError != null,
            supportingText = if (servingsError != null) {
                { Text(servingsError) }
            } else {
                { Text(stringResource(R.string.editor_servings_supporting)) }
            },
            singleLine = true,
            // Texto y no decimal: el teclado decimal de muchos móviles no tiene «/»,
            // y el núcleo acepta 1,5 y 1/2. La ayuda lo dice.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
        )

        TimeField(
            value = form.totalTime,
            onValueChange = actions.onTotalTimeChange,
            label = stringResource(R.string.editor_total_time_label),
            error = firstError(state, DraftField.TotalTime, null),
            help = stringResource(R.string.editor_time_supporting),
            readOnly = state.saving,
        )
        TimeField(
            value = form.prepTime,
            onValueChange = actions.onPrepTimeChange,
            label = stringResource(R.string.editor_prep_time_label),
            error = firstError(state, DraftField.PrepTime, null),
            help = null,
            readOnly = state.saving,
        )
        TimeField(
            value = form.cookTime,
            onValueChange = actions.onCookTimeChange,
            label = stringResource(R.string.editor_cook_time_label),
            error = firstError(state, DraftField.CookTime, null),
            help = null,
            readOnly = state.saving,
        )
    }
}

@Composable
private fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    help: String?,
    readOnly: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .fieldError(error != null, error.orEmpty()),
        readOnly = readOnly,
        label = { Text(label) },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else if (help != null) {
            { Text(help) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
}

// ------------------------------------------------------------------- foto

/**
 * Foto de la receta, solo en modo edición: una receta nueva se guarda antes de
 * tener foto. Los botones abren la galería o la cámara del sistema; la foto se
 * recodifica sin datos de ubicación y se sube sola, con progreso. El «ya está»
 * va en el aviso corto de la pantalla (Snackbar, fase 16h), no dentro de la
 * sección: aquí solo quedan la vista previa, el progreso y los fallos, que se
 * leen con calma y ofrecen repetir.
 *
 * Sin Uri, sin rutas y sin bytes en el estado: la vista previa ya es un
 * `Bitmap` pequeño y recodificado ([PhotoState.preview]).
 */
@Composable
private fun PhotoSection(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    if (state.mode is EditorMode.Create) {
        // También aquí hay título: sin él, la frase suelta parecía parte de la sección
        // anterior y no se entendía de qué hablaba.
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            SectionTitle(stringResource(R.string.editor_section_photo))
            Text(
                text = stringResource(R.string.editor_photo_hint_create),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // En una preview no hay Activity detrás y `rememberLauncherForActivityResult` no
    // puede registrarse: el selector solo se monta en la app de verdad.
    val inPreview = LocalInspectionMode.current
    val picker = if (inPreview) {
        null
    } else {
        rememberPhotoPicker { result ->
            when (result) {
                is PhotoPickResult.Picked -> actions.onPhotoPicked(result.uri, result.source)
                PhotoPickResult.NoCamera -> actions.onPhotoProblem(PhotoProblem.NoCamera)
                PhotoPickResult.Failed -> actions.onPhotoProblem(PhotoProblem.PickFailed)
                // Cancelar no es un fallo: no hay nada que contar.
                PhotoPickResult.Cancelled -> Unit
            }
        }
    }

    val photo = state.photo
    val busy = photo.busy || state.saving

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.editor_section_photo))
        Text(
            text = stringResource(R.string.editor_photo_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val preview = photo.preview
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = stringResource(R.string.editor_photo_preview_description),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PhotoPreviewHeight)
                    .clip(MaterialTheme.shapes.medium),
            )
        }

        OutlinedButton(
            onClick = { picker?.pickFromGallery() },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Icon(painter = painterResource(R.drawable.ic_photo), contentDescription = null)
            Spacer(Modifier.width(Spacing.small))
            Text(stringResource(R.string.editor_photo_gallery))
        }
        OutlinedButton(
            onClick = { picker?.takePhoto() },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Icon(painter = painterResource(R.drawable.ic_camera), contentDescription = null)
            Spacer(Modifier.width(Spacing.small))
            Text(stringResource(R.string.editor_photo_camera))
        }

        if (photo.preparing || photo.uploading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(
                        if (photo.uploading) R.string.editor_photo_uploading else R.string.editor_photo_preparing,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        val problem = photo.problem
        if (problem != null) {
            PhotoProblemBox(
                problem = problem,
                onRetry = actions.onRetryPhoto,
                onOpenSettings = actions.onOpenSettings,
            )
        }
    }
}

/** Aviso de la foto: dice qué pasa y, si sirve de algo, ofrece repetir o ir a Ajustes. */
@Composable
private fun PhotoProblemBox(problem: PhotoProblem, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val unauthorized = (problem as? PhotoProblem.Network)?.problem == ConnectionProblem.Unauthorized
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = photoProblemText(problem),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (unauthorized) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.problem_open_settings))
                }
            } else if (problem is PhotoProblem.Network || problem == PhotoProblem.Unknown) {
                // Subir la foto es un `PUT`: repetir no duplica nada.
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.problem_retry))
                }
            }
        }
    }
}

@Composable
private fun photoProblemText(problem: PhotoProblem): String = when (problem) {
    is PhotoProblem.Network ->
        if (problem.problem == ConnectionProblem.Unauthorized) {
            // La foto se pierde con el formulario: el aviso lo dice.
            stringResource(R.string.problem_text_unauthorized_form)
        } else {
            stringResource(errorTextRes(problem.problem))
        }
    PhotoProblem.TooLarge -> stringResource(R.string.editor_photo_problem_too_large)
    PhotoProblem.NotAnImage -> stringResource(R.string.editor_photo_problem_not_image)
    PhotoProblem.HasMetadata -> stringResource(R.string.editor_photo_problem_metadata)
    PhotoProblem.NoCamera -> stringResource(R.string.editor_photo_problem_no_camera)
    PhotoProblem.PickFailed -> stringResource(R.string.editor_photo_problem_pick_failed)
    PhotoProblem.Rejected -> stringResource(R.string.editor_photo_problem_rejected)
    PhotoProblem.NotFound -> stringResource(R.string.editor_photo_problem_not_found)
    PhotoProblem.Unknown -> stringResource(R.string.setup_error_unknown)
}

// -------------------------------------------------------------- ingredientes

@Composable
private fun IngredientsSection(
    state: RecipeEditorState.Editing,
    actions: RecipeEditorActions,
    onAskRemove: (Int) -> Unit,
) {
    val rows = state.form.ingredients
    // Foco y scroll: al añadir, el foco va al ingrediente nuevo; al subir o bajar, la
    // tarjeta que se mueve se lleva el foco y se queda a la vista. Se apunta por id de
    // interfaz y no por posición: con `key(row.uiId)` la tarjeta es la misma aunque
    // cambie de sitio, así que un segundo toque sigue moviendo la misma fila.
    var focus by remember { mutableStateOf<CardFocus?>(null) }
    var focusToken by remember { mutableIntStateOf(0) }
    var knownCount by remember { mutableIntStateOf(rows.size) }

    LaunchedEffect(rows.size) {
        if (rows.size > knownCount) {
            val added = rows.lastOrNull()
            if (added != null) {
                focusToken += 1
                focus = CardFocus(uiId = added.uiId, focus = true, token = focusToken)
            }
        }
        knownCount = rows.size
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.editor_section_ingredients))
        // Una vez, no en cada tarjeta: de dónde salen la unidad y el alimento.
        if (rows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.editor_ingredient_catalog_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_ingredients_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEachIndexed { index, row ->
            key(row.uiId) {
                IngredientCard(
                    index = index,
                    row = row,
                    count = rows.size,
                    state = state,
                    actions = actions,
                    focusRequest = focus?.takeIf { it.uiId == row.uiId },
                    onMoveUp = {
                        actions.onMoveIngredientUp(index)
                        if (index > 0) {
                            focusToken += 1
                            focus = CardFocus(uiId = row.uiId, focus = false, token = focusToken)
                        }
                    },
                    onMoveDown = {
                        actions.onMoveIngredientDown(index)
                        if (index < rows.lastIndex) {
                            focusToken += 1
                            focus = CardFocus(uiId = row.uiId, focus = false, token = focusToken)
                        }
                    },
                    onAskRemove = { onAskRemove(index) },
                )
            }
        }
        OutlinedButton(
            onClick = actions.onAddIngredient,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
            )
            Spacer(Modifier.width(Spacing.small))
            Text(stringResource(R.string.editor_add_ingredient))
        }
    }
}

@Composable
private fun IngredientCard(
    index: Int,
    row: IngredientForm,
    count: Int,
    state: RecipeEditorState.Editing,
    actions: RecipeEditorActions,
    focusRequest: CardFocus?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAskRemove: () -> Unit,
) {
    val quantityError = firstError(state, DraftField.IngredientQuantity, index)
    val noteError = firstError(state, DraftField.IngredientNote, index)
    val unitError = firstError(state, DraftField.IngredientUnit, index)
    val foodError = firstError(state, DraftField.IngredientFood, index)
    val titleError = firstError(state, DraftField.IngredientTitle, index)
    val rowError = quantityError ?: noteError ?: unitError ?: foodError ?: titleError

    val bringIntoView = remember { BringIntoViewRequester() }
    val quantityFocus = remember { FocusRequester() }
    LaunchedEffect(focusRequest?.token) {
        val request = focusRequest ?: return@LaunchedEffect
        if (request.focus) quantityFocus.requestFocus()
        bringIntoView.bringIntoView()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .fieldError(rowError != null, rowError.orEmpty()),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(
                    text = stringResource(R.string.editor_ingredient_number, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MoveButtons(
                    enabled = !state.saving,
                    canMoveUp = index > 0,
                    canMoveDown = index < count - 1,
                    onUp = onMoveUp,
                    onDown = onMoveDown,
                    // Con algo escrito pide confirmación; vacía se quita directo.
                    onRemove = {
                        if (hasIngredientText(row)) onAskRemove() else actions.onRemoveIngredient(index)
                    },
                    itemName = stringResource(R.string.editor_ingredient_number, index + 1),
                    removeLabel = stringResource(R.string.editor_remove_ingredient, index + 1),
                )
            }

            if (row.title.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.editor_ingredient_section, row.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = row.quantity,
                onValueChange = { actions.onIngredientQuantityChange(index, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(quantityFocus)
                    .fieldError(quantityError != null, quantityError.orEmpty()),
                readOnly = state.saving,
                label = { Text(stringResource(R.string.editor_ingredient_quantity_label)) },
                isError = quantityError != null,
                supportingText = if (quantityError != null) {
                    { Text(quantityError) }
                } else {
                    { Text(stringResource(R.string.editor_ingredient_quantity_supporting)) }
                },
                singleLine = true,
                // Texto y no decimal: el teclado decimal de muchos móviles no tiene «/»
                // y el núcleo acepta 1,5 y 1/2. La ayuda de al lado lo dice.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            )

            OutlinedTextField(
                value = row.note,
                onValueChange = { actions.onIngredientNoteChange(index, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .fieldError(noteError != null, noteError.orEmpty()),
                readOnly = state.saving,
                label = { Text(stringResource(R.string.editor_ingredient_note_label)) },
                isError = noteError != null,
                supportingText = if (noteError != null) {
                    { Text(noteError) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            )

            // La unidad y el alimento se eligen del catálogo del servidor: la tarjeta
            // nunca deja escribirlos a mano, para no inventar referencias nuevas.
            val noUnit = stringResource(R.string.editor_ingredient_unit_none)
            val noFood = stringResource(R.string.editor_ingredient_food_none)
            CatalogField(
                label = stringResource(R.string.editor_ingredient_unit_label),
                value = row.unit,
                placeholder = noUnit,
                description = stringResource(
                    R.string.editor_ingredient_unit_field,
                    index + 1,
                    row.unit.ifEmpty { noUnit },
                ),
                enabled = !state.saving,
                onClick = { actions.onOpenUnitPicker(index) },
                modifier = Modifier.fieldError(unitError != null, unitError.orEmpty()),
            )
            if (unitError != null) {
                Text(
                    text = unitError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            CatalogField(
                label = stringResource(R.string.editor_ingredient_food_label),
                value = row.food,
                placeholder = noFood,
                description = stringResource(
                    R.string.editor_ingredient_food_field,
                    index + 1,
                    row.food.ifEmpty { noFood },
                ),
                enabled = !state.saving,
                onClick = { actions.onOpenFoodPicker(index) },
                modifier = Modifier.fieldError(foodError != null, foodError.orEmpty()),
            )
            if (foodError != null) {
                Text(
                    text = foodError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (titleError != null) {
                Text(
                    text = titleError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** ¿Hay algo escrito en el ingrediente? Solo entonces quitar pide confirmación. */
private fun hasIngredientText(row: IngredientForm): Boolean =
    row.quantity.isNotBlank() || row.note.isNotBlank() ||
        row.unit.isNotBlank() || row.food.isNotBlank()

// ------------------------------------------------- catálogo (unidad y alimento)

/**
 * Campo de la unidad o el alimento: se lee, no se escribe. Detrás hay un
 * selector, porque Mealie guarda una referencia al catálogo y no un texto
 * suelto. No baja de 48 dp y TalkBack dice de qué ingrediente es y qué hay
 * elegido, que con varias filas iguales hace falta.
 */
@Composable
private fun CatalogField(
    label: String,
    value: String,
    placeholder: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.MinTouchTarget)
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Selector de unidad o de alimento: busca en el catálogo del servidor mientras
 * se escribe (con retardo, en el ViewModel) y deja crear lo que no esté.
 *
 * - Cargando, sin resultados, sin conexión (con «Volver a intentar») y sesión
 *   caducada (con «Abrir Ajustes») se dicen aquí, sin dejar el hueco vacío.
 * - Crear nunca se reintenta solo: si el servidor no confirma, se avisa de que
 *   puede existir y se pide mirar el catálogo antes de repetir. Tras un fallo de
 *   creación, el botón repite la **búsqueda**, no el alta: crear otra vez es
 *   siempre un toque aparte y a propósito.
 * - Mientras llega una búsqueda nueva, los resultados de la anterior siguen a la
 *   vista con el aviso de espera encima: la hoja no se queda en blanco en cada letra.
 * - Con una creación en vuelo no se puede cerrar ni elegir: el resultado del
 *   servidor tiene que llegar a la línea, o se avisa de que no se pudo aplicar.
 * - El nombre escrito para crear es de esta pantalla y de nadie más (`remember`):
 *   no es un dato del servidor y no se guarda al girar el móvil. Empieza con lo
 *   que se ha buscado, que es justo lo que se quiere crear cuando no aparece.
 */
@Composable
private fun CatalogPickerDialog(
    picker: CatalogPicker,
    ingredientNumber: Int,
    currentValue: String,
    currentId: String?,
    actions: RecipeEditorActions,
) {
    val isUnit = picker.kind == IngredientCatalogKind.Unit
    // El nombre a crear es de esta pantalla y de nadie más (`remember`, no
    // `rememberSaveable`). Empieza con lo buscado y, en cuanto la persona escribe
    // en el campo, manda lo suyo (también si lo deja vacío a propósito).
    var typedName by remember(picker.kind, picker.index) { mutableStateOf<String?>(null) }
    val createName = typedName ?: picker.query
    val trimmedName = createName.trim()
    val firstCreateError = if (picker.createErrors.isEmpty()) {
        null
    } else {
        catalogCreateErrorText(picker.createErrors.first())
    }
    val createProblem = picker.createProblem

    Dialog(
        // Con una creación en vuelo, atrás o un toque fuera no cierran: el `POST` ya
        // está enviado y el resultado tiene que llegar a la línea (si no, al repetir
        // se podría duplicar la entrada). El ViewModel lo comprueba también.
        onDismissRequest = { if (!picker.creating) actions.onCloseCatalogPicker() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Todo el contenido se desplaza: con el texto del sistema al doble, la
        // lista y el bloque de crear no caben a la vez y nada puede quedar cortado.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (isUnit) R.string.editor_catalog_unit_title else R.string.editor_catalog_food_title,
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(R.string.editor_ingredient_number, ingredientNumber),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = actions.onCloseCatalogPicker, enabled = !picker.creating) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.editor_catalog_close),
                        )
                    }
                }

                if (currentValue.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.editor_catalog_current, currentValue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = picker.query,
                    onValueChange = actions.onCatalogQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !picker.creating,
                    label = { Text(stringResource(R.string.editor_catalog_search_label)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )

                // Con resultados a la vista, la espera es una línea más y la lista no
                // parpadea en cada letra.
                if (picker.loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(
                                if (isUnit) R.string.editor_catalog_loading_unit else R.string.editor_catalog_loading_food,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val problem = picker.problem
                if (problem != null) {
                    ProblemBanner(
                        problem = problem,
                        message = stringResource(
                            if (isUnit) R.string.editor_catalog_failed_unit else R.string.editor_catalog_failed_food,
                        ),
                        onRetry = actions.onRetryCatalogPicker,
                        onOpenSettings = actions.onOpenSettings,
                        showDetail = false,
                        // Aunque aquí el detalle va oculto, el selector vive encima del
                        // formulario: si algún día se enseña, no puede prometer otra cosa.
                        unauthorizedTextRes = R.string.problem_text_unauthorized_form,
                    )
                }
                // Lo que ya estaba a la vista no desaparece al teclear ni porque falle
                // una búsqueda nueva: los resultados de la búsqueda anterior siguen ahí
                // (con el aviso de espera encima) y el fallo se cuenta arriba. Así la
                // hoja no se queda en blanco entre letra y letra.
                if (picker.items.isNotEmpty()) {
                    for (entry in picker.items) {
                        val isCurrent = currentId?.let { it == entry.id } ?: (entry.name == currentValue)
                        CatalogChoiceRow(
                            entry = entry,
                            isSelected = isCurrent,
                            enabled = !picker.creating,
                            onClick = { actions.onPickCatalogEntry(entry) },
                        )
                    }
                    if (picker.truncated) {
                        Text(
                            text = stringResource(R.string.editor_catalog_truncated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!picker.loading && picker.loaded && problem == null) {
                    Text(
                        text = stringResource(
                            when {
                                picker.query.isNotBlank() && isUnit -> R.string.editor_catalog_empty_unit
                                picker.query.isNotBlank() -> R.string.editor_catalog_empty_food
                                isUnit -> R.string.editor_catalog_catalog_empty_unit
                                else -> R.string.editor_catalog_catalog_empty_food
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                if (currentValue.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { actions.onPickCatalogEntry(null) },
                        enabled = !picker.creating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizes.MinTouchTarget),
                    ) {
                        Text(
                            stringResource(
                                if (isUnit) R.string.editor_catalog_clear_unit else R.string.editor_catalog_clear_food,
                            ),
                        )
                    }
                }

                Text(
                    text = stringResource(
                        if (isUnit) R.string.editor_catalog_create_title_unit else R.string.editor_catalog_create_title_food,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = createName,
                    onValueChange = { typedName = it },
                    modifier = Modifier.fillMaxWidth(),
                    // Tras «puede que ya exista» o si la entrada creada no se pudo
                    // aplicar, crear otra vez a ciegas puede duplicar: no se escribe más.
                    enabled = !picker.creating && !picker.maybeCreated && !picker.createdNotApplied,
                    label = {
                        Text(
                            stringResource(
                                if (isUnit) R.string.editor_catalog_create_label_unit else R.string.editor_catalog_create_label_food,
                            ),
                        )
                    },
                    singleLine = true,
                    isError = firstCreateError != null,
                    supportingText = if (firstCreateError != null) {
                        { Text(firstCreateError) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Button(
                    onClick = { actions.onCreateCatalogEntry(trimmedName) },
                    // Tras un «puede que ya exista» no se repite a ciegas: primero se
                    // mira el catálogo (el aviso de abajo lo dice y ofrece buscarlo).
                    enabled = trimmedName.isNotEmpty() && !picker.creating &&
                        !picker.maybeCreated && !picker.createdNotApplied,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    if (picker.creating) BusyIndicator()
                    Text(
                        text = when {
                            picker.creating -> stringResource(R.string.editor_catalog_creating)
                            trimmedName.isEmpty() -> stringResource(R.string.editor_catalog_create_idle)
                            else -> stringResource(R.string.editor_catalog_create_action, trimmedName)
                        },
                    )
                }

                if (picker.maybeCreated) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.medium),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Text(
                                text = stringResource(R.string.editor_catalog_maybe_created),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            OutlinedButton(
                                onClick = actions.onRetryCatalogPicker,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Sizes.MinTouchTarget),
                            ) {
                                Text(stringResource(R.string.editor_catalog_search_again))
                            }
                        }
                    }
                }

                if (createProblem != null || picker.createRejected) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.medium),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Text(
                                text = if (picker.createRejected) {
                                    stringResource(R.string.editor_catalog_create_rejected)
                                } else {
                                    stringResource(R.string.editor_catalog_create_failed)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            if (createProblem != null) {
                                Text(
                                    text = stringResource(errorTextRes(createProblem)),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (createProblem == ConnectionProblem.Unauthorized) {
                                Button(
                                    onClick = actions.onOpenSettings,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = Sizes.MinTouchTarget),
                                ) {
                                    Text(stringResource(R.string.problem_open_settings))
                                }
                            } else {
                                // Repetir el alta a ciegas puede dejar dos entradas: el
                                // botón vuelve a leer el catálogo y, si no está, se crea
                                // a propósito con «Crear», que sigue justo arriba.
                                if (!picker.createRejected) {
                                    Text(
                                        text = stringResource(R.string.editor_catalog_create_check_first),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                OutlinedButton(
                                    onClick = actions.onRetryCatalogPicker,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = Sizes.MinTouchTarget),
                                ) {
                                    Text(stringResource(R.string.editor_catalog_search_again))
                                }
                            }
                        }
                    }
                }

                // La entrada se creó en el servidor pero la fila ya no estaba: se dice
                // en vez de dejar el resultado perdido (no debería pasar con el selector
                // abierto, que no deja tocar nada mientras se crea).
                if (picker.createdNotApplied) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.medium),
                            verticalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Text(
                                text = stringResource(R.string.editor_catalog_created_not_applied),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            OutlinedButton(
                                onClick = actions.onCloseCatalogPicker,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Sizes.MinTouchTarget),
                            ) {
                                Text(stringResource(R.string.editor_catalog_close))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Una entrada del catálogo en el selector. Con marca, no solo con color, si es la elegida. */
@Composable
private fun CatalogChoiceRow(
    entry: CatalogEntry,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.MinTouchTarget)
            .semantics(mergeDescendants = true) { selected = isSelected },
        shape = MaterialTheme.shapes.small,
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                if (entry.hint.isNotEmpty()) {
                    Text(
                        text = entry.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSelected) {
                Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null)
            }
        }
    }
}

/** ¿Hay algo escrito en el paso? */
private fun hasStepText(row: StepForm): Boolean = row.title.isNotBlank() || row.text.isNotBlank()

/**
 * Error del nombre nuevo del catálogo. No es un campo del formulario, así que
 * «falta este dato» se dice en genérico (el botón además está apagado en
 * blanco) y solo se concreta lo que tiene un tope conocido.
 */
@Composable
private fun catalogCreateErrorText(error: DraftError): String = when (error.kind) {
    DraftErrorKind.TooLong -> if (error.field == DraftField.IngredientFood) {
        stringResource(R.string.editor_error_ingredient_food_too_long)
    } else {
        stringResource(R.string.editor_error_ingredient_unit_too_long)
    }
    else -> genericErrorText(error.kind)
}

// --------------------------------------------------------------------- pasos

@Composable
private fun StepsSection(
    state: RecipeEditorState.Editing,
    actions: RecipeEditorActions,
    onAskRemove: (Int) -> Unit,
) {
    val rows = state.form.steps
    // Igual que en los ingredientes: el paso nuevo recibe el foco y el que se mueve
    // se lleva el foco consigo y se queda a la vista, por id y no por posición.
    var focus by remember { mutableStateOf<CardFocus?>(null) }
    var focusToken by remember { mutableIntStateOf(0) }
    var knownCount by remember { mutableIntStateOf(rows.size) }

    LaunchedEffect(rows.size) {
        if (rows.size > knownCount) {
            val added = rows.lastOrNull()
            if (added != null) {
                focusToken += 1
                focus = CardFocus(uiId = added.uiId, focus = true, token = focusToken)
            }
        }
        knownCount = rows.size
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.editor_section_steps))
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_steps_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEachIndexed { index, row ->
            key(row.uiId) {
                StepCard(
                    index = index,
                    row = row,
                    count = rows.size,
                    state = state,
                    actions = actions,
                    focusRequest = focus?.takeIf { it.uiId == row.uiId },
                    onMoveUp = {
                        actions.onMoveStepUp(index)
                        if (index > 0) {
                            focusToken += 1
                            focus = CardFocus(uiId = row.uiId, focus = false, token = focusToken)
                        }
                    },
                    onMoveDown = {
                        actions.onMoveStepDown(index)
                        if (index < rows.lastIndex) {
                            focusToken += 1
                            focus = CardFocus(uiId = row.uiId, focus = false, token = focusToken)
                        }
                    },
                    onAskRemove = { onAskRemove(index) },
                )
            }
        }
        OutlinedButton(
            onClick = actions.onAddStep,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
            )
            Spacer(Modifier.width(Spacing.small))
            Text(stringResource(R.string.editor_add_step))
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    row: StepForm,
    count: Int,
    state: RecipeEditorState.Editing,
    actions: RecipeEditorActions,
    focusRequest: CardFocus?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAskRemove: () -> Unit,
) {
    val titleError = firstError(state, DraftField.StepTitle, index)
    val textError = firstError(state, DraftField.StepText, index)
    val rowError = titleError ?: textError

    val bringIntoView = remember { BringIntoViewRequester() }
    val textFocus = remember { FocusRequester() }
    LaunchedEffect(focusRequest?.token) {
        val request = focusRequest ?: return@LaunchedEffect
        // El foco va a «Qué se hace», que es lo primero que se escribe de un paso.
        if (request.focus) textFocus.requestFocus()
        bringIntoView.bringIntoView()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .fieldError(rowError != null, rowError.orEmpty()),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(
                    text = stringResource(R.string.editor_step_number, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MoveButtons(
                    enabled = !state.saving,
                    canMoveUp = index > 0,
                    canMoveDown = index < count - 1,
                    onUp = onMoveUp,
                    onDown = onMoveDown,
                    // Con algo escrito pide confirmación; vacío se quita directo.
                    onRemove = {
                        if (hasStepText(row)) onAskRemove() else actions.onRemoveStep(index)
                    },
                    itemName = stringResource(R.string.editor_step_number, index + 1),
                    removeLabel = stringResource(R.string.editor_remove_step, index + 1),
                )
            }

            OutlinedTextField(
                value = row.title,
                onValueChange = { actions.onStepTitleChange(index, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .fieldError(titleError != null, titleError.orEmpty()),
                readOnly = state.saving,
                label = { Text(stringResource(R.string.editor_step_title_label)) },
                isError = titleError != null,
                supportingText = if (titleError != null) {
                    { Text(titleError) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            )

            OutlinedTextField(
                value = row.text,
                onValueChange = { actions.onStepTextChange(index, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(textFocus)
                    .fieldError(textError != null, textError.orEmpty()),
                readOnly = state.saving,
                label = { Text(stringResource(R.string.editor_step_text_label)) },
                isError = textError != null,
                supportingText = if (textError != null) {
                    { Text(textError) }
                } else {
                    null
                },
                minLines = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            )
        }
    }
}

// ------------------------------------------------------------ etiquetas/categorías

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizersSection(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        val catalogs = state.catalogs
        if (catalogs.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.editor_catalogs_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (catalogs.problem != null) {
            ProblemBanner(
                problem = catalogs.problem,
                message = stringResource(R.string.editor_catalogs_failed),
                onRetry = actions.onRetryCatalogs,
                onOpenSettings = actions.onOpenSettings,
                // También aquí hay formulario abierto: ir a Ajustes pierde lo escrito.
                unauthorizedTextRes = R.string.problem_text_unauthorized_form,
            )
        }
        OrganizerChips(
            title = stringResource(R.string.editor_section_tags),
            choices = catalogs.tags,
            selected = state.form.tags,
            enabled = !state.saving,
            emptyText = if (catalogs.problem != null) {
                stringResource(R.string.editor_catalogs_failed)
            } else {
                stringResource(R.string.editor_tags_empty)
            },
            partialText = if (catalogs.tagsTruncated) {
                stringResource(R.string.editor_tags_partial)
            } else {
                null
            },
            onToggle = actions.onToggleTag,
        )
        OrganizerChips(
            title = stringResource(R.string.editor_section_categories),
            choices = catalogs.categories,
            selected = state.form.categories,
            enabled = !state.saving,
            emptyText = if (catalogs.problem != null) {
                stringResource(R.string.editor_catalogs_failed)
            } else {
                stringResource(R.string.editor_categories_empty)
            },
            partialText = if (catalogs.categoriesTruncated) {
                stringResource(R.string.editor_categories_partial)
            } else {
                null
            },
            onToggle = actions.onToggleCategory,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizerChips(
    title: String,
    choices: List<DraftOrganizer>,
    selected: List<DraftOrganizer>,
    enabled: Boolean,
    emptyText: String,
    partialText: String?,
    onToggle: (DraftOrganizer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SectionTitle(title)
        if (choices.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                for (choice in choices) {
                    val isSelected = selected.any { sameOrganizer(it, choice) }
                    FilterChip(
                        selected = isSelected,
                        onClick = { onToggle(choice) },
                        // De pie y con prisa: el chip no baja de 48 dp como el resto.
                        modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                        enabled = enabled,
                        label = { Text(choice.name) },
                        // Una marca además del color: el estado no depende solo del tono.
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            if (partialText != null) {
                Text(
                    text = partialText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ auxiliares

@Composable
private fun MoveButtons(
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
    itemName: String,
    removeLabel: String,
) {
    Row {
        IconButton(onClick = onUp, enabled = enabled && canMoveUp) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_up),
                contentDescription = stringResource(R.string.editor_move_up, itemName),
            )
        }
        IconButton(onClick = onDown, enabled = enabled && canMoveDown) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = stringResource(R.string.editor_move_down, itemName),
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.ic_remove),
                contentDescription = removeLabel,
            )
        }
    }
}

@Composable
private fun SaveBar(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        ) {
            Button(
                // Con una foto a medias, guardar cerraría el formulario y dejaría la
                // subida colgada: se espera y se explica debajo.
                onClick = actions.onSave,
                enabled = !state.saving && !state.photo.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (state.saving) BusyIndicator()
                Text(stringResource(if (state.saving) R.string.editor_saving else R.string.editor_save))
            }
            // Mientras se guarda, atrás y los campos no responden: se dice y TalkBack lo
            // lee, en vez de dejar la sensación de que la pantalla se ha colgado.
            if (state.saving) {
                Text(
                    text = stringResource(R.string.editor_saving_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = Spacing.small)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.photo.busy) {
                Text(
                    text = stringResource(R.string.editor_photo_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.small),
                )
            }
        }
    }
}

@Composable
private fun SavedContent(
    state: RecipeEditorState.Saved,
    actions: RecipeEditorActions,
    modifier: Modifier = Modifier,
) {
    val warning = state.warning
    if (warning == null) {
        LoadingBlock(
            text = stringResource(R.string.editor_saved_opening),
            modifier = modifier,
        )
    } else {
        MessageBlock(
            title = stringResource(R.string.editor_saved_warning_title),
            text = stringResource(R.string.editor_saved_warning_text) + " " + stringResource(errorTextRes(warning)),
            primaryLabel = stringResource(R.string.editor_saved_back),
            onPrimary = actions.onReviewList,
            secondaryLabel = if (state.created) stringResource(R.string.editor_saved_open) else null,
            onSecondary = { actions.onOpenSavedRecipe(state.slug) },
            modifier = modifier,
        )
    }
}

@Composable
private fun DiscardDialog(
    createdIncomplete: Boolean,
    replacesDraft: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = {
            Text(
                stringResource(
                    when {
                        createdIncomplete -> R.string.editor_discard_text_created
                        replacesDraft -> R.string.editor_discard_text_draft
                        else -> R.string.editor_discard_text
                    },
                ),
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_discard_keep))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_discard_confirm))
            }
        },
    )
}

/**
 * «Recuperar» cuando el formulario ya tiene texto: se va a sustituir tal cual.
 * Sin este aviso, un toque perdido tira lo escrito (o la receta en edición).
 */
@Composable
private fun RecoverDraftDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_draft_recover_confirm_title)) },
        text = { Text(stringResource(R.string.editor_draft_recover_confirm_text)) },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_dialog_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_draft_recover_confirm))
            }
        },
    )
}

/** «Descartar» el borrador borra de verdad: se pregunta antes, como todo lo irreversible. */
@Composable
private fun DiscardDraftDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_draft_discard_confirm_title)) },
        text = { Text(stringResource(R.string.editor_draft_discard_confirm_text)) },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_dialog_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_draft_discard_confirm))
            }
        },
    )
}

/**
 * «Guardar otra vez» cuando el servidor no ha confirmado la creación. Repetir a
 * ciegas deja dos recetas: primero se pregunta, y el aviso de arriba sigue puesto.
 */
@Composable
private fun DuplicateSaveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_maybe_created_dialog_title)) },
        text = { Text(stringResource(R.string.editor_maybe_created_dialog_text)) },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_discard_keep))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_maybe_created_confirm))
            }
        },
    )
}

/**
 * «¿Quitar este ingrediente?» / «¿Quitar este paso?». Solo cuando la fila tiene algo
 * escrito: un toque perdido en la papelera no puede tirar un paso largo sin querer.
 * Vacía se quita directo, sin preguntar.
 */
@Composable
private fun RemoveRowDialog(isStep: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isStep) R.string.editor_remove_step_dialog_title else R.string.editor_remove_ingredient_dialog_title,
                ),
            )
        },
        text = { Text(stringResource(R.string.editor_remove_dialog_text)) },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_remove_dialog_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.editor_remove_dialog_confirm))
            }
        },
    )
}

// ------------------------------------------------------------ errores de campo

@Composable
private fun firstError(state: RecipeEditorState.Editing, field: DraftField, index: Int?): String? {
    val draftError = state.errors.firstOrNull { it.field == field && it.index == index }
    if (draftError != null) return draftErrorText(draftError)
    val detail = state.serverDetails.firstOrNull {
        it.field == field && (index == null || it.index == index)
    }
    return detail?.message
}

@Composable
private fun draftErrorText(error: DraftError): String = when (error.field) {
    DraftField.Name -> when (error.kind) {
        DraftErrorKind.Required -> stringResource(R.string.editor_error_name_required)
        DraftErrorKind.TooLong -> stringResource(R.string.editor_error_name_too_long)
        else -> genericErrorText(error.kind)
    }
    DraftField.Description -> when (error.kind) {
        DraftErrorKind.TooLong -> stringResource(R.string.editor_error_description_too_long)
        else -> genericErrorText(error.kind)
    }
    DraftField.Servings -> when (error.kind) {
        DraftErrorKind.OutOfRange -> stringResource(R.string.editor_error_servings_range)
        DraftErrorKind.NotFinite, DraftErrorKind.Invalid ->
            stringResource(R.string.editor_error_servings_invalid)
        else -> genericErrorText(error.kind)
    }
    DraftField.TotalTime, DraftField.PrepTime, DraftField.CookTime, DraftField.PerformTime ->
        if (error.kind == DraftErrorKind.TooLong) {
            stringResource(R.string.editor_error_time_too_long)
        } else {
            genericErrorText(error.kind)
        }
    DraftField.Ingredients ->
        if (error.kind == DraftErrorKind.TooMany) {
            stringResource(R.string.editor_error_ingredients_too_many)
        } else {
            genericErrorText(error.kind)
        }
    DraftField.IngredientQuantity -> when (error.kind) {
        DraftErrorKind.OutOfRange -> stringResource(R.string.editor_error_ingredient_quantity_range)
        DraftErrorKind.NotFinite, DraftErrorKind.Invalid ->
            stringResource(R.string.editor_error_ingredient_quantity_invalid)
        else -> genericErrorText(error.kind)
    }
    DraftField.IngredientNote -> when (error.kind) {
        DraftErrorKind.Required -> stringResource(R.string.editor_error_ingredient_note_required)
        DraftErrorKind.TooLong -> stringResource(R.string.editor_error_ingredient_text_too_long)
        else -> genericErrorText(error.kind)
    }
    DraftField.IngredientTitle ->
        if (error.kind == DraftErrorKind.TooLong) {
            stringResource(R.string.editor_error_ingredient_text_too_long)
        } else {
            genericErrorText(error.kind)
        }
    DraftField.IngredientUnit -> when (error.kind) {
        DraftErrorKind.Required -> stringResource(R.string.editor_error_ingredient_unit_required)
        DraftErrorKind.Invalid -> stringResource(R.string.editor_error_ingredient_unit_invalid)
        DraftErrorKind.TooLong -> stringResource(R.string.editor_error_ingredient_unit_too_long)
        else -> genericErrorText(error.kind)
    }
    DraftField.IngredientFood -> when (error.kind) {
        DraftErrorKind.Required -> stringResource(R.string.editor_error_ingredient_food_required)
        DraftErrorKind.Invalid -> stringResource(R.string.editor_error_ingredient_food_invalid)
        DraftErrorKind.TooLong -> stringResource(R.string.editor_error_ingredient_food_too_long)
        else -> genericErrorText(error.kind)
    }
    DraftField.Steps ->
        if (error.kind == DraftErrorKind.TooMany) {
            stringResource(R.string.editor_error_steps_too_many)
        } else {
            genericErrorText(error.kind)
        }
    DraftField.StepTitle ->
        if (error.kind == DraftErrorKind.TooLong) {
            stringResource(R.string.editor_error_step_title_too_long)
        } else {
            genericErrorText(error.kind)
        }
    DraftField.StepText ->
        if (error.kind == DraftErrorKind.TooLong) {
            stringResource(R.string.editor_error_step_text_too_long)
        } else {
            genericErrorText(error.kind)
        }
    DraftField.Tags -> when (error.kind) {
        DraftErrorKind.TooMany -> stringResource(R.string.editor_error_tags_too_many)
        DraftErrorKind.TooLong -> stringResource(R.string.editor_error_tag_too_long)
        DraftErrorKind.Duplicate -> stringResource(R.string.editor_error_tag_duplicate)
        DraftErrorKind.Required -> stringResource(R.string.editor_error_tag_required)
        DraftErrorKind.Invalid -> stringResource(R.string.editor_error_tag_invalid)
        else -> genericErrorText(error.kind)
    }
    DraftField.Categories -> when (error.kind) {
        DraftErrorKind.TooMany -> stringResource(R.string.editor_error_categories_too_many)
        DraftErrorKind.TooLong -> stringResource(R.string.editor_error_category_too_long)
        DraftErrorKind.Duplicate -> stringResource(R.string.editor_error_category_duplicate)
        DraftErrorKind.Required -> stringResource(R.string.editor_error_category_required)
        DraftErrorKind.Invalid -> stringResource(R.string.editor_error_category_invalid)
        else -> genericErrorText(error.kind)
    }
    DraftField.Notes, DraftField.NoteTitle, DraftField.NoteText -> genericErrorText(error.kind)
}

@Composable
private fun genericErrorText(kind: DraftErrorKind): String = when (kind) {
    DraftErrorKind.Required -> stringResource(R.string.editor_error_required)
    DraftErrorKind.TooLong -> stringResource(R.string.editor_error_too_long)
    DraftErrorKind.TooMany -> stringResource(R.string.editor_error_too_many)
    DraftErrorKind.NotFinite -> stringResource(R.string.editor_error_not_finite)
    DraftErrorKind.OutOfRange -> stringResource(R.string.editor_error_out_of_range)
    DraftErrorKind.Invalid -> stringResource(R.string.editor_error_invalid)
    DraftErrorKind.Duplicate -> stringResource(R.string.editor_error_duplicate)
    DraftErrorKind.NotEditable -> stringResource(R.string.editor_error_not_editable)
}

// -------------------------------------------------------------------- previews

/** Vista previa de la foto en el formulario: se ve, pero no se come la pantalla. */
private val PhotoPreviewHeight = 160.dp

private fun previewActions() = RecipeEditorActions()

@Preview(showBackground = true)
@Composable
private fun RecipeEditorCreatePreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(mode = EditorMode.Create),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorEditPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeEditorEditDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeEditorEditLargeTextPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorErrorsPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                errors = listOf(
                    DraftError(DraftField.Name, DraftErrorKind.Required),
                    DraftError(DraftField.IngredientQuantity, DraftErrorKind.Invalid, 0),
                ),
                serverDetails = listOf(
                    ValidationDetail(DraftField.Steps, 1, "El paso tiene un formato que no se acepta."),
                ),
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorSavingPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(saving = true),
            actions = previewActions(),
        )
    }
}

/** Sesión caducada al guardar: el aviso dice que ir a Ajustes cierra el formulario. */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeEditorUnauthorizedPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState().copy(saveProblem = ConnectionProblem.Unauthorized),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorDiscardPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(showDiscardConfirm = true),
            actions = previewActions(),
        )
    }
}

/** «Salir y guardar» con la oferta de borrador viva: el texto avisa de que la sustituye. */
@Preview(showBackground = true)
@Composable
private fun RecipeEditorDiscardReplacingDraftPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                showDiscardConfirm = true,
                recoveredDraft = RecipePreviewData.draft,
                recoveredAtText = "hoy a las 20:14",
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorRecoveredDraftPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                recoveredDraft = RecipePreviewData.draft,
                recoveredAtText = "hoy a las 20:14",
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeEditorRecoveredDraftDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                recoveredDraft = RecipePreviewData.draft,
                recoveredAtText = "ayer a las 9:05",
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeEditorRecoveredDraftLargeTextPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                mode = EditorMode.Create,
                recoveredDraft = RecipePreviewData.draft,
                recoveredAtText = "el 5 de noviembre de 2026",
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorRecoveredDraftInvalidPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                recoveredDraft = RecipePreviewData.draft,
                recoveredAtText = "hoy a las 20:14",
                draftInvalid = true,
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorRecoverDraftConfirmPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                recoveredDraft = RecipePreviewData.draft,
                recoveredAtText = "hoy a las 20:14",
                showDraftConfirm = true,
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorDiscardDraftConfirmPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                recoveredDraft = RecipePreviewData.draft,
                recoveredAtText = "hoy a las 20:14",
                showDraftDiscardConfirm = true,
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorLoadFailedPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipeEditorState.LoadFailed(
                EditorMode.Edit("arroz-con-verduras"),
                ConnectionProblem.NoNetwork,
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorSavedWarningPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipeEditorState.Saved(
                slug = "arroz-con-verduras",
                warning = ConnectionProblem.ServerNotResponding,
                created = true,
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorNotFoundPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipeEditorState.NotFound,
            actions = previewActions(),
        )
    }
}

// Foto: sin foto, elegida y ya subida (el «ya está» se dice en el aviso de abajo, no
// dentro de la sección), subiendo y con un error.

@Preview(showBackground = true)
@Composable
private fun RecipeEditorPhotoEmptyPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(photo = PhotoState()),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorPhotoChosenPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                photo = PhotoState(
                    preview = RecipePreviewData.photoPreviewBitmap(),
                    done = true,
                    uploadedCount = 1,
                ),
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeEditorPhotoUploadingPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                photo = PhotoState(
                    preview = RecipePreviewData.photoPreviewBitmap(),
                    uploading = true,
                ),
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorPhotoErrorPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                photo = PhotoState(problem = PhotoProblem.HasMetadata),
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeEditorPhotoNetworkErrorPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                photo = PhotoState(
                    preview = RecipePreviewData.photoPreviewBitmap(),
                    problem = PhotoProblem.Network(ConnectionProblem.NoNetwork),
                ),
            ),
            actions = previewActions(),
        )
    }
}

// «Puede que ya se haya creado»: el aviso y el diálogo de repetir.

@Preview(showBackground = true)
@Composable
private fun RecipeEditorMaybeCreatedPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                mode = EditorMode.Create,
                maybeCreated = true,
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorDuplicateConfirmPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(
                mode = EditorMode.Create,
                maybeCreated = true,
                showDuplicateConfirm = true,
            ),
            actions = previewActions(),
        )
    }
}

// Quitar con texto escrito: el paso y el ingrediente piden confirmación.
// Solo se ven las primeras etiquetas y categorías: el formulario lo dice.

@Preview(showBackground = true)
@Composable
private fun RecipeEditorCatalogsPartialPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = RecipePreviewData.editorState(catalogsTruncated = true),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorRemoveIngredientPreview() {
    GastronomiconTheme {
        RemoveRowDialog(isStep = false, onConfirm = {}, onDismiss = {})
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeEditorRemoveStepPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        RemoveRowDialog(isStep = true, onConfirm = {}, onDismiss = {})
    }
}

// Selector de unidad y de alimento: resultados, espera, nada encontrado, sin
// conexión y el bloque de crear (también con «puede que ya exista»).

/** Entradas inventadas del catálogo: ids con forma de UUID, ningún dato real. */
private fun previewCatalogEntries(): List<CatalogEntry> = listOf(
    CatalogEntry("11111111-1111-4111-8111-111111111111", "gramos", "g"),
    CatalogEntry("22222222-2222-4222-8222-222222222222", "cucharadas", "cdas"),
    CatalogEntry("33333333-3333-4333-8333-333333333333", "mililitros", "ml"),
    CatalogEntry("44444444-4444-4444-8444-444444444444", "pizca"),
)

private fun previewPicker(
    kind: IngredientCatalogKind = IngredientCatalogKind.Unit,
    query: String = "",
    itemsQuery: String = query,
    items: List<CatalogEntry> = previewCatalogEntries(),
    loading: Boolean = false,
    problem: ConnectionProblem? = null,
    truncated: Boolean = false,
    loaded: Boolean = true,
    creating: Boolean = false,
    createProblem: ConnectionProblem? = null,
    createRejected: Boolean = false,
    createErrors: List<DraftError> = emptyList(),
    maybeCreated: Boolean = false,
    createdNotApplied: Boolean = false,
): RecipeEditorState.Editing = RecipePreviewData.editorState().copy(
    catalogPicker = CatalogPicker(
        kind = kind,
        index = 0,
        query = query,
        items = items,
        itemsQuery = itemsQuery,
        loading = loading,
        problem = problem,
        truncated = truncated,
        loaded = loaded,
        creating = creating,
        createProblem = createProblem,
        createRejected = createRejected,
        createErrors = createErrors,
        maybeCreated = maybeCreated,
        createdNotApplied = createdNotApplied,
    ),
)

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeEditorUnitPickerPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(state = previewPicker(query = "gra"), actions = previewActions())
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeEditorFoodPickerDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        RecipeEditorScreen(
            state = previewPicker(kind = IngredientCatalogKind.Food, query = "hari", truncated = true),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeEditorUnitPickerLargeTextPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(createErrors = listOf(DraftError(DraftField.IngredientUnit, DraftErrorKind.TooLong))),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorPickerLoadingPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(items = emptyList(), loading = true, loaded = false),
            actions = previewActions(),
        )
    }
}

/** Se está buscando otra cosa: los resultados anteriores siguen a la vista, no en blanco. */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeEditorPickerRefreshingPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(query = "gram", itemsQuery = "", loading = true),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorPickerEmptyPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(query = "kombu", items = emptyList()),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorPickerNoNetworkPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(items = emptyList(), problem = ConnectionProblem.NoNetwork),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeEditorPickerMaybeCreatedPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(maybeCreated = true, query = "harina"),
            actions = previewActions(),
        )
    }
}

/** Se creó en el servidor pero la fila del ingrediente ya no estaba: se avisa. */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeEditorPickerCreatedNotAppliedPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(createdNotApplied = true, query = "harina"),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeEditorPickerCreateFailedPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        RecipeEditorScreen(
            state = previewPicker(
                kind = IngredientCatalogKind.Food,
                createRejected = true,
                items = emptyList(),
            ),
            actions = previewActions(),
        )
    }
}

/** Fallo al crear sin rechazo del nombre: se pide mirar el catálogo antes de repetir. */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeEditorPickerCreateProblemPreview() {
    GastronomiconTheme {
        RecipeEditorScreen(
            state = previewPicker(
                query = "harina de fuerza",
                items = emptyList(),
                createProblem = ConnectionProblem.NoNetwork,
            ),
            actions = previewActions(),
        )
    }
}

