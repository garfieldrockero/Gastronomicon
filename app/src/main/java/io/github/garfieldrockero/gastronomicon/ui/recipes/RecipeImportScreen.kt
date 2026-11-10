package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.client.AiCapabilities
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.platform.PhotoPickResult
import io.github.garfieldrockero.gastronomicon.platform.PhotoSource
import io.github.garfieldrockero.gastronomicon.platform.rememberPhotoPicker
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.fieldError
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Importar una receta de tres formas: pegando la dirección de una página (la
 * descarga tu Mealie), con un archivo o texto que se manda al servidor (JSON de
 * `schema.org/Recipe` o el HTML de la receta) o con la IA que tenga configurada
 * tu Mealie (un enlace, un texto y fotos de la galería o de la cámara del
 * sistema). Sin lógica: pinta [RecipeImportUiState]; el núcleo decide qué vale y
 * qué no.
 *
 * El progreso puede durar hasta dos minutos y, con IA, hasta tres; se puede
 * cancelar. Tras un tiempo de espera agotado, un corte o una cancelación no se
 * reintenta solo: se avisa de que la receta puede existir ya y se ofrece revisar
 * la lista.
 *
 * El texto pegado, el enlace y las fotos de la IA viven en el ViewModel: girar el
 * móvil no los pierde, y se descartan al cerrar la pantalla o si el sistema mata
 * la app, a propósito (el texto lleva la receta entera y no es un secreto que
 * deba quedar en disco). Nada de eso pasa por `rememberSaveable`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeImportScreen(
    state: RecipeImportUiState,
    onBack: () -> Unit,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onDataTextChange: (String) -> Unit,
    onImportData: () -> Unit,
    onFilePicked: (Uri) -> Unit,
    onRemoveFile: () -> Unit,
    onCancel: () -> Unit,
    onReviewList: () -> Unit,
    onOpenSettings: () -> Unit,
    onAiLinkChange: (String) -> Unit,
    onAiTextChange: (String) -> Unit,
    onAiPhotoPicked: (Uri, Boolean) -> Unit,
    onAiPhotoProblem: (AiProblem) -> Unit,
    onRemoveAiPhoto: (Int) -> Unit,
    onImportAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.running) { if (!state.running) confirmLeave = false }
    // Lo que se perdería al salir: la receta pegada, el archivo ya leído o lo que
    // haya en la sección de IA (hasta 1 MiB de texto y fotos ya preparadas, sin
    // forma de recuperarlo; no hay borrador). También cuenta una foto que se está
    // preparando: si no, salir en ese instante tiraría el trabajo sin preguntar.
    // Solo cuenta mientras se ve el formulario: después de importar, el contenido
    // ya no está en juego.
    val formVisible = state.importedSlug == null && !state.maybeCreated && !state.cancelled
    val hasContent = formVisible &&
        (state.dataText.isNotBlank() || state.fileLoaded || state.hasAiContent ||
            state.aiPreparingPhoto)
    val back = { if (state.running || hasContent) confirmLeave = true else onBack() }
    BackHandler { back() }
    if (confirmLeave && (state.running || hasContent)) {
        // Con una importación en marcha se avisa de que el servidor puede acabar
        // igualmente; con contenido sin enviar no hay nada que esperar y se sale.
        val leavingRunning = state.running
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = {
                Text(
                    stringResource(
                        if (leavingRunning) {
                            R.string.import_leave_title
                        } else {
                            R.string.import_leave_discard_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (leavingRunning) {
                            R.string.import_leave_text
                        } else {
                            R.string.import_leave_discard_text
                        },
                    ),
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmLeave = false },
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(
                        stringResource(
                            if (leavingRunning) {
                                R.string.import_leave_keep
                            } else {
                                R.string.import_leave_discard_keep
                            },
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        if (leavingRunning) onCancel() else onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(
                        stringResource(
                            if (leavingRunning) {
                                R.string.import_leave_confirm
                            } else {
                                R.string.import_leave_discard_confirm
                            },
                        ),
                    )
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.import_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when {
            state.importedSlug != null -> LoadingBlock(
                text = stringResource(R.string.import_imported),
                modifier = contentModifier,
            )
            state.maybeCreated || state.cancelled -> MessageBlock(
                title = stringResource(
                    if (state.cancelled) R.string.import_cancelled_title else R.string.import_maybe_created_title,
                ),
                text = stringResource(
                    if (state.cancelled) R.string.import_cancelled_text else R.string.import_maybe_created_text,
                ),
                primaryLabel = stringResource(R.string.import_review_list),
                onPrimary = onReviewList,
                secondaryLabel = stringResource(R.string.import_back),
                onSecondary = onBack,
                modifier = contentModifier,
            )
            else -> ImportForm(
                state = state,
                onUrlChange = onUrlChange,
                onImport = onImport,
                onDataTextChange = onDataTextChange,
                onImportData = onImportData,
                onFilePicked = onFilePicked,
                onRemoveFile = onRemoveFile,
                onCancel = onCancel,
                onOpenSettings = onOpenSettings,
                onAiLinkChange = onAiLinkChange,
                onAiTextChange = onAiTextChange,
                onAiPhotoPicked = onAiPhotoPicked,
                onAiPhotoProblem = onAiPhotoProblem,
                onRemoveAiPhoto = onRemoveAiPhoto,
                onImportAi = onImportAi,
                modifier = contentModifier,
            )
        }
    }
}

@Composable
private fun ImportForm(
    state: RecipeImportUiState,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onDataTextChange: (String) -> Unit,
    onImportData: () -> Unit,
    onFilePicked: (Uri) -> Unit,
    onRemoveFile: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onAiLinkChange: (String) -> Unit,
    onAiTextChange: (String) -> Unit,
    onAiPhotoPicked: (Uri, Boolean) -> Unit,
    onAiPhotoProblem: (AiProblem) -> Unit,
    onRemoveAiPhoto: (Int) -> Unit,
    onImportAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(Spacing.large),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
    ) {
        Text(
            text = stringResource(R.string.import_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        UrlImportSection(
            url = state.url,
            invalid = state.invalidUrl,
            running = state.runningSource == ImportSource.Url,
            busy = state.busy,
            problem = if (state.problemSource == ImportSource.Url) state.problem else null,
            rejected = state.rejectedSource == ImportSource.Url,
            details = state.details,
            onUrlChange = onUrlChange,
            onImport = onImport,
            onCancel = onCancel,
            onOpenSettings = onOpenSettings,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DataImportSection(
            state = state,
            onDataTextChange = onDataTextChange,
            onImportData = onImportData,
            onFilePicked = onFilePicked,
            onRemoveFile = onRemoveFile,
            onCancel = onCancel,
            onOpenSettings = onOpenSettings,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        AiImportSection(
            state = state,
            onAiLinkChange = onAiLinkChange,
            onAiTextChange = onAiTextChange,
            onAiPhotoPicked = onAiPhotoPicked,
            onAiPhotoProblem = onAiPhotoProblem,
            onRemoveAiPhoto = onRemoveAiPhoto,
            onImportAi = onImportAi,
            onCancel = onCancel,
            onOpenSettings = onOpenSettings,
        )
    }
}

/** La dirección de una página: la descarga y la importa tu Mealie. */
@Composable
private fun UrlImportSection(
    url: String,
    invalid: Boolean,
    running: Boolean,
    busy: Boolean,
    problem: ConnectionProblem?,
    rejected: Boolean,
    details: List<ValidationDetail>,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val importAndHide = {
        keyboard?.hide()
        onImport()
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        SectionTitle(stringResource(R.string.import_url_section))
        val invalidText = stringResource(R.string.import_url_invalid)
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier
                .fillMaxWidth()
                .fieldError(invalid, invalidText),
            readOnly = busy,
            label = { Text(stringResource(R.string.import_url_label)) },
            isError = invalid,
            supportingText = {
                Text(
                    stringResource(
                        if (invalid) R.string.import_url_invalid else R.string.import_url_supporting,
                    ),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (!busy) importAndHide() }),
        )

        if (running) {
            ImportRunning(
                onCancel = onCancel,
                label = stringResource(R.string.import_running),
                hint = stringResource(R.string.import_running_hint),
            )
        } else {
            Button(
                onClick = importAndHide,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.import_action))
            }
        }

        if (problem != null) {
            ProblemBanner(
                problem = problem,
                message = stringResource(R.string.import_failed_title),
                onRetry = importAndHide,
                onOpenSettings = onOpenSettings,
                // La dirección escrita se pierde si se va a Ajustes: el aviso lo dice.
                unauthorizedTextRes = R.string.problem_text_unauthorized_form,
            )
        }

        if (rejected) {
            NoticeBlock(
                text = stringResource(R.string.import_rejected),
                details = details,
            )
        }
    }
}

/**
 * Un archivo elegido con el selector del sistema o el texto pegado a mano. El
 * archivo se manda tal cual: aquí no se interpreta ni se sanea (lo hace Mealie).
 *
 * El contenido del archivo no se pinta en el campo —puede ocupar hasta 1 MiB—:
 * se enseña su tamaño y un botón para quitarlo. Mientras hay uno cargado, el
 * campo está apagado para no dejar dudas de qué se va a enviar.
 */
@Composable
private fun DataImportSection(
    state: RecipeImportUiState,
    onDataTextChange: (String) -> Unit,
    onImportData: () -> Unit,
    onFilePicked: (Uri) -> Unit,
    onRemoveFile: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // En una preview no hay registro de resultados de actividad y el selector no
    // puede registrarse: se pinta igual, pero sin abrir nada.
    val inPreview = LocalInspectionMode.current
    val pickFile = if (inPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFilePicked(uri)
        }
    }

    val running = state.runningSource == ImportSource.Data
    val busy = state.busy
    val loadedBytes = state.loadedBytes
    val importAndHide = {
        keyboard?.hide()
        onImportData()
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.import_data_section))
        Text(
            text = stringResource(R.string.import_data_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = {
                // Si el campo de pegar tenía el foco, el selector del sistema salía
                // con el teclado detrás y al volver podía reaparecer.
                keyboard?.hide()
                pickFile?.launch(importMimeTypes)
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.import_data_pick_file))
        }

        if (state.readingFile) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.medium))
                Text(
                    text = stringResource(R.string.import_data_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loadedBytes != null) {
            val removeDescription = stringResource(R.string.import_data_file_remove_description)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(start = Spacing.medium, end = Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.import_data_file_loaded,
                            (loadedBytes + 1023) / 1024,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onRemoveFile,
                        enabled = !busy,
                        modifier = Modifier
                            .heightIn(min = Sizes.MinTouchTarget)
                            .semantics { contentDescription = removeDescription },
                    ) {
                        Text(stringResource(R.string.import_data_file_remove))
                    }
                }
            }
        }

        // El valor viaja por la sobrecarga de String y vive en el ViewModel: no
        // pasa por el estado guardado. No migrar a `rememberTextFieldState` ni a
        // `rememberSaveable`: hasta 1 MiB acabaría en el Bundle.
        OutlinedTextField(
            value = state.dataText,
            onValueChange = onDataTextChange,
            modifier = Modifier.fillMaxWidth(),
            readOnly = busy || loadedBytes != null,
            label = { Text(stringResource(R.string.import_data_text_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (loadedBytes != null) {
                            R.string.import_data_file_only
                        } else {
                            R.string.import_data_text_supporting
                        },
                    ),
                )
            },
            minLines = 5,
            maxLines = 10,
        )

        val dataProblem = state.fileProblem ?: state.dataProblem
        if (dataProblem != null) {
            NoticeBlock(
                text = stringResource(dataProblemTextRes(dataProblem)),
                details = emptyList(),
            )
        }

        if (running) {
            ImportRunning(
                onCancel = onCancel,
                label = stringResource(R.string.import_running),
                hint = stringResource(R.string.import_running_hint),
            )
        } else {
            Button(
                onClick = importAndHide,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                // Dice qué se manda: sin archivo cargado se manda el texto pegado,
                // aunque el último aviso hable de un archivo que no se pudo leer.
                Text(
                    stringResource(
                        if (loadedBytes != null) {
                            R.string.import_data_action_file
                        } else {
                            R.string.import_data_action_text
                        },
                    ),
                )
            }
        }

        val problem = if (state.problemSource == ImportSource.Data) state.problem else null
        if (problem != null) {
            ProblemBanner(
                problem = problem,
                message = stringResource(R.string.import_failed_title),
                onRetry = importAndHide,
                onOpenSettings = onOpenSettings,
                // Lo pegado o el archivo cargado se pierde si se va a Ajustes.
                unauthorizedTextRes = R.string.problem_text_unauthorized_form,
            )
        }

        if (state.rejectedSource == ImportSource.Data) {
            NoticeBlock(
                text = stringResource(R.string.import_data_rejected),
                details = state.details,
            )
        }
    }
}

