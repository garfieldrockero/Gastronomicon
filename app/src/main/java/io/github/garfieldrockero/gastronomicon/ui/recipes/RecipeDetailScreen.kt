package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.IngredientLine
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailState
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailView
import io.github.garfieldrockero.gastronomicon.core.recipes.SourceLink
import io.github.garfieldrockero.gastronomicon.core.recipes.TimeKind
import io.github.garfieldrockero.gastronomicon.core.recipes.TimeLine
import io.github.garfieldrockero.gastronomicon.platform.ImageLoader
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingDialog
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingNoticeCard
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingOutcome
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingPreviewData
import io.github.garfieldrockero.gastronomicon.ui.components.AddToShoppingUi
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.GastronomiconSnackbarHost
import io.github.garfieldrockero.gastronomicon.ui.components.GastronomiconTopBar
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBlock
import io.github.garfieldrockero.gastronomicon.ui.components.RecipePhoto
import io.github.garfieldrockero.gastronomicon.ui.components.SanitizedHtmlContent
import io.github.garfieldrockero.gastronomicon.ui.components.SectionTitle
import io.github.garfieldrockero.gastronomicon.ui.components.SnackbarNotice
import io.github.garfieldrockero.gastronomicon.ui.components.StarRating
import io.github.garfieldrockero.gastronomicon.ui.components.StatusText
import io.github.garfieldrockero.gastronomicon.ui.components.addToShoppingAddedText
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.isLargeText
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.StepText
import io.github.garfieldrockero.gastronomicon.ui.theme.TabularNumbers
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode
import io.github.garfieldrockero.gastronomicon.ui.theme.motionTween
import io.github.garfieldrockero.gastronomicon.ui.theme.rememberAnimationsEnabled

/**
 * Qué bloque enseña el detalle. Es la clave (estable) de la transición: no cambia al
 * escalar raciones ni al recargar la misma receta, así que no recrea nodos ni regiones
 * vivas; solo cambia cuando cambia lo que se ve de verdad.
 */
private enum class DetailPane {
    Loading,
    Problem,
    Unauthorized,
    NotFound,
    Ready,
    Offline,
}

/** Lo que se pinta: la copia guardada manda sobre el estado del núcleo. */
private fun detailPane(state: RecipeDetailState, offline: Boolean): DetailPane = when {
    offline -> DetailPane.Offline
    state is RecipeDetailState.Ready -> DetailPane.Ready
    state is RecipeDetailState.Failed -> DetailPane.Problem
    state == RecipeDetailState.Unauthorized -> DetailPane.Unauthorized
    state == RecipeDetailState.NotFound -> DetailPane.NotFound
    // Mientras carga (y el instante en que la ruta retira el detalle al cerrarlo).
    else -> DetailPane.Loading
}

/**
 * Transición entre dos bloques del detalle. Lo que se va se apaga en el acto (`snap`),
 * así que nunca conviven dos regiones vivas ni un aviso de error espera su turno.
 */
private fun detailStateFade(animate: Boolean): ContentTransform =
    fadeIn(animationSpec = motionTween(enabled = animate)) togetherWith
        fadeOut(animationSpec = snap())

/**
 * Numera los avisos de «añadir a la compra» de esta pantalla.
 *
 * El aviso en sí vive en el ViewModel (`AddToShoppingUi.notice`) y lo consume el host
 * al terminar; lo que la pantalla pone es un **id que cambia con cada aviso** y que no
 * cambia al volver a componer. Así dos «añadido» seguidos se anuncian los dos, y el
 * mismo no se repite en cada recomposición (la clave del `LaunchedEffect` del host es
 * el id, nunca un booleano).
 *
 * Se compara por referencia, no por contenido: dos añadidos iguales —la misma lista y
 * la misma receta— son dos avisos distintos aunque digan lo mismo.
 *
 * `remember`, no `rememberSaveable`: lo que hay que recordar al girar es el aviso, y
 * eso ya está en el ViewModel; el número solo tiene que ser estable mientras la
 * pantalla viva. Si el móvil gira a medio aviso, el que no se llegó a consumir se
 * vuelve a pintar con el número que toque, que es lo que se quiere.
 */
