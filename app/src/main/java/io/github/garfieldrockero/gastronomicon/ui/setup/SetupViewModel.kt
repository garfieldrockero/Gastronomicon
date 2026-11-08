package io.github.garfieldrockero.gastronomicon.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.config.ServerAddress
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.addressProblemOf
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.platform.SessionStorageException
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.settings.ConnectionTestResult
import io.github.garfieldrockero.gastronomicon.ui.settings.ConnectionTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Posee el [SetupUiState] de la primera configuración. Como en Ajustes, solo
 * une piezas: validar es de `core/` (`ServerAddress`, `ApiToken`), guardar es
 * del [SessionStore] y probar es del [ConnectionTester].
 *
 * El interruptor «Conectar sin HTTPS» empieza siempre desactivado (esta
 * pantalla solo se ve sin sesión) y su valor se guarda en las preferencias
 * junto con la sesión. El token se vacía del estado en cuanto se guarda.
 */
class SetupViewModel(
    private val sessionStore: SessionStore,
    private val preferences: AppPreferences,
    private val connectionTester: ConnectionTester,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private fun initialState() = SetupUiState(
        server = "",
        port = ServerAddress.defaultPortFor(false).toString(),
        token = "",
        allowLocalHttp = false,
        canSave = false,
        status = SetupStatus.Idle,
    )

    /** Al volver a esta pantalla (p. ej. tras borrar la sesión): campos limpios. */
    fun onEnter() {
        _state.value = initialState()
    }

    fun onServerChange(value: String) = edit { it.copy(server = value) }

    fun onPortChange(value: String) = edit { it.copy(port = value) }

    fun onTokenChange(value: String) = edit { it.copy(token = value) }

    fun onAllowLocalHttpChange(value: Boolean) = edit { current ->
        // Si el puerto sigue en el sugerido del modo anterior, pasa al del nuevo.
        val keepsSuggested = current.port == ServerAddress.defaultPortFor(current.allowLocalHttp).toString()
        current.copy(
            allowLocalHttp = value,
            port = if (keepsSuggested) ServerAddress.defaultPortFor(value).toString() else current.port,
        )
    }

    private fun edit(change: (SetupUiState) -> SetupUiState) {
        _state.update { current ->
            if (current.busy) return@update current
            change(current).copy(status = SetupStatus.Idle).withCanSave()
        }
    }

    fun onTestConnection() {
        val snapshot = startBusy(SetupStatus.Testing) ?: return
        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { guarded { test(snapshot) } }
            _state.update { it.copy(status = outcome) }
        }
    }

    fun onSave() {
        val snapshot = startBusy(SetupStatus.Saving) ?: return
        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { guarded(saving = true) { save(snapshot) } }
            _state.update { current ->
                // Con la sesión guardada el token sale del estado.
                if (outcome == SetupStatus.Saved) current.copy(token = "", status = outcome) else current.copy(status = outcome)
            }
        }
    }

    /** Pone «ocupado» y devuelve la foto de lo que hay que usar; null si ya hay algo en marcha o faltan datos. */
    private fun startBusy(status: SetupStatus): SetupUiState? {
        var snapshot: SetupUiState? = null
        _state.update { current ->
            snapshot = null
            if (current.busy || !current.canSave) {
                current
            } else {
                snapshot = current
                current.copy(status = status)
            }
        }
        return snapshot
    }

    private suspend fun test(s: SetupUiState): SetupStatus {
        val url = try {
            buildUrl(s)
        } catch (e: IllegalArgumentException) {
            return SetupStatus.Error(addressProblemOf(e))
        }
        val token = parseToken(s.token) ?: return SetupStatus.Error(ConnectionProblem.InvalidToken)
        return when (val result = connectionTester.test(url, token, s.allowLocalHttp)) {
            ConnectionTestResult.Success -> SetupStatus.Success
            is ConnectionTestResult.Failure -> SetupStatus.Error(result.problem)
        }
    }

    /**
     * Guarda solo si la conexión sale bien: se prueba con lo escrito y únicamente
     * con Success se escribe la sesión. Si la prueba falla, no se guarda nada y
     * se enseña el motivo.
     */
    private suspend fun save(s: SetupUiState): SetupStatus {
        val url = try {
            buildUrl(s)
        } catch (e: IllegalArgumentException) {
            return SetupStatus.SaveFailed(addressProblemOf(e))
        }
        val token = parseToken(s.token) ?: return SetupStatus.SaveFailed(ConnectionProblem.InvalidToken)
        when (val result = connectionTester.test(url, token, s.allowLocalHttp)) {
            ConnectionTestResult.Success -> Unit
            is ConnectionTestResult.Failure -> return SetupStatus.SaveFailed(result.problem)
        }
        try {
            sessionStore.save(Session(url, token))
        } catch (e: IllegalArgumentException) {
            // Session exige la URL canónica; si no lo es, la dirección no vale.
            return SetupStatus.SaveFailed(ConnectionProblem.InvalidAddress)
        }
        // Primero la sesión y luego la preferencia: si se corta entre las dos,
        // queda una sesión sin interruptor, que falla cerrado.
        preferences.allowLocalHttp = s.allowLocalHttp
        return SetupStatus.Saved
    }

    private fun buildUrl(s: SetupUiState): String {
        val portText = s.port.trim()
        val port = if (portText.isEmpty()) {
            null
        } else {
            portText.toIntOrNull() ?: throw IllegalArgumentException("Puerto no numérico")
        }
        return ServerAddress.build(s.server, port, s.allowLocalHttp)
    }

    private fun parseToken(input: String): ApiToken? = try {
        ApiToken.fromInput(input)
    } catch (e: IllegalArgumentException) {
        null
    }

    private val SetupUiState.busy: Boolean
        get() = status == SetupStatus.Testing || status == SetupStatus.Saving || status == SetupStatus.Saved

    private fun SetupUiState.withCanSave(): SetupUiState =
        copy(canSave = server.isNotBlank() && port.isNotBlank() && token.isNotBlank())

    /**
     * Un fallo del almacén o un error inesperado se reduce a un estado cerrado:
     * sin mensajes de la excepción, que podrían arrastrar datos. Un fallo del
     * almacén seguro tiene su propio aviso.
     */
    private inline fun guarded(saving: Boolean = false, block: () -> SetupStatus): SetupStatus = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SessionStorageException) {
        SetupStatus.StorageError
    } catch (e: Exception) {
        if (saving) SetupStatus.SaveFailed(ConnectionProblem.Unknown) else SetupStatus.Error(ConnectionProblem.Unknown)
    }

    companion object {
        fun factory(
            sessionStore: SessionStore,
            preferences: AppPreferences,
            connectionTester: ConnectionTester,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SetupViewModel(sessionStore, preferences, connectionTester) as T
        }
    }
}