/**
 * La tercera vía: un enlace, un texto y/o fotos que interpreta la IA que tenga
 * configurada tu Mealie. La IA la pone y la paga el servidor; la app solo manda
 * el contenido a ESE servidor.
 *
 * - Lo que se escribe vive en el ViewModel y **nunca** en `rememberSaveable`.
 * - Las fotos salen de la galería o de la cámara del sistema (sin permisos, ver
 *   `rememberPhotoPicker`), se recodifican sin EXIF en el ViewModel y aquí solo se
 *   pinta una miniatura pequeña con su «Quitar foto N».
 * - Las capacidades son tres (`AiCapabilities`, del núcleo): sin proveedor de
 *   **imagen** la zona de fotos se apaga con su motivo; sin proveedor de **audio**
 *   se avisa junto al enlace de que los vídeos pueden no funcionar, pero el enlace
 *   se sigue admitiendo porque la app no distingue un vídeo de una página web.
 * - Si el servidor ha dicho que no tiene IA y no hay nada escrito, la sección se
 *   queda en título y aviso, remitiendo a las otras dos vías. Si el «no hay IA»
 *   llega tarde, con contenido ya dentro, se avisa pero se deja intentarlo: el
 *   servidor contestará con su error, y lo escrito no se queda encerrado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiImportSection(
    state: RecipeImportUiState,
    onAiLinkChange: (String) -> Unit,
    onAiTextChange: (String) -> Unit,
    onAiPhotoPicked: (Uri, Boolean) -> Unit,
    onAiPhotoProblem: (AiProblem) -> Unit,
    onRemoveAiPhoto: (Int) -> Unit,
    onImportAi: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // En una preview no hay registro de resultados de actividad: los selectores se
    // pintan igual, pero sin abrir nada.
    val inPreview = LocalInspectionMode.current
    val picker = if (inPreview) {
        null
    } else {
        rememberPhotoPicker { result ->
            when (result) {
                // La cámara deja un temporal que el ViewModel borra al recodificarlo.
                is PhotoPickResult.Picked ->
                    onAiPhotoPicked(result.uri, result.source == PhotoSource.Camera)
                PhotoPickResult.NoCamera -> onAiPhotoProblem(AiProblem.NoCamera)
                PhotoPickResult.Failed -> onAiPhotoProblem(AiProblem.PickFailed)
                // Cancelar no es un fallo: no hay nada que contar.
                PhotoPickResult.Cancelled -> Unit
            }
        }
    }
    // La hoja de «de dónde sale la foto». Solo un booleano: sin Uri ni rutas.
    var photoSheet by rememberSaveable { mutableStateOf(false) }

    val formVisible = state.aiFormVisible
    val running = state.runningSource == ImportSource.Ai
    val busy = state.busy
    val importAndHide = {
        keyboard?.hide()
        onImportAi()
    }
    // Sin imágenes en la IA del servidor no se ofrecen fotos: mandarlas gasta una
    // petición a la IA para recibir un error. Solo se apaga con un «no» claro del
    // servidor (`RecipeImportUiState.aiPhotosAvailable`), y se dice por qué.
    val photosAvailable = state.aiPhotosAvailable
    val photosUnavailableText = stringResource(R.string.import_ai_photos_unavailable)
    val canAddPhoto = !busy && formVisible && photosAvailable && !state.aiPhotosFull
    // Sin nada que mandar el botón está apagado y una línea dice por qué.
    val canImport = !busy && formVisible && state.hasAiContent

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.import_ai_section))
        Text(
            text = stringResource(
                if (photosAvailable) R.string.import_ai_intro else R.string.import_ai_intro_no_photos,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state.aiAvailability) {
            // Barra arriba y texto debajo, no en una fila: el texto largo se comía el
            // ancho y la barra quedaba a 0 dp (y a 2× se comía también el hueco).
            AiAvailability.Checking -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.import_ai_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AiAvailability.NotConfigured -> Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.import_ai_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Spacing.medium),
                )
            }
            // Con IA y con «no se sabe» se ofrece lo mismo: el servidor contesta
            // al intentarlo.
            AiAvailability.Available, AiAvailability.Unknown -> Unit
        }

        // Fijo y visible (no un «tooltip»): lo que hay que saber antes de mandar
        // nada. Va DESPUÉS del aviso de disponibilidad, para no contradecirlo si el
        // «no hay IA» llega tarde (entonces ni se pinta: hablar de lo que hace la IA
        // de tu servidor cuando no la tiene solo confunde). Dos párrafos, un tema
        // cada uno, para que se lean enteros a texto grande.
        if (formVisible && !state.aiUnavailable) Surface(
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
                    text = stringResource(R.string.import_ai_notice_waits),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.import_ai_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Sin IA y sin nada escrito no hay formulario: solo el título y el aviso, que
        // ya remite a las otras dos vías. Con contenido dentro sí se pinta, aunque el
        // «no hay IA» haya llegado tarde (ver `RecipeImportUiState.aiFormVisible`).
        if (!formVisible) return@Column

        val invalidLink = state.aiLinkInvalid
        val invalidLinkText = stringResource(R.string.import_ai_link_invalid)
        // Sin audio no se recomienda el vídeo en la ayuda: sería recomendar y
        // desaconsejar en dos líneas seguidas. El motivo va dentro del propio campo
        // (`supportingText`), para que TalkBack lo lea con él y a texto grande no
        // parezca la etiqueta del campo siguiente.
        val linkHelp = stringResource(
            if (invalidLink) {
                R.string.import_ai_link_invalid
            } else if (state.aiAudioUnavailable) {
                R.string.import_ai_link_supporting_no_audio
            } else {
                R.string.import_ai_link_supporting
            },
        )
        // Aviso, no bloqueo: la app no puede saber si un https es un vídeo o una
        // página, y el servidor contestará. Se dice para que un «no funciona» no
        // parezca un fallo del móvil.
        val audioNotice = if (state.aiAudioUnavailable) {
            stringResource(R.string.import_ai_link_audio_unavailable)
        } else {
            null
        }
        OutlinedTextField(
            value = state.aiLink,
            onValueChange = onAiLinkChange,
            modifier = Modifier
                .fillMaxWidth()
                .fieldError(invalidLink, invalidLinkText),
            readOnly = busy,
            label = { Text(stringResource(R.string.import_ai_link_label)) },
            isError = invalidLink,
            supportingText = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall)) {
                    Text(linkHelp)
                    if (audioNotice != null) {
                        Text(
                            text = audioNotice,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )

        // El valor viaja por la sobrecarga de String y vive en el ViewModel: no
        // pasa por el estado guardado. Hasta 1 MiB acabaría en el Bundle.
        OutlinedTextField(
            value = state.aiText,
            onValueChange = onAiTextChange,
            modifier = Modifier.fillMaxWidth(),
            readOnly = busy,
            label = { Text(stringResource(R.string.import_ai_text_label)) },
            supportingText = {
                Text(stringResource(R.string.import_ai_text_supporting, state.maxAiTextMiB))
            },
            minLines = 4,
            maxLines = 8,
        )

        OutlinedButton(
            onClick = {
                // Si el campo tenía el foco, el selector salía con el teclado detrás.
                keyboard?.hide()
                photoSheet = true
            },
            enabled = canAddPhoto,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget)
                // El motivo también para quien no ve la línea de debajo: TalkBack
                // dice «Añadir fotos, desactivado, <motivo>» de una vez.
                .semantics {
                    if (!photosAvailable) stateDescription = photosUnavailableText
                },
        ) {
            Icon(painter = painterResource(R.drawable.ic_photo), contentDescription = null)
            Spacer(Modifier.width(Spacing.small))
            Text(stringResource(R.string.import_ai_photos))
        }

        // El botón sigue ahí, apagado, para que se vea que la opción existe y por
        // qué no se puede: con una IA que no lee fotos, mandarlas gasta una petición
        // para recibir un error.
        if (!photosAvailable) {
            Text(
                text = photosUnavailableText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Contador y ayuda, solo cuando tienen sentido: sin fotos posibles y sin
        // ninguna ya preparada, «0 de 5 fotos» sobraba. Las preparadas se siguen
        // enseñando siempre, para poder quitarlas.
        if (photosAvailable || state.aiPhotos.isNotEmpty() || state.aiPreparingPhoto) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.import_ai_photos_count,
                        state.aiPhotos.size,
                        state.maxAiPhotos,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.aiPreparingPhoto) {
                    LinearProgressIndicator(modifier = Modifier.width(AiThumbWidth))
                }
            }

            if (state.aiPreparingPhoto) {
                Text(
                    text = stringResource(R.string.import_ai_photo_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(
                    R.string.import_ai_photos_hint,
                    state.maxAiPhotos,
                    state.maxAiPhotoMiB,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.aiPhotos.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                state.aiPhotos.forEachIndexed { index, photo ->
                    AiPhotoItem(
                        index = index,
                        photo = photo,
                        enabled = !busy,
                        onRemove = { onRemoveAiPhoto(index) },
                    )
                }
            }
        }

        // El total, antes de enviar: el núcleo lo rechaza igualmente, pero así se
        // sabe qué quitar sin esperar turno. Solo se avisa; no se apaga nada.
        if (state.aiPhotosOverTotalBytes) {
            NoticeBlock(
                text = stringResource(
                    R.string.import_ai_photos_total_too_large,
                    state.maxAiTotalMiB,
                ),
                details = emptyList(),
            )
        }

        // Los motivos locales van ANTES del botón: nacen donde está el dedo. Debajo,
        // el aviso de «falta algo» o «esa foto no vale» caía fuera de la pantalla.
        val aiProblem = state.aiProblem
        if (aiProblem != null) {
            NoticeBlock(
                text = aiProblemText(aiProblem, state),
                details = emptyList(),
            )
        }

        if (running) {
            ImportRunning(
                onCancel = onCancel,
                label = stringResource(R.string.import_ai_running),
                hint = stringResource(R.string.import_ai_running_hint),
            )
        } else {
            Button(
                onClick = importAndHide,
                enabled = canImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.import_ai_action))
            }
            // El mismo patrón que «Guardar» en la primera pantalla: si está apagado,
            // se dice por qué, en reposo y sin ocupar sitio cuando ya se puede. Sin
            // fotos posibles, la ayuda no pide una foto (B1 de la revisión de UX).
            if (!state.hasAiContent) {
                Text(
                    text = stringResource(
                        if (photosAvailable) {
                            R.string.import_ai_action_hint
                        } else {
                            R.string.import_ai_action_hint_no_photos
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val problem = if (state.problemSource == ImportSource.Ai) state.problem else null
        if (problem != null) {
            ProblemBanner(
                problem = problem,
                message = stringResource(R.string.import_ai_failed_title),
                onRetry = importAndHide,
                onOpenSettings = onOpenSettings,
                // El texto, el enlace y las fotos se pierden si se va a Ajustes.
                unauthorizedTextRes = R.string.problem_text_unauthorized_form,
            )
        }

        if (state.rejectedSource == ImportSource.Ai) {
            NoticeBlock(
                text = stringResource(R.string.import_ai_rejected),
                details = state.details,
            )
        }
    }

    // La hoja de la foto: la cámara del sistema (sin CameraX y sin permiso CAMERA) o
    // la galería. Se cierra sola al elegir y deja paso al selector.
    if (photoSheet) {
        ModalBottomSheet(
            onDismissRequest = { photoSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.medium, vertical = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.import_ai_photos_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedButton(
                    onClick = {
                        photoSheet = false
                        picker?.takePhoto()
                    },
                    enabled = canAddPhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Icon(painter = painterResource(R.drawable.ic_camera), contentDescription = null)
                    Spacer(Modifier.width(Spacing.small))
                    Text(stringResource(R.string.import_ai_photos_camera))
                }
                OutlinedButton(
                    onClick = {
                        photoSheet = false
                        picker?.pickFromGallery()
                    },
                    enabled = canAddPhoto,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Icon(painter = painterResource(R.drawable.ic_photo), contentDescription = null)
                    Spacer(Modifier.width(Spacing.small))
                    Text(stringResource(R.string.import_ai_photos_gallery))
                }
            }
        }
    }
}

/**
 * Una foto preparada para la IA: su miniatura y un «Quitar» grande. Se numera en
 * el texto que lee TalkBack («Quitar foto 2»), porque las miniaturas se parecen.
 */