private class ShoppingNoticeIds {
    private var last: AddToShoppingOutcome.Added? = null
    private var lastId: Long = 0L

    /** El id de [added]: el mismo mientras sea el mismo aviso, nuevo cuando cambia. */
    fun idFor(added: AddToShoppingOutcome.Added): Long {
        if (added !== last) {
            last = added
            lastId += 1
        }
        return lastId
    }
}

/**
 * Detalle de una receta. Sin lógica: las raciones, las cantidades y los textos
 * ya vienen del núcleo ([RecipeDetailView]); aquí solo se pintan y se avisa de lo
 * que toca la persona.
 *
 * Orden: tiempos, raciones, ingredientes, pasos, notas, categorías y etiquetas, origen.
 * Lectura de pie: pasos con letra grande e interlineado generoso, ingredientes con
 * casillas grandes (toda la fila es el botón, con la cantidad alineada en columna) y
 * raciones con botones de 56 dp. Marcar un ingrediente tacha el texto y TalkBack dice «marcada».
 *
 * El enlace de origen es un dato: se ve el servidor de origen y, solo si es https,
 * un botón que lo abre en el navegador del sistema con un toque.
 *
 * Sin conexión ([offline] no nulo): se pinta la copia guardada, con un aviso que
 * lleva su fecha y «Volver a intentar», sin foto (no se guardan), sin editar y sin
 * borrar. Las raciones se siguen pudiendo escalar.
 *
 * Fase 9, extra 1: en el menú, «Añadir a la lista de la compra», con un diálogo
 * que elige la lista y las raciones. Al terminar bien, el aviso es una confirmación
 * corta en la franja inferior («Añadido…», con «Ver la compra») que se va sola; con
 * un desenlace dudoso la tarjeta se queda a la vista, porque hay que mirar la lista
 * antes de repetir. Las listas, las recetas y el envío son del núcleo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    state: RecipeDetailState,
    deleteState: RecipeDeleteUiState,
    imageLoader: ImageLoader?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onMoreServings: () -> Unit,
    onFewerServings: () -> Unit,
    onOpenSource: (SourceLink) -> Boolean,
    onOpenSettings: () -> Unit,
    onEdit: () -> Unit,
    onDeleteClick: () -> Unit,
    onDeleteDismiss: () -> Unit,
    onDeleteConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    offline: OfflineCopyUi? = null,
    shopping: AddToShoppingUi = AddToShoppingUi(),
    shoppingActions: RecipeShoppingActions = RecipeShoppingActions(),
) {
    // Lo que se pinta: la copia guardada manda sobre lo que diga el estado del núcleo.
    val shownName = offline?.view?.name ?: (state as? RecipeDetailState.Ready)?.view?.name
    // Aviso corto de «añadido a la compra» (fase 16h): solo el éxito, con «Ver la compra»
    // si la navegación ya lleva a la pestaña. La duda (`MaybeAdded`) y los fallos no
    // pasan por aquí: se quedan en su tarjeta, porque exigen mirar antes de repetir.
    val noticeIds = remember { ShoppingNoticeIds() }
    val addedNotice = shopping.notice as? AddToShoppingOutcome.Added
    val openShopping = shoppingActions.onOpenShopping
    val openShoppingLabel = if (openShopping != null) {
        stringResource(R.string.extras_compra_notice_open)
    } else {
        null
    }
    val snackbarNotice = if (addedNotice != null) {
        SnackbarNotice(
            id = noticeIds.idFor(addedNotice),
            message = addToShoppingAddedText(addedNotice),
            actionLabel = openShoppingLabel,
        )
    } else {
        null
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        // Un host por pantalla (decisión de la fase 16h): el `Scaffold` lo coloca por
        // encima de lo que haya abajo y el componente se encarga del teclado y del 2×.
        snackbarHost = {
            GastronomiconSnackbarHost(
                notice = snackbarNotice,
                // Al terminar, el aviso se consume en el ViewModel (uno por id, nunca dos veces).
                onShown = shoppingActions.onNoticeDismiss,
                onAction = { openShopping?.invoke() },
            )
        },
        topBar = {
            GastronomiconTopBar(
                title = {
                    if (shownName != null) {
                        // El nombre en una línea con «…»; el encabezado completo está en el cuerpo,
                        // así que TalkBack no lo lee dos veces.
                        Text(
                            text = shownName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.detail_title),
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                actions = {
                    // Con la copia no se edita ni se borra: lo que se ve no está en el servidor.
                    // Los botones siguen a la vista pero apagados, para que se vea qué se podrá
                    // hacer cuando vuelva la conexión.
                    val online = state is RecipeDetailState.Ready && offline == null
                    val shown = offline?.view != null || state is RecipeDetailState.Ready
                    if (shown) {
                        IconButton(onClick = onEdit, enabled = online) {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = stringResource(R.string.detail_edit),
                            )
                        }
                        // Lo destructivo, apartado de lo frecuente: va en un menú.
                        var menuOpen by rememberSaveable { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }, enabled = online && !deleteState.deleting) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.detail_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen && online,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            // Añadir a la compra: secundaria y no destructiva, antes de borrar.
                            if (shopping.available) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.extras_compra_menu_add)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_tab_shopping),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        shoppingActions.onOpenClick()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.detail_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDeleteClick()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier.fillMaxSize()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // La duda de «añadir a la compra» («puede que ya se hayan añadido»), fija encima
            // del contenido y sin taparlo: es lo único que no puede irse solo. El éxito ya
            // no ocupa sitio aquí: lo confirma el aviso corto de la franja inferior.
            val shoppingNotice = shopping.notice
            val doubtNotice = shoppingNotice?.takeIf { it is AddToShoppingOutcome.MaybeAdded }
            if (doubtNotice != null) {
                AddToShoppingNoticeCard(
                    notice = doubtNotice,
                    onLook = shoppingActions.onOpenShopping,
                    onDismiss = shoppingActions.onNoticeDismiss,
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                val animationsEnabled = rememberAnimationsEnabled()
                AnimatedContent(
                    targetState = detailPane(state, offline != null),
                    modifier = contentModifier,
                    transitionSpec = {
                        // La receta (o su copia guardada) entra con un fundido corto. Cargando,
                        // el fallo, el 401 y el 404 se cambian de golpe: son regiones vivas y un
                        // error no se queda 120 ms esperando para contarse.
                        detailStateFade(
                            animate = animationsEnabled &&
                                (targetState == DetailPane.Ready || targetState == DetailPane.Offline),
                        )
                    },
                    label = "detalle-receta",
                ) { pane ->
                    val blockModifier = Modifier.fillMaxSize()
                    when (pane) {
                        // Sin red no se pueden pedir fotos: la copia no las guarda.
                        DetailPane.Offline -> {
                            val copy = offline
                            if (copy != null) {
                                RecipeDetailContent(
                                    view = copy.view,
                                    imageLoader = null,
                                    onMoreServings = onMoreServings,
                                    onFewerServings = onFewerServings,
                                    onOpenSource = onOpenSource,
                                    modifier = blockModifier,
                                    offline = copy,
                                    onRetry = onRetry,
                                )
                            }
                        }

                        DetailPane.Loading -> LoadingBlock(
                            text = stringResource(R.string.detail_loading),
                            modifier = blockModifier,
                        )

                        DetailPane.Problem -> {
                            val problem = (state as? RecipeDetailState.Failed)?.problem
                            if (problem != null) {
                                ProblemBlock(
                                    problem = problem,
                                    defaultTitle = R.string.problem_title_recipe,
                                    onRetry = onRetry,
                                    onOpenSettings = onOpenSettings,
                                    modifier = blockModifier,
                                )
                            }
                        }

                        // 401 y 403: lleva a Ajustes, no a reintentar.
                        DetailPane.Unauthorized -> ProblemBlock(
                            problem = ConnectionProblem.Unauthorized,
                            defaultTitle = R.string.problem_title_recipe,
                            onRetry = onRetry,
                            onOpenSettings = onOpenSettings,
                            modifier = blockModifier,
                        )

                        DetailPane.NotFound -> MessageBlock(
                            title = stringResource(R.string.detail_not_found_title),
                            text = stringResource(R.string.detail_not_found_text),
                            primaryLabel = stringResource(R.string.detail_not_found_action),
                            onPrimary = onBack,
                            modifier = blockModifier,
                        )

                        DetailPane.Ready -> {
                            val ready = state as? RecipeDetailState.Ready
                            if (ready != null) {
                                RecipeDetailContent(
                                    view = ready.view,
                                    imageLoader = imageLoader,
                                    onMoreServings = onMoreServings,
                                    onFewerServings = onFewerServings,
                                    onOpenSource = onOpenSource,
                                    modifier = blockModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteState.confirming && state is RecipeDetailState.Ready && offline == null) {
        DeleteRecipeDialog(
            name = state.view.name,
            deleting = deleteState.deleting,
            problem = deleteState.problem,
            rejected = deleteState.rejected,
            onConfirm = onDeleteConfirm,
            onDismiss = onDeleteDismiss,
            onOpenSettings = onOpenSettings,
        )
    }

    // Añadir a la compra: el diálogo se pinta solo si está abierto (y con la receta a la vista).
    val ready = state as? RecipeDetailState.Ready
    if (shopping.open && ready != null) {
        AddToShoppingDialog(
            state = shopping,
            title = stringResource(R.string.extras_compra_dialog_title),
            // Con raciones o por veces: lo dice el estado, que lo fijó el detalle.
            lead = stringResource(
                if (shopping.usesServings) {
                    R.string.extras_compra_dialog_lead
                } else {
                    R.string.extras_compra_dialog_lead_times
                },
                ready.view.name,
            ),
            showScale = true,
            showLists = true,
            onSelectList = shoppingActions.onSelectList,
            onScaleChange = shoppingActions.onScaleChange,
            onRetryLists = shoppingActions.onRetryLists,
            onConfirm = shoppingActions.onConfirm,
            onDismiss = shoppingActions.onDismiss,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun DeleteRecipeDialog(
    name: String,
    deleting: Boolean,
    problem: ConnectionProblem?,
    rejected: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.detail_delete_dialog_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(stringResource(R.string.detail_delete_dialog_text))
                if (problem != null) {
                    Text(
                        text = stringResource(errorTextRes(problem)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (rejected) {
                    Text(
                        text = stringResource(R.string.detail_delete_rejected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (deleting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(stringResource(R.string.detail_delete_deleting))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !deleting,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.detail_delete_dialog_cancel))
            }
        },
        confirmButton = {
            val unauthorized = problem == ConnectionProblem.Unauthorized
            TextButton(
                onClick = {
                    if (unauthorized) {
                        onDismiss()
                        onOpenSettings()
                    } else {
                        onConfirm()
                    }
                },
                enabled = !deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (deleting) BusyIndicator()
                Text(
                    stringResource(
                        when {
                            unauthorized -> R.string.problem_open_settings
                            problem != null || rejected -> R.string.detail_delete_retry
                            else -> R.string.detail_delete_dialog_confirm
                        },
                    ),
                )
            }
        },
    )
}

// ------------------------------------------------------------------ contenido

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeDetailContent(
    view: RecipeDetailView,
    imageLoader: ImageLoader?,
    onMoreServings: () -> Unit,
    onFewerServings: () -> Unit,
    onOpenSource: (SourceLink) -> Boolean,
    modifier: Modifier = Modifier,
    offline: OfflineCopyUi? = null,
    onRetry: () -> Unit = {},
) {
    val heroPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Sin red no hay foto: la copia no guarda imágenes, así que ni se intenta.
        if (view.imageRecipeId != null && offline == null) {
            // A sangre, pegada a la barra y con las esquinas de arriba redondeadas. Sin
            // texto encima: el nombre va justo debajo como encabezado y no se lee dos veces.
            RecipePhoto(
                recipeId = view.imageRecipeId,
                loader = imageLoader,
                maxSidePx = heroPx,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(HERO_ASPECT)
                    .clip(
                        // Las esquinas de arriba de `shapes.large`; abajo, rectas.
                        MaterialTheme.shapes.large.copy(
                            bottomStart = ZeroCornerSize,
                            bottomEnd = ZeroCornerSize,
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            if (offline != null) {
                OfflineNotice(copy = offline, onRetry = onRetry)
            }

            Text(
                text = view.name,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )

            val stars = view.ratingStars
            if (stars != null && stars > 0) {
                StarRating(stars = stars)
            }

            if (view.description.isNotEmpty()) {
                Text(
                    text = view.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (view.times.isNotEmpty()) {
                TimesBlock(view.times)
            }

            ServingsBlock(
                view = view,
                onMore = onMoreServings,
                onFewer = onFewerServings,
            )

            IngredientsBlock(view)
            StepsBlock(view)
            NotesBlock(view)

            if (view.categories.isNotEmpty()) {
                LabelsBlock(title = stringResource(R.string.detail_section_categories), labels = view.categories)
            }
            if (view.tags.isNotEmpty()) {
                LabelsBlock(title = stringResource(R.string.detail_section_tags), labels = view.tags)
            }

            val source = view.sourceLink
            if (source != null) {
                SourceBlock(link = source, onOpen = onOpenSource)
            }
        }
    }
}

/**
 * Aviso de que lo que se está viendo es una copia guardada: la fecha (ya en
 * texto, del núcleo), el motivo real si no fue simple falta de red, y «Volver a
 * intentar».
 *
 * Compacto a propósito: va arriba del todo y no puede empujar la receta fuera de
 * la pantalla. Una línea de fecha con el botón al lado y, debajo, el texto corto
 * (con el texto del sistema muy grande se omite). Mientras el reintento está en
 * marcha, la copia **sigue** a la vista, el botón se apaga y se anuncia.
 */
