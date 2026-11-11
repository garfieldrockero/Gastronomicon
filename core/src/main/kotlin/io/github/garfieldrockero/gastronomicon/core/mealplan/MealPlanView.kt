package io.github.garfieldrockero.gastronomicon.core.mealplan

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Secciones principales de un día, en orden de reloj. No es el orden de
 * declaración de [MealType]: allí la cena va antes que la guarnición y el
 * aperitivo porque ese orden se usa para ordenar las comidas sueltas, y aquí
 * manda cómo se lee una comida del día (desayuno, comida, aperitivo, cena).
 */
private val MAIN_SECTION_ORDER: List<MealType> =
    listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.SNACK, MealType.DINNER)

/**
 * Una comida tal como se pinta. El título ya está compuesto (nombre de la
 * receta saneado o texto libre) y nunca queda vacío: si no hay nada, dice
 * explícitamente que la entrada está sin título, y si apuntaba a una receta
 * que el servidor ya no devuelve, lo dice en lugar de inventarse un nombre.
 *
 * [note] es lo que acompaña al título (el texto libre, o el título escrito si
 * se está enseñando el nombre de la receta). Los textos se vuelven a pasar por
 * `PlainTextSanitizer` aunque vengan saneados del cliente: la vista no depende
 * de que quien construyó el [PlannedMeal] se acordara.
 */
data class MealPlanMealView(
    val id: Long,
    val date: LocalDate,
    val mealType: MealType,
    val title: String,
    val note: String,
    val hasRecipe: Boolean,
    val recipeMissing: Boolean,
    val isBlank: Boolean,
    val recipeScale: Double,
) {
    /** «×2» o «×1,5» si la ración está escalada; null si es la de siempre. */
    val scaleText: String?
        get() = if (hasRecipe && recipeScale != 1.0) "×" + formatScale(recipeScale) else null

    // Sin los textos: son datos del servidor y una vista puede acabar en un log.
    override fun toString(): String =
        "MealPlanMealView(id=$id, date=$date, mealType=$mealType, hasRecipe=$hasRecipe, " +
            "recipeMissing=$recipeMissing)"

    companion object {
        private const val MAX_LABEL = 300

        fun from(meal: PlannedMeal): MealPlanMealView {
            val recipeName = PlainTextSanitizer.sanitize(meal.recipeName, MAX_LABEL)
            val title = PlainTextSanitizer.sanitize(meal.title, MAX_LABEL)
            val note = PlainTextSanitizer.sanitize(meal.note, MAX_LABEL)
            val hasRecipe = meal.recipeId != null
            val missing = hasRecipe && recipeName.isEmpty()

            val label: String
            val extra: String
            when {
                recipeName.isNotEmpty() -> {
                    label = recipeName
                    extra = join(title, note)
                }
                title.isNotEmpty() -> {
                    label = title
                    extra = note
                }
                note.isNotEmpty() -> {
                    label = note
                    extra = ""
                }
                missing -> {
                    label = MealPlanWeekView.MISSING_RECIPE_LABEL
                    extra = ""
                }
                else -> {
                    label = MealPlanWeekView.UNTITLED_MEAL_LABEL
                    extra = ""
                }
            }

            return MealPlanMealView(
                id = meal.id,
                date = meal.date,
                mealType = meal.mealType,
                title = label,
                note = extra,
                hasRecipe = hasRecipe,
                recipeMissing = missing,
                isBlank = meal.isBlank,
                recipeScale = meal.recipeScale,
            )
        }

        private fun join(first: String, second: String): String = when {
            first.isEmpty() -> second
            second.isEmpty() -> first
            else -> "$first · $second"
        }

        private fun formatScale(value: Double): String {
            val rounded = Math.round(value * 10.0) / 10.0
            return if (rounded % 1.0 == 0.0) {
                rounded.toLong().toString()
            } else {
                rounded.toString().replace('.', ',')
            }
        }
    }
}

/**
 * Sección de comidas de un día: el desayuno, la comida, el aperitivo, la cena
 * o el cajón «Otras».
 *
 * [title] es un texto fijo de la app ([MealType.label] o «Otras»), nunca un
 * dato del servidor; por eso se puede registrar en un log sin riesgo.
 * [isOther] distingue el cajón de guarniciones, bebidas, postres y tipos
 * desconocidos, que la interfaz pinta distinto (cada comida lleva su etiqueta
 * de tipo). El agrupado se calcula aquí: la pantalla solo recorre la lista.
 */
data class MealPlanSectionView(
    val title: String,
    val isOther: Boolean,
    val meals: List<MealPlanMealView>,
) {
    // Sin las comidas: sus títulos son datos del servidor y una vista puede acabar en un log.
    override fun toString(): String =
        "MealPlanSectionView(title=$title, isOther=$isOther, meals=${meals.size})"
}

