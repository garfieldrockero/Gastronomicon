package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiErrors
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.ServingScaler

/**
 * Lleva una lista de la compra: carga, marcar/desmarcar, añadir, editar la
 * nota y la cantidad, y borrar. La interfaz solo llama a estos métodos y pinta
 * [state].
 *
 * Los métodos que hablan con la red son **bloqueantes**, como el resto del
 * núcleo: `app/` los lanza desde un hilo de fondo. [state] es seguro de leer
 * desde cualquier hilo.
 *
 * Marcado optimista: [toggle] cambia el elemento en el estado antes de enviar
 * el `PUT` y lo revierte si el servidor falla. Mientras ese elemento esté en
 * [ShoppingListState.busyItemIds] no se acepta otra escritura sobre él, así que
 * dos toques rápidos no lanzan dos `PUT` que se pisen.
 *
 * Las escrituras solo empiezan con la lista en [ShoppingPhase.Idle] y fuera de
 * un borrado de marcados: así el resultado de una escritura nunca se mezcla
 * con una recarga en vuelo y la fase no se queda en «cargando». Además, al
 * aplicar el resultado se comprueba que sigue abierta la misma lista
 * ([ShoppingListState.listId]); si se abrió otra, el resultado se descarta.
 *
 * Añadir no devuelve el elemento: tras un `POST` correcto (o dudoso, ver
 * [ApiResult.MaybeCreated]) se vuelve a leer la lista, para no inventar ids ni
 * arriesgar duplicados si el servidor contesta algo que no se entiende.
 *
 * Cualquier [NetworkError] de la fuente se traduce con `ApiErrors.map`, igual
 * que en `ShoppingClient` (401 y 403 → [ShoppingProblem.Unauthorized], 404 →
 * [ShoppingProblem.NotFound], 422 → [ShoppingProblem.InvalidData], el resto →
 * [ShoppingProblem.Failed]), y cualquier otra excepción inesperada se reduce a
 * [ShoppingProblem.Failed] con [ConnectionProblem.Unknown]: el estado nunca se
 * queda «cargando».
 */
class ShoppingListController(private val source: ShoppingListSource) {

    private val lock = Any()

    // Cuenta las cargas lanzadas; solo la última tiene derecho a escribir el estado.
    // Las escrituras no la tocan: no invalidan una recarga en vuelo.
    private var generation = 0L

    // Carga que puso la fase actual. Si su resultado se descarta y la fase sigue
    // siendo suya, se devuelve a Idle para que la interfaz no espere para siempre.
    private var phaseTicket = 0L

    @Volatile
    var state: ShoppingListState = ShoppingListState()
        private set

    /**
     * Abre [listId] y la carga. Si el id no es un UUID no se envía nada y el
     * estado queda en [ShoppingProblem.NotFound].
     */
    fun open(listId: String): ShoppingListState {
        val checked = ShoppingIds.valid(listId)
        if (checked == null) {
            synchronized(lock) {
                generation++
                state = ShoppingListState(problem = ShoppingProblem.NotFound)
            }
            return state
        }
        synchronized(lock) {
            generation++
            phaseTicket = generation
            state = ShoppingListState(listId = checked, phase = ShoppingPhase.LoadingFirst)
        }
        return load(checked, firstLoad = true)
    }

    /** Vuelve a leer la lista abierta conservando lo que ya había a la vista. */
    fun refresh(): ShoppingListState {
        val current = state
        val listId = current.listId ?: return current
        if (current.isLoading) return current
        return load(listId, firstLoad = !current.loadedOnce)
    }

    /** Repite la carga que falló; sin fallo o con una carga en curso no hace nada. */
    fun retry(): ShoppingListState {
        val current = state
        val listId = current.listId ?: return current
        if (current.phase != ShoppingPhase.Idle || current.problem == null) return current
        return load(listId, firstLoad = !current.loadedOnce)
    }

    /** Vuelve a la lista de listas: descarta lo cargado y cualquier carga en curso. */
    fun close(): ShoppingListState =
        synchronized(lock) {
            generation++
            state = ShoppingListState()
            state
        }

