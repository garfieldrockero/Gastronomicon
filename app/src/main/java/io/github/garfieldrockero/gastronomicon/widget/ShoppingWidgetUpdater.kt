package io.github.garfieldrockero.gastronomicon.widget

import android.content.Context
import android.content.Intent

/**
 * Avisa al widget de que la copia ha cambiado (o de que hay que repintarla).
 *
 * Es una difusión **explícita** al receptor propio: no le afectan las
 * restricciones de difusiones implícitas de Android 8+ y no necesita permiso.
 * No consulta la red ni descifra nada por sí misma: el receptor solo leerá la
 * caché y repintará. Los fallos al enviarla se ignoran: en el peor caso el
 * widget se queda con la copia anterior hasta el siguiente repintado.
 */
object ShoppingWidgetUpdater {

    /** Pide al receptor que repinte todos los widgets con la caché actual. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        try {
            val intent = Intent(appContext, ShoppingWidgetProvider::class.java)
                .setAction(ShoppingWidgetProvider.ACTION_REFRESH_CACHE)
            appContext.sendBroadcast(intent)
        } catch (ignored: RuntimeException) {
            // El widget no es crítico: no se tumba ninguna operación por esto.
        }
    }
}
