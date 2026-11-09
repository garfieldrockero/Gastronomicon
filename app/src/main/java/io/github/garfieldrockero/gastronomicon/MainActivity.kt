package io.github.garfieldrockero.gastronomicon

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailState
import io.github.garfieldrockero.gastronomicon.platform.AppContainer
import io.github.garfieldrockero.gastronomicon.platform.purgeStaleCameraFilesAsync
import io.github.garfieldrockero.gastronomicon.ui.mealplan.MealPlanRoute
import io.github.garfieldrockero.gastronomicon.ui.mealplan.MealPlanViewModel
import io.github.garfieldrockero.gastronomicon.ui.navigation.MainTab
import io.github.garfieldrockero.gastronomicon.ui.navigation.MainTabsScaffold
import io.github.garfieldrockero.gastronomicon.ui.recipes.RecipeDetailViewModel
import io.github.garfieldrockero.gastronomicon.ui.recipes.RecipeEditorState
import io.github.garfieldrockero.gastronomicon.ui.recipes.RecipeEditorViewModel
import io.github.garfieldrockero.gastronomicon.ui.recipes.RecipeImportViewModel
import io.github.garfieldrockero.gastronomicon.ui.recipes.RecipeListViewModel
import io.github.garfieldrockero.gastronomicon.ui.recipes.RecipesRoute
import io.github.garfieldrockero.gastronomicon.ui.recipes.TopRequestMemo
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingRoute
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingViewModel
import io.github.garfieldrockero.gastronomicon.ui.settings.SettingsRoute
import io.github.garfieldrockero.gastronomicon.ui.settings.SettingsViewModel
import io.github.garfieldrockero.gastronomicon.ui.setup.SetupRoute
import io.github.garfieldrockero.gastronomicon.ui.setup.SetupViewModel
import io.github.garfieldrockero.gastronomicon.ui.start.StartLoadingScreen
import io.github.garfieldrockero.gastronomicon.ui.start.StorageProblemScreen
import io.github.garfieldrockero.gastronomicon.ui.theme.GastronomiconTheme
import io.github.garfieldrockero.gastronomicon.ui.theme.isDark
import io.github.garfieldrockero.gastronomicon.ui.theme.themeBackground

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La foto de la cámara vive en la caché mientras se recodifica: lo que quedara de
        // una sesión anterior (app cerrada a medias) se borra al arrancar, fuera del hilo
        // principal y sin tocar el fichero de ninguna foto elegida de la galería.
        purgeStaleCameraFilesAsync(this)
        enableEdgeToEdge()
        setContent {
            val appViewModel: AppViewModel = viewModel(
                factory = AppViewModel.factory(container.sessionStore, container.preferences),
            )
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val dark = themeMode.isDark()
            // Las barras del sistema y el fondo de la ventana siguen el tema
            // ELEGIDO, no el del sistema (el XML solo conoce este último).
            LaunchedEffect(dark) { applySystemBars(dark) }
            GastronomiconTheme(mode = themeMode) {
                AppRoot(appViewModel, container)
            }
        }
    }

    private fun applySystemBars(dark: Boolean) {
        // Con el modo elegido y no el del sistema: `detectDarkMode` solo devuelve `dark`.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { dark },
        )
        window.setBackgroundDrawable(ColorDrawable(themeBackground(dark).toArgb()))
    }

    private companion object {
        // Los mismos velos que usa `enableEdgeToEdge()` por defecto con la navegación de tres botones.
        val LIGHT_NAV_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x16)
    }
}

/**
 * Navegación de la V1: una pantalla a la vez según [AppViewModel.screen]. Ajustes
 * es una pantalla aparte (flecha «Atrás» y botón Atrás del sistema vuelven a la
 * pestaña que se estuviera viendo, salvo mientras se borra la sesión; ver
 * `SettingsRoute`). Las tres listas raíz (Recetas, Planificador y Compra) comparten
 * `AppScreen.Recipes` y se cambian con la barra inferior ([AppViewModel.tab]).
 *
 * Los ViewModels de Primera configuración y Ajustes viven en la actividad y no
 * cargan nada al crearse; al entrar se les llama a `onEnter()` para que no se
 * vea ni un token a medio escribir ni un aviso de la vez anterior.
 */
