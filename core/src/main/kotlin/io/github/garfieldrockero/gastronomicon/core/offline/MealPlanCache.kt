package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.mealplan.MealType
import io.github.garfieldrockero.gastronomicon.core.mealplan.PlannedMeal
import java.time.LocalDate

/**
 * Copia cifrada de las comidas del plan, para que el recordatorio no necesite
 * red ni token en segundo plano (decisión D1).
 *
 * - **Un solo blob por ámbito** ([ServerScope]: servidor y cuenta), con clave
 *   `mp1.<scope>`. Lo que se consulta es un día, no una colección, así que no
 *   hace falta índice: menos ficheros, menos estado que pueda quedar incoherente.
 * - **Solo lo que el aviso necesita**: fecha, tipo de comida y nombre seguro. La
 *   nota libre **no se guarda nunca** (decisión D10): una entrada que solo tenía
 *   nota se descarta. Tampoco se guardan ids, recetas ni `rawJson`.
 * - **Datos del disco = datos no confiables.** Al leer se vuelve a sanear y
 *   validar ([MealPlanCacheCodec]); si algo no cuadra se borra el blob y se
 *   responde `Miss`, nunca una excepción.
 * - **Caducidad de 7 días** (decisión D9) y **la copia caducada se borra**
 *   (decisión D12): un plan de hace dos semanas no es de fiar y no se avisa con
 *   él.
 * - **Falla cerrado**: sin blob, con un blob ilegible o con el almacén caído no
 *   hay aviso. Un fallo transitorio del almacén es `Failed` (no `Miss`) para que
 *   quien llama sepa que no es que no haya datos.
 * - [clear] vacía todo (también lo barre [OfflineWipe] al cerrar la sesión);
 *   [clear] con ámbito solo toca esa cuenta.
 */
class MealPlanCache(
    private val store: EncryptedBlobStore,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    init {
        require(maxBytes > 0) { "El tamaño máximo del plan debe ser positivo." }
        require(ttlMillis > 0) { "La caducidad debe ser positiva." }
    }

    /**
     * Sustituye la copia de [scope] por [meals]. Si no cabe en [maxBytes] no se
     * guarda y se borra la anterior: servir un plan viejo como si fuera el de
     * ahora sería peor que no avisar.
     */
    fun save(scope: ServerScope, meals: List<PlannedMeal>): OfflineWrite = synchronized(lock) {
        val key = keyOf(scope)
        val savedAt = now()
        if (savedAt <= 0) return OfflineWrite.Rejected

        val bytes = MealPlanCacheCodec.encode(scope, meals, savedAt)
        if (bytes == null) return OfflineWrite.Rejected
        if (bytes.size > maxBytes) {
            BlobIo.delete(store, key)
            return OfflineWrite.TooLarge
        }
        when (val put = BlobIo.put(store, key, bytes)) {
            is StoreResult.Ok -> OfflineWrite.Saved
            is StoreResult.Failed -> OfflineWrite.Failed(put.problem)
        }
    }

    /**
     * Copia de [scope] si existe, no ha caducado y pasa el saneado. Si existe
     * pero no vale, se borra y se responde `Miss`.
     */
    fun load(scope: ServerScope): OfflineRead<CachedMealPlan> = synchronized(lock) {
        val key = keyOf(scope)
        val read = BlobIo.get(store, key)
        when (read) {
            is StoreResult.Failed -> {
                if (read.problem == StorageProblem.Corrupt) {
                    // No descifra: no se puede recuperar, se descarta.
                    BlobIo.delete(store, key)
                    OfflineRead.Miss
                } else {
                    OfflineRead.Failed(read.problem)
                }
            }
            is StoreResult.Ok -> {
                val bytes = read.value ?: return OfflineRead.Miss
                if (bytes.size > maxBytes) {
                    // Tope incumplido: manipulado o de otra configuración.
                    BlobIo.delete(store, key)
                    return OfflineRead.Miss
                }
                val plan = MealPlanCacheCodec.decode(bytes, scope, maxBytes)
                if (plan == null) {
                    BlobIo.delete(store, key)
                    return OfflineRead.Miss
                }
                val current = now()
                if (plan.savedAtMillis > current + FUTURE_SKEW_MILLIS ||
                    current - plan.savedAtMillis > ttlMillis
                ) {
                    // Caducada o con la marca en el futuro: se borra y no se usa.
                    BlobIo.delete(store, key)
                    return OfflineRead.Miss
                }
                OfflineRead.Hit(plan)
            }
        }
    }

    /** Borra la copia de una cuenta. No toca las demás. */
    fun clear(scope: ServerScope): OfflineWrite = synchronized(lock) {
        when (val deleted = BlobIo.delete(store, keyOf(scope))) {
            is StoreResult.Ok -> OfflineWrite.Saved
            is StoreResult.Failed -> OfflineWrite.Failed(deleted.problem)
        }
    }

    /** Borra las copias de todos los servidores y cuentas. */
    fun clear(): OfflineWrite = synchronized(lock) {
        val problem = BlobIo.deleteUnder(store, BlobPrefix.MEAL_PLAN_ENTRY)
        if (problem == null) OfflineWrite.Saved else OfflineWrite.Failed(problem)
    }

    private fun keyOf(scope: ServerScope): String =
        BlobName.index(BlobPrefix.MEAL_PLAN_ENTRY, scope)

    companion object {

        /** Tope del blob por servidor y cuenta: un plan de una semana cabe de sobra. */
        const val DEFAULT_MAX_BYTES = 64 * 1024

        /** Una semana: más viejo no es de fiar (decisión D9). */
        const val DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Margen para relojes desajustados al comparar marcas de tiempo. */
        private const val FUTURE_SKEW_MILLIS = 5L * 60 * 1000
    }
}

/**
 * Copia del plan ya validada. Solo guarda lo que el aviso enseña; no hay ids ni
 * notas que reconstruir.
 */
class CachedMealPlan internal constructor(
    val savedAtMillis: Long,
    val days: List<CachedDay>,
) {

    /** Comidas del [date], ya ordenadas por tipo de comida. */
    fun mealsOn(date: LocalDate): List<CachedMeal> =
        days.firstOrNull { it.date == date }?.meals ?: emptyList()

    fun hasMealsOn(date: LocalDate): Boolean = mealsOn(date).isNotEmpty()

    // Sin nombres ni fechas: el plan puede acabar en un log al depurar.
    override fun toString(): String =
        "CachedMealPlan(savedAtMillis=$savedAtMillis, days=${days.size})"
}

/** Un día de la copia, con sus comidas. */
class CachedDay internal constructor(
    val date: LocalDate,
    val meals: List<CachedMeal>,
) {
    override fun toString(): String = "CachedDay(date=$date, meals=${meals.size})"
}

/** Una comida de la copia: tipo y nombre ya seguros, sin nota. */
class CachedMeal internal constructor(
    val mealType: MealType,
    val title: String,
) {
    // El título es contenido de la persona: no se arrastra a un log.
    override fun toString(): String = "CachedMeal(mealType=$mealType)"
}
