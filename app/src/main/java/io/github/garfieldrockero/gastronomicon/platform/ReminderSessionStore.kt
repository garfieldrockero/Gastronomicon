package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore

/**
 * [SessionStore] que mantiene la alarma del recordatorio atada a la sesión
 * guardada (decisión D7):
 *
 * - Al **guardar** otra sesión, se reprograma con la nueva (el guardado ya ha
 *   vaciado la copia del plan de la cuenta anterior en
 *   [OfflineClearingSessionStore] y el ámbito nuevo ya está apuntado).
 * - Al **borrar** la sesión, se cancela el aviso. La copia del plan la borra el
 *   vaciado de lo offline (`mp1.` está en `BlobPrefix.ALL`); la preferencia del
 *   interruptor **se conserva**.
 *
 * Va por fuera de [ScopeRecordingSessionStore] en `AppContainer`. Nunca lanza
 * por la alarma: [ReminderScheduler] recoge sus propios fallos, así que un
 * problema del sistema de alarmas no puede tumbar el guardado ni el borrado.
 */
class ReminderSessionStore(
    private val delegate: SessionStore,
    private val scheduler: ReminderScheduler,
) : SessionStore {

    override fun load(): Session? = delegate.load()

    override fun save(session: Session) {
        delegate.save(session)
        scheduler.schedule()
    }

    override fun clear() {
        try {
            delegate.clear()
        } finally {
            scheduler.cancel()
        }
    }

    override fun toString(): String = "ReminderSessionStore"
}