@Composable
private fun AiPhotoItem(
    index: Int,
    photo: AiPhoto,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val number = index + 1
    val removeDescription = stringResource(R.string.import_ai_photo_remove_description, number)
    val photoDescription = stringResource(R.string.import_ai_photo_description, number)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
    ) {
        val preview = photo.preview
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = photoDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AiThumbWidth, AiThumbHeight)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            Surface(
                modifier = Modifier.size(AiThumbWidth, AiThumbHeight),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(R.drawable.ic_photo), contentDescription = null)
                }
            }
        }
        TextButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = Sizes.MinTouchTarget)
                .semantics { contentDescription = removeDescription },
        ) {
            Text(stringResource(R.string.import_ai_photo_remove))
        }
    }
}

/** Tamaño de las miniaturas de la sección de IA: pequeñas, para reconocer la foto. */
private val AiThumbWidth = 96.dp
private val AiThumbHeight = 72.dp

/** Progreso de una importación en marcha, con su cancelar. */
@Composable
private fun ImportRunning(
    onCancel: () -> Unit,
    label: String,
    hint: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.import_cancel))
        }
    }
}

/** Aviso con fondo de error: un rechazo del servidor o un problema con el archivo. */
@Composable
private fun NoticeBlock(text: String, details: List<ValidationDetail>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .padding(Spacing.medium)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
            )
            for (detail in details) {
                Text(
                    text = detail.message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

/** El texto de cada motivo local, en el idioma de quien cocina. */
@StringRes
private fun dataProblemTextRes(problem: ImportDataProblem): Int = when (problem) {
    ImportDataProblem.Empty -> R.string.import_data_empty
    ImportDataProblem.TooLarge -> R.string.import_data_too_large
    ImportDataProblem.NotText -> R.string.import_data_not_text
    ImportDataProblem.NotUtf8 -> R.string.import_data_not_utf8
    ImportDataProblem.Unreadable -> R.string.import_data_unreadable
}

/**
 * El texto de cada motivo de la sección de IA. Los topes (fotos y MiB) salen del
 * núcleo a través del estado, no de los recursos: si cambian allí, cambian aquí.
 */
@Composable
private fun aiProblemText(problem: AiProblem, state: RecipeImportUiState): String = when (problem) {
    // Sin imágenes, pedir una foto sería pedir algo imposible (B1 de la revisión
    // de UX del 10-11-2026).
    AiProblem.Empty -> stringResource(
        if (state.aiPhotosAvailable) {
            R.string.import_ai_problem_empty
        } else {
            R.string.import_ai_problem_empty_no_photos
        },
    )
    AiProblem.TextTooLarge ->
        stringResource(R.string.import_ai_problem_text_too_large, state.maxAiTextMiB)
    AiProblem.TextNotText -> stringResource(R.string.import_ai_problem_text_not_text)
    AiProblem.TooManyPhotos ->
        stringResource(R.string.import_ai_problem_too_many_photos, state.maxAiPhotos)
    AiProblem.PhotosTooLarge -> stringResource(
        R.string.import_ai_problem_photos_too_large,
        state.maxAiPhotoMiB,
        state.maxAiTotalMiB,
    )
    AiProblem.PhotoUnreadable -> stringResource(R.string.import_ai_problem_photo_unreadable)
    AiProblem.PhotoTooLarge -> stringResource(R.string.import_ai_problem_photo_too_large)
    AiProblem.NoCamera -> stringResource(R.string.import_ai_problem_no_camera)
    AiProblem.PickFailed -> stringResource(R.string.import_ai_problem_pick_failed)
}

/**
 * Tipos que ofrece el selector de archivos. El comodín de texto (la última entrada) cubre los
 * proveedores que etiquetan el HTML o el JSON como `text/xhtml` o `text/json`. No hace falta
 * ningún permiso: el sistema solo deja leer lo que se elige.
 */
private val importMimeTypes = arrayOf(
    "application/json",
    "text/html",
    "text/plain",
    "text/*",
)

// -------------------------------------------------------------------- previews

private fun previewState() = RecipeImportUiState(
    visible = true,
    url = "https://recetas.example.com/arroz-con-verduras",
    // Con IA disponible: es lo que verá casi todo el mundo y deja ver la sección
    // entera en las previews de siempre.
    aiAvailability = AiAvailability.Available,
    aiCapabilities = AiCapabilities(available = true, imageAvailable = true, audioAvailable = true),
)

/** Tres fotos de mentira: un color plano, sin fichero, sin EXIF y sin datos de nadie. */
private fun previewAiPhotos(count: Int = 3, bytes: Int = 620 * 1024): List<AiPhoto> =
    List(count) { AiPhoto(preview = RecipePreviewData.photoPreviewBitmap(), bytes = bytes) }

@Composable
private fun ImportPreview(state: RecipeImportUiState) {
    RecipeImportScreen(
        state = state,
        onBack = {},
        onUrlChange = {},
        onImport = {},
        onDataTextChange = {},
        onImportData = {},
        onFilePicked = {},
        onRemoveFile = {},
        onCancel = {},
        onReviewList = {},
        onOpenSettings = {},
        onAiLinkChange = {},
        onAiTextChange = {},
        onAiPhotoPicked = { _, _ -> },
        onAiPhotoProblem = {},
        onRemoveAiPhoto = {},
        onImportAi = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportIdlePreview() {
    GastronomiconTheme { ImportPreview(previewState()) }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeImportIdleDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) { ImportPreview(previewState()) }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeImportIdleLargeTextPreview() {
    GastronomiconTheme { ImportPreview(previewState()) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportRunningPreview() {
    GastronomiconTheme { ImportPreview(previewState().copy(runningSource = ImportSource.Url)) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportInvalidUrlPreview() {
    GastronomiconTheme {
        ImportPreview(previewState().copy(url = "http://recetas.example.com/arroz", invalidUrl = true))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportMaybeCreatedPreview() {
    GastronomiconTheme {
        ImportPreview(previewState().copy(maybeCreated = true))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportCancelledPreview() {
    GastronomiconTheme { ImportPreview(previewState().copy(cancelled = true)) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportUnauthorizedPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                problem = ConnectionProblem.Unauthorized,
                problemSource = ImportSource.Data,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportRejectedPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                rejectedSource = ImportSource.Url,
                details = listOf(
                    ValidationDetail(DraftField.Tags, null, "El servidor no ha podido leer esa página."),
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportImportedPreview() {
    GastronomiconTheme {
        ImportPreview(previewState().copy(importedSlug = "arroz-con-verduras"))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportPastedTextPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                dataText = "{\n  \"@context\": \"https://schema.org\",\n  \"@type\": \"Recipe\",\n" +
                    "  \"name\": \"Arroz con verduras\",\n  \"recipeYield\": \"4 raciones\"\n}",
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportFileLoadedPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                loadedBytes = 24 * 1024,
                runningSource = ImportSource.Data,
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeImportDataProblemsLargeTextPreview() {
    GastronomiconTheme {
        ImportPreview(previewState().copy(dataProblem = ImportDataProblem.NotText))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportFileNotTextPreview() {
    GastronomiconTheme {
        ImportPreview(previewState().copy(fileProblem = ImportDataProblem.NotUtf8))
    }
}

// ------------------------------------------------------ importar con la IA

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportAiPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiLink = "https://video.example.com/paella",
                aiText = "Sofríe el pollo, añade el arroz y el caldo, y deja cocer 18 minutos.",
                aiPhotos = previewAiPhotos(),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeImportAiLargeTextPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiLink = "https://video.example.com/paella",
                aiPhotos = previewAiPhotos(1),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportAiDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        ImportPreview(previewState().copy(aiPhotos = previewAiPhotos(2)))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportAiCheckingPreview() {
    GastronomiconTheme {
        ImportPreview(previewState().copy(aiAvailability = AiAvailability.Checking))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportAiUnavailablePreview() {
    GastronomiconTheme {
        ImportPreview(previewState().copy(aiAvailability = AiAvailability.NotConfigured))
    }
}

/**
 * El «no hay IA» que llega tarde, con el texto ya escrito: se avisa pero el formulario
 * sigue ahí y se puede intentar (lo escrito no se queda encerrado).
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportAiLateNoAiPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiAvailability = AiAvailability.NotConfigured,
                aiText = "Sofríe el pollo y añade el arroz.",
            ),
        )
    }
}

/** Cuatro fotos de 5 MiB: pasan del tope del conjunto y se avisa antes de enviar. */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportAiPhotosOverTotalPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiPhotos = previewAiPhotos(count = 4, bytes = 5 * 1024 * 1024),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportAiRunningPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                runningSource = ImportSource.Ai,
                aiText = "Sofríe el pollo y añade el arroz.",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportAiTooManyPhotosPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiPhotos = previewAiPhotos(5),
                aiProblem = AiProblem.TooManyPhotos,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportAiLinkInvalidPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiLink = "http://video.example.com/paella",
                aiLinkInvalid = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportAiUnauthorizedPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                problem = ConnectionProblem.Unauthorized,
                problemSource = ImportSource.Ai,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportAiRejectedPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                rejectedSource = ImportSource.Ai,
                details = listOf(
                    ValidationDetail(DraftField.Name, null, "El servidor no ha podido preparar la receta."),
                ),
            ),
        )
    }
}

// Sin proveedor de imagen en el servidor: las fotos se apagan y se dice por qué,
// a texto grande para ver que el aviso cabe y se lee entero.
@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeImportAiNoImagePreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiCapabilities = AiCapabilities(
                    available = true,
                    imageAvailable = false,
                    audioAvailable = true,
                ),
                aiText = "Sofríe el pollo y añade el arroz.",
            ),
        )
    }
}

/**
 * El estado inicial sin imágenes y sin audio: ni la introducción ni la ayuda del
 * botón piden una foto que el botón de al lado niega (B1), y la ayuda del enlace no
 * recomienda el vídeo que el aviso de debajo desaconseja (M2).
 */
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportAiNoImageNoAudioEmptyPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiCapabilities = AiCapabilities(
                    available = true,
                    imageAvailable = false,
                    audioAvailable = false,
                ),
            ),
        )
    }
}

// Sin proveedor de audio: el enlace se sigue admitiendo y solo se avisa de los vídeos.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeImportAiNoAudioPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                aiCapabilities = AiCapabilities(
                    available = true,
                    imageAvailable = true,
                    audioAvailable = false,
                ),
                aiLink = "https://video.example.com/paella",
            ),
        )
    }
}
