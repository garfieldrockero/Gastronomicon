package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImage
import java.time.LocalDate

/**
 * Lo que la interfaz escribe para crear o editar una entrada del planificador.
 *
 * Lleva datos sin validar: [MealPlanDraftValidator] los sanea y acota antes de
 * que salgan hacia el servidor. [recipeId] es el UUID de la receta si la
 * comida es una receta y null si es solo texto; con receta, el [title] puede
 * ir vacío (la vista enseña el nombre de la receta).
 */
class MealPlanDraft(
    val date: LocalDate,
    val mealType: MealType,
    val recipeId: String? = null,
    val title: String = "",
    val text: String = "",
    val recipeScale: Double = 1.0,
) {
    // Sin los textos: son datos de la persona y un borrador puede acabar en un log.
    override fun toString(): String =
        "MealPlanDraft(date=$date, mealType=$mealType, hasRecipe=${recipeId != null})"
}

/** Borrador ya saneado y validado: es lo único que `MealPlanClient` envía. */
class CleanMealPlanDraft internal constructor(
    val date: LocalDate,
    val mealType: MealType,
    val recipeId: String?,
    val title: String,
    val text: String,
    val recipeScale: Double,
) {
    override fun toString(): String =
        "CleanMealPlanDraft(date=$date, mealType=$mealType, hasRecipe=${recipeId != null})"
}

/** Resultado de validar un [MealPlanDraft]. */
sealed interface MealPlanDraftCheck {
    class Valid(val draft: CleanMealPlanDraft) : MealPlanDraftCheck
    class Invalid(val problem: MealPlanInputProblem) : MealPlanDraftCheck
}

/**
 * Valida y sanea un borrador antes de que salga hacia el servidor.
 *
 * Todo texto pasa por [PlainTextSanitizer] (fuera controles, bidireccionales y
 * marcas invisibles). No se recorta en silencio: pasarse del máximo es
 * [MealPlanInputProblem.TitleTooLong] o [MealPlanInputProblem.TextTooLong],
 * porque cortar lo que la persona escribió sin avisar es perder datos.
 *
 * Límites: 200 para el título y 1000 para el texto libre. Con receta, el
 * título es opcional; sin receta, hace falta título o texto, porque una
 * entrada vacía no se puede reconocer después. Las raciones son un número
 * finito mayor que cero y como mucho [MAX_SCALE].
 */
object MealPlanDraftValidator {

    const val MAX_TITLE = 200
    const val MAX_TEXT = 1_000
    const val MAX_SCALE = 100.0

    fun validate(draft: MealPlanDraft): MealPlanDraftCheck {
        MealPlanDates.check(draft.date)?.let { return MealPlanDraftCheck.Invalid(it) }
        if (!draft.mealType.isKnown) {
            return MealPlanDraftCheck.Invalid(MealPlanInputProblem.InvalidMealType)
        }

        val title = clean(draft.title, MAX_TITLE)
            ?: return MealPlanDraftCheck.Invalid(MealPlanInputProblem.TitleTooLong)
        val text = clean(draft.text, MAX_TEXT)
            ?: return MealPlanDraftCheck.Invalid(MealPlanInputProblem.TextTooLong)

        // Un id en blanco es «sin receta»; uno escrito que no sea UUID es un error.
        val rawRecipeId = draft.recipeId
        val recipeId = if (rawRecipeId.isNullOrBlank()) {
            null
        } else {
            RecipeImage.normalizeId(rawRecipeId.trim())
                ?: return MealPlanDraftCheck.Invalid(MealPlanInputProblem.InvalidRecipeId)
        }

        val scale = draft.recipeScale
        if (!scale.isFinite() || scale <= 0.0 || scale > MAX_SCALE) {
            return MealPlanDraftCheck.Invalid(MealPlanInputProblem.InvalidRecipeScale)
        }

        if (recipeId == null && title.isEmpty() && text.isEmpty()) {
            return MealPlanDraftCheck.Invalid(MealPlanInputProblem.EmptyEntry)
        }

        return MealPlanDraftCheck.Valid(
            CleanMealPlanDraft(
                date = draft.date,
                mealType = draft.mealType,
                recipeId = recipeId,
                title = title,
                text = text,
                recipeScale = scale,
            ),
        )
    }

    /** Texto saneado, o null si al sanearlo sigue pasando de [max] code points. */
    private fun clean(raw: String, max: Int): String? {
        // Se sanea a max+1 para poder distinguir «justo cabe» de «se pasa».
        val text = PlainTextSanitizer.sanitize(raw, max + 1)
        return if (text.codePointCount(0, text.length) > max) null else text
    }
}
