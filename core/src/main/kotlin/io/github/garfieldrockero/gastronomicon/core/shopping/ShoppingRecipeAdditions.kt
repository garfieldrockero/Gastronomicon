package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDraftValidator
import io.github.garfieldrockero.gastronomicon.core.mealplan.PlannedMeal
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailView

/**
 * Una receta que se va a añadir a la lista de la compra con
 * `POST /api/households/shopping/lists/{item_id}/recipe`, con la escala de sus
 * ingredientes.
 *
 * [recipeId] es el UUID de la receta y [scale] se manda como
 * `recipeIncrementQuantity` (1.0 = las cantidades tal como están escritas en la
 * receta). No se valida al construir: [RecipeAdditions.check] es quien decide
 * si sirve, para que la interfaz pueda explicar el motivo en vez de recibir una
 * excepción desde un `data class`.
 */
data class RecipeAddition(val recipeId: String, val scale: Double) {
    // El UUID de una receta no es un secreto, pero se deja explícito para que
    // nadie añada aquí texto del servidor más adelante.
    override fun toString(): String = "RecipeAddition(recipeId=$recipeId, scale=$scale)"
}

/** Por qué una tanda de recetas no se puede añadir a la lista. */
enum class RecipeAdditionProblem {
    /** No hay ninguna receta que añadir. */
    Empty,

    /** Más recetas que [RecipeAdditions.MAX_RECIPES_PER_OPERATION]. */
    TooManyRecipes,

    /** Algún `recipeId` no tiene forma de UUID: no se puede formar la ruta. */
    InvalidRecipeId,

    /** Alguna escala no es un número positivo dentro del rango del núcleo. */
    InvalidScale,
}

/**
 * Lo que se añadirá a la lista al pedir «ingredientes de la semana», ya sin
 * recetas repetidas.
 *
 * [skippedRecipes] cuenta las recetas que se quedan fuera: las que se pasan del
 * tope de la operación y las que, al sumar sus escalas, se salen del rango del
 * núcleo. [truncated] es verdadero solo si el motivo fue el tope; así la
 * interfaz puede decir «se añaden las primeras N» sin inventar el número.
 */
data class RecipeAdditionPlan(
    val additions: List<RecipeAddition>,
    val skippedRecipes: Int,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = additions.isEmpty()

    // Sin recetas: el plan puede acabar en un log y aquí no hay nada que ocultar
    // más allá de los ids, que no son secretos.
    override fun toString(): String =
        "RecipeAdditionPlan(additions=${additions.size}, skipped=$skippedRecipes, truncated=$truncated)"
}

/**
 * Validación y agrupación de las recetas que se añaden a la lista de la compra.
 *
 * Es lógica de núcleo pura: no toca la red ni el estado. [ShoppingClient] la
 * usa como última barrera antes de formar el cuerpo, [ShoppingListController]
 * para no enviar nada inválido y `app/` para poder explicar un rechazo antes de
 * llamar.
 *
 * **Repetir una receta suma escalas.** Si la semana tiene la misma receta dos
 * veces (por ejemplo comida a escala 2 y cena a escala 1), no se manda dos
 * veces: se manda una sola con `scale = 3`. Duplicar la entrada dejaría dos
 * líneas de los mismos ingredientes, y sumar es lo que espera quien apunta la
 * misma receta dos veces. El orden es el de la primera aparición en la semana,
 * para que dos ejecuciones con las mismas entradas den siempre lo mismo.
 *
 * Lo mismo vale para una tanda escrita a mano: [merge] junta los `recipeId`
 * repetidos sumando sus escalas y [check] valida el resultado ya fusionado, así
 * que dos entradas iguales nunca llegan al servidor como dos líneas. El tope
 * ([MAX_RECIPES_PER_OPERATION]) cuenta recetas **distintas**, no entradas.
 */
object RecipeAdditions {

    /**
     * Tope de recetas distintas por operación. Una semana del planificador son
     * 7 días con hasta 7 comidas, pero recetas distintas suele haber muchas
     * menos; 30 deja sitio de sobra y mantiene el cuerpo de la petición pequeño.
     */
    const val MAX_RECIPES_PER_OPERATION = 30

    /**
     * Tope de la escala. Es el mismo que acepta una entrada del planificador
     * (`MealPlanDraftValidator.MAX_SCALE`): sumar dos escalas válidas puede
     * pasarse. En una semana, la receta se descarta ([plan]); en una tanda
     * explícita, [check] la rechaza con
     * [RecipeAdditionProblem.InvalidScale] en vez de mandar una cantidad que el
     * servidor no espera.
     */
    const val MAX_SCALE = MealPlanDraftValidator.MAX_SCALE

    /** La escala sirve: finita, positiva y sin pasarse del tope. */
    fun validScale(scale: Double): Boolean =
        scale.isFinite() && scale > 0.0 && scale <= MAX_SCALE