@Composable
private fun AppRoot(appViewModel: AppViewModel, container: AppContainer) {
    val screen by appViewModel.screen.collectAsStateWithLifecycle()
    val setupViewModel: SetupViewModel = viewModel(
        factory = SetupViewModel.factory(
            container.sessionStore,
            container.preferences,
            container.connectionTester,
        ),
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            container.sessionStore,
            container.preferences,
            container.connectionTester,
            container.appVersion,
        ),
    )

    val recipeListViewModel: RecipeListViewModel = viewModel(
        factory = RecipeListViewModel.factory(container.clientAccess),
    )
    val recipeDetailViewModel: RecipeDetailViewModel = viewModel(
        factory = RecipeDetailViewModel.factory(container.clientAccess),
    )
    val recipeEditorViewModel: RecipeEditorViewModel = viewModel(
        factory = RecipeEditorViewModel.factory(container.clientAccess, container.imageRecoder),
    )
    val recipeImportViewModel: RecipeImportViewModel = viewModel(
        factory = RecipeImportViewModel.factory(container.clientAccess),
    )
    val mealPlanViewModel: MealPlanViewModel = viewModel(
        factory = MealPlanViewModel.factory(container.mealPlanAccess, container.clientAccess),
    )
    val shoppingViewModel: ShoppingViewModel = viewModel(
        factory = ShoppingViewModel.factory(container.shoppingAccess),
    )

    // El desplazamiento de la lista vive aquí, fuera de Recetas: así sobrevive a ir a Ajustes
    // y volver (mirar el tema o «Acerca de» no tira la posición). «Volver arriba» lo aplica
    // RecipesRoute cuando la lista está en pantalla (ver `TopRequestMemo`), no desde aquí.
    val recipesScroll = rememberLazyListState()
    val topMemo = remember { TopRequestMemo(recipeListViewModel.state.value.topRequest) }

    // Cancela lo que corra en las tres pestañas y olvida lo cargado (recetas, planificador,
    // compra y fotos). Se llama en todo camino que lleve a una sesión distinta: otro servidor,
    // token o interruptor HTTP, borrado (también a medias), errores del almacén y entrada en la
    // primera configuración. Lo que hay en memoria es del servidor o del token anteriores.
    // También vuelve a la pestaña de Recetas: la sesión nueva empieza por lo primero.
    fun resetRecipes() {
        recipeDetailViewModel.reset()
        recipeListViewModel.reset()
        recipeEditorViewModel.reset()
        recipeImportViewModel.reset()
        mealPlanViewModel.reset()
        shoppingViewModel.reset()
        appViewModel.onTabSelected(MainTab.Recipes)
        container.imageLoader.clear()
    }

    // El «sesión borrada» lo da el propio SettingsViewModel al terminar, esté o
    // no Ajustes en pantalla; así la navegación nunca se queda sin enterarse.
    SideEffect {
        // Guardar otro servidor, otro token u otro valor del HTTP local; nada más reinicia.
        settingsViewModel.onSessionChangedListener = { resetRecipes() }
        settingsViewModel.onSessionClearedListener = {
            setupViewModel.onEnter()
            appViewModel.onSessionCleared()
        }
        // Entrar en Setup o en el aviso del almacén, guardar desde Setup y borrar tras un fallo
        // del almacén: AppViewModel avisa y se vacía todo (ver `AppViewModel.onSessionDiscarded`).
        appViewModel.onSessionDiscarded = { resetRecipes() }
    }

    when (val current = screen) {
        AppScreen.Loading -> StartLoadingScreen()

        AppScreen.Setup -> SetupRoute(
            viewModel = setupViewModel,
            onSaved = appViewModel::onSetupSaved,
        )

        AppScreen.Recipes -> {
            val tab by appViewModel.tab.collectAsStateWithLifecycle()
            val recipeDetail by recipeDetailViewModel.state.collectAsStateWithLifecycle()
            val recipeEditor by recipeEditorViewModel.state.collectAsStateWithLifecycle()
            val recipeImport by recipeImportViewModel.state.collectAsStateWithLifecycle()

            // La barra de pestañas solo se ve en las listas raíz: el detalle, el formulario y
            // la importación ocupan la pantalla entera (con sus barras y su propio «Atrás»).
            val recipesAtRoot = recipeDetail == RecipeDetailState.Closed &&
                recipeEditor == RecipeEditorState.Closed &&
                !recipeImport.visible
            val showBar = tab != MainTab.Recipes || recipesAtRoot

            // Atrás en otra pestaña vuelve a Recetas; en Recetas manda lo de siempre
            // (detalle, formulario… o salir de la app).
            BackHandler(enabled = tab != MainTab.Recipes) {
                appViewModel.onTabSelected(MainTab.Recipes)
            }

            val openSettings = {
                settingsViewModel.onEnter()
                appViewModel.onOpenSettings()
            }

            // La barra de pestañas ya se queda con el inset de abajo: a las tres listas
            // raíz se les quita para que su propio Scaffold no lo sume otra vez. El
            // superior y los laterales siguen llegando como siempre.
            val tabInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
            )

            MainTabsScaffold(
                selected = tab,
                showBar = showBar,
                onSelect = appViewModel::onTabSelected,
            ) { contentModifier ->
                when (tab) {
                    MainTab.Recipes -> RecipesRoute(
                        listViewModel = recipeListViewModel,
                        detailViewModel = recipeDetailViewModel,
                        editorViewModel = recipeEditorViewModel,
                        importViewModel = recipeImportViewModel,
                        imageLoader = container.imageLoader,
                        scroll = recipesScroll,
                        topMemo = topMemo,
                        onOpenSettings = openSettings,
                        modifier = contentModifier,
                        listContentWindowInsets = tabInsets,
                    )

                    MainTab.MealPlan -> MealPlanRoute(
                        viewModel = mealPlanViewModel,
                        onOpenSettings = openSettings,
                        modifier = contentModifier,
                        contentWindowInsets = tabInsets,
                    )

                    MainTab.Shopping -> ShoppingRoute(
                        viewModel = shoppingViewModel,
                        onOpenSettings = openSettings,
                        modifier = contentModifier,
                        contentWindowInsets = tabInsets,
                    )
                }
            }
        }

        AppScreen.Settings -> {
            SettingsRoute(
                viewModel = settingsViewModel,
                onBack = {
                    // Lo que se estuviera probando o guardando se cancela al salir.
                    settingsViewModel.onLeave()
                    appViewModel.onCloseSettings()
                },
                onThemeChanged = appViewModel::onThemeChanged,
            )
        }

        is AppScreen.StorageProblem -> StorageProblemScreen(
            clearing = current.clearing,
            clearFailed = current.clearFailed,
            onRetry = appViewModel::start,
            onClearSession = appViewModel::onClearAfterStorageProblem,
        )
    }
}
