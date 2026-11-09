package io.github.garfieldrockero.gastronomicon.ui.mealplan

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanActionFailure
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDayView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanMealView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanOperation
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanPhase
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanProblem
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanState
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanWeekView
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.recipes.AddToShoppingNoticeCard
import io.github.garfieldrockero.gastronomicon.ui.recipes.AddToShoppingOutcome
import io.github.garfieldrockero.gastronomicon.ui.recipes.AddToShoppingUi
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import java.time.LocalDate

/** Todo lo que la pantalla puede pedir; el [MealPlanViewModel] lo reparte al núcleo. */
class MealPlanActions(
    val onRefresh: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onPreviousWeek: () -> Unit = {},
    val onNextWeek: () -> Unit = {},
    val onToday: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onAddClick: (LocalDate?) -> Unit = {},
    val onEditClick: (MealPlanMealView) -> Unit = {},
    val onDeleteClick: (MealPlanMealView) -> Unit = {},
    val onDismissActionFailure: () -> Unit = {},
    val onNoticeShown: () -> Unit = {},
    /** «Ver esa semana» del aviso: la comida se guardó en otra semana. */
    val onShowNoticeWeek: () -> Unit = {},
    // «Añadir la semana a la compra» (fase 9, extra 1).
    val onAddToShoppingClick: () -> Unit = {},
    val onAddToShoppingNoticeDismiss: () -> Unit = {},
    /** Lleva a la pestaña Compra; null si la navegación aún no lo cablea. */
    val onOpenShopping: (() -> Unit)? = null,
    val sheets: MealPlanSheetActions = MealPlanSheetActions(),
)

