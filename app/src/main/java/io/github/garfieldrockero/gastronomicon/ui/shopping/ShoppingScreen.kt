package io.github.garfieldrockero.gastronomicon.ui.shopping

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingInputProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingLine
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingText
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.isLargeText
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.LocalExtendedColors
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.TabularNumbers
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Lista de la compra. Sin lógica: pinta el estado del núcleo ([ShoppingUiState]) y
 * avisa de lo que hace la persona. Pensada para el súper con una mano: el campo de
 * añadir va abajo, al alcance del pulgar, y cada fila entera es la casilla.
 *
 * Estados: cargando, fallo (con acción), sin cobertura, sesión caducada (lleva a
 * Ajustes), sin listas, lista vacía y lista con contenido. Un fallo al escribir no tapa
 * la lista: sale como aviso encima con su acción.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(
    state: ShoppingUiState,
    onToggle: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEditNote: (itemId: String, note: String, quantity: Double) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteChecked: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissProblem: () -> Unit,
    modifier: Modifier = Modifier,
    // Con la barra de pestañas debajo, quien la pinta quita aquí el inset inferior
    // (ya lo consume la barra). Suelto, el valor por defecto es el de siempre.
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val list = state.list
    val addFocus = remember { FocusRequester() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Con el teclado abierto, el campo de añadir sube con él.
        contentWindowInsets = contentWindowInsets,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (list.loadedOnce) list.view.listName else stringResource(R.string.shopping_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                actions = {
                    IconButton(onClick = onRetry, enabled = !list.isLoading && !state.resolving) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.shopping_reload),
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
            if (list.listId != null && list.loadedOnce) {
                AddBar(
                    draft = state.draft,
                    adding = list.adding,
                    onDraftChange = onDraftChange,
                    onAdd = onAdd,
                    focus = addFocus,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ShoppingBody(
                state = state,
                onToggle = onToggle,
                onEdit = { editingId = it },
                onClearChecked = { confirmClear = true },
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                onDismissProblem = onDismissProblem,
                onFocusAdd = { addFocus.requestFocus() },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (confirmClear) {
        ClearCheckedDialog(
            count = list.checkedCount,
            onConfirm = {
                confirmClear = false
                onDeleteChecked()
            },
            onDismiss = { confirmClear = false },
        )
    }

    // El diálogo se busca por id: si el elemento desaparece (se borró o la lista se recargó), se cierra solo.
    val editingLine = editingId?.let { id ->
        list.view.sections.asSequence().flatMap { it.lines.asSequence() }.firstOrNull { it.itemId == id }
    }
    if (editingLine != null) {
        EditItemDialog(
            line = editingLine,
            onSave = { note ->
                editingId = null
                onEditNote(editingLine.itemId, note, editingLine.quantity)
            },
            onDelete = {
                editingId = null
                onDelete(editingLine.itemId)
            },
            onDismiss = { editingId = null },
        )
    }
}

// ------------------------------------------------------------------ contenido

@Composable
private fun ShoppingBody(
    state: ShoppingUiState,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
    onClearChecked: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissProblem: () -> Unit,
    onFocusAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = state.list
    val problem = list.problem
    val resolveProblem = state.resolveProblem
    when {
        resolveProblem != null -> ShoppingProblemBlock(resolveProblem, onRetry, onOpenSettings, modifier)

        state.noList -> MessageBlock(
            title = stringResource(R.string.shopping_no_list_title),
            text = stringResource(R.string.shopping_no_list_text),
            primaryLabel = stringResource(R.string.shopping_no_list_action),
            onPrimary = onRetry,
            modifier = modifier,
        )

        // Sin lista cargada todavía y con un fallo: pantalla de fallo completa.
        !list.loadedOnce && problem != null && !list.isLoading -> {
            val connection = problem.asConnectionProblem()
            if (connection != null) {
                ShoppingProblemBlock(connection, onRetry, onOpenSettings, modifier)
            } else {
                MessageBlock(
                    title = stringResource(R.string.shopping_problem_title),
                    text = stringResource(problem.messageRes()),
                    primaryLabel = stringResource(R.string.problem_retry),
                    onPrimary = onRetry,
                    modifier = modifier,
                )
            }
        }

        // También antes de la primera carga: la pantalla nunca se queda en blanco.
        !list.loadedOnce -> LoadingBlock(
            text = stringResource(R.string.shopping_loading),
            modifier = modifier,
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            if (list.phase == ShoppingPhase.Refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (problem != null) {
                ProblemNotice(
                    problem = problem,
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings,
                    onDismiss = onDismissProblem,
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }
            if (list.view.isEmpty) {
                MessageBlock(
                    title = stringResource(R.string.shopping_empty_title),
                    text = stringResource(R.string.shopping_empty_text),
                    primaryLabel = stringResource(R.string.shopping_empty_action),
                    onPrimary = onFocusAdd,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ShoppingLines(
                    list = list,
                    onToggle = onToggle,
                    onEdit = onEdit,
                    onClearChecked = onClearChecked,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ShoppingLines(
    list: ShoppingListState,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
    onClearChecked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = list.view
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = Spacing.small),
    ) {
        item(key = "summary") {
            Summary(
                pending = view.pendingCount,
                checked = view.checkedCount,
                allChecked = view.allChecked,
            )
        }
        if (view.hasChecked) {
            item(key = "clear") {
                ClearCheckedButton(
                    count = view.checkedCount,
                    clearing = list.clearingChecked,
                    enabled = !list.isBusy && !list.isLoading,
                    onClick = onClearChecked,
                )
            }
        }
        for ((index, section) in view.sections.withIndex()) {
            if (section.title.isNotEmpty()) {
                item(key = "section-$index") {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.medium, bottom = Spacing.extraSmall)
                            .semantics { heading() },
                    )
                }
            }
            items(items = section.lines, key = { it.itemId }) { line ->
                ShoppingRow(
                    line = line,
                    busy = line.itemId in list.busyItemIds,
                    onToggle = { onToggle(line.itemId) },
                    onEdit = { onEdit(line.itemId) },
                )
            }
        }
    }
}

/**
 * «Faltan 3 cosas · 2 en el carro». Un solo texto con liveRegion: al marcar, el lector
 * de pantalla lee el recuento nuevo, y a la vista es la confirmación de que se ha apuntado.
 */
@Composable
private fun Summary(pending: Int, checked: Int, allChecked: Boolean) {
    val text = if (allChecked) {
        stringResource(R.string.shopping_all_done)
    } else {
        val left = pluralStringResource(R.plurals.shopping_pending, pending, pending)
        if (checked > 0) {
            left + " · " + pluralStringResource(R.plurals.shopping_checked, checked, checked)
        } else {
            left
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
        color = if (allChecked) LocalExtendedColors.current.success else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.small)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ClearCheckedButton(count: Int, clearing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !clearing,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ActionMinHeight)
            .padding(vertical = Spacing.extraSmall)
            .semantics { if (clearing) liveRegion = LiveRegionMode.Polite },
    ) {
        if (clearing) {
            BusyIndicator()
            Text(stringResource(R.string.shopping_clearing))
        } else {
            Text(stringResource(R.string.shopping_clear_button, count))
        }
    }
}

/**
 * Una cosa de la lista. La fila entera es la casilla (pulgar cómodo, 64 dp de alto
 * como mínimo); el lápiz de la derecha abre la edición y se apaga mientras esa fila
 * tiene una escritura en marcha (el núcleo ya ignora un segundo toque sobre ella). Marcada: casilla, tachado y
 * color apagado, nunca solo el color.
 */
@Composable
private fun ShoppingRow(
    line: ShoppingLine,
    busy: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val checkedState = stringResource(R.string.shopping_item_checked_state)
    val uncheckedState = stringResource(R.string.shopping_item_unchecked_state)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RowMinHeight)
                    .toggleable(
                        value = line.checked,
                        role = Role.Checkbox,
                        onValueChange = { onToggle() },
                    )
                    .semantics { stateDescription = if (line.checked) checkedState else uncheckedState },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // La fila entera es el botón; la casilla solo muestra el estado y se agranda para verla bien.
                Checkbox(
                    checked = line.checked,
                    onCheckedChange = null,
                    modifier = Modifier
                        .padding(horizontal = Spacing.medium, vertical = Spacing.small)
                        .scale(CHECKBOX_SCALE),
                )
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (line.checked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (line.checked) TextDecoration.LineThrough else null,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = Spacing.small),
                )
            }
            IconButton(
                onClick = onEdit,
                enabled = !busy,
                modifier = Modifier.size(Sizes.MinTouchTarget),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.shopping_item_edit, line.text),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ------------------------------------------------------------------ añadir

@Composable
private fun AddBar(
    draft: String,
    adding: Boolean,
    onDraftChange: (String) -> Unit,
    onAdd: () -> Unit,
    focus: FocusRequester,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // Fuera de la zona de gestos/botones del sistema y por encima del teclado.
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text(stringResource(R.string.shopping_add_label)) },
                singleLine = true,
                // readOnly y no enabled: un campo deshabilitado pierde el foco y cierra el teclado.
                readOnly = adding,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
            )
            FilledIconButton(
                onClick = onAdd,
                enabled = !adding && draft.isNotBlank(),
                modifier = Modifier.size(AddButtonSize),
            ) {
                if (adding) {
                    val addingText = stringResource(R.string.shopping_adding)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .semantics {
                                contentDescription = addingText
                                liveRegion = LiveRegionMode.Polite
                            },
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.shopping_add_button),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ diálogos

@Composable
private fun ClearCheckedDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shopping_clear_dialog_title)) },
        text = { Text(pluralStringResource(R.plurals.shopping_clear_dialog_text, count, count)) },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                Text(stringResource(R.string.shopping_dialog_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.shopping_clear_dialog_confirm))
            }
        },
    )
}

