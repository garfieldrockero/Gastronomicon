package io.github.garfieldrockero.gastronomicon.ui.navigation

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.Sizes
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode
import io.github.garfieldrockero.gastronomicon.ui.theme.motionTween
import io.github.garfieldrockero.gastronomicon.ui.theme.rememberAnimationsEnabled

/**
 * Las tres listas raíz de la app, en el orden en que se ven en la barra inferior
 * (decisión de la persona: Recetas · Planificador · Compra).
 *
 * El nombre visible y la descripción para TalkBack son el mismo texto: son
 * nombres cortos y claros; el icono no repite la etiqueta.
 */
enum class MainTab(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Recipes(R.string.nav_tab_recipes, R.drawable.ic_tab_recipes),
    MealPlan(R.string.nav_tab_meal_plan, R.drawable.ic_tab_mealplan),
    Shopping(R.string.nav_tab_shopping, R.drawable.ic_tab_shopping),
}

/**
 * Barra inferior de pestañas. Cada destino lleva su etiqueta SIEMPRE visible
 * (no solo la elegida), un objetivo táctil de [Sizes.MinTouchTarget] y el
 * estado de selección que anuncia TalkBack.
 */
@Composable
fun GastronomiconBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    NavigationBar {
        MainTab.entries.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    // Decorativo: el nombre accesible es la etiqueta de abajo.
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // Las tres etiquetas siempre a la vista, no solo la elegida.
                alwaysShowLabel = true,
                // Con etiqueta visible, TalkBack leería el nombre dos veces (icono y texto).
                // Se deja como `contentDescription` del destino, que manda sobre el texto.
                modifier = Modifier
                    .heightIn(min = Sizes.MinTouchTarget)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/**
 * Marco de las pestañas: pone la barra abajo y deja el resto de la pantalla a la
 * lista elegida. Solo se pinta cuando toca (listas raíz); en detalle,
 * formulario, importación, Ajustes y primera configuración se llama con
 * [showBar] = false.
 *
 * Cada pantalla trae su propio `Scaffold` con sus barras. Para que no sumen dos
 * veces el inset de abajo, aquí se le pasa al contenido el alto de la barra y
 * se marca como consumido ([consumeWindowInsets]): dentro, el inset inferior
 * queda a cero y las barras propias no añaden un hueco de más.
 *
 * Al cambiar de pestaña, una se apaga mientras la otra aparece con un fundido corto
 * (`Motion.ShortMillis`, 120 ms): se ve que se ha cambiado de lista sin que la nueva
 * tarde en estar lista. Con las animaciones del sistema quitadas, el cambio es
 * instantáneo (`snap`). El contenido recibe la pestaña que le toca pintar, no la
 * elegida: durante el fundido conviven las dos, y cada una se dibuja con su estado.
 */
@Composable
fun MainTabsScaffold(
    selected: MainTab,
    showBar: Boolean,
    onSelect: (MainTab) -> Unit,
    content: @Composable (MainTab, Modifier) -> Unit,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    Scaffold(
        // La barra se queda con el inset de abajo; el contenido recibe solo su alto.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                GastronomiconBottomBar(selected = selected, onSelect = onSelect)
            }
        },
    ) { innerPadding ->
        val tabModifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
        AnimatedContent(
            targetState = selected,
            modifier = tabModifier,
            transitionSpec = {
                fadeIn(animationSpec = motionTween(enabled = animationsEnabled)) togetherWith
                    fadeOut(animationSpec = motionTween(enabled = animationsEnabled))
            },
            label = "pestana",
        ) { tab ->
            content(tab, Modifier)
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun GastronomiconBottomBarPreview() {
    GastronomiconTheme {
        GastronomiconBottomBar(selected = MainTab.Recipes, onSelect = {})
    }
}

@Preview(showBackground = true, widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GastronomiconBottomBarDarkPreview() {
    GastronomiconTheme(mode = ThemeMode.DARK) {
        GastronomiconBottomBar(selected = MainTab.MealPlan, onSelect = {})
    }
}

@Preview(showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun GastronomiconBottomBarLargeTextPreview() {
    GastronomiconTheme {
        GastronomiconBottomBar(selected = MainTab.Shopping, onSelect = {})
    }
}

// El marco de las pestañas con una caja de ejemplo en el sitio de la lista: comprueba
// que la barra se queda abajo y que el contenido no suma dos veces el inset inferior.
// Aquí no hay red ni ViewModels: solo el hueco de la pestaña elegida.
@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun MainTabsScaffoldPreview() {
    GastronomiconTheme {
        MainTabsScaffold(selected = MainTab.Recipes, showBar = true, onSelect = {}) { tab, modifier ->
            Surface(
                modifier = modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    modifier = Modifier.padding(Spacing.medium),
                )
            }
        }
    }
}
