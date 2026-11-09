package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import io.github.garfieldrockero.gastronomicon.core.network.HttpRequest
import io.github.garfieldrockero.gastronomicon.core.network.HttpResponse
import io.github.garfieldrockero.gastronomicon.core.network.HttpTransport
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import io.github.garfieldrockero.gastronomicon.core.session.Session
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * Cliente del planificador de comidas sobre [HttpTransport]: lee entradas por
 * rango de fechas y las de hoy, y crea, mueve, edita y borra.
 *
 * Es una clase aparte de `MealieClient` y usa los mismos [HttpTransport] y
 * `Session` (ver [create]): la URL, el token, los plazos, las redirecciones y
 * la frontera del HTTP local son los del transporte, no se duplican aquí.
 *
 * Rutas y parámetros, de `docs/openapi.json`:
 * - `GET /api/households/mealplans?start_date&end_date&page&perPage`.
 * - `GET /api/households/mealplans/today`.
 * - `GET|PUT|DELETE /api/households/mealplans/{item_id}`.
 *
 * Todo método es síncrono y **no lanza por red**: devuelve un
 * [MealPlanResult]. Los datos de entrada se validan antes de formar la ruta o
 * la consulta: un id o una fecha que no valgan dan [MealPlanResult.InvalidInput]
 * sin enviar nada. Ningún resultado lleva el token, la URL del servidor ni un
 * cuerpo crudo; el detalle de un 422 se entrega saneado y acotado.
 */
class MealPlanClient(private val transport: HttpTransport) : MealPlanSource {

    /**
     * Todas las entradas entre [start] y [end], ambos incluidos, siguiendo la
     * paginación con `page` y `perPage`. Los `next`/`previous` del servidor se
     * ignoran (son URLs ajenas) y se corta si el servidor repite la página
     * pedida, para no dar vueltas. Se deduplica por id y se acota a
     * [MAX_MEAL_PLAN_ENTRIES].
     */
    override fun listEntries(start: LocalDate, end: LocalDate): MealPlanResult<List<PlannedMeal>> {
        MealPlanDates.check(start, end)?.let { return MealPlanResult.InvalidInput(it) }
        return try {
            val entries = ArrayList<PlannedMeal>()
            val seen = HashSet<Long>()
            var page = 1
            var more = true
            while (more && page <= MAX_PAGES && entries.size < MAX_MEAL_PLAN_ENTRIES) {
                val root = parseElement(transport.execute(pageRequest(start, end, page))) as? JsonObject
                    ?: throw NetworkError.InvalidResponse
                val dto = decodeObject(PlanEntryPageDto.serializer(), root)
                val rawItems = (root["items"] as? JsonArray)?.toList().orEmpty()
                var added = 0
                for (element in rawItems) {
                    if (entries.size >= MAX_MEAL_PLAN_ENTRIES) break
                    val entry = entryOf(element) ?: continue
                    if (seen.add(entry.id)) {
                        entries += entry
                        added++
                    }
                }
                val serverPage = dto.page ?: page
                // Una página sin ids nuevos no aporta nada: el servidor está
                // repitiendo entradas y seguir solo daría vueltas.
                more = added > 0 && serverPage == page && pageHasMore(dto, page, rawItems.size)
                page++
            }
            MealPlanResult.Success(entries)
        } catch (error: NetworkError) {
            MealPlanErrors.map(error)
        }
    }

    /**
     * Entradas de hoy. El esquema de `/today` viene vacío en
     * `docs/openapi.json`, así que se aceptan las dos formas razonables: una
     * lista de entradas o el objeto paginado con `items`. Cualquier otra cosa
     * es [MealPlanResult.Failure] (nunca una lista inventada).
     */
    override fun entriesToday(): MealPlanResult<List<PlannedMeal>> = guarded {
        val root = parseElement(transport.execute(HttpRequest(HttpMethod.GET, TODAY_PATH)))
        val elements = when (root) {
            is JsonArray -> root
            is JsonObject -> root["items"] as? JsonArray
            else -> null
        } ?: throw NetworkError.InvalidResponse

        val entries = ArrayList<PlannedMeal>()
        val seen = HashSet<Long>()
        for (element in elements) {
            if (entries.size >= MAX_MEAL_PLAN_ENTRIES) break
            val entry = entryOf(element) ?: continue
            if (seen.add(entry.id)) entries += entry
        }
        MealPlanResult.Success(entries)
    }

