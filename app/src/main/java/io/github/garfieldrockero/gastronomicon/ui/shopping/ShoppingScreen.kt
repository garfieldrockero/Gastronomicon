package io.github.garfieldrockero.gastronomicon.ui.shopping

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingInputProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingLine
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListState
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSummary
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingPhase
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingProblem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingText
import io.github.garfieldrockero.gastronomicon.ui.components.BusyIndicator
import io.github.garfieldrockero.gastronomicon.ui.components.GastronomiconTopBar
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.NoticeCard
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBanner
import io.github.garfieldrockero.gastronomicon.ui.components.SectionTitle
import io.github.garfieldrockero.gastronomicon.ui.components.errorTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.isLargeText
import io.github.garfieldrockero.gastronomicon.ui.components.problemTextRes
import io.github.garfieldrockero.gastronomicon.ui.components.problemTitleRes
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.LocalExtendedColors
import io.github.garfieldrockero.gastronomicon.ui.theme.Motion
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.TabularNumbers
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode
import io.github.garfieldrockero.gastronomicon.ui.theme.motionTween
import io.github.garfieldrockero.gastronomicon.ui.theme.rememberAnimationsEnabled

/**
 * Lista de la compra. Sin lógica: pinta el estado del núcleo ([ShoppingUiState]) y
 * avisa de lo que hace la persona. Pensada para el súper con una mano: el campo de
 * añadir va abajo, al alcance del pulgar, y cada fila entera es la casilla.
 *
 * El título de la barra es el selector: abre la lista que se quiera de las que
 * tiene el servidor o crea una nueva. Qué lista está abierta, cómo se ordenan y
 * qué nombre vale al crearla lo decide el núcleo.
 *
 * Estados: buscando listas, sin ninguna lista, cargando la lista, fallo (con
 * acción), sin cobertura, sesión caducada (lleva a Ajustes), lista vacía y lista
 * con contenido. Un fallo al escribir no tapa la lista: sale como aviso encima
 * con su acción.
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
    onSelectList: (String) -> Unit,
    onOpenCreateList: () -> Unit,
    onDismissCreateList: () -> Unit,
    onCreateList: (String) -> Unit,
    onDismissCreateNotice: () -> Unit,
    modifier: Modifier = Modifier,
    // Con la barra de pestañas debajo, quien la pinta quita el inset inferior (ya lo
    // consume la barra). Suelto, el valor por defecto es el de siempre.
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val list = state.list
    val picker = state.picker
    val addFocus = remember { FocusRequester() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    // Lo que de verdad hay en pantalla manda para el título; el resumen de las
    // listas solo cubre el hueco antes de la primera carga.
    val openList = picker.openList
    val title = when {
        list.loadedOnce && list.view.listName.isNotEmpty() -> list.view.listName
        openList != null -> openList.displayName
        else -> stringResource(R.string.shopping_title)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Con el teclado abierto, el campo de añadir sube con él.
        contentWindowInsets = contentWindowInsets,
        topBar = {
            GastronomiconTopBar(
                title = {
                    ListSelector(
                        title = title,
                        lists = picker.lists,
                        openListId = picker.openListId,
                        // Sin una lectura buena no se despliega: crear una lista
                        // sin haber visto las que hay acaba en duplicados, que es
                        // justo lo que evita el aviso de «puede que se haya
                        // creado». El cuerpo ya dice por qué y ofrece reintentar.
                        enabled = picker.loadedOnce && !picker.isCreating,
                        canCreate = picker.loadedOnce && picker.problem == null && !picker.isCreating,
                        onSelect = onSelectList,
                        onCreate = onOpenCreateList,
                    )
                },
                actions = {
                    IconButton(onClick = onRetry, enabled = !list.isLoading && !picker.isLoading) {
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
                onCreateList = onOpenCreateList,
                onDismissCreateNotice = onDismissCreateNotice,
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

    if (state.createDialog) {
        CreateListDialog(
            creating = picker.isCreating,
            problem = state.createProblem,
            onCreate = onCreateList,
            onDismiss = onDismissCreateList,
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
    onCreateList: () -> Unit,
    onDismissCreateNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = state.list
    val picker = state.picker
    val listProblem = list.problem
    val pickerProblem = picker.problem
    val animations = rememberAnimationsEnabled()

    // El aviso de arriba nunca pasa de la mitad del hueco: con el texto al 2× y
    // 360 dp de ancho no se come la lista, y sus botones quedan siempre a la vista
    // porque solo se desplaza el texto (ver [NoticeCard]).
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val noticeMaxHeight = maxHeight * NOTICE_MAX_SHARE
        Column(modifier = Modifier.fillMaxSize()) {
            // «Puede que la lista se haya creado» se queda a la vista en cualquier
            // estado, también cuando la relectura no encuentra ninguna lista: es justo
            // ahí donde es más fácil crearla otra vez sin querer. Va FUERA del fundido:
            // su región viva no puede nacer ni morir al cambiar de estado.
            if (state.maybeCreated) {
                MaybeCreatedNotice(
                    onReload = onRetry,
                    onDismiss = onDismissCreateNotice,
                    maxHeight = noticeMaxHeight,
                    modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                )
            }

            // Solo cuatro estados posibles: la clave del fundido es este valor y no
            // los datos, que cambian sin parar. Con las animaciones del sistema
            // quitadas (`animations` en falso) el cambio es inmediato: un fallo o un
            // 401 se ven sin esperar.
            val body = when {
                !picker.loadedOnce && pickerProblem != null -> ShoppingScreenBody.ListsProblem
                // Primera lectura de la página: la pantalla nunca se queda en blanco.
                !picker.loadedOnce -> ShoppingScreenBody.ListsLoading
                // Mealie respondió y no hay ninguna lista: se puede crear desde aquí.
                picker.lists.isEmpty() -> ShoppingScreenBody.NoLists
                else -> ShoppingScreenBody.Content
            }
            AnimatedContent(
                targetState = body,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    shoppingBodyFade(animate = animations && targetState == ShoppingScreenBody.Content)
                },
                sizeTransform = null,
                label = "shopping-body",
            ) { target ->
                // Lo que se va lee del estado actual: si ya no tiene datos, se apaga
                // sin dibujar nada en vez de quedarse con el fallo de antes.
                when (target) {
                    ShoppingScreenBody.ListsProblem -> {
                        val current = picker.problem
                        if (current != null) {
                            ListsProblemBlock(
                                problem = current,
                                onRetry = onRetry,
                                onOpenSettings = onOpenSettings,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            LoadingBlock(
                                text = stringResource(R.string.shopping_lists_loading),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    ShoppingScreenBody.ListsLoading -> LoadingBlock(
                        text = stringResource(R.string.shopping_lists_loading),
                        modifier = Modifier.fillMaxSize(),
                    )

                    ShoppingScreenBody.NoLists -> MessageBlock(
                        title = stringResource(R.string.shopping_no_list_title),
                        text = stringResource(R.string.shopping_no_list_text),
                        // Con el aviso de «puede que se haya creado» a la vista no se
                        // ofrece crear otra: el propio aviso ya pide mirar antes.
                        primaryLabel = if (state.maybeCreated) null else stringResource(R.string.shopping_no_list_create),
                        onPrimary = onCreateList,
                        secondaryLabel = stringResource(R.string.shopping_no_list_action),
                        onSecondary = onRetry,
                        modifier = Modifier.fillMaxSize(),
                    )

                    ShoppingScreenBody.Content -> Column(modifier = Modifier.fillMaxSize()) {
                        if (list.phase == ShoppingPhase.Refreshing) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                        // Un solo aviso a la vista: primero las listas y luego la lista abierta.
                        val noticeModifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small)
                        when {
                            // «Pudo crearse» ya tiene su propio aviso arriba: no se repite.
                            pickerProblem != null && pickerProblem !is ShoppingListPickerProblem.MaybeCreated ->
                                PickerNotice(
                                    problem = pickerProblem,
                                    onRetry = onRetry,
                                    onOpenSettings = onOpenSettings,
                                    maxHeight = noticeMaxHeight,
                                    modifier = noticeModifier,
                                )
                            listProblem != null && list.loadedOnce -> ProblemNotice(
                                problem = listProblem,
                                onRetry = onRetry,
                                onOpenSettings = onOpenSettings,
                                onDismiss = onDismissProblem,
                                maxHeight = noticeMaxHeight,
                                modifier = noticeModifier,
                            )
                        }
                        when {
                            // Fallo sin nada que enseñar todavía: pantalla completa con su acción.
                            !list.loadedOnce && listProblem != null && !list.isLoading -> {
                                val connection = listProblem.asConnectionProblem()
                                if (connection != null) {
                                    ShoppingProblemBlock(connection, onRetry, onOpenSettings, Modifier.weight(1f))
                                } else {
                                    MessageBlock(
                                        title = stringResource(R.string.shopping_problem_title),
                                        text = stringResource(listProblem.messageRes()),
                                        primaryLabel = stringResource(R.string.problem_retry),
                                        onPrimary = onRetry,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            !list.loadedOnce -> LoadingBlock(
                                text = stringResource(R.string.shopping_loading),
                                modifier = Modifier.weight(1f),
                            )

                            list.view.isEmpty -> MessageBlock(
                                title = stringResource(R.string.shopping_empty_title),
                                text = stringResource(R.string.shopping_empty_text),
                                primaryLabel = stringResource(R.string.shopping_empty_action),
                                onPrimary = onFocusAdd,
                                modifier = Modifier.weight(1f),
                            )

                            else -> ShoppingLines(
                                list = list,
                                animations = animations,
                                onToggle = onToggle,
                                onEdit = onEdit,
                                onClearChecked = onClearChecked,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Qué ocupa el cuerpo de la compra: cuatro estados excluyentes y de cardinalidad fija.
 * Es la clave del cambio de bloque; los datos cambian demasiado como para usarlos de clave.
 */
