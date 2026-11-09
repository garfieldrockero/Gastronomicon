package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences

/**
 * Guarda el **ámbito** de los datos sin conexión (recetas abiertas y
 * borradores) para poder enseñarlos al arrancar sin red.
 *
 * El ámbito es el hash de [ServerScope] (servidor + cuenta) y nada más: **ni la
 * URL, ni el id de usuario, ni el token** pasan por las preferencias. No es un
 * secreto, pero tampoco se enseña en ningún sitio.
 *
 * Cómo se evita mezclar cuentas:
 *
 * 1. Al probar la conexión (`MealieConnectionTester`) se pregunta
 *    `/api/users/self` y se apunta en memoria qué usuario ha respondido, con la
 *    URL y el token exactos que se acaban de probar ([onConnectionSucceeded]).
 *    Si esa es ya la sesión guardada, el ámbito se escribe en el acto (probar no
 *    debe obligar a guardar).
 * 2. Al **guardar** la sesión, si la que se escribe es justo esa (mismo
 *    servidor y mismo token, comparados en memoria sin escribirlos), se calcula
 *    el ámbito y se guarda su id ([onSessionSaved]). Probar un servidor y no
 *    guardarlo no deja nada.
 * 3. Al cambiar o borrar la sesión, [clear]: el ámbito muere con ella (ver
 *    `AppContainer`, que lo enchufa al vaciado de lo offline).
 *
 * Si algo falla por el camino no se guarda nada y la app no ofrece contenido
 * sin conexión (falla cerrado): es preferible perder la caché a enseñar la de
 * otra cuenta.
 *
 * Todo se llama desde hilos de fondo (red y Keystore); [pending] y [revoked]
 * son el único estado mutable y son `@Volatile`.
 */