    override fun getEntry(id: Long): MealPlanResult<PlannedMeal> {
        val checked = MealPlanIds.checked(id)
            ?: return MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidEntryId)
        return guarded {
            val root = parseElement(transport.execute(HttpRequest(HttpMethod.GET, itemPath(checked)))) as? JsonObject
                ?: throw NetworkError.InvalidResponse
            val dto = decodeObject(PlanEntryDto.serializer(), root)
            MealPlanResult.Success(MealPlanMapper.entry(dto, root) ?: throw NetworkError.InvalidResponse)
        }
    }

    /**
     * `POST /api/households/mealplans` con el borrador validado. Solo viajan
     * los campos con contenido (el resto son valores por defecto del esquema).
     *
     * No se reintenta nunca: si el plazo vence o la conexión se corta con el
     * cuerpo ya enviado, el resultado es [MealPlanResult.MaybeCreated] y quien
     * llama tiene que mirar la semana antes de repetir.
     */
    override fun createEntry(draft: MealPlanDraft): MealPlanResult<PlannedMeal> {
        val clean = when (val check = MealPlanDraftValidator.validate(draft)) {
            is MealPlanDraftCheck.Valid -> check.draft
            is MealPlanDraftCheck.Invalid -> return MealPlanResult.InvalidInput(check.problem)
        }
        return try {
            val response = transport.execute(
                HttpRequest.json(HttpMethod.POST, MEALPLANS_PATH, encode(createBody(clean)), readErrorBody = true),
            )
            MealPlanResult.Success(readEntry(response))
        } catch (error: NetworkError.AfterSend) {
            MealPlanResult.MaybeCreated(error.original.toConnectionProblem())
        } catch (error: NetworkError) {
            creationFailure(error)
        }
    }

    /**
     * Mueve [entry] a [date] (y, si se indica, a otro [mealType]). El cuerpo
     * parte del JSON crudo de la lectura y cambia SOLO `date` y `entryType`:
     * la nota conserva sus saltos de línea, el título no se recorta y
     * `recipeId`, `recipeScale` o claves desconocidas salen tal cual, aunque
     * esta versión de la app no sepa interpretarlos.
     */
    override fun moveEntry(
        entry: PlannedMeal,
        date: LocalDate,
        mealType: MealType,
    ): MealPlanResult<PlannedMeal> {
        val id = MealPlanIds.checked(entry.id)
            ?: return MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidEntryId)
        MealPlanDates.check(date)?.let { return MealPlanResult.InvalidInput(it) }
        if (!mealType.isKnown) return MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidMealType)
        return guarded {
            val body = moveBody(entry, id, date, mealType)
                ?: return@guarded MealPlanResult.InvalidInput(MealPlanInputProblem.MissingEntryIdentity)
            putEntry(id, body)
        }
    }

    /**
     * Sustituye los datos de [entry] por los del borrador, ya validado, sobre
     * el JSON crudo: solo se reescriben los campos que el borrador define y el
     * resto del elemento no se toca.
     */
    override fun editEntry(entry: PlannedMeal, draft: MealPlanDraft): MealPlanResult<PlannedMeal> {
        val clean = when (val check = MealPlanDraftValidator.validate(draft)) {
            is MealPlanDraftCheck.Valid -> check.draft
            is MealPlanDraftCheck.Invalid -> return MealPlanResult.InvalidInput(check.problem)
        }
        val id = MealPlanIds.checked(entry.id)
            ?: return MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidEntryId)
        MealPlanDates.check(clean.date)?.let { return MealPlanResult.InvalidInput(it) }
        if (!clean.mealType.isKnown) return MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidMealType)
        return guarded {
            val body = editBody(entry, id, clean)
                ?: return@guarded MealPlanResult.InvalidInput(MealPlanInputProblem.MissingEntryIdentity)
            putEntry(id, body)
        }
    }

    override fun deleteEntry(id: Long): MealPlanResult<Unit> {
        val checked = MealPlanIds.checked(id)
            ?: return MealPlanResult.InvalidInput(MealPlanInputProblem.InvalidEntryId)
        return guarded {
            transport.execute(HttpRequest(HttpMethod.DELETE, itemPath(checked)))
            MealPlanResult.Success(Unit)
        }
    }

    // ------------------------------------------------------------- privados

    /** `PUT` de la entrada y lectura de lo que devuelve el servidor. */
    private fun putEntry(id: Long, body: JsonObject): MealPlanResult<PlannedMeal> {
        val response = transport.execute(
            HttpRequest.json(HttpMethod.PUT, itemPath(id), encode(body), readErrorBody = true),
        )
        return MealPlanResult.Success(readEntry(response))
    }

    /** El JSON crudo con `date` y `entryType` cambiados; null si no hay identidad con la que escribir. */
    private fun moveBody(entry: PlannedMeal, id: Long, date: LocalDate, mealType: MealType): JsonObject? {
        val base = entry.rawJson ?: identityBody(entry, id) ?: return null
        val fields = LinkedHashMap<String, JsonElement>(base)
        fields["date"] = JsonPrimitive(date.toString())
        fields["entryType"] = JsonPrimitive(mealType.apiValue)
        return JsonObject(fields)
    }

    /** El JSON crudo con los campos del borrador cambiados; null si no hay identidad. */
    private fun editBody(entry: PlannedMeal, id: Long, draft: CleanMealPlanDraft): JsonObject? {
        val base = entry.rawJson ?: identityBody(entry, id) ?: return null
        val fields = LinkedHashMap<String, JsonElement>(base)
        fields["date"] = JsonPrimitive(draft.date.toString())
        fields["entryType"] = JsonPrimitive(draft.mealType.apiValue)
        fields["title"] = JsonPrimitive(draft.title)
        fields["text"] = JsonPrimitive(draft.text)
        // Null explícito: quitar la receta de una entrada es un cambio que se envía.
        fields["recipeId"] = draft.recipeId?.let { JsonPrimitive(it) } ?: JsonNull
        fields["recipeScale"] = JsonPrimitive(draft.recipeScale)
        return JsonObject(fields)
    }

    /**
     * Cuerpo mínimo cuando la entrada no trae JSON crudo (la construyó la app):
     * `UpdatePlanEntry` exige `id`, `groupId` y `userId`, así que sin identidad
     * válida no se escribe. Es el único camino con pérdida posible de campos
     * ajenos, y solo se usa si no hubo lectura.
     */
    private fun identityBody(entry: PlannedMeal, id: Long): JsonObject? {
        val groupId = entry.groupId?.takeIf { RecipeImage.normalizeId(it) != null } ?: return null
        val userId = entry.userId?.takeIf { RecipeImage.normalizeId(it) != null } ?: return null
        val fields = LinkedHashMap<String, JsonElement>()
        fields["id"] = JsonPrimitive(id)
        fields["groupId"] = JsonPrimitive(groupId)
        fields["userId"] = JsonPrimitive(userId)
        fields["date"] = JsonPrimitive(entry.date.toString())
        fields["entryType"] = JsonPrimitive(entry.mealType.apiValue)
        fields["title"] = JsonPrimitive(entry.title)
        fields["text"] = JsonPrimitive(entry.note)
        fields["recipeId"] = entry.recipeId?.let { JsonPrimitive(it) } ?: JsonNull
        fields["recipeScale"] = JsonPrimitive(entry.recipeScale)
        return JsonObject(fields)
    }

    private fun pageRequest(start: LocalDate, end: LocalDate, page: Int): HttpRequest {
        // LocalDate.toString() es «yyyy-MM-dd»; los años se han acotado antes,
        // así que nunca aparece el «+» de un año de cinco cifras.
        val query = "start_date=$start&end_date=$end&page=$page&perPage=$PER_PAGE"
        // El detalle de un 422 se pide para poder decir qué parámetro molesta;
        // el transporte lo acota y MealPlanErrors lo sanea antes de entregarlo.
        return HttpRequest(HttpMethod.GET, MEALPLANS_PATH, query = query, readErrorBody = true)
    }

    /** ¿Queda otra página? Se usa el `per_page` del servidor si lo manda. */
    private fun pageHasMore(dto: PlanEntryPageDto, requestedPage: Int, received: Int): Boolean {
        if (received == 0) return false
        val perPage = dto.perPage?.takeIf { it in 1..MAX_PER_PAGE } ?: PER_PAGE
        val total = dto.total
        if (total != null && total > 0L) return requestedPage.toLong() * perPage < total
        return received >= perPage
    }

    private fun itemPath(id: Long): String = "$MEALPLANS_PATH/$id"

    private fun createBody(draft: CleanMealPlanDraft): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["date"] = JsonPrimitive(draft.date.toString())
        fields["entryType"] = JsonPrimitive(draft.mealType.apiValue)
        if (draft.title.isNotEmpty()) fields["title"] = JsonPrimitive(draft.title)
        if (draft.text.isNotEmpty()) fields["text"] = JsonPrimitive(draft.text)
        draft.recipeId?.let { fields["recipeId"] = JsonPrimitive(it) }
        fields["recipeScale"] = JsonPrimitive(draft.recipeScale)
        return JsonObject(fields)
    }

    /** Decodifica una entrada suelta de una lista sin que una rota tumbe el resto. */
    private fun entryOf(element: JsonElement): PlannedMeal? {
        val objectElement = element as? JsonObject ?: return null
        return try {
            MealPlanMapper.entry(decodeObject(PlanEntryDto.serializer(), objectElement), objectElement)
        } catch (ignored: NetworkError) {
            null
        }
    }

    private fun readEntry(response: HttpResponse): PlannedMeal {
        val root = parseElement(response) as? JsonObject ?: throw NetworkError.InvalidResponse
        val dto = decodeObject(PlanEntryDto.serializer(), root)
        return MealPlanMapper.entry(dto, root) ?: throw NetworkError.InvalidResponse
    }

    private fun encode(element: JsonElement): String =
        CoreJson.json.encodeToString(JsonElement.serializer(), element)

    private fun parseElement(response: HttpResponse): JsonElement =
        try {
            CoreJson.json.parseToJsonElement(response.bodyAsText())
        } catch (ignored: IllegalArgumentException) {
            throw NetworkError.InvalidResponse
        } catch (ignored: StackOverflowError) {
            throw NetworkError.InvalidResponse
        }

    /**
     * Cualquier fallo de lectura del JSON de un objeto se convierte en un error
     * fijo: el mensaje de las excepciones de la librería cita trozos del
     * cuerpo, que es texto del servidor y no debe llegar a un log.
     */
    private fun <T> decodeObject(serializer: KSerializer<T>, obj: JsonObject): T =
        try {
            CoreJson.json.decodeFromString(serializer, obj.toString())
        } catch (ignored: IllegalArgumentException) {
            throw NetworkError.InvalidResponse
        } catch (ignored: StackOverflowError) {
            throw NetworkError.InvalidResponse
        }

    /**
     * Clasifica un fallo de crear: «no se creó» solo si es anterior al envío o
     * un rechazo definitivo. Un 5xx (proxies incluidos, que pueden cortar
     * mientras Mealie sigue procesando) y un 2xx ilegible son ambiguos.
     */
    private fun creationFailure(error: NetworkError): MealPlanResult<Nothing> = when {
        error.happenedBeforeSend() -> MealPlanErrors.map(error)
        error is NetworkError.HttpError && error.statusCode in DEFINITIVE_CREATION_REJECTIONS ->
            MealPlanErrors.map(error)
        else -> MealPlanResult.MaybeCreated(error.toConnectionProblem())
    }

    /** ¿El fallo prueba que la petición no llegó a salir? Mismo criterio que `MealieClient`. */
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

    /** Ejecuta [block] y traduce cualquier [NetworkError] a un [MealPlanResult]. */
    private inline fun <T> guarded(block: () -> MealPlanResult<T>): MealPlanResult<T> =
        try {
            block()
        } catch (error: NetworkError) {
            MealPlanErrors.map(error)
        }

    companion object {
        const val MEALPLANS_PATH = "/api/households/mealplans"
        const val TODAY_PATH = "/api/households/mealplans/today"

        /** Entradas por página que se piden; el esquema trae 50 por defecto. */
        const val PER_PAGE = 100

        /** Tope de páginas por rango: 20 × 100 entradas cubre de sobra una semana o un año. */
        const val MAX_PAGES = 20

        /** Tope que se acepta como `per_page` del servidor para hacer la aritmética. */
        private const val MAX_PER_PAGE = 10_000

        /**
         * Códigos con los que el servidor ha rechazado la creación de forma
         * definitiva: no se ha creado nada. Mismo conjunto que en `MealieClient`.
         */
        private val DEFINITIVE_CREATION_REJECTIONS = setOf(400, 401, 403, 404, 405, 409, 413, 415, 422)

        /** Las respuestas del planificador son pequeñas; 2 MiB es un techo amplio. */
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        private const val READ_TIMEOUT_MS = 30_000
        private const val TOTAL_TIMEOUT_MS = 60_000L

        /**
         * Cliente con la sesión guardada y el transporte real. Las políticas de
         * URL, de HTTP local y de redirecciones son las de
         * [UrlConnectionTransport.create]: aquí solo se eligen los plazos y el
         * límite de respuesta. Como en `MealieClient`, `allowLocalHttp` y
         * `activeNetwork` se vuelven a leer de los ajustes cada vez que se crea.
         */
        fun create(
            session: Session,
            allowLocalHttp: Boolean = false,
            activeNetwork: ActiveNetworkProvider? = null,
        ): MealPlanClient = MealPlanClient(
            UrlConnectionTransport.create(
                serverUrl = session.serverUrl,
                token = session.token,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                readTimeoutMs = READ_TIMEOUT_MS,
                maxResponseBytes = MAX_RESPONSE_BYTES,
                totalTimeoutMs = TOTAL_TIMEOUT_MS,
            ),
        )
    }
}