    /**
     * Escala que deja la receta en [wantedServings] raciones, partiendo de las
     * que dice la receta ([originalServings]).
     *
     * Es la conversión que necesita «añadir a la compra» desde el detalle, donde
     * la persona escribe **raciones** y el `POST` espera una escala
     * (`recipeIncrementQuantity`). La pantalla no divide: llama aquí.
     *
     * Devuelve null —y entonces la interfaz dice que ese número no vale— cuando:
     * - la receta no dice para cuántas raciones es (sin proporción que aplicar;
     *   la pantalla ofrece «veces la receta»),
     * - algún número falta, no es finito, es cero o es negativo,
     * - se piden más de [RecipeDetailView.MAX_SERVINGS] raciones (el mismo tope
     *   que el selector del detalle), o
     * - la escala resultante se sale de [MAX_SCALE]: mejor decirlo que mandar
     *   una cantidad que el servidor no espera.
     */
    fun scaleForServings(originalServings: Double?, wantedServings: Double?): Double? {
        val original = originalServings?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val wanted = wantedServings?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (wanted > RecipeDetailView.MAX_SERVINGS) return null
        val scale = wanted / original
        return scale.takeIf { validScale(it) }
    }

    /**
     * Fusiona las entradas con el mismo `recipeId` sumando sus escalas, en el
     * orden de la primera aparición. Los ids que son UUID se normalizan a
     * minúsculas, para que dos formas del mismo id no cuenten como recetas
     * distintas. Una suma que se sale de [MAX_SCALE] se queda tal cual (positiva
     * pero fuera de rango) y [check] la rechaza con
     * [RecipeAdditionProblem.InvalidScale]: preferible a recortarla o a mandar
     * una cantidad que el servidor no espera.
     *
     * Es la normalización que comparten [check], [ShoppingClient.addRecipes] y
     * [ShoppingListController.addRecipes]: así la validación y el cuerpo que
     * sale miran exactamente la misma lista.
     */
    fun merge(additions: List<RecipeAddition>): List<RecipeAddition> {
        if (additions.size <= 1) {
            return additions.map { RecipeAddition(ShoppingIds.valid(it.recipeId) ?: it.recipeId, it.scale) }
        }
        val order = ArrayList<String>(additions.size)
        val scales = HashMap<String, Double>(additions.size)
        for (addition in additions) {
            val recipeId = ShoppingIds.valid(addition.recipeId) ?: addition.recipeId
            val previous = scales[recipeId]
            if (previous == null) {
                order += recipeId
                scales[recipeId] = addition.scale
            } else {
                scales[recipeId] = previous + addition.scale
            }
        }
        return order.map { RecipeAddition(it, scales.getValue(it)) }
    }

    /**
     * El primer motivo por el que [additions] no se puede enviar, o null si
     * todo vale. Se fusionan antes los ids repetidos ([merge]); el tope se
     * comprueba sobre las recetas distintas y antes que sus datos: con
     * demasiadas, el motivo útil es ese y no cuál de ellas está mal.
     */
    fun check(additions: List<RecipeAddition>): RecipeAdditionProblem? {
        if (additions.isEmpty()) return RecipeAdditionProblem.Empty
        val merged = merge(additions)
        if (merged.size > MAX_RECIPES_PER_OPERATION) return RecipeAdditionProblem.TooManyRecipes
        for (addition in merged) {
            if (ShoppingIds.valid(addition.recipeId) == null) return RecipeAdditionProblem.InvalidRecipeId
            if (!validScale(addition.scale)) return RecipeAdditionProblem.InvalidScale
        }
        return null
    }

    /**
     * Recetas a añadir a partir de las entradas de una semana del planificador.
     * Solo cuentan las que tienen `recipeId`; las de solo texto se ignoran.
     *
     * @param maxRecipes tope propio de la llamada; se recorta a
     *   [MAX_RECIPES_PER_OPERATION], que no se puede superar.
     */
    fun plan(
        entries: List<PlannedMeal>,
        maxRecipes: Int = MAX_RECIPES_PER_OPERATION,
    ): RecipeAdditionPlan {
        val limit = maxRecipes.coerceIn(1, MAX_RECIPES_PER_OPERATION)
        val order = ArrayList<String>(limit)
        val scales = HashMap<String, Double>()
        // Recetas que se han descartado por pasarse de escala: si vuelven a
        // aparecer más adelante no se resucitan, o la suma se desbordaría otra vez.
        val dropped = HashSet<String>()
        var skipped = 0

        for (entry in entries) {
            // Sin receta (solo título o nota) no hay ingredientes que añadir; un
            // id que no es UUID tampoco puede ir en el cuerpo.
            val recipeId = ShoppingIds.valid(entry.recipeId) ?: continue
            if (recipeId in dropped) continue
            val scale = entry.recipeScale
            // Una entrada con una escala rota no se convierte en «escala 1»: se ignora.
            if (!validScale(scale)) continue

            val previous = scales[recipeId]
            if (previous == null) {
                scales[recipeId] = scale
                order += recipeId
                continue
            }
            val sum = previous + scale
            if (validScale(sum)) {
                scales[recipeId] = sum
            } else {
                scales.remove(recipeId)
                order.remove(recipeId)
                dropped += recipeId
                skipped++
            }
        }

        val truncated = order.size > limit
        val additions = ArrayList<RecipeAddition>(limit)
        for (index in 0 until order.size) {
            if (index >= limit) {
                // Lo que sobra del tope también se cuenta como receta no añadida.
                skipped++
                continue
            }
            val recipeId = order[index]
            additions += RecipeAddition(recipeId, scales.getValue(recipeId))
        }
        return RecipeAdditionPlan(additions = additions, skippedRecipes = skipped, truncated = truncated)
    }

    /** Atajo de [plan] cuando solo interesa la lista de recetas a añadir. */
    fun fromWeek(
        entries: List<PlannedMeal>,
        maxRecipes: Int = MAX_RECIPES_PER_OPERATION,
    ): List<RecipeAddition> = plan(entries, maxRecipes).additions
}
