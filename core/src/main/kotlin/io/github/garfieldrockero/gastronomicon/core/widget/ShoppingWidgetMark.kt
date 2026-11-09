package io.github.garfieldrockero.gastronomicon.core.widget

import io.github.garfieldrockero.gastronomicon.core.client.ApiErrors
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingIds
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingList
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSource
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListView
import java.util.concurrent.CancellationException

/**
 * Contrato de la comprobación que no vive en `core/`: si se puede escribir
 * ahora. `app/` lo implementa con `KeyguardManager` (móvil bloqueado) y con lo
 * que haga falta; el núcleo solo lo pregunta **antes** de tocar la red.
 */
fun interface ShoppingWidgetWriteGuard {

    /** `false` deja el toque en [ShoppingWidgetMarkResult.Locked] sin ninguna petición. */
    fun canWrite(): Boolean
}

/**
 * Desenlace de un toque en una fila del widget.
 *
 * Cada valor lleva lo justo para que `app/` repinte y elija el texto fijo
 * ([ShoppingWidgetStatus.of]); ninguno lleva token, URL, cuerpo del servidor ni
 * `rawJson`. Los que traen [ShoppingWidgetSnapshot] son datos ya saneados y
 * acotados: se pueden guardar en la caché tal cual.
 */
sealed interface ShoppingWidgetMarkResult {

    /** El id de la lista o el del elemento no es un UUID: no se ha tocado nada. */
    data object Invalid : ShoppingWidgetMarkResult

    /** El móvil está bloqueado: lo decide platform a través de [ShoppingWidgetWriteGuard]. */
    data object Locked : ShoppingWidgetMarkResult

    /**
     * Ya había un toque de ese mismo elemento en curso en este proceso. No se
     * lanza una segunda petición: el primer toque decide.
     */
    data object InFlight : ShoppingWidgetMarkResult

    /** La lista ya no existe en Mealie (404 en el `GET`): hay que borrar la copia. */
    data object ListGone : ShoppingWidgetMarkResult

    /** 401/403 en el `GET` o en el `PUT`: la copia se conserva y se pide Ajustes. */
    data object Unauthorized : ShoppingWidgetMarkResult

    /**
     * Se ha marcado, o ya estaba marcado antes de este toque. [snapshot] es la
     * lista fresca con el elemento ya fuera de las pendientes; [alreadyChecked]
     * dice si hizo falta mandar el `PUT` (si era `true`, no se mandó).
     */
    data class Marked(
        val snapshot: ShoppingWidgetSnapshot,
        val alreadyChecked: Boolean,
        val itemText: String,
    ) : ShoppingWidgetMarkResult {
        // Sin el texto del producto: es contenido del servidor.
        override fun toString(): String =
            "Marked(pending=${snapshot.pendingCount}, alreadyChecked=$alreadyChecked, itemChars=${itemText.length})"
    }

    /** El elemento se borró en otro sitio: [snapshot] es la lista fresca sin él. */
    data class ItemGone(val snapshot: ShoppingWidgetSnapshot) : ShoppingWidgetMarkResult

    /**
     * No se pudo leer la lista fresca. La fila vuelve a su sitio: no se ha
     * escrito nada.
     */
    data class ReadFailed(val problem: ConnectionProblem) : ShoppingWidgetMarkResult

    /**
     * El `PUT` no se aplicó con seguridad (red o TLS antes de enviar, HTTP
     * local fuera de casa, 4xx local…). La fila vuelve a su sitio y no se
     * reintenta sola.
     */
    data class NotApplied(val problem: ConnectionProblem) : ShoppingWidgetMarkResult

    /**
     * El `PUT` pudo aplicarse y no se sabe (5xx, respuesta inválida…). La fila
     * vuelve a su sitio, se enseña un aviso de duda y **no se reintenta sola**:
     * repetir a ciegas podría dejar la vista mintiendo.
     */
    data class Unconfirmed(val problem: ConnectionProblem) : ShoppingWidgetMarkResult

