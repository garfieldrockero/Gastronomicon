package io.github.garfieldrockero.gastronomicon

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.content.Intent
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
import io.github.garfieldrockero.gastronomicon.platform.purgeCameraFilesAsync
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
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }

    /**
     * Petición de abrir el planificador pendiente (una notificación pulsada).
     * Se consume en la composición, que es quien tiene el [AppViewModel] de la
     * pestaña. Vive en la actividad porque el `Intent` llega antes que la UI.
     */
    private val openMealPlan = MutableStateFlow(false)

    /**
     * Petición de abrir la pestaña Compra (el widget pulsado). Misma mecánica
     * que [openMealPlan]: el `Intent` llega antes de que exista la composición.
     */
    private val openShopping = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // La foto de la cámara vive en la caché mientras se recodifica: al arrancar el
        // proceso se vacía lo que hubiera quedado antes (una sola vez, aunque la actividad
        // se recree), fuera del hilo principal y sin tocar la foto de la galería.
        purgeCameraFilesAsync(this)
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
                AppRoot(appViewModel, container, openMealPlan, openShopping)
            }
        }
    }

    /**
     * Con `singleTop`, pulsar el aviso con la app abierta no crea otra instancia:
     * llega aquí. Solo se lee la acción propia; el `Intent` no lleva datos.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Al volver a primer plano se repinta el widget desde su copia cifrada (sin
     * red). Es la red de seguridad para el que se añadió con la app cerrada o
     * para el que quedó con el estado vacío.
     */
    override fun onStart() {
        super.onStart()
        container.refreshShoppingWidget()
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_MEAL_PLAN -> openMealPlan.value = true
            // El título del widget de la compra abre su pestaña, no un deep link.
            ACTION_OPEN_SHOPPING -> openShopping.value = true
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

    companion object {
        /**
         * Acción del `PendingIntent` de la notificación del recordatorio. Abre
         * esta actividad y selecciona la pestaña del planificador (decisión D8).
         * El `Intent` no lleva ni fechas ni nombres: solo esta acción.
         */
        const val ACTION_OPEN_MEAL_PLAN: String =
            "io.github.garfieldrockero.gastronomicon.action.ABRIR_PLANIFICADOR"

        /**
         * Acción del `PendingIntent` del widget de la compra: abre esta actividad
         * y selecciona la pestaña Compra. Sin extras: no hay deep links ni URIs
         * del servidor.
         */
        const val ACTION_OPEN_SHOPPING: String =
            "io.github.garfieldrockero.gastronomicon.action.ABRIR_COMPRA"

        // Los mismos velos que usa `enableEdgeToEdge()` por defecto con la navegación de tres botones.
        private val LIGHT_NAV_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_NAV_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x16)
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
private fun AppRoot(
    appViewModel: AppViewModel,
    container: AppContainer,
    openMealPlan: MutableStateFlow<Boolean>,
    openShopping: MutableStateFlow<Boolean>,
) {
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
            container.reminderAccess,
        ),
    )

    val recipeListViewModel: RecipeListViewModel = viewModel(
        factory = RecipeListViewModel.factory(container.clientAccess, container.offline),
    )
    val recipeDetailViewModel: RecipeDetailViewModel = viewModel(
        factory = RecipeDetailViewModel.factory(
            container.clientAccess,
            container.offline,
            container.shoppingAccess,
        ),
    )
    val recipeEditorViewModel: RecipeEditorViewModel = viewModel(
        factory = RecipeEditorViewModel.factory(container.clientAccess, container.imageRecoder, container.offline),
    )
    val recipeImportViewModel: RecipeImportViewModel = viewModel(
        factory = RecipeImportViewModel.factory(container.clientAccess),
    )
    val mealPlanViewModel: MealPlanViewModel = viewModel(
        factory = MealPlanViewModel.factory(
            container.mealPlanAccess,
            container.clientAccess,
            container.shoppingAccess,
        ),
    )
    val shoppingViewModel: ShoppingViewModel = viewModel(
        factory = ShoppingViewModel.factory(container.shoppingAccess, container.shoppingWidgetSink),
    )

    // Pulsar el aviso del recordatorio lleva al planificador (decisión D8). Con
    // `singleTop` no se crea otra actividad: se cambia la pestaña. Si la persona
    // estaba en Ajustes, se cierra; la primera configuración no se toca.
    val mealPlanRequest by openMealPlan.collectAsStateWithLifecycle()
    LaunchedEffect(mealPlanRequest) {
        if (!mealPlanRequest) return@LaunchedEffect
        appViewModel.onTabSelected(MainTab.MealPlan)
        if (appViewModel.screen.value == AppScreen.Settings) {
            settingsViewModel.onLeave()
            appViewModel.onCloseSettings()
        }
        openMealPlan.value = false
    }

    // El título del widget de la compra hace lo mismo con su pestaña.
    val shoppingRequest by openShopping.collectAsStateWithLifecycle()
    LaunchedEffect(shoppingRequest) {
        if (!shoppingRequest) return@LaunchedEffect
        appViewModel.onTabSelected(MainTab.Shopping)
        if (appViewModel.screen.value == AppScreen.Settings) {
            settingsViewModel.onLeave()
            appViewModel.onCloseSettings()
        }
        openShopping.value = false
    }

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
            ) { currentTab, contentModifier ->
                when (currentTab) {
                    MainTab.Recipes -> RecipesRoute(
                        listViewModel = recipeListViewModel,
                        detailViewModel = recipeDetailViewModel,
                        editorViewModel = recipeEditorViewModel,
                        importViewModel = recipeImportViewModel,
                        imageLoader = container.imageLoader,
                        scroll = recipesScroll,
                        topMemo = topMemo,
                        onOpenSettings = openSettings,
                        // «Ver la compra» del detalle: se cierra la receta (el detalle ocupa la
                        // pantalla entera y su aviso ya no hace falta) y se cambia de pestaña.
                        onOpenShoppingTab = {
                            recipeDetailViewModel.close()
                            appViewModel.onTabSelected(MainTab.Shopping)
                        },
                        modifier = contentModifier,
                        listContentWindowInsets = tabInsets,
                    )

                    MainTab.MealPlan -> MealPlanRoute(
                        viewModel = mealPlanViewModel,
                        onOpenSettings = openSettings,
                        // El aviso de la semana lleva a la compra: no hace falta volver a verlo
                        // al regresar al planificador.
                        onOpenShoppingTab = {
                            mealPlanViewModel.onAddToShoppingNoticeDismiss()
                            appViewModel.onTabSelected(MainTab.Shopping)
                        },
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
