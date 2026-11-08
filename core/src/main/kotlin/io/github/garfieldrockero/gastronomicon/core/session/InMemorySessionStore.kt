package io.github.garfieldrockero.gastronomicon.core.session

/**
 * Almacén en memoria. Va en main y no en test porque `app/` lo puede querer
 * para sus propias pruebas y para previsualizaciones, y los fuentes de test de
 * `core/` no se comparten con otros módulos. No persiste nada: no sirve como
 * almacén real.
 *
 * `@Volatile` basta porque cada operación toca una sola referencia.
 */
class InMemorySessionStore(initial: Session? = null) : SessionStore {

    @Volatile
    private var current: Session? = initial

    override fun load(): Session? = current

    override fun save(session: Session) {
        current = session
    }

    override fun clear() {
        current = null
    }

    // Sin sesión en el toString: heredaría la máscara, pero así ni se plantea.
    override fun toString(): String = "InMemorySessionStore(hasSession=${current != null})"
}
