package io.github.garfieldrockero.gastronomicon.core.reminder

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.offline.CachedMealPlan
import java.time.LocalDate

/**
 * Una comida tal como la pinta el aviso: tipo y nombre ya seguro.
 *
 * No hay campo para la nota libre a propósito: es el texto más delicado de una
 * entrada del plan y la decisión D10 es que **nunca** entra en el aviso. Si una
 * entrada no tiene ni receta ni título, la caché ni siquiera la guarda (ver
 * `MealPlanCacheCodec`), así que aquí no puede colarse la nota.
 */
data class ReminderMeal(val mealType: MealType, val name: String) {
    // El nombre es contenido de la persona: no se arrastra a un log.
    override fun toString(): String = "ReminderMeal(mealType=$mealType)"
}

/**
 * Texto ya compuesto del aviso. La interfaz solo lo mete en el
 * `NotificationCompat`; no recorta ni ordena nada.
 */
data class ReminderMessage(
    val title: String,
    val body: String,
    /** Comidas que aparecen (ya sin las entradas vacías). */
    val mealCount: Int,
    /** Comidas que no caben en el texto y se resumen con «y N más». */
    val hiddenCount: Int,
) {
    val hasHidden: Boolean get() = hiddenCount > 0

    // Sin el cuerpo: los nombres de receta son contenido y el aviso puede acabar
    // en un log al depurar.
    override fun toString(): String =
        "ReminderMessage(mealCount=$mealCount, hiddenCount=$hiddenCount)"
}

/**
 * Composición del aviso «hoy toca».
 *
 * - Título con sentido por sí solo («Comidas de hoy»): TalkBack lo lee al llegar
 *   la notificación y el cuerpo empieza por el tipo de comida, no por un nombre
 *   suelto.
 * - Una línea por comida, `«<Tipo>: <nombre>»`, ordenadas por tipo (desayuno →
 *   cena) y no alfabéticamente.
 * - Como mucho [MAX_LINES] líneas; si hay más, se añade «y N más». Cada línea se
 *   acota a [MAX_LINE_CODE_POINTS] puntos de código con «…».
 * - Día sin comidas utilizables → `null`: no se avisa de nada (decisión D3). La
 *   interfaz no inventa un aviso genérico.
 *
 * Nada de Android aquí: ni `PendingIntent`, ni recursos, ni notificaciones.
 * [TITLE] es el texto por defecto para quien no quiera un `R.string`; la
 * pantalla de bloqueo muestra los nombres (decisión D4), así que no hay versión
 * pública sin nombres que componer.
 */
object ReminderContent {

    /** Texto del título. Puede sustituirse por un `R.string` en la app. */
    const val TITLE: String = "Comidas de hoy"

    /** Líneas completas que se enseñan antes de resumir el resto. */
    const val MAX_LINES: Int = 5

    /** Tope de una línea completa (tipo incluido), en puntos de código. */
    const val MAX_LINE_CODE_POINTS: Int = 120

    /** Tope del nombre antes de componer la línea. */
    const val MAX_NAME_CODE_POINTS: Int = 120

    private const val SEPARATOR = " · "

    private const val ELLIPSIS = "…"

    /** Aviso para [meals], o `null` si no queda ninguna comida que enseñar. */
    fun of(meals: List<ReminderMeal>): ReminderMessage? {
        if (meals.isEmpty()) return null

        val lines = ArrayList<String>(meals.size)
        for (meal in meals.sortedWith(compareBy { it.mealType.ordinal })) {
            val name = PlainTextSanitizer.sanitize(meal.name, MAX_NAME_CODE_POINTS)
            if (name.isEmpty()) continue
            lines += truncate("${meal.mealType.label}: $name", MAX_LINE_CODE_POINTS)
        }
        if (lines.isEmpty()) return null

        val shown = lines.take(MAX_LINES)
        val hidden = lines.size - shown.size
        val body = StringBuilder()
        for (index in shown.indices) {
            if (index > 0) body.append(SEPARATOR)
            body.append(shown[index])
        }
        if (hidden > 0) {
            body.append(SEPARATOR)
            body.append("y ")
            body.append(hidden)
            body.append(" más")
        }

        return ReminderMessage(
            title = TITLE,
            body = body.toString(),
            mealCount = lines.size,
            hiddenCount = hidden,
        )
    }

    /**
     * Aviso a partir de la copia cifrada del plan (decisión D1: sin red en
     * segundo plano). `null` si ese día no hay comidas guardadas.
     */
    fun of(plan: CachedMealPlan, date: LocalDate): ReminderMessage? =
        of(plan.mealsOn(date).map { ReminderMeal(it.mealType, it.title) })

    /** Recorta [text] a [maxCodePoints] puntos de código, con «…» si se corta. */
    private fun truncate(text: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ELLIPSIS
        if (text.codePointCount(0, text.length) <= maxCodePoints) return text
        var index = 0
        var count = 0
        while (index < text.length && count < maxCodePoints - 1) {
            index += Character.charCount(text.codePointAt(index))
            count++
        }
        return text.substring(0, index) + ELLIPSIS
    }
}
