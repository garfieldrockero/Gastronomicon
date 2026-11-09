package io.github.garfieldrockero.gastronomicon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineRead
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetMarkResult
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetStatus
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receptor del widget de la lista de la compra.
 *
 * Solo acepta cuatro entradas, todas explícitas y propias:
 * - `APPWIDGET_UPDATE` y el cambio de tamaño: el sistema. **Solo leen la caché**
 *   y repintan; nunca abren red ni descifran el token.
 * - [ACTION_REFRESH_CACHE]: la propia app (al abrirse, al guardar la compra y al
 *   cerrar sesión). Solo lee la caché.
 * - [ACTION_REFRESH_NETWORK]: el botón «Actualizar». `GET` de la lista y repintar.
 *   Como el toque, se rechaza con el móvil bloqueado.
 * - [ACTION_TOGGLE]: el toque en una fila. `GET` fresco y `PUT` de `checked` con
 *   el marcador del núcleo, sin cola y sin reintentos.
 *
 * El receptor **no está exportado** (`exported="false"`): el sistema entrega
 * `APPWIDGET_UPDATE` de forma explícita y los `PendingIntent` se ejecutan con la
 * identidad de la app. Las acciones propias no van en el `intent-filter`, así
 * que no son una puerta que se pueda abrir desde fuera. Un `Intent` con otra
 * forma, otro componente o un id que no sea UUID se ignora.
 *
 * Todo el trabajo va en un hilo de fondo con `goAsync()` y un vigilante corto:
 * el widget no puede quedarse colgado de una lectura del Keystore o de una
 * petición lenta. Un plazo agotado no deja nada a medias: la caché solo se
 * escribe con una instantánea completa.
 */
class ShoppingWidgetProvider : AppWidgetProvider() {

