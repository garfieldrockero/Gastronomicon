package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiErrors
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.ServingScaler
import java.util.concurrent.CancellationException

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
 * Las escrituras solo empiezan con la lista en [ShoppingPhase.Idle], fuera de
 * un borrado de marcados y sin un «añadiendo» en vuelo ([ShoppingListState.adding]):
 * así el resultado de una escritura nunca se mezcla con una recarga en vuelo ni
 * con la relectura que sigue a un `POST`, y la fase no se queda en «cargando». Además, al
 * aplicar el resultado se comprueba que sigue abierta la misma lista
 * ([ShoppingListState.listId]); si se abrió otra, el resultado se descarta.
 *
 * Añadir no devuelve el elemento: tras un `POST` correcto (o dudoso, ver
 * [ApiResult.MaybeCreated]) se vuelve a leer la lista, para no inventar ids ni
 * arriesgar duplicados si el servidor contesta algo que no se entiende. Lo mismo
 * vale para añadir los ingredientes de recetas ([addRecipes]): el `POST` lleva
 * la escala y la respuesta se ignora; la lista se relee.
 *
 * Cualquier [NetworkError] de la fuente se traduce con `ApiErrors.map`, igual
 * que en `ShoppingClient` (401 y 403 → [ShoppingProblem.Unauthorized], 404 →
 * [ShoppingProblem.NotFound], 422 → [ShoppingProblem.InvalidData], el resto →
 * [ShoppingProblem.Failed]), y cualquier otra excepción inesperada se reduce a
 * [ShoppingProblem.Failed] con [ConnectionProblem.Unknown]: el estado nunca se
 * queda «cargando».
 *
 * Dos cosas no se disfrazan nunca:
 * - Al **añadir**, un fallo que no prueba que la petición no salió (un
 *   [NetworkError.AfterSend], un 5xx o una respuesta ilegible) es
 *   [ApiResult.MaybeCreated]: preferir un aviso de más solo hace mirar la
 *   lista, mientras que decir «no se añadió» de menos deja dos líneas iguales.
 *   Solo se cuenta como fallo definitivo lo que prueba que no salió nada (los
 *   mismos [NetworkError] que `ShoppingClient`) o un rechazo definitivo del
 *   servidor. Una `RuntimeException` suelta también es definitiva: el
 *   transporte no deja escapar ninguna suya, así que saltó antes de enviar.
 * - Una **cancelación** o un `Error` de la carga no son fallos de red: la fase
 *   vuelve a [ShoppingPhase.Idle] y la excepción sigue subiendo, para que quien
 *   canceló lo sepa y la interfaz no espere una respuesta que no va a llegar.
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
     * en marcha, un borrado de marcados, un «añadiendo» en vuelo o una
     * escritura ya en curso sobre él no hacen nada.
     */
    fun toggle(itemId: String): ShoppingListState {
        val id = ShoppingIds.valid(itemId) ?: return state
        var target: ShoppingItem? = null
        var newChecked = false
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.phase != ShoppingPhase.Idle || current.clearingChecked || current.adding) return current
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
        // `adding` se cierra siempre, también si la fuente se corta con una
        // cancelación o un `Error`: si no, el botón de añadir quedaría muerto.
        var result: ApiResult<Unit>? = null
        try {
            result = callCreate { source.addItem(openList, input.text) }
        } finally {
            synchronized(lock) {
                if (state.listId == openList) state = state.copy(adding = false)
            }
        }
        val added = result ?: return state
        return applyCreated(openList, added)
    }

    /**
     * Añade los ingredientes de una receta con [scale] (1.0 = tal cual).
     * Atajo de [addRecipes].
     */
    fun addRecipe(recipeId: String, scale: Double = 1.0): ShoppingListState =
        addRecipes(listOf(RecipeAddition(recipeId, scale)))

    /**
     * Añade los ingredientes de [additions] en una sola petición y relee la
     * lista. Sirve para una receta suelta y para la semana del planificador
     * (ver `RecipeAdditions.plan`).
     *
     * Las recetas se validan aquí ([RecipeAdditions.check]): un id que no es
     * UUID, una escala fuera del rango del núcleo o una tanda vacía o
     * demasiado grande **no se envían y no tocan el estado**. Los `recipeId`
     * repetidos se fusionan sumando sus escalas ([RecipeAdditions.merge]) antes
     * de validar y de enviar, para no dejar dos líneas de los mismos
     * ingredientes. No se pone un aviso porque `ShoppingProblem` no tiene un
     * motivo para «dato inválido de la receta» sin tocar la interfaz: quien
     * llama tiene que comprobar antes con `RecipeAdditions.check` para poder
     * explicarlo. (Pendiente de decisión: añadir un motivo tipado y su texto.)
     *
     * Tras un `POST` correcto se relee la lista, igual que al añadir una nota.
     * Un desenlace dudoso ([ApiResult.MaybeCreated]) también relee y deja el
     * aviso: el servidor puede haber metido los ingredientes y repetir a ciegas
     * los duplicaría.
     *
     * Mientras dura, [ShoppingListState.adding] está encendido y no se acepta
     * ninguna otra escritura.
     */
    fun addRecipes(additions: List<RecipeAddition>): ShoppingListState {
        // Se copia y se fusiona: quien llama puede reutilizar la lista que pasa y
        // no debe cambiar lo que se manda a mitad de la petición, y un id
        // repetido no puede acabar en dos líneas de los mismos ingredientes.
        val batch = RecipeAdditions.merge(additions)
        if (RecipeAdditions.check(batch) != null) return state
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.adding || current.clearingChecked || current.phase != ShoppingPhase.Idle) return current
            listId = current.listId ?: return current
            state = current.copy(adding = true, problem = null)
        }
        val openList = listId ?: return state
        // `adding` se cierra siempre, también si la fuente se corta con una
        // cancelación o un `Error`: si no, el botón de añadir quedaría muerto.
        var result: ApiResult<Unit>? = null
        try {
            result = callCreate { source.addRecipes(openList, batch) }
        } finally {
            synchronized(lock) {
                if (state.listId == openList) state = state.copy(adding = false)
            }
        }
        val added = result ?: return state
        return applyCreated(openList, added)
    }

    /**
     * Cambia la nota y la cantidad del elemento [itemId]. La cantidad 0 es
     * «sin cantidad». Si el texto se queda vacío y el elemento no tiene
     * alimento ni unidad, no se envía: la línea no tendría nada que enseñar.
     * No empieza con una carga, un borrado de marcados o un «añadiendo» en
     * vuelo.
     */
    fun editNote(itemId: String, note: String, quantity: Double): ShoppingListState {
        val id = ShoppingIds.valid(itemId) ?: return state
        val input = ShoppingText.item(note)
        var target: ShoppingItem? = null
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.phase != ShoppingPhase.Idle || current.clearingChecked || current.adding) return current
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
     * cuando el servidor lo acepta o contesta 404 (ya no estaba). No empieza con
     * una carga, un borrado de marcados o un «añadiendo» en vuelo.
     */
    fun delete(itemId: String): ShoppingListState {
        val id = ShoppingIds.valid(itemId) ?: return state
        var listId: String? = null
        synchronized(lock) {
            val current = state
            if (current.phase != ShoppingPhase.Idle || current.clearingChecked || current.adding) return current
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
     * algún elemento con una escritura en vuelo ni mientras se están añadiendo
     * ingredientes: su `PUT` podría revertir y el borrado dejaría el estado
     * mintiendo, y la relectura del `POST` podría resucitar lo borrado. Mientras
     * dura, todo el lote está en [ShoppingListState.busyItemIds] y en
     * [ShoppingListState.clearingItemIds], y no se acepta ninguna otra escritura.
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
            if (current.adding) return current
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
     * Como [call], pero para la carga: una cancelación no se convierte en un
     * fallo de red. Se relanza tal cual ([load] devuelve la fase a
     * [ShoppingPhase.Idle] antes) para que quien canceló lo sepa y la interfaz
     * no enseñe un error que no ha existido.
     */
    private inline fun <T> callLoad(block: () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkError) {
            ApiErrors.map(error)
        } catch (error: RuntimeException) {
            ApiResult.Failure(ConnectionProblem.Unknown)
        }

    /**
     * Como [call], pero para el `POST` de añadir: solo se cuenta como fallo
     * definitivo lo que prueba que la petición no salió o un rechazo del
     * servidor que no deja nada creado. Todo lo demás —[NetworkError.AfterSend],
     * un 5xx (un proxy puede contestar 502 mientras Mealie sigue creando) o una
     * respuesta ilegible— es [ApiResult.MaybeCreated]: se relee la lista antes de
     * dejar repetir, igual que hace [ShoppingClient].
     *
     * Una [RuntimeException] que llegue hasta aquí **no** es una creación
     * dudosa: el transporte envuelve en [NetworkError] (y en
     * [NetworkError.AfterSend] lo ambiguo posterior al envío) cualquier fallo
     * suyo, así que una excepción suelta salió al validar, al componer el cuerpo
     * o al abrir la conexión, es decir, antes de enviar nada. Se cuenta como
     * fallo definitivo ([ApiResult.Failure] con [ConnectionProblem.Unknown]) y
     * no se avisa de «quizá creado» ni se relee: mandar a mirar la lista por una
     * petición que nunca salió solo confunde.
     *
     * Una cancelación no se traga: se relanza tal cual ([add] cierra `adding`
     * antes) para que quien canceló lo sepa.
     */
    private inline fun callCreate(block: () -> ApiResult<Unit>): ApiResult<Unit> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkError.AfterSend) {
            ApiResult.MaybeCreated(error.original.toConnectionProblem())
        } catch (error: NetworkError) {
            creationFailure(error)
        } catch (error: RuntimeException) {
            // El transporte no deja escapar sus propias excepciones: si llega
            // una, saltó antes de enviar y no hay nada que comprobar.
            ApiResult.Failure(ConnectionProblem.Unknown)
        }

    /**
     * «No se creó» solo si el fallo es anterior al envío o si el servidor ha
     * contestado un rechazo definitivo; lo demás es ambiguo. Mismo criterio que
     * `ShoppingClient.creationFailure`, para que el controlador y el cliente no
     * discrepen con la misma respuesta.
     */
    private fun creationFailure(error: NetworkError): ApiResult<Nothing> = when {
        error.happenedBeforeSend() -> ApiErrors.map(error)
        error is NetworkError.HttpError && error.statusCode in DEFINITIVE_CREATION_REJECTIONS ->
            ApiErrors.map(error)
        else -> ApiResult.MaybeCreated(error.toConnectionProblem())
    }

    /** ¿El fallo prueba que la petición no llegó a salir? Mismo criterio que `ShoppingClient`. */
    private fun NetworkError.happenedBeforeSend(): Boolean = when (this) {
        NetworkError.NoConnection,
        NetworkError.ConnectionRefused,
        NetworkError.DnsFailure,
        NetworkError.TlsError,
        NetworkError.Timeout,
        NetworkError.InvalidRequest,
        NetworkError.LocalHttpNotAllowedOnThisNetwork -> true
        else -> false
    }

    /**
     * Aplica el desenlace de un `POST` que crea algo en la lista (una nota o
     * los ingredientes de recetas). Con éxito o con duda se relee la lista; el
     * aviso se pone sobre el estado vigente y no sobre la copia que devolvió
     * `load`: entre la relectura y este punto otra operación pudo cambiarlo
     * (abrir otra lista, un borrado) y pisarlo con la copia vieja perdería ese
     * cambio. Con un rechazo definitivo no se relee: no hay nada nuevo.
     */
    private fun applyCreated(openList: String, created: ApiResult<Unit>): ShoppingListState = when (created) {
        is ApiResult.Success -> load(openList, firstLoad = false)
        is ApiResult.MaybeCreated -> {
            // La fase de «añadiendo» ya está cerrada antes de releer.
            load(openList, firstLoad = false)
            synchronized(lock) {
                if (state.listId == openList) {
                    state = state.copy(problem = problemOf(created))
                }
                state
            }
        }
        else -> synchronized(lock) {
            if (state.listId == openList) state = state.copy(problem = problemOf(created))
            state
        }
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
        // Un `Error` o una cancelación no dejan resultado: la fase vuelve a Idle
        // antes de que la excepción siga subiendo, o la interfaz se quedaría
        // esperando para siempre una respuesta que ya no va a llegar.
        var result: ApiResult<ShoppingList>? = null
        try {
            result = callLoad { source.getList(listId) }
        } finally {
            if (result == null) releasePhase(ticket, listId)
        }
        val loaded = result ?: return state
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
                state = when (loaded) {
                    is ApiResult.Success -> {
                        val list = loaded.value
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
                        problem = problemOf(loaded),
                    )
                }
                state
            }
        }
    }

    /**
     * Devuelve la fase a [ShoppingPhase.Idle] si la carga [ticket] sigue
     * mandando. Solo se llama cuando la lectura se corta con algo que no es un
     * resultado (un `Error`, una cancelación): sin esto la fase se quedaría en
     * «cargando» para siempre.
     */
    private fun releasePhase(ticket: Long, listId: String) {
        synchronized(lock) {
            if (ticket == generation && state.listId == listId && state.phase != ShoppingPhase.Idle) {
                state = state.copy(phase = ShoppingPhase.Idle)
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

    private companion object {
        /**
         * Códigos con los que el servidor rechaza la petición de forma
         * definitiva: no se ha creado nada. Mismo conjunto que `ShoppingClient`.
         */
        val DEFINITIVE_CREATION_REJECTIONS = setOf(400, 401, 403, 404, 405, 409, 413, 415, 422)
    }
}
