package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import android.content.SharedPreferences
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.theme.ThemeMode

/**
 * Tema y modo de red local en `SharedPreferences` privadas y sin cifrar: no son
 * secretos. Archivo distinto del de la sesión. Excluido de las copias de
 * seguridad igual que todo lo demás (`allowBackup=false`).
 */
class SharedPreferencesAppPreferences(context: Context) : AppPreferences {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var themeMode: ThemeMode
        get() {
            val stored = prefs.getString(KEY_THEME, null)
            // Un valor desconocido (versión futura, archivo manipulado) vuelve al sistema.
            return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    override var allowLocalHttp: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_HTTP, false)
        set(value) {
            // Si no se pudo escribir se lanza: `SessionReset` apaga el interruptor
            // ANTES de borrar, y sin esto un fallo de disco pasaría por «apagado»
            // y se borraría la sesión con el interruptor aún encendido.
            if (!trySetAllowLocalHttp(value)) throw IllegalStateException("No se pudo guardar el modo de red.")
        }

    /**
     * Escribe el interruptor y dice si `commit()` lo aceptó. `commit()` y no
     * `apply()`: bloquea hasta que el sistema responde, pero no garantiza por sí
     * solo que el dato llegue al disco si el proceso muere justo después; lo que
     * sí da es el resultado, que aquí se devuelve en vez de ignorarlo.
     */
    fun trySetAllowLocalHttp(value: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_LOCAL_HTTP, value).commit()

    private companion object {
        const val PREFS_NAME = "gastronomicon_preferencias"
        const val KEY_THEME = "tema"
        const val KEY_LOCAL_HTTP = "http_local"
    }
}