@Composable
private fun OfflineNotice(copy: OfflineCopyUi, onRetry: () -> Unit) {
    val largeText = isLargeText()
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                ) {
                    Text(
                        text = stringResource(R.string.detail_offline_banner, copy.cachedAtText),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (!largeText) {
                        Text(
                            text = stringResource(R.string.detail_offline_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val reason = copy.problem?.takeIf { it != ConnectionProblem.NoNetwork }
                    if (reason != null) {
                        Text(
                            text = stringResource(errorTextRes(reason)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                TextButton(
                    onClick = onRetry,
                    enabled = !copy.retrying,
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.problem_retry))
                }
            }
            if (copy.retrying) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.offline_retrying),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimesBlock(times: List<TimeLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        for (time in times) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { },
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(timeLabelRes(time.kind)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = time.text,
                    style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun timeLabelRes(kind: TimeKind): Int = when (kind) {
    TimeKind.Total -> R.string.detail_time_total
    TimeKind.Prep -> R.string.detail_time_prep
    // Cook y cualquier otro: solo hay tres tiempos; la variante en desuso del núcleo no se emite.
    else -> R.string.detail_time_cook
}

/**
 * Selector de raciones. Los botones miden 56 dp y se desactivan en los límites que
 * da el núcleo. Solo se lee el valor (región viva): TalkBack anuncia «Raciones: 6» tras
 * cada toque, que es la confirmación; el rótulo visible queda fuera de la semántica para
 * no decirlo dos veces. Con texto grande el rótulo pasa encima de los botones.
 */
@Composable
private fun ServingsBlock(view: RecipeDetailView, onMore: () -> Unit, onFewer: () -> Unit) {
    if (!view.canScale) {
        if (view.yieldText.isNotEmpty()) {
            Text(
                text = stringResource(R.string.detail_yield, view.yieldText),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }

    val label = stringResource(R.string.detail_servings)
    if (isLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            ServingsLabel(label)
            ServingsStepper(view, onMore, onFewer)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            ServingsLabel(label, Modifier.weight(1f))
            ServingsStepper(view, onMore, onFewer)
        }
    }
}

@Composable
private fun ServingsLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clearAndSetSemantics { },
    )
}

@Composable
private fun ServingsStepper(view: RecipeDetailView, onMore: () -> Unit, onFewer: () -> Unit) {
    val valueDescription = stringResource(R.string.detail_servings_value, view.servingsText)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        FilledTonalIconButton(
            onClick = onFewer,
            enabled = view.canDecreaseServings,
            modifier = Modifier.size(Sizes.StepperButton),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_remove),
                contentDescription = stringResource(R.string.detail_servings_less),
            )
        }
        Text(
            text = view.servingsText,
            style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = Sizes.StepperButton)
                .semantics {
                    contentDescription = valueDescription
                    liveRegion = LiveRegionMode.Polite
                },
        )
        FilledTonalIconButton(
            onClick = onMore,
            enabled = view.canIncreaseServings,
            modifier = Modifier.size(Sizes.StepperButton),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.detail_servings_more),
            )
        }
    }
}

