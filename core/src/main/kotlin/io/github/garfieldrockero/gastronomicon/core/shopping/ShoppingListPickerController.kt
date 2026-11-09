package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiErrors
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import java.util.concurrent.CancellationException

/**
 * Selector de listas de la compra: lee la página de listas, recuerda cuál está
 * abierta y crea una nueva dejándola abierta. La interfaz solo llama a estos
 * métodos y pinta [state]; no ordena, no deduplica y no decide qué lista abrir.
 *
 * [load], [refresh], [retry] y [create] son **bloqueantes**, como el resto del
 * núcleo: `app/` los lanza desde un hilo de fondo. [rememberOpenList] no toca la
 * red. [state] es seguro de leer desde cualquier hilo.
 *
 * Carreras:
 * - Solo la última lectura escribe el estado: cada una lleva una época
 *   ([generation]) y una respuesta que llega después de otra se descarta. Un
 *   [refresh] sí puede adelantar a una lectura en curso (la sustituye); [load] y
 *   [retry] no repiten si ya hay algo en marcha.
 * - Una creación no empieza con una lectura en curso, ni una lectura con una
 *   creación en curso: las escrituras no se pisan. Un segundo [create] mientras
 *   el primero sigue en vuelo no envía nada.
 * - Al crear con respuesta dudosa ([ApiResult.MaybeCreated]) se vuelve a leer la
 *   página para enseñar la lista si llegó a crearse, y se conserva el aviso: no
 *   se reintenta solo.
 *
 * El nombre se valida aquí con [ShoppingText.listNameInput] antes de llamar a
 * la fuente: vacío o demasiado largo queda en
 * [ShoppingListPickerProblem.InputRejected] sin enviar nada. La fuente también
 * lo comprueba como última barrera.
 *
 * Cualquier [NetworkError] de la fuente se traduce con `ApiErrors.map`, igual
 * que en [ShoppingClient] (401 y 403 → Unauthorized, 422 → InvalidData con el
 * detalle saneado, el resto → Rejected/Failed), y cualquier otra excepción
 * inesperada de una lectura se reduce a [ShoppingListPickerProblem.Failed] con
 * [ConnectionProblem.Unknown]: el estado nunca se queda «cargando».
 *
 * Al crear, un fallo que no prueba que el `POST` no salió ([NetworkError.AfterSend],
 * un 5xx o una respuesta ilegible) es
 * [ApiResult.MaybeCreated]: preferir un aviso de más solo hace mirar la lista,
 * mientras que decir «no se creó» de menos deja dos listas iguales. Solo se
 * cuenta como fallo definitivo lo que prueba que no salió nada (los mismos
 * [NetworkError] que [ShoppingClient]) o un rechazo definitivo del servidor.
 * Una `RuntimeException` suelta también es definitiva: el transporte no deja
 * escapar ninguna suya, así que saltó antes de enviar.
 *
 * Una cancelación o un `Error` no se disfrazan de fallo ni dejan la fase
 * colgada: la fase vuelve a [ShoppingListPickerPhase.Idle] y la excepción sigue
 * subiendo, para que quien canceló lo sepa.
 */