    /** El servidor rechazó el `PUT` de forma definitiva (4xx sin detalle útil). */
    data object ServerRejected : ShoppingWidgetMarkResult
}

/** Lectura fresca, ya reducida a lo que el toque necesita. */
internal sealed interface WidgetRead {
    data class Loaded(val list: ShoppingList) : WidgetRead
    data object Gone : WidgetRead
    data object Unauthorized : WidgetRead
    data class Failed(val problem: ConnectionProblem) : WidgetRead
}

/**
 * Traduce cualquier [ApiResult] de un `GET`, sin `else`: si se añade un
 * desenlace nuevo, esto deja de compilar hasta decidir qué significa.
 */
internal fun readOutcome(result: ApiResult<ShoppingList>): WidgetRead = when (result) {
    is ApiResult.Success -> WidgetRead.Loaded(result.value)
    is ApiResult.Unauthorized, is ApiResult.Forbidden -> WidgetRead.Unauthorized
    is ApiResult.NotFound -> WidgetRead.Gone
    is ApiResult.Conflict,
    is ApiResult.Invalid,
    is ApiResult.Rejected,
    is ApiResult.DraftInvalid,
    is ApiResult.InputRejected -> WidgetRead.Failed(ConnectionProblem.Unknown)
    is ApiResult.SavedButNotReloaded -> WidgetRead.Failed(result.problem)
    is ApiResult.MaybeCreated -> WidgetRead.Failed(result.problem)
    is ApiResult.Failure -> WidgetRead.Failed(result.problem)
}

/** Escritura, ya reducida a lo que el toque necesita. */
internal sealed interface WidgetWrite {
    data object Applied : WidgetWrite
    data object Gone : WidgetWrite
    data object Unauthorized : WidgetWrite
    data object Rejected : WidgetWrite
    data class NotApplied(val problem: ConnectionProblem) : WidgetWrite
    data class Unconfirmed(val problem: ConnectionProblem) : WidgetWrite
}

/**
 * Traduce cualquier [ApiResult] de un `PUT` de marcado, sin `else`.
 *
 * La diferencia entre [WidgetWrite.NotApplied] y [WidgetWrite.Unconfirmed] es
 * la única decisión de seguridad de esta operación: un 5xx o una respuesta
 * ilegible pueden haber escrito, y entonces la fila no puede volver a pintarse
 * como si nada hubiera pasado. «No se sabe» se dice como tal y no se reintenta.
 */
internal fun writeOutcome(result: ApiResult<Unit>): WidgetWrite = when (result) {
    is ApiResult.Success -> WidgetWrite.Applied
    is ApiResult.Unauthorized, is ApiResult.Forbidden -> WidgetWrite.Unauthorized
    is ApiResult.NotFound -> WidgetWrite.Gone
    is ApiResult.Conflict,
    is ApiResult.Invalid,
    is ApiResult.Rejected,
    is ApiResult.DraftInvalid,
    is ApiResult.InputRejected -> WidgetWrite.Rejected
    is ApiResult.SavedButNotReloaded -> WidgetWrite.Unconfirmed(result.problem)
    is ApiResult.MaybeCreated -> WidgetWrite.Unconfirmed(result.problem)
    is ApiResult.Failure -> failureAfterPut(result.problem)
}

/**
 * ¿Un [ConnectionProblem] de un `PUT` prueba que no se escribió? Se sigue el
 * mismo criterio que `ShoppingClient.happenedBeforeSend`: lo que se sabe que
 * falló antes de enviar (TLS, DNS, HTTP local bloqueado, dirección inválida)
 * no dejó nada escrito; un 5xx o una respuesta rara, sí puede. Un corte de
 * conexión se cuenta como antes de enviar, como en el resto de la app.
 */