/**
 * Planificador de comidas: una semana de lunes a domingo, un día por tarjeta.
 * Sin lógica: pinta el estado del núcleo ([MealPlanUiState]) y avisa de lo que
 * hace la persona. Cambiar de semana y «Añadir comida» van abajo, al alcance
 * del pulgar.
 *
 * Estados: cargando, fallo de conexión (con acción), sin cobertura, sesión
 * caducada (lleva a Ajustes), semana vacía con acción, semana con comidas y
 * fallos al guardar (dentro de la hoja que se está usando).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealPlanScreen(
    state: MealPlanUiState,
    actions: MealPlanActions,
    modifier: Modifier = Modifier,
    // Con la barra de pestañas debajo, quien la pinta quita aquí el inset inferior
    // (ya lo consume la barra). Suelto, el valor por defecto es el de siempre.
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val plan = state.plan
    val week = plan.week
    val snackbar = remember { SnackbarHostState() }

    // Confirmación al terminar: Snackbar (TalkBack lo anuncia) que se da por visto al cerrarse.
    // La clave es el contador, no el tipo: dos comidas añadidas seguidas se anuncian las dos.
    val addedText = stringResource(R.string.plan_notice_added)
    val editedText = stringResource(R.string.plan_notice_edited)
    val movedOtherWeekText = stringResource(R.string.plan_notice_moved_other_week)
    val deletedText = stringResource(R.string.plan_notice_deleted)
    val missingText = stringResource(R.string.plan_action_entry_missing)
    val reloadLabel = stringResource(R.string.plan_action_reload)
    val viewWeekLabel = stringResource(R.string.plan_notice_view_week)
    LaunchedEffect(state.noticeId) {
        val notice = state.notice ?: return@LaunchedEffect
        val missing = notice == MealPlanNotice.EntryMissing
        // El aviso de otra semana lleva a verla, si se sabe a cuál fue.
        val otherWeek = notice == MealPlanNotice.MovedOtherWeek && state.noticeDate != null
        val result = snackbar.showSnackbar(
            message = when (notice) {
                MealPlanNotice.Added -> addedText
                MealPlanNotice.Edited -> editedText
                MealPlanNotice.MovedOtherWeek -> movedOtherWeekText
                MealPlanNotice.Deleted -> deletedText
                MealPlanNotice.EntryMissing -> missingText
            },
            actionLabel = when {
                missing -> reloadLabel
                otherWeek -> viewWeekLabel
                else -> null
            },
            // Con acción hay que dar tiempo a pulsarla.
            duration = if (missing || otherWeek) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        actions.onNoticeShown()
        if (result == SnackbarResult.ActionPerformed) {
            if (otherWeek) actions.onShowNoticeWeek() else actions.onRefresh()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = contentWindowInsets,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.plan_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                actions = {
                    IconButton(onClick = actions.onRefresh, enabled = !plan.isBusy) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.plan_reload),
                        )
                    }
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.nav_open_settings),
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (week != null) {
                WeekBar(
                    busy = plan.isBusy,
                    onPrevious = actions.onPreviousWeek,
                    onNext = actions.onNextWeek,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Con la semana vacía ya hay un «Añadir la primera comida»: dos atajos
            // al mismo sitio sobran y el flotante tapa el texto.
            if (week != null && week.hasMeals) {
                ExtendedFloatingActionButton(
                    onClick = { if (!plan.isBusy) actions.onAddClick(null) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                        )
                    },
                    text = { Text(stringResource(R.string.plan_add_fab)) },
                )
            }
        },
    ) { innerPadding ->
        MealPlanBody(
            state = state,
            actions = actions,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }

    MealPlanSheets(state = state, actions = actions.sheets, onOpenSettings = actions.onOpenSettings, onReload = actions.onRefresh)
}

// ------------------------------------------------------------------ contenido

@Composable
private fun MealPlanBody(
    state: MealPlanUiState,
    actions: MealPlanActions,
    modifier: Modifier = Modifier,
) {
    val plan = state.plan
    val week = plan.week
    val problem = plan.loadProblem
    when {
        week == null && problem != null && !plan.isLoading -> LoadProblemBlock(
            problem = problem,
            onRetry = actions.onRetry,
            onToday = actions.onToday,
            onOpenSettings = actions.onOpenSettings,
            modifier = modifier,
        )

        // También antes de la primera carga: la pantalla nunca se queda en blanco.
        week == null -> LoadingBlock(text = stringResource(R.string.plan_loading), modifier = modifier)

        else -> WeekContent(
            plan = plan,
            week = week,
            addToShopping = state.addToShopping,
            // Con una hoja abierta, el fallo de esa escritura se enseña dentro de ella.
            showActionFailure = state.dialog is MealPlanDialog.None,
            actions = actions,
            modifier = modifier,
        )
    }
}

@Composable
private fun WeekContent(
    plan: MealPlanState,
    week: MealPlanWeekView,
    addToShopping: AddToShoppingUi,
    showActionFailure: Boolean,
    actions: MealPlanActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Otra semana: se empieza arriba.
    LaunchedEffect(week.weekStart) { listState.scrollToItem(0) }

    Column(modifier = modifier) {
        if (plan.isBusy) {
            val text = stringResource(if (plan.phase == MealPlanPhase.Saving) R.string.plan_saving else R.string.plan_loading)
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = text
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        val loadProblem = plan.loadProblem
        if (loadProblem != null) {
            LoadProblemBanner(
                problem = loadProblem,
                onRetry = actions.onRetry,
                onToday = actions.onToday,
                onOpenSettings = actions.onOpenSettings,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }
        val failure = plan.actionFailure
        if (failure != null && showActionFailure) {
            ActionFailureBox(
                failure = failure,
                onOpenSettings = actions.onOpenSettings,
                onReload = actions.onRefresh,
                onDismiss = actions.onDismissActionFailure,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }

        // «Ya están en la lista»: encima de la semana y sin taparla, con su acción.
        val shoppingNotice = addToShopping.notice
        if (shoppingNotice != null) {
            AddToShoppingNoticeCard(
                notice = shoppingNotice,
                onLook = actions.onOpenShopping,
                onDismiss = actions.onAddToShoppingNoticeDismiss,
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
            )
        }

        // Añadir la semana a la compra: solo si la pantalla tiene transporte de la
        // compra. El recuento de recetas lo dice el núcleo al abrir el diálogo.
        val addWeekToShopping: (() -> Unit)? =
            if (addToShopping.available) actions.onAddToShoppingClick else null

        if (!week.hasMeals) {
            // Semana en blanco: un texto útil y una acción, no siete tarjetas vacías.
            WeekHeader(week = week, onToday = actions.onToday, enabled = !plan.isBusy)
            MessageBlock(
                title = stringResource(R.string.plan_empty_title),
                text = stringResource(R.string.plan_empty_text),
                primaryLabel = stringResource(R.string.plan_empty_action),
                onPrimary = { actions.onAddClick(null) },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                // Abajo, sitio para que el botón flotante no tape el último día.
                contentPadding = PaddingValues(
                    start = Spacing.medium,
                    end = Spacing.medium,
                    bottom = FabClearance,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                item(key = "header") {
                    WeekHeader(
                        week = week,
                        onToday = actions.onToday,
                        enabled = !plan.isBusy,
                        inList = true,
                        onAddToShopping = addWeekToShopping,
                    )
                }
                items(items = week.days, key = { it.date.toString() }) { day ->
                    DayCard(
                        day = day,
                        busy = plan.isBusy,
                        onAdd = { actions.onAddClick(day.date) },
                        onEdit = actions.onEditClick,
                        onDelete = actions.onDeleteClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(
    week: MealPlanWeekView,
    onToday: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    inList: Boolean = false,
    /** «Añadir la semana a la compra»; null si esta pantalla no puede hacerlo. */
    onAddToShopping: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (inList) 0.dp else Spacing.medium, vertical = Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = week.title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            // Al cambiar de semana el lector de pantalla anuncia la nueva.
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        if (!week.isCurrentWeek) {
            OutlinedButton(
                onClick = onToday,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.plan_week_today))
            }
        }
        if (onAddToShopping != null) {
            // Objetivo grande y al alcance: se usa de pie, antes de ir al súper.
            OutlinedButton(
                onClick = onAddToShopping,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tab_shopping),
                    contentDescription = null,
                )
                Text(
                    text = stringResource(R.string.extras_compra_week_action),
                    modifier = Modifier.padding(start = Spacing.small),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCard(
    day: MealPlanDayView,
    busy: Boolean,
    onAdd: () -> Unit,
    onEdit: (MealPlanMealView) -> Unit,
    onDelete: (MealPlanMealView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (day.isToday) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (day.isToday) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            // «Hoy» va con texto, no solo con color.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = day.dayLabel,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                if (day.isToday) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(R.string.plan_today_badge),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = Spacing.small, vertical = Spacing.extraSmall),
                        )
                    }
                }
            }
            val empty = day.emptyLabel
            if (empty != null) {
                Text(
                    text = empty,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            day.meals.forEach { meal ->
                MealItem(
                    meal = meal,
                    busy = busy,
                    onEdit = { onEdit(meal) },
                    onDelete = { onDelete(meal) },
                )
            }
            val addDescription = stringResource(R.string.plan_add_to_day_description, day.dayLabel)
            TextButton(
                onClick = onAdd,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget)
                    .semantics { contentDescription = addDescription },
            ) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null)
                Text(
                    text = stringResource(R.string.plan_add_to_day),
                    modifier = Modifier.padding(start = Spacing.small),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealItem(
    meal: MealPlanMealView,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Text(
                text = meal.mealType.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = meal.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (meal.recipeMissing || meal.isBlank) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (meal.note.isNotEmpty()) {
                Text(
                    text = meal.note,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val scale = meal.scaleText
            if (scale != null) {
                Text(
                    text = stringResource(R.string.plan_meal_scale, scale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // El nombre accesible empieza por el verbo visible y dice de qué comida
            // habla: con tres comidas, «Editar» a secas no distingue ninguna.
            val editDescription = stringResource(R.string.plan_meal_edit_description, meal.title)
            val deleteDescription = stringResource(R.string.plan_meal_delete_description, meal.title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TextButton(
                    onClick = onEdit,
                    enabled = !busy,
                    modifier = Modifier
                        .heightIn(min = Sizes.MinTouchTarget)
                        .semantics { contentDescription = editDescription },
                ) {
                    Text(stringResource(R.string.plan_meal_edit))
                }
                TextButton(
                    onClick = onDelete,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .heightIn(min = Sizes.MinTouchTarget)
                        .semantics { contentDescription = deleteDescription },
                ) {
                    Text(stringResource(R.string.plan_meal_delete))
                }
            }
        }
    }
}

/** Cambiar de semana, abajo y a la altura del pulgar. */
@Composable
private fun WeekBar(
    busy: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El relleno de la barra de navegación va DENTRO del Surface: el fondo llega al borde.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        val previous = stringResource(R.string.plan_week_previous)
        val next = stringResource(R.string.plan_week_next)
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            OutlinedButton(
                onClick = onPrevious,
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.MinTouchTarget)
                    .semantics { contentDescription = previous },
            ) {
                // El chevrón es solo adorno: el lector dice «Semana anterior».
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = null,
                )
                Text(text = previous, modifier = Modifier.padding(start = Spacing.small))
            }
            OutlinedButton(
                onClick = onNext,
                enabled = !busy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.MinTouchTarget)
                    .semantics { contentDescription = next },
            ) {
                Text(text = next, modifier = Modifier.padding(end = Spacing.small))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- fallos

/** Fallo de una escritura: qué falló, por qué y la acción que lo arregla. */
@Composable
internal fun ActionFailureBox(
    failure: MealPlanActionFailure,
    onOpenSettings: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val problem = failure.problem
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(
                    text = stringResource(planActionTitleRes(failure.operation)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(planActionTextRes(problem)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (problem.needsSettings()) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.problem_open_settings))
                }
            } else if (problem.needsReload()) {
                Button(
                    onClick = onReload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.plan_action_reload))
                }
            }
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.plan_action_dismiss))
                }
            }
        }
    }
}

