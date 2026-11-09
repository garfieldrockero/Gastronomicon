package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftError
import io.github.garfieldrockero.gastronomicon.core.recipes.DraftField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Resultado de las operaciones nuevas de [MealieClient] (escritura, importar,
 * subir imagen y listas de organizadores). No lanza por red: todo desenlace es
 * un valor, y ninguno lleva el token, la URL del servidor ni un cuerpo crudo.
 *
 * Los textos que sí aparecen (el detalle de un 422) son del servidor y están
 * saneados y acotados en [ValidationDetail].
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val value: T) : ApiResult<T>

    /** 401: el token no vale o ha caducado. */
    data object Unauthorized : ApiResult<Nothing>

    /** 403: el token es válido pero no tiene permiso para esto. */
    data object Forbidden : ApiResult<Nothing>

    /** 404: la receta (o la ruta) no existe. */
    data object NotFound : ApiResult<Nothing>

    /** 409: ya existe. Mealie puede no usarlo nunca; se distingue por si lo hace. */
    data object Conflict : ApiResult<Nothing>

    /** 422: el servidor ha rechazado los datos. [details] puede venir vacío. */
    data class Invalid(val details: List<ValidationDetail>) : ApiResult<Nothing>

    /** Otro 4xx (400, 413…): el servidor ha rechazado la petición sin más detalle útil. */
    data class Rejected(val statusCode: Int) : ApiResult<Nothing>

    /** El borrador no ha pasado la validación local: no se ha enviado nada. */
    data class DraftInvalid(val errors: List<DraftError>) : ApiResult<Nothing>

    /** Un dato de entrada no vale (URL, imagen): no se ha enviado nada. */
    data class InputRejected(val reason: InputProblem) : ApiResult<Nothing>

    /**
     * Solo en `updateRecipe`: el `PUT` se aceptó pero no se ha podido volver a
     * leer la receta. Los cambios están guardados; hay que recargar.
     */
    data class SavedButNotReloaded(val problem: ConnectionProblem) : ApiResult<Nothing>

    /** Red, TLS, redirección bloqueada, 5xx, respuesta inválida o demasiado grande. */
    data class Failure(val problem: ConnectionProblem) : ApiResult<Nothing>
}

/** Por qué una entrada se ha rechazado antes de salir de la app. */
enum class InputProblem {
    /** La URL a importar no es una `https` válida. */
    InvalidUrl,

    /** Los bytes no son una imagen webp, jpeg o png, o no coinciden con la extensión. */
    InvalidImage,

    /** La extensión no es `webp`, `jpeg` ni `png`. */
    UnsupportedImageType,

    /** La imagen pasa del tope de subida. */
    ImageTooLarge,
}

/**
 * Una entrada del 422 de FastAPI, reducida a lo útil: el campo del formulario
 * (si se reconoce), la fila y el mensaje saneado. No se copia `input` ni `ctx`:
 * pueden repetir lo enviado o traer cualquier cosa.
 */
data class ValidationDetail(val field: DraftField?, val index: Int?, val message: String)

/** Traducción de errores de red y del cuerpo de un 422 a [ApiResult]. */
internal object ApiErrors {

    const val MAX_DETAILS = 10
    const val MAX_MESSAGE_LENGTH = 200

    /** Cualquier [NetworkError] de una operación nueva, sin perder el matiz de cada código HTTP. */
    fun map(error: NetworkError): ApiResult<Nothing> {
        if (error is NetworkError.HttpError) {
            return when (val code = error.statusCode) {
                401 -> ApiResult.Unauthorized
                403 -> ApiResult.Forbidden
                404 -> ApiResult.NotFound
                409 -> ApiResult.Conflict
                422 -> ApiResult.Invalid(validationDetails(error.body))
                in 400..499 -> ApiResult.Rejected(code)
                else -> ApiResult.Failure(error.toConnectionProblem())
            }
        }
        return ApiResult.Failure(error.toConnectionProblem())
    }

    /**
     * Lee `{"detail":[{"loc":[…],"msg":"…"}]}`. Cualquier otra forma (un texto,
     * un objeto, JSON roto, anidado hasta el desbordamiento) da una lista
     * vacía: el error se entrega igual, sin detalle.
     */
    fun validationDetails(body: ByteArray?): List<ValidationDetail> {
        if (body == null || body.isEmpty()) return emptyList()
        val root = try {
            CoreJson.json.parseToJsonElement(String(body, Charsets.UTF_8))
        } catch (ignored: IllegalArgumentException) {
            return emptyList()
        } catch (ignored: StackOverflowError) {
            return emptyList()
        }
        val detail = (root as? JsonObject)?.get("detail") as? JsonArray ?: return emptyList()
        val result = ArrayList<ValidationDetail>()
        for (entry in detail) {
            if (result.size >= MAX_DETAILS) break
            val item = entry as? JsonObject ?: continue
            val message = PlainTextSanitizer.sanitize(
                (item["msg"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                MAX_MESSAGE_LENGTH,
            )
            if (message.isEmpty()) continue
            val loc = item["loc"] as? JsonArray
            result += ValidationDetail(fieldOf(loc), indexOf(loc), message)
        }
        return result
    }

    private val FIELDS = mapOf(
        "name" to DraftField.Name,
        "description" to DraftField.Description,
        "recipeServings" to DraftField.Servings,
        "totalTime" to DraftField.TotalTime,
        "prepTime" to DraftField.PrepTime,
        "cookTime" to DraftField.CookTime,
        "performTime" to DraftField.PerformTime,
        "recipeIngredient" to DraftField.Ingredients,
        "recipeInstructions" to DraftField.Steps,
        "notes" to DraftField.Notes,
        "tags" to DraftField.Tags,
        "recipeCategory" to DraftField.Categories,
    )

    /** Primer elemento de `loc` que sea un campo conocido ("body" y similares se saltan). */
    private fun fieldOf(loc: JsonArray?): DraftField? {
        for (part in loc.orEmpty()) {
            val text = (part as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            FIELDS[text]?.let { return it }
        }
        return null
    }

    /** Número que sigue al campo en `loc` (la fila de la lista), si lo hay. */
    private fun indexOf(loc: JsonArray?): Int? {
        val parts = loc.orEmpty()
        for ((position, part) in parts.withIndex()) {
            val text = (part as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (text in FIELDS) {
                val next = parts.getOrNull(position + 1) as? JsonPrimitive ?: return null
                if (next.isString) return null
                return next.intOrNull?.takeIf { it >= 0 }
            }
        }
        return null
    }
}
