package io.github.garfieldrockero.gastronomicon.core.session

/**
 * Dónde se guarda la sesión. `core/` no sabe nada de Android: la
 * implementación real (con el Keystore) vive en `app/`.
 *
 * Los fallos de almacenamiento se comunican con excepciones, y quien implemente
 * esto no debe incluir en ellas ni el token ni la URL.
 */
interface SessionStore {
    /**
     * Devuelve la sesión guardada.
     *
     * Devuelve null si no hay sesión o si la guardada era inválida o irrecuperable (en ese
     * caso la implementación la ha borrado). Ante un fallo transitorio (p. ej. el Keystore
     * ocupado) lanza `SessionStorageException` y NO borra nada, para poder reintentar:
     * quien llame debe capturarla y no tratarla como «sin sesión».
     */
    fun load(): Session?

    /** Guarda la sesión, sustituyendo la anterior si la había. */
    fun save(session: Session)

    /** Borra la sesión guardada; no pasa nada si no había ninguna. */
    fun clear()
}