@StringRes
private fun loadTitleRes(problem: MealPlanProblem): Int = when (problem) {
    MealPlanProblem.Unauthorized -> R.string.problem_title_unauthorized
    MealPlanProblem.Forbidden -> R.string.plan_forbidden_title
    MealPlanProblem.NotFound -> R.string.plan_not_found_title
    is MealPlanProblem.InvalidInput -> R.string.plan_date_title
    is MealPlanProblem.Invalid, is MealPlanProblem.Rejected, is MealPlanProblem.MaybeCreated ->
        R.string.plan_rejected_title
    is MealPlanProblem.Connection -> when (problem.problem) {
        ConnectionProblem.NoNetwork -> R.string.problem_title_no_network
        ConnectionProblem.ServerNotResponding -> R.string.problem_title_not_responding
        ConnectionProblem.Unauthorized -> R.string.problem_title_unauthorized
        else -> R.string.plan_error_title
    }
}

@StringRes
private fun loadTextRes(problem: MealPlanProblem): Int = when (problem) {
    MealPlanProblem.Unauthorized -> R.string.problem_text_unauthorized
    MealPlanProblem.Forbidden -> R.string.plan_forbidden_text
    MealPlanProblem.NotFound -> R.string.plan_not_found_text
    is MealPlanProblem.InvalidInput -> R.string.plan_date_text
    is MealPlanProblem.Invalid, is MealPlanProblem.Rejected, is MealPlanProblem.MaybeCreated ->
        R.string.plan_rejected_text
    is MealPlanProblem.Connection ->
        if (problem.problem == ConnectionProblem.Unauthorized) R.string.problem_text_unauthorized else errorTextRes(problem.problem)
}

