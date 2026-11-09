package io.github.garfieldrockero.gastronomicon.ui.mealplan

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Planificador de comidas: une el [MealPlanViewModel] con la pantalla. No
 * decide dónde vive dentro de la app (pestaña, pantalla aparte…): eso está
 * pendiente de decisión y lo cablea quien edite la navegación.
 *
 * El ViewModel vive fuera de esta pantalla (en la actividad), así que ir a
 * Ajustes y volver no pierde la semana ni lo escrito en una hoja abierta. Solo
 * si Ajustes cambia la sesión, `MainActivity` debe llamar a
 * [MealPlanViewModel.reset].
 */
@Composable
fun MealPlanRoute(
    viewModel: MealPlanViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Lleva a la pestaña Compra desde el aviso de «ya están en la lista». Null
     * mientras la navegación no lo cablee: entonces el aviso no ofrece el botón.
     */
    onOpenShoppingTab: (() -> Unit)? = null,
    // Con la barra de pestañas debajo, quien la pinta quita el inset inferior (ya lo
    // consume la barra). Suelto, el valor por defecto es el de siempre.
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Primera carga al entrar; si la semana ya está cargada, no hace nada.
    LaunchedEffect(viewModel) { viewModel.onShown() }

    val actions = remember(viewModel, onOpenSettings, onOpenShoppingTab) {
        MealPlanActions(
            onRefresh = viewModel::onRefresh,
            onRetry = viewModel::onRetry,
            onPreviousWeek = viewModel::onPreviousWeek,
            onNextWeek = viewModel::onNextWeek,
            onToday = viewModel::onToday,
            onOpenSettings = onOpenSettings,
            onAddClick = viewModel::onAddClick,
            onEditClick = viewModel::onEditClick,
            onDeleteClick = viewModel::onDeleteClick,
            onDismissActionFailure = viewModel::onDismissActionFailure,
            onNoticeShown = viewModel::onNoticeShown,
            onShowNoticeWeek = viewModel::onShowNoticeWeek,
            onAddToShoppingClick = viewModel::onAddToShoppingClick,
            onAddToShoppingNoticeDismiss = viewModel::onAddToShoppingNoticeDismiss,
            onOpenShopping = onOpenShoppingTab,
            sheets = MealPlanSheetActions(
                onDismiss = viewModel::onDismissDialog,
                onAddDateChange = viewModel::onAddDateChange,
                onAddTypeChange = viewModel::onAddTypeChange,
                onAddKindChange = viewModel::onAddKindChange,
                onAddTitleChange = viewModel::onAddTitleChange,
                onAddTextChange = viewModel::onAddTextChange,
                onAddScaleChange = viewModel::onAddScaleChange,
                onPickRecipeOpen = viewModel::onPickRecipeOpen,
                onPickRecipeBack = viewModel::onPickRecipeBack,
                onRecipePicked = viewModel::onRecipePicked,
                onPickerQueryChange = viewModel::onPickerQueryChange,
                onPickerSearch = viewModel::onPickerSearch,
                onPickerRetry = viewModel::onPickerRetry,
                onPickerLoadMore = viewModel::onPickerLoadMore,
                onConfirmAdd = viewModel::onConfirmAdd,
                onConfirmDelete = viewModel::onConfirmDelete,
                onAddToShoppingSelectList = viewModel::onAddToShoppingListSelected,
                onAddToShoppingRetryLists = viewModel::onAddToShoppingRetryLists,
                onAddToShoppingConfirm = viewModel::onAddToShoppingConfirm,
                onAddToShoppingDismiss = viewModel::onAddToShoppingDismiss,
            ),
        )
    }

    MealPlanScreen(
        state = state,
        actions = actions,
        modifier = modifier,
        contentWindowInsets = contentWindowInsets,
    )
}
