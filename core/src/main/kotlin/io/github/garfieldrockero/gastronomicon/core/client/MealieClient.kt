package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.SafeUrl
import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import io.github.garfieldrockero.gastronomicon.core.network.HttpRequest
import io.github.garfieldrockero.gastronomicon.core.network.HttpResponse
import io.github.garfieldrockero.gastronomicon.core.network.HttpTransport
import io.github.garfieldrockero.gastronomicon.core.network.MultipartBody
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftErrorKind
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import io.github.garfieldrockero.gastronomicon.core.recipes.IngredientFood
import io.github.garfieldrockero.gastronomicon.core.recipes.IngredientUnit
import io.github.garfieldrockero.gastronomicon.core.recipes.Organizer
import io.github.garfieldrockero.gastronomicon.core.recipes.OrganizerKind
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import io.github.garfieldrockero.gastronomicon.core.recipes.PercentEncoding
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftValidation
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageMetadata
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageSize
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListQuery
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummary
import io.github.garfieldrockero.gastronomicon.core.session.Session
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/** Resultado de «Probar conexión»: o la versión del servidor, o el motivo ya reducido. */
sealed interface ConnectionCheck {
    /** [serverVersion] es texto saneado de `AppInfo.version` (p. ej. «v3.29.0»). */
    data class Success(val serverVersion: String) : ConnectionCheck

    data class Failure(val problem: ConnectionProblem) : ConnectionCheck
}

/**
 * La cuenta con la que se habla, reducida a su `id`.
 *
 * Es lo único que la app necesita de `/api/users/self`: el ámbito de los datos
 * offline ([io.github.garfieldrockero.gastronomicon.core.offline.ServerScope])
 * se compone de la URL y de este id, para que dos cuentas del mismo servidor no
 * compartan caché. El id **no es un secreto** (lo es el token, que aquí no
 * entra), pero no hace falta en un log: [toString] no lo enseña.
 *
 * Solo se construye con un id ya validado por el núcleo: es un UUID en
 * minúsculas.
 */
class CurrentUser internal constructor(val id: String) {
    override fun toString(): String = "CurrentUser(***)"
}

/**
 * Cliente de lectura de Mealie sobre [HttpTransport].
 *
 * Tiene dos transportes del mismo servidor por una razón de seguridad:
 * [anonymousTransport] no manda nunca el token y es el único que habla con
 * `/api/app/about`, de modo que a una dirección que resulta no ser un Mealie
 * no le llega el secreto. [transport] lleva el token y es el de todo lo demás.
 *
 * Los métodos son síncronos (ver [HttpTransport]): quien llama decide el hilo.
 *
 * Errores: [checkConnection] nunca lanza por red, devuelve [ConnectionCheck].
 * El resto lanza [NetworkError] (código HTTP incluido: 401, 404, 422, 5xx) y
 * [NetworkError.InvalidResponse] si el cuerpo no es el JSON esperado. Esos
 * mensajes son fijos y no llevan token, URL ni cuerpo de la respuesta.
 *
 * Las operaciones de escritura, importar, subir imagen, listar organizadores,
 * el catálogo de unidades y alimentos (más abajo) y [currentUser] no lanzan por
 * red: devuelven [ApiResult]. El cuerpo de un 422 se lee solo ahí, con límite
 * de bytes, y se entrega saneado y acotado ([ValidationDetail]). Ninguna de las
 * dos formas lleva token, URL ni cuerpo crudo.
 */
