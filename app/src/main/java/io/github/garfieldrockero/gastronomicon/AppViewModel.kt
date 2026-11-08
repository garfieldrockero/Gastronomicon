package io.github.garfieldrockero.gastronomicon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.session.SessionReset
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.platform.SessionStorageException
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pantalla que toca mostrar. La navegación de la V1 es esta lista corta, sin librería. */
sealed interface AppScreen {
    /** Leyendo la sesión guardada. */
    data object Loading : AppScreen

    /** Sin sesión: primera configuración. */
    data object Setup : AppScreen

    data object Recipes : AppScreen

    data object Settings : AppScreen

    /** El Keystore no contestó; no se tomó por «sin sesión». */
    data class StorageProblem(val clearing: Boolean, val clearFailed: Boolean) : AppScreen
}

/**
 * Decide qué pantalla se ve: lee la sesión al arrancar (fuera del hilo
 * principal) y lleva de una a otra. Vive en el ViewModel para sobrevivir a un
 * giro de pantalla; si el proceso muere se vuelve a empezar leyendo la sesión.
 *
 * También guarda el tema efectivo para que la raíz lo aplique al instante
 * cuando se cambia en Ajustes (Ajustes ya lo ha escrito en las preferencias).
 */
class AppViewModel(
    private val sessionStore: SessionStore,
    private val preferences: AppPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Loading)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _themeMode = MutableStateFlow(preferences.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        start()
    }

    /** Lee la sesión: con ella, Recetas; sin ella, primera configuración; si el almacén falla, su pantalla. */
    fun start() {
        _screen.value = AppScreen.Loading
        viewModelScope.launch {
            val next = withContext(ioDispatcher) {
                try {
                    if (sessionStore.load() != null) AppScreen.Recipes else AppScreen.Setup
                } catch (e: SessionStorageException) {
                    AppScreen.StorageProblem(clearing = false, clearFailed = false)
                }
            }
            _screen.value = next
        }
    }

    fun onSetupSaved() {
        _screen.value = AppScreen.Recipes
    }

    fun onOpenSettings() {
        _screen.value = AppScreen.Settings
    }

    /** Al salir de Ajustes se relee la sesión: puede haberse borrado o el almacén haber fallado. */
    fun onCloseSettings() = start()

    fun onSessionCleared() {
        _screen.value = AppScreen.Setup
    }

    fun onThemeChanged(mode: ThemeMode) {
        _themeMode.value = mode
    }

    /** «Borrar la sesión y volver a configurar» desde la pantalla de fallo del almacén. Borrar no descifra. */
    fun onClearAfterStorageProblem() {
        val current = _screen.value
        if (current !is AppScreen.StorageProblem || current.clearing) return
        _screen.value = AppScreen.StorageProblem(clearing = true, clearFailed = false)
        viewModelScope.launch {
            val cleared = withContext(ioDispatcher) {
                try {
                    // Apaga el interruptor de HTTP y después borra (ver SessionReset).
                    SessionReset.clear(sessionStore, preferences)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            _screen.value = if (cleared) {
                AppScreen.Setup
            } else {
                AppScreen.StorageProblem(clearing = false, clearFailed = true)
            }
        }
    }

    companion object {
        fun factory(sessionStore: SessionStore, preferences: AppPreferences): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(sessionStore, preferences) as T
            }
    }
}
