package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem

/** Qué está haciendo la lista ahora mismo. Las escrituras no cambian la fase. */
enum class ShoppingPhase {
    Idle,

    /** Primera carga de una lista: todavía no hay nada que enseñar. */
    LoadingFirst,

    /** Recarga con la lista anterior aún a la vista. */
    Refreshing,
}

/**
 * Motivo de un fallo de la compra, ya tipado para que la interfaz elija el
 * texto. [InvalidData] lleva el detalle de un 422 ya saneado y acotado por
 * [io.github.garfieldrockero.gastronomicon.core.client.ApiResult].
 */
sealed interface ShoppingProblem {

    /** 401 o 403: el token no vale o no tiene permiso. */
    data object Unauthorized : ShoppingProblem

    /** 404: la lista o el elemento ya no existe. */
    data object NotFound : ShoppingProblem

    /** 422: el servidor rechazó los datos; [details] puede venir vacío. */
    data class InvalidData(val details: List<ValidationDetail>) : ShoppingProblem {
        // Sin los mensajes: son texto del servidor y un problema puede acabar en un log.
        override fun toString(): String = "ShoppingProblem.InvalidData(details=${details.size})"
    }

    /** Otro 4xx (400, 409, 413…). */
    data class Rejected(val statusCode: Int) : ShoppingProblem

    /** Rechazado en la app antes de enviar nada. */
    data class InputRejected(val reason: ShoppingInputProblem) : ShoppingProblem

    /** Red, TLS, 5xx, respuesta inválida o demasiado grande. */
    data class Failed(val problem: ConnectionProblem) : ShoppingProblem
}

/**
 * Estado de una lista de la compra. Es inmutable: cada paso devuelve otro
 * estado y la interfaz solo pinta [view] o los contadores.
 *
 * [busyItemIds] contiene los elementos con una escritura en curso (marcar,
 * editar o borrar). Mientras un id esté ahí no se lanza otra escritura sobre
 * él, para que un doble toque no acabe en dos `PUT` que se pisen. Durante un
 * borrado de los marcados también contiene todo el lote ([clearingItemIds]).
 *
 * Los elementos ya vienen saneados del cliente; aquí solo se deduplican por id
 * y se acotan.
 */
data class ShoppingListState(
    val listId: String? = null,
    val listName: String = "",
    val items: List<ShoppingItem> = emptyList(),
    val phase: ShoppingPhase = ShoppingPhase.Idle,
    /** Motivo del último fallo; se borra al empezar otra operación. */
    val problem: ShoppingProblem? = null,
    /** Si ya ha terminado bien alguna carga. */
    val loadedOnce: Boolean = false,
    val busyItemIds: Set<String> = emptySet(),
    /** Hay un `POST` en curso: un elemento nuevo o los ingredientes de recetas. */
    val adding: Boolean = false,
    /** Hay un borrado de los marcados en curso. */
    val clearingChecked: Boolean = false,
    /**
     * Ids del lote que se está borrando con «borrar marcados»: es exactamente lo
     * que la interfaz pudo confirmar, aunque alguien marque más mientras tanto.
     */
    val clearingItemIds: Set<String> = emptySet(),
) {
    /** Vista lista para pintar: agrupada, ordenada y con el texto compuesto. */
    val view: ShoppingListView by lazy { ShoppingListView.from(listName, items) }

    val pendingCount: Int get() = items.count { !it.checked }

    val checkedCount: Int get() = items.count { it.checked }

    /** Cuántos elementos forman el lote de «borrar marcados» en curso. */
    val clearingCount: Int get() = clearingItemIds.size

    val isLoading: Boolean get() = phase != ShoppingPhase.Idle

    /** Hay alguna escritura en curso: la interfaz bloquea los botones. */
    val isBusy: Boolean get() = adding || clearingChecked || busyItemIds.isNotEmpty()

    /** Cargada sin fallos y sin ningún elemento. */
    val isEmpty: Boolean get() = loadedOnce && phase == ShoppingPhase.Idle && problem == null && items.isEmpty()

    /** Sustituye los elementos deduplicando por id y aplicando el tope. */
    fun withItems(newItems: List<ShoppingItem>): ShoppingListState {
        val seen = HashSet<String>(newItems.size)
        val unique = ArrayList<ShoppingItem>(newItems.size)
        for (item in newItems) {
            if (unique.size >= MAX_ITEMS) break
            if (seen.add(item.id)) unique += item
        }
        return copy(items = unique)
    }

    /** Reemplaza un elemento por su id, o lo añade si no estaba (nunca lo duplica). */
    fun withItem(item: ShoppingItem): ShoppingListState =
        if (items.any { it.id == item.id }) {
            copy(items = items.map { if (it.id == item.id) item else it })
        } else if (items.size >= MAX_ITEMS) {
            this
        } else {
            copy(items = items + item)
        }

    fun withoutItem(itemId: String): ShoppingListState =
        copy(items = items.filterNot { it.id == itemId })

    // Sin el contenido: el estado puede acabar en un log y los textos son del servidor.
    override fun toString(): String =
        "ShoppingListState(items=${items.size}, phase=$phase, loadedOnce=$loadedOnce, " +
            "busy=${busyItemIds.size}, clearing=${clearingItemIds.size}, " +
            "problem=${problem?.javaClass?.simpleName})"

    companion object {
        /** Tope de elementos en memoria; una compra real no se acerca. */
        const val MAX_ITEMS = 2_000
    }
}
