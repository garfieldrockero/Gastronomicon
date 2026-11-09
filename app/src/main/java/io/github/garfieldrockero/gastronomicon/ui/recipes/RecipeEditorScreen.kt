package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBlock
import io.github.garfieldrockero.gastronomicon.ui.components.StatusText
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(titleRes(state)),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions.onRequestClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            val editing = state as? RecipeEditorState.Editing
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
                modifier = contentModifier,
            )
            is RecipeEditorState.Saved -> SavedContent(
                state = state,
                actions = actions,
                modifier = contentModifier,
            )
        }
    }

    val editing = state as? RecipeEditorState.Editing
    if (editing != null && editing.showDiscardConfirm) {
        DiscardDialog(
            createdIncomplete = editing.createdIncomplete,
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

@Composable
private fun EditorContent(
    state: RecipeEditorState.Editing,
    actions: RecipeEditorActions,
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
        IngredientsSection(state = state, actions = actions)
        StepsSection(state = state, actions = actions)
        PhotoSection(state = state, actions = actions)
        OrganizersSection(state = state, actions = actions)
    }
}

@Composable
private fun EditorMessages(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next,
            ),
        )

        TimeField(
            value = form.totalTime,
            onValueChange = actions.onTotalTimeChange,
            label = stringResource(R.string.editor_total_time_label),
            error = firstError(state, DraftField.TotalTime, null),
            readOnly = state.saving,
        )
        TimeField(
            value = form.prepTime,
            onValueChange = actions.onPrepTimeChange,
            label = stringResource(R.string.editor_prep_time_label),
            error = firstError(state, DraftField.PrepTime, null),
            readOnly = state.saving,
        )
        TimeField(
            value = form.cookTime,
            onValueChange = actions.onCookTimeChange,
            label = stringResource(R.string.editor_cook_time_label),
            error = firstError(state, DraftField.CookTime, null),
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
        } else {
            { Text(stringResource(R.string.editor_time_supporting)) }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
}

// ------------------------------------------------------------------- foto

/**
 * Foto de la receta, solo en modo edición: una receta nueva se guarda antes de
 * tener foto. Los botones abren la galería o la cámara del sistema; la foto se
 * recodifica sin datos de ubicación y se sube sola, con progreso y aviso.
 *
 * Sin Uri, sin rutas y sin bytes en el estado: la vista previa ya es un
 * `Bitmap` pequeño y recodificado ([PhotoState.preview]).
 */
@Composable
private fun PhotoSection(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    if (state.mode is EditorMode.Create) {
        Text(
            text = stringResource(R.string.editor_photo_hint_create),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

        if (photo.done && problem == null) {
            StatusText(
                text = stringResource(R.string.editor_photo_done),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.editor_photo_done_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            stringResource(R.string.problem_text_unauthorized)
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
private fun IngredientsSection(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.editor_section_ingredients))
        if (state.form.ingredients.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_ingredients_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.form.ingredients.forEachIndexed { index, row ->
            IngredientCard(
                index = index,
                row = row,
                count = state.form.ingredients.size,
                state = state,
                actions = actions,
            )
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
) {
    val quantityError = firstError(state, DraftField.IngredientQuantity, index)
    val noteError = firstError(state, DraftField.IngredientNote, index)
    val fixedError = firstError(state, DraftField.IngredientUnit, index)
        ?: firstError(state, DraftField.IngredientFood, index)
        ?: firstError(state, DraftField.IngredientTitle, index)
    val rowError = quantityError ?: noteError ?: fixedError

    Surface(
        modifier = Modifier
            .fillMaxWidth()
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
                    index = index,
                    count = count,
                    enabled = !state.saving,
                    up = actions.onMoveIngredientUp,
                    down = actions.onMoveIngredientDown,
                    remove = actions.onRemoveIngredient,
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
                    .fieldError(quantityError != null, quantityError.orEmpty()),
                readOnly = state.saving,
                label = { Text(stringResource(R.string.editor_ingredient_quantity_label)) },
                isError = quantityError != null,
                supportingText = if (quantityError != null) {
                    { Text(quantityError) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            if (row.unit.isNotEmpty() || row.food.isNotEmpty()) {
                val fixed = listOf(row.unit, row.food).filter { it.isNotEmpty() }.joinToString(" ")
                Text(
                    text = stringResource(R.string.editor_ingredient_fixed, fixed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.editor_ingredient_fixed_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (fixedError != null) {
                Text(
                    text = fixedError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- pasos

@Composable
private fun StepsSection(state: RecipeEditorState.Editing, actions: RecipeEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.editor_section_steps))
        if (state.form.steps.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_steps_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.form.steps.forEachIndexed { index, row ->
            StepCard(
                index = index,
                row = row,
                count = state.form.steps.size,
                state = state,
                actions = actions,
            )
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
) {
    val titleError = firstError(state, DraftField.StepTitle, index)
    val textError = firstError(state, DraftField.StepText, index)
    val rowError = titleError ?: textError

    Surface(
        modifier = Modifier
            .fillMaxWidth()
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
                    index = index,
                    count = count,
                    enabled = !state.saving,
                    up = actions.onMoveStepUp,
                    down = actions.onMoveStepDown,
                    remove = actions.onRemoveStep,
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = row.text,
                onValueChange = { actions.onStepTextChange(index, it) },
                modifier = Modifier
                    .fillMaxWidth()
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
                    FilterChip(
                        selected = selected.any { sameOrganizer(it, choice) },
                        onClick = { onToggle(choice) },
                        enabled = enabled,
                        label = { Text(choice.name) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ auxiliares

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun MoveButtons(
    index: Int,
    count: Int,
    enabled: Boolean,
    up: (Int) -> Unit,
    down: (Int) -> Unit,
    remove: (Int) -> Unit,
    itemName: String,
    removeLabel: String,
) {
    Row {
        IconButton(onClick = { up(index) }, enabled = enabled && index > 0) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_up),
                contentDescription = stringResource(R.string.editor_move_up, itemName),
            )
        }
        IconButton(onClick = { down(index) }, enabled = enabled && index < count - 1) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = stringResource(R.string.editor_move_down, itemName),
            )
        }
        IconButton(onClick = { remove(index) }, enabled = enabled) {
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
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = {
            Text(
                stringResource(
                    if (createdIncomplete) R.string.editor_discard_text_created else R.string.editor_discard_text,
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
    DraftField.IngredientUnit, DraftField.IngredientFood ->
        if (error.kind == DraftErrorKind.NotEditable) {
            stringResource(R.string.editor_error_ingredient_not_editable)
        } else {
            genericErrorText(error.kind)
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

// Foto: sin foto, elegida y ya subida, subiendo y con un error.

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