class ShoppingListPickerController(
    private val source: ShoppingListSource,
    initialOpenListId: String? = null,
) {
    private val lock = Any()

    // Época de las lecturas: solo la última lanzada escribe el estado.
    private var generation = 0L

    @Volatile
    var state: ShoppingListPickerState = ShoppingListPickerState(
        openListId = ShoppingIds.valid(initialOpenListId),
    )
        private set

    /**
     * Primera lectura de la página. No hace nada si ya se leyó bien o si hay
     * una operación en curso.
     */
    fun load(): ShoppingListPickerState {
        val current = state
        if (current.phase != ShoppingListPickerPhase.Idle) return current
        if (current.loadedOnce && current.problem == null) return current
        return read(always = false)
    }

    /**
     * Vuelve a leer siempre. Sustituye a una lectura en curso (solo su respuesta
     * manda), pero no empieza mientras hay una creación en vuelo.
     */
    fun refresh(): ShoppingListPickerState {
        if (state.isCreating) return state
        return read(always = true)
    }

    /** Repite la lectura que falló; sin fallo o con algo en curso no hace nada. */
    fun retry(): ShoppingListPickerState {
        val current = state
        if (current.phase != ShoppingListPickerPhase.Idle || current.problem == null) return current
        return read(always = false)
    }

    /**
     * Recuerda qué lista está abierta, sin tocar la red. [listId] nulo la
     * olvida (no hay ninguna abierta); un id que no es un UUID se ignora y se
     * conserva el que hubiera.
     */
    fun rememberOpenList(listId: String?): ShoppingListPickerState {
        val checked = if (listId == null) null else ShoppingIds.valid(listId) ?: return state
        return synchronized(lock) {
            if (state.openListId == checked) {
                state
            } else {
                state = state.copy(openListId = checked)
                state
            }
        }
    }

    /**
     * Crea una lista con [name] y la deja abierta ([ShoppingListPickerState.openListId]).
     * El nombre se sanea y se valida antes de enviar nada.
     *
     * Tras un `POST` correcto, la lista se añade a [ShoppingListPickerState.lists]
     * y se abre; no se vuelve a pedir la página. Si el resultado es
     * [ApiResult.MaybeCreated], se relee la página para enseñar lo que haya y se
     * deja el aviso: nadie debe repetir a ciegas y crear dos listas.
     *
     * Con una lectura o una creación en curso no se envía nada y se devuelve el
     * estado vigente.
     */
    fun create(name: String): ShoppingListPickerState {
        val input = ShoppingText.listNameInput(name)
        var ticket = 0L
        synchronized(lock) {
            val current = state
            if (current.isBusy) return current
            val inputProblem = input.problem
            if (inputProblem != null) {
                state = current.copy(problem = ShoppingListPickerProblem.InputRejected(inputProblem))
                return state
            }
            generation++
            ticket = generation
            state = current.copy(phase = ShoppingListPickerPhase.Creating, problem = null)
        }

        // Un `Error` o una cancelación no dejan resultado: la fase vuelve a Idle
        // antes de que la excepción siga subiendo, o el botón de crear quedaría
        // bloqueado para siempre.
        var result: ApiResult<ShoppingListSummary>? = null
        try {
            result = callCreate { source.createList(input.text) }
        } finally {
            if (result == null) releasePhase(ticket)
        }
        val created = result ?: return state

        // Fallo dudoso que hay que comprobar releyendo; null si no hay nada que verificar.
        var doubtful: ConnectionProblem? = null
        synchronized(lock) {
            if (ticket != generation) return state
            state = when (created) {
                is ApiResult.Success -> {
                    val summary = created.value
                    val id = ShoppingIds.valid(summary.id)
                    if (id == null) {
                        // Un id que la app no puede abrir: la lista puede existir, así
                        // que se comprueba en vez de dar por bueno algo inutilizable.
                        doubtful = ConnectionProblem.Unknown
                        state.copy(
                            phase = ShoppingListPickerPhase.Idle,
                            problem = ShoppingListPickerProblem.MaybeCreated(ConnectionProblem.Unknown),
                        )
                    } else {
                        state.withList(summary).copy(
                            openListId = id,
                            phase = ShoppingListPickerPhase.Idle,
                            problem = null,
                        )
                    }
                }
                is ApiResult.MaybeCreated -> {
                    doubtful = created.problem
                    state.copy(
                        phase = ShoppingListPickerPhase.Idle,
                        problem = ShoppingListPickerProblem.MaybeCreated(created.problem),
                    )
                }
                else -> state.copy(
                    phase = ShoppingListPickerPhase.Idle,
                    problem = problemOf(created),
                )
            }
        }

        val verify = doubtful ?: return state
        // Se relee para enseñar la lista si el POST llegó a crearla. El aviso se
        // pone después sobre el estado vigente: si mientras tanto otra operación
        // ha empezado, manda su estado y no se mezcla con este resultado.
        read(always = true)
        return synchronized(lock) {
            if (!state.isBusy) {
                state = state.copy(problem = ShoppingListPickerProblem.MaybeCreated(verify))
            }
            state
        }
    }

    // ------------------------------------------------------------- privados

    /**
     * Lee una página de listas. [always] distingue la primera carga y el
     * reintento (no repiten si ya hay una lectura buena) del refresco, que
     * siempre pide y sustituye a la lectura en curso.
     */
    private fun read(always: Boolean): ShoppingListPickerState {
        var ticket = 0L
        synchronized(lock) {
            val current = state
            if (current.phase == ShoppingListPickerPhase.Creating) return current
            // load y retry no repiten con una lectura en curso; refresh sí la sustituye.
            if (!always && current.phase != ShoppingListPickerPhase.Idle) return current
            if (!always && current.loadedOnce && current.problem == null) return current
            generation++
            ticket = generation
            state = current.copy(phase = ShoppingListPickerPhase.Loading, problem = null)
        }

        // Un `Error` o una cancelación no dejan resultado: la fase vuelve a Idle
        // antes de que la excepción siga subiendo, o la pantalla se quedaría
        // cargando para siempre.
        var result: ApiResult<Page<ShoppingListSummary>>? = null
        try {
            result = callLoad { source.listLists(page = 1, perPage = LISTS_PER_PAGE) }
        } finally {
            if (result == null) releasePhase(ticket)
        }
        val loaded = result ?: return state

        return synchronized(lock) {
            // Una lectura superada no toca nada: otra más nueva manda (y su fase).
            if (ticket != generation) return@synchronized state
            state = when (loaded) {
                is ApiResult.Success -> state.withLists(loaded.value.items).copy(
                    phase = ShoppingListPickerPhase.Idle,
                    problem = null,
                    loadedOnce = true,
                )
                else -> state.copy(
                    phase = ShoppingListPickerPhase.Idle,
                    problem = problemOf(loaded),
                )
            }
            state
        }
    }

    /**
     * Devuelve la fase a [ShoppingListPickerPhase.Idle] si la operación [ticket]
     * sigue mandando. Solo se llama cuando la lectura o la creación se cortan
     * con algo que no es un resultado (un `Error`, una cancelación): sin esto la
     * fase se quedaría en «cargando» o en «creando» para siempre.
     */
    private fun releasePhase(ticket: Long) {
        synchronized(lock) {
            if (ticket == generation && state.phase != ShoppingListPickerPhase.Idle) {
                state = state.copy(phase = ShoppingListPickerPhase.Idle)
            }
        }
    }

    /**
     * Ejecuta una lectura y reduce cualquier fallo a un resultado tipado. Lo que
     * no sea red ni [RuntimeException] no se atrapa a propósito: un fallo raro
     * debe verse en desarrollo, no disfrazarse de problema de red. Una
     * cancelación tampoco se disfraza: se relanza tal cual ([read] devuelve la
     * fase a Idle antes) para que quien canceló lo sepa y la pantalla no enseñe
     * un error que no ha existido.
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
     * Como [callLoad], pero para el `POST` de crear: solo se cuenta como fallo
     * definitivo lo que prueba que la petición no salió o un rechazo del
     * servidor que no deja nada creado. Todo lo demás
     * —[NetworkError.AfterSend], un 5xx (un proxy puede contestar 502 mientras
     * Mealie sigue creando) o una respuesta ilegible— es
     * [ApiResult.MaybeCreated]: se relee la página antes de dejar repetir, igual
     * que hace [ShoppingClient].
     *
     * Una [RuntimeException] suelta no es dudosa: el transporte convierte en
     * [NetworkError] (y en [NetworkError.AfterSend] lo ambiguo posterior al
     * envío) cualquier fallo suyo, así que una excepción que llegue hasta aquí
     * saltó antes de enviar. Es un fallo definitivo con
     * [ConnectionProblem.Unknown], sin aviso de «quizá creado».
     */
    private inline fun <T> callCreate(block: () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (error: CancellationException) {
            // Se relanza: [create] devuelve la fase a Idle antes de que suba.
            throw error
        } catch (error: NetworkError.AfterSend) {
            ApiResult.MaybeCreated(error.original.toConnectionProblem())
        } catch (error: NetworkError) {
            creationFailure(error)
        } catch (error: RuntimeException) {
            // Saltó antes de enviar: no hay nada que comprobar en la página.
            ApiResult.Failure(ConnectionProblem.Unknown)
        }

    /**
     * «No se creó» solo si el fallo es anterior al envío o si el servidor ha
     * contestado un rechazo definitivo; lo demás es ambiguo. Mismo criterio que
     * `ShoppingClient.creationFailure`, para que el selector y el cliente no
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

    private fun problemOf(result: ApiResult<*>): ShoppingListPickerProblem? = when (result) {
        is ApiResult.Success -> null
        is ApiResult.Unauthorized, is ApiResult.Forbidden -> ShoppingListPickerProblem.Unauthorized
        is ApiResult.NotFound -> ShoppingListPickerProblem.Rejected(404)
        is ApiResult.Conflict -> ShoppingListPickerProblem.Rejected(409)
        is ApiResult.Invalid -> ShoppingListPickerProblem.InvalidData(result.details)
        is ApiResult.Rejected -> ShoppingListPickerProblem.Rejected(result.statusCode)
        is ApiResult.DraftInvalid -> ShoppingListPickerProblem.Rejected(422)
        is ApiResult.InputRejected -> ShoppingListPickerProblem.Failed(ConnectionProblem.Unknown)
        is ApiResult.SavedButNotReloaded -> ShoppingListPickerProblem.Failed(result.problem)
        is ApiResult.MaybeCreated -> ShoppingListPickerProblem.MaybeCreated(result.problem)
        is ApiResult.Failure -> ShoppingListPickerProblem.Failed(result.problem)
    }

    private companion object {
        /** Una página basta: el selector no pagina (igual que la pantalla anterior). */
        const val LISTS_PER_PAGE = ShoppingClient.DEFAULT_LISTS_PER_PAGE

        /**
         * Códigos con los que el servidor rechaza la petición de forma
         * definitiva: no se ha creado nada. Mismo conjunto que `ShoppingClient`.
         */
        val DEFINITIVE_CREATION_REJECTIONS = setOf(400, 401, 403, 404, 405, 409, 413, 415, 422)
    }
}
