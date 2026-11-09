package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.network.toConnectionProblem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tope de entradas que el núcleo mantiene en memoria entre la lectura y la
 * vista. Un planificador no tiene miles de comidas por semana; el tope protege
 * de un servidor que ignore la paginación.
 */
const val MAX_MEAL_PLAN_ENTRIES = 2_000

/**
 * Un dato de entrada no vale. Se rechaza en la app y **no se envía nada**.
 */
enum class MealPlanInputProblem {
    /** La fecha no está entre [MealPlanDates.MIN_YEAR] y [MealPlanDates.MAX_YEAR]. */
    InvalidDate,

    /** La fecha de fin es anterior a la de inicio. */
    InvalidRange,

    /** El rango pasa de [MealPlanDates.MAX_RANGE_DAYS] días. */
    RangeTooWide,

    /** El tipo de comida no se puede mandar (incluye [MealType.UNKNOWN]). */
    InvalidMealType,

    /** Ni receta, ni título, ni texto: la entrada no diría nada. */
    EmptyEntry,

    /** El título pasa del máximo; no se recorta en silencio. */
    TitleTooLong,

    /** El texto libre pasa del máximo; no se recorta en silencio. */
    TextTooLong,

    /** El id de receta no tiene forma de UUID. */
    InvalidRecipeId,

    /** Las raciones no son un número finito mayor que cero y razonable. */
    InvalidRecipeScale,

    /** El id de la entrada no puede ir en una ruta. */
    InvalidEntryId,

    /**
     * La entrada leída no trae `groupId` y `userId` utilizables, que
     * `UpdatePlanEntry` exige: sin ellos no se puede mover ni editar.
     */
    MissingEntryIdentity,
}

/**
 * Resultado de una operación del planificador. No lanza por red: todo
 * desenlace es un valor y ninguno lleva el token, la URL del servidor ni un
 * cuerpo crudo. Los mensajes de [Invalid] son texto del servidor, ya saneado y
 * acotado.
 */
sealed interface MealPlanResult<out T> {

    data class Success<T>(val value: T) : MealPlanResult<T>

    /** 401: el token no vale o ha caducado. */
    data object Unauthorized : MealPlanResult<Nothing>

    /** 403: el token es válido pero no tiene permiso para el planificador. */
    data object Forbidden : MealPlanResult<Nothing>

    /** 404: la entrada (o la ruta) no existe. */
    data object NotFound : MealPlanResult<Nothing>

    /** 422: el servidor ha rechazado los datos. [messages] puede venir vacío. */
    data class Invalid(val messages: List<String>) : MealPlanResult<Nothing> {
        // Sin el texto del servidor: un resultado puede acabar en un log.
        override fun toString(): String = "Invalid(messages=${messages.size})"
    }

    /** Otro 4xx (400, 409, 413…): rechazado sin más detalle útil. */
    data class Rejected(val statusCode: Int) : MealPlanResult<Nothing>

    /** La entrada no ha pasado la validación local: no se ha enviado nada. */
    data class InvalidInput(val problem: MealPlanInputProblem) : MealPlanResult<Nothing>

    /**
     * Solo al crear: el cuerpo llegó a enviarse y el fallo ocurrió después
     * (plazo agotado o conexión cortada), así que **no se sabe** si el
     * servidor llegó a crear la entrada. Quien llama debe mirar la semana
     * antes de repetir, para no dejar dos comidas iguales.
     */
    data class MaybeCreated(val problem: ConnectionProblem) : MealPlanResult<Nothing>

    /** Red, TLS, redirección bloqueada, 5xx, respuesta inválida o demasiado grande. */
    data class Failure(val problem: ConnectionProblem) : MealPlanResult<Nothing>
}

/**
 * Fallo ya reducido a lo que la interfaz necesita decidir (pedir token,
 * reintentar, corregir un dato o avisar de que quizá se creó).
 */
sealed interface MealPlanProblem {
    data object Unauthorized : MealPlanProblem
    data object Forbidden : MealPlanProblem
    data object NotFound : MealPlanProblem
    data class InvalidInput(val problem: MealPlanInputProblem) : MealPlanProblem
    data class Invalid(val messages: List<String>) : MealPlanProblem {
        override fun toString(): String = "Invalid(messages=${messages.size})"
    }
    data class Rejected(val statusCode: Int) : MealPlanProblem
    data class MaybeCreated(val problem: ConnectionProblem) : MealPlanProblem
    data class Connection(val problem: ConnectionProblem) : MealPlanProblem
}

/** Traduce el resultado de una operación al problema que se enseña; null si salió bien. */
internal fun MealPlanResult<*>.problemOrNull(): MealPlanProblem? = when (this) {
    is MealPlanResult.Success -> null
    is MealPlanResult.Unauthorized -> MealPlanProblem.Unauthorized
    is MealPlanResult.Forbidden -> MealPlanProblem.Forbidden
    is MealPlanResult.NotFound -> MealPlanProblem.NotFound
    is MealPlanResult.Invalid -> MealPlanProblem.Invalid(messages)
    is MealPlanResult.Rejected -> MealPlanProblem.Rejected(statusCode)
    is MealPlanResult.InvalidInput -> MealPlanProblem.InvalidInput(problem)
    is MealPlanResult.MaybeCreated -> MealPlanProblem.MaybeCreated(problem)
    is MealPlanResult.Failure -> MealPlanProblem.Connection(problem)
}

/** Traducción de errores de red y del cuerpo de un 422 a [MealPlanResult]. */
internal object MealPlanErrors {

    const val MAX_DETAILS = 10
    const val MAX_MESSAGE_LENGTH = 200

    /** Cualquier [NetworkError], sin perder el matiz de cada código HTTP. */
    fun map(error: NetworkError): MealPlanResult<Nothing> {
        if (error is NetworkError.HttpError) {
            return when (val code = error.statusCode) {
                401 -> MealPlanResult.Unauthorized
                403 -> MealPlanResult.Forbidden
                404 -> MealPlanResult.NotFound
                422 -> MealPlanResult.Invalid(details(error.body))
                in 400..499 -> MealPlanResult.Rejected(code)
                else -> MealPlanResult.Failure(error.toConnectionProblem())
            }
        }
        return MealPlanResult.Failure(error.toConnectionProblem())
    }

    /**
     * Lee `{"detail":[{"loc":[…],"msg":"…"}]}` y devuelve solo los mensajes
     * saneados y acotados. Cualquier otra forma (texto, JSON roto o anidado
     * hasta desbordar) da una lista vacía: el error se entrega igual.
     */
    fun details(body: ByteArray?): List<String> {
        if (body == null || body.isEmpty()) return emptyList()
        val root = try {
            CoreJson.json.parseToJsonElement(body.toString(Charsets.UTF_8))
        } catch (ignored: IllegalArgumentException) {
            return emptyList()
        } catch (ignored: StackOverflowError) {
            return emptyList()
        }
        val detail = (root as? JsonObject)?.get("detail") as? JsonArray ?: return emptyList()
        val messages = ArrayList<String>()
        for (entry in detail) {
            if (messages.size >= MAX_DETAILS) break
            val message = PlainTextSanitizer.sanitize(
                ((entry as? JsonObject)?.get("msg") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                MAX_MESSAGE_LENGTH,
            )
            if (message.isNotEmpty()) messages += message
        }
        return messages
    }
}