internal fun failureAfterPut(problem: ConnectionProblem): WidgetWrite = when (problem) {
    ConnectionProblem.ServerError,
    ConnectionProblem.Unknown -> WidgetWrite.Unconfirmed(problem)
    ConnectionProblem.Unauthorized,
    ConnectionProblem.InvalidToken -> WidgetWrite.Unauthorized
    ConnectionProblem.NoNetwork,
    ConnectionProblem.ServerNotResponding,
    ConnectionProblem.InvalidAddress,
    ConnectionProblem.SecureConnectionFailed,
    ConnectionProblem.LocalHttpNeedsHomeNetwork,
    ConnectionProblem.NotLocalAddress -> WidgetWrite.NotApplied(problem)
}

/** Qué hacer con la lista fresca antes de escribir. */
internal sealed interface WidgetPlan {
    data class Send(val item: ShoppingItem) : WidgetPlan
    data object AlreadyChecked : WidgetPlan
    data object ItemGone : WidgetPlan
}

/**
 * Plan de marcado: con la lista fresca en la mano, el `PUT` solo sale si el
 * elemento sigue ahí y está pendiente. Si ya está marcado no se manda nada (un
 * segundo toque o un reintento no duplica ni deshace), y si ya no está se
 * responde sin escribir.
 */
internal fun planFor(list: ShoppingList, itemId: String): WidgetPlan {
    val item = list.items.firstOrNull { it.id == itemId } ?: return WidgetPlan.ItemGone
    return if (item.checked) WidgetPlan.AlreadyChecked else WidgetPlan.Send(item)
}

/**
 * Marca un elemento desde el widget con `GET` fresco + `PUT` de `checked`
 * (decisión D2 del diseño).
 *
 * Por qué así:
 * - El `PUT` parte del **JSON recién leído**, no de una copia en disco: si la
 *   nota cambió en la web de Mealie, no se pisa con la vieja.
 * - Si el `GET` ve el elemento ya marcado, no hay `PUT`. Por eso un toque
 *   repetido (o un `PendingIntent` que el launcher reenvía) no duplica nada:
 *   el destino es un booleano, no una suma.
 * - Un id «en vuelo» en este proceso ignora el segundo toque
 *   ([ShoppingWidgetMarkResult.InFlight]); dos procesos a la vez pueden hacer
 *   dos `PUT` con el mismo valor, que es idempotente.
 * - Sin cola y sin reintentos: un fallo revierte la fila (lo hace `app/` con el
 *   resultado) y se queda ahí. [ShoppingWidgetMarkResult.Unconfirmed] avisa de
 *   que el `PUT` pudo aplicarse.
 *
 * Los métodos son bloqueantes, como el resto del núcleo: `app/` los llama desde
 * su hilo de fondo. El cerrojo solo protege el conjunto de ids en vuelo; dos
 * elementos distintos pueden ir en paralelo.
 */
