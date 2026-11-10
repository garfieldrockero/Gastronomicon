package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Barra superior de las pantallas con «Atrás» y acciones.
 *
 * Existe para que todas las barras de la app sean la misma: hoy solo envuelve la
 * barra de Material (mismo alto, mismo color y mismos huecos, sin cambios de
 * píxel), pero deja un solo sitio donde cambiar el estilo cuando toque, en vez
 * de siete copias que se van separando.
 *
 * [title] es el texto de la barra; el que quiera marcarlo como encabezado, que
 * lo haga en su propio `Text` (la compra usa el título como selector y ahí no
 * conviene anunciar un encabezado).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GastronomiconTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}