/** Un día de la semana con sus comidas ya ordenadas y un hueco explícito si no hay ninguna. */
data class MealPlanDayView(
    val date: LocalDate,
    val dayOfWeek: DayOfWeek,
    /** «lunes 9 de noviembre», ya compuesto. */
    val dayLabel: String,
    val isToday: Boolean,
    val meals: List<MealPlanMealView>,
) {
    val isEmpty: Boolean get() = meals.isEmpty()

    /**
     * Las mismas [meals] agrupadas por sección, en orden de reloj y sin
     * secciones vacías: desayuno, comida, aperitivo y cena, y después una sola
     * «Otras» con guarnición, bebida, postre y tipos desconocidos (en el orden
     * de declaración de [MealType]).
     *
     * Dentro de cada sección manda el `id`, así que el orden no depende de que
     * quien construya la vista haya ordenado antes [meals]. Un día sin comidas
     * no tiene secciones (lista vacía), y el tope de comidas por día ya lo puso
     * `MealPlanWeekView.of`.
     */
    val sections: List<MealPlanSectionView>
        get() {
            if (meals.isEmpty()) return emptyList()

            val byType = meals.groupBy { it.mealType }
            val result = ArrayList<MealPlanSectionView>(MAIN_SECTION_ORDER.size + 1)
            for (type in MAIN_SECTION_ORDER) {
                val ofType = byType[type] ?: continue
                result += MealPlanSectionView(
                    title = type.label,
                    isOther = false,
                    meals = ofType.sortedBy { it.id },
                )
            }

            val others = meals
                .filter { it.mealType !in MAIN_SECTION_ORDER }
                .sortedWith(compareBy<MealPlanMealView> { it.mealType.ordinal }.thenBy { it.id })
            if (others.isNotEmpty()) {
                result += MealPlanSectionView(
                    title = MealPlanWeekView.OTHER_SECTION_LABEL,
                    isOther = true,
                    meals = others,
                )
            }
            return result
        }

    /** «Sin comidas» cuando el día está vacío; null cuando hay algo que enseñar. */
    val emptyLabel: String?
        get() = if (meals.isEmpty()) MealPlanWeekView.EMPTY_DAY_LABEL else null

    // Sin el contenido: son datos del servidor.
    override fun toString(): String =
        "MealPlanDayView(date=$date, meals=${meals.size}, isToday=$isToday)"
}

/**
 * Una semana completa, de lunes a domingo, con todo calculado: los siete días
 * en orden (también los vacíos), cada día con sus comidas ordenadas por tipo,
 * el indicador de «hoy» y los lunes de la semana anterior y siguiente.
 *
 * [of] normaliza [weekStart] al lunes de su semana y descarta lo que caiga
 * fuera: así una entrada movida a otra semana no se cuela en la vista. Las
 * entradas repetidas por id cuentan una vez (el servidor puede repetirlas al
 * paginar) y cada día se acota a [MAX_MEALS_PER_DAY].
 */
data class MealPlanWeekView(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val today: LocalDate,
    /** «Semana del 9 al 15 de noviembre de 2026». */
    val title: String,
    val days: List<MealPlanDayView>,
    val previousWeekStart: LocalDate,
    val nextWeekStart: LocalDate,
) {
    /** La semana que se está mirando contiene el día de hoy. */
    val isCurrentWeek: Boolean
        get() = contains(today)

    /**
     * ¿[date] cae en esta semana? Lo usa la interfaz para saber si una comida
     * guardada se queda a la vista o se va a otra semana; la comparación de
     * fechas no se hace en la pantalla.
     */
    fun contains(date: LocalDate): Boolean = !date.isBefore(weekStart) && !date.isAfter(weekEnd)

    val hasMeals: Boolean get() = days.any { it.meals.isNotEmpty() }

    val mealCount: Int get() = days.sumOf { it.meals.size }

    // Sin el contenido: son datos del servidor y una vista puede acabar en un log.
    override fun toString(): String =
        "MealPlanWeekView(weekStart=$weekStart, meals=$mealCount, isCurrentWeek=$isCurrentWeek)"

    companion object {
        /** Texto del día sin comidas; la interfaz no tiene que decidirlo. */
        const val EMPTY_DAY_LABEL = "Sin comidas"

        /** Texto cuando no hay receta, ni título, ni texto que enseñar. */
        const val UNTITLED_MEAL_LABEL = "Comida sin título"

        /** Texto cuando la entrada apunta a una receta que el servidor ya no devuelve. */
        const val MISSING_RECIPE_LABEL = "Receta no disponible"

        /** Título del cajón de guarniciones, bebidas, postres y tipos desconocidos. */
        const val OTHER_SECTION_LABEL = "Otras"

        private const val MAX_MEALS_PER_DAY = 50

        /**
         * Etiqueta de un día suelto, compuesta en el núcleo:
         * «miércoles 18 de noviembre», con el año cuando no es el de [today]
         * («viernes 1 de enero de 2027»). La interfaz no formatea fechas ni
         * depende del idioma del móvil.
         */
        fun dayLabel(date: LocalDate, today: LocalDate): String =
            MealPlanLabels.dayWithYear(date, today)

        fun of(weekStart: LocalDate, meals: List<PlannedMeal>, today: LocalDate): MealPlanWeekView {
            val start = MealPlanDates.weekStart(weekStart)
            val end = start.plusDays(6)

            val byDate = HashMap<LocalDate, MutableList<PlannedMeal>>()
            for (meal in meals) {
                if (meal.date.isBefore(start) || meal.date.isAfter(end)) continue
                byDate.getOrPut(meal.date) { ArrayList() }.add(meal)
            }

            val days = ArrayList<MealPlanDayView>(7)
            var day = start
            repeat(7) {
                val dayMeals = byDate[day].orEmpty()
                    .distinctBy { it.id }
                    .sortedWith(compareBy<PlannedMeal> { it.mealType.ordinal }.thenBy { it.id })
                    .take(MAX_MEALS_PER_DAY)
                    .map { MealPlanMealView.from(it) }
                days += MealPlanDayView(
                    date = day,
                    dayOfWeek = day.dayOfWeek,
                    dayLabel = MealPlanLabels.day(day),
                    isToday = day == today,
                    meals = dayMeals,
                )
                day = day.plusDays(1)
            }

            return MealPlanWeekView(
                weekStart = start,
                weekEnd = end,
                today = today,
                title = MealPlanLabels.week(start, end),
                days = days,
                previousWeekStart = start.minusWeeks(1),
                nextWeekStart = start.plusWeeks(1),
            )
        }
    }
}