@Composable
private fun EditItemDialog(
    line: ShoppingLine,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // `remember` y no `rememberSaveable`: la nota es texto del servidor y no
    // tiene por qué acabar serializada en el estado guardado.
    var note by remember(line.itemId) { mutableStateOf(line.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shopping_edit_title)) },
        text = {
            // Con texto grande el contenido no cabe: se desplaza.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = note,
                    // El tope es el del núcleo: nunca se escribe más de lo que se aceptaría.
                    onValueChange = { note = it.take(ShoppingText.MAX_ITEM_TEXT) },
                    label = { Text(stringResource(R.string.shopping_edit_label)) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Quitar no va pegado al campo: con texto grande y el pulgar, se toca de paso.
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = Spacing.medium),
                )
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.shopping_edit_delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                Text(stringResource(R.string.shopping_dialog_cancel))
            }
        },
        confirmButton = {
            Button(onClick = { onSave(note) }, modifier = Modifier.heightIn(min = Sizes.MinTouchTarget)) {
                Text(stringResource(R.string.shopping_edit_save))
            }
        },
    )
}

// ------------------------------------------------------------------ fallos

/**
 * Fallo de conexión a pantalla completa. Igual que el de recetas, pero con el aviso de
 * «sin cobertura» propio de la compra (el de recetas habla de recetas).
 */