class ShoppingWidgetMarker(
    private val source: ShoppingListSource,
    private val guard: ShoppingWidgetWriteGuard = ShoppingWidgetWriteGuard { true },
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()
    private val inFlight = HashSet<String>()

    /**
     * Marca [itemId] de la lista [listId]. [listId] es el de la instantánea que
     * estaba pintada: el toque no lleva un id de lista del servidor.
     */
    fun mark(listId: String, itemId: String): ShoppingWidgetMarkResult {
        val checkedList = ShoppingIds.valid(listId) ?: return ShoppingWidgetMarkResult.Invalid
        val checkedItem = ShoppingIds.valid(itemId) ?: return ShoppingWidgetMarkResult.Invalid
        // La comprobación del móvil bloqueado se hace antes de cualquier
        // petición: ni se lee ni se escribe con la pantalla bloqueada.
        if (!guard.canWrite()) return ShoppingWidgetMarkResult.Locked
        if (!begin(checkedItem)) return ShoppingWidgetMarkResult.InFlight
        try {
            val fresh = when (val read = readOutcome(call { source.getList(checkedList) })) {
                is WidgetRead.Loaded -> read.list
                WidgetRead.Gone -> return ShoppingWidgetMarkResult.ListGone
                WidgetRead.Unauthorized -> return ShoppingWidgetMarkResult.Unauthorized
                is WidgetRead.Failed -> return ShoppingWidgetMarkResult.ReadFailed(read.problem)
            }
            // El GET tiene que devolver la lista que se ha pedido. Si el
            // servidor contesta otra (o un id que no vale), no se escribe sobre
            // ella: no se puede marcar un elemento de otra lista con un id
            // viejo.
            if (ShoppingIds.valid(fresh.id) != checkedList) return ShoppingWidgetMarkResult.Invalid
            return when (val plan = planFor(fresh, checkedItem)) {
                is WidgetPlan.Send -> send(fresh, plan.item)
                WidgetPlan.AlreadyChecked -> marked(fresh, checkedItem, alreadyChecked = true)
                WidgetPlan.ItemGone -> itemGone(fresh, checkedItem)
            }
        } finally {
            finish(checkedItem)
        }
    }

    private fun send(fresh: ShoppingList, item: ShoppingItem): ShoppingWidgetMarkResult =
        when (val write = writeOutcome(call { source.setChecked(item, true) })) {
            WidgetWrite.Applied -> marked(fresh, item.id, alreadyChecked = false)
            WidgetWrite.Gone -> itemGone(fresh, item.id)
            WidgetWrite.Unauthorized -> ShoppingWidgetMarkResult.Unauthorized
            WidgetWrite.Rejected -> ShoppingWidgetMarkResult.ServerRejected
            is WidgetWrite.NotApplied -> ShoppingWidgetMarkResult.NotApplied(write.problem)
            is WidgetWrite.Unconfirmed -> ShoppingWidgetMarkResult.Unconfirmed(write.problem)
        }

    /** Instantánea con el elemento marcado. */
    private fun marked(fresh: ShoppingList, itemId: String, alreadyChecked: Boolean): ShoppingWidgetMarkResult {
        val snapshot = snapshotWith(fresh, itemId, checked = true) ?: return ShoppingWidgetMarkResult.Invalid
        return ShoppingWidgetMarkResult.Marked(
            snapshot = snapshot,
            alreadyChecked = alreadyChecked,
            itemText = textOf(fresh, itemId),
        )
    }

    /** Instantánea sin el elemento, que ya no está en el servidor. */
    private fun itemGone(fresh: ShoppingList, itemId: String): ShoppingWidgetMarkResult {
        val snapshot = snapshotWith(fresh, itemId, checked = null) ?: return ShoppingWidgetMarkResult.Invalid
        return ShoppingWidgetMarkResult.ItemGone(snapshot)
    }

    /** [checked] `null` significa «quitar el elemento»; si no, cambia su marcado. */
    private fun snapshotWith(fresh: ShoppingList, itemId: String, checked: Boolean?): ShoppingWidgetSnapshot? {
        val items = if (checked == null) {
            fresh.items.filterNot { it.id == itemId }
        } else {
            fresh.items.map { if (it.id == itemId) it.copy(checked = checked) else it }
        }
        return ShoppingWidgetSnapshot.from(ShoppingList(fresh.id, fresh.name, items), now())
    }

    /** Texto visible del elemento en la lista fresca, para el aviso «En el carro». */
    private fun textOf(fresh: ShoppingList, itemId: String): String {
        val view = ShoppingListView.from(fresh.name, fresh.items)
        for (section in view.sections) {
            for (line in section.lines) {
                if (line.itemId == itemId) {
                    return PlainTextSanitizer.sanitize(line.text, ShoppingWidgetSnapshot.MAX_ROW_TEXT)
                }
            }
        }
        return ""
    }

    private fun begin(itemId: String): Boolean = synchronized(lock) { inFlight.add(itemId) }

    private fun finish(itemId: String) {
        synchronized(lock) { inFlight.remove(itemId) }
    }

    /**
     * Ejecuta una llamada a la fuente y reduce un fallo de red a un [ApiResult]
     * tipado, igual que `ShoppingListController`. Una cancelación no se traga
     * (el `finally` ya quitó el id en vuelo) y un `Error` tampoco.
     */
    private inline fun <T> call(block: () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkError) {
            ApiErrors.map(error)
        } catch (error: RuntimeException) {
            ApiResult.Failure(ConnectionProblem.Unknown)
        }
}
