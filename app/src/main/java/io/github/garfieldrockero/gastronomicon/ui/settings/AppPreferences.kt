package io.github.garfieldrockero.gastronomicon.ui.settings

import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Preferencias que no son secretas: el tema y el interruptor de la red local.
 * Van en `SharedPreferences` normales; el token NUNCA pasa por aquí (tiene su
 * propio almacén cifrado, `SessionStore`).
 *
 * Es una interfaz para poder usar una versión en memoria en previews y
 * pruebas, y para que la raíz de la app lea el tema con la misma fuente que
 * Ajustes.
 */
interface AppPreferences : io.github.garfieldrockero.gastronomicon.core.session.LocalHttpSwitch {
    var themeMode: ThemeMode

    /**
     * Interruptor «Conectar sin HTTPS». Desactivado de inicio. Viene de
     * `LocalHttpSwitch` (core/) para que `SessionReset.clear` lo reinicie al
     * borrar la sesión.
     */
    override var allowLocalHttp: Boolean
}

/** Preferencias en memoria para previews y pruebas: no persisten nada. */
class InMemoryAppPreferences(
    override var themeMode: ThemeMode = ThemeMode.SYSTEM,
    override var allowLocalHttp: Boolean = false,
) : AppPreferences
