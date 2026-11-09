package io.github.garfieldrockero.gastronomicon.ui.shopping

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Lista de la compra conectada a su [ShoppingViewModel]. El ViewModel vive fuera
 * (en la actividad), así que ir a Ajustes y volver no pierde nada; solo si Ajustes
 * cambia la sesión, quien lo posee llama a [ShoppingViewModel.reset].
 *
 * Al mostrarse pide la primera carga, o repite la última si falló (por ejemplo,
 * tras arreglar un 401 en Ajustes).
 */
@Composable
fun ShoppingRoute(
    viewModel: ShoppingViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // Con la barra de pestañas debajo, quien la pinta quita el inset inferior (ya lo
    // consume la barra). Suelto, el valor por defecto es el de siempre.
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onShown() }

    ShoppingScreen(
        state = state,
        onToggle = viewModel::onToggle,
        onDraftChange = viewModel::onDraftChange,
        onAdd = viewModel::onAdd,
        onEditNote = viewModel::onEditNote,
        onDelete = viewModel::onDelete,
        onDeleteChecked = viewModel::onDeleteChecked,
        onRetry = viewModel::onRetry,
        onOpenSettings = onOpenSettings,
        onDismissProblem = viewModel::onDismissProblem,
        modifier = modifier,
        contentWindowInsets = contentWindowInsets,
    )
}
