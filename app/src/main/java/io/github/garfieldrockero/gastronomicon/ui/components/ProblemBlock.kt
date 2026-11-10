package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing

/**
 * Pantalla de estado (vacío, fallo…): título, texto útil y hasta dos acciones.
 * Caja que centra y columna que se desplaza: con texto grande crece y se
 * desplaza, y `Arrangement.Center` dentro de un `verticalScroll` no centraría.
 */
@Composable
fun MessageBlock(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (primaryLabel != null) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(primaryLabel)
                }
            }
            if (secondaryLabel != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.MinTouchTarget),
                ) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

/** Cargando: rueda y texto en un solo nodo para TalkBack, que lo anuncia al aparecer. */
@Composable
fun LoadingBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Fallo de conexión a pantalla completa. El núcleo decide el motivo; aquí solo se le
 * pone título y texto. Con [ConnectionProblem.Unauthorized] la acción principal lleva a
 * Ajustes (sin borrar nada por sí sola); con cualquier otro, «Volver a intentar» y
 * «Abrir Ajustes» como segunda opción (por si la dirección está mal).
 */
@Composable
fun ProblemBlock(
    problem: ConnectionProblem,
    @StringRes defaultTitle: Int,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unauthorized = problem == ConnectionProblem.Unauthorized
    MessageBlock(
        title = stringResource(problemTitleRes(problem, defaultTitle)),
        text = stringResource(problemTextRes(problem)),
        hint = if (problem == ConnectionProblem.NoNetwork) stringResource(R.string.problem_offline_hint) else null,
        primaryLabel = stringResource(if (unauthorized) R.string.problem_open_settings else R.string.problem_retry),
        onPrimary = if (unauthorized) onOpenSettings else onRetry,
        secondaryLabel = if (unauthorized) null else stringResource(R.string.problem_open_settings),
        onSecondary = onOpenSettings,
        modifier = modifier,
    )
}

/**
 * Fallo a mitad de una lista que ya tiene contenido (cargar más): aviso con su acción, sin tapar lo que hay.
 *
 * [unauthorizedTextRes] solo se usa con [ConnectionProblem.Unauthorized]: una pantalla con
 * un formulario abierto pasa aquí el texto que avisa de que ir a Ajustes cierra la pantalla
 * y pierde lo escrito. Sin él, el aviso es neutro y cierto en cualquier sitio.
 */
@Composable
fun ProblemBanner(
    problem: ConnectionProblem,
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showDetail: Boolean = true,
    @StringRes unauthorizedTextRes: Int = R.string.problem_text_unauthorized,
) {
    val unauthorized = problem == ConnectionProblem.Unauthorized
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
                text = if (unauthorized) stringResource(R.string.problem_title_unauthorized) else message,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // Con el texto muy grande el aviso de arriba se queda en título y botón, para que no
            // se coma la lista; el detalle sigue en el aviso de abajo y en la pantalla de error.
            if (showDetail) {
                Text(
                    text = stringResource(problemTextRes(problem, unauthorizedTextRes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = if (unauthorized) onOpenSettings else onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.MinTouchTarget),
            ) {
                Text(stringResource(if (unauthorized) R.string.problem_open_settings else R.string.problem_retry))
            }
        }
    }
}

@StringRes
private fun problemTitleRes(problem: ConnectionProblem, @StringRes defaultTitle: Int): Int = when (problem) {
    ConnectionProblem.NoNetwork -> R.string.problem_title_no_network
    ConnectionProblem.ServerNotResponding -> R.string.problem_title_not_responding
    ConnectionProblem.Unauthorized -> R.string.problem_title_unauthorized
    else -> defaultTitle
}

@StringRes
private fun problemTextRes(
    problem: ConnectionProblem,
    @StringRes unauthorizedTextRes: Int = R.string.problem_text_unauthorized,
): Int = if (problem == ConnectionProblem.Unauthorized) unauthorizedTextRes else errorTextRes(problem)