@Composable
private fun ShoppingProblemBlock(
    problem: ConnectionProblem,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unauthorized = problem == ConnectionProblem.Unauthorized
    MessageBlock(
        title = stringResource(
            when (problem) {
                ConnectionProblem.NoNetwork -> R.string.problem_title_no_network
                ConnectionProblem.ServerNotResponding -> R.string.problem_title_not_responding
                ConnectionProblem.Unauthorized -> R.string.problem_title_unauthorized
                else -> R.string.shopping_problem_title
            },
        ),
        text = stringResource(if (unauthorized) R.string.problem_text_unauthorized else errorTextRes(problem)),
        hint = if (problem == ConnectionProblem.NoNetwork) stringResource(R.string.shopping_offline_hint) else null,
        primaryLabel = stringResource(if (unauthorized) R.string.problem_open_settings else R.string.problem_retry),
        onPrimary = if (unauthorized) onOpenSettings else onRetry,
        secondaryLabel = if (unauthorized) null else stringResource(R.string.problem_open_settings),
        onSecondary = onOpenSettings,
        modifier = modifier,
    )
}

/** Fallo con la lista a la vista: aviso arriba, sin tapar lo que hay. */
@Composable
private fun ProblemNotice(
    problem: ShoppingProblem,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = problem.asConnectionProblem()
    if (connection != null) {
        ProblemBanner(
            problem = connection,
            message = stringResource(R.string.shopping_change_failed),
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
            showDetail = !isLargeText(),
        )
        return
    }
    // Rechazos de la app (texto vacío…) no piden recargar; el resto sí.
    val canReload = problem !is ShoppingProblem.InputRejected
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
            Text(
                text = stringResource(problem.messageRes()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (canReload) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.shopping_reload))
                }
            } else {
                // Un rechazo de la app se lee y se cierra: no hay nada que recargar.
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(stringResource(R.string.shopping_problem_dismiss))
                }
            }
        }
    }
}

