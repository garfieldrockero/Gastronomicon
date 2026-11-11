package io.github.garfieldrockero.gastronomicon.ui.mealplan

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
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
import io.github.garfieldrockero.gastronomicon.ui.components.GastronomiconTopBar
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.SectionTitle
import io.github.garfieldrockero.gastronomicon.ui.components.problemTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.problemTitleRes
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingNoticeCard
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingOutcome
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingUi
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Motion
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.motionTween
import io.github.garfieldrockero.gastronomicon.ui.theme.rememberAnimationsEnabled
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
 * Dentro de cada día, las comidas van agrupadas por tipo (desayuno, comida,
 * aperitivo, cena y un cajón «Otras»): el agrupado y el orden los pone
 * `MealPlanDayView.sections` en el núcleo, aquí solo se pintan.
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
            GastronomiconTopBar(
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
                    // El radio sale del tema (`shapes.large`), no del valor de plantilla.
                    shape = MaterialTheme.shapes.large,
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

/**
 * Qué ocupa el cuerpo de la pantalla. Son tres estados excluyentes y de cardinalidad
 * fija: es la clave que se le da al cambio de bloque, no los datos (que cambian en
 * cada recomposición y dispararían la animación sin parar).
 */
private enum class PlanBody { Loading, Problem, Ready }

/**
 * Cambio de bloque del cuerpo. Solo se desvanece el que **no** se anuncia por
 * TalkBack (la semana con comidas); lo que se va se apaga en el acto (`snap`), de modo
 * que nunca hay dos regiones vivas a la vez (la semana y el fallo) ni un error
 * esperando 120 ms para contarse.
 */
private fun planBodyFade(animate: Boolean): ContentTransform =
    fadeIn(animationSpec = motionTween(enabled = animate)) togetherWith
        fadeOut(animationSpec = snap())

@Composable
private fun MealPlanBody(
    state: MealPlanUiState,
    actions: MealPlanActions,
    modifier: Modifier = Modifier,
) {
    val plan = state.plan
    val animations = rememberAnimationsEnabled()
    val target = when {
        plan.week == null && plan.loadProblem != null && !plan.isLoading -> PlanBody.Problem
        // También antes de la primera carga: la pantalla nunca se queda en blanco.
        plan.week == null -> PlanBody.Loading
        else -> PlanBody.Ready
    }
    // El cambio de bloque solo ocurre cuando el estado cambia de verdad (cargando →
    // semana → fallo), y con las animaciones del sistema quitadas no hay fundido: snap.
    // Cada bloque lee del estado actual: el que se va ya no tiene datos y no dibuja
    // nada inventado.
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { planBodyFade(animate = animations && targetState == PlanBody.Ready) },
        sizeTransform = null,
        label = "plan-body",
    ) { body ->
        val week = plan.week
        val problem = plan.loadProblem
        when (body) {
            PlanBody.Loading -> LoadingBlock(
                text = stringResource(R.string.plan_loading),
                modifier = Modifier.fillMaxSize(),
            )

            PlanBody.Problem -> if (problem != null) {
                LoadProblemBlock(
                    problem = problem,
                    onRetry = actions.onRetry,
                    onToday = actions.onToday,
                    onOpenSettings = actions.onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LoadingBlock(
                    text = stringResource(R.string.plan_loading),
                    modifier = Modifier.fillMaxSize(),
                )
            }

            PlanBody.Ready -> if (week != null) {
                WeekContent(
                    plan = plan,
                    week = week,
                    addToShopping = state.addToShopping,
                    // Con una hoja abierta, el fallo de esa escritura se enseña dentro de ella.
                    showActionFailure = state.dialog is MealPlanDialog.None,
                    animations = animations,
                    actions = actions,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LoadingBlock(
                    text = stringResource(R.string.plan_loading),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun WeekContent(
    plan: MealPlanState,
    week: MealPlanWeekView,
    addToShopping: AddToShoppingUi,
    showActionFailure: Boolean,
    animations: Boolean,
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
                    bottom = Sizes.FabClearance,
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
                        animations = animations,
                        onAdd = { actions.onAddClick(day.date) },
                        onEdit = actions.onEditClick,
                        onDelete = actions.onDeleteClick,
                        // Al cambiar de semana los días son otros: entran y salen con un
                        // fundido corto y las posiciones se recolocan en 200 ms. Es la
                        // confirmación de que la semana cargada es la nueva.
                        modifier = Modifier.animateItem(
                            fadeInSpec = motionTween(animations, Motion.ShortMillis),
                            placementSpec = motionTween(animations, Motion.MediumMillis),
                            fadeOutSpec = motionTween(animations, Motion.ShortMillis),
                        ),
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
        SectionTitle(
            text = week.title,
            // Al cambiar de semana el lector de pantalla anuncia la nueva. Es lo único que
            // necesita el título de la semana, así que se pinta con el de sección común
            // (mismo tamaño, color y encabezado que el resto de la app).
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            // Un escalón por encima de los días (titleLarge) para que mande la semana.
            style = MaterialTheme.typography.headlineSmall,
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
    /** Animaciones del sistema encendidas; en falso, la tarjeta cambia de alto de golpe. */
    animations: Boolean = false,
) {
    Card(
        // Añadir o quitar una comida cambia el alto del día: se abre o se cierra en
        // 120 ms en vez de dar un salto, y el resto de la lista se recoloca con su
        // propia animación de posición.
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(motionTween(animations, Motion.ShortMillis)),
        // La misma tarjeta que la lista de recetas: esquina grande del tema y borde de 1 dp.
        // El tono solo no la separa del fondo (1,06:1 en claro y 1,07:1 en oscuro).
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (day.isToday) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (day.isToday) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
            // Las comidas del día van agrupadas por tipo: la cabecera de la sección dice
            // «Desayuno» una vez y cada comida de una sección principal no lo repite. En
            // «Otras» no hay un tipo común, así que allí cada comida conserva el suyo.
            // El agrupado y el orden los decide el núcleo (`day.sections`).
            day.sections.forEachIndexed { index, section ->
                MealSectionHeader(
                    title = section.title,
                    isToday = day.isToday,
                    // Aire entre secciones: la primera ya va separada del nombre del día
                    // por el hueco de la columna.
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else Spacing.medium),
                )
                section.meals.forEach { meal ->
                    MealItem(
                        meal = meal,
                        busy = busy,
                        // La cabecera ya ha dicho el tipo; solo «Otras» lo repite dentro.
                        showTypeLabel = section.isOther,
                        onEdit = { onEdit(meal) },
                        onDelete = { onDelete(meal) },
                    )
                }
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

/**
 * Cabecera de una sección de comidas dentro de la tarjeta de un día: «Desayuno»,
 * «Comida», «Otras»…
 *
 * El tipo se lee una vez aquí y no en cada comida, que es lo que hace legible la
 * tarjeta: sin esto, tres comidas seguidas repiten tres etiquetas iguales de tamaño
 * y color. Va en el color de acento y en `titleSmall` (un escalón por debajo del
 * nombre del día, que es `titleLarge`) y marcada como encabezado para que TalkBack
 * pueda saltar de sección en sección.
 *
 * En la tarjeta de hoy el fondo es `primaryContainer`, y el acento encima se queda
 * en 4,27:1 en oscuro (por debajo de AA para texto pequeño): allí el encabezado usa
 * el color de contenido de la tarjeta, que da 6,85:1 en oscuro y 12,5:1 en claro.
 */
@Composable
private fun MealSectionHeader(
    title: String,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = if (isToday) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealItem(
    meal: MealPlanMealView,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Si la comida enseña su tipo («Desayuno») encima del título. False cuando la
     * cabecera de la sección ya lo ha dicho, para no leerlo dos veces.
     */
    showTypeLabel: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Concéntrica con la tarjeta del día: 24 dp de esquina menos los 16 dp de relleno dan
        // 8 dp (`shapes.small`). Es una caja dentro de otra, más pequeña y sin segundo marco.
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            if (showTypeLabel) {
                Text(
                    text = meal.mealType.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
        Column {
            // El tono solo no despega la barra del fondo (1,06:1): la delimita una línea de
            // 1 dp con `outline`, la misma que llevan las tarjetas de los días.
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
    is MealPlanProblem.Connection -> problemTitleRes(problem.problem, R.string.plan_error_title)
}

@StringRes
private fun loadTextRes(problem: MealPlanProblem): Int = when (problem) {
    MealPlanProblem.Unauthorized -> R.string.problem_text_unauthorized
    MealPlanProblem.Forbidden -> R.string.plan_forbidden_text
    MealPlanProblem.NotFound -> R.string.plan_not_found_text
    is MealPlanProblem.InvalidInput -> R.string.plan_date_text
    is MealPlanProblem.Invalid, is MealPlanProblem.Rejected, is MealPlanProblem.MaybeCreated ->
        R.string.plan_rejected_text
    is MealPlanProblem.Connection -> problemTextRes(problem.problem)
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

// Semana llena con texto grande y en oscuro: la tarjeta del día, su borde de 1 dp y el
// botón flotante, que es lo que más sufre a 2×.
@Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun MealPlanWeekDarkLargeTextPreview() {
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

// Otra semana con texto grande y en oscuro: «Ir a esta semana», «Añadir la semana a la
// compra» y la barra de cambiar de semana son tres filas de botones seguidas.
@Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun MealPlanOtherWeekDarkLargeTextPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.otherWeek(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanEmptyPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.emptyWeek(), MealPlanActions()) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MealPlanEmptyDarkPreview() {
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

// ------------------------------------------- secciones de comida (16f)

/**
 * Una tarjeta de día sola, sobre el fondo de la app y con el mismo margen que en la
 * lista: así se mira de cerca lo que cambia con las secciones (cabecera, orden y
 * etiquetas repetidas) sin siete tarjetas de por medio.
 */
@Composable
private fun DayCardPreview(day: MealPlanDayView) {
    GastronomiconTheme {
        Surface(
            modifier = Modifier.padding(Spacing.medium),
            color = MaterialTheme.colorScheme.background,
        ) {
            DayCard(day = day, busy = false, onAdd = {}, onEdit = {}, onDelete = {})
        }
    }
}

// Desayuno, Comida, Aperitivo y Cena: cuatro cabeceras y ninguna comida repitiendo su tipo.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MealPlanDaySectionsPreview() {
    DayCardPreview(MealPlanPreviewData.dayView(MealPlanPreviewData.sectionsWeek()))
}

// Un día que no es hoy: fondo normal y cabecera en el acento de la app. En oscuro es
// donde el acento sobre `surfaceContainer` hay que mirarlo.
@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MealPlanDaySectionsNotTodayDarkPreview() {
    // El miércoles tiene las cuatro secciones, pero el «hoy» de la semana es el sábado.
    val state = MealPlanPreviewData.sectionsWeek(today = MealPlanPreviewData.TODAY.plusDays(3))
    DayCardPreview(MealPlanPreviewData.dayView(state))
}

// La misma tarjeta en oscuro y a 2×: cuatro cabeceras y seis comidas es el caso que más
// crece y el que puede dejar el botón «Añadir» lejos del pulgar.
@Preview(
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun MealPlanDaySectionsDarkLargeTextPreview() {
    DayCardPreview(MealPlanPreviewData.dayView(MealPlanPreviewData.sectionsWeek()))
}

// Con «Otras» al final: allí cada comida sí lleva su etiqueta (Guarnición, Bebida…).
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MealPlanDayOthersPreview() {
    DayCardPreview(MealPlanPreviewData.dayView(MealPlanPreviewData.othersWeek()))
}

@Preview(
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun MealPlanDayOthersDarkLargeTextPreview() {
    DayCardPreview(MealPlanPreviewData.dayView(MealPlanPreviewData.othersWeek()))
}

// Día sin nada: «Sin comidas» y el botón de añadir, sin cabeceras ni huecos raros.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MealPlanDayEmptyPreview() {
    DayCardPreview(MealPlanPreviewData.emptyDay())
}

@Preview(
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun MealPlanDayEmptyDarkLargeTextPreview() {
    DayCardPreview(MealPlanPreviewData.emptyDay())
}

// La semana entera, para ver cómo respira el conjunto con las secciones dentro.
@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun MealPlanSectionsWeekPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.sectionsWeek(), MealPlanActions()) }
}

@Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 2f,
)
@Composable
private fun MealPlanOthersWeekDarkLargeTextPreview() {
    GastronomiconTheme { MealPlanScreen(MealPlanPreviewData.othersWeek(), MealPlanActions()) }
}
