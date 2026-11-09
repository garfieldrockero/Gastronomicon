package io.github.garfieldrockero.gastronomicon.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.garfieldrockero.gastronomicon.MainActivity
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderMessage

/**
 * Canal y aviso del recordatorio del planificador.
 *
 * - El canal se crea **al activar** el recordatorio (no al arrancar la app) y con
 *   [NotificationManager.IMPORTANCE_DEFAULT] porque la decisión D5 es que suene:
 *   un recordatorio mudo se pierde. La importancia de un canal **no se puede
 *   subir** después de crearlo; si algún día se quiere otra cosa, habrá que usar
 *   un id nuevo y borrar el viejo.
 * - Aviso único, con etiqueta y id fijos: cada día **sustituye** al anterior en
 *   vez de apilarse.
 * - **Nombres visibles en la pantalla de bloqueo** (decisión D4): se pinta con
 *   `VISIBILITY_PUBLIC` y **sin** `setPublicVersion`. La nota libre de la entrada
 *   no está en el texto: `ReminderContent` ni siquiera la recibe.
 * - Pulsar abre el planificador con un `PendingIntent` **inmutable y sin datos**
 *   (solo una acción): nada de fechas ni nombres en el `Intent`.
 * - Sin permiso de notificaciones no se construye nada y no se lanza: un
 *   `SecurityException` se recoge (en API 33+ el sistema suele descartarla en
 *   silencio, pero versiones intermedias pueden lanzar).
 */
object ReminderNotifier {

    /** Id del canal. Constante de `app/` porque es una cosa de Android, no del núcleo. */
    const val CHANNEL_ID = "recordatorio_plan"

    private const val NOTIFICATION_TAG = "recordatorio"
    private const val NOTIFICATION_ID = 1001
    private const val OPEN_REQUEST_CODE = 4101

    /** Crea el canal si no existe. Llamarlo de nuevo no cambia su importancia. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /** ¿El sistema deja publicar notificaciones de la app ahora mismo? */
    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Publica el aviso con el texto ya compuesto por `core/`. Devuelve `true` si
     * el sistema lo aceptó; `false` si estaban bloqueadas o la publicación falló.
     * No registra nada.
     */
    fun notify(context: Context, message: ReminderMessage): Boolean {
        if (!notificationsAllowed(context)) return false

        val openIntent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_MEAL_PLAN)
        val contentIntent = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
            true
        } catch (ignored: SecurityException) {
            false
        }
    }
}