/** Etiquetas y categorías: datos, no botones (por eso no piden 48 dp). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelsBlock(title: String, labels: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            for (label in labels) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ingredientes

@Composable
private fun IngredientsBlock(view: RecipeDetailView) {
    // Las casillas marcadas son estado de pantalla (índices de línea); sobreviven a un giro.
    var done by rememberSaveable(view.slug) { mutableStateOf(emptyList<Int>()) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SectionTitle(stringResource(R.string.detail_section_ingredients))
        if (view.ingredients.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_ingredients_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        view.ingredients.forEachIndexed { index, line ->
            IngredientItem(
                line = line,
                checked = index in done,
                onToggle = { done = if (index in done) done - index else done + index },
            )
        }
    }
}

@Composable
private fun IngredientItem(line: IngredientLine, checked: Boolean, onToggle: () -> Unit) {
    if (line.title.isNotEmpty()) {
        Text(
            text = line.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = Spacing.small)
                .semantics { heading() },
        )
    }
    if (line.text.isEmpty()) return

    val textColor = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val decoration = if (checked) TextDecoration.LineThrough else null
    val spoken = line.text
    // Con texto grande la columna de cantidades no cabe: la línea va entera, en un solo texto.
    val largeText = isLargeText()
    val quantityWidth = with(LocalDensity.current) { QuantityColumnWidth.toDp() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.IngredientRow)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            // TalkBack lee la frase entera («2 cucharadas aceite»), no cantidad y descripción por separado.
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        // La fila entera es el botón; la casilla solo muestra el estado y se agranda para verla bien.
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier
                .padding(Spacing.small)
                .scale(Sizes.CheckboxScale),
        )
        if (largeText || (line.quantityText.isEmpty() && line.description.isEmpty())) {
            Text(
                text = spoken,
                style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                color = textColor,
                textDecoration = decoration,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { },
            )
        } else {
            // Cantidades alineadas a la derecha en una columna de ancho fijo (crece con el tamaño de letra).
            Text(
                text = line.quantityText,
                style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                textDecoration = decoration,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .widthIn(min = quantityWidth)
                    .clearAndSetSemantics { },
            )
            Text(
                text = line.description,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                textDecoration = decoration,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { },
            )
        }
    }
}

// ----------------------------------------------------------------------- pasos

@Composable
private fun StepsBlock(view: RecipeDetailView) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        SectionTitle(stringResource(R.string.detail_section_steps))
        if (view.steps.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_steps_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        view.steps.forEachIndexed { index, step ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(
                    text = stringResource(R.string.detail_step_number, step.number),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (step.title.isNotEmpty()) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (step.summary.isNotEmpty()) {
                    Text(
                        text = step.summary,
                        style = MaterialTheme.typography.bodyLarge.merge(StepText),
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SanitizedHtmlContent(
                    html = step.body,
                    style = MaterialTheme.typography.bodyLarge.merge(StepText),
                )
                // El saneador tira las imágenes y los vídeos de los pasos (seguridad):
                // se dice en el paso para que el hueco no parezca un fallo de la app.
                // Una línea discreta, no un aviso de error.
                if (step.body.omittedMedia) {
                    Text(
                        text = stringResource(R.string.detail_step_media_omitted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesBlock(view: RecipeDetailView) {
    if (view.notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SectionTitle(stringResource(R.string.detail_section_notes))
        for (note in view.notes) {
            if (note.title.isNotEmpty()) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            SanitizedHtmlContent(html = note.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * Origen: el servidor de la receta como texto. El botón existe solo si el enlace es
 * https (`SourceLink.isHttps`, del núcleo) y abre el navegador del sistema con un toque;
 * si no se puede abrir, lo dice.
 */
