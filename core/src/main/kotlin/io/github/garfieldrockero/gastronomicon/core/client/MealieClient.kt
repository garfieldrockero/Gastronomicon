package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import io.github.garfieldrockero.gastronomicon.core.network.HttpRequest
import io.github.garfieldrockero.gastronomicon.core.network.HttpResponse
import io.github.garfieldrockero.gastronomicon.core.network.HttpTransport
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransport
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageSize
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListQuery
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListSource
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummary
import io.github.garfieldrockero.gastronomicon.core.session.Session
import kotlinx.serialization.KSerializer

/** Resultado de «Probar conexión»: o la versión del servidor, o el motivo ya reducido. */
sealed interface ConnectionCheck {
    /** [serverVersion] es texto saneado de `AppInfo.version` (p. ej. «v3.29.0»). */
    data class Success(val serverVersion: String) : ConnectionCheck

    data class Failure(val problem: ConnectionProblem) : ConnectionCheck
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
 * mensajes son fijos y no llevan token, URL ni cuerpo de la respuesta. El 422
 * no se lee: el detalle del formulario se decidió para la fase de escritura.
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

    // ------------------------------------------------------------- privados

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

        private const val MAX_VERSION_LENGTH = 64

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
            anonymousTransport = UrlConnectionTransport.createWithoutToken(
                serverUrl = session.serverUrl,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = activeNetwork,
                maxResponseBytes = ABOUT_MAX_RESPONSE_BYTES,
            ),
        )
    }
}