    /** Alta del widget, arranque o alta de la app: leer la caché y pintar. */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        runAsync(context) { paintFromCache(it, appWidgetManager, appWidgetIds) }
    }

    /** Redimensionar: recalcular filas y pintar. Sin red. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        runAsync(context) { paintFromCache(it, appWidgetManager, intArrayOf(appWidgetId)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH_CACHE -> runAsync(context) { paintFromCache(it) }
            ACTION_REFRESH_NETWORK -> runAsync(context) { refreshFromServer(it) }
            ACTION_TOGGLE -> runAsync(context) { toggle(it, intent) }
            // APPWIDGET_UPDATE, el borrado y el alta del widget siguen su curso normal.
            else -> super.onReceive(context, intent)
        }
    }

    // ------------------------------------------------------------- pintar

    private fun paintFromCache(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(component(context))
        paintFromCache(context, manager, ids)
    }

    /**
     * Pinta [ids] con la copia guardada. Si no hay cuenta, no hay copia o el
     * almacén no responde, el widget enseña el estado vacío con su mensaje; el
     * toque abre la app y no se borra nada.
     */
    private fun paintFromCache(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val entry = ShoppingWidgetEntryPoint(context)
        val scope = entry.scope()
        if (scope == null) {
            ShoppingWidgetPainter.paint(context, manager, ids, null, ShoppingWidgetStatus.NoSession)
            return
        }
        when (val read = entry.load(scope)) {
            is OfflineRead.Hit ->
                ShoppingWidgetPainter.paint(context, manager, ids, read.value, ShoppingWidgetStatus.Hidden)
            OfflineRead.Miss ->
                ShoppingWidgetPainter.paint(context, manager, ids, null, ShoppingWidgetStatus.NoCache)
            is OfflineRead.Failed ->
                ShoppingWidgetPainter.paint(context, manager, ids, null, ShoppingWidgetStatus.StorageUnavailable)
        }
    }

    // ------------------------------------------------------------- actualizar

    /**
     * Botón «Actualizar»: `GET` de la lista abierta y, si va bien, se guarda la
     * copia y se pinta. Si falla, se queda lo anterior y solo se cambia la línea
     * de estado. Sin copia no se consulta nada: no se inventa qué lista mirar.
     *
     * Con el móvil bloqueado no se consulta (decisión D14), igual que un toque.
     */
    private fun refreshFromServer(context: Context) {
        val entry = ShoppingWidgetEntryPoint(context)
        val scope = entry.scope()
        if (scope == null) {
            ShoppingWidgetPainter.paintAll(context, null, ShoppingWidgetStatus.NoSession)
            return
        }
        val snapshot = when (val read = entry.load(scope)) {
            is OfflineRead.Hit -> read.value
            OfflineRead.Miss -> {
                ShoppingWidgetPainter.paintAll(context, null, ShoppingWidgetStatus.NoCache)
                return
            }
            is OfflineRead.Failed -> {
                ShoppingWidgetPainter.paintAll(context, null, ShoppingWidgetStatus.StorageUnavailable)
                return
            }
        }

        // Sin desbloquear no se pide nada ni se descifra el token.
        if (!entry.guard().canWrite()) {
            ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.Locked)
            return
        }

        val client = try {
            entry.newClient()
        } catch (error: NetworkError) {
            ShoppingWidgetPainter.paintAll(
                context,
                snapshot,
                ShoppingWidgetStatus.of(error.toConnectionProblem()),
            )
            return
        } catch (error: RuntimeException) {
            ShoppingWidgetPainter.paintAll(context, snapshot, statusOf(error))
            return
        }

        when (val result = client.getList(snapshot.listId)) {
            is ApiResult.Success -> {
                val fresh = ShoppingWidgetSnapshot.from(result.value)
                when {
                    fresh == null ->
                        ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.ServerNotResponding)
                    // La sesión cambió mientras llegaba la respuesta: no se guarda.
                    !sameAccount(entry, scope) -> paintFromCache(context)
                    else -> {
                        entry.save(scope, fresh)
                        ShoppingWidgetPainter.paintAll(context, fresh, ShoppingWidgetStatus.Hidden)
                    }
                }
            }
            is ApiResult.Unauthorized, is ApiResult.Forbidden ->
                ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.SessionExpired)
            is ApiResult.NotFound -> {
                // La lista se borró en Mealie: la copia ya no vale.
                if (sameAccount(entry, scope)) {
                    entry.clear(scope)
                    ShoppingWidgetPainter.paintAll(context, null, ShoppingWidgetStatus.ListGone)
                } else {
                    paintFromCache(context)
                }
            }
            is ApiResult.Failure ->
                ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.of(result.problem))
            is ApiResult.SavedButNotReloaded,
            is ApiResult.MaybeCreated,
            is ApiResult.Conflict,
            is ApiResult.Invalid,
            is ApiResult.Rejected,
            is ApiResult.DraftInvalid,
            is ApiResult.InputRejected ->
                ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.ServerNotResponding)
        }
    }

    // ------------------------------------------------------------- marcar

    /**
     * Toque en una fila. Se comprueba que se puede escribir y que la cuenta
     * sigue siendo la misma, se pinta «Guardando…» y se llama al marcador del
     * núcleo, que hace `GET` + `PUT` y decide la instantánea final. La fila no
     * se quita antes de tiempo: el estado del toque lo dice, y con el resultado
     * se repinta la copia buena.
     *
     * No hay estado optimista en disco ni cola: si el `PUT` falla, la fila sigue
     * donde estaba y se enseña un motivo fijo. [ShoppingWidgetMarkResult.Unconfirmed]
     * avisa de la duda y no se reintenta solo.
     */
    private fun toggle(context: Context, intent: Intent) {
        val itemId = ShoppingWidgetIntents.itemIdOf(
            intent.data,
            intent.getStringExtra(EXTRA_ITEM_ID),
        ) ?: return

        val entry = ShoppingWidgetEntryPoint(context)
        val scope = entry.scope()
        if (scope == null) {
            ShoppingWidgetPainter.paintAll(context, null, ShoppingWidgetStatus.NoSession)
            return
        }
        val snapshot = when (val read = entry.load(scope)) {
            is OfflineRead.Hit -> read.value
            OfflineRead.Miss -> {
                ShoppingWidgetPainter.paintAll(context, null, ShoppingWidgetStatus.NoCache)
                return
            }
            is OfflineRead.Failed -> {
                ShoppingWidgetPainter.paintAll(context, null, ShoppingWidgetStatus.StorageUnavailable)
                return
            }
        }

        // Un toque sobre una fila que ya no está en la copia (PendingIntent
        // viejo) no marca nada: se repinta lo que hay.
        if (snapshot.rows.none { it.itemId == itemId }) {
            ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.Hidden)
            return
        }

        // Móvil bloqueado: se dice y no se pinta «Guardando…». La comprobación va
        // antes de pintar para no anunciar un guardado que no va a ocurrir, y el
        // marcador la repite antes de su primera petición.
        if (!entry.guard().canWrite()) {
            ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.Locked)
            return
        }

        // La cuenta tiene que seguir siendo la misma: el marcador crea su cliente
        // con la sesión vigente y no puede escribir sobre la lista de otra cuenta.
        if (!sameAccount(entry, scope)) {
            paintFromCache(context)
            return
        }

        ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.Saving)

        // El marcador del núcleo reduce sus fallos a valores; si algo inesperado
        // saltara, se avisa de la duda y no se reintenta (opción segura).
        val result = try {
            ShoppingWidgetRuntime.marker(context).mark(snapshot.listId, itemId)
        } catch (ignored: Exception) {
            ShoppingWidgetPainter.paintAll(context, snapshot, ShoppingWidgetStatus.Unconfirmed)
            return
        }
        val status = ShoppingWidgetStatus.of(result)
        // Si la sesión se cerró mientras se marcaba, la copia ya está borrada: no
        // se vuelve a escribir con el ámbito de la cuenta anterior.
        if (!sameAccount(entry, scope)) {
            paintFromCache(context)
            return
        }
        val next = when (result) {
            is ShoppingWidgetMarkResult.Marked -> {
                entry.save(scope, result.snapshot)
                result.snapshot
            }
            is ShoppingWidgetMarkResult.ItemGone -> {
                entry.save(scope, result.snapshot)
                result.snapshot
            }
            ShoppingWidgetMarkResult.ListGone -> {
                entry.clear(scope)
                null
            }
            else -> snapshot
        }
        ShoppingWidgetPainter.paintAll(context, next, status)
    }

    // ------------------------------------------------------------- interno

    /**
     * ¿Sigue la misma cuenta? Se vuelve a preguntar justo antes de escribir: el
     * trabajo del receptor puede durar hasta ocho segundos y, si en medio se
     * cierra la sesión (o se cambia de cuenta), `clearAll` ya habrá borrado la
     * copia; guardarla con el ámbito viejo la resucitaría. Si cambió, no se
     * escribe nada y se repinta la caché de la cuenta que haya ahora.
     */
    private fun sameAccount(entry: ShoppingWidgetEntryPoint, scope: ServerScope): Boolean =
        entry.scope() == scope

    /** Un fallo al crear el cliente se cuenta sin texto técnico ni URL. */
    private fun statusOf(error: RuntimeException): ShoppingWidgetStatus = when (error) {
        is IllegalArgumentException -> ShoppingWidgetStatus.Settings
        is IllegalStateException -> ShoppingWidgetStatus.StorageUnavailable
        else -> ShoppingWidgetStatus.ServerNotResponding
    }

    private fun component(context: Context) = android.content.ComponentName(
        context,
        ShoppingWidgetProvider::class.java,
    )

    /**
     * Ejecuta [block] fuera del hilo principal y termina el receptor pase lo que
     * pase. El vigilante acota el trabajo para no agotar el tiempo que da el
     * sistema a una difusión (del orden de 10 s).
     *
     * Límite aceptado: el vigilante cierra la difusión, pero **no interrumpe el
     * hilo**. Una petición ya empezada sigue hasta que el transporte la corta
     * por su cuenta (10 s de conexión y 30 s de lectura) y el proceso puede
     * morir antes del repintado final. Es inocuo porque el `PUT` del marcado es
     * idempotente y el `GET` siguiente corrige lo que falte; por eso no se usa
     * `shutdownNow()`, que cortaría una escritura a mitad sin poder saber si
     * llegó a aplicarse.
     */
    private fun runAsync(context: Context, block: (Context) -> Unit) {
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
         * Acción propia: la app ha cambiado la copia (o la ha borrado) y hay
         * que repintar. No va en el `intent-filter` del manifiesto.
         */
        const val ACTION_REFRESH_CACHE: String =
            "io.github.garfieldrockero.gastronomicon.action.WIDGET_REFRESH_CACHE"

        /** Acción propia del botón «Actualizar»: sí consulta la red. */
        const val ACTION_REFRESH_NETWORK: String =
            "io.github.garfieldrockero.gastronomicon.action.WIDGET_REFRESH_NETWORK"

        /** Acción propia del toque en una fila. */
        const val ACTION_TOGGLE: String =
            "io.github.garfieldrockero.gastronomicon.action.WIDGET_TOGGLE"

        /** Único extra de un toque: el UUID del elemento, validado al recibir. */
        const val EXTRA_ITEM_ID: String =
            "io.github.garfieldrockero.gastronomicon.extra.WIDGET_ITEM_ID"

        /** Tope del trabajo del receptor; el sistema concede del orden de 10 s. */
        private const val WATCHDOG_MILLIS = 8_000L
    }
}