/** El motivo de conexión si el fallo es de conexión o de sesión; null si es otro tipo de rechazo. */
private fun ShoppingProblem.asConnectionProblem(): ConnectionProblem? = when (this) {
    ShoppingProblem.Unauthorized -> ConnectionProblem.Unauthorized
    is ShoppingProblem.Failed -> problem
    else -> null
}

/** Texto de los rechazos que no son de conexión. Sin «else»: un motivo nuevo no compila hasta tener su texto. */
private fun ShoppingProblem.messageRes(): Int = when (this) {
    ShoppingProblem.Unauthorized -> R.string.problem_text_unauthorized
    ShoppingProblem.NotFound -> R.string.shopping_problem_not_found
    is ShoppingProblem.InvalidData -> R.string.shopping_problem_rejected
    is ShoppingProblem.Rejected -> R.string.shopping_problem_rejected
    is ShoppingProblem.InputRejected -> when (reason) {
        ShoppingInputProblem.EmptyText -> R.string.shopping_problem_empty_text
        ShoppingInputProblem.TextTooLong -> R.string.shopping_problem_too_long
        ShoppingInputProblem.InvalidQuantity -> R.string.shopping_problem_invalid_quantity
    }
    is ShoppingProblem.Failed -> R.string.shopping_change_failed
}

private val RowMinHeight = 64.dp
private val ActionMinHeight = 56.dp
private val AddButtonSize = 56.dp
private const val CHECKBOX_SCALE = 1.4f

// ------------------------------------------------------------------ previews

private const val PREVIEW_LIST_ID = "00000000-0000-4000-8000-000000000001"

private fun loaded(
    items: List<ShoppingItem>,
    problem: ShoppingProblem? = null,
    phase: ShoppingPhase = ShoppingPhase.Idle,
    busy: Set<String> = emptySet(),
    adding: Boolean = false,
    clearing: Boolean = false,
) = ShoppingListState(
    listId = PREVIEW_LIST_ID,
    listName = "Compra de la semana",
    items = items,
    phase = phase,
    problem = problem,
    loadedOnce = true,
    busyItemIds = busy,
    adding = adding,
    clearingChecked = clearing,
    clearingItemIds = if (clearing) items.filter { it.checked }.mapTo(HashSet()) { it.id } else emptySet(),
)

@Composable
private fun ShoppingPreview(state: ShoppingUiState) {
    ShoppingScreen(
        state = state,
        onToggle = {},
        onDraftChange = {},
        onAdd = {},
        onEditNote = { _, _, _ -> },
        onDelete = {},
        onDeleteChecked = {},
        onRetry = {},
        onOpenSettings = {},
        onDismissProblem = {},
    )
}

