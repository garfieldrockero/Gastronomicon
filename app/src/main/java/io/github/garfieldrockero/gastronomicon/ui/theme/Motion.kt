package io.github.garfieldrockero.gastronomicon.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Movimiento de la app: dos tiempos cortos y un solo sitio donde se deciden.
 *
 * La app se usa de pie, con una mano y a ratos: el movimiento sirve para saber que
 * algo ha cambiado, no para lucirse. Nada de rebotes, elemento compartido ni
 * deslizamientos largos (decisión de la persona).
 */
object Motion {
    /**
     * Fundido al cambiar de pantalla, de estado o al aparecer una fila. Tan corto que
     * no frena la lectura: en el súper, 120 ms ya se notan.
     */
    const val ShortMillis = 120

    /**
     * Recolocación de filas y cambios de tamaño. Algo más largo que el fundido porque
     * el ojo necesita seguir el elemento que se mueve, pero sin pasarse.
     */
    const val MediumMillis = 200
}

/**
 * Si el móvil tiene las animaciones encendidas ahora mismo.
 *
 * Lee el ajuste de Android «Quitar animaciones» (Accesibilidad), que es el mismo
 * `ANIMATOR_DURATION_SCALE` del modo desarrollador, sin pedir ningún permiso. Si el
 * ajuste vale 0 o no se puede leer, se devuelve `false`: es preferible una app sin
 * animaciones que una app que anima cuando la persona ha pedido lo contrario.
 *
 * Se lee una vez por composición (no se queda escuchando): cambiar el ajuste con la
 * app abierta es un caso raro y se aplica al volver a entrar en la pantalla.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrNull()
        scale != null && scale != 0f
    }
}

/**
 * La animación que toca para [enabled]: un `tween` corto de [millis] o, si las
 * animaciones están quitadas, `snap` (termina en el acto) para que ningún cambio se
 * quede a medias ni retrase un aviso.
 *
 * El tipo (`Float` para un fundido, `IntOffset` para una recolocación, `IntSize` para
 * un cambio de tamaño) lo pone quien la usa.
 */
fun <T> motionTween(enabled: Boolean, millis: Int = Motion.ShortMillis): FiniteAnimationSpec<T> =
    if (enabled) tween(durationMillis = millis) else snap()
