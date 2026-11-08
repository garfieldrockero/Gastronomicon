package io.github.garfieldrockero.gastronomicon.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Prohíbe capturar o grabar la ventana de la actividad mientras este
 * composable esté en pantalla.
 *
 * Se usa en las pantallas donde se escribe el token: con `FLAG_SECURE` el
 * sistema no deja hacer capturas, no permite grabar la pantalla y no guarda la
 * miniatura en Recientes, que es justo lo que se vería al cambiar de app.
 * Es una defensa del sistema operativo, no de la app: aquí no se puede filtrar
 * lo que nunca se llega a componer en forma de imagen.
 *
 * El efecto se retira al salir del composable, así que no afecta al resto de la
 * app. Cuando Ajustes también escriba el token, las dos pantallas lo pedirán:
 * hoy solo hay una, y por eso basta con poner y quitar la marca.
 */
@Composable
fun SecureWindowEffect() {
    val context = LocalContext.current
    // En el panel de diseño no hay ventana que proteger y no se toca nada.
    val preview = LocalInspectionMode.current
    DisposableEffect(context, preview) {
        val window = if (preview) null else context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/**
 * Busca la actividad subiendo por los envoltorios del contexto: dentro de
 * Compose, [LocalContext] puede ser la actividad, un tema suyo o el propio
 * contexto de la aplicación.
 *
 * En una preview no hay actividad y devuelve `null`: el efecto no hace nada y
 * no falla, para que el panel de diseño siga funcionando.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base: Context = current.baseContext
        // Un envoltorio que se devuelve a sí mismo colgaría el bucle.
        if (base === current) return null
        current = base
    }
    return null
}