class MealieClient(
    private val transport: HttpTransport,
    private val anonymousTransport: HttpTransport,
    /**
     * Transporte con token y límite de respuesta pequeño, solo para
     * `/api/users/self` en [checkConnection]. Por defecto es [transport].
     */
    private val selfTransport: HttpTransport = transport,
    /**
     * Transporte con token y con el límite de [MAX_IMAGE_BYTES], solo para
     * [fetchImage]. Por defecto es [transport] (y entonces manda su límite).
     */
    private val imageTransport: HttpTransport = transport,
    /**
     * Transporte con token y plazos más largos, solo para [importFromUrl]: el
     * servidor descarga y analiza la página antes de contestar. Por defecto
     * es [transport].
     */
    private val importTransport: HttpTransport = transport,
    /**
     * Transporte con token y un plazo total propio, solo para [uploadImage].
     * Por defecto es [transport], que ya trae su plazo; [create] le pone
     * [UPLOAD_TOTAL_TIMEOUT_MS] explícito.
     */
    private val uploadTransport: HttpTransport = transport,
) : RecipeListSource, RecipeDetailSource {

    /**
     * Dos pasos: `GET /api/app/about` sin token (¿es un Mealie?) y después
     * `GET /api/users/self` con token (¿vale?). Así «dirección mal» y «token
     * mal» no se confunden, que es la diferencia entre
     * [ConnectionProblem.InvalidAddress] y [ConnectionProblem.Unauthorized].
     *
     * Límite de seguridad: la comprobación de `about` frena erratas de dirección
     * (un dominio equivocado, un servicio que no es Mealie), no a un servidor
     * hostil. Cualquier servidor que devuelva la forma de `AppInfo` (`version` y
     * `production`) la pasa y, a continuación, recibe el token en `users/self`.
     * La protección real contra eso es la URL que la persona escribe y confirma,
     * HTTPS con certificado válido y que el token no cambie de origen sin probar
     * (ver `SaveSessionPolicy`).
     */
    fun checkConnection(): ConnectionCheck {
        val aboutResponse = try {
            anonymousTransport.execute(HttpRequest(HttpMethod.GET, ABOUT_PATH))
        } catch (error: NetworkError) {
            return ConnectionCheck.Failure(aboutProblem(error))
        }
        val version = readServerVersion(aboutResponse)
            ?: return ConnectionCheck.Failure(ConnectionProblem.InvalidAddress)

        // Con un 2xx basta: el cuerpo es un UserOut con la lista de tokens de
        // la persona y no hay motivo para leerlo. Por eso va por un transporte
        // con límite pequeño: se descarta y no debe costar 2 MiB. Si se pasa
        // del límite, el transporte lanza ResponseTooLarge (-> Unknown).
        try {
            selfTransport.execute(HttpRequest(HttpMethod.GET, SELF_PATH))
        } catch (error: NetworkError) {
            return ConnectionCheck.Failure(error.toConnectionProblem())
        }
        return ConnectionCheck.Success(version)
    }

    /**
     * La cuenta con la que se habla, solo por su `id`.
     *
     * `GET /api/users/self` devuelve un `UserOut` con correo, grupo, hogar y la
     * lista de tokens; aquí solo se lee `id` y se descarta el resto sin mirarlo.
     * El id se valida como UUID y se pasa a minúsculas ([RecipeImage.normalizeId]);
     * si no tiene esa forma se falla cerrado ([ApiResult.Failure]) y no hay
     * ámbito offline, en vez de usar un identificador inventado.
     *
     * No lanza por red: todo desenlace es un [ApiResult]. Ni el resultado ni sus
     * mensajes llevan el token ni el cuerpo de la respuesta.
     */
    fun currentUser(): ApiResult<CurrentUser> {
        val response = try {
            selfTransport.execute(HttpRequest(HttpMethod.GET, SELF_PATH))
        } catch (error: NetworkError) {
            return ApiErrors.map(error)
        }
        val dto = try {
            decode(UserSelfDto.serializer(), response)
        } catch (error: NetworkError) {
            return ApiErrors.map(error)
        }
        val id = RecipeImage.normalizeId(dto.id)
            ?: return ApiResult.Failure(ConnectionProblem.Unknown)
        return ApiResult.Success(CurrentUser(id))
    }

    /**
     * Una página de recetas. La consulta la construye el núcleo
     * ([RecipeListQuery]) y los `next`/`previous` del servidor se ignoran:
     * son URLs ajenas y la paginación se calcula con `page` y `perPage`.
     *
     * Las filas con un slug fuera de la lista blanca se descartan (no se
     * podrían abrir sin riesgo), así que una página puede traer menos filas
     * que `perPage`.
     */
    override fun listRecipes(query: RecipeListQuery): Page<RecipeSummary> {
        val dto = decode(RecipePageDto.serializer(), transport.execute(query.toRequest()))
        val items = dto.items.orEmpty().asSequence()
            .filterNotNull()
            // El tope evita que un servidor que ignora perPage llene la memoria.
            .take(RecipeListQuery.MAX_PER_PAGE)
            .mapNotNull { RecipeMapper.summary(it) }
            .toList()
        return Page.of(
            items = items,
            page = dto.page ?: query.page,
            perPage = dto.perPage ?: query.perPage,
            total = dto.total ?: items.size.toLong(),
        )
    }

    /**
     * Una receta completa por su slug.
     *
     * @throws IllegalArgumentException si [slug] no pasa la lista blanca de
     *   ruta (antes de enviar nada).
     */
    override fun getRecipe(slug: String): Recipe {
        val checked = RecipeSlug.checked(slug)
        val response = transport.execute(HttpRequest(HttpMethod.GET, "$RECIPES_PATH/$checked"))
        return RecipeMapper.recipe(decode(RecipeDto.serializer(), response), checked)
    }

    /**
     * La imagen de una receta, por su **id** (no el slug), con el transporte
     * autenticado. No lanza por red: todo desenlace es un [ImageResult].
     *
     * - Un id que no es un UUID da [ImageResult.NotFound] sin enviar nada.
     * - 404 es [ImageResult.NotFound]; 401 y 403, [ImageResult.Unauthorized].
     * - Solo `image/webp`, `image/jpeg` e `image/png`, y además los primeros
     *   bytes tienen que ser de ese mismo formato (nunca SVG ni otro): si no,
     *   [ImageResult.InvalidImage]. Un cuerpo vacío también lo es.
     * - Más de [MAX_IMAGE_BYTES] es [ImageResult.TooLarge], lo corte el
     *   transporte o este método.
     * - El resultado no lleva ni token, ni URL del servidor, ni cuerpos de error.
     */
    fun fetchImage(recipeId: String?, size: ImageSize = ImageSize.Min): ImageResult {
        val request = RecipeImage.request(recipeId, size) ?: return ImageResult.NotFound
        val response = try {
            imageTransport.execute(request)
        } catch (error: NetworkError) {
            return imageFailure(error)
        }
        val body = response.body
        if (body.isEmpty()) return ImageResult.InvalidImage
        if (body.size > MAX_IMAGE_BYTES) return ImageResult.TooLarge
        val declared = ImageTypes.fromContentType(response.header("content-type"))
            ?: return ImageResult.InvalidImage
        val real = ImageTypes.fromMagic(body) ?: return ImageResult.InvalidImage
        if (declared != real) return ImageResult.InvalidImage
        return ImageResult.Success(body, real)
    }

    // ------------------------------------------------------------ escritura

    /**
     * `POST /api/recipes`: crea una receta vacía con ese nombre y devuelve su
     * slug (ya validado con la lista blanca). El nombre se sanea y se valida
     * igual que el del borrador.
     *
     * No se reintenta nunca. La regla de «¿se creó?» es la de
     * [importFromUrl]: solo un rechazo DEFINITIVO del servidor (400, 401, 403,
     * 404, 405, 409, 413, 415 y 422) o un fallo anterior al envío permiten
     * afirmar que no se creó. Un 5xx, una respuesta demasiado grande, un 2xx
     * con un slug ausente o fuera de la lista blanca y un plazo o un corte de
     * conexión después de enviar el cuerpo devuelven [ApiResult.MaybeCreated]:
     * la receta puede existir y hay que mirar la lista antes de repetir.
     */
    fun createRecipe(name: String): ApiResult<String> {
        val (clean, errors) = RecipeDraftValidator.validateName(name)
        if (clean == null) return ApiResult.DraftInvalid(errors)
        return guardedCreate {
            val body = JsonObject(mapOf("name" to JsonPrimitive(clean)))
            val response = transport.execute(
                HttpRequest.json(HttpMethod.POST, RECIPES_PATH, encode(body), readErrorBody = true),
            )
            ApiResult.Success(readSlug(response))
        }
    }

    /**
     * Borrador para el formulario de edición, leído del servidor (`GET`) con
     * las mismas reglas de saneado que usa [updateRecipe] para decidir qué se
     * ha editado. Lleva los ids de línea que permiten conservar lo que el
     * formulario no muestra.
     *
     * @throws IllegalArgumentException si [slug] no pasa la lista blanca.
     */
    fun loadDraft(slug: String): ApiResult<RecipeDraft> {
        val checked = RecipeSlug.checked(slug)
        return guarded {
            val original = readObject(transport.execute(HttpRequest(HttpMethod.GET, "$RECIPES_PATH/$checked")))
            ApiResult.Success(RecipeDraftJson.toDraft(original))
        }
    }

    /**
     * Guarda [draft] sobre la receta [slug] con un solo `PUT` (sin `PATCH`):
     * 1. valida y sanea el borrador (si falla, [ApiResult.DraftInvalid] y no se
     *    envía nada);
     * 2. pide el `GET` entero y lo trata como `JsonObject`, sin pasar por DTO,
     *    para no perder ni inventar campos;
     * 3. sustituye SOLO las claves editadas y quita las marcas de tiempo que
     *    rellena Mealie (ver [RecipeDraftJson]);
     * 4. envía el `PUT` y, como su respuesta no tiene esquema, vuelve a pedir
     *    el `GET` para devolver la receta guardada.
     *
     * Si el nombre cambia, Mealie puede cambiar el slug: el `slug` de la
     * [Recipe] devuelta es el que hay que usar a partir de ahora. Si el `PUT`
     * se acepta pero la relectura falla, el resultado es
     * [ApiResult.SavedButNotReloaded] (los cambios sí están guardados).
     *
     * Entre el `GET` y el `PUT` no hay control de concurrencia: si otra persona
     * edita a la vez, gana el último en guardar.
     *
     * @throws IllegalArgumentException si [slug] no pasa la lista blanca.
     */
    fun updateRecipe(slug: String, draft: RecipeDraft): ApiResult<Recipe> {
        val checked = RecipeSlug.checked(slug)
        val clean = when (val validation = RecipeDraftValidator.validate(draft)) {
            is DraftValidation.Valid -> validation.draft
            is DraftValidation.Invalid -> return ApiResult.DraftInvalid(validation.errors)
        }
        val path = "$RECIPES_PATH/$checked"
        val original = try {
            readObject(transport.execute(HttpRequest(HttpMethod.GET, path)))
        } catch (error: NetworkError) {
            return ApiErrors.map(error)
        }
        val body = RecipeDraftJson.merge(original, clean)
        val saved = try {
            transport.execute(HttpRequest.json(HttpMethod.PUT, path, encode(body), readErrorBody = true))
        } catch (error: NetworkError) {
            return ApiErrors.map(error)
        }
        val reloadSlug = slugFromUpdateResponse(saved) ?: checked
        return try {
            ApiResult.Success(getRecipe(reloadSlug))
        } catch (error: NetworkError) {
            ApiResult.SavedButNotReloaded(error.toConnectionProblem())
        }
    }

    /**
     * `DELETE /api/recipes/{slug}`. El cuerpo de la respuesta (sin esquema
     * fiable) se descarta.
     *
     * @throws IllegalArgumentException si [slug] no pasa la lista blanca.
     */
    fun deleteRecipe(slug: String): ApiResult<Unit> {
        val checked = RecipeSlug.checked(slug)
        return guarded {
            transport.execute(HttpRequest(HttpMethod.DELETE, "$RECIPES_PATH/$checked"))
            ApiResult.Success(Unit)
        }
    }

    /**
     * `POST /api/recipes/create/url`: el SERVIDOR descarga la página; la app no
     * toca esa URL. Solo se admite `https` (con [SafeUrl]: sin usuario, sin
     * espacios, ASCII) y, si no cumple, [ApiResult.InputRejected] sin enviar
     * nada. Devuelve el slug de la receta creada, validado.
     *
     * No se reintenta nunca (y menos aquí, que el servidor tarda en descargar
     * la página). Una vez enviado el cuerpo, solo un rechazo DEFINITIVO (400,
     * 401, 403, 404, 405, 409, 413, 415 y 422) prueba que no se creó. Todo lo
     * demás es [ApiResult.MaybeCreated]: un 5xx (también el 500/502/503/504 de
     * un proxy, que puede cortar mientras Mealie sigue importando), una
     * respuesta demasiado grande, un 2xx sin slug legible y un plazo o un
     * corte de conexión. Solo lo anterior al envío (URL inválida, sin red,
     * error al conectar) es un [ApiResult.Failure] de «no se creó».
     */
    fun importFromUrl(
        url: String,
        includeTags: Boolean = false,
        includeCategories: Boolean = false,
    ): ApiResult<String> {
        val safe = SafeUrl.httpOrNull(url)
        if (safe == null || !safe.startsWith("https://", ignoreCase = true)) {
            return ApiResult.InputRejected(InputProblem.InvalidUrl)
        }
        return guardedCreate {
            val body = JsonObject(
                mapOf(
                    "url" to JsonPrimitive(safe),
                    "includeTags" to JsonPrimitive(includeTags),
                    "includeCategories" to JsonPrimitive(includeCategories),
                ),
            )
            val response = importTransport.execute(
                HttpRequest.json(HttpMethod.POST, "$RECIPES_PATH/create/url", encode(body), readErrorBody = true),
            )
            ApiResult.Success(readSlug(response))
        }
    }

    /**
     * `PUT /api/recipes/{slug}/image` (multipart: `image` y `extension`).
     *
     * El recodificado y el borrado de EXIF los hace `app/`; aquí, como defensa
     * en profundidad, se rechaza lo que no sea una imagen: extensión distinta
     * de `webp`/`jpeg`/`png`, bytes cuyo inicio (bytes mágicos) no sea de ese
     * mismo formato, vacía, de más de [MAX_IMAGE_BYTES] o que aún lleve
     * metadatos ([ImageMetadata.hasMetadata]: EXIF, XMP, IPTC, comentarios,
     * texto en PNG y bits de VP8X, además de un fichero truncado o con datos
     * después del fin de imagen).
     *
     * Usa [uploadTransport], con un plazo total de [UPLOAD_TOTAL_TIMEOUT_MS]
     * para escribir el cuerpo y leer la respuesta. El plazo se mira entre
     * bloques de escritura (HttpURLConnection no tiene tiempo de espera de
     * escritura), así que una escritura ya bloqueada puede alargarlo; el
     * detalle está en el KDoc de `UrlConnectionTransport`. Al vencer, el
     * resultado es [ApiResult.Failure] con
     * [ConnectionProblem.ServerNotResponding], no un cuelgue.
     *
     * @throws IllegalArgumentException si [slug] no pasa la lista blanca.
     */
    fun uploadImage(slug: String, bytes: ByteArray, extension: String): ApiResult<Unit> {
        val checked = RecipeSlug.checked(slug)
        val wanted = ImageType.values().firstOrNull { typeExtension(it) == extension.trim().lowercase(Locale.ROOT) }
            ?: return ApiResult.InputRejected(InputProblem.UnsupportedImageType)
        if (bytes.isEmpty()) return ApiResult.InputRejected(InputProblem.InvalidImage)
        if (bytes.size > MAX_IMAGE_BYTES) return ApiResult.InputRejected(InputProblem.ImageTooLarge)
        if (ImageTypes.fromMagic(bytes) != wanted) return ApiResult.InputRejected(InputProblem.InvalidImage)
        if (ImageMetadata.hasMetadata(bytes)) return ApiResult.InputRejected(InputProblem.ImageHasMetadata)
        val ext = typeExtension(wanted)
        val multipart = MultipartBody.build(
            listOf(
                MultipartBody.Part.File("image", "image.$ext", wanted.mimeType, bytes),
                MultipartBody.Part.Field("extension", ext),
            ),
        )
        return guarded {
            uploadTransport.execute(
                HttpRequest.multipart(HttpMethod.PUT, "$RECIPES_PATH/$checked/image", multipart, readErrorBody = true),
            )
            ApiResult.Success(Unit)
        }
    }

    // ------------------------------------------------------- organizadores

    /** Categorías del servidor para el formulario. Ver [listOrganizers]. */
    fun listCategories(
        page: Int = 1,
        perPage: Int = MAX_ORGANIZERS_PER_PAGE,
        search: String? = null,
    ): ApiResult<Page<Organizer>> =
        listOrganizers(OrganizerKind.Categories, page, perPage, search)

    /** Etiquetas del servidor para el formulario. Ver [listOrganizers]. */
    fun listTags(
        page: Int = 1,
        perPage: Int = MAX_ORGANIZERS_PER_PAGE,
        search: String? = null,
    ): ApiResult<Page<Organizer>> =
        listOrganizers(OrganizerKind.Tags, page, perPage, search)

    /**
     * Una página de `GET /api/organizers/categories` o `/tags`, por nombre
     * ascendente. Como en las recetas, `next`/`previous` se ignoran y la
     * paginación la calcula [Page]. Se descartan las entradas sin id (UUID),
     * slug válido o nombre.
     */
    fun listOrganizers(
        kind: OrganizerKind,
        page: Int = 1,
        perPage: Int = MAX_ORGANIZERS_PER_PAGE,
        search: String? = null,
    ): ApiResult<Page<Organizer>> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, MAX_ORGANIZERS_PER_PAGE)
        val term = PlainTextSanitizer.sanitize(search, ORGANIZER_SEARCH_MAX).ifEmpty { null }
        val query = buildString {
            append("page=").append(safePage)
            append("&perPage=").append(safePerPage)
            term?.let { append("&search=").append(PercentEncoding.encode(it)) }
            append("&orderBy=name&orderDirection=asc")
        }
        return guarded {
            val dto = decode(
                OrganizerPageDto.serializer(),
                transport.execute(HttpRequest(HttpMethod.GET, kind.path, query = query)),
            )
            val items = dto.items.orEmpty().asSequence()
                .filterNotNull()
                .take(OrganizerMapper.MAX_ITEMS)
                .mapNotNull { OrganizerMapper.organizer(it) }
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

    // ------------------------------------------- catálogo de unidades y alimentos

    /**
     * Una página de `GET /api/units` para elegir la unidad de un ingrediente.
     *
     * - Se pide con `page`, `perPage` y `search` (saneado y acotado); la
     *   paginación la calcula [Page] y `next`/`previous` del servidor se
     *   ignoran, como en las recetas.
     * - **No se manda `orderBy`**: el catálogo no documenta qué nombres de
     *   campo admite y no se inventa; el orden es el que decida el servidor.
     * - Lo que no tiene id con forma de UUID o nombre se descarta, y una
     *   página nunca trae más de [MAX_CATALOG_PER_PAGE] entradas aunque el
     *   servidor ignore `perPage`.
     */
    fun listUnits(
        page: Int = 1,
        perPage: Int = MAX_CATALOG_PER_PAGE,
        search: String? = null,
    ): ApiResult<Page<IngredientUnit>> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, MAX_CATALOG_PER_PAGE)
        val term = PlainTextSanitizer.sanitize(search, CATALOG_SEARCH_MAX).ifEmpty { null }
        val query = buildString {
            append("page=").append(safePage)
            append("&perPage=").append(safePerPage)
            term?.let { append("&search=").append(PercentEncoding.encode(it)) }
        }
        return guarded {
            val dto = decode(
                IngredientUnitPageDto.serializer(),
                transport.execute(HttpRequest(HttpMethod.GET, UNITS_PATH, query = query)),
            )
            val items = dto.items.orEmpty().asSequence()
                .filterNotNull()
                .take(MAX_CATALOG_PER_PAGE)
                .mapNotNull { IngredientCatalogMapper.unit(it) }
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

    /** Una página de `GET /api/foods`. Mismas reglas que [listUnits]. */
    fun listFoods(
        page: Int = 1,
        perPage: Int = MAX_CATALOG_PER_PAGE,
        search: String? = null,
    ): ApiResult<Page<IngredientFood>> {
        val safePage = page.coerceAtLeast(1)
        val safePerPage = perPage.coerceIn(1, MAX_CATALOG_PER_PAGE)
        val term = PlainTextSanitizer.sanitize(search, CATALOG_SEARCH_MAX).ifEmpty { null }
        val query = buildString {
            append("page=").append(safePage)
            append("&perPage=").append(safePerPage)
            term?.let { append("&search=").append(PercentEncoding.encode(it)) }
        }
        return guarded {
            val dto = decode(
                IngredientFoodPageDto.serializer(),
                transport.execute(HttpRequest(HttpMethod.GET, FOODS_PATH, query = query)),
            )
            val items = dto.items.orEmpty().asSequence()
                .filterNotNull()
                .take(MAX_CATALOG_PER_PAGE)
                .mapNotNull { IngredientCatalogMapper.food(it) }
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

    /**
     * `POST /api/units`: crea una unidad y devuelve la que ha quedado guardada
     * (con su id), lista para meterla en el borrador con
     * `DraftIngredient.unitId`.
     *
     * Solo viajan los campos con contenido: `name` (obligatorio) y, si se
     * escriben, `pluralName`, `abbreviation` y `pluralAbbreviation`. `fraction`
     * y `useAbbreviation` se dejan en los valores por defecto del esquema
     * porque su efecto en el texto del ingrediente no está confirmado.
     *
     * No se reintenta nunca. Un rechazo definitivo del servidor (o un fallo
     * antes de enviar) es un error; cualquier desenlace ambiguo después de
     * enviar el cuerpo es [ApiResult.MaybeCreated]: la unidad puede existir y
     * hay que mirar el catálogo antes de repetir. Un 2xx sin id o nombre
     * legibles también es ambiguo.
     */
    fun createUnit(
        name: String,
        pluralName: String? = null,
        abbreviation: String? = null,
        pluralAbbreviation: String? = null,
    ): ApiResult<IngredientUnit> {
        val (clean, errors) = RecipeDraftValidator.validateUnitName(name)
        val problems = ArrayList(errors)
        val plural = optionalText(pluralName, RecipeDraftValidator.MAX_UNIT_FOOD, DraftField.IngredientUnit, problems)
        val abbrev = optionalText(abbreviation, RecipeDraftValidator.MAX_ABBREVIATION, DraftField.IngredientUnit, problems)
        val pluralAbbrev = optionalText(
            pluralAbbreviation,
            RecipeDraftValidator.MAX_ABBREVIATION,
            DraftField.IngredientUnit,
            problems,
        )
        if (clean == null || problems.isNotEmpty()) return ApiResult.DraftInvalid(problems)
        val body = LinkedHashMap<String, JsonElement>()
        body["name"] = JsonPrimitive(clean)
        plural?.let { body["pluralName"] = JsonPrimitive(it) }
        abbrev?.let { body["abbreviation"] = JsonPrimitive(it) }
        pluralAbbrev?.let { body["pluralAbbreviation"] = JsonPrimitive(it) }
        return guardedCreate {
            val response = transport.execute(
                HttpRequest.json(HttpMethod.POST, UNITS_PATH, encode(JsonObject(body)), readErrorBody = true),
            )
            ApiResult.Success(readUnit(response))
        }
    }

    /**
     * `POST /api/foods`: crea un alimento y devuelve el guardado, con su id.
     * Mismas reglas de reintento que [createUnit].
     */
    fun createFood(name: String, pluralName: String? = null): ApiResult<IngredientFood> {
        val (clean, errors) = RecipeDraftValidator.validateFoodName(name)
        val problems = ArrayList(errors)
        val plural = optionalText(pluralName, RecipeDraftValidator.MAX_UNIT_FOOD, DraftField.IngredientFood, problems)
        if (clean == null || problems.isNotEmpty()) return ApiResult.DraftInvalid(problems)
        val body = LinkedHashMap<String, JsonElement>()
        body["name"] = JsonPrimitive(clean)
        plural?.let { body["pluralName"] = JsonPrimitive(it) }
        return guardedCreate {
            val response = transport.execute(
                HttpRequest.json(HttpMethod.POST, FOODS_PATH, encode(JsonObject(body)), readErrorBody = true),
            )
            ApiResult.Success(readFood(response))
        }
    }

    // ------------------------------------------------------------- privados

    /** Ejecuta [block] y traduce cualquier [NetworkError] a un [ApiResult]. */
    private inline fun <T> guarded(block: () -> ApiResult<T>): ApiResult<T> =
        try {
            block()
        } catch (error: NetworkError) {
            ApiErrors.map(error)
        }

    /**
     * Como [guarded], pero para crear o importar: un fallo ambiguo después de
     * enviar el cuerpo no se puede contar como «no se creó», porque repetir
     * dejaría dos recetas. Se avisa con [ApiResult.MaybeCreated] para que la
     * interfaz pida mirar la lista antes de reintentar.
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
     * Clasifica un fallo de crear o importar.
     *
     * «No se creó» solo si el fallo es anterior al envío o si el servidor ha
     * contestado un rechazo DEFINITIVO. Cualquier otra cosa posterior al envío
     * es ambigua: un proxy puede contestar 502 mientras Mealie sigue
     * importando, y un 2xx puede traer un slug que no se sabe leer.
     *
     * [NetworkError.InvalidResponse] y [NetworkError.RedirectRejected] también
     * cuentan como ambiguos: el transporte usa el primero tanto para una
     * respuesta ilegible como para un destino que no se puede abrir, y una
     * redirección bloqueada llega después de que el servidor haya podido
     * procesar el cuerpo. Preferir [ApiResult.MaybeCreated] de más solo hace
     * mirar la lista; decir «no se creó» de menos deja dos recetas.
     */
    private fun creationFailure(error: NetworkError): ApiResult<Nothing> = when {
        error.happenedBeforeSend() -> ApiErrors.map(error)
        error is NetworkError.HttpError && error.statusCode in DEFINITIVE_CREATION_REJECTIONS ->
            ApiErrors.map(error)
        else -> ApiResult.MaybeCreated(error.toConnectionProblem())
    }

    /**
     * ¿El fallo prueba que la petición no llegó a salir? El transporte envuelve
     * en [NetworkError.AfterSend] todo lo ambiguo posterior al envío, así que
     * estos subtipos, cuando llegan sueltos, son de antes: abrir el flujo de
     * salida, resolver el nombre, el TLS o el plazo inicial.
     */
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

    private fun encode(element: JsonElement): String =
        CoreJson.json.encodeToString(JsonElement.serializer(), element)

    /**
     * Un campo opcional del catálogo (plural o abreviatura), saneado. Vacío es
     * null (no se envía). Si tras sanear pasa del tope, es un error: no se
     * recorta en silencio lo que la persona ha escrito.
     */
    private fun optionalText(
        raw: String?,
        max: Int,
        field: DraftField,
        errors: MutableList<DraftError>,
    ): String? {
        if (raw == null) return null
        val text = PlainTextSanitizer.sanitize(raw, max + 1)
        if (text.codePointCount(0, text.length) > max) {
            errors += DraftError(field, DraftErrorKind.TooLong)
            return null
        }
        return text.ifEmpty { null }
    }

    /** La unidad creada, o [NetworkError.InvalidResponse] si el 2xx no trae id y nombre legibles. */
    private fun readUnit(response: HttpResponse): IngredientUnit {
        val dto = decode(IngredientUnitDto.serializer(), response)
        return IngredientCatalogMapper.unit(dto) ?: throw NetworkError.InvalidResponse
    }

    /** El alimento creado, o [NetworkError.InvalidResponse] si el 2xx no trae id y nombre legibles. */
    private fun readFood(response: HttpResponse): IngredientFood {
        val dto = decode(IngredientFoodDto.serializer(), response)
        return IngredientCatalogMapper.food(dto) ?: throw NetworkError.InvalidResponse
    }

    /** El cuerpo como árbol JSON; cualquier fallo es [NetworkError.InvalidResponse] sin citar el cuerpo. */
    private fun parseElement(response: HttpResponse): JsonElement =
        try {
            CoreJson.json.parseToJsonElement(response.bodyAsText())
        } catch (ignored: IllegalArgumentException) {
            throw NetworkError.InvalidResponse
        } catch (ignored: StackOverflowError) {
            throw NetworkError.InvalidResponse
        }

    private fun readObject(response: HttpResponse): JsonObject =
        parseElement(response) as? JsonObject ?: throw NetworkError.InvalidResponse

    /** Respuesta de crear/importar: un texto JSON con el slug, que pasa la lista blanca o no vale. */
    private fun readSlug(response: HttpResponse): String {
        val primitive = parseElement(response) as? JsonPrimitive
        val slug = primitive?.takeIf { it.isString }?.content
        if (slug == null || !RecipeSlug.isValid(slug)) throw NetworkError.InvalidResponse
        return slug
    }

    /**
     * El slug que devuelve el `PUT`, si lo trae y es válido. La respuesta no
     * tiene esquema en el JSON del servidor, así que solo se mira ese campo y
     * cualquier otra cosa (vacía, rota, otra forma) se ignora.
     */
    private fun slugFromUpdateResponse(response: HttpResponse): String? =
        try {
            val value = (parseElement(response) as? JsonObject)?.get("slug") as? JsonPrimitive
            value?.takeIf { it.isString }?.content?.takeIf { RecipeSlug.isValid(it) }
        } catch (ignored: NetworkError) {
            null
        }

    private fun typeExtension(type: ImageType): String = type.mimeType.substringAfter('/')

    private fun imageFailure(error: NetworkError): ImageResult =
        when {
            error is NetworkError.HttpError && error.statusCode == 404 -> ImageResult.NotFound
            error is NetworkError.HttpError && (error.statusCode == 401 || error.statusCode == 403) ->
                ImageResult.Unauthorized
            error === NetworkError.ResponseTooLarge -> ImageResult.TooLarge
            else -> ImageResult.Failure(error.toConnectionProblem())
        }

    /**
     * La versión si la respuesta tiene forma de `AppInfo`; null si no la
     * tiene (otra aplicación, una página HTML de un proxy o de un SPA que
     * contesta 200 a todo).
     */
    private fun readServerVersion(response: HttpResponse): String? {
        val info = try {
            decode(AboutDto.serializer(), response)
        } catch (ignored: NetworkError.InvalidResponse) {
            return null
        }
        if (info.production == null) return null
        return PlainTextSanitizer.sanitize(info.version, MAX_VERSION_LENGTH).ifEmpty { null }
    }

    /**
     * Motivo del fallo del primer paso. No es el mapeo general porque aquí no
     * se ha mandado ningún token: un 401 o 403 es una pasarela o un proxy que
     * pide su propia identificación, no «tu token no vale», y un `about`
     * enorme es una web cualquiera, no un Mealie.
     */
    private fun aboutProblem(error: NetworkError): ConnectionProblem =
        when {
            error is NetworkError.HttpError && error.statusCode in 500..599 -> ConnectionProblem.ServerError
            error is NetworkError.HttpError -> ConnectionProblem.InvalidAddress
            error === NetworkError.ResponseTooLarge -> ConnectionProblem.InvalidAddress
            else -> error.toConnectionProblem()
        }

    /**
     * Cualquier fallo de lectura del JSON se convierte en un error fijo: el
     * mensaje de las excepciones de la librería cita trozos del cuerpo, que es
     * texto del servidor y no debe llegar a un log.
     */
    private fun <T> decode(serializer: KSerializer<T>, response: HttpResponse): T =
        try {
            CoreJson.json.decodeFromString(serializer, response.bodyAsText())
        } catch (ignored: IllegalArgumentException) {
            // SerializationException y JsonDecodingException descienden de esta.
            throw NetworkError.InvalidResponse
        } catch (ignored: StackOverflowError) {
            // Un JSON anidado a propósito en un campo que se lee como árbol
            // (el campo «image» no tiene tipo). Se corta aquí en vez de dejar
            // caer el hilo; la respuesta se trata como inválida.
            throw NetworkError.InvalidResponse
        }

    companion object {
        const val ABOUT_PATH = "/api/app/about"
        const val SELF_PATH = "/api/users/self"
        const val RECIPES_PATH = "/api/recipes"
        const val UNITS_PATH = "/api/units"
        const val FOODS_PATH = "/api/foods"

        /**
         * Códigos con los que el servidor (o un proxy) contesta que NO ha
         * aceptado la creación: 400, 401, 403, 404, 405, 409, 413, 415 y 422.
         * Solo estos permiten responder «no se creó»; el resto de códigos,
         * una vez enviado el cuerpo, son ambiguos y van a
         * [ApiResult.MaybeCreated].
         */
        private val DEFINITIVE_CREATION_REJECTIONS = setOf(400, 401, 403, 404, 405, 409, 413, 415, 422)

        private const val MAX_VERSION_LENGTH = 64
        private const val ORGANIZER_SEARCH_MAX = 100
        private const val CATALOG_SEARCH_MAX = 100

        /** Tope de elementos por página de unidades o alimentos. */
        const val MAX_CATALOG_PER_PAGE = 100

        /** Tope de elementos por página de etiquetas o categorías. */
        const val MAX_ORGANIZERS_PER_PAGE = 100

        /** Importar por URL: el servidor descarga y analiza la página, que puede tardar. */
        private const val IMPORT_READ_TIMEOUT_MS = 110_000
        private const val IMPORT_TOTAL_TIMEOUT_MS = 120_000L

        /**
         * Plazo total de una subida de imagen: escribir el cuerpo (hasta
         * [MAX_IMAGE_BYTES] más el envoltorio) y leer la respuesta.
         *
         * Se mira entre bloques de escritura, y una lectura ya empezada puede
         * alargarlo hasta [UPLOAD_READ_TIMEOUT_MS] más (el límite general de
         * `UrlConnectionTransport`): `HttpURLConnection` no tiene tiempo de
         * espera de escritura y su tiempo de lectura no se puede cambiar una
         * vez conectado. Al vencer, la subida falla con un error tipado en vez
         * de quedarse colgada.
         */
        const val UPLOAD_TOTAL_TIMEOUT_MS = 60_000L

        /** Tiempo de lectura de la respuesta de la subida, dentro del plazo total. */
        private const val UPLOAD_READ_TIMEOUT_MS = 30_000

        /** `AppInfo` son unos cientos de bytes: más que esto no es un Mealie. */
        private const val ABOUT_MAX_RESPONSE_BYTES = 64L * 1024

        /** `users/self` se descarta; 64 KiB sobra para un `UserOut` aun con muchos tokens. */
        const val SELF_MAX_RESPONSE_BYTES = 64L * 1024

        /** Tope de una imagen de receta: una foto de móvil recodificada cabe de sobra. */
        const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

        /**
         * Cliente para la sesión guardada, con el transporte real. Las
         * políticas de URL, de HTTP local y de redirecciones son las de
         * [UrlConnectionTransport.create]; [allowLocalHttp] y [activeNetwork]
         * se vuelven a leer de los ajustes cada vez que se crea (la sesión
         * guardada no sabe si el interruptor sigue encendido).
         */
        fun create(
            session: Session,
            allowLocalHttp: Boolean = false,
            activeNetwork: ActiveNetworkProvider? = null,
        ): MealieClient = MealieClient(
            transport = UrlConnectionTransport.create(
                serverUrl = session.serverUrl,
                token = session.token,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
            ),
            selfTransport = UrlConnectionTransport.create(
                serverUrl = session.serverUrl,
                token = session.token,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                maxResponseBytes = SELF_MAX_RESPONSE_BYTES,
            ),
            imageTransport = UrlConnectionTransport.create(
                serverUrl = session.serverUrl,
                token = session.token,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                maxResponseBytes = MAX_IMAGE_BYTES,
            ),
            importTransport = UrlConnectionTransport.create(
                serverUrl = session.serverUrl,
                token = session.token,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                readTimeoutMs = IMPORT_READ_TIMEOUT_MS,
                totalTimeoutMs = IMPORT_TOTAL_TIMEOUT_MS,
            ),
            uploadTransport = UrlConnectionTransport.create(
                serverUrl = session.serverUrl,
                token = session.token,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                readTimeoutMs = UPLOAD_READ_TIMEOUT_MS,
                totalTimeoutMs = UPLOAD_TOTAL_TIMEOUT_MS,
            ),
            anonymousTransport = UrlConnectionTransport.createWithoutToken(
                serverUrl = session.serverUrl,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                maxResponseBytes = ABOUT_MAX_RESPONSE_BYTES,
            ),
        )
    }
}
