package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.offline.StoreResult
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore

/**
 * [SessionStore] que borra los datos offline cuando la sesión deja de ser la misma.
 *
 * Los datos de la fase 8 (caché de recetas y borradores) son de una cuenta
 * concreta de un servidor concreto. Al cambiar de servidor, cambiar el token o
 * borrar la sesión no pueden sobrevivir: [clearOffline] vacía el almacén cifrado
 * entero (ver [KeystoreBlobStore.clearAll]).
 *
 * Se pone delante del almacén real en [AppContainer], así que **todos** los
 * caminos que ya existían —Primera configuración, Ajustes, `SessionReset` y el
 * aviso de almacén roto— quedan cubiertos sin tocar las pantallas: ninguno llama
 * a [KeystoreBlobStore.clearAll] por su cuenta.
 *
 * - [save] solo borra si la sesión guardada era otra (mismo servidor y mismo
 *   token no tiran la caché). Si no se puede leer la anterior, se borra: es
 *   preferible perder una caché a mezclar dos cuentas.
 * - [clear] borra la sesión y **también** lo offline, pase lo que pase: si el
 *   borrado de la sesión falla, la excepción sube tal cual (el llamante decide,
 *   como siempre) pero la caché de esa cuenta se va igual. Dejarla «para otro
 *   día» por un fallo de disco no aporta nada: o la sesión ya no está, o se
 *   vuelve a configurar, y la caché se rellena sola.
 * - Un fallo al borrar lo offline **no** se propaga: el almacén deja una marca de
 *   borrado pendiente y el arranque siguiente lo reintenta. Lo que quede sigue
 *   cifrado y ligado a su ámbito.
 */
class OfflineClearingSessionStore(
    private val delegate: SessionStore,
    private val clearOffline: () -> StoreResult<Unit>,
) : SessionStore {

    override fun load(): Session? = delegate.load()

    override fun save(session: Session) {
        // Antes de escribir: después, load() ya devolvería la nueva.
        val previous = try {
            delegate.load()
        } catch (ignored: Exception) {
            // No se sabe qué había (Keystore ocupado o clave perdida): falla cerrado.
            null
        }
        // Si el guardado falla, la excepción sube y no se toca nada offline: la
        // sesión anterior sigue en pie.
        delegate.save(session)
        if (previous != session) clearOfflineQuietly()
    }

    override fun clear() {
        try {
            delegate.clear()
        } finally {
            // En `finally` a propósito: si el borrado de la sesión falla, el error
            // sube igual, pero la caché de esa cuenta no se queda por el camino.
            clearOfflineQuietly()
        }
    }

    override fun toString(): String = "OfflineClearingSessionStore"

    /**
     * Nunca propaga. Un fallo queda anotado por el propio almacén (marca de
     * borrado pendiente) y se reintenta al arrancar.
     */
    private fun clearOfflineQuietly() {
        try {
            clearOffline()
        } catch (ignored: Exception) {
            // Los datos siguen cifrados y ligados a su ámbito; no se pierde seguridad,
            // como mucho queda una caché que ya no se va a mirar.
        }
    }
}