private enum class ShoppingScreenBody { ListsProblem, ListsLoading, NoLists, Content }

/**
 * Cambio de bloque del cuerpo: solo se desvanece la lista, que es lo único que no se
 * anuncia por TalkBack. Lo que se va se apaga en el acto (`snap`, nunca dos regiones
 * vivas a la vez) y un fallo o un 401 se leen al instante, sin esperar 120 ms.
 */
private fun shoppingBodyFade(animate: Boolean): ContentTransform =
    fadeIn(animationSpec = motionTween(enabled = animate)) togetherWith
        fadeOut(animationSpec = snap())

@Composable
private fun ShoppingLines(
    list: ShoppingListState,
    animations: Boolean,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
    onClearChecked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = list.view
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.medium,
            end = Spacing.medium,
            top = Spacing.small,
            bottom = 0.dp,
        ),
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
                // Aparece y desaparece con un fundido corto al marcar la primera cosa
                // (y al desmarcarla, va con la lista).
                Box(
                    modifier = Modifier.animateItem(
                        fadeInSpec = motionTween(animations, Motion.ShortMillis),
                        placementSpec = motionTween(animations, Motion.MediumMillis),
                        fadeOutSpec = motionTween(animations, Motion.ShortMillis),
                    ),
                ) {
                    ClearCheckedButton(
                        count = view.checkedCount,
                        clearing = list.clearingChecked,
                        enabled = !list.isBusy && !list.isLoading,
                        onClick = onClearChecked,
                    )
                }
            }
        }
        for ((index, section) in view.sections.withIndex()) {
            if (section.title.isNotEmpty()) {
                item(key = "section-$index") {
                    // Mismo título de sección que el resto de la app (tamaño y color del
                    // tema, y marcado como encabezado para TalkBack): aquí solo cambia el
                    // aire de arriba y abajo. Sin `stickyHeader`, que además no es estable.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.medium, bottom = Spacing.extraSmall),
                    ) {
                        // Más pequeña que el título de pantalla: a texto grande una sección
                        // corta («Verdura») no debe ocupar tres líneas.
                        SectionTitle(section.title, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            items(items = section.lines, key = { it.itemId }) { line ->
                ShoppingRow(
                    line = line,
                    busy = line.itemId in list.busyItemIds,
                    animations = animations,
                    onToggle = { onToggle(line.itemId) },
                    onEdit = { onEdit(line.itemId) },
                    // Al marcar, la línea baja al final de su sección (lo ordena el
                    // núcleo): se ve cómo se recoloca, que es la confirmación de que el
                    // toque ha entrado, en vez de desaparecer de donde estaba.
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
        // El hueco va por fuera del alto mínimo: así el botón mide los 56 dp del token
        // (antes el relleno se los comía y se quedaba en 48) y se pulsa con el pulgar.
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.extraSmall)
            .heightIn(min = Sizes.ActionMinHeight)
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
    modifier: Modifier = Modifier,
    /** Animaciones del sistema encendidas; en falso, el color cambia de golpe. */
    animations: Boolean = false,
) {
    val checkedState = stringResource(R.string.shopping_item_checked_state)
    val uncheckedState = stringResource(R.string.shopping_item_unchecked_state)
    // Marcar apaga el texto: el cambio de color se ve venir en 120 ms en vez de
    // parpadear, y la casilla y el tachado siguen siendo la señal de verdad.
    val textColor by animateColorAsState(
        targetValue = if (line.checked) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = motionTween(animations, Motion.ShortMillis),
        label = "shopping-line-color",
    )
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.ShoppingRow)
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
                        .scale(Sizes.CheckboxScale),
                )
                Text(
                    text = line.text,
                    // El mismo tamaño que un renglón de ingrediente del detalle: es la
                    // misma cosa (texto con casilla) y se lee igual de lejos, de pie.
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
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
        // El color de superficie del sistema, tal cual: sin elevación tonal que lo tiña
        // (antes llevaba 3 dp y salía con un velo del color de acento). La barra se
        // separa de la lista por su línea de arriba, que es lo que se ve de verdad.
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
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
                    modifier = Modifier.size(Sizes.AddButton),
                ) {
                    if (adding) {
                        val addingText = stringResource(R.string.shopping_adding)
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(Sizes.SmallSpinner)
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
}

// ------------------------------------------------------------------ selector

/**
 * Título de la barra convertido en selector: enseña la lista abierta y despliega
 * las demás con «Nueva lista…» al final. Con el menú abierto, la lista actual va
 * marcada con su nombre y un visto, para no fiarse solo del color.
 *
 * Sin una lectura buena de la página ([enabled] en falso) no se despliega y no
 * lleva flecha: sin saber qué listas hay, crear una a ciegas las duplicaría. El
 * lector de pantalla oye el motivo en el propio título.
 */
@Composable
private fun ListSelector(
    title: String,
    lists: List<ShoppingListSummary>,
    openListId: String?,
    enabled: Boolean,
    canCreate: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lockedText = stringResource(R.string.shopping_selector_locked)
    Box {
        Row(
            modifier = Modifier
                .heightIn(min = Sizes.MinTouchTarget)
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    enabled = enabled,
                    onClickLabel = stringResource(R.string.shopping_selector_change),
                    role = Role.DropdownList,
                ) { expanded = true }
                .semantics { if (!enabled) stateDescription = lockedText }
                .padding(end = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { heading() },
            )
            if (enabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.extraSmall),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ListMenuContent(
                lists = lists,
                openListId = openListId,
                canCreate = canCreate,
                onSelect = { listId ->
                    expanded = false
                    onSelect(listId)
                },
                onCreate = {
                    expanded = false
                    onCreate()
                },
            )
        }
    }
}

/** Las opciones del selector. Va aparte para poder enseñarlas en una preview. */
@Composable
private fun ListMenuContent(
    lists: List<ShoppingListSummary>,
    openListId: String?,
    canCreate: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
) {
    for (list in lists) {
        DropdownMenuItem(
            text = {
                Text(text = list.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            onClick = { onSelect(list.id) },
            trailingIcon = if (list.id == openListId) {
                {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = stringResource(R.string.shopping_selector_current),
                    )
                }
            } else {
                null
            },
            modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
        )
    }
    if (lists.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    DropdownMenuItem(
        text = { Text(text = stringResource(R.string.shopping_new_list)) },
        onClick = onCreate,
        // Sin la página de listas leída no se crea: podría duplicar una que exista.
        enabled = canCreate,
        leadingIcon = {
            Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null)
        },
        modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
    )
}

// ------------------------------------------------------------------ diálogos

@Composable
private fun CreateListDialog(
    creating: Boolean,
    problem: ShoppingListPickerProblem?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // El nombre lo escribe la persona: `remember` basta y no acaba serializado en
    // el estado guardado. Lo que se puede escribir y lo que vale lo decide el
    // núcleo (`ShoppingText.listNameInput`), igual que el controlador.
    var name by remember { mutableStateOf("") }
    val input = ShoppingText.listNameInput(name)
    val inputProblem = input.problem
    // El teclado sale solo al abrir: se ahorra un toque con el móvil en una mano.
    // Si el campo aún no estuviera colocado, el foco se pierde sin romper el
    // diálogo: la persona solo tiene que tocar el campo.
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            nameFocus.requestFocus()
        } catch (ignored: IllegalStateException) {
            // FocusRequester sin nodo todavía: se escribe tocando el campo.
        }
    }
    // El aviso del nombre va pegado al campo, para que el lector de pantalla lo
    // lea con él; si no hay nada que decir, el hueco no se reserva.
    val inputProblemBlock: (@Composable () -> Unit)? = if (inputProblem == null) {
        null
    } else {
        {
            Text(
                text = stringResource(inputProblemText(inputProblem)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text(stringResource(R.string.shopping_new_list_title)) },
        text = {
            // Con texto grande el contenido no cabe: se desplaza.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    text = stringResource(R.string.shopping_new_list_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    // El tope es el del núcleo; se deja escribir uno de más para que
                    // el aviso de «demasiado largo» no recorte en silencio lo pegado.
                    onValueChange = { name = it.take(ShoppingText.MAX_LIST_NAME + 1) },
                    label = { Text(stringResource(R.string.shopping_new_list_label)) },
                    singleLine = true,
                    readOnly = creating,
                    isError = inputProblem != null,
                    supportingText = inputProblemBlock,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (input.isValid && !creating) onCreate(input.text) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus),
                )
                if (problem != null) {
                    Text(
                        text = createProblemText(problem),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !creating,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(R.string.shopping_dialog_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(input.text) },
                enabled = !creating && input.isValid,
                modifier = Modifier.heightIn(min = Sizes.MinTouchTarget),
            ) {
                if (creating) {
                    BusyIndicator()
                    Text(stringResource(R.string.shopping_new_list_creating))
                } else {
                    Text(stringResource(R.string.shopping_new_list_create))
                }
            }
        },
    )
}

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

// ------------------------------------------------------------------ avisos

/**
 * Aviso de «puede que la lista se haya creado». No lleva «crear otra vez» a
 * propósito: repetir a ciegas crearía una segunda lista. Solo se mira otra vez
 * (recargar) o se cierra.
 */
@Composable
private fun MaybeCreatedNotice(
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    NoticeCard(
        maxHeight = maxHeight,
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        title = stringResource(R.string.shopping_maybe_created_title),
        detail = stringResource(R.string.shopping_maybe_created_text),
        modifier = modifier,
    ) {
        Button(
            onClick = onReload,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.shopping_maybe_created_reload))
        }
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

/**
 * Fallo al leer la página de listas con algo ya a la vista: aviso arriba, sin
 * tapar la lista abierta.
 */
@Composable
private fun PickerNotice(
    problem: ShoppingListPickerProblem,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val connection = problem.asConnectionProblem()
    if (connection != null) {
        ProblemBanner(
            problem = connection,
            message = stringResource(R.string.shopping_lists_stale),
            onRetry = onRetry,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
            showDetail = !isLargeText(),
        )
        return
    }
    // Rechazos del servidor a la hora de leer las listas: no hay sesión que
    // arreglar ni nada que reintentar a ciegas, pero sí recargar.
    NoticeCard(
        maxHeight = maxHeight,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        title = stringResource(R.string.shopping_lists_stale),
        detail = pickerProblemText(problem),
        modifier = modifier,
    ) {
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.MinTouchTarget),
        ) {
            Text(stringResource(R.string.shopping_reload))
        }
    }
}

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
        title = stringResource(problemTitleRes(problem, R.string.shopping_problem_title)),
        text = stringResource(problemTextRes(problem)),
        hint = if (problem == ConnectionProblem.NoNetwork) stringResource(R.string.shopping_offline_hint) else null,
        primaryLabel = stringResource(if (unauthorized) R.string.problem_open_settings else R.string.problem_retry),
        onPrimary = if (unauthorized) onOpenSettings else onRetry,
        secondaryLabel = if (unauthorized) null else stringResource(R.string.problem_open_settings),
        onSecondary = onOpenSettings,
        modifier = modifier,
    )
}

