package io.github.garfieldrockero.gastronomicon.ui.mealplan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDayView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanMealView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanOperation
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanState
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummaryRow
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import java.time.LocalDate

/** Acciones de las hojas (añadir, mover) y del diálogo de quitar. */
class MealPlanSheetActions(
    val onDismiss: () -> Unit = {},
    val onAddDateChange: (LocalDate) -> Unit = {},
    val onAddTypeChange: (MealType) -> Unit = {},
    val onAddKindChange: (AddKind) -> Unit = {},
    val onAddTitleChange: (String) -> Unit = {},
    val onAddTextChange: (String) -> Unit = {},
    val onPickRecipeOpen: () -> Unit = {},
    val onPickRecipeBack: () -> Unit = {},
    val onRecipePicked: (id: String, name: String) -> Unit = { _, _ -> },
    val onPickerQueryChange: (String) -> Unit = {},
    val onPickerSearch: () -> Unit = {},
    val onPickerRetry: () -> Unit = {},
    val onPickerLoadMore: () -> Unit = {},
    val onConfirmAdd: () -> Unit = {},
    val onMoveDateChange: (LocalDate) -> Unit = {},
    val onMoveTypeChange: (MealType) -> Unit = {},
    val onConfirmMove: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
)

/** La hoja o el diálogo que toque según [MealPlanUiState.dialog]; nada si no hay ninguno. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlanSheets(
    state: MealPlanUiState,
    actions: MealPlanSheetActions,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
) {
    when (val dialog = state.dialog) {
        MealPlanDialog.None -> Unit

        is MealPlanDialog.Add -> ModalBottomSheet(
            onDismissRequest = actions.onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AddMealContent(
                form = dialog.form,
                plan = state.plan,
                picker = state.picker,
                actions = actions,
                onOpenSettings = onOpenSettings,
                onReload = onReload,
            )
        }

        is MealPlanDialog.Move -> ModalBottomSheet(
            onDismissRequest = actions.onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            MoveMealContent(
                move = dialog,
                plan = state.plan,
                actions = actions,
                onOpenSettings = onOpenSettings,
                onReload = onReload,
            )
        }

        is MealPlanDialog.ConfirmDelete -> DeleteMealDialog(
            meal = dialog.meal,
            plan = state.plan,
            actions = actions,
            onOpenSettings = onOpenSettings,
            onReload = onReload,
        )
    }
}

// --------------------------------------------------------------------- añadir

@Composable
internal fun AddMealContent(
    form: AddMealForm,
    plan: MealPlanState,
    picker: RecipePickerUiState,
    actions: MealPlanSheetActions,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (form.picking) {
        RecipePickerContent(picker = picker, actions = actions, onOpenSettings = onOpenSettings, modifier = modifier)
    } else {
        AddMealFormBody(
            form = form,
            plan = plan,
            actions = actions,
            onOpenSettings = onOpenSettings,
            onReload = onReload,
            modifier = modifier,
        )
    }
}

@Composable
private fun AddMealFormBody(
    form: AddMealForm,
    plan: MealPlanState,
    actions: MealPlanSheetActions,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saving = plan.isSaving
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.medium)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        SheetTitle(stringResource(R.string.plan_add_title))
        FailureFor(MealPlanOperation.Create, plan, onOpenSettings, onReload)

        plan.week?.let { week ->
            DayChoices(
                days = week.days,
                selected = form.date,
                title = stringResource(R.string.plan_add_day),
                onSelect = actions.onAddDateChange,
            )
        }
        TypeChoices(selected = form.mealType, onSelect = actions.onAddTypeChange)

        SectionLabel(stringResource(R.string.plan_add_kind))
        ChoiceRow {
            ChoiceChip(
                label = stringResource(R.string.plan_add_kind_recipe),
                selected = form.kind == AddKind.Recipe,
                onClick = { actions.onAddKindChange(AddKind.Recipe) },
            )
            ChoiceChip(
                label = stringResource(R.string.plan_add_kind_note),
                selected = form.kind == AddKind.Note,
                onClick = { actions.onAddKindChange(AddKind.Note) },
            )
        }

        when (form.kind) {
            AddKind.Recipe -> {
                val recipe = form.recipe
                Text(
                    text = recipe?.name ?: stringResource(R.string.plan_add_recipe_none),
                    style = if (recipe != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                    color = if (recipe != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                OutlinedButton(
                    onClick = actions.onPickRecipeOpen,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(
                        stringResource(
                            if (recipe == null) R.string.plan_add_recipe_pick else R.string.plan_add_recipe_change,
                        ),
                    )
                }
            }

            AddKind.Note -> {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = actions.onAddTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.plan_add_note_title)) },
                    placeholder = { Text(stringResource(R.string.plan_add_note_title_hint)) },
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = form.text,
                    onValueChange = actions.onAddTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.plan_add_note_text)) },
                    enabled = !saving,
                    minLines = 2,
                )
            }
        }

        Button(
            onClick = actions.onConfirmAdd,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            if (saving) BusyIndicator()
            Text(stringResource(if (saving) R.string.plan_saving else R.string.plan_add_confirm))
        }
        TextButton(
            onClick = actions.onDismiss,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.plan_cancel))
        }
    }
}

// ------------------------------------------------------------ elegir receta

@Composable
private fun RecipePickerContent(
    picker: RecipePickerUiState,
    actions: MealPlanSheetActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val list = picker.list
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(PICKER_HEIGHT_FRACTION)
            .imePadding()
            .padding(horizontal = Spacing.medium)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        SheetTitle(stringResource(R.string.plan_picker_title))
        OutlinedTextField(
            value = picker.query,
            onValueChange = actions.onPickerQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.plan_picker_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    actions.onPickerSearch()
                },
            ),
        )
        val problem = list.problem
        val rows = list.visibleRows.filter { it.summary.id != null }
        Column(modifier = Modifier.weight(1f)) {
            when {
                list.items.isEmpty() && problem != null && !list.isLoading -> ProblemBanner(
                    problem = problem,
                    message = stringResource(R.string.plan_picker_failed),
                    onRetry = actions.onPickerRetry,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(vertical = Spacing.small),
                )

                list.items.isEmpty() && (list.isLoading || !list.loadedOnce) -> LoadingBlock(
                    text = stringResource(R.string.plan_picker_loading),
                )

                list.isEmpty && list.search != null -> MessageBlock(
                    title = stringResource(R.string.plan_picker_no_match_title),
                    text = stringResource(R.string.plan_picker_no_match_text),
                    primaryLabel = stringResource(R.string.plan_picker_back),
                    onPrimary = actions.onPickRecipeBack,
                )

                list.isEmpty -> MessageBlock(
                    title = stringResource(R.string.plan_picker_empty_title),
                    text = stringResource(R.string.plan_picker_empty_text),
                    primaryLabel = stringResource(R.string.plan_picker_back),
                    onPrimary = actions.onPickRecipeBack,
                )

                rows.isEmpty() -> MessageBlock(
                    title = stringResource(R.string.plan_picker_no_match_title),
                    text = stringResource(R.string.plan_picker_no_match_text),
                    primaryLabel = stringResource(R.string.plan_picker_search_all),
                    onPrimary = {
                        keyboard?.hide()
                        actions.onPickerSearch()
                    },
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = Spacing.small),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    items(items = rows, key = { it.slug }) { row ->
                        PickerRow(row = row, onPick = { actions.onRecipePicked(row.summary.id.orEmpty(), row.name) })
                    }
                    if (problem != null) {
                        item(key = "problem") {
                            ProblemBanner(
                                problem = problem,
                                message = stringResource(R.string.plan_picker_failed),
                                onRetry = actions.onPickerRetry,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                    }
                    if (list.showSearchAllHint) {
                        item(key = "search-all") {
                            TextButton(
                                onClick = {
                                    keyboard?.hide()
                                    actions.onPickerSearch()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Sizes.MinTouchTarget),
                            ) {
                                Text(stringResource(R.string.plan_picker_search_all))
                            }
                        }
                    }
                    if (list.canLoadMore) {
                        item(key = "more") {
                            OutlinedButton(
                                onClick = actions.onPickerLoadMore,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Sizes.MinTouchTarget),
                            ) {
                                Text(stringResource(R.string.plan_picker_more))
                            }
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = actions.onPickRecipeBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.plan_picker_back))
        }
    }
}

@Composable
private fun PickerRow(row: RecipeSummaryRow, onPick: () -> Unit) {
    val description = stringResource(R.string.plan_picker_choose_description, row.name)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PICKER_ROW_MIN_HEIGHT)
            .clickable(onClick = onPick)
            .semantics { contentDescription = description },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.totalTimeText.isNotEmpty()) {
                Text(
                    text = row.totalTimeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- mover

@Composable
internal fun MoveMealContent(
    move: MealPlanDialog.Move,
    plan: MealPlanState,
    actions: MealPlanSheetActions,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saving = plan.isSaving
    val week = plan.week
    val currentDay = week?.days?.firstOrNull { it.date == move.meal.date }?.dayLabel.orEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.medium)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        SheetTitle(stringResource(R.string.plan_move_title))
        Text(
            text = stringResource(R.string.plan_move_text, move.meal.title, currentDay),
            style = MaterialTheme.typography.bodyLarge,
        )
        FailureFor(MealPlanOperation.Move, plan, onOpenSettings, onReload)
        if (week != null) {
            DayChoices(
                days = week.days,
                selected = move.date,
                title = stringResource(R.string.plan_add_day),
                onSelect = actions.onMoveDateChange,
            )
        }
        TypeChoices(selected = move.mealType, onSelect = actions.onMoveTypeChange)
        Button(
            onClick = actions.onConfirmMove,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            if (saving) BusyIndicator()
            Text(stringResource(if (saving) R.string.plan_saving else R.string.plan_move_confirm))
        }
        TextButton(
            onClick = actions.onDismiss,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.plan_cancel))
        }
    }
}

// --------------------------------------------------------------------- quitar

/** Confirmación de quitar: la acción destructiva, con su verbo completo y en color de error. */
@Composable
internal fun DeleteMealDialog(
    meal: MealPlanMealView,
    plan: MealPlanState,
    actions: MealPlanSheetActions,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
) {
    val saving = plan.isSaving
    AlertDialog(
        onDismissRequest = { if (!saving) actions.onDismiss() },
        title = { Text(stringResource(R.string.plan_delete_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.plan_delete_text, meal.title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                FailureFor(MealPlanOperation.Delete, plan, onOpenSettings, onReload)
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onDismiss,
                enabled = !saving,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.plan_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onConfirmDelete,
                enabled = !saving,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (saving) BusyIndicator()
                Text(stringResource(if (saving) R.string.plan_saving else R.string.plan_delete_confirm))
            }
        },
    )
}

// ------------------------------------------------------------ piezas comunes

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/** Fallo de [operation], solo si es el de la hoja abierta. */
@Composable
private fun FailureFor(
    operation: MealPlanOperation,
    plan: MealPlanState,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
) {
    val failure = plan.actionFailure
    if (failure != null && failure.operation == operation) {
        ActionFailureBox(failure = failure, onOpenSettings = onOpenSettings, onReload = onReload)
    }
}

@Composable
private fun DayChoices(
    days: List<MealPlanDayView>,
    selected: LocalDate,
    title: String,
    onSelect: (LocalDate) -> Unit,
) {
    SectionLabel(title)
    ChoiceRow {
        days.forEach { day ->
            ChoiceChip(label = day.dayLabel, selected = day.date == selected, onClick = { onSelect(day.date) })
        }
    }
}

@Composable
private fun TypeChoices(selected: MealType, onSelect: (MealType) -> Unit) {
    SectionLabel(stringResource(R.string.plan_add_meal_type))
    ChoiceRow {
        // `UNKNOWN` se enseña si el servidor lo manda, pero no se puede elegir como destino.
        MealType.values().filter { it.isKnown }.forEach { type ->
            ChoiceChip(label = type.label, selected = type == selected, onClick = { onSelect(type) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        content()
    }
}

/** Opción de un grupo; la marca «✓» acompaña al color para que no dependa solo de él. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    // Adorno: FilterChip ya dice «seleccionado» al lector de pantalla.
                    Text(text = "✓ ", modifier = Modifier.clearAndSetSemantics { })
                }
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
        },
    )
}

private const val PICKER_HEIGHT_FRACTION = 0.9f
private val PICKER_ROW_MIN_HEIGHT = Sizes.MinTouchTarget

// ------------------------------------------------------------------- previews

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AddRecipePreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = AddMealForm(date = MealPlanPreviewData.TODAY, recipe = PickedRecipe("1", "Lentejas de la abuela")),
                plan = MealPlanPreviewData.ready().plan,
                picker = MealPlanPreviewData.picker(),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun AddNoteLargeTextPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = AddMealForm(date = MealPlanPreviewData.TODAY, kind = AddKind.Note, title = "Sobras de ayer"),
                plan = MealPlanPreviewData.ready().plan,
                picker = MealPlanPreviewData.picker(),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AddFailedPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = AddMealForm(date = MealPlanPreviewData.TODAY),
                plan = MealPlanPreviewData.ready(
                    actionFailure = MealPlanPreviewData.maybeCreated(),
                ).plan,
                picker = MealPlanPreviewData.picker(),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun PickerPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = AddMealForm(date = MealPlanPreviewData.TODAY, picking = true),
                plan = MealPlanPreviewData.ready().plan,
                picker = MealPlanPreviewData.picker(),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun PickerLoadingPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = AddMealForm(date = MealPlanPreviewData.TODAY, picking = true),
                plan = MealPlanPreviewData.ready().plan,
                picker = RecipePickerUiState(RecipeListState(), ""),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 700)
@Composable
private fun PickerEmptyPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = AddMealForm(date = MealPlanPreviewData.TODAY, picking = true),
                plan = MealPlanPreviewData.ready().plan,
                picker = RecipePickerUiState(RecipeListState(loadedOnce = true), ""),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MovePreview() {
    GastronomiconTheme {
        Surface {
            val plan = MealPlanPreviewData.ready().plan
            val meal = plan.week!!.days.first { it.meals.isNotEmpty() }.meals.first()
            MoveMealContent(
                move = MealPlanDialog.Move(meal, meal.date, meal.mealType),
                plan = plan,
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun DeleteDialogLargeTextPreview() {
    GastronomiconTheme {
        val plan = MealPlanPreviewData.ready().plan
        val meal = plan.week!!.days.first { it.meals.isNotEmpty() }.meals.first()
        DeleteMealDialog(
            meal = meal,
            plan = plan,
            actions = MealPlanSheetActions(),
            onOpenSettings = {},
            onReload = {},
        )
    }
}
