package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDates
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanWeekView
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.mealplan.PlannedMeal
import io.github.garfieldrockero.gastronomicon.core.network.CoreJson
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.TreeMap

/*
 * Formato persistido de la copia del planificador para el recordatorio.
 *
 * Solo se guarda lo que el aviso necesita: fecha, tipo de comida y un nombre ya
 * seguro. **No** se guarda la nota libre (decisión D10 y bloqueante de
 * seguridad), ni el id, ni la receta, ni el `rawJson`. Así la copia no sirve
 * para reconstruir una entrada ni para `PUT`, y una pantalla de bloqueo no puede
 * enseñar el texto más delicado de la persona.
 *
 * El nombre se compone aquí, no con `MealPlanMealView.from`, porque esa vista
 * usa la nota como etiqueta cuando no hay receta ni título. La regla de esta
 * copia es: receta → título escrito → «Receta no disponible» si apuntaba a una
 * receta que no vino; si no hay ninguna de las tres, la entrada **se descarta**
 * (solo tenía una nota, y la nota no sale de aquí).
 */

@Serializable
internal class StoredMealPlan(
    val format: Int = 0,
    // Ámbito al que pertenece el blob: si no coincide con el de la clave, se
    // descarta. Un fichero copiado a otra cuenta no se sirve.
    val scope: String = "",
    val savedAt: Long = 0,
    val days: List<StoredMealPlanDay> = emptyList(),
)

@Serializable
internal class StoredMealPlanDay(
    val date: String = "",
    val meals: List<StoredPlannedMeal> = emptyList(),
)

@Serializable
internal class StoredPlannedMeal(
    /** `apiValue` de `MealType`; vacío si el servidor mandó un tipo desconocido. */
    val mealType: String = "",
    /** Nombre ya saneado y acotado; nunca una nota ni un campo crudo. */
    val title: String = "",
)

/**
 * Serialización y validación de la copia del plan.
 *
 * Misma disciplina que [RecipeCacheCodec]: el mismo camino sirve para guardar y
 * para leer, y al leer se vuelve a sanear todo porque el disco no es de fiar.
 * Cualquier incoherencia descarta el blob **entero** (falla cerrado): versión,
 * ámbito, fecha imposible, más días o comidas de los permitidos, o un título que
 * no pasa el saneado.
 */
internal object MealPlanCacheCodec {

    /** Días que se conservan como mucho (la semana que se mira). */
    const val MAX_DAYS = 7

    /** Comidas por día; el mismo tope que la vista de la semana. */
    const val MAX_MEALS_PER_DAY = 50

    /** Tope del nombre guardado, en puntos de código. */
    const val MAX_TITLE_CODE_POINTS = 120

