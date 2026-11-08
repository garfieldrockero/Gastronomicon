package io.github.garfieldrockero.gastronomicon.core.session

import io.github.garfieldrockero.gastronomicon.core.config.ServerOrigin

/** Qué hay que hacer al pulsar «Guardar» en Ajustes. */
enum class SaveDecision {
    /** Se ha escrito un token nuevo: se guarda con él (la interfaz puede probarlo antes si quiere). */
    UseTypedToken,

    /** Mismo origen y token vacío: se conserva el guardado, sin probar. */
    KeepSavedToken,

    /**
     * Otro origen y token vacío: solo se guarda si `MealieClient.checkConnection()`
     * contra el servidor NUEVO, con el token guardado, devuelve `Success`.
     * Si falla, no se guarda nada.
     */
    TestThenSaveWithSavedToken,

    /** Token vacío y nada guardado que reutilizar: hay que escribir el token. */
    TokenRequired,
}

/**
 * Regla de «guardar solo tras probar» para no mandar el token guardado a un
 * servidor distinto sin comprobarlo (una errata de dominio, o algo que no es
 * Mealie con HTTPS válido, lo recibiría en la siguiente petición).
 */
object SaveSessionPolicy {

    /**
     * @param saved origen de la sesión guardada, o null si no hay sesión (o su
     *   URL no es válida: falla cerrado y cuenta como «distinto»).
     * @param new origen de la dirección que se quiere guardar.
     * @param typedTokenIsEmpty true si el campo de token está vacío (o solo
     *   espacios, a criterio de quien llame).
     */
    fun decide(saved: ServerOrigin?, new: ServerOrigin, typedTokenIsEmpty: Boolean): SaveDecision = when {
        !typedTokenIsEmpty -> SaveDecision.UseTypedToken
        // Sin sesión guardada no hay token que reutilizar.
        saved == null -> SaveDecision.TokenRequired
        saved == new -> SaveDecision.KeepSavedToken
        else -> SaveDecision.TestThenSaveWithSavedToken
    }

    /**
     * Versión con URLs. Si [newUrl] no es válida lanza
     * [IllegalArgumentException] con mensaje fijo (sin repetir la entrada);
     * si [savedUrl] no es válida se trata como sin sesión reutilizable.
     */
    fun decide(savedUrl: String?, newUrl: String, typedTokenIsEmpty: Boolean): SaveDecision {
        val new = ServerOrigin.of(newUrl)
            ?: throw IllegalArgumentException("La URL del servidor no es válida.")
        val saved = savedUrl?.let { ServerOrigin.of(it) }
        // Si lo guardado es ilegible, con token vacío no hay nada fiable que conservar.
        return decide(saved, new, typedTokenIsEmpty)
    }
}
