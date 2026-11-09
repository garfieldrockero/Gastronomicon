package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.client.ApiErrors
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import io.github.garfieldrockero.gastronomicon.core.network.HttpRequest
import io.github.garfieldrockero.gastronomicon.core.network.HttpResponse
import io.github.garfieldrockero.gastronomicon.core.network.HttpTransport
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import io.github.garfieldrockero.gastronomicon.core.recipes.ServingScaler
import io.github.garfieldrockero.gastronomicon.core.session.Session
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Cliente de la lista de la compra de Mealie sobre [HttpTransport].
 *
 * Sigue la misma política que `MealieClient`: las rutas las construye el
 * núcleo con ids validados (nunca una URL del servidor), todo texto del
 * servidor pasa por [ShoppingMapper] antes de entrar en un modelo, las
 * respuestas van acotadas por el transporte y por los topes de aquí, y los
 * fallos no llevan token, URL ni cuerpo crudo.
 *
 * Operaciones y endpoints (docs/openapi.json):
 * - `GET /api/households/shopping/lists` ([listLists]).
 * - `POST /api/households/shopping/lists` ([createList]) con
 *   `ShoppingListCreate`; devuelve 201 y el `ShoppingListOut` de la lista.
 * - `GET /api/households/shopping/lists/{item_id}` ([getList]). Devuelve la
 *   lista entera con sus `listItems`; este endpoint no pagina, así que no se
 *   inventa una paginación de elementos.
 * - `POST /api/households/shopping/items` ([addItem]).
 * - `POST /api/households/shopping/lists/{item_id}/recipe` ([addRecipe] y
 *   [addRecipes]): añade los ingredientes de una o varias recetas, con escala.
 *   Se usa esta ruta y no la de una sola receta
 *   (`/recipe/{recipe_id}`) porque el `openapi.json` de Mealie marca la segunda
 *   como `deprecated`; el cuerpo es un array de `ShoppingListAddRecipeParamsBulk`
 *   y la respuesta, `ShoppingListOut`, se ignora: el controlador relee la lista.
 * - `PUT /api/households/shopping/items/{item_id}` ([setChecked], [editItem]).
 * - `DELETE /api/households/shopping/items/{item_id}` ([deleteItem] y
 *   [deleteChecked]).
 *
 * El `PUT` parte del JSON crudo que devolvió el `GET` ([ShoppingItem.rawJson])
 * y cambia solo `checked`, `note` o `quantity`: así no se pierden
 * `recipeReferences`, `extras` ni campos de otra versión de Mealie, y una nota
 * que la persona no ha editado no se recorta ni se aplana.
 *
 * No se usan `PUT /items` ni `DELETE /items?ids=` en bloque: el primero
 * necesita `ShoppingListItemUpdateBulk` y el segundo codifica una lista en la
 * consulta (`ids` repetido), y ninguna de las dos formas está confirmada
 * contra un servidor real. Borrar de uno en uno es más lento pero seguro.
 *
 * Los métodos son síncronos: quien llama decide el hilo.
 */
class ShoppingClient(private val transport: HttpTransport) : ShoppingListSource {