    /**
     * Serializa el plan de [scope], o `null` si no se puede. [savedAtMillis]
     * tiene que ser positivo.
     *
     * Se ordenan las fechas y las comidas para que el blob sea determinista, se
     * descartan las entradas sin receta ni título (su única información sería
     * la nota) y se acotan días, comidas y nombres.
     */
    fun encode(scope: ServerScope, meals: List<PlannedMeal>, savedAtMillis: Long): ByteArray? {
        if (savedAtMillis <= 0) return null

        val byDate = TreeMap<LocalDate, MutableList<PlannedMeal>>()
        for (meal in meals) {
            if (meal.date.year !in MealPlanDates.MIN_YEAR..MealPlanDates.MAX_YEAR) continue
            byDate.getOrPut(meal.date) { ArrayList() }.add(meal)
        }

        val days = ArrayList<StoredMealPlanDay>(minOf(byDate.size, MAX_DAYS))
        for ((date, dayMeals) in byDate) {
            if (days.size >= MAX_DAYS) break
            val storedMeals = encodeMeals(dayMeals)
            if (storedMeals.isEmpty()) continue
            days += StoredMealPlanDay(date = date.toString(), meals = storedMeals)
        }

        val stored = StoredMealPlan(
            format = OfflineFormat.CURRENT,
            scope = scope.id,
            savedAt = savedAtMillis,
            days = days,
        )
        return try {
            CoreJson.json.encodeToString(StoredMealPlan.serializer(), stored).toByteArray(Charsets.UTF_8)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /**
     * Lee una copia de [scope]. [maxBytes] evita parsear un blob desmesurado.
     * Null ante cualquier incoherencia; quien llama borra el blob y responde
     * `Miss`.
     */
    fun decode(bytes: ByteArray, scope: ServerScope, maxBytes: Int): CachedMealPlan? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null

        val stored = try {
            CoreJson.json.decodeFromString(StoredMealPlan.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (ignored: IllegalArgumentException) {
            // Incluye SerializationException, que hereda de IllegalArgumentException.
            return null
        } catch (ignored: StackOverflowError) {
            return null
        }
        if (stored.format != OfflineFormat.CURRENT) return null
        if (stored.scope != scope.id) return null
        if (stored.savedAt <= 0) return null
        if (stored.days.size > MAX_DAYS) return null

        val dates = HashSet<LocalDate>(stored.days.size * 2)
        val days = ArrayList<CachedDay>(stored.days.size)
        for (day in stored.days) {
            val date = parseDate(day.date) ?: return null
            if (!dates.add(date)) return null
            if (day.meals.size > MAX_MEALS_PER_DAY) return null
            val meals = ArrayList<CachedMeal>(day.meals.size)
            for (meal in day.meals) {
                val title = cleanTitle(meal.title) ?: return null
                meals += CachedMeal(mealType = decodeMealType(meal.mealType), title = title)
            }
            days += CachedDay(date = date, meals = meals)
        }
        return CachedMealPlan(savedAtMillis = stored.savedAt, days = days)
    }

    // ------------------------------------------------------------- interno

    private fun encodeMeals(dayMeals: List<PlannedMeal>): List<StoredPlannedMeal> {
        val result = ArrayList<StoredPlannedMeal>(minOf(dayMeals.size, MAX_MEALS_PER_DAY))
        val seen = HashSet<Long>(dayMeals.size * 2)
        val sorted = dayMeals.sortedWith(
            compareBy<PlannedMeal> { it.mealType.ordinal }.thenBy { it.id },
        )
        for (meal in sorted) {
            if (result.size >= MAX_MEALS_PER_DAY) break
            if (!seen.add(meal.id)) continue
            // La nota libre no se lee en ningún caso: si no hay receta ni
            // título, labelOf devuelve null y la entrada se queda fuera.
            val label = labelOf(meal) ?: continue
            result += StoredPlannedMeal(mealType = meal.mealType.apiValue, title = label)
        }
        return result
    }

    /**
     * Nombre seguro de una entrada. **Nunca** usa `meal.note`.
     *
     * Se aplica el mismo criterio que la vista del plan para no inventar: la
     * receta manda; si no, el título escrito; si apuntaba a una receta que no
     * vino, se dice; y si solo había una nota, `null` (entrada descartada).
     */
    private fun labelOf(meal: PlannedMeal): String? {
        val recipeName = PlainTextSanitizer.sanitize(meal.recipeName, MAX_TITLE_CODE_POINTS)
        if (recipeName.isNotEmpty()) return recipeName
        val title = PlainTextSanitizer.sanitize(meal.title, MAX_TITLE_CODE_POINTS)
        if (title.isNotEmpty()) return title
        if (meal.recipeId != null) return MealPlanWeekView.MISSING_RECIPE_LABEL
        return null
    }

    private fun cleanTitle(raw: String): String? {
        if (raw.codePointCount(0, raw.length) > MAX_TITLE_CODE_POINTS) return null
        val clean = PlainTextSanitizer.sanitize(raw, MAX_TITLE_CODE_POINTS)
        return clean.ifEmpty { null }
    }

    /**
     * Tipo de comida guardado. No se usa [MealType.fromApi] porque un valor
     * vacío allí significa `breakfast` (el valor por defecto del esquema) y una
     * entrada desconocida se convertiría en desayuno: aquí un texto que no es de
     * un tipo conocido es [MealType.UNKNOWN].
     */
    private fun decodeMealType(raw: String): MealType =
        MealType.entries.firstOrNull { it.isKnown && it.apiValue == raw } ?: MealType.UNKNOWN

    private fun parseDate(raw: String): LocalDate? {
        if (raw.length != 10) return null
        val date = try {
            LocalDate.parse(raw)
        } catch (ignored: DateTimeParseException) {
            return null
        }
        if (date.year !in MealPlanDates.MIN_YEAR..MealPlanDates.MAX_YEAR) return null
        if (date.toString() != raw) return null
        return date
    }
}
