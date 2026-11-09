package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.offline.StoreResult
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.ui.settings.InMemoryAppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El ámbito de lo offline y el orden real de `AppContainer`:
 * `ScopeRecordingSessionStore(OfflineClearingSessionStore(SessionStore))`.
 *
 * El punto delicado es que el vaciado de lo offline pasa por
 * [OfflineScopeStore.clear] **antes** de que [ScopeRecordingSessionStore] apunte
 * el ámbito nuevo: si `clear()` olvidara la prueba de conexión, guardar tras
 * probar dejaría la fase 8 apagada.
 */
class OfflineScopeStoreTest {

    @Test
    fun savingAfterTestingKeepsTheScopeEvenThoughTheSessionChangeClearsIt() {
        val preferences = InMemoryAppPreferences()
        val keystore = FakeSessionStore()
        val scopeStore = OfflineScopeStore(preferences) { keystore.load() }
        val chain = chainOf(keystore, scopeStore)

        // Primera configuración: se prueba la conexión (no hay sesión guardada) y se guarda.
        scopeStore.onConnectionSucceeded(SERVER, TOKEN, USER)
        chain.save(Session(SERVER, TOKEN))

        // El guardado vació lo offline por el camino (la sesión anterior era otra) y aun así
        // el ámbito queda escrito: es lo que enciende la fase 8.
        assertNotNull(preferences.offlineScopeId)
        assertNotNull(scopeStore.current())
    }

    @Test
    fun deletingTheSessionForgetsWhatWasTested() {
        val preferences = InMemoryAppPreferences()
        val keystore = FakeSessionStore()
        val scopeStore = OfflineScopeStore(preferences) { keystore.load() }
        val chain = chainOf(keystore, scopeStore)

        scopeStore.onConnectionSucceeded(SERVER, TOKEN, USER)
        chain.clear()
        // Después de borrar la sesión, lo probado ya no vale para nada: guardar otra
        // sesión sin volver a probar no deja ámbito (falla cerrado).
        chain.save(Session(SERVER, TOKEN))

        assertNull(preferences.offlineScopeId)
        assertNull(scopeStore.current())
    }

    @Test
    fun aPendingClearHidesTheScopeUntilTheRetryFinishes() {
        val preferences = InMemoryAppPreferences()
        // Como si un borrado se hubiera quedado marcado y el proceso hubiera muerto.
        preferences.offlineScopeId = SCOPE_ID
        preferences.offlineScopeClearPending = true
        val scopeStore = OfflineScopeStore(preferences) { null }

        // El id viejo sigue en el archivo, pero no se sirve.
        assertNull(scopeStore.current())

        scopeStore.retryPendingClear()

        assertNull(preferences.offlineScopeId)
        assertFalse(preferences.offlineScopeClearPending)
        assertNull(scopeStore.current())
    }

    @Test
    fun aFreshScopeRemovesThePendingMark() {
        val preferences = InMemoryAppPreferences()
        preferences.offlineScopeClearPending = true
        val scopeStore = OfflineScopeStore(preferences) { null }

        // Sin id guardado no hay nada que pueda mezclar cuentas: la marca sobra.
        assertNull(scopeStore.current())

        scopeStore.onConnectionSucceeded(SERVER, TOKEN, USER)
        scopeStore.onSessionSaved(Session(SERVER, TOKEN))

        assertNotNull(scopeStore.current())
        assertFalse(preferences.offlineScopeClearPending)
        assertNotNull(preferences.offlineScopeId)
    }

    /** El mismo orden que monta `AppContainer`. */
    private fun chainOf(keystore: SessionStore, scopeStore: OfflineScopeStore): SessionStore =
        ScopeRecordingSessionStore(
            OfflineClearingSessionStore(keystore) {
                scopeStore.clear()
                StoreResult.Ok(Unit)
            },
            scopeStore,
        )

    private class FakeSessionStore : SessionStore {
        private var saved: Session? = null

        override fun load(): Session? = saved

        override fun save(session: Session) {
            saved = session
        }

        override fun clear() {
            saved = null
        }
    }

    private companion object {
        const val SERVER = "https://mealie.example.com"
        const val USER = "cuenta-1"

        /** Un ámbito con la forma exacta que acepta `ServerScope.fromStoredId`. */
        const val SCOPE_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val TOKEN = ApiToken("token-de-prueba")
    }
}