@Composable
private fun SourceBlock(link: SourceLink, onOpen: (SourceLink) -> Boolean) {
    var openFailed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        SectionTitle(stringResource(R.string.detail_section_source))
        Text(
            text = stringResource(R.string.detail_source_text, link.host),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (link.isHttps) {
            OutlinedButton(
                onClick = { openFailed = !onOpen(link) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.detail_source_open))
            }
            if (openFailed) {
                StatusText(
                    text = stringResource(R.string.detail_source_open_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val HERO_ASPECT = 16f / 9f

// Ancho mínimo de la columna de cantidades, en sp para que crezca con la letra.
private val QuantityColumnWidth = 64.sp

// ------------------------------------------------------------------ previews

@Composable
private fun DetailPreview(
    state: RecipeDetailState,
    deleteState: RecipeDeleteUiState = RecipeDeleteUiState(),
    offline: OfflineCopyUi? = null,
    shopping: AddToShoppingUi = AddToShoppingUi(),
    shoppingActions: RecipeShoppingActions = RecipeShoppingActions(),
) {
    RecipeDetailScreen(
        state = state,
        deleteState = deleteState,
        offline = offline,
        imageLoader = null,
        onBack = {},
        onRetry = {},
        onMoreServings = {},
        onFewerServings = {},
        onOpenSource = { true },
        onOpenSettings = {},
        onEdit = {},
        onDeleteClick = {},
        onDeleteDismiss = {},
        onDeleteConfirm = {},
        shopping = shopping,
        shoppingActions = shoppingActions,
    )
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailLoadedPreview() {
    // Sin foto: la receta empieza directamente por el nombre.
    GastronomiconTheme { DetailPreview(RecipeDetailState.Ready(RecipePreviewData.detail)) }
}

// Con foto: el héroe va a sangre, con las esquinas de arriba redondeadas y sin texto
// encima. Sin loader no se descarga nada: se ve el marco, como antes de que llegue la foto.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeDetailPhotoPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Ready(RecipePreviewData.detailWithPhoto)) }
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeDetailPhotoDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        DetailPreview(RecipeDetailState.Ready(RecipePreviewData.detailWithPhoto))
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeDetailPhotoLargeTextPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Ready(RecipePreviewData.detailWithPhoto)) }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeDetailLoadedDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        DetailPreview(RecipeDetailState.Ready(RecipePreviewData.detail.moreServings().moreServings()))
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeDetailLoadedLargeTextPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Ready(RecipePreviewData.detail)) }
}

// Un paso que traía una imagen y el saneador la tiró: el detalle lo dice en el paso.
@Preview(showBackground = true, widthDp = 360)
@Composable
private fun RecipeDetailOmittedMediaPreview() {
    GastronomiconTheme {
        DetailPreview(
            RecipeDetailState.Ready(
                RecipeDetailView.from(RecipePreviewData.recipeWithOmittedMedia),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailLoadingPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Loading) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailOfflinePreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Failed(ConnectionProblem.NoNetwork),
            offline = OfflineCopyUi(
                view = RecipePreviewData.detail,
                cachedAtText = "hoy a las 20:14",
            ),
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeDetailOfflineDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        DetailPreview(
            state = RecipeDetailState.Failed(ConnectionProblem.NoNetwork),
            offline = OfflineCopyUi(
                view = RecipePreviewData.detail.moreServings(),
                cachedAtText = "ayer a las 9:05",
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeDetailOfflineLargeTextPreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Failed(ConnectionProblem.ServerNotResponding),
            offline = OfflineCopyUi(
                view = RecipePreviewData.detail,
                cachedAtText = "el 5 de noviembre de 2026",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailOfflineRetryingPreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Failed(ConnectionProblem.NoNetwork),
            offline = OfflineCopyUi(
                view = RecipePreviewData.detail.moreServings(),
                cachedAtText = "hoy a las 20:14",
                problem = ConnectionProblem.NoNetwork,
                retrying = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailOfflineCertificatePreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Failed(ConnectionProblem.SecureConnectionFailed),
            offline = OfflineCopyUi(
                view = RecipePreviewData.detail,
                cachedAtText = "ayer a las 9:05",
                problem = ConnectionProblem.SecureConnectionFailed,
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeDetailFailedLargeTextPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Failed(ConnectionProblem.NoNetwork)) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailUnauthorizedPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Unauthorized) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailNotFoundPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.NotFound) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailDeleteDialogPreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Ready(RecipePreviewData.detail),
            deleteState = RecipeDeleteUiState(confirming = true),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailDeleteFailedPreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Ready(RecipePreviewData.detail),
            deleteState = RecipeDeleteUiState(
                confirming = true,
                problem = ConnectionProblem.NoNetwork,
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun RecipeDetailAddToShoppingPreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Ready(RecipePreviewData.detail),
            shopping = AddToShoppingPreviewData.ui(open = true, scaleText = "2"),
            shoppingActions = RecipeShoppingActions(onOpenShopping = {}),
        )
    }
}

// El éxito de «añadir a la compra» ya es un aviso corto en la franja inferior (fase 16h):
// se pinta en el `snackbarHost` del `Scaffold` y se va solo. En la vista previa puede no
// salir, porque `showSnackbar` suspende; su aspecto está en las previews del componente
// (`ui/components/GastronomiconSnackbar.kt`), que pinta el mismo aviso real.
@Preview(showBackground = true, widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeDetailShoppingAddedDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        DetailPreview(
            state = RecipeDetailState.Ready(RecipePreviewData.detail),
            shopping = AddToShoppingPreviewData.ui(
                notice = AddToShoppingOutcome.Added("Compra de la semana", 1),
            ),
            shoppingActions = RecipeShoppingActions(onOpenShopping = {}),
        )
    }
}

// La duda sí ocupa sitio en la pantalla: se queda hasta que la persona la lea.
@Preview(showBackground = true, widthDp = 360, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeDetailShoppingMaybeAddedDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        DetailPreview(
            state = RecipeDetailState.Ready(RecipePreviewData.detail),
            shopping = AddToShoppingPreviewData.ui(
                notice = AddToShoppingOutcome.MaybeAdded(ConnectionProblem.NoNetwork),
            ),
            shoppingActions = RecipeShoppingActions(onOpenShopping = {}),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 2f)
@Composable
private fun RecipeDetailShoppingMaybeAddedLargeTextPreview() {
    GastronomiconTheme {
        DetailPreview(
            state = RecipeDetailState.Ready(RecipePreviewData.detail),
            shopping = AddToShoppingPreviewData.ui(
                notice = AddToShoppingOutcome.MaybeAdded(ConnectionProblem.ServerNotResponding),
            ),
            shoppingActions = RecipeShoppingActions(onOpenShopping = {}),
        )
    }
}
