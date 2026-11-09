package io.github.garfieldrockero.gastronomicon.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.garfieldrockero.gastronomicon.core.offline.MealPlanCache
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineRead
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderContent
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSchedule
import io.github.garfieldrockero.gastronomicon.core.reminder.ReminderSettings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receptor del recordatorio: recibe la alarma diaria y las difusiones que
 * obligan a reprogramarla (reinicio, actualización de la APK, cambio de hora o
 * de zona). **No abre conexiones y no lee la sesión ni el token**: solo
 * preferencias, el ámbito (`offlineScopeId`) y la copia cifrada del plan
 * (`MealPlanCache`), que tiene su propia clave del almacén de blobs.
 *
 * Reglas que aplica, todas decididas antes en el núcleo:
 * - **Un aviso al día**: `ReminderSettings.shouldNotify`. Si ese día ya se
 *   avisó (reprogramación, reloj movido), no se repite.
 * - **Comparar con el día local**: si el disparo llega pasada la medianoche
 *   (Doze, móvil apagado), el instante programado de hoy todavía está por venir
 *   y el aviso sería el de ayer bajo el título «Comidas de hoy»: no se avisa y
 *   solo se reprograma.
 * - **Tarde**: más de `ReminderSchedule.LATE_THRESHOLD` de retraso tampoco avisa.
 * - **Sin copia, copia caducada o sin comidas para hoy**: no se inventa un aviso
 *   (decisión D3); `MealPlanCache` ya borra la copia caducada (D12).
 * - **Se programa antes de notificar**: el de mañana no se pierde si notificar
 *   falla o el proceso muere.
 * - En reinicio, actualización o cambio de hora **solo se reprograma**; nunca se
 *   notifica.
 *
 * El trabajo real va en un hilo con `goAsync()` y un **vigilante de tiempo**:
 * leer el blob puede descifrar con el Keystore frío y no se debe pasar del
 * presupuesto de un receptor de difusión (ANR). Si el vigilante salta, se
 * termina el receptor; no se toca ningún dato a medias porque la escritura del
 * aviso es una preferencia suelta.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REMINDER_ALARM -> runWithWatchdog(context) { deliverReminder(it) }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> runWithWatchdog(context) { rescheduleOnly(it) }
            else -> Unit
        }
    }

    /** Alarma diaria: decide si toca avisar, programa el de mañana y notifica. */
    private fun deliverReminder(context: Context) {
        val preferences = SharedPreferencesAppPreferences(context)
        val scheduler = ReminderScheduler(context, preferences)
        val settings = scheduler.settings()
        if (!settings.enabled) {
            scheduler.cancel()
            return
        }
        val scope = preferences.reminderScopeOrNull()
        if (scope == null) {
            scheduler.cancel()
            return
        }

        val now = scheduler.now()
        val today = now.atZone(scheduler.zone()).toLocalDate()
        val scheduledToday = scheduler.instantOn(today, settings.minuteOfDay)
        // Sin hora válida o antes de la hora de hoy: la alarma es la de ayer.
        if (scheduledToday == null || now.isBefore(scheduledToday)) {
            scheduler.schedule()
            return
        }
        if (!settings.shouldNotify(today) || ReminderSchedule.isTooLate(scheduledToday, now)) {
            scheduler.schedule()
            return
        }

        // Primero el de mañana; después el aviso de hoy.
        scheduler.schedule()

        if (!ReminderNotifier.notificationsAllowed(context)) return
        val plan = MealPlanCache(KeystoreBlobStore(context)).load(scope)
        val message = when (plan) {
            is OfflineRead.Hit -> ReminderContent.of(plan.value, today)
            else -> null
        } ?: return

        if (ReminderNotifier.notify(context, message)) {
            preferences.reminderLastNotifiedDate = ReminderSettings.dateText(today)
        }
    }

    /** Reinicio, actualización o cambio de hora: solo se pone la próxima alarma. */
    private fun rescheduleOnly(context: Context) {
        val preferences = SharedPreferencesAppPreferences(context)
        ReminderScheduler(context, preferences).schedule()
    }

    /**
     * Ejecuta [block] fuera del hilo principal y termina el receptor pase lo que
     * pase. El vigilante ([WATCHDOG_MILLIS]) acota el trabajo para no agotar el
     * tiempo que da el sistema al receptor.
     */
    private fun runWithWatchdog(context: Context, block: (Context) -> Unit) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()

        lateinit var watchdog: Runnable
        val finish = Runnable {
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacks(watchdog)
                try {
                    pendingResult.finish()
                } catch (ignored: RuntimeException) {
                    // El sistema ya lo había dado por terminado.
                }
                executor.shutdown()
            }
        }
        watchdog = Runnable { finish.run() }
        handler.postDelayed(watchdog, WATCHDOG_MILLIS)

        executor.execute {
            try {
                block(appContext)
            } catch (ignored: Exception) {
                // Sin red y sin datos que registrar: no hay nada útil que contar.
            } finally {
                finish.run()
            }
        }
    }

    companion object {

        /**
         * Acción propia de la alarma. **No** va en el `intent-filter` del
         * manifiesto: viaja en el `Intent` explícito del `PendingIntent`, así que
         * no es una puerta que se pueda abrir desde fuera.
         */
        const val ACTION_REMINDER_ALARM: String =
            "io.github.garfieldrockero.gastronomicon.action.RECORDATORIO_PLANIFICADOR"

        /**
         * Tope del trabajo del receptor. El sistema da del orden de 10 s; se deja
         * margen para descifrar con el Keystore frío y terminar con holgura.
         */
        private const val WATCHDOG_MILLIS = 8_000L
    }
}
