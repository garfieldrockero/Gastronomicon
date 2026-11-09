package io.github.garfieldrockero.gastronomicon.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.garfieldrockero.gastronomicon.MainActivity
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingIds

/**
 * Los `Intent` y `PendingIntent` del widget, en un solo sitio para que todos
 * cumplan la misma regla:
 *
 * - **Explícitos** (componente propio): ni un `Intent` implícito puede acabar en
 *   otra app.
 * - **Inmutables** (`FLAG_IMMUTABLE`): nadie puede rellenar ni cambiar sus
 *   extras.
 * - **Sin datos sensibles**: ni URL, ni token, ni el nombre de la lista. El
 *   único extra de una fila es el UUID del elemento, validado al recibirlo con
 *   [ShoppingIds.valid]; si no es un UUID, se ignora. El data URI, además,
 *   identifica el `PendingIntent` de cada fila sin recurrir a extras.
 * - El de abrir la app es un `getActivity` a [MainActivity] con una acción
 *   propia; no hay deep links ni URIs del servidor.
 *
 * El receptor no está exportado: solo la propia app (y el sistema, con la
 * difusión explícita de `APPWIDGET_UPDATE`) puede entregarle estos intents.
 */
internal object ShoppingWidgetIntents {

    const val SCHEME = "gastronomicon"
    const val AUTHORITY = "widget"
    const val PATH_TOGGLE = "toggle"
    const val PATH_REFRESH = "refresh"

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** Toque en una fila: marca el elemento [itemId] (UUID ya validado). */
    fun toggle(context: Context, itemId: String): PendingIntent {
        val intent = providerIntent(context, ShoppingWidgetProvider.ACTION_TOGGLE)
            .setData(Uri.parse("$SCHEME://$AUTHORITY/$PATH_TOGGLE/$itemId"))
            .putExtra(ShoppingWidgetProvider.EXTRA_ITEM_ID, itemId)
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    /** Botón de actualizar: sí consulta la red, a petición de la persona. */
    fun refresh(context: Context): PendingIntent {
        val intent = providerIntent(context, ShoppingWidgetProvider.ACTION_REFRESH_NETWORK)
            .setData(Uri.parse("$SCHEME://$AUTHORITY/$PATH_REFRESH"))
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS)
    }

    /** Abre la app en la pestaña Compra. */
    fun open(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SHOPPING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, FLAGS)
    }

    /**
     * El id del elemento de un toque, o `null` si algo no cuadra.
     *
     * Se exige el data URI del widget con la forma `toggle/<uuid>` **y** que el
     * extra sea el mismo UUID: un `Intent` con otra forma, con texto o con un id
     * distinto se descarta sin tocar la red.
     */
    fun itemIdOf(data: Uri?, extra: String?): String? {
        if (data == null) return null
        if (data.scheme != SCHEME) return null
        if (data.authority != AUTHORITY) return null
        val segments = data.pathSegments
        if (segments.size != 2 || segments[0] != PATH_TOGGLE) return null
        val fromUri = ShoppingIds.valid(segments[1]) ?: return null
        val fromExtra = ShoppingIds.valid(extra) ?: return null
        return if (fromUri == fromExtra) fromUri else null
    }

    private fun providerIntent(context: Context, action: String): Intent =
        Intent(context, ShoppingWidgetProvider::class.java).setAction(action)
}