    /**
     * Marca o desmarca el elemento [itemId]. El cambio se ve al instante y se
     * revierte si la escritura falla. Un id que no está en la lista, una carga
     * en marcha, un borrado de marcados o una escritura ya en curso sobre él no
     * hacen nada.
     */
    fun toggle(itemId: String): ShoppingListState {
        val id = ShoppingIds.valid(itemId) ?: return state
        var target: ShoppingItem? = null
        var newChecked = false
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.phase != ShoppingPhase.Idle || current.clearingChecked) return current
            val item = current.items.firstOrNull { it.id == id } ?: return current
            if (id in current.busyItemIds) return current
            target = item
            newChecked = !item.checked
            listId = current.listId
            state = current
                .withItem(item.copy(checked = newChecked))
                .copy(busyItemIds = current.busyItemIds + id, problem = null)
        }
        val current = target ?: return state
        val expectedList = listId
        val result = call { source.setChecked(current, newChecked) }
        return synchronized(lock) {
            if (state.listId != expectedList) return@synchronized state
            val updated = state.copy(busyItemIds = state.busyItemIds - id)
            state = when (result) {
                // Se vuelve a aplicar el valor optimista: si una recarga se coló
                // entre el toque y la respuesta, el estado traería el valor viejo.
                // Solo se toca el marcado: una nota más nueva de una recarga se respeta.
                is ApiResult.Success -> {
                    val item = updated.items.firstOrNull { it.id == id }
                    if (item == null) updated else updated.withItem(item.copy(checked = newChecked))
                }
                else -> updated.copy(
                    items = updated.items.map {
                        // Solo se revierte si el elemento sigue con el valor optimista:
                        // si ya no está (se borró), no hay nada que deshacer. Se
                        // deshace solo el marcado sobre la copia vigente: una recarga
                        // pudo traer una nota o una cantidad nuevas que no se pisan.
                        if (it.id == id && it.checked == newChecked) it.copy(checked = !newChecked) else it
                    },
                    problem = problemOf(result),
                )
            }
            state
        }
    }

    /**
     * Añade una nota de texto libre. El texto se valida aquí (vacío o
     * demasiado largo no sale de la app). Tras un `POST` correcto relee la
     * lista; si el resultado es [ApiResult.MaybeCreated] también relee, y deja
     * el aviso para que nadie repita a ciegas.
     */
    fun add(text: String): ShoppingListState {
        val input = ShoppingText.item(text)
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.adding || current.clearingChecked || current.phase != ShoppingPhase.Idle) return current
            listId = current.listId ?: return current
            val inputProblem = input.problem
            if (inputProblem != null) {
                state = current.copy(problem = ShoppingProblem.InputRejected(inputProblem))
                return state
            }
            state = current.copy(adding = true, problem = null)
        }
        val openList = listId ?: return state
        val result = callCreate { source.addItem(openList, input.text) }
        synchronized(lock) {
            if (state.listId == openList) state = state.copy(adding = false)
        }
        return when (result) {
            is ApiResult.Success -> load(openList, firstLoad = false)
            is ApiResult.MaybeCreated -> {
                // La fase de «añadiendo» ya está cerrada antes de releer. El
                // aviso se pone sobre el estado vigente y no sobre la copia que
                // devolvió `load`: entre la relectura y este punto otra
                // operación pudo cambiarlo (abrir otra lista, un borrado) y
                // pisarlo con la copia vieja perdería ese cambio.
                load(openList, firstLoad = false)
                synchronized(lock) {
                    if (state.listId == openList) {
                        state = state.copy(problem = problemOf(result))
                    }
                    state
                }
            }
            else -> synchronized(lock) {
                if (state.listId == openList) state = state.copy(problem = problemOf(result))
                state
            }
        }
    }

    /**
     * Cambia la nota y la cantidad del elemento [itemId]. La cantidad 0 es
     * «sin cantidad». Si el texto se queda vacío y el elemento no tiene
     * alimento ni unidad, no se envía: la línea no tendría nada que enseñar.
     */
    fun editNote(itemId: String, note: String, quantity: Double): ShoppingListState {
        val id = ShoppingIds.valid(itemId) ?: return state
        val input = ShoppingText.item(note)
        var target: ShoppingItem? = null
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.phase != ShoppingPhase.Idle || current.clearingChecked) return current
            val item = current.items.firstOrNull { it.id == id } ?: return current
            if (id in current.busyItemIds) return current
            val inputProblem = input.problem
            if (inputProblem != null && inputProblem != ShoppingInputProblem.EmptyText) {
                state = current.copy(problem = ShoppingProblem.InputRejected(inputProblem))
                return state
            }
            if (!validQuantity(quantity)) {
                state = current.copy(problem = ShoppingProblem.InputRejected(ShoppingInputProblem.InvalidQuantity))
                return state
            }
            val visible = input.text.isNotEmpty() || item.foodSingular.isNotEmpty() ||
                item.unitSingular.isNotEmpty() || quantity > 0.0
            if (!visible) {
                state = current.copy(problem = ShoppingProblem.InputRejected(ShoppingInputProblem.EmptyText))
                return state
            }
            target = item
            listId = current.listId
            state = current.copy(busyItemIds = current.busyItemIds + id, problem = null)
        }
        val current = target ?: return state
        val expectedList = listId
        val clean = input.text
        val result = call { source.editItem(current, clean, quantity) }
        return synchronized(lock) {
            if (state.listId != expectedList) return@synchronized state
            val updated = state.copy(busyItemIds = state.busyItemIds - id)
            state = when (result) {
                is ApiResult.Success -> {
                    val item = updated.items.firstOrNull { it.id == id }
                    if (item == null) updated else updated.withItem(item.withSavedContent(clean, quantity))
                }
                else -> updated.copy(problem = problemOf(result))
            }
            state
        }
    }

    /**
     * Borra el elemento [itemId]. No es optimista: se quita del estado solo
     * cuando el servidor lo acepta o contesta 404 (ya no estaba).
     */
    fun delete(itemId: String): ShoppingListState {
        val id = ShoppingIds.valid(itemId) ?: return state
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.phase != ShoppingPhase.Idle || current.clearingChecked) return current
            if (current.items.none { it.id == id } || id in current.busyItemIds) return current
            listId = current.listId
            state = current.copy(busyItemIds = current.busyItemIds + id, problem = null)
        }
        val expectedList = listId
        val result = call { source.deleteItem(id) }
        return synchronized(lock) {
            if (state.listId != expectedList) return@synchronized state
            val updated = state.copy(busyItemIds = state.busyItemIds - id)
            state = when (result) {
                // Un 404 es que el elemento ya no existe: el estado deja de enseñarlo,
                // igual que en el borrado de marcados.
                is ApiResult.Success, is ApiResult.NotFound -> updated.withoutItem(id)
                else -> updated.copy(problem = problemOf(result))
            }
            state
        }
    }

    /**
     * Borra los elementos marcados que había al empezar. No empieza si hay
     * algún elemento con una escritura en vuelo: su `PUT` podría revertir y el
     * borrado dejaría el estado mintiendo. Mientras dura, todo el lote está en
     * [ShoppingListState.busyItemIds] y en [ShoppingListState.clearingItemIds],
     * y no se acepta ninguna otra escritura.
     *
     * El lote es exactamente lo que la interfaz pudo confirmar: si alguien
     * marca otro mientras tanto, ese se queda. Tras un fallo parcial se relee
     * la lista (un `GET`) porque en el servidor pueden faltar algunos.
     */
    fun deleteChecked(): ShoppingListState {
        var batch: List<ShoppingItem> = emptyList()
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.clearingChecked) return current
            if (current.phase != ShoppingPhase.Idle) return current
            if (current.busyItemIds.isNotEmpty()) return current
            val openList = current.listId ?: return current
            batch = current.items.filter { it.checked }
            if (batch.isEmpty()) return current
            listId = openList
            val ids = batch.mapTo(LinkedHashSet()) { it.id }
            state = current.copy(
                busyItemIds = current.busyItemIds + ids,
                clearingChecked = true,
                clearingItemIds = ids,
                problem = null,
            )
        }
        val openList = listId ?: return state
        val items = batch
        val ids = items.mapTo(HashSet()) { it.id }
        val result = call { source.deleteChecked(items) }
        val stillOpen = synchronized(lock) {
            if (state.listId != openList) {
                false
            } else {
                state = state.copy(
                    busyItemIds = state.busyItemIds - ids,
                    clearingChecked = false,
                    clearingItemIds = emptySet(),
                )
                true
            }
        }
        if (!stillOpen) return state
        return when (result) {
            is ApiResult.Success -> synchronized(lock) {
                state = state.copy(items = state.items.filterNot { it.id in ids }, problem = null)
                state
            }
            else -> {
                // Puede que algunos ya no estén en el servidor: se relee para no
                // enseñar elementos borrados y se conserva el aviso del fallo.
                // El aviso se pone sobre el estado vigente: `load` puede devolver
                // una copia que ya no manda en cuanto otra operación toca el
                // estado, y copiarla pisaría ese cambio.
                val problem = problemOf(result)
                load(openList, firstLoad = false)
                synchronized(lock) {
                    if (state.listId != openList || state.problem != null) {
                        state
                    } else {
                        state = state.copy(problem = problem)
                        state
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- privados

    /**
     * Ejecuta una llamada de la fuente y reduce cualquier fallo a un resultado
     * tipado. Un [NetworkError] se traduce con `ApiErrors.map`, como en
     * [ShoppingClient]; lo que no sea ni red ni [RuntimeException] no se
     * atrapa a propósito: un fallo raro debe verse en desarrollo, no disfrazarse
     * de problema de red.
     */
    private inline fun <T> call(block: () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (error: NetworkError) {
            ApiErrors.map(error)
        } catch (error: RuntimeException) {
            ApiResult.Failure(ConnectionProblem.Unknown)
        }

    /**
     * Como [call], pero para el `POST` de añadir: un fallo después de enviar el
     * cuerpo ([NetworkError.AfterSend]) puede haber creado el elemento, así que
     * se cuenta como [ApiResult.MaybeCreated] y se relee la lista antes de
     * repetir, igual que hace [ShoppingClient].
     */
    private inline fun callCreate(block: () -> ApiResult<Unit>): ApiResult<Unit> =
        try {
            block()
        } catch (error: NetworkError.AfterSend) {
            ApiResult.MaybeCreated(error.original.toConnectionProblem())
        } catch (error: NetworkError) {
            ApiErrors.map(error)
        } catch (error: RuntimeException) {
            ApiResult.Failure(ConnectionProblem.Unknown)
        }

    private fun load(listId: String, firstLoad: Boolean): ShoppingListState {
        val ticket = synchronized(lock) {
            // Si ya no está abierta esta lista (se abrió otra o se cerró mientras
            // una escritura estaba en vuelo), no se toca la fase ni la generación:
            // invalidaría la carga de la lista que sí está abierta y la dejaría
            // vacía y sin fallo, sin nada que reintentar.
            if (state.listId != listId) return state
            state = state.copy(
                phase = if (firstLoad) ShoppingPhase.LoadingFirst else ShoppingPhase.Refreshing,
                problem = null,
            )
            generation++
            phaseTicket = generation
            generation
        }
        val result = call { source.getList(listId) }
        return synchronized(lock) {
            // Se descarta si otra carga la ha superado o si ya no está abierta
            // la misma lista: aplicar entonces los elementos de una lista vieja
            // reabriría la que se acaba de cerrar.
            if (ticket != generation || state.listId != listId) {
                // La carga ya no manda. Si fue esta la que dejó la fase en
                // «cargando», se devuelve a Idle: la interfaz no debe esperar
                // por una respuesta que ya no se va a aplicar.
                if (phaseTicket == ticket && state.phase != ShoppingPhase.Idle) {
                    state = state.copy(phase = ShoppingPhase.Idle)
                }
                state
            } else {
                state = when (result) {
                    is ApiResult.Success -> {
                        val list = result.value
                        state.withItems(list.items).copy(
                            listId = list.id,
                            listName = list.name,
                            phase = ShoppingPhase.Idle,
                            problem = null,
                            loadedOnce = true,
                        )
                    }
                    else -> state.copy(
                        phase = ShoppingPhase.Idle,
                        problem = problemOf(result),
                    )
                }
                state
            }
        }
    }

    private fun validQuantity(quantity: Double): Boolean =
        quantity.isFinite() && quantity >= 0.0 && quantity <= ServingScaler.MAX_QUANTITY

    private fun problemOf(result: ApiResult<*>): ShoppingProblem = when (result) {
        is ApiResult.Success -> ShoppingProblem.Failed(ConnectionProblem.Unknown)
        is ApiResult.Unauthorized, is ApiResult.Forbidden -> ShoppingProblem.Unauthorized
        is ApiResult.NotFound -> ShoppingProblem.NotFound
        is ApiResult.Conflict -> ShoppingProblem.Rejected(409)
        is ApiResult.Invalid -> ShoppingProblem.InvalidData(result.details)
        is ApiResult.Rejected -> ShoppingProblem.Rejected(result.statusCode)
        is ApiResult.DraftInvalid -> ShoppingProblem.Rejected(422)
        is ApiResult.InputRejected -> ShoppingProblem.Failed(ConnectionProblem.Unknown)
        is ApiResult.SavedButNotReloaded -> ShoppingProblem.Failed(result.problem)
        is ApiResult.MaybeCreated -> ShoppingProblem.Failed(result.problem)
        is ApiResult.Failure -> ShoppingProblem.Failed(result.problem)
    }
}
