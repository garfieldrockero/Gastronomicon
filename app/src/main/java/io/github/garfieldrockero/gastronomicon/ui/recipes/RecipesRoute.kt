package io.github.garfieldrockero.gastronomicon.ui.recipes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailState
import io.github.garfieldrockero.gastronomicon.platform.ImageLoader
import io.github.garfieldrockero.gastronomicon.platform.ImageRecoder
import io.github.garfieldrockero.gastronomicon.platform.ImportFileReader
import io.github.garfieldrockero.gastronomicon.platform.openInBrowser
import io.github.garfieldrockero.gastronomicon.ui.components.SnackbarNotice
import io.github.garfieldrockero.gastronomicon.ui.theme.motionTween
import io.github.garfieldrockero.gastronomicon.ui.theme.rememberAnimationsEnabled

/**
 * Último «volver arriba» ([RecipeListUiState.topRequest]) que ya se aplicó a la lista. Vive
 * fuera de [RecipesRoute] para que, al volver a la lista desde Ajustes o desde el detalle,
 * solo se desplace si hubo una petición nueva mientras no se veía.
 */
class TopRequestMemo(var handled: Int = 0)

/**
 * Recetas: lista, detalle y, encima, formulario o importación. Que haya detalle lo dice
 * [RecipeDetailViewModel] (`Closed` = se ve la lista); el formulario y la importación son
 * estados propios. Así la navegación general (`AppScreen`) no cambia y «Atrás» vuelve al
 * sitio anterior conservando la posición de la lista.
 *
 * Los ViewModels y el desplazamiento [scroll] viven fuera de esta pantalla (en la
 * actividad), así que ir a Ajustes y volver no pierde nada. Solo si Ajustes cambia la sesión
 * (o se vuelve a la primera configuración), `MainActivity` los reinicia.
 */
