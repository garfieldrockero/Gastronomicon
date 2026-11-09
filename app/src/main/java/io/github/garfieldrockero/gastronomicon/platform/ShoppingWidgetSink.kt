package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.offline.OfflineWrite
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.core.offline.ShoppingListCache
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListState
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetRow
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lo que la pantalla de la compra avisa cuando su estado cambia, para que el
 * widget tenga una copia cifrada de la lista abierta.
 *
 * Es una interfaz pequeña a propósito: el widget no entra en el ViewModel y la
 * pantalla no sabe nada de `RemoteViews` ni del almacén. [onState] llega con el
 * estado **vigente** del controlador (también mientras hay una operación en
 * vuelo); decidir si ese estado es final y bueno es cosa de
 * [ShoppingWidgetSnapshot.from], que devuelve `null` si no lo es.
 */
interface ShoppingWidgetSink {

    /** Estado de la lista abierta; se queda con el último. */
    fun onState(state: ShoppingListState)

    /** Sesión cambiada o borrada: se descarta lo pendiente y se repinta vacío. */
    fun onReset()
}

/**
 * Escribe la copia del widget desde la app, en un hilo de fondo y sin red.
 *
 * - **Un solo hilo y una sola escritura a la vez**: si llegan varios estados
 *   seguidos (el sondeo de la pantalla publica cada 60 ms), solo se escribe el
 *   último y los idénticos se saltan ([Fingerprint]). Así marcar cinco cosas no
 *   cifra cinco ficheros en paralelo.
 * - Se escribe con el ámbito **vigente** leído en el momento de escribir y se
 *   vuelve a comprobar justo antes de guardar: si la sesión cambia a la vez, no
 *   se guarda nada (falla cerrado, y no se resucita la copia que el cierre de
 *   sesión acaba de borrar).
 * - `onReset` sube una generación: lo que estuviera en cola de la sesión
 *   anterior no llega a escribirse. La caché ya la borra el cierre de sesión
 *   (`OfflineClearingSessionStore`); aquí solo se repinta vacío.
 * - Un fallo de escritura no se propaga: como mucho el widget enseña la copia
 *   anterior, y la siguiente operación lo vuelve a intentar.
 */
class ShoppingWidgetPublisher(
    private val cache: ShoppingListCache,
    private val scope: () -> ServerScope?,
    private val notifyWidget: () -> Unit,
) : ShoppingWidgetSink {

    private class Pending(val generation: Long, val state: ShoppingListState)

    private data class Fingerprint(
        val listId: String,
        val listName: String,
        val pendingCount: Int,
        val checkedCount: Int,
        val rows: List<ShoppingWidgetRow>,
    )

    private val generation = AtomicLong(0L)
    private val pending = AtomicReference<Pending?>(null)
    private val running = AtomicBoolean(false)
    private val last = AtomicReference<Fingerprint?>(null)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gastronomicon-widget").apply { isDaemon = true }
    }

    override fun onState(state: ShoppingListState) {
        pending.set(Pending(generation.get(), state))
        start()
    }

    override fun onReset() {
        // Lo que hubiera en cola es de la sesión anterior.
        generation.incrementAndGet()
        pending.set(null)
        last.set(null)
        notifyWidget()
    }

    // Sin el estado ni los textos: puede acabar en un log.
    override fun toString(): String = "ShoppingWidgetPublisher"

    private fun start() {
        if (running.compareAndSet(false, true)) executor.execute { drain() }
    }

    private fun drain() {
        try {
            while (true) {
                val item = pending.getAndSet(null) ?: break
                if (item.generation != generation.get()) continue
                publish(item.state)
            }
        } catch (ignored: RuntimeException) {
            // Un fallo del almacén no puede tumbar el hilo de la app.
        } finally {
            running.set(false)
            // Si llegó algo justo mientras se cerraba, se vuelve a lanzar.
            if (pending.get() != null) start()
        }
    }

    private fun publish(state: ShoppingListState) {
        val snapshot = ShoppingWidgetSnapshot.from(state) ?: return
        val account = scope() ?: return
        val fingerprint = Fingerprint(
            listId = snapshot.listId,
            listName = snapshot.listName,
            pendingCount = snapshot.pendingCount,
            checkedCount = snapshot.checkedCount,
            rows = snapshot.rows,
        )
        if (fingerprint == last.get()) return
        // Mientras este estado esperaba turno la persona pudo cerrar la sesión (o
        // cambiar de cuenta): escribir con el ámbito de antes dejaría una copia
        // viva después de `clearAll`. Se vuelve a preguntar justo antes de guardar.
        if (scope() != account) return
        when (cache.save(account, snapshot)) {
            OfflineWrite.Saved -> {
                last.set(fingerprint)
                notifyWidget()
            }
            // Demasiado grande: la copia anterior ya está borrada; que se vea el estado vacío.
            OfflineWrite.TooLarge -> {
                last.set(null)
                notifyWidget()
            }
            else -> Unit
        }
    }
}
