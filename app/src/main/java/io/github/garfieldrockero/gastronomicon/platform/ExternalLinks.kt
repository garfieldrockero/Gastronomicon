package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.garfieldrockero.gastronomicon.core.recipes.SourceLink

/**
 * Abre el origen de una receta en el navegador del sistema (nunca en una vista web de
 * la app). Se llama SOLO desde un toque explícito de la persona. Devuelve false, sin
 * abrir nada, si el enlace no es `https` (`SourceLink.isHttps`, regla del núcleo) o si
 * el sistema no ha podido abrirlo; la pantalla dice entonces, con un texto fijo,
 * «No se pudo abrir el enlace».
 *
 * Se captura cualquier `RuntimeException` del `startActivity` (sin navegador,
 * permisos, intents rechazados por el sistema…) y NO se registra su mensaje: puede
 * llevar la URL. El fallo no queda mudo: la persona lo ve.
 */
fun openInBrowser(context: Context, link: SourceLink): Boolean {
    if (!link.isHttps) return false
    val uri = Uri.parse(link.url)
    if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty()) return false
    val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        true
    } catch (ignored: RuntimeException) {
        false
    }
}