@Composable
fun RecipesRoute(
    listViewModel: RecipeListViewModel,
    detailViewModel: RecipeDetailViewModel,
    editorViewModel: RecipeEditorViewModel,
    importViewModel: RecipeImportViewModel,
    imageLoader: ImageLoader,
    scroll: LazyListState,
    topMemo: TopRequestMemo,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Lleva a la pestaña Compra (aviso de «ya están en la lista»). Null mientras la
     * navegación no lo cablee: entonces el aviso no ofrece el botón, en vez de
     * dejar un botón que no hace nada.
     */
    onOpenShoppingTab: (() -> Unit)? = null,
    // Solo para la lista: cuando hay barra de pestañas debajo, el inset inferior lo
    // consume esa barra. Detalle, formulario e importación siguen con el de siempre
    // (se ven sin barra).
    listContentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    val detailState by detailViewModel.state.collectAsStateWithLifecycle()
    val offlineCopy by detailViewModel.offlineCopy.collectAsStateWithLifecycle()
    val deleteState by detailViewModel.deleteState.collectAsStateWithLifecycle()
    val deleteNotice by detailViewModel.notice.collectAsStateWithLifecycle()
    val shoppingState by detailViewModel.shopping.collectAsStateWithLifecycle()
    val editorState by editorViewModel.state.collectAsStateWithLifecycle()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Lector del archivo que se elija para importar: solo lee lo que devuelve el
    // selector del sistema, con tope y en UTF-8 estricto (ver `ImportFileReader`).
    val importFileReader = remember(context) { ImportFileReader(context) }
    // Recodificador de las fotos de la importación con IA: las reduce, las endereza
    // y les quita los EXIF antes de que salgan del móvil (ver `ImageRecoder`).
    val importPhotoRecoder = remember(context) { ImageRecoder(context) }

    // Primera carga al entrar; si ya hay recetas o se está cargando, no hace nada.
    LaunchedEffect(Unit) { listViewModel.onShown() }

    // Guardado normal: refrescar la lista y abrir la receta con el slug que devolvió el núcleo.
    val savedEditor = editorState as? RecipeEditorState.Saved
    LaunchedEffect(savedEditor) {
        val saved = savedEditor ?: return@LaunchedEffect
        if (saved.warning == null) {
            listViewModel.onRefresh()
            detailViewModel.open(saved.slug)
            editorViewModel.close()
        }
    }

    // Importación terminada: abrir la receta importada y refrescar la lista.
    val importedSlug = importState.importedSlug
    LaunchedEffect(importedSlug) {
        val slug = importedSlug ?: return@LaunchedEffect
        listViewModel.onRefresh()
        detailViewModel.open(slug)
        importViewModel.close()
    }

    // Receta borrada: la lista se recarga y el detalle se cierra.
    LaunchedEffect(deleteState.deleted) {
        if (deleteState.deleted) {
            listViewModel.onRefresh()
            detailViewModel.close()
        }
    }

    // «Receta borrada» (fase 16h): un aviso de un solo uso que se pinta en la **lista**, que
    // es donde se queda la persona (el detalle se acaba de cerrar). El texto se resuelve
    // aquí porque el ViewModel no conoce los recursos; la clave es el id del ViewModel, que
    // cambia con cada aviso, para que no se pierda ni se repita al girar el móvil. Es una
    // confirmación: los errores y la copia sin conexión siguen en su tarjeta.
    val deletedNoticeText = stringResource(R.string.recipes_deleted_notice)
    val listNotice = deleteNotice.notice?.let {
        SnackbarNotice(id = deleteNotice.noticeId, message = deletedNoticeText)
    }

    // Foto subida desde el formulario: hay que olvidar la copia que hubiera en memoria
    // de esa receta y volver a leer el detalle (puede que antes no tuviera foto y su
    // `imageRecipeId` fuera nulo). El detalle vive debajo del formulario, así que
    // recargarlo no tapa lo que se está escribiendo.
    val photoUploads = (editorState as? RecipeEditorState.Editing)?.photo?.uploadedCount ?: 0
    LaunchedEffect(photoUploads) {
        if (photoUploads <= 0) return@LaunchedEffect
        val mode = (editorState as? RecipeEditorState.Editing)?.mode as? EditorMode.Edit
            ?: return@LaunchedEffect
        val previousId = (detailState as? RecipeDetailState.Ready)?.view?.imageRecipeId
        if (previousId != null) imageLoader.invalidate(previousId)
        detailViewModel.open(mode.slug)
    }

    when {
        editorState !is RecipeEditorState.Closed -> RecipeEditorScreen(
            state = editorState,
            actions = RecipeEditorActions(
                onRequestClose = {
                    val editing = editorState as? RecipeEditorState.Editing
                    val saved = editorState as? RecipeEditorState.Saved
                    // «Puede que ya se haya creado»: al salir, la lista se mira de nuevo.
                    val maybeCreated = editing?.maybeCreated == true
                    editorViewModel.requestClose()
                    if (saved != null || maybeCreated ||
                        (editing != null && editing.createdIncomplete && !editing.dirty)
                    ) {
                        listViewModel.onRefresh()
                    }
                },
                onRetryLoad = editorViewModel::onRetryLoad,
                onNameChange = editorViewModel::onNameChange,
                onDescriptionChange = editorViewModel::onDescriptionChange,
                onServingsChange = editorViewModel::onServingsChange,
                onTotalTimeChange = editorViewModel::onTotalTimeChange,
                onPrepTimeChange = editorViewModel::onPrepTimeChange,
                onCookTimeChange = editorViewModel::onCookTimeChange,
                onIngredientQuantityChange = editorViewModel::onIngredientQuantityChange,
                onIngredientNoteChange = editorViewModel::onIngredientNoteChange,
                // Selector de unidad y alimento (el ViewModel busca, crea y elige; la
                // pantalla solo avisa).
                onOpenUnitPicker = editorViewModel::onOpenUnitPicker,
                onOpenFoodPicker = editorViewModel::onOpenFoodPicker,
                onCloseCatalogPicker = editorViewModel::onCloseCatalogPicker,
                onCatalogQueryChange = editorViewModel::onCatalogQueryChange,
                onRetryCatalogPicker = editorViewModel::onRetryCatalogPicker,
                onPickCatalogEntry = editorViewModel::onPickCatalogEntry,
                onCreateCatalogEntry = editorViewModel::onCreateCatalogEntry,
                onAddIngredient = editorViewModel::onAddIngredient,
                onRemoveIngredient = editorViewModel::onRemoveIngredient,
                onMoveIngredientUp = editorViewModel::onMoveIngredientUp,
                onMoveIngredientDown = editorViewModel::onMoveIngredientDown,
                onStepTitleChange = editorViewModel::onStepTitleChange,
                onStepTextChange = editorViewModel::onStepTextChange,
                onAddStep = editorViewModel::onAddStep,
                onRemoveStep = editorViewModel::onRemoveStep,
                onMoveStepUp = editorViewModel::onMoveStepUp,
                onMoveStepDown = editorViewModel::onMoveStepDown,
                onToggleTag = editorViewModel::onToggleTag,
                onToggleCategory = editorViewModel::onToggleCategory,
                onRetryCatalogs = editorViewModel::onRetryCatalogs,
                onSave = editorViewModel::onSave,
                onConfirmDuplicateSave = editorViewModel::confirmDuplicateSave,
                onDismissDuplicateSave = editorViewModel::dismissDuplicateSave,
                onOpenSettings = onOpenSettings,
                onConfirmDiscard = {
                    val editing = editorState as? RecipeEditorState.Editing
                    val refresh = editing?.createdIncomplete == true || editing?.maybeCreated == true
                    editorViewModel.confirmDiscard()
                    if (refresh) listViewModel.onRefresh()
                },
                onDismissDiscard = editorViewModel::dismissDiscard,
                onReviewList = {
                    listViewModel.onRefresh()
                    editorViewModel.close()
                },
                onOpenSavedRecipe = { slug ->
                    listViewModel.onRefresh()
                    detailViewModel.open(slug)
                    editorViewModel.close()
                },
                onPhotoPicked = editorViewModel::onPhotoPicked,
                onPhotoProblem = editorViewModel::onPhotoProblem,
                onRetryPhoto = editorViewModel::onRetryPhoto,
                onRecoverDraft = editorViewModel::onRecoverDraft,
                onConfirmRecoverDraft = editorViewModel::confirmRecoverDraft,
                onDismissRecoverDraft = editorViewModel::dismissRecoverDraft,
                onDiscardRecoveredDraft = editorViewModel::onDiscardRecoveredDraft,
                onConfirmDiscardRecoveredDraft = editorViewModel::confirmDiscardRecoveredDraft,
                onDismissDiscardRecoveredDraft = editorViewModel::dismissDiscardRecoveredDraft,
                onEnterBackground = editorViewModel::saveDraftNow,
            ),
            modifier = modifier,
        )

        importState.visible -> RecipeImportScreen(
            state = importState,
            onBack = {
                val refresh = importState.importedSlug != null ||
                    importState.maybeCreated ||
                    importState.cancelled
                importViewModel.close()
                if (refresh) listViewModel.onRefresh()
            },
            onUrlChange = importViewModel::onUrlChange,
            onImport = importViewModel::onImport,
            onDataTextChange = importViewModel::onDataTextChange,
            onImportData = importViewModel::onImportData,
            // El lector vive aquí (necesita el contexto) y el ViewModel lanza la
            // lectura para poder cancelarla al cerrar o al cambiar de sesión.
            onFilePicked = { uri -> importViewModel.onFilePicked(uri, importFileReader) },
            onRemoveFile = importViewModel::onRemoveFile,
            onCancel = importViewModel::onCancel,
            onAiLinkChange = importViewModel::onAiLinkChange,
            onAiTextChange = importViewModel::onAiTextChange,
            // Igual que el lector: el ViewModel lanza la recodificación para poder
            // cancelarla al cerrar o al cambiar de sesión. El segundo valor dice si la
            // foto es un temporal de la cámara (hay que borrarlo al acabar).
            onAiPhotoPicked = { uri, fromCamera ->
                importViewModel.onAiPhotoPicked(uri, importPhotoRecoder, fromCamera)
            },
            onAiPhotoProblem = importViewModel::onAiPhotoProblem,
            onRemoveAiPhoto = importViewModel::onRemoveAiPhoto,
            onImportAi = importViewModel::onImportAi,
            onReviewList = {
                listViewModel.onRefresh()
                importViewModel.close()
            },
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )

        else -> {
            // Al abrir una receta, la lista se apaga y el detalle aparece con un desvanecido
            // de 120 ms (decisión de la persona), sin deslizamiento largo ni elemento
            // compartido. Al cerrar no hay fundido: el estado ya es `Closed` y el detalle no
            // tendría receta que desvanecer; volver a la lista es instantáneo, sin un
            // «Abriendo la receta…» de un tirón. Con las animaciones del sistema quitadas,
            // todo en el acto (`snap`).
            val animationsEnabled = rememberAnimationsEnabled()
            AnimatedContent(
                targetState = detailState is RecipeDetailState.Closed,
                modifier = modifier,
                transitionSpec = {
                    // `false` es el detalle (se está abriendo).
                    if (animationsEnabled && !targetState) {
                        fadeIn(animationSpec = motionTween(enabled = animationsEnabled)) togetherWith
                            fadeOut(animationSpec = motionTween(enabled = animationsEnabled))
                    } else {
                        fadeIn(animationSpec = snap()) togetherWith fadeOut(animationSpec = snap())
                    }
                },
                label = "receta",
            ) { showingList ->
                if (showingList) {
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
                        onCreateRecipe = editorViewModel::openCreate,
                        onImportRecipe = importViewModel::open,
                        notice = listNotice,
                        onNoticeShown = detailViewModel::onNoticeShown,
                        contentWindowInsets = listContentWindowInsets,
                    )
                } else if (detailState !is RecipeDetailState.Closed) {
                    // Al cerrar, la rama que sale ya no tiene receta: no se compone una
                    // pantalla de «Abriendo la receta…» para un fotograma.
                    BackHandler { detailViewModel.close() }
                    RecipeDetailScreen(
                        state = detailState,
                        deleteState = deleteState,
                        offline = offlineCopy,
                        imageLoader = imageLoader,
                        onBack = detailViewModel::close,
                        onRetry = detailViewModel::retry,
                        onMoreServings = detailViewModel::onMoreServings,
                        onFewerServings = detailViewModel::onFewerServings,
                        // Solo con un toque explícito, y al navegador del sistema.
                        onOpenSource = { link -> openInBrowser(context, link) },
                        onOpenSettings = onOpenSettings,
                        onEdit = {
                            val ready = detailState as? RecipeDetailState.Ready
                            if (ready != null) editorViewModel.openEdit(ready.view.slug)
                        },
                        onDeleteClick = detailViewModel::onDeleteClick,
                        onDeleteDismiss = detailViewModel::onDeleteDismiss,
                        onDeleteConfirm = detailViewModel::onDeleteConfirm,
                        shopping = shoppingState,
                        shoppingActions = RecipeShoppingActions(
                            onOpenClick = detailViewModel::onAddToShoppingClick,
                            onSelectList = detailViewModel::onAddToShoppingListSelected,
                            onScaleChange = detailViewModel::onAddToShoppingScaleChange,
                            onRetryLists = detailViewModel::onAddToShoppingRetryLists,
                            onConfirm = detailViewModel::onAddToShoppingConfirm,
                            onDismiss = detailViewModel::onAddToShoppingDismiss,
                            onNoticeDismiss = detailViewModel::onAddToShoppingNoticeDismiss,
                            onOpenShopping = onOpenShoppingTab,
                        ),
                    )
                }
            }
        }
    }
}
