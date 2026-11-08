package io.github.garfieldrockero.gastronomicon.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Primera configuración con su [SetupViewModel]. [onSaved] se llama una vez
 * guardada la sesión; a dónde se va lo decide la navegación de la raíz.
 */
@Composable
fun SetupRoute(
    viewModel: SetupViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.status) {
        if (state.status == SetupStatus.Saved) onSaved()
    }
    SetupScreen(
        state = state,
        onServerChange = viewModel::onServerChange,
        onPortChange = viewModel::onPortChange,
        onAllowLocalHttpChange = viewModel::onAllowLocalHttpChange,
        onTokenChange = viewModel::onTokenChange,
        onTestConnection = viewModel::onTestConnection,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}
