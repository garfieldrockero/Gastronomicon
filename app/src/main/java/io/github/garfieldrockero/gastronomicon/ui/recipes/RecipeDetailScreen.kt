package io.github.garfieldrockero.gastronomicon.ui.recipes

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.garfieldrockero.gastronomicon.ui.components.LoadingBlock
import io.github.garfieldrockero.gastronomicon.ui.components.MessageBlock
import io.github.garfieldrockero.gastronomicon.ui.components.ProblemBlock
import io.github.garfieldrockero.gastronomicon.ui.components.RecipePhoto
import io.github.garfieldrockero.gastronomicon.ui.components.SanitizedHtmlContent
import io.github.garfieldrockero.gastronomicon.ui.components.StarRating
import io.github.garfieldrockero.gastronomicon.ui.components.StatusText
import io.github.garfieldrockero.gastronomicon.ui.components.isLargeText
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.StepText
import io.github.garfieldrockero.gastronomicon.ui.theme.TabularNumbers
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    state: RecipeDetailState,
    imageLoader: ImageLoader?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onMoreServings: () -> Unit,
    onFewerServings: () -> Unit,
    onOpenSource: (SourceLink) -> Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    if (state is RecipeDetailState.Ready) {
                        // El nombre en una línea con «…»; el encabezado completo está en el cuerpo,
                        // así que TalkBack no lo lee dos veces.
                        Text(
                            text = state.view.name,
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
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        when (state) {
            // Closed no se ve nunca (la ruta enseña la lista), pero el «when» ha de ser completo.
            RecipeDetailState.Closed, RecipeDetailState.Loading -> LoadingBlock(
                text = stringResource(R.string.detail_loading),
                modifier = contentModifier,
            )

            is RecipeDetailState.Failed -> ProblemBlock(
                problem = state.problem,
                defaultTitle = R.string.problem_title_recipe,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                modifier = contentModifier,
            )

            // 401 y 403: lleva a Ajustes, no a reintentar.
            RecipeDetailState.Unauthorized -> ProblemBlock(
                problem = ConnectionProblem.Unauthorized,
                defaultTitle = R.string.problem_title_recipe,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                modifier = contentModifier,
            )

            RecipeDetailState.NotFound -> MessageBlock(
                title = stringResource(R.string.detail_not_found_title),
                text = stringResource(R.string.detail_not_found_text),
                primaryLabel = stringResource(R.string.detail_not_found_action),
                onPrimary = onBack,
                modifier = contentModifier,
            )

            is RecipeDetailState.Ready -> RecipeDetailContent(
                view = state.view,
                imageLoader = imageLoader,
                onMoreServings = onMoreServings,
                onFewerServings = onFewerServings,
                onOpenSource = onOpenSource,
                modifier = contentModifier,
            )
        }
    }
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
) {
    val heroPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (view.imageRecipeId != null) {
            RecipePhoto(
                recipeId = view.imageRecipeId,
                loader = imageLoader,
                maxSidePx = heroPx,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(HERO_ASPECT),
            )
        }

        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
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
            modifier = Modifier.size(StepperButtonSize),
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
                .widthIn(min = StepperButtonSize)
                .semantics {
                    contentDescription = valueDescription
                    liveRegion = LiveRegionMode.Polite
                },
        )
        FilledTonalIconButton(
            onClick = onMore,
            enabled = view.canIncreaseServings,
            modifier = Modifier.size(StepperButtonSize),
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
            .heightIn(min = IngredientRowMinHeight)
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
                .scale(CHECKBOX_SCALE),
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
private const val CHECKBOX_SCALE = 1.35f

private val StepperButtonSize = 56.dp

// Ancho mínimo de la columna de cantidades, en sp para que crezca con la letra.
private val QuantityColumnWidth = 64.sp
private val IngredientRowMinHeight = 56.dp

// ------------------------------------------------------------------ previews

@Composable
private fun DetailPreview(state: RecipeDetailState) {
    RecipeDetailScreen(
        state = state,
        imageLoader = null,
        onBack = {},
        onRetry = {},
        onMoreServings = {},
        onFewerServings = {},
        onOpenSource = { true },
        onOpenSettings = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun RecipeDetailLoadedPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Ready(RecipePreviewData.detail)) }
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

@Preview(showBackground = true)
@Composable
private fun RecipeDetailLoadingPreview() {
    GastronomiconTheme { DetailPreview(RecipeDetailState.Loading) }
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
