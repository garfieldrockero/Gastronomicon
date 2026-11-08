package io.github.garfieldrockero.gastronomicon.core.session

/**
 * Acceso mínimo al interruptor «Conectar sin HTTPS». `core/` no conoce las
 * preferencias de Android; `app/` implementa esto con las suyas (o hace que
 * su `AppPreferences` lo extienda).
 */
interface LocalHttpSwitch {
    /** Desactivado de inicio. */
    var allowLocalHttp: Boolean
}

/** Borrado de sesión. */
object SessionReset {

    /**
     * Borra la sesión y deja el interruptor de HTTP en claro en «desactivado»,
     * que es su valor de inicio. El interruptor se apaga PRIMERO: si el borrado
     * falla después, queda una sesión http con el interruptor apagado, que
     * [io.github.garfieldrockero.gastronomicon.core.ServerUrl] rechaza (falla
     * cerrado). Al revés, un fallo dejaría el interruptor encendido sin sesión.
     * Las excepciones del almacén se propagan sin tocar.
     */
    fun clear(store: SessionStore, localHttp: LocalHttpSwitch) {
        localHttp.allowLocalHttp = false
        store.clear()
    }
}
