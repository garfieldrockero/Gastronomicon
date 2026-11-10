package io.github.garfieldrockero.gastronomicon.ui.mealplan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDates
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDayView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanInputProblem
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanMealView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanOperation
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanProblem
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanState
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanWeekView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummaryRow
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingDialog
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Acciones de las hojas (añadir o editar) y del diálogo de quitar. */
class MealPlanSheetActions(
    val onDismiss: () -> Unit = {},
    val onAddDateChange: (LocalDate) -> Unit = {},
    val onAddTypeChange: (MealType) -> Unit = {},
    val onAddKindChange: (AddKind) -> Unit = {},
    val onAddTitleChange: (String) -> Unit = {},
    val onAddTextChange: (String) -> Unit = {},
    val onAddScaleChange: (String) -> Unit = {},
    val onPickRecipeOpen: () -> Unit = {},
    val onPickRecipeBack: () -> Unit = {},
    val onRecipePicked: (id: String, name: String) -> Unit = { _, _ -> },
    val onPickerQueryChange: (String) -> Unit = {},
    val onPickerSearch: () -> Unit = {},
    val onPickerRetry: () -> Unit = {},
    val onPickerLoadMore: () -> Unit = {},
    val onConfirmAdd: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    // «Añadir la semana a la compra» (fase 9, extra 1).
    val onAddToShoppingSelectList: (String) -> Unit = {},
    val onAddToShoppingRetryLists: () -> Unit = {},
    val onAddToShoppingConfirm: () -> Unit = {},
    val onAddToShoppingDismiss: () -> Unit = {},
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

        is MealPlanDialog.ConfirmDelete -> DeleteMealDialog(
            meal = dialog.meal,
            plan = state.plan,
            actions = actions,
            onOpenSettings = onOpenSettings,
            onReload = onReload,
        )
    }

    // «Añadir la semana a la compra»: es un diálogo, no una hoja, y va aparte de
    // la hoja de comida para que las dos no se pisen.
    val shopping = state.addToShopping
    if (shopping.open) {
        AddToShoppingDialog(
            state = shopping,
            title = stringResource(R.string.extras_compra_week_title),
            // El recuento de recetas distintas lo da el núcleo al abrir el diálogo.
            lead = if (shopping.recipes == 0) {
                stringResource(R.string.extras_compra_week_nothing)
            } else {
                pluralStringResource(R.plurals.extras_compra_week_lead, shopping.recipes, shopping.recipes)
            },
            note = when {
                shopping.truncated -> stringResource(R.string.extras_compra_week_truncated)
                shopping.skipped > 0 -> pluralStringResource(
                    R.plurals.extras_compra_week_skipped,
                    shopping.skipped,
                    shopping.skipped,
                )
                else -> null
            },
            showScale = false,
            showLists = shopping.recipes > 0,
            onSelectList = actions.onAddToShoppingSelectList,
            onScaleChange = {},
            onRetryLists = actions.onAddToShoppingRetryLists,
            onConfirm = actions.onAddToShoppingConfirm,
            onDismiss = actions.onAddToShoppingDismiss,
            onOpenSettings = onOpenSettings,
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
    val editing = form.editingId != null
    // Editar no cambia de «receta» a «nota» a la ligera: quitaría la receta de
    // la entrada. La clase de comida solo se elige al añadir.
    val operation = if (editing) MealPlanOperation.Edit else MealPlanOperation.Create
    // El calendario es una hoja aparte; con rememberSaveable, rotar el móvil no lo cierra.
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    // El día elegido puede quedar fuera de la semana a la vista (solo al editar).
    val outsideWeek = plan.week?.contains(form.date) == false
    val scaleInvalid = hasInvalidScale(plan, operation)
    // El calendario se abre sin teclado delante: si no, se queda encima de la hoja.
    val focusManager = LocalFocusManager.current
    // Las raciones son el último campo y el pie de la hoja no se mueve: con el
    // teclado abierto se traen a la vista al tocarlas.
    val scaleRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = Spacing.medium)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            SheetTitle(stringResource(if (editing) R.string.plan_edit_title else R.string.plan_add_title))

            plan.week?.let { week ->
                DayChoices(
                    days = week.days,
                    selected = form.date,
                    today = plan.today,
                    title = stringResource(R.string.plan_add_day),
                    onSelect = actions.onAddDateChange,
                )
            }

            if (editing) {
                // Un toque para un día de la semana a la vista y «Otra fecha…» para
                // cualquier otro, también de otra semana.
                OutlinedButton(
                    onClick = {
                        // Sin foco no queda teclado tapando el calendario.
                        focusManager.clearFocus()
                        pickingDay = true
                    },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.plan_edit_other_date))
                }
                if (outsideWeek) {
                    Text(
                        text = stringResource(
                            R.string.plan_edit_day_outside,
                            MealPlanWeekView.dayLabel(form.date, plan.today),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            TypeChoices(selected = form.mealType, onSelect = actions.onAddTypeChange)

            if (!editing) {
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
                    // Título y nota no sustituyen a la receta: la vista los enseña
                    // debajo de su nombre, y las ayudas lo dicen antes de escribirlos.
                    // Las raciones se piden como texto y el núcleo decide si valen.
                    OutlinedTextField(
                        value = form.title,
                        onValueChange = actions.onAddTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.plan_recipe_title)) },
                        supportingText = { Text(stringResource(R.string.plan_recipe_title_hint)) },
                        enabled = !saving,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = form.text,
                        onValueChange = actions.onAddTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.plan_recipe_text)) },
                        supportingText = { Text(stringResource(R.string.plan_recipe_text_hint)) },
                        enabled = !saving,
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    ScaleField(
                        value = form.scaleText,
                        onValueChange = actions.onAddScaleChange,
                        enabled = !saving,
                        invalid = scaleInvalid,
                        modifier = Modifier.bringIntoViewRequester(scaleRequester),
                        onFocused = { scope.launch { scaleRequester.bringIntoView() } },
                    )
                }

                AddKind.Note -> {
                    OutlinedTextField(
                        value = form.title,
                        onValueChange = actions.onAddTitleChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.plan_add_note_title)) },
                        placeholder = { Text(stringResource(R.string.plan_add_note_title_hint)) },
                        enabled = !saving,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = form.text,
                        onValueChange = actions.onAddTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.plan_add_note_text)) },
                        enabled = !saving,
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            }
        }

        // «Guardar cambios» y «Cancelar» no se van con el scroll: siguen a la
        // vista con el texto grande. El fallo se queda pegado al botón.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            FailureFor(operation, plan, onOpenSettings, onReload)

            Button(
                onClick = actions.onConfirmAdd,
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (saving) BusyIndicator()
                Text(
                    stringResource(
                        when {
                            saving -> R.string.plan_saving
                            editing -> R.string.plan_edit_confirm
                            else -> R.string.plan_add_confirm
                        },
                    ),
                )
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

    if (pickingDay) {
        DayPickerSheet(
            initial = form.date,
            today = plan.today,
            onDismiss = { pickingDay = false },
            onPick = { date ->
                actions.onAddDateChange(date)
                pickingDay = false
            },
        )
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
    val action = stringResource(R.string.plan_picker_choose_description, row.name)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PICKER_ROW_MIN_HEIGHT)
            // El nombre y el tiempo se leen tal cual; la acción va como pista,
            // sin pisar el texto visible (el control por voz sigue encontrándolo).
            .clickable(onClickLabel = action, role = Role.Button, onClick = onPick),
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

// ------------------------------------------------------------ elegir el día

/**
 * Cualquier día, también de otra semana.
 *
 * Va en una hoja propia y no en un `DatePickerDialog`: con el texto al 200 % el
 * diálogo recortaba el calendario y sus botones. Aquí el calendario se lleva
 * todo el alto que sobra y «Elegir este día» y «Cancelar» quedan fijos debajo,
 * siempre alcanzables. Con el texto grande el calendario no cabe entero, así que
 * la hoja abre escribiendo la fecha, que ocupa mucho menos; el selector de modo
 * sigue ahí para quien prefiera el calendario. El título y el día elegido los
 * compone el núcleo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerSheet(
    initial: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale > LARGE_TEXT_FONT_SCALE
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        yearRange = MealPlanDates.MIN_YEAR..MealPlanDates.MAX_YEAR,
        initialDisplayMode = if (largeText) DisplayMode.Input else DisplayMode.Picker,
    )
    val selected = state.selectedDateMillis?.toPickerDate() ?: initial
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(PICKER_SHEET_HEIGHT_FRACTION)
                .padding(horizontal = Spacing.medium)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            DatePicker(
                state = state,
                // El alto que sobra es para el calendario y él mismo desplaza sus
                // filas por dentro; `weight` le pone el tope para que ese
                // desplazamiento exista. Un `verticalScroll` por fuera lo mediría
                // sin tope de alto y el selector de años, que es una lista
                // perezosa, no se puede medir así.
                modifier = Modifier.weight(1f),
                showModeToggle = true,
                title = {
                    Text(
                        text = stringResource(R.string.plan_date_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = Spacing.large, vertical = Spacing.medium),
                    )
                },
                headline = {
                    Text(
                        text = MealPlanWeekView.dayLabel(selected, today),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(horizontal = Spacing.large, vertical = Spacing.small)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                },
            )
            Button(
                onClick = { onPick(selected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.plan_date_picker_confirm))
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.plan_cancel))
            }
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
    today: LocalDate,
    title: String,
    onSelect: (LocalDate) -> Unit,
) {
    SectionLabel(title)
    ChoiceRow {
        days.forEach { day ->
            ChoiceChip(
                // Con el año si el día no es del año en curso; lo compone el núcleo.
                label = MealPlanWeekView.dayLabel(day.date, today),
                selected = day.date == selected,
                onClick = { onSelect(day.date) },
            )
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

/**
 * Opción de un grupo. La marca es un icono (no un carácter «✓», que en algunos
 * móviles se dibuja como emoji) y acompaña al color para que el estado no
 * dependa solo del tono. FilterChip ya anuncia «seleccionado» al lector.
 *
 * El icono se reserva siempre, invisible cuando no está marcado: así el chip no
 * se ensancha al elegirlo y los demás no saltan de línea.
 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
        label = { Text(text = label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier
                    .size(FilterChipDefaults.IconSize)
                    .alpha(if (selected) 1f else 0f),
            )
        },
    )
}

/**
 * Raciones escritas a mano; el núcleo valida el número al confirmar. Si lo
 * rechaza, el aviso va en el propio campo (donde se corrige) y no solo junto
 * al botón. Al tocarlo se trae a la vista: es el último campo de la hoja y el
 * pie no se aparta con el teclado.
 */
@Composable
private fun ScaleField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    invalid: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focus -> if (focus.isFocused) onFocused() },
        label = { Text(stringResource(R.string.plan_scale_label)) },
        placeholder = { Text(stringResource(R.string.plan_scale_placeholder)) },
        supportingText = {
            Text(
                stringResource(
                    if (invalid) {
                        planInputTextRes(MealPlanInputProblem.InvalidRecipeScale)
                    } else {
                        R.string.plan_scale_hint
                    },
                ),
            )
        },
        isError = invalid,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        // «Hecho» cierra el teclado; antes no hacía nada.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
    )
}

