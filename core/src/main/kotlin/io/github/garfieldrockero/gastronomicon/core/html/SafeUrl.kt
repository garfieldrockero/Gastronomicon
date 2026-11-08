package io.github.garfieldrockero.gastronomicon.core.html

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Validación de una URL que llega del servidor (por ejemplo, el origen de una
 * receta importada) antes de que el estado la guarde.
 *
 * Solo se aceptan `http` y `https` absolutas, con servidor, sin usuario ni
 * contraseña y en ASCII imprimible. Todo lo demás (`javascript:`, `data:`,
 * `file:`, `intent:`, esquemas relativos, controles) da null. No abre nada:
 * decidir si se muestra o se abre es cosa de la interfaz.
 */
object SafeUrl {

    const val MAX_LENGTH = 2048

    fun httpOrNull(raw: String?): String? {
        val candidate = raw?.trim() ?: return null
        if (candidate.isEmpty() || candidate.length > MAX_LENGTH) return null
        // Sin espacios, controles ni Unicode: lo que no es ASCII visible no se
        // admite (los dominios internacionales vienen en forma «xn--»).
        if (candidate.any { it.code !in 0x21..0x7E }) return null
        val uri = try {
            URI(candidate)
        } catch (ignored: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.host.isNullOrEmpty()) return null
        return candidate
    }
}
