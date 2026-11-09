package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ValidationDetail
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import java.util.Locale

/** Qué está haciendo el selector de listas. */
enum class ShoppingListPickerPhase {
    Idle,

    /** Leyendo la página de listas. */
    Loading,

    /** Hay una creación de lista en curso (el `POST` sigue en vuelo). */
    Creating,
}

/**
 * Motivo de un fallo del selector, ya tipado para que la interfaz elija el
 * texto. [InvalidData] lleva el detalle de un 422 ya saneado y acotado por
 * `ApiResult`.
 */
sealed interface ShoppingListPickerProblem {

    /** 401 o 403: el token no vale o no tiene permiso. */
    data object Unauthorized : ShoppingListPickerProblem

    /** Otro 4xx (400, 404, 409…): el servidor rechazó la petición. */
    data class Rejected(val statusCode: Int) : ShoppingListPickerProblem

    /** 422: el servidor rechazó los datos; [details] puede venir vacío. */
    data class InvalidData(val details: List<ValidationDetail>) : ShoppingListPickerProblem {
        // Sin los mensajes: son texto del servidor y un problema puede acabar en un log.
        override fun toString(): String = "ShoppingListPickerProblem.InvalidData(details=${details.size})"
    }

    /** Rechazado en la app antes de enviar nada (nombre vacío o demasiado largo). */
    data class InputRejected(val reason: ShoppingInputProblem) : ShoppingListPickerProblem

    /**
     * El `POST` pudo llegar al servidor y no se sabe si la lista se creó. La
     * interfaz debe avisar y pedir que se mire la lista antes de repetir; el
     * controlador ya ha vuelto a leer la página para enseñar lo que haya.
     */
    data class MaybeCreated(val problem: ConnectionProblem) : ShoppingListPickerProblem

    /** Red, TLS, 5xx, respuesta inválida o demasiado grande. */
    data class Failed(val problem: ConnectionProblem) : ShoppingListPickerProblem
}

/**
 * Estado del selector de listas de la compra. Es inmutable: cada paso devuelve
 * otro estado y la interfaz solo pinta esto; ordenar, deduplicar y decidir qué
 * lista queda abierta se hace aquí, no en `app/`.
 *
 * [openListId] es la lista que la pantalla debe tener abierta. Se recuerda al
 * elegirla y al crear una nueva; si no hay ninguna recordada, la primera de la
 * página (ya ordenada por nombre) pasa a ser la abierta. El id se conserva
 * aunque la relectura no lo traiga (la lista puede estar en otra página): solo
 * se olvida si no hay ninguna lista.
 *
 * Las listas vienen saneadas del cliente; aquí solo se deduplican por id, se
 * ordenan igual que `ShoppingClient.listLists` y se acotan.
 */
data class ShoppingListPickerState(
    val lists: List<ShoppingListSummary> = emptyList(),
    /** Lista que la pantalla debe abrir; null si no hay ninguna. */
    val openListId: String? = null,
    val phase: ShoppingListPickerPhase = ShoppingListPickerPhase.Idle,
    /** Motivo del último fallo; se borra al empezar otra operación. */
    val problem: ShoppingListPickerProblem? = null,
    /** Si ya ha terminado bien alguna lectura de la página de listas. */
    val loadedOnce: Boolean = false,
) {
    /** La lista recordada, si está en la página que se conoce. */
    val openList: ShoppingListSummary? get() = lists.firstOrNull { it.id == openListId }

    /** Hay una lista que abrir: la pantalla puede cargar sus elementos. */
    val hasOpenList: Boolean get() = openListId != null

    val isLoading: Boolean get() = phase == ShoppingListPickerPhase.Loading

    /** Hay una creación en curso: la interfaz bloquea el botón de crear. */
    val isCreating: Boolean get() = phase == ShoppingListPickerPhase.Creating

    val isBusy: Boolean get() = phase != ShoppingListPickerPhase.Idle

    /** Leída la página, sin fallos y sin ninguna lista. */
    val isEmpty: Boolean get() = loadedOnce && phase == ShoppingListPickerPhase.Idle &&
        problem == null && lists.isEmpty()

    /**
     * Sustituye la página. Si no hay lista recordada, se abre la primera; si la
     * página viene vacía, se olvida la que hubiera. Una lista recordada que no
     * esté en la página se conserva: puede estar en otra.
     */
    internal fun withLists(newLists: List<ShoppingListSummary>): ShoppingListPickerState {
        val clean = sorted(newLists)
        val open = when {
            clean.isEmpty() -> null
            openListId == null -> clean.first().id
            else -> openListId
        }
        return copy(lists = clean, openListId = open)
    }

    /** Añade o sustituye una lista por su id, sin duplicarla, y la deja ordenada. */
    internal fun withList(list: ShoppingListSummary): ShoppingListPickerState =
        copy(lists = sorted(lists.filterNot { it.id == list.id } + list))

    // Sin nombres ni ids: el estado puede acabar en un log y los nombres son del servidor.
    override fun toString(): String =
        "ShoppingListPickerState(lists=${lists.size}, hasOpen=${openListId != null}, phase=$phase, " +
            "loadedOnce=$loadedOnce, problem=${problem?.javaClass?.simpleName})"

    companion object {
        /**
         * Tope de listas en memoria. Una página trae como mucho
         * `ShoppingClient.MAX_LISTS_PER_PAGE`; el margen deja sitio a la lista
         * recién creada sin que la ordenación la tire.
         */
        const val MAX_LISTS = 200

        /** Mismo orden que `ShoppingClient.listLists`: nombre sin mayúsculas, nombre e id. */
        internal fun sorted(lists: List<ShoppingListSummary>): List<ShoppingListSummary> {
            val seen = HashSet<String>(lists.size)
            val unique = ArrayList<ShoppingListSummary>(lists.size)
            for (list in lists) {
                if (unique.size >= MAX_LISTS) break
                // Un id que no se puede poner en una ruta no se puede abrir: fuera.
                // El cliente ya lo valida; esto cubre una fuente mal comportada.
                val id = ShoppingIds.valid(list.id) ?: continue
                if (seen.add(id)) unique += if (id == list.id) list else list.copy(id = id)
            }
            unique.sortWith(
                compareBy<ShoppingListSummary>(
                    { it.name.lowercase(Locale.ROOT) },
                    { it.name },
                    { it.id },
                ),
            )
            return unique
        }
    }
}
