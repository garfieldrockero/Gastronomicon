package io.github.garfieldrockero.gastronomicon.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.config.ServerAddress
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.addressProblemOf
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSettings
import io.github.garfieldrockero.gastronomicon.core.session.SaveDecision
import io.github.garfieldrockero.gastronomicon.core.session.SaveSessionPolicy
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionReset
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.platform.SessionStorageException
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Posee el [SettingsUiState] de Ajustes. Solo une piezas: validar la dirección
 * y el token es de `core/` (`ServerAddress`, `ApiToken`, `SaveSessionPolicy`),
 * guardar es del [SessionStore] y probar la conexión es de [ConnectionTester].
 *
 * Todo lo que toca el Keystore o la red va fuera del hilo principal.
 * El token guardado no sale nunca de aquí hacia el estado.
 *
 * No carga nada al crearse: quien abre Ajustes llama a [onEnter], que lee la
 * sesión y deja el estado limpio (sin token escrito ni avisos de la vez anterior).
 */
class SettingsViewModel(
    private val sessionStore: SessionStore,
    private val preferences: AppPreferences,
    private val connectionTester: ConnectionTester,
    private val appVersion: String,
    private val reminderAccess: ReminderAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Lo último que se leyó o guardó: con esto se sabe si hay cambios («dirty»). */
    private class Baseline(val server: String, val port: String, val allowLocalHttp: Boolean)

    private var baseline = Baseline("", "", false)
    private var loadJob: Job? = null

    /** Probar o guardar en marcha: se cancela al salir de Ajustes o al volver a entrar. */
    private var actionJob: Job? = null

    /**
     * Quien navega se entera aquí de que la sesión ya está borrada, directamente
     * desde el ViewModel y no desde la pantalla: así llega aunque Ajustes ya no
     * esté en pantalla. Se llama en el hilo principal, una vez por borrado.
     */
    var onSessionClearedListener: (() -> Unit)? = null

    /**
     * Aviso de «sesión cambiada»: se ha guardado otro servidor, otro token u otro valor
     * del interruptor de HTTP local. Quien muestra las recetas las olvida entonces; si
     * Ajustes solo se ha mirado (tema, «Acerca de», cambios no guardados) no se llama,
     * y la lista conserva lo cargado y su posición. Se llama en el hilo principal, una
     * vez por guardado, aunque se salga de Ajustes mientras termina de guardar.
     */
    var onSessionChangedListener: (() -> Unit)? = null

    // Se pone en el hilo de fondo justo al guardar; se consume en el principal.
    @Volatile
    private var sessionChanged = false

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun initialState(): SettingsUiState = SettingsUiState(
        loading = true,
        loadFailed = false,
        server = "",
        port = ServerAddress.defaultPortFor(preferences.allowLocalHttp).toString(),
        allowLocalHttp = preferences.allowLocalHttp,
        tokenInput = "",
        hasSavedToken = false,
        canSave = false,
        dirty = false,
        themeMode = preferences.themeMode,
        appVersion = appVersion,
        status = SettingsStatus.Idle,
        sessionStatus = SessionStatus.Idle,
        showClearConfirm = false,
    )

    /** Al abrir la pantalla: estado limpio y lectura de la sesión guardada. */
    fun onEnter() {
        actionJob?.cancel()
        actionJob = null
        load()
    }

    /**
     * Al salir de Ajustes: lo que se estuviera probando o guardando se cancela y
     * no guarda nada. El borrado de la sesión NO se cancela (la pantalla impide
     * salir mientras dura).
     */
    fun onLeave() {
        actionJob?.cancel()
        actionJob = null
        _state.update { current ->
            if (current.status == SettingsStatus.Testing || current.status == SettingsStatus.Saving) {
                current.copy(status = SettingsStatus.Idle)
            } else {
                current
            }
        }
    }

    fun onRetryLoad() = load()

    private fun load() {
        loadJob?.cancel()
        _state.value = initialState()
        loadJob = viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                try {
                    LoadResult.Loaded(
                        session = sessionStore.load(),
                        reminder = reminderAccess.settings(),
                        notificationsAllowed = reminderAccess.notificationsAllowed(),
                    )
                } catch (e: SessionStorageException) {
                    LoadResult.Failed
                }
            }
            _state.update { current -> withLoaded(current, loaded) }
        }
    }

    /** Aplica el resultado de leer la sesión al estado y reinicia la referencia de «sin cambios». */
    private fun withLoaded(current: SettingsUiState, loaded: LoadResult): SettingsUiState = when (loaded) {
        LoadResult.Failed -> current.copy(loading = false, loadFailed = true).withCanSave()
        is LoadResult.Loaded -> {
            val session = loaded.session
            val fields = session?.let { fieldsFor(it.serverUrl, current.allowLocalHttp) }
            val withFields = current.copy(
                loading = false,
                loadFailed = false,
                server = fields?.server ?: current.server,
                port = fields?.port ?: current.port,
                hasSavedToken = session != null,
                reminder = ReminderUiState(
                    enabled = loaded.reminder.enabled && session != null,
                    minuteOfDay = loaded.reminder.minuteOfDay,
                    notificationsAllowed = loaded.notificationsAllowed,
                    permissionDenied = false,
                ),
            )
            baseline = Baseline(withFields.server, withFields.port, withFields.allowLocalHttp)
            withFields.withCanSave()
        }
    }

    // ---- Edición de campos: cualquier cambio invalida el resultado anterior ----

    fun onServerChange(value: String) = edit { it.copy(server = value) }

    fun onPortChange(value: String) = edit { it.copy(port = value) }

    fun onTokenChange(value: String) = edit { it.copy(tokenInput = value) }

    fun onAllowLocalHttpChange(value: Boolean) = edit { current ->
        // Si el puerto sigue en el sugerido del modo anterior, pasa al del nuevo.
        val keepsSuggested = current.port == ServerAddress.defaultPortFor(current.allowLocalHttp).toString()
        current.copy(
            allowLocalHttp = value,
            port = if (keepsSuggested) ServerAddress.defaultPortFor(value).toString() else current.port,
        )
    }

    private fun edit(change: (SettingsUiState) -> SettingsUiState) {
        _state.update { current ->
            if (current.busy) return@update current
            change(current).copy(status = SettingsStatus.Idle).withCanSave()
        }
    }

    // ---- Tema ----

    fun onThemeChange(mode: ThemeMode) {
        preferences.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    // ---- Probar y guardar ----

    /**
     * Prueba la conexión con lo escrito. OJO, es el diseño y no un descuido: con
     * el campo del token vacío y OTRO origen (esquema, host o puerto), se manda
     * el token GUARDADO a `users/self` del servidor nuevo, una vez que su `about`
     * ha salido válido (ver `MealieConnectionTester`).
     *
     * Y ese `about` frena erratas, no atacantes: cualquier servidor que conteste
     * con la forma de `AppInfo` lo pasa. No es autenticación del servidor y nadie
     * debe tomarlo por tal; el freno real es que la persona escribió esa dirección.
     */
    fun onTestConnection() {
        val snapshot = startBusy(SettingsStatus.Testing) ?: return
        actionJob = viewModelScope.launch {
            val outcome = withContext(ioDispatcher) { guarded({ it }) { test(snapshot) } }
            _state.update { it.copy(status = outcome) }
        }
    }

    fun onSave() {
        val current = _state.value
        if (!current.canSave || !current.dirty) return
        val snapshot = startBusy(SettingsStatus.Saving) ?: return
        actionJob = viewModelScope.launch {
            try {
                saveAndPublish(snapshot)
            } finally {
                // También si se cancela: la sesión puede haberse guardado ya.
                notifySessionChanged()
            }
        }
    }

    private suspend fun saveAndPublish(snapshot: SettingsUiState) {
        val outcome = withContext(ioDispatcher) {
            guarded({ SaveOutcome.Failed(it) }, saving = true) { save(snapshot) }
        }
        _state.update { latest ->
            when (outcome) {
                is SaveOutcome.Done -> {
                    val fields = fieldsFor(outcome.serverUrl, snapshot.allowLocalHttp)
                    val saved = latest.copy(
                        server = fields?.server ?: latest.server,
                        port = fields?.port ?: latest.port,
                        allowLocalHttp = snapshot.allowLocalHttp,
                        tokenInput = "",
                        hasSavedToken = true,
                        status = SettingsStatus.Saved,
                    )
                    baseline = Baseline(saved.server, saved.port, saved.allowLocalHttp)
                    saved.withCanSave()
                }
                is SaveOutcome.Failed -> latest.copy(status = outcome.status)
            }
        }
    }

    private fun notifySessionChanged() {
        if (!sessionChanged) return
        sessionChanged = false
        onSessionChangedListener?.invoke()
    }

    /** Pone el estado «ocupado» y devuelve la foto de lo que hay que usar; null si ya hay algo en marcha o no hay datos. */
    private fun startBusy(status: SettingsStatus): SettingsUiState? {
        var snapshot: SettingsUiState? = null
        _state.update { current ->
            // La actualización puede repetirse si hay carrera: se parte siempre de cero.
            snapshot = null
            if (current.busy || current.loading || current.loadFailed) {
                current
            } else {
                snapshot = current
                current.copy(status = status)
            }
        }
        return snapshot
    }

    private suspend fun test(s: SettingsUiState): SettingsStatus {
        val url = try {
            buildUrl(s)
        } catch (e: IllegalArgumentException) {
            return SettingsStatus.Error(addressProblemOf(e))
        }
        val token = tokenFor(s) ?: return SettingsStatus.Error(ConnectionProblem.InvalidToken)
        return when (val result = connectionTester.test(url, token, s.allowLocalHttp)) {
            ConnectionTestResult.Success -> SettingsStatus.Success
            is ConnectionTestResult.Failure -> SettingsStatus.Error(result.problem)
        }
    }

    /**
     * «Guardar» según la regla de `core/` ([SaveSessionPolicy]): con un token
     * escrito se prueba y, solo si sale bien, se guarda con él; con el campo vacío y el mismo origen se conserva
     * el guardado; y con el campo vacío y OTRO origen (esquema, host o puerto) el
     * token guardado solo viaja al servidor nuevo si `checkConnection` contra él
     * sale bien. Si falla, no se guarda nada.
     */
    private suspend fun save(s: SettingsUiState): SaveOutcome {
        val url = try {
            buildUrl(s)
        } catch (e: IllegalArgumentException) {
            return failed(addressProblemOf(e))
        }
        val saved = sessionStore.load()
        val decision = try {
            SaveSessionPolicy.decide(saved?.serverUrl, url, typedTokenIsEmpty = s.tokenInput.isBlank())
        } catch (e: IllegalArgumentException) {
            return failed(ConnectionProblem.InvalidAddress)
        }
        val invalidToken = failed(ConnectionProblem.InvalidToken)
        val token: ApiToken = when (decision) {
            SaveDecision.UseTypedToken -> {
                // Con token escrito también se prueba antes de guardar: solo se
                // guarda con Success, igual que en la rama de abajo.
                val typed = parseToken(s.tokenInput) ?: return invalidToken
                when (val result = connectionTester.test(url, typed, s.allowLocalHttp)) {
                    ConnectionTestResult.Success -> typed
                    is ConnectionTestResult.Failure ->
                        return failed(result.problem)
                }
            }
            SaveDecision.KeepSavedToken -> saved?.token ?: return invalidToken
            SaveDecision.TestThenSaveWithSavedToken -> {
                val savedToken = saved?.token ?: return invalidToken
                when (val result = connectionTester.test(url, savedToken, s.allowLocalHttp)) {
                    ConnectionTestResult.Success -> savedToken
                    is ConnectionTestResult.Failure ->
                        return failed(result.problem)
                }
            }
            SaveDecision.TokenRequired -> return invalidToken
        }
        // Si se ha salido de Ajustes durante la prueba, no se guarda nada.
        currentCoroutineContext().ensureActive()
        // Se marca ANTES de escribir: si el guardado se interrumpe o falla a medias, el
        // almacén puede haber cambiado y lo cargado ya no se fía.
        sessionChanged = true
        try {
            sessionStore.save(Session(url, token))
        } catch (e: IllegalArgumentException) {
            // Session exige la URL canónica; si no lo es, la dirección no vale y no se escribió nada.
            sessionChanged = false
            return failed(ConnectionProblem.InvalidAddress)
        }
        preferences.allowLocalHttp = s.allowLocalHttp
        return SaveOutcome.Done(url)
    }

    private fun failed(problem: ConnectionProblem) = SaveOutcome.Failed(SettingsStatus.SaveFailed(problem))

    /** Servidor y puerto de los campos a la URL canónica; la regla vive en `ServerAddress`. */
    private fun buildUrl(s: SettingsUiState): String {
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

    /**
     * El token que toca usar: el escrito si lo hay, y si no el guardado. Null si
     * lo escrito no tiene forma de token o no hay ninguno guardado.
     * Puede lanzar [SessionStorageException]; [guarded] la recoge.
     */
    private fun tokenFor(s: SettingsUiState): ApiToken? =
        if (s.tokenInput.isNotBlank()) parseToken(s.tokenInput) else sessionStore.load()?.token

    /** Lo que se escribe en «Servidor» y «Puerto» para una URL guardada. */
    private class Fields(val server: String, val port: String)

    /**
     * Parte la URL con `ServerAddress.split` (la lógica es del núcleo) y rellena
     * los dos campos como en la primera pantalla, con el puerto que de verdad se
     * usa. Si el esquema guardado no es el que `ServerAddress.build` deduciría con
     * el interruptor actual (http sin interruptor, o https con él), se deja
     * explícito en el campo para que guardar no cambie de esquema sin que se vea.
     */
    private fun fieldsFor(url: String, allowLocalHttp: Boolean): Fields? {
        val split = ServerAddress.split(url) ?: return null
        val inferred = if (allowLocalHttp) "http" else "https"
        val server = if (split.scheme == inferred) split.server else "${split.scheme}://${split.server}"
        return Fields(server, split.port.toString())
    }

    // ---- Borrar la sesión ----

    fun onClearSessionClick() = _state.update { if (it.busy) it else it.copy(showClearConfirm = true) }

    fun onClearSessionDismiss() = _state.update { it.copy(showClearConfirm = false) }

    fun onClearSessionConfirm() {
        // Borrar no descifra, así que también se puede con la carga fallida.
        var proceed = false
        _state.update { current ->
            proceed = !current.busy && !current.loading
            if (proceed) {
                current.copy(showClearConfirm = false, sessionStatus = SessionStatus.Clearing)
            } else {
                current.copy(showClearConfirm = false)
            }
        }
        if (!proceed) return
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
            if (cleared) {
                finishCleared()
            } else {
                // El almacén o el interruptor pueden haber cambiado aunque el borrado falle:
                // lo cargado deja de valer (se avisa tras releer, más abajo).
                sessionChanged = true
                // Borrado a medias: SessionReset ya pudo apagar el interruptor y el
                // almacén puede haber quedado de cualquier forma. Se vuelve a leer
                // lo que hay de verdad y se reinicia la referencia de «sin cambios».
                val reread = withContext(ioDispatcher) {
                    try {
                        LoadResult.Loaded(
                            session = sessionStore.load(),
                            reminder = reminderAccess.settings(),
                            notificationsAllowed = reminderAccess.notificationsAllowed(),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LoadResult.Failed
                    }
                }
                if (reread is LoadResult.Loaded && reread.session == null) {
                    // En realidad ya no hay sesión: es un borrado completo.
                    finishCleared()
                } else {
                    notifySessionChanged()
                    _state.update { current ->
                        val fresh = current.copy(
                            allowLocalHttp = preferences.allowLocalHttp,
                            server = "",
                            port = ServerAddress.defaultPortFor(preferences.allowLocalHttp).toString(),
                            tokenInput = "",
                            hasSavedToken = false,
                            status = SettingsStatus.Idle,
                            sessionStatus = SessionStatus.StorageError,
                        )
                        withLoaded(fresh, reread)
                    }
                }
            }
        }
    }

    /** Sesión borrada: campos a los valores de inicio, estado `Cleared` y aviso a quien navega. */
    private fun finishCleared() {
        // El aviso de «sesión borrada» ya vacía todo; el de «cambiada» sobra.
        sessionChanged = false
        _state.update { current ->
            baseline = Baseline("", ServerAddress.defaultPortFor(false).toString(), false)
            current.copy(
                loadFailed = false,
                server = baseline.server,
                port = baseline.port,
                allowLocalHttp = false,
                tokenInput = "",
                hasSavedToken = false,
                status = SettingsStatus.Idle,
                sessionStatus = SessionStatus.Cleared,
                // Sin sesión no hay aviso; la preferencia guardada se conserva
                // (decisión D7) y volverá a verse al configurar de nuevo.
                reminder = current.reminder.copy(enabled = false),
            ).withCanSave()
        }
        onSessionClearedListener?.invoke()
    }

    // ---- Recordatorio del planificador ----

    /**
     * Enciende o apaga el aviso. Solo con una sesión guardada y sin una acción en
     * marcha. Si el sistema no deja publicar notificaciones (decisión D6), **no**
     * se enciende: se deja constancia para que la pantalla enseñe el aviso y el
     * botón a los ajustes del sistema.
     */
    fun onReminderEnabledChange(enabled: Boolean) {
        val current = _state.value
        if (current.loading || current.loadFailed || !current.hasSavedToken) return
        if (enabled) {
            // Se pregunta al sistema justo ahora: el permiso pudo concederse o
            // revocarse en sus ajustes con Ajustes abierto.
            if (queryNotificationsAllowed() != true) {
                onReminderPermissionDenied()
                return
            }
            reminderAccess.setEnabled(true)
            _state.update { latest ->
                latest.copy(
                    reminder = latest.reminder.copy(
                        enabled = true,
                        // Ya está permitido: el aviso del permiso se quita en la
                        // misma pulsación, sin esperar a volver a la pantalla.
                        notificationsAllowed = true,
                        permissionDenied = false,
                    ),
                )
            }
            return
        }
        reminderAccess.setEnabled(false)
        _state.update { latest ->
            latest.copy(reminder = latest.reminder.copy(enabled = false, permissionDenied = false))
        }
    }

    /**
     * Vuelve a preguntar al sistema si deja publicar notificaciones. Se llama al
     * volver a Ajustes (`ON_RESUME`, ver `SettingsRoute`) porque el permiso se
     * puede conceder o revocar desde los ajustes del sistema sin cerrar la app:
     *
     * - al concederlo, el aviso de la sección desaparece;
     * - al revocarlo con el interruptor encendido, el aviso vuelve a verse. La
     *   preferencia no se toca: la persona no lo ha apagado, y en cuanto vuelva a
     *   permitir las notificaciones el aviso funcionará otra vez.
     *
     * Sin sesión guardada no hay sección que corregir y no se pregunta nada.
     */
    fun refreshNotificationsAllowed() {
        val current = _state.value
        if (current.loading || current.loadFailed || !current.hasSavedToken) return
        viewModelScope.launch {
            val allowed = withContext(ioDispatcher) { queryNotificationsAllowed() } ?: return@launch
            _state.update { latest ->
                if (latest.loading || latest.loadFailed) {
                    latest
                } else {
                    latest.copy(reminder = latest.reminder.copy(notificationsAllowed = allowed))
                }
            }
        }
    }

    /**
     * Lo que dice el sistema ahora mismo, o `null` si no contesta (no se cambia
     * nada de lo que ya se sabía). Al activar, `null` cuenta como «no»: la rama
     * cerrada de D6.
     */
    private fun queryNotificationsAllowed(): Boolean? = try {
        reminderAccess.notificationsAllowed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** El diálogo del sistema ha denegado el permiso: el interruptor no se queda encendido. */
    fun onReminderPermissionDenied() {
        _state.update { current ->
            current.copy(
                reminder = current.reminder.copy(
                    enabled = false,
                    notificationsAllowed = false,
                    permissionDenied = true,
                ),
            )
        }
    }

    /**
     * Cambia la hora del aviso (minuto del día). La coacción a 0..1439 es de
     * `core/` y la implementación reprograma la alarma si está activado.
     */
    fun onReminderMinuteChange(minuteOfDay: Int) {
        if (_state.value.loading) return
        val safe = ReminderSettings.coerceMinuteOfDay(minuteOfDay)
        reminderAccess.setMinuteOfDay(safe)
        _state.update { current ->
            current.copy(reminder = current.reminder.copy(minuteOfDay = safe))
        }
    }

    // ---- Auxiliares ----

    private fun SettingsUiState.withCanSave(): SettingsUiState = copy(
        canSave = !loading && !loadFailed && server.isNotBlank() && port.isNotBlank() &&
            (tokenInput.isNotBlank() || hasSavedToken),
        dirty = tokenInput.isNotBlank() || server != baseline.server || port != baseline.port ||
            allowLocalHttp != baseline.allowLocalHttp,
    )

    /**
     * Un fallo del almacén o un error inesperado se reduce a un estado cerrado:
     * sin mensajes de la excepción, que podrían arrastrar datos.
     */
    private inline fun <T> guarded(
        failed: (SettingsStatus) -> T,
        saving: Boolean = false,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SessionStorageException) {
        failed(SettingsStatus.StorageError)
    } catch (e: Exception) {
        failed(
            if (saving) SettingsStatus.SaveFailed(ConnectionProblem.Unknown)
            else SettingsStatus.Error(ConnectionProblem.Unknown)
        )
    }

    private sealed interface LoadResult {
        data class Loaded(
            val session: Session?,
            val reminder: ReminderSettings,
            val notificationsAllowed: Boolean,
        ) : LoadResult

        data object Failed : LoadResult
    }

    private sealed interface SaveOutcome {
        data class Done(val serverUrl: String) : SaveOutcome
        data class Failed(val status: SettingsStatus) : SaveOutcome
    }

    companion object {
        /** Fábrica para `ViewModelProvider`: el ViewModel recibe sus piezas por constructor. */
        fun factory(
            sessionStore: SessionStore,
            preferences: AppPreferences,
            connectionTester: ConnectionTester,
            appVersion: String,
            reminderAccess: ReminderAccess,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    sessionStore,
                    preferences,
                    connectionTester,
                    appVersion,
                    reminderAccess,
                ) as T
        }
    }
}