private fun ui(list: ShoppingListState, draft: String = "") = ShoppingUiState(list = list, draft = draft)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingSectionsPreview() {
    GastronomiconTheme { ShoppingPreview(ui(loaded(ShoppingPreviewData.withSections))) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShoppingSectionsDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) { ShoppingPreview(ui(loaded(ShoppingPreviewData.withSections))) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingLargeTextPreview() {
    GastronomiconTheme { ShoppingPreview(ui(loaded(ShoppingPreviewData.withoutSections), draft = "Leche")) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingBusyPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(
                    ShoppingPreviewData.withSections,
                    busy = setOf(ShoppingPreviewData.withSections[0].id),
                    adding = true,
                ),
                draft = "Aceite de oliva",
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingClearingPreview() {
    GastronomiconTheme {
        ShoppingPreview(ui(loaded(ShoppingPreviewData.withSections, clearing = true)))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingAllCheckedPreview() {
    GastronomiconTheme { ShoppingPreview(ui(loaded(ShoppingPreviewData.allChecked))) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingRefreshingPreview() {
    GastronomiconTheme {
        ShoppingPreview(ui(loaded(ShoppingPreviewData.withSections, phase = ShoppingPhase.Refreshing)))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingEmptyPreview() {
    GastronomiconTheme { ShoppingPreview(ui(loaded(emptyList()))) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingEmptyLargeTextPreview() {
    GastronomiconTheme { ShoppingPreview(ui(loaded(emptyList()))) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingLoadingPreview() {
    GastronomiconTheme { ShoppingPreview(ShoppingUiState(resolving = true)) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingLoadingLargeTextPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ShoppingUiState(list = ShoppingListState(listId = PREVIEW_LIST_ID, phase = ShoppingPhase.LoadingFirst)),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingNoListPreview() {
    GastronomiconTheme { ShoppingPreview(ShoppingUiState(noList = true)) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingNoNetworkPreview() {
    GastronomiconTheme { ShoppingPreview(ShoppingUiState(resolveProblem = ConnectionProblem.NoNetwork)) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingNotRespondingLargeTextPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ShoppingUiState(
                list = ShoppingListState(
                    listId = PREVIEW_LIST_ID,
                    problem = ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingUnauthorizedPreview() {
    GastronomiconTheme { ShoppingPreview(ShoppingUiState(resolveProblem = ConnectionProblem.Unauthorized)) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingChangeFailedPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(
                    ShoppingPreviewData.withSections,
                    problem = ShoppingProblem.Failed(ConnectionProblem.NoNetwork),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingChangeFailedLargeTextPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(
                    ShoppingPreviewData.withSections,
                    problem = ShoppingProblem.Failed(ConnectionProblem.ServerNotResponding),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingRejectedPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(loaded(ShoppingPreviewData.withSections, problem = ShoppingProblem.Rejected(409))),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingEmptyTextRejectedPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(
                    ShoppingPreviewData.withSections,
                    problem = ShoppingProblem.InputRejected(ShoppingInputProblem.EmptyText),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingClearDialogPreview() {
    GastronomiconTheme { ClearCheckedDialog(count = 2, onConfirm = {}, onDismiss = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingClearDialogLargeTextPreview() {
    GastronomiconTheme { ClearCheckedDialog(count = 1, onConfirm = {}, onDismiss = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingEditDialogPreview() {
    val line = ShoppingLine(
        itemId = ShoppingPreviewData.editable.id,
        text = "Papel de cocina",
        checked = false,
        quantity = 0.0,
        note = "Papel de cocina",
        position = 6,
    )
    GastronomiconTheme { EditItemDialog(line = line, onSave = {}, onDelete = {}, onDismiss = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShoppingEditDialogLargeTextDarkPreview() {
    val line = ShoppingLine(
        itemId = ShoppingPreviewData.editable.id,
        text = "2 kg patatas (las rojas, para asar)",
        checked = false,
        quantity = 2.0,
        note = "las rojas, para asar",
        position = 1,
    )
    GastronomiconTheme(mode = ThemeMode.DARK) {
        EditItemDialog(line = line, onSave = {}, onDelete = {}, onDismiss = {})
    }
}
