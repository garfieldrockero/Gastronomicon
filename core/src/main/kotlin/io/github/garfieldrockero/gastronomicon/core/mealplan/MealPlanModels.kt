package io.github.garfieldrockero.gastronomicon.core.mealplan

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.util.Locale

/**
 * Tipo de comida de una entrada del planificador.
 *
 * Los valores de [apiValue] son los de `PlanEntryType` en `docs/openapi.json`
 * (breakfast, lunch, dinner, side, snack, drink y dessert). Se admiten los
 * siete porque el servidor puede devolver cualquiera; [UNKNOWN] no se puede
 * enviar y está para que una versión futura de Mealie que añada otro tipo no
 * rompa la lectura (se queda al final y no se reinterpreta como otro tipo).
 *
 * El orden de declaración es el de presentación (desayuno, comida, cena…), así
 * que las comidas de un día se ordenan por `ordinal` y no por el texto.
 */
enum class MealType(val apiValue: String, val label: String) {
    BREAKFAST("breakfast", "Desayuno"),
    LUNCH("lunch", "Comida"),
    DINNER("dinner", "Cena"),
    SIDE("side", "Guarnición"),
    SNACK("snack", "Aperitivo"),
    DRINK("drink", "Bebida"),
    DESSERT("dessert", "Postre"),

    /** Tipo que esta versión de la app no conoce: se muestra, pero no se crea. */
    UNKNOWN("", "Otra comida");

    /** False solo en [UNKNOWN]: no se puede mandar al servidor. */
    val isKnown: Boolean get() = this != UNKNOWN

    companion object {
        /**
         * Tipo a partir del texto del servidor. Ausente o vacío es
         * [BREAKFAST] porque el esquema `ReadPlanEntry` declara ese valor por
         * defecto; un valor no reconocido es [UNKNOWN] (no se adivina).
         */
        fun fromApi(raw: String?): MealType {
            if (raw.isNullOrBlank()) return BREAKFAST
            val wanted = raw.trim().lowercase(Locale.ROOT)
            return values().firstOrNull { it != MealType.UNKNOWN && it.apiValue == wanted }
                ?: MealType.UNKNOWN
        }
    }
}

/**
 * Límites de los identificadores de una entrada del planificador.
 *
 * El `id` de Mealie es un entero de base de datos; se acepta hasta el máximo
 * de un entero de 32 bits y se rechaza el resto antes de formar una ruta.
 */
object MealPlanIds {
    const val MAX_ID: Long = 2_147_483_647L

    /** El id si puede ir en una ruta, o null. */
    fun checked(id: Long?): Long? = id?.takeIf { it in 1L..MAX_ID }
}

/**
 * Una entrada del planificador ya saneada y acotada. Es lo que entrega
 * `MealPlanClient` y lo que consume la vista; nunca lleva texto crudo del
 * servidor.
 *
 * [groupId] y [userId] son los que exige `UpdatePlanEntry` para mover o
 * editar (`id`, `groupId` y `userId` son obligatorios en el esquema). Si el
 * servidor no manda UUID válidos quedan en null y la edición se rechaza
 * localmente en vez de enviar una petición que el servidor va a rechazar.
 *
 * [rawJson] es la entrada tal cual la mandó el servidor (acotada por
 * `MealPlanMapper`). Mover o editar parte de ella y cambia solo lo editado:
 * así no se aplanan notas, no se recortan títulos ni se pierden campos de otra
 * versión de Mealie. Es `null` si la entrada no vino de una lectura.
 */
data class PlannedMeal(
    val id: Long,
    val date: LocalDate,
    val mealType: MealType,
    /** Título escrito para la entrada (puede venir vacío si hay receta). */
    val title: String,
    /** Nota libre de la entrada, ya saneada. */
    val note: String,
    /** UUID de la receta, o null si la entrada es solo texto. */
    val recipeId: String?,
    /** Nombre de la receta tal como lo manda el resumen, ya saneado. */
    val recipeName: String?,
    val recipeSlug: String?,
    /** Raciones de la entrada; 1.0 si el servidor no manda un valor usable. */
    val recipeScale: Double,
    val groupId: String?,
    val userId: String?,
    val rawJson: JsonObject? = null,
) {
    /** No hay receta ni texto: la vista enseña una etiqueta explícita. */
    val isBlank: Boolean
        get() = recipeId == null && title.isEmpty() && note.isEmpty()

    /** Se apuntó una receta que el servidor ya no devuelve (borrada o sin permiso). */
    val recipeMissing: Boolean
        get() = recipeId != null && recipeName.isNullOrEmpty()

    // Sin el contenido: son datos del servidor y una entrada puede acabar en un log.
    override fun toString(): String =
        "PlannedMeal(id=$id, date=$date, mealType=$mealType, hasRecipe=${recipeId != null}, " +
            "hasRaw=${rawJson != null})"
}