/** Fallo al cargar la semana, a pantalla completa (no hay semana que enseñar). */
@Composable
private fun LoadProblemBlock(
    problem: MealPlanProblem,
    onRetry: () -> Unit,
    onToday: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val noNetwork = (problem as? MealPlanProblem.Connection)?.problem == ConnectionProblem.NoNetwork
    val invalidDate = problem is MealPlanProblem.InvalidInput
    val settings = problem.needsSettings()
    MessageBlock(
        title = stringResource(loadTitleRes(problem)),
        text = stringResource(loadTextRes(problem)),
        hint = if (noNetwork) stringResource(R.string.plan_offline_hint) else null,
        primaryLabel = stringResource(
            when {
                invalidDate -> R.string.plan_week_today
                settings -> R.string.problem_open_settings
                else -> R.string.problem_retry
            },
        ),
        onPrimary = when {
            invalidDate -> onToday
            settings -> onOpenSettings
            else -> onRetry
        },
        // Un token caducado no se arregla reintentando; una fecha mala, tampoco.
        secondaryLabel = when {
            invalidDate -> null
            problem == MealPlanProblem.Forbidden -> stringResource(R.string.problem_retry)
            settings -> null
            else -> stringResource(R.string.problem_open_settings)
        },
        onSecondary = if (problem == MealPlanProblem.Forbidden) onRetry else onOpenSettings,
        modifier = modifier,
    )
}