class OfflineScopeStore(
    private val preferences: AppPreferences,
    /** La sesión guardada, para no exigir un guardado de más. Puede lanzar; se recoge. */
    private val savedSession: () -> Session?,
) {

    /**
     * Última prueba de conexión correcta, solo en memoria y hasta el guardado.
     * Guarda el token para comparar sesiones; nunca se escribe en disco y no
     * sale en `toString`.
     */
    @Volatile
    private var pending: Pending? = null

    /**
     * El último borrado no se pudo escribir del todo. Mientras siga a `true`,
     * [current] devuelve null: el borrado nunca se ignora en silencio. La marca
     * persistente de las preferencias cubre el caso de que el proceso muera.
     */
    @Volatile
    private var revoked = false

    /**
     * El ámbito de la cuenta guardada, o null si no hay ninguno válido (nada
     * offline). También null si el último borrado no se pudo escribir del todo
     * ([revoked] o la marca `offlineScopeClearPending`), aunque el id viejo siga
     * en las preferencias: se falla cerrado.
     *
     * Si la marca sigue puesta pero el id ya no está, no hay nada que pueda
     * quedar mal y se sigue con normalidad (el arranque quitará la marca).
     */
    fun current(): ServerScope? {
        if (revoked) return null
        val stored = preferences.offlineScopeId ?: return null
        if (preferences.offlineScopeClearPending) return null
        return ServerScope.fromStoredId(stored)
    }

    /** Conexión correcta con [serverUrl] y [token]: se recuerda la cuenta [userId]. */
    fun onConnectionSucceeded(serverUrl: String, token: ApiToken, userId: String) {
        val scope = ServerScope.of(serverUrl, userId) ?: run {
            // URL o id raros: mejor no ofrecer nada sin conexión.
            pending = null
            return
        }
        if (isSavedSession(serverUrl, token)) {
            // Ya es la sesión guardada: el ámbito se puede apuntar sin esperar a un guardado.
            storeScope(scope.id)
            pending = null
            return
        }
        pending = Pending(serverUrl, token.value, scope.id)
    }

    /**
     * Sesión guardada: el ámbito pasa a preferencias solo si es la que se acaba
     * de probar.
     *
     * En el orden real de `AppContainer` —`Clearing(Recording(...))`— el vaciado
     * de lo offline ([clear]) ya ha pasado antes por aquí y **no** olvida
     * [pending]: si lo hiciera, este método saldría sin escribir nada y la fase 8
     * quedaría apagada tras la primera configuración.
     */
    fun onSessionSaved(session: Session) {
        val remembered = pending ?: return
        // URL y token se comparan aquí, en memoria, y no se escriben en ningún sitio: si la
        // sesión guardada no es la que se probó, no se apunta ningún ámbito (falla cerrado).
        if (remembered.serverUrl != session.serverUrl) return
        if (remembered.token != session.token.value) return
        storeScope(remembered.scopeId)
        pending = null
    }

    /**
     * Sesión cambiada o borrada: el ámbito anterior ya no vale.
     *
     * Orden: primero la **marca** en las preferencias, después el borrado del id.
     * Si el proceso muere entre medias, el arranque siguiente ve la marca
     * ([retryPendingClear]) y repite el borrado en vez de servir el ámbito de la
     * cuenta anterior. El id se borra comprobándolo y se reintenta una vez; si
     * aun así sigue ahí, queda [revoked] y la marca puesta: este proceso no ofrece
     * nada sin conexión.
     *
     * **No toca [pending]** a propósito: en el orden de `AppContainer` este
     * `clear()` corre dentro del guardado de la sesión y `onSessionSaved` necesita
     * ese pending justo después. Olvidarlo es cosa de
     * [ScopeRecordingSessionStore.clear].
     */
    fun clear() {
        // Desde ya, nada con el ámbito viejo, mientras se intenta escribir.
        revoked = true
        preferences.offlineScopeClearPending = true
        finishClear()
    }

    /**
     * Reintenta un borrado del ámbito que se quedó marcado (un `commit()` que no
     * cuajó, el proceso muerto a media limpieza). Se llama al arrancar, junto a
     * `KeystoreBlobStore.retryPendingClear`; sin marca no toca nada.
     */
    fun retryPendingClear() {
        if (!preferences.offlineScopeClearPending) return
        revoked = true
        finishClear()
    }

    /**
     * La sesión se ha borrado: la prueba de conexión que hubiera ya no vale para
     * nada. Lo llama [ScopeRecordingSessionStore.clear] **después** del vaciado,
     * porque ese vaciado pasa por [clear], que a propósito no olvida el pending.
     */
    fun onSessionCleared() {
        pending = null
    }

    // Sin URL, token ni hash: un objeto de plataforma puede acabar en un log.
    override fun toString(): String = "OfflineScopeStore"

    /**
     * Borra el id (si hace falta) y, solo si ya no está, quita la marca. La marca
     * se queda si el borrado no cuaja: el arranque siguiente lo reintenta.
     */
    private fun finishClear() {
        if (!wipeScopeId()) return
        revoked = false
        removeClearPending()
    }

    /** Borra el id y lo comprueba leyendo, con un reintento. True si ya no está. */
    private fun wipeScopeId(): Boolean {
        repeat(CLEAR_ATTEMPTS) {
            preferences.offlineScopeId = null
            if (preferences.offlineScopeId == null) return true
        }
        return false
    }

    /**
     * Apunta el ámbito nuevo. La marca de borrado pendiente se quita **después**
     * de escribir el id: al revés, un fallo dejaría el id viejo sin nada que lo
     * bloqueara.
     */
    private fun storeScope(scopeId: String) {
        preferences.offlineScopeId = scopeId
        removeClearPending()
        revoked = false
    }

    /**
     * Quita la marca. Si el `commit()` no cuaja, se queda: como mucho el arranque
     * siguiente repetirá un borrado que ya no tiene trabajo. Con el id nuevo ya
     * escrito, [current] no se bloquea por eso (solo mira la marca si hay id).
     */
    private fun removeClearPending() {
        if (!preferences.offlineScopeClearPending) return
        preferences.offlineScopeClearPending = false
    }

    /** Si lo que se acaba de probar es exactamente la sesión guardada. */
    private fun isSavedSession(serverUrl: String, token: ApiToken): Boolean {
        val saved = try {
            savedSession()
        } catch (ignored: Exception) {
            // Almacén sin responder: se trata como «no hay sesión» y se espera al guardado.
            null
        } ?: return false
        return saved.serverUrl == serverUrl && saved.token.value == token.value
    }

    private class Pending(val serverUrl: String, val token: String, val scopeId: String) {
        // `data class` imprimiría el token y la URL.
        override fun toString(): String = "Pending"
    }

    private companion object {
        /** Un intento y su reintento: un `commit()` fallido casi siempre es puntual. */
        const val CLEAR_ATTEMPTS = 2
    }
}

/**
 * [SessionStore] que avisa a [scopeStore] de cada sesión guardada, para que el
 * ámbito de lo offline se escriba justo después de la sesión y no antes.
 *
 * Va **por fuera** de [OfflineClearingSessionStore] en `AppContainer`: primero
 * se guarda (y se vacía lo offline si la sesión cambia) y después se apunta el
 * ámbito nuevo. Al borrar, el vaciado de [OfflineClearingSessionStore] ya llama a
 * [OfflineScopeStore.clear]; aquí solo se olvida la prueba de conexión que
 * hubiera, **después** del vaciado (si se olvidara antes, guardar justo después
 * se quedaría sin ámbito).
 */
class ScopeRecordingSessionStore(
    private val delegate: SessionStore,
    private val scopeStore: OfflineScopeStore,
) : SessionStore {

    override fun load(): Session? = delegate.load()

    override fun save(session: Session) {
        delegate.save(session)
        scopeStore.onSessionSaved(session)
    }

    override fun clear() {
        delegate.clear()
        scopeStore.onSessionCleared()
    }

    override fun toString(): String = "ScopeRecordingSessionStore"
}
