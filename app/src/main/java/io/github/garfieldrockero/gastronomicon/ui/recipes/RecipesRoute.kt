package io.github.garfieldrockero.gastronomicon.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailState
import io.github.garfieldrockero.gastronomicon.platform.ImageLoader
import io.github.garfieldrockero.gastronomicon.platform.openInBrowser

/**
 * Último «volver arriba» ([RecipeListUiState.topRequest]) que ya se aplicó a la lista. Vive
 * fuera de [RecipesRoute] para que, al volver a la lista desde Ajustes o desde el detalle,
 * solo se desplace si hubo una petición nueva mientras no se veía.
 */
class TopRequestMemo(var handled: Int = 0)

/**
 * Recetas: la lista y, encima, el detalle de la receta abierta. Que haya detalle
 * lo dice [RecipeDetailViewModel] (`Closed` = se ve la lista), así que la
 * navegación general (`AppScreen`) no cambia: Recetas → Detalle es interno de esta
 * pantalla y «Atrás» (flecha o gesto) vuelve a la lista, que conserva su posición.
 *
 * Los dos ViewModels y el desplazamiento [scroll] viven fuera de esta pantalla (en la
 * actividad), así que ir a Ajustes y volver no pierde la lista ni la posición. Solo si
 * Ajustes cambia la sesión (o se vuelve a la primera configuración), `MainActivity` los
 * reinicia (`reset`/`close`).
 */
@Composable
fun RecipesRoute(
    listViewModel: RecipeListViewModel,
    detailViewModel: RecipeDetailViewModel,
    imageLoader: ImageLoader,
    scroll: LazyListState,
    topMemo: TopRequestMemo,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    val detailState by detailViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Primera carga al entrar; si ya hay recetas o se está cargando, no hace nada.
    LaunchedEffect(Unit) { listViewModel.onShown() }

    if (detailState is RecipeDetailState.Closed) {
        // «Volver arriba» solo con la lista en pantalla (con ella fuera, el LazyListState no
        // tiene diseño al que desplazar). Si hubo peticiones mientras no se veía, se aplica
        // al volver; se anota como hecha al terminar, así una interrupción la repite.
        LaunchedEffect(listState.topRequest) {
            if (listState.topRequest != topMemo.handled) {
                scroll.scrollToItem(0)
                topMemo.handled = listState.topRequest
            }
        }
        RecipeListScreen(
            state = listState,
            imageLoader = imageLoader,
            listState = scroll,
            onQueryChange = listViewModel::onQueryChange,
            onSubmitSearch = listViewModel::onSubmitSearch,
            onClearSearch = listViewModel::onClearSearch,
            onRefresh = listViewModel::onRefresh,
            onLoadMore = listViewModel::onLoadMore,
            onRetry = listViewModel::onRetry,
            onOpenRecipe = detailViewModel::open,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    } else {
        BackHandler { detailViewModel.close() }
        RecipeDetailScreen(
            state = detailState,
            imageLoader = imageLoader,
            onBack = detailViewModel::close,
            onRetry = detailViewModel::retry,
            onMoreServings = detailViewModel::onMoreServings,
            onFewerServings = detailViewModel::onFewerServings,
            // Solo con un toque explícito, y al navegador del sistema.
            onOpenSource = { link -> openInBrowser(context, link) },
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    }
}
