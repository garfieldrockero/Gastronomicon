package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import java.util.Locale

/**
 * Cuál de los tiempos de la receta es. El rótulo lo pone la interfaz:
 * sugerencia «Tiempo total», «Preparación» y «Cocción».
 */
enum class TimeKind {
    Total,
    Prep,

    /** Lo que tarda en hacerse al fuego o al horno; ver [RecipeTimeText.lines] para de dónde sale. */
    Cook,

    /**
     * Ya no se emite: `performTime` se muestra como [Cook]. Se deja para que
     * un `when` exhaustivo de `app/` siga compilando; se puede borrar con él.
     */
    @Deprecated("performTime se muestra como Cook; esta variante nunca se emite.")
    Perform,
}

/** Un tiempo ya formateado: el texto de la receta, o los segundos en forma corta («1 h 30 min»). */
data class TimeLine(val kind: TimeKind, val text: String)

/**
 * Qué tiempos se enseñan y con qué nombre.
 *
 * Decisión (9-11-2026), contrastada con `docs/openapi.json` (`Recipe-Output`
 * y `RecipeSummary`): el esquema trae `prepTime`, `cookTime`, `performTime` y
 * `totalTime` como texto libre, y `totalTimeSeconds`, `prepTimeSeconds` y
 * `performTimeSeconds` como segundos (no hay `cookTimeSeconds`). El JSON no
 * trae descripciones, así que no dice cuál de `cookTime` y `performTime` es
 * «la cocción». Lo que sí indica el esquema es que `performTime` es el único
 * con versión en segundos, y por memoria del código de Mealie (a comprobar en
 * el servidor real) su pantalla de receta enseña preparación, `performTime` y
 * total, no `cookTime`. Por eso:
 *
 * - **Total** → «Tiempo total» (`totalTime`, o `totalTimeSeconds`).
 * - **Prep** → «Preparación» (`prepTime`, o `prepTimeSeconds`).
 * - **Cook** → «Cocción»: `performTime` (o `performTimeSeconds`) y, solo si
 *   falta, `cookTime`. Nunca dos filas de cocción: quien cocina quiere un
 *   dato, no dos que se contradicen.
 * - Cada tiempo sale solo si existe. El texto de la receta manda sobre los
 *   segundos (puede decir «una hora y pico»).
 * - Si hay tiempo total y es lo único más que sale, con el mismo texto que
 *   él, solo se deja el total: no se repite el dato.
 */
internal object RecipeTimeText {
    private const val MAX_TIME_TEXT = 100

    /** El texto saneado si lo hay; si no, los segundos en forma corta; si no, cadena vacía. */
    fun shown(text: String, seconds: Int?): String =
        PlainTextSanitizer.sanitize(text, MAX_TIME_TEXT).ifEmpty { DurationFormatter.format(seconds) }

    fun total(times: RecipeTimes): String = shown(times.total, times.totalSeconds)

    fun lines(times: RecipeTimes): List<TimeLine> {
        val total = total(times)
        val prep = shown(times.prep, times.prepSeconds)
        val cook = shown(times.perform, times.performSeconds).ifEmpty { shown(times.cook, null) }

        val others = ArrayList<TimeLine>(2)
        if (prep.isNotEmpty()) others += TimeLine(TimeKind.Prep, prep)
        if (cook.isNotEmpty()) others += TimeLine(TimeKind.Cook, cook)

        if (total.isEmpty()) return others
        if (others.size == 1 && sameText(others[0].text, total)) return listOf(TimeLine(TimeKind.Total, total))
        return listOf(TimeLine(TimeKind.Total, total)) + others
    }

    private fun sameText(a: String, b: String): Boolean =
        a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)
}

/** Estrellas de valoración. */
object RatingStars {
    const val MAX_STARS = 5

    /**
     * Estrellas llenas, de 0 a [MAX_STARS], redondeando al entero más cercano
     * (la mitad hacia arriba) y acotando lo que se salga. Null si no hay
     * valoración o no es un número (NaN, infinito). Un 0 es «ninguna estrella
     * llena»: la interfaz decide si pinta o no una fila sin valorar.
     */
    fun of(rating: Double?): Int? {
        if (rating == null || !rating.isFinite()) return null
        return Math.round(rating.coerceIn(0.0, MAX_STARS.toDouble())).toInt()
    }
}