    /** Una página de listas. `next`/`previous` se ignoran y la paginación se calcula aquí. */
    override fun listLists(
        page: Int,
        perPage: Int,
    ): ApiResult<Page<ShoppingListSummary>> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, MAX_LISTS_PER_PAGE)
        return guarded {
            val response = transport.execute(
                HttpRequest(HttpMethod.GET, LISTS_PATH, query = "page=$safePage&perPage=$safePerPage"),
            )
            val dto = decode(ShoppingListPageDto.serializer(), response)
            val items = dto.items.orEmpty().asSequence()
                .filterNotNull()
                .take(MAX_LISTS_PER_PAGE * 2)
                .mapNotNull { ShoppingMapper.summary(it) }
                .take(MAX_LISTS_PER_PAGE)
                .sortedWith(
                    compareBy<ShoppingListSummary>(
                        { it.name.lowercase(Locale.ROOT) },
                        { it.name },
                        { it.id },
                    ),
                )
                .toList()
            ApiResult.Success(
                Page.of(
                    items = items,
                    page = dto.page ?: safePage,
                    perPage = dto.perPage ?: safePerPage,
                    total = dto.total ?: items.size.toLong(),
                ),
            )
        }
    }

    /** Página 1 con el tamaño por defecto: atajo para no romper las llamadas de siempre. */
    fun listLists(): ApiResult<Page<ShoppingListSummary>> =
        listLists(page = 1, perPage = DEFAULT_LISTS_PER_PAGE)

    /**
     * `POST /api/households/shopping/lists` (`ShoppingListCreate`): crea una
     * lista vacía con ese nombre y devuelve su resumen ya saneado.
     *
     * El nombre pasa por [ShoppingText.listNameInput]: vacío o por encima de
     * [ShoppingText.MAX_LIST_NAME] no sale de la app. Es la última barrera,
     * como en [addItem]; el selector de listas valida antes y lo cuenta como
     * `ShoppingListPickerProblem.InputRejected`.
     *
     * No se reintenta nunca. Un fallo posterior al envío del cuerpo es
     * [ApiResult.MaybeCreated]: la lista puede existir y repetir el `POST`
     * dejaría dos. Igual que al crear una receta, un 2xx con un id que no se
     * puede leer también es dudoso.
     *
     * @throws IllegalArgumentException si el nombre queda vacío o pasa del tope.
     */
    override fun createList(name: String): ApiResult<ShoppingListSummary> {
        val input = ShoppingText.listNameInput(name)
        if (input.problem != null) {
            throw IllegalArgumentException("El nombre de la lista de la compra no es válido.")
        }
        return guardedCreate {
            val body = encode(
                ShoppingListCreateDto.serializer(),
                ShoppingListCreateDto(name = input.text),
            )
            val root = parseObject(
                transport.execute(HttpRequest.json(HttpMethod.POST, LISTS_PATH, body, readErrorBody = true)),
            )
            val dto = decodeObject(ShoppingListSummaryDto.serializer(), root)
            val summary = ShoppingMapper.summary(dto) ?: throw NetworkError.InvalidResponse
            ApiResult.Success(summary)
        }
    }

    /**
     * La lista con todos sus elementos, ya saneados, sin ids repetidos y
     * acotada a [ShoppingListState.MAX_ITEMS]. Cada elemento se queda además
     * con su JSON crudo para poder reenviarlo al editar.
     *
     * @throws IllegalArgumentException si [listId] no es un UUID.
     */
    override fun getList(listId: String): ApiResult<ShoppingList> {
        val checked = ShoppingIds.checked(listId)
        return guarded {
            val root = parseObject(transport.execute(HttpRequest(HttpMethod.GET, "$LISTS_PATH/$checked")))
            val dto = decodeObject(ShoppingListDto.serializer(), root)
            val rawItems = (root["listItems"] as? JsonArray)?.toList().orEmpty()
            ApiResult.Success(ShoppingMapper.list(dto, checked, rawItems))
        }
    }

    /**
     * Añade una nota de texto libre. El nombre y la cantidad quedan como los
     * ponga el servidor: la app no inventa alimento, unidad ni cantidad para
     * una nota.
     *
     * Un fallo posterior al envío del cuerpo es [ApiResult.MaybeCreated]: el
     * elemento puede estar ya en la lista y repetir el `POST` lo duplicaría.
     *
     * @throws IllegalArgumentException si el id no es un UUID o el texto queda
     *   vacío o pasa del tope después de sanearlo. El controlador valida
     *   antes; aquí es la última barrera antes de que salga algo.
     */
    override fun addItem(listId: String, text: String): ApiResult<Unit> {
        val checked = ShoppingIds.checked(listId)
        val input = ShoppingText.item(text)
        if (input.problem != null) {
            throw IllegalArgumentException("El texto del elemento de la compra no es válido.")
        }
        val body = encode(
            ShoppingItemCreateDto.serializer(),
            ShoppingItemCreateDto(shoppingListId = checked, note = input.text),
        )
        return guardedCreate {
            transport.execute(HttpRequest.json(HttpMethod.POST, ITEMS_PATH, body, readErrorBody = true))
            ApiResult.Success(Unit)
        }
    }

    /**
     * Añade los ingredientes de la receta [recipeId], escalados por [scale].
     * Atajo de [addRecipes] con una sola receta.
     *
     * @throws IllegalArgumentException si la lista o la receta no son UUID o la
     *   escala no está en el rango del núcleo.
     */
    override fun addRecipe(listId: String, recipeId: String, scale: Double): ApiResult<Unit> =
        addRecipes(listId, listOf(RecipeAddition(recipeId, scale)))

    /**
     * Añade los ingredientes de [additions] en una sola petición
     * (`POST /api/households/shopping/lists/{item_id}/recipe`), que el servidor
     * escala con `recipeIncrementQuantity`.
     *
     * No se lee la respuesta (`ShoppingListOut`): quien llama relee la lista
     * para no inventar ids ni duplicar líneas. Los `recipeId` repetidos se
     * fusionan sumando sus escalas ([RecipeAdditions.merge]) antes de validar y
     * de componer el cuerpo. Un fallo posterior al envío del cuerpo es
     * [ApiResult.MaybeCreated], porque repetir el `POST` volvería a
     * meter los mismos ingredientes. Nunca se reintenta solo.
     *
     * @throws IllegalArgumentException si la lista o alguna receta no son UUID,
     *   si la lista de recetas está vacía, si hay más recetas **distintas** que
     *   el tope ([RecipeAdditions.MAX_RECIPES_PER_OPERATION]) o si alguna escala
     *   (también la suma de un id repetido) se sale del rango del núcleo. El
     *   controlador valida antes; aquí es la última barrera antes de que salga
     *   algo.
     */
    override fun addRecipes(listId: String, additions: List<RecipeAddition>): ApiResult<Unit> {
        val checked = ShoppingIds.checked(listId)
        // Los ids repetidos se fusionan sumando sus escalas: una tanda con la
        // misma receta dos veces no puede dejar dos líneas de sus ingredientes.
        val merged = RecipeAdditions.merge(additions)
        if (RecipeAdditions.check(merged) != null) {
            throw IllegalArgumentException("Las recetas para la lista de la compra no son válidas.")
        }
        // La ruta valida cada id, pero se normalizan antes de mandarlos para que
        // el cuerpo lleve exactamente los mismos UUID que la ruta.
        val body = merged.map { RecipeAddition(ShoppingIds.checked(it.recipeId), it.scale) }
        val json = encode(ListSerializer(ShoppingListAddRecipeDto.serializer()), body.map {
            ShoppingListAddRecipeDto(recipeId = it.recipeId, recipeIncrementQuantity = it.scale)
        })
        return guardedCreate {
            transport.execute(
                HttpRequest.json(HttpMethod.POST, "$LISTS_PATH/$checked/recipe", json, readErrorBody = true),
            )
            ApiResult.Success(Unit)
        }
    }

    /**
     * Marca o desmarca con un `PUT` sobre el JSON crudo del elemento: solo
     * cambia `checked`. Todo lo demás (nota con sus saltos de línea, unidad,
     * alimento, referencias de receta y claves que la app no conoce) sale tal
     * cual lo mandó el servidor.
     *
     * @throws IllegalArgumentException si algún id del elemento no es un UUID.
     */
    override fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit> =
        update(item, checked = checked, note = null, quantity = null)

    /**
     * Cambia la nota y la cantidad. La nota se sanea aquí otra vez; una nota
     * vacía es válida si el elemento tiene alimento o unidad.
     *
     * @throws IllegalArgumentException si la nota pasa del tope o la cantidad
     *   no está entre 0 y [ServingScaler.MAX_QUANTITY].
     */
    override fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit> {
        val input = ShoppingText.item(note)
        if (input.problem == ShoppingInputProblem.TextTooLong) {
            throw IllegalArgumentException("La nota del elemento de la compra es demasiado larga.")
        }
        if (!validQuantity(quantity)) {
            throw IllegalArgumentException("La cantidad del elemento de la compra no es válida.")
        }
        // El marcado se reenvía con el valor que conoce la app: si el JSON crudo
        // venía de antes de un toque, no puede revertirlo.
        return update(item, checked = item.checked, note = input.text, quantity = quantity)
    }

    /**
     * `DELETE /api/households/shopping/items/{item_id}`.
     *
     * @throws IllegalArgumentException si [itemId] no es un UUID.
     */
    override fun deleteItem(itemId: String): ApiResult<Unit> {
        val checked = ShoppingIds.checked(itemId)
        return guarded {
            transport.execute(HttpRequest(HttpMethod.DELETE, "$ITEMS_PATH/$checked"))
            ApiResult.Success(Unit)
        }
    }

    /**
     * Borra los elementos recibidos de uno en uno. Un 404 cuenta como ya
     * borrado y se sigue con el siguiente. Si uno falla de otra forma, se para
     * y se devuelve ese fallo: los anteriores ya no están en el servidor, así
     * que quien llama tiene que releer la lista antes de dar el borrado por
     * bueno.
     */
    override fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit> {
        for (item in items) {
            val result = deleteItem(item.id)
            if (result is ApiResult.Success || result is ApiResult.NotFound) continue
            return result
        }
        return ApiResult.Success(Unit)
    }

    // ------------------------------------------------------------- privados

    /**
     * `PUT` de un elemento. Solo se toca lo que llega no nulo; el resto del
     * cuerpo es el JSON crudo de la lectura.
     */
    private fun update(
        item: ShoppingItem,
        checked: Boolean?,
        note: String?,
        quantity: Double?,
    ): ApiResult<Unit> {
        val itemId = ShoppingIds.checked(item.id)
        val listId = ShoppingIds.checked(item.shoppingListId)
        if (quantity != null && !validQuantity(quantity)) {
            throw IllegalArgumentException("La cantidad del elemento de la compra no es válida.")
        }
        val body = updateBody(item, listId, checked, note, quantity)
        val json = encode(JsonElement.serializer(), body)
        return guarded {
            transport.execute(HttpRequest.json(HttpMethod.PUT, "$ITEMS_PATH/$itemId", json, readErrorBody = true))
            ApiResult.Success(Unit)
        }
    }

    /**
     * Cuerpo del `PUT`: el JSON crudo del elemento con solo `checked`, `note` o
     * `quantity` sustituidos. Sin JSON crudo (elemento creado por la app o
     * desmesurado) se compone un cuerpo mínimo con lo que se conoce; es la
     * única forma de no enviar a ciegas, y se documenta como pérdida posible de
     * campos ajenos.
     */
    private fun updateBody(
        item: ShoppingItem,
        listId: String,
        checked: Boolean?,
        note: String?,
        quantity: Double?,
    ): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        val raw = item.rawJson
        if (raw == null) {
            fields["shoppingListId"] = JsonPrimitive(listId)
            fields["note"] = JsonPrimitive(item.note)
            fields["quantity"] = JsonPrimitive(item.quantity)
            fields["checked"] = JsonPrimitive(item.checked)
            fields["position"] = JsonPrimitive(item.position)
            item.labelId?.let { fields["labelId"] = JsonPrimitive(it) }
            item.foodId?.let { fields["foodId"] = JsonPrimitive(it) }
            item.unitId?.let { fields["unitId"] = JsonPrimitive(it) }
        } else {
            fields.putAll(raw)
            // El esquema exige `shoppingListId`; solo se añade si el servidor no
            // lo mandó (no se reescribe el suyo).
            if (!fields.containsKey("shoppingListId")) fields["shoppingListId"] = JsonPrimitive(listId)
        }
        checked?.let { fields["checked"] = JsonPrimitive(it) }
        note?.let { fields["note"] = JsonPrimitive(it) }
        quantity?.let { fields["quantity"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun validQuantity(quantity: Double): Boolean =
        quantity.isFinite() && quantity >= 0.0 && quantity <= ServingScaler.MAX_QUANTITY

    /** Ejecuta [block] y traduce cualquier [NetworkError] a un [ApiResult]. */
    private inline fun <T> guarded(block: () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (error: NetworkError) {
            ApiErrors.map(error)
        }

    /**
     * Como [guarded], pero para crear: un fallo ambiguo después de enviar el
     * cuerpo no se puede contar como «no se creó», porque repetir dejaría dos
     * elementos. Se avisa con [ApiResult.MaybeCreated] para que la interfaz
     * pida mirar la lista antes de reintentar.
     */
    private inline fun <T> guardedCreate(block: () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (error: NetworkError.AfterSend) {
            ApiResult.MaybeCreated(error.original.toConnectionProblem())
        } catch (error: NetworkError) {
            creationFailure(error)
        }

    /**
     * «No se creó» solo si el fallo es anterior al envío o si el servidor ha
     * contestado un rechazo definitivo. Lo demás es ambiguo: un proxy puede
     * contestar 502 mientras Mealie sigue procesando el cuerpo.
     */
    private fun creationFailure(error: NetworkError): ApiResult<Nothing> = when {
        error.happenedBeforeSend() -> ApiErrors.map(error)
        error is NetworkError.HttpError && error.statusCode in DEFINITIVE_CREATION_REJECTIONS ->
            ApiErrors.map(error)
        else -> ApiResult.MaybeCreated(error.toConnectionProblem())
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

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        CoreJson.json.encodeToString(serializer, value)

    /**
     * Cualquier fallo de JSON se convierte en [NetworkError.InvalidResponse]:
     * el mensaje de la librería cita trozos del cuerpo, que es texto del
     * servidor y no debe llegar a un log.
     */
    private fun <T> decode(serializer: KSerializer<T>, response: HttpResponse): T =
        try {
            CoreJson.json.decodeFromString(serializer, response.bodyAsText())
        } catch (ignored: IllegalArgumentException) {
            throw NetworkError.InvalidResponse
        } catch (ignored: StackOverflowError) {
            throw NetworkError.InvalidResponse
        }

    /** El cuerpo como objeto JSON; cualquier fallo es [NetworkError.InvalidResponse] sin citar el cuerpo. */
    private fun parseObject(response: HttpResponse): JsonObject =
        try {
            CoreJson.json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: throw NetworkError.InvalidResponse
        } catch (ignored: IllegalArgumentException) {
            throw NetworkError.InvalidResponse
        } catch (ignored: StackOverflowError) {
            throw NetworkError.InvalidResponse
        }

    private fun <T> decodeObject(serializer: KSerializer<T>, obj: JsonObject): T =
        try {
            CoreJson.json.decodeFromString(serializer, obj.toString())
        } catch (ignored: IllegalArgumentException) {
            throw NetworkError.InvalidResponse
        } catch (ignored: StackOverflowError) {
            throw NetworkError.InvalidResponse
        }

    companion object {
        const val LISTS_PATH = "/api/households/shopping/lists"
        const val ITEMS_PATH = "/api/households/shopping/items"

        /** Elementos por página al listar listas (el servidor usa 50 por defecto). */
        const val DEFAULT_LISTS_PER_PAGE = 50

        /** Tope de listas por página, aunque el servidor mande más. */
        const val MAX_LISTS_PER_PAGE = 100

        /** Tope del cuerpo de una lista: una compra de casa no llega ni de lejos. */
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        /**
         * Códigos con los que el servidor ha rechazado la petición de forma
         * definitiva: no se ha creado nada. Mismo conjunto que en `MealieClient`.
         */
        private val DEFINITIVE_CREATION_REJECTIONS = setOf(400, 401, 403, 404, 405, 409, 413, 415, 422)

        /**
         * Cliente para la sesión guardada, con el transporte real. La política
         * de URL, de HTTP local y de redirecciones es la de
         * [UrlConnectionTransport.create]; [allowLocalHttp] y [activeNetwork]
         * se vuelven a leer de los ajustes cada vez que se crea.
         */
        fun create(
            session: Session,
            allowLocalHttp: Boolean = false,
            activeNetwork: ActiveNetworkProvider? = null,
            maxResponseBytes: Long = MAX_RESPONSE_BYTES,
        ): ShoppingClient = ShoppingClient(
            UrlConnectionTransport.create(
                serverUrl = session.serverUrl,
                token = session.token,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                maxResponseBytes = maxResponseBytes,
            ),
        )
    }
}