/** Fallo al leer las listas antes de tener ninguna a la vista: pantalla completa con su acción. */
@Composable
private fun ListsProblemBlock(
    problem: ShoppingListPickerProblem,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = problem.asConnectionProblem()
    if (connection == null) {
        MessageBlock(
            title = stringResource(R.string.shopping_lists_problem_title),
            text = pickerProblemText(problem),
            primaryLabel = stringResource(R.string.problem_retry),
            onPrimary = onRetry,
            modifier = modifier,
        )
        return
    }
    val unauthorized = connection == ConnectionProblem.Unauthorized
    MessageBlock(
        title = stringResource(problemTitleRes(connection, R.string.shopping_lists_problem_title)),
        text = stringResource(problemTextRes(connection)),
        hint = if (connection == ConnectionProblem.NoNetwork) stringResource(R.string.shopping_offline_hint) else null,
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
    maxHeight: Dp,
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
    NoticeCard(
        maxHeight = maxHeight,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        title = stringResource(problem.messageRes()),
        detail = null,
        modifier = modifier,
    ) {
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

/** El motivo de conexión si el fallo es de conexión o de sesión; null si es otro tipo de rechazo. */
private fun ShoppingProblem.asConnectionProblem(): ConnectionProblem? = when (this) {
    ShoppingProblem.Unauthorized -> ConnectionProblem.Unauthorized
    is ShoppingProblem.Failed -> problem
    else -> null
}

/** Lo mismo para el selector: «pudo crearse» no es un fallo de conexión que reintentar. */
private fun ShoppingListPickerProblem.asConnectionProblem(): ConnectionProblem? = when (this) {
    ShoppingListPickerProblem.Unauthorized -> ConnectionProblem.Unauthorized
    is ShoppingListPickerProblem.Failed -> problem
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

/** Texto del selector. Un motivo nuevo en el núcleo no compila hasta tener su texto. */
@Composable
private fun pickerProblemText(problem: ShoppingListPickerProblem): String = when (problem) {
    ShoppingListPickerProblem.Unauthorized -> stringResource(R.string.problem_text_unauthorized)
    is ShoppingListPickerProblem.Rejected -> stringResource(R.string.shopping_lists_rejected)
    is ShoppingListPickerProblem.InvalidData -> stringResource(R.string.shopping_lists_rejected)
    is ShoppingListPickerProblem.InputRejected -> stringResource(inputProblemText(problem.reason))
    is ShoppingListPickerProblem.MaybeCreated -> stringResource(R.string.shopping_maybe_created_text)
    is ShoppingListPickerProblem.Failed -> stringResource(errorTextRes(problem.problem))
}

/** Texto del nombre rechazado antes de enviarlo. */
private fun inputProblemText(problem: ShoppingInputProblem): Int = when (problem) {
    ShoppingInputProblem.EmptyText -> R.string.shopping_new_list_empty
    ShoppingInputProblem.TextTooLong -> R.string.shopping_new_list_too_long
    ShoppingInputProblem.InvalidQuantity -> R.string.shopping_problem_invalid_quantity
}

/**
 * Rechazo del servidor dentro del diálogo de crear: aquí no hay nada que
 * recargar, así que el texto de la página («recarga y vuelve a intentarlo») no
 * vale. Solo cambian los rechazos; el resto usa el texto del selector.
 */
@Composable
private fun createProblemText(problem: ShoppingListPickerProblem): String = when (problem) {
    is ShoppingListPickerProblem.Rejected -> stringResource(R.string.shopping_new_list_rejected)
    is ShoppingListPickerProblem.InvalidData -> stringResource(R.string.shopping_new_list_rejected)
    else -> pickerProblemText(problem)
}

/** Cuánto del hueco del cuerpo puede ocupar un aviso fijo como mucho (y escrolear por dentro). */
private const val NOTICE_MAX_SHARE = 0.5f

// ------------------------------------------------------------------ previews

private const val PREVIEW_LIST_ID = "00000000-0000-4000-8000-000000000001"
private const val PREVIEW_SECOND_LIST_ID = "00000000-0000-4000-8000-000000000002"
private const val PREVIEW_THIRD_LIST_ID = "00000000-0000-4000-8000-000000000003"

private val previewLists = listOf(
    ShoppingListSummary(PREVIEW_LIST_ID, "Compra de la semana"),
    ShoppingListSummary(PREVIEW_SECOND_LIST_ID, "Mercado del sábado"),
    ShoppingListSummary(PREVIEW_THIRD_LIST_ID, "Cena de cumpleaños"),
)

private fun loaded(
    items: List<ShoppingItem>,
    problem: ShoppingProblem? = null,
    phase: ShoppingPhase = ShoppingPhase.Idle,
    busy: Set<String> = emptySet(),
    adding: Boolean = false,
    clearing: Boolean = false,
    name: String = "Compra de la semana",
) = ShoppingListState(
    listId = PREVIEW_LIST_ID,
    listName = name,
    items = items,
    phase = phase,
    problem = problem,
    loadedOnce = true,
    busyItemIds = busy,
    adding = adding,
    clearingChecked = clearing,
    clearingItemIds = if (clearing) items.filter { it.checked }.mapTo(HashSet()) { it.id } else emptySet(),
)

/** Estado del selector con datos de ejemplo (los nombres son inventados). */
private fun choices(
    lists: List<ShoppingListSummary> = previewLists,
    openListId: String? = if (lists.isEmpty()) null else lists.first().id,
    phase: ShoppingListPickerPhase = ShoppingListPickerPhase.Idle,
    problem: ShoppingListPickerProblem? = null,
    loadedOnce: Boolean = true,
) = ShoppingListPickerState(
    lists = lists,
    openListId = openListId,
    phase = phase,
    problem = problem,
    loadedOnce = loadedOnce,
)

private fun ui(
    list: ShoppingListState,
    draft: String = "",
    picker: ShoppingListPickerState = choices(),
    createDialog: Boolean = false,
    createProblem: ShoppingListPickerProblem? = null,
    maybeCreated: Boolean = false,
) = ShoppingUiState(
    list = list,
    picker = picker,
    createDialog = createDialog,
    createProblem = createProblem,
    maybeCreated = maybeCreated,
    draft = draft,
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
        onSelectList = {},
        onOpenCreateList = {},
        onDismissCreateList = {},
        onCreateList = {},
        onDismissCreateNotice = {},
    )
}

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
        ShoppingPreview(
            ui(
                loaded(ShoppingPreviewData.withSections, phase = ShoppingPhase.Refreshing),
                picker = choices(phase = ShoppingListPickerPhase.Loading),
            ),
        )
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
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                ShoppingListState(),
                picker = choices(
                    lists = emptyList(),
                    phase = ShoppingListPickerPhase.Loading,
                    loadedOnce = false,
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingLoadingLargeTextPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(ShoppingListState(listId = PREVIEW_LIST_ID, phase = ShoppingPhase.LoadingFirst)),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingNoListPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(ShoppingListState(), picker = choices(lists = emptyList(), loadedOnce = true)),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingNoListLargeTextPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(ShoppingListState(), picker = choices(lists = emptyList(), loadedOnce = true)),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingNoNetworkPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                ShoppingListState(),
                picker = choices(
                    lists = emptyList(),
                    loadedOnce = false,
                    problem = ShoppingListPickerProblem.Failed(ConnectionProblem.NoNetwork),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingNotRespondingLargeTextPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                ShoppingListState(
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
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                ShoppingListState(),
                picker = choices(
                    lists = emptyList(),
                    loadedOnce = false,
                    problem = ShoppingListPickerProblem.Unauthorized,
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingListsStalePreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(ShoppingPreviewData.withSections),
                picker = choices(problem = ShoppingListPickerProblem.Failed(ConnectionProblem.NoNetwork)),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingListsRejectedPreview() {
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(ShoppingPreviewData.withSections),
                picker = choices(problem = ShoppingListPickerProblem.Rejected(409)),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingListsRejectedLargeTextPreview() {
    // El aviso de listas con el texto al 2×: se desplaza por dentro y deja ver la
    // lista y su botón de recargar.
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(ShoppingPreviewData.withSections),
                picker = choices(problem = ShoppingListPickerProblem.Rejected(409)),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingSelectorLockedLargeTextPreview() {
    // Sin las listas leídas el título no lleva flecha y no se despliega: el cuerpo
    // explica por qué y ofrece «Volver a intentar».
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                ShoppingListState(),
                picker = choices(
                    lists = emptyList(),
                    loadedOnce = false,
                    problem = ShoppingListPickerProblem.Failed(ConnectionProblem.ServerNotResponding),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingMaybeCreatedPreview() {
    GastronomiconTheme {
        ShoppingPreview(ui(loaded(ShoppingPreviewData.withoutSections), maybeCreated = true))
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingMaybeCreatedLargeTextPreview() {
    GastronomiconTheme {
        ShoppingPreview(ui(loaded(ShoppingPreviewData.withoutSections), maybeCreated = true))
    }
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
private fun ShoppingListChoicesPreview() {
    // El menú de verdad vive en un Popup y no sale en una preview: se enseña su contenido.
    GastronomiconTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ListMenuContent(
                    lists = previewLists,
                    openListId = PREVIEW_SECOND_LIST_ID,
                    canCreate = true,
                    onSelect = {},
                    onCreate = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShoppingListChoicesLargeTextDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ListMenuContent(
                    lists = previewLists,
                    openListId = PREVIEW_THIRD_LIST_ID,
                    canCreate = true,
                    onSelect = {},
                    onCreate = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingCreateDialogPreview() {
    GastronomiconTheme {
        CreateListDialog(creating = false, problem = null, onCreate = {}, onDismiss = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f)
@Composable
private fun ShoppingCreateDialogLargeTextPreview() {
    GastronomiconTheme {
        CreateListDialog(
            creating = false,
            problem = ShoppingListPickerProblem.Failed(ConnectionProblem.ServerNotResponding),
            onCreate = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShoppingCreateDialogDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        CreateListDialog(creating = true, problem = null, onCreate = {}, onDismiss = {})
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

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun ShoppingSelectorListsPreview() {
    // Otra lista elegida entre tres: el título enseña la que está abierta.
    GastronomiconTheme {
        ShoppingPreview(
            ui(
                loaded(ShoppingPreviewData.withSections, name = "Mercado del sábado"),
                picker = choices(openListId = PREVIEW_SECOND_LIST_ID),
            ),
        )
    }
}

// ------------------------------------------------------------------ piezas sueltas

/**
 * Tres renglones de ejemplo (uno marcado, uno largo) con su título de sección: es la
 * pieza que se toca a menudo y no se ve bien dentro de la pantalla entera. Datos
 * inventados, sin nada del servidor.
 */
private val previewLines = listOf(
    ShoppingLine(
        itemId = "00000000-0000-4000-8000-000000000201",
        text = "2 kg de patatas",
        checked = false,
        quantity = 2.0,
        note = "",
        position = 1,
    ),
    ShoppingLine(
        itemId = "00000000-0000-4000-8000-000000000202",
        text = "Yogures naturales, los de siempre",
        checked = true,
        quantity = 0.0,
        note = "",
        position = 2,
    ),
    ShoppingLine(
        itemId = "00000000-0000-4000-8000-000000000203",
        text = "Bolsas de basura grandes, las de 100 litros",
        checked = false,
        quantity = 0.0,
        note = "",
        position = 3,
    ),
)

@Composable
private fun ShoppingRowsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        ) {
            SectionTitle("Verdura", style = MaterialTheme.typography.titleMedium)
            for (line in previewLines) {
                ShoppingRow(line = line, busy = false, onToggle = {}, onEdit = {})
            }
        }
    }
}

@Composable
private fun ShoppingAddBarPreviewContent(draft: String, adding: Boolean) {
    val focus = remember { FocusRequester() }
    Surface(color = MaterialTheme.colorScheme.background) {
        AddBar(
            draft = draft,
            adding = adding,
            onDraftChange = {},
            onAdd = {},
            focus = focus,
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 320)
@Composable
private fun ShoppingRowsPreview() {
    GastronomiconTheme { ShoppingRowsPreviewContent() }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 320, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShoppingRowsDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) { ShoppingRowsPreviewContent() }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 480, fontScale = 2f)
@Composable
private fun ShoppingRowsLargeTextPreview() {
    GastronomiconTheme { ShoppingRowsPreviewContent() }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 200)
@Composable
private fun ShoppingAddBarPreview() {
    GastronomiconTheme { ShoppingAddBarPreviewContent(draft = "Leche", adding = false) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShoppingAddBarDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        ShoppingAddBarPreviewContent(draft = "Leche", adding = true)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 260, fontScale = 2f)
@Composable
private fun ShoppingAddBarLargeTextPreview() {
    GastronomiconTheme { ShoppingAddBarPreviewContent(draft = "Aceite de oliva", adding = false) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShoppingNoticeLargeTextDarkPreview() {
    // El aviso con tope del 50 %, de noche y con el texto al 2×: se desplaza por dentro
    // y deja ver la lista y el botón de recargar.
    GastronomiconTheme(mode = ThemeMode.DARK) {
        ShoppingPreview(
            ui(
                loaded(ShoppingPreviewData.withSections),
                picker = choices(problem = ShoppingListPickerProblem.Rejected(409)),
            ),
        )
    }
}
