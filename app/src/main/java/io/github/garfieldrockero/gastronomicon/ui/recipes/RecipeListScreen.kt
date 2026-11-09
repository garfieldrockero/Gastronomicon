package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.ListOperation
import io.github.garfieldrockero.gastronomicon.core.recipes.ListPhase
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListState
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummaryRow
import io.github.garfieldrockero.gastronomicon.platform.ImageLoader
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBlock
import io.github.garfieldrockero.gastronomicon.ui.components.RecipePhoto
import io.github.garfieldrockero.gastronomicon.ui.components.StarRating
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.isLargeText
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Lista de recetas. Sin lógica: pinta el estado del núcleo ([RecipeListUiState]) y
 * avisa de lo que hace la persona. El buscador va abajo, al alcance del pulgar.
 *
 * Estados: cargando, fallo (con acción), sin cobertura, sesión caducada (lleva a
 * Ajustes), lista vacía, sin coincidencias y lista con más páginas que se piden al
 * llegar al final.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RecipeListScreen(
    state: RecipeListUiState,
    imageLoader: ImageLoader?,
    listState: LazyListState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCreateRecipe: () -> Unit,
    onImportRecipe: () -> Unit,
    modifier: Modifier = Modifier,
    // Con la barra de pestañas debajo, quien la pinta quita aquí el inset inferior
    // (ya lo consume la barra). Suelto, el valor por defecto es el de siempre.
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    // Buscar y «Ver todas» cierran el teclado: si no, tapa los resultados que se acaban de pedir.
    val keyboard = LocalSoftwareKeyboardController.current
    val submitSearch = {
        keyboard?.hide()
        onSubmitSearch()
    }
    val clearSearch = {
        keyboard?.hide()
        onClearSearch()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Con el teclado abierto, el buscador sube con él.
        contentWindowInsets = contentWindowInsets,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.recipes_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                actions = {
                    IconButton(onClick = onImportRecipe) {
                        Icon(
                            painter = painterResource(R.drawable.ic_link),
                            contentDescription = stringResource(R.string.recipes_import),
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !state.list.isLoading) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.recipes_reload),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.nav_open_settings),
                        )
                    }
                },
            )
        },
        bottomBar = {
            SearchBar(
                query = state.query,
                serverSearch = state.list.search,
                onQueryChange = onQueryChange,
                onSubmit = submitSearch,
                onClearSearch = clearSearch,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateRecipe) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.recipes_create),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val reloadProblem = state.list.problem
            if (state.list.phase == ListPhase.Refreshing) {
                RefreshingBar()
            } else if (
                reloadProblem != null &&
                state.list.failedOperation == ListOperation.Refresh &&
                state.list.items.isNotEmpty()
            ) {
                // El fallo de Recargar se ve arriba, donde estaba la barra de progreso, y su
                // botón repite justo esa operación.
                ProblemBanner(
                    problem = reloadProblem,
                    message = stringResource(R.string.recipes_reload_failed),
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                    showDetail = !isLargeText(),
                )
            }
            // Sin conexión: lo que hay en la lista son las copias guardadas. Va arriba,
            // antes de las recetas, para que se sepa antes de tocar nada.
            val offline = state.offline
            if (offline != null) {
                OfflineListBanner(
                    banner = offline,
                    loading = state.list.isLoading,
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            } else if (state.offlineScopeHint) {
                // Sesión de antes del modo sin conexión: se dice cómo activarlo.
                OfflineScopeHint(
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }
            RecipeListContent(
                list = state.list,
                imageLoader = imageLoader,
                listState = listState,
                onSubmitSearch = submitSearch,
                onClearSearch = clearSearch,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                onOpenRecipe = onOpenRecipe,
                onOpenSettings = onOpenSettings,
                onCreateRecipe = onCreateRecipe,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ------------------------------------------------------------------ contenido

/**
 * Aviso de que la lista son las copias guardadas: la fecha de la más reciente
 * (ya en texto, del núcleo), qué se está viendo, el motivo real si no fue simple
 * falta de red y «Volver a intentar».
 *
 * Caja de color suave, no de error: no hay nada roto, solo falta cobertura. Se
 * anuncia al aparecer (región viva) para que no pase desapercibido.
 *
 * Con el texto del sistema muy grande se deja solo el título y el botón: si no,
 * el aviso se comería media pantalla (justo donde más falta hace la lista).
 * Mientras el reintento está en marcha, se ve en el propio aviso y el botón se
 * apaga, para que el toque no parezca no haber hecho nada.
 */
@Composable
private fun OfflineListBanner(
    banner: OfflineBanner,
    loading: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeText = isLargeText()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = stringResource(R.string.recipes_offline_banner, banner.cachedAtText),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (!largeText) {
                Text(
                    text = stringResource(R.string.recipes_offline_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // «Sin conexión» no siempre es falta de cobertura: con el certificado roto o
            // con HTTP local fuera de casa también se ve la copia, y hay que decirlo.
            val reason = banner.problem?.takeIf { it != ConnectionProblem.NoNetwork }
            if (reason != null) {
                Text(
                    text = stringResource(errorTextRes(reason)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onRetry,
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.problem_retry))
            }
            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(SmallSpinner),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
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

/**
 * Sin ámbito guardado no hay copias que enseñar: se explica cómo activarlas en
 * una línea, sin tapar el error de conexión que va debajo.
 */
@Composable
private fun OfflineScopeHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.recipes_offline_scope_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(Spacing.medium)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun RecipeListContent(
    list: RecipeListState,
    imageLoader: ImageLoader?,
    listState: LazyListState,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCreateRecipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = list.problem
    when {
        list.items.isEmpty() && problem != null && !list.isLoading -> ProblemBlock(
            problem = problem,
            defaultTitle = R.string.problem_title_default,
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )

        // También antes de la primera carga: la pantalla nunca se queda en blanco.
        list.items.isEmpty() && (list.isLoading || !list.loadedOnce) -> LoadingBlock(
            text = stringResource(R.string.recipes_loading),
            modifier = modifier,
        )

        list.isEmpty && list.search != null -> MessageBlock(
            title = stringResource(R.string.recipes_empty_search_title),
            text = stringResource(R.string.recipes_empty_search_text),
            primaryLabel = stringResource(R.string.recipes_search_all),
            onPrimary = onClearSearch,
            modifier = modifier,
        )

        list.isEmpty -> MessageBlock(
            title = stringResource(R.string.recipes_empty_title),
            text = stringResource(R.string.recipes_empty_text),
            primaryLabel = stringResource(R.string.recipes_empty_create),
            onPrimary = onCreateRecipe,
            secondaryLabel = stringResource(R.string.recipes_reload),
            onSecondary = onRefresh,
            modifier = modifier,
        )

        // Hay recetas cargadas, pero el filtro de la persona las esconde todas.
        list.visibleItems.isEmpty() -> MessageBlock(
            title = stringResource(R.string.recipes_no_match_title),
            text = stringResource(R.string.recipes_no_match_text),
            primaryLabel = stringResource(R.string.recipes_no_match_action),
            onPrimary = onSubmitSearch,
            modifier = modifier,
        )

        else -> RecipeLazyList(
            list = list,
            imageLoader = imageLoader,
            listState = listState,
            onLoadMore = onLoadMore,
            onSearchAll = onSubmitSearch,
            onRetry = onRetry,
            onOpenRecipe = onOpenRecipe,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    }
}

@Composable
private fun RecipeLazyList(
    list: RecipeListState,
    imageLoader: ImageLoader?,
    listState: LazyListState,
    onLoadMore: () -> Unit,
    onSearchAll: () -> Unit,
    onRetry: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Al acercarse al final se pide la página siguiente, pero solo cuando el núcleo lo permite
    // (`canAutoLoad`): no con un filtro puesto ni tras un fallo, para no martillear al servidor.
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - LOAD_MORE_MARGIN
        }
    }
    val autoLoad = list.canAutoLoad
    LaunchedEffect(nearEnd, autoLoad, list.items.size) {
        if (nearEnd && autoLoad) onLoadMore()
    }

    val photoPx = with(LocalDensity.current) { PhotoSize.roundToPx() }
    val problem = list.problem

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // Abajo, sitio para que el botón flotante no tape la última tarjeta ni «Cargar más».
        contentPadding = PaddingValues(
            start = Spacing.medium,
            top = Spacing.medium,
            end = Spacing.medium,
            bottom = 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        items(items = list.visibleRows, key = { it.slug }) { row ->
            RecipeRow(
                row = row,
                imageLoader = imageLoader,
                photoPx = photoPx,
                onClick = { onOpenRecipe(row.slug) },
            )
        }
        if (list.phase == ListPhase.LoadingMore) {
            item(key = FOOTER_LOADING_KEY) { LoadingMoreRow() }
        }
        // Filtrando solo se mira lo cargado: al final, la salida a todo el Mealie.
        if (list.showSearchAllHint) {
            item(key = FOOTER_SEARCH_ALL_KEY) {
                SearchAllRow(text = list.localFilter, onClick = onSearchAll)
            }
        }
        // El fallo de «cargar más» va al final; el de Recargar, arriba (ver RecipeListScreen).
        if (problem != null && list.failedOperation != ListOperation.Refresh) {
            item(key = FOOTER_PROBLEM_KEY) {
                ProblemBanner(
                    problem = problem,
                    message = stringResource(R.string.recipes_more_failed),
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeRow(
    row: RecipeSummaryRow,
    imageLoader: ImageLoader?,
    photoPx: Int,
    onClick: () -> Unit,
) {
    val recipe = row.summary
    // Con texto grande la foto se achica para dejarle sitio al nombre.
    val photoSize = if (isLargeText()) PhotoSizeLargeText else PhotoSize
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = photoSize + Spacing.small * 2),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecipePhoto(
                recipeId = if (recipe.hasImage) recipe.id else null,
                loader = imageLoader,
                maxSidePx = photoPx,
                modifier = Modifier
                    .size(photoSize)
                    .clip(RoundedCornerShape(PhotoCorner)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
            ) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (recipe.description.isNotEmpty()) {
                    Text(
                        text = recipe.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Tiempo total y valoración, ya calculados por el núcleo.
                val stars = row.ratingStars
                val showStars = stars != null && stars > 0
                if (row.totalTimeText.isNotEmpty() || showStars) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    ) {
                        if (row.totalTimeText.isNotEmpty()) {
                            Text(
                                text = row.totalTimeText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (stars != null && showStars) {
                            StarRating(stars = stars, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** Salida de un filtro sin suerte: preguntar a todo el Mealie con el mismo texto. */
@Composable
private fun SearchAllRow(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.MinTouchTarget),
    ) {
        Text(
            text = stringResource(R.string.recipes_search_all_hint, text),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingMoreRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.small)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(SmallSpinner),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
        Text(
            text = stringResource(R.string.recipes_loading_more),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RefreshingBar() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.recipes_refreshing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.extraSmall),
        )
    }
}

// ------------------------------------------------------------------ buscador

/**
 * Buscador abajo del todo. Cada letra filtra lo que ya está cargado, sin red; la
 * lupa (o «Buscar» del teclado) pregunta a todo el Mealie. «Ver todas» aparece
 * solo mientras hay una búsqueda del servidor activa ([serverSearch], ya limpia).
 * Quien lo usa cierra el teclado en [onSubmit] y [onClearSearch].
 */
@Composable
private fun SearchBar(
    query: String,
    serverSearch: String?,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearSearch: () -> Unit,
) {
    // El relleno de la barra de navegación va DENTRO del Surface: así el fondo del buscador
    // llega hasta el borde de la pantalla y no queda una franja de otro color debajo.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            if (serverSearch != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(
                        text = stringResource(R.string.recipes_search_active, serverSearch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onClearSearch,
                        modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                    ) {
                        Text(stringResource(R.string.recipes_search_all))
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.recipes_search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.recipes_search_clear_text),
                                )
                            }
                        }
                        IconButton(onClick = onSubmit) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = stringResource(R.string.recipes_search_submit),
                            )
                        }
                    }
                },
            )
        }
    }
}

// Cuántas filas antes del final se pide la página siguiente.
private const val LOAD_MORE_MARGIN = 3
private const val FOOTER_LOADING_KEY = "footer-loading"
private const val FOOTER_PROBLEM_KEY = "footer-problem"
private const val FOOTER_SEARCH_ALL_KEY = "footer-search-all"

private val PhotoSize = 88.dp
private val PhotoSizeLargeText = 56.dp
private val PhotoCorner = 12.dp
private val SmallSpinner = 24.dp

// ------------------------------------------------------------------ previews

private fun previewState(list: RecipeListState, query: String = "") = RecipeListUiState(list, query)

private val loadedList = RecipeListState(
    items = RecipePreviewData.summaries,
    lastPage = 1,
    hasMore = true,
    total = 40,
    loadedOnce = true,
)

@Composable
private fun ListPreview(state: RecipeListUiState) {
    RecipeListScreen(
        state = state,
        imageLoader = null,
        listState = rememberLazyListState(),
        onQueryChange = {},
        onSubmitSearch = {},
        onClearSearch = {},
        onRefresh = {},
        onLoadMore = {},
        onRetry = {},
        onOpenRecipe = {},
        onOpenSettings = {},
        onCreateRecipe = {},
        onImportRecipe = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun RecipeListLoadedPreview() {
    GastronomiconTheme { ListPreview(previewState(loadedList.copy(hasMore = false))) }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecipeListLoadedDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) { ListPreview(previewState(loadedList.copy(hasMore = false))) }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeListLoadedLargeTextPreview() {
    GastronomiconTheme { ListPreview(previewState(loadedList.copy(phase = ListPhase.LoadingMore))) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListLoadingPreview() {
    GastronomiconTheme {
        ListPreview(previewState(RecipeListState(phase = ListPhase.LoadingFirst)))
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeListLoadingLargeTextPreview() {
    GastronomiconTheme {
        ListPreview(previewState(RecipeListState(phase = ListPhase.LoadingFirst)))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListOfflinePreview() {
    GastronomiconTheme {
        ListPreview(
            RecipeListUiState(
                list = loadedList.copy(hasMore = false),
                query = "",
                offline = OfflineBanner(cachedAtText = "hoy a las 20:14"),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeListOfflineLargeTextPreview() {
    GastronomiconTheme {
        ListPreview(
            RecipeListUiState(
                list = loadedList.copy(hasMore = false),
                query = "",
                offline = OfflineBanner(cachedAtText = "el 5 de noviembre de 2026"),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListOfflineRetryingPreview() {
    GastronomiconTheme {
        ListPreview(
            RecipeListUiState(
                list = loadedList.copy(hasMore = false, phase = ListPhase.LoadingFirst),
                query = "",
                offline = OfflineBanner(cachedAtText = "hoy a las 20:14"),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListOfflineCertificatePreview() {
    GastronomiconTheme {
        ListPreview(
            RecipeListUiState(
                list = loadedList.copy(hasMore = false),
                query = "",
                offline = OfflineBanner(
                    cachedAtText = "ayer a las 9:05",
                    problem = ConnectionProblem.SecureConnectionFailed,
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListOfflineScopeHintPreview() {
    GastronomiconTheme {
        ListPreview(
            RecipeListUiState(
                list = RecipeListState(problem = ConnectionProblem.NoNetwork, loadedOnce = true),
                query = "",
                offlineScopeHint = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListEmptyPreview() {
    GastronomiconTheme { ListPreview(previewState(RecipeListState(loadedOnce = true))) }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeListEmptyLargeTextPreview() {
    GastronomiconTheme { ListPreview(previewState(RecipeListState(loadedOnce = true))) }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListEmptySearchPreview() {
    GastronomiconTheme {
        ListPreview(previewState(RecipeListState(search = "paella", loadedOnce = true), query = "paella"))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListNoMatchPreview() {
    GastronomiconTheme {
        ListPreview(previewState(loadedList.withLocalFilter("zzz"), query = "zzz"))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListNoNetworkPreview() {
    GastronomiconTheme {
        ListPreview(previewState(RecipeListState(problem = ConnectionProblem.NoNetwork, loadedOnce = true)))
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun RecipeListErrorLargeTextPreview() {
    GastronomiconTheme {
        ListPreview(previewState(RecipeListState(problem = ConnectionProblem.ServerNotResponding, loadedOnce = true)))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListUnauthorizedPreview() {
    GastronomiconTheme {
        ListPreview(previewState(RecipeListState(problem = ConnectionProblem.Unauthorized, loadedOnce = true)))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListMoreFailedPreview() {
    GastronomiconTheme {
        ListPreview(
            previewState(
                loadedList.copy(problem = ConnectionProblem.NoNetwork, failedOperation = ListOperation.LoadMore),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListReloadFailedPreview() {
    GastronomiconTheme {
        ListPreview(
            previewState(
                loadedList.copy(problem = ConnectionProblem.ServerNotResponding, failedOperation = ListOperation.Refresh),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListFilteringPreview() {
    GastronomiconTheme {
        ListPreview(previewState(loadedList.withLocalFilter("arroz"), query = "arroz"))
    }
}

@Preview(showBackground = true)
@Composable
private fun RecipeListServerSearchPreview() {
    GastronomiconTheme {
        ListPreview(
            previewState(
                loadedList.copy(search = "arroz", hasMore = false),
                query = "arroz",
            ),
        )
    }
}