/** Fallo al recargar o al cambiar de semana cuando ya hay una semana a la vista. */
@Composable
private fun LoadProblemBanner(
    problem: MealPlanProblem,
    onRetry: () -> Unit,
    onToday: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val invalidDate = problem is MealPlanProblem.InvalidInput
    val settings = problem.needsSettings()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(
                    text = stringResource(R.string.plan_reload_failed),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(loadTextRes(problem)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Button(
                onClick = when {
                    invalidDate -> onToday
                    settings -> onOpenSettings
                    else -> onRetry
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(
                    stringResource(
                        when {
                            invalidDate -> R.string.plan_week_today
                            settings -> R.string.problem_open_settings
                            else -> R.string.problem_retry
                        },
                    ),
                )
            }
        }
    }
}

private val FabClearance = 88.dp

// ------------------------------------------------------------------- previews

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanWeekPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.ready(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MealPlanWeekDarkPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.ready(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun MealPlanWeekLargeTextPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.ready(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanOtherWeekPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.otherWeek(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanEmptyPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.emptyWeek(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun MealPlanEmptyLargeTextPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.emptyWeek(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanLoadingPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.loading(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanNoNetworkPreview() {
    GastronomiconTheme {
        MealPlanScreen(
            MealPlanPreviewData.loadFailed(MealPlanProblem.Connection(ConnectionProblem.NoNetwork)),
            MealPlanActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun MealPlanServerDownLargeTextPreview() {
    GastronomiconTheme {
        MealPlanScreen(
            MealPlanPreviewData.loadFailed(MealPlanProblem.Connection(ConnectionProblem.ServerNotResponding)),
            MealPlanActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanUnauthorizedPreview() {
    GastronomiconTheme {
        MealPlanScreen(MealPlanPreviewData.loadFailed(MealPlanProblem.Unauthorized), MealPlanActions())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanReloadFailedPreview() {
    GastronomiconTheme {
        MealPlanScreen(
            MealPlanPreviewData.ready(
                loadProblem = MealPlanProblem.Connection(ConnectionProblem.NoNetwork),
            ),
            MealPlanActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanActionFailedPreview() {
    GastronomiconTheme {
        MealPlanScreen(
            MealPlanPreviewData.ready(
                actionFailure = MealPlanActionFailure(
                    MealPlanOperation.Delete,
                    MealPlanProblem.Connection(ConnectionProblem.ServerNotResponding),
                ),
            ),
            MealPlanActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanSavingPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.ready(phase = MealPlanPhase.Saving), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanShoppingAddedPreview() {
    GastronomiconTheme {
        MealPlanScreen(
            MealPlanPreviewData.ready(
                shoppingNotice = AddToShoppingOutcome.Added("Compra de la semana", 4),
            ),
            MealPlanActions(onOpenShopping = {}),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun MealPlanShoppingMaybeAddedLargeTextPreview() {
    GastronomiconTheme {
        MealPlanScreen(
            MealPlanPreviewData.ready(
                shoppingNotice = AddToShoppingOutcome.MaybeAdded(ConnectionProblem.ServerNotResponding),
            ),
            MealPlanActions(onOpenShopping = {}),
        )
    }
}
