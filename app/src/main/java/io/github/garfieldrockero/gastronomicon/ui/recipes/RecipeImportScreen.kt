package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
 * Importar una receta por URL. La descarga la hace tu Mealie: aquí solo se
 * escribe la dirección y se espera. Sin lógica: pinta [RecipeImportUiState].
 *
 * El progreso puede durar hasta dos minutos y se puede cancelar. Tras un
 * tiempo de espera agotado o una cancelación no se reintenta solo: se avisa de
 * que la receta puede existir ya y se ofrece revisar la lista.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeImportScreen(
    state: RecipeImportUiState,
    onBack: () -> Unit,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onReviewList: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.running) { if (!state.running) confirmLeave = false }
    val back = { if (state.running) confirmLeave = true else onBack() }
    BackHandler { back() }
    if (confirmLeave && state.running) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.import_leave_title)) },
            text = { Text(stringResource(R.string.import_leave_text)) },
            dismissButton = {
                TextButton(
                    onClick = { confirmLeave = false },
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) { Text(stringResource(R.string.import_leave_keep)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        onCancel()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) { Text(stringResource(R.string.import_leave_confirm)) }
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
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val importAndHide = {
        keyboard?.hide()
        onImport()
    }
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

        val invalid = state.invalidUrl
        val invalidText = stringResource(R.string.import_url_invalid)
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            modifier = Modifier
                .fillMaxWidth()
                .fieldError(invalid, invalidText),
            readOnly = state.running,
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
            keyboardActions = KeyboardActions(onDone = { importAndHide() }),
        )

        if (state.running) {
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
        } else {
            Button(
                onClick = importAndHide,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.import_action))
            }
        }

        val problem = state.problem
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

        if (state.rejected) {
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
                        text = stringResource(R.string.import_rejected),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    for (detail in state.details) {
                        Text(
                            text = detail.message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

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
    GastronomiconTheme { ImportPreview(previewState().copy(running = true)) }
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
        ImportPreview(previewState().copy(problem = ConnectionProblem.Unauthorized))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeImportRejectedPreview() {
    GastronomiconTheme {
        ImportPreview(
            previewState().copy(
                rejected = true,
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