/**
 * El núcleo ha rechazado las raciones de esta hoja: se marca el campo, que es
 * donde se arreglan. Aquí no se valida nada; solo se refleja el fallo.
 */
private fun hasInvalidScale(plan: MealPlanState, operation: MealPlanOperation): Boolean {
    val failure = plan.actionFailure ?: return false
    if (failure.operation != operation) return false
    val problem = failure.problem
    return problem is MealPlanProblem.InvalidInput &&
        problem.problem == MealPlanInputProblem.InvalidRecipeScale
}

// El selector de fecha trabaja en milisegundos UTC a medianoche, como Material 3.
private const val MILLIS_PER_DAY = 86_400_000L

private fun LocalDate.toPickerMillis(): Long = toEpochDay() * MILLIS_PER_DAY

private fun Long.toPickerDate(): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))

private const val PICKER_HEIGHT_FRACTION = 0.9f

/** La hoja del calendario: casi toda la pantalla, con los botones siempre abajo. */
private const val PICKER_SHEET_HEIGHT_FRACTION = 0.92f

/**
 * A partir de este tamaño de letra el calendario no cabe entero en la hoja: se
 * abre escribiendo la fecha, que es más corta y se maneja mejor a 2×.
 */
private const val LARGE_TEXT_FONT_SCALE = 1.3f

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

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun EditRecipePreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = MealPlanPreviewData.editRecipeForm(),
                plan = MealPlanPreviewData.ready().plan,
                picker = MealPlanPreviewData.picker(),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun EditRecipeLargeTextPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = MealPlanPreviewData.editRecipeForm(),
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
private fun EditNoteLargeTextPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = MealPlanPreviewData.editNoteForm(),
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

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun EditOtherWeekPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = MealPlanPreviewData.editRecipeOtherWeekForm(),
                plan = MealPlanPreviewData.ready().plan,
                picker = MealPlanPreviewData.picker(),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
private fun EditScaleErrorPreview() {
    GastronomiconTheme {
        Surface {
            AddMealContent(
                form = MealPlanPreviewData.editRecipeForm().copy(scaleText = "dos"),
                plan = MealPlanPreviewData.ready(actionFailure = MealPlanPreviewData.invalidScale()).plan,
                picker = MealPlanPreviewData.picker(),
                actions = MealPlanSheetActions(),
                onOpenSettings = {},
                onReload = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun DayPickerSmallScreenPreview() {
    GastronomiconTheme {
        DayPickerSheet(
            initial = MealPlanPreviewData.TODAY,
            today = MealPlanPreviewData.TODAY,
            onDismiss = {},
            onPick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun DayPickerLargeTextPreview() {
    GastronomiconTheme {
        DayPickerSheet(
            initial = MealPlanPreviewData.TODAY,
            today = MealPlanPreviewData.TODAY,
            onDismiss = {},
            onPick = {},
        )
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
