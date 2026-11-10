package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.fieldError
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Importar una receta de dos formas: pegando la dirección de una página (la
 * descarga tu Mealie) o con un archivo o texto que se manda al servidor (JSON de
 * `schema.org/Recipe` o el HTML de la receta). Sin lógica: pinta
 * [RecipeImportUiState]; el núcleo decide qué vale y qué no.
 *
 * El progreso puede durar hasta dos minutos y se puede cancelar. Tras un tiempo
 * de espera agotado, un corte o una cancelación no se reintenta solo: se avisa de
 * que la receta puede existir ya y se ofrece revisar la lista.
 *
 * El texto pegado vive en el ViewModel: girar el móvil no lo pierde, y se
 * descarta al cerrar la pantalla o si el sistema mata la app, a propósito
 * (lleva la receta entera y no es un secreto que deba quedar en disco).
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
    modifier: Modifier = Modifier,
) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.running) { if (!state.running) confirmLeave = false }
    // Lo que se perdería al salir: la receta pegada o el archivo ya leído (hasta
    // 1 MiB y sin forma de recuperarlo; no hay borrador). Solo cuenta mientras se
    // ve el formulario: después de importar, el contenido ya no está en juego.
    val formVisible = state.importedSlug == null && !state.maybeCreated && !state.cancelled
    val hasContent = formVisible && (state.dataText.isNotBlank() || state.fileLoaded)
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
            busy = state.running || state.readingFile,
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
            ImportRunning(onCancel = onCancel)
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
    val busy = state.running || state.readingFile
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
            ImportRunning(onCancel = onCancel)
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

/** Progreso de una importación en marcha, con su cancelar. */
@Composable
private fun ImportRunning(onCancel: () -> Unit) {
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
                text = stringResource(R.string.import_running),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.import_running_hint),
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
 * Tipos que ofrece el selector de archivos. `text/*` cubre los proveedores que
 * etiquetan el HTML o el JSON como `text/xhtml` o `text/json`. No hace falta
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
)

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
