package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug

/**
 * Caché de solo lectura con el detalle de las recetas ya abiertas.
 *
 * Decisiones de la fase 8:
 *
 * - Se guarda el **modelo ya saneado** de `core/recipes`, no el JSON del
 *   servidor: al leer no hay que confiar en campos que la app no conoce.
 * - El ámbito es el servidor **y la cuenta** ([ServerScope]); ni una receta de
 *   otro servidor ni la de otro usuario del mismo Mealie se mezclan.
 * - El índice va ligado al ámbito y a la versión del formato, y cada entrada al
 *   hash de su clave: si no cuadra, se descarta **todo** lo del ámbito.
 * - Todo lo leído del disco se vuelve a sanear ([RecipeCacheCodec]); si no
 *   cuadra se descarta la entrada y se responde `Miss`, nunca una excepción.
 * - Sin reintentos y sin logs. Los fallos son [OfflineWrite.Failed] o
 *   [OfflineRead.Failed].
 * - Hay topes por entrada, en total y de número, y caducidad configurable. Al
 *   pasarse, se expulsa la entrada más antigua. Cada guardado aprovecha para
 *   borrar las caducadas y los blobs sin índice, con un tope de trabajo para no
 *   bloquear.
 *
 * `clear(scope)` se llama al cerrar una cuenta; `clear()` y
 * [OfflineWipe.clearAll] al cerrar la sesión o cambiar el token.
 */
class RecipeCache(
    private val store: EncryptedBlobStore,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    init {
        require(maxEntries > 0) { "El número máximo de recetas debe ser positivo." }
        require(maxEntryBytes > 0) { "El tamaño máximo por receta debe ser positivo." }
        require(maxTotalBytes > 0) { "El tamaño máximo total debe ser positivo." }
        require(ttlMillis > 0) { "La caducidad debe ser positiva." }
    }

    /**
     * Guarda [recipe] en el ámbito [scope]. Sustituye la copia anterior del
     * mismo slug, expulsa las más antiguas si hace falta y purga lo caducado y
     * lo que no está en el índice.
     */
    fun save(scope: ServerScope, recipe: Recipe): OfflineWrite = synchronized(lock) {
        if (!RecipeSlug.isValid(recipe.slug)) return OfflineWrite.Rejected
        val entryKey = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, recipe.slug)
            ?: return OfflineWrite.Failed(StorageProblem.Unavailable)

        // El índice se lee antes de escribir nada: si no se puede leer, no se
        // toca el almacén (una entrada sin índice sería basura invisible).
        val entries = when (val state = readIndex(scope)) {
            is IndexState.Failed -> return OfflineWrite.Failed(state.problem)
            IndexState.Missing -> ArrayList<IndexEntry>()
            is IndexState.Loaded -> ArrayList(state.entries)
        }

        val savedAt = now()
        val bytes = RecipeCacheCodec.encode(recipe, savedAt, scope)
        if (bytes == null || bytes.size > maxEntryBytes) {
            // La copia anterior ya no vale: se quita también para no servir una
            // versión vieja como si fuera la receta actual.
            removeFromIndex(entries, recipe.slug)
            BlobIo.delete(store, entryKey)
            val purged = dropExpired(entries, savedAt)
            if (writeIndex(scope, entries) == null) {
                dropOrphans(scope, entries, MAX_PURGE_WORK - purged)
            }
            return OfflineWrite.TooLarge
        }

        when (val put = BlobIo.put(store, entryKey, bytes)) {
            is StoreResult.Failed -> return OfflineWrite.Failed(put.problem)
            is StoreResult.Ok -> Unit
        }

        entries.removeAll { it.itemId == recipe.slug || it.key == entryKey }
        entries += IndexEntry(key = entryKey, itemId = recipe.slug, bytes = bytes.size, cachedAt = savedAt)
        val purged = dropExpired(entries, savedAt)
        val evicted = evict(entries)
        for (entry in evicted) BlobIo.delete(store, entry.key)

        val problem = writeIndex(scope, entries)
        if (problem != null) {
            // Sin índice la entrada no se puede encontrar y quedaría huérfana.
            BlobIo.delete(store, entryKey)
            return OfflineWrite.Failed(problem)
        }
        // Con el índice ya escrito, lo que no aparezca en él sobra.
        dropOrphans(scope, entries, MAX_PURGE_WORK - purged)
        OfflineWrite.Saved
    }

    /**
     * Receta guardada para [slug], o `Miss` si no está, ha caducado o no ha
     * pasado el saneado.
     */
    fun load(scope: ServerScope, slug: String): OfflineRead<CachedRecipe> = synchronized(lock) {
        if (!RecipeSlug.isValid(slug)) return OfflineRead.Miss
        val entryKey = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, slug)
            ?: return OfflineRead.Failed(StorageProblem.Unavailable)

        val entries = when (val state = readIndex(scope)) {
            is IndexState.Failed -> return OfflineRead.Failed(state.problem)
            IndexState.Missing -> return OfflineRead.Miss
            is IndexState.Loaded -> state.entries
        }
        val info = entries.firstOrNull { it.itemId == slug } ?: return OfflineRead.Miss

        val currentTime = now()
        if (expired(info.cachedAt, currentTime) || info.bytes > maxEntryBytes) {
            forget(scope, slug)
            return OfflineRead.Miss
        }

        val bytes = when (val read = BlobIo.get(store, entryKey)) {
            is StoreResult.Failed -> {
                // Un blob que no descifra no se puede recuperar: se descarta.
                if (read.problem == StorageProblem.Corrupt) {
                    forget(scope, slug)
                    return OfflineRead.Miss
                }
                return OfflineRead.Failed(read.problem)
            }
            is StoreResult.Ok -> read.value ?: run {
                forget(scope, slug)
                return OfflineRead.Miss
            }
        }
        if (bytes.size != info.bytes || bytes.size > maxEntryBytes) {
            // El tamaño del índice y el del blob tienen que coincidir: si no,
            // uno de los dos está manipulado.
            forget(scope, slug)
            return OfflineRead.Miss
        }

        val cached = RecipeCacheCodec.decode(bytes, scope, slug, maxEntryBytes)
        if (cached == null) {
            forget(scope, slug)
            return OfflineRead.Miss
        }
        // La marca de la entrada y la del índice tienen que coincidir: si no,
        // una de las dos está manipulada y no se sabe cuál es la buena.
        if (cached.cachedAtMillis != info.cachedAt || expired(cached.cachedAtMillis, currentTime)) {
            forget(scope, slug)
            return OfflineRead.Miss
        }
        OfflineRead.Hit(cached)
    }

    /** Recetas guardadas (no caducadas), de la más reciente a la más antigua. */
    fun list(scope: ServerScope): OfflineList<CachedRecipeInfo> = synchronized(lock) {
        val entries = when (val state = readIndex(scope)) {
            is IndexState.Failed -> return OfflineList.Failed(state.problem)
            IndexState.Missing -> return OfflineList.Ok<CachedRecipeInfo>(emptyList())
            is IndexState.Loaded -> state.entries
        }
        val currentTime = now()
        val alive = entries.filter { !expired(it.cachedAt, currentTime) }
        if (alive.size != entries.size) {
            for (gone in entries) {
                if (expired(gone.cachedAt, currentTime)) BlobIo.delete(store, gone.key)
            }
            writeIndex(scope, alive)
        }
        OfflineList.Ok(
            alive.sortedByDescending { it.cachedAt }
                .map { CachedRecipeInfo(slug = it.itemId, cachedAtMillis = it.cachedAt, bytes = it.bytes) },
        )
    }

    /**
     * Borra la caché de una cuenta. No toca los borradores: para eso está
     * [DraftStore.clear] o, al cerrar la sesión, [OfflineWipe.clearAll].
     */
    fun clear(scope: ServerScope): OfflineWrite = synchronized(lock) {
        val first = BlobIo.deleteUnder(store, BlobPrefix.RECIPE_ENTRY + scope.id + ".")
        val second = BlobIo.deleteUnder(store, BlobPrefix.RECIPE_INDEX + scope.id)
        combine(first, second)
    }

    /**
     * Borra toda la caché de recetas de todos los servidores y cuentas. No toca
     * los borradores: al cambiar o borrar la sesión hay que llamar a
     * [OfflineWipe.clearAll], que barre todo lo offline.
     */
    fun clear(): OfflineWrite = synchronized(lock) {
        val first = BlobIo.deleteUnder(store, BlobPrefix.RECIPE_ENTRY)
        val second = BlobIo.deleteUnder(store, BlobPrefix.RECIPE_INDEX)
        combine(first, second)
    }

    // ------------------------------------------------------------- interno

    private sealed interface IndexState {
        data class Loaded(val entries: List<IndexEntry>) : IndexState
        data object Missing : IndexState
        data class Failed(val problem: StorageProblem) : IndexState
    }

    private fun readIndex(scope: ServerScope): IndexState {
        val key = BlobName.index(BlobPrefix.RECIPE_INDEX, scope)
        return when (val read = BlobIo.get(store, key)) {
            is StoreResult.Failed -> {
                if (read.problem == StorageProblem.Corrupt) {
                    // El índice no descifra: se borra y se deja de confiar en él.
                    BlobIo.delete(store, key)
                    deleteEntriesOf(scope)
                    IndexState.Missing
                } else {
                    IndexState.Failed(read.problem)
                }
            }
            is StoreResult.Ok -> {
                val bytes = read.value ?: return IndexState.Missing
                val entries = OfflineIndex.decode(bytes, scope, BlobPrefix.RECIPE_ENTRY) {
                    RecipeSlug.isValid(it)
                }
                if (entries == null) {
                    // Versión distinta, índice de otro ámbito o manipulado: se
                    // descarta todo lo del ámbito (falla cerrado).
                    BlobIo.delete(store, key)
                    deleteEntriesOf(scope)
                    IndexState.Missing
                } else {
                    IndexState.Loaded(entries)
                }
            }
        }
    }

    /** Quita [slug] del índice y del almacén (mejor esfuerzo). */
    private fun forget(scope: ServerScope, slug: String) {
        val key = BlobName.item(BlobPrefix.RECIPE_ENTRY, scope, slug) ?: return
        BlobIo.delete(store, key)
        when (val state = readIndex(scope)) {
            is IndexState.Loaded -> {
                val kept = state.entries.filterNot { it.itemId == slug || it.key == key }
                if (kept.size != state.entries.size) writeIndex(scope, kept)
            }
            else -> Unit
        }
    }

    private fun removeFromIndex(entries: MutableList<IndexEntry>, slug: String) {
        entries.removeAll { it.itemId == slug }
    }

    /**
     * Quita del índice las caducadas y borra su blob, hasta [MAX_PURGE_WORK]
     * entradas por operación. Devuelve cuántas se han borrado.
     */
    private fun dropExpired(entries: MutableList<IndexEntry>, currentTime: Long): Int {
        var used = 0
        val iterator = entries.iterator()
        while (iterator.hasNext() && used < MAX_PURGE_WORK) {
            val entry = iterator.next()
            if (!expired(entry.cachedAt, currentTime)) continue
            iterator.remove()
            BlobIo.delete(store, entry.key)
            used++
        }
        return used
    }

    /**
     * Borra blobs del ámbito que no están en el índice (huérfanos de un guardado
     * interrumpido o de un índice descartado). Mejor esfuerzo: un fallo de
     * borrado no invalida lo que ya está guardado. [budget] acota el trabajo.
     */
    private fun dropOrphans(scope: ServerScope, entries: List<IndexEntry>, budget: Int) {
        if (budget <= 0) return
        val prefix = BlobPrefix.RECIPE_ENTRY + scope.id + "."
        val known = HashSet<String>(entries.size * 2)
        for (entry in entries) known += entry.key
        val keys = when (val listed = BlobIo.list(store, prefix)) {
            is StoreResult.Ok -> listed.value
            is StoreResult.Failed -> return
        }
        var used = 0
        for (key in keys) {
            if (used >= budget) return
            if (!BlobKeys.isValid(key) || !key.startsWith(prefix)) continue
            if (key in known) continue
            when (BlobIo.delete(store, key)) {
                is StoreResult.Ok -> used++
                is StoreResult.Failed -> return
            }
        }
    }

    /** Expulsa las más antiguas hasta cumplir número y tamaño. Devuelve las quitadas. */
    private fun evict(entries: MutableList<IndexEntry>): List<IndexEntry> {
        val removed = ArrayList<IndexEntry>()
        while (entries.size > maxEntries) {
            val oldest = entries.minByOrNull { it.cachedAt } ?: break
            entries.remove(oldest)
            removed += oldest
        }
        var total = entries.sumOf { it.bytes.toLong() }
        while (total > maxTotalBytes && entries.size > 1) {
            val oldest = entries.minByOrNull { it.cachedAt } ?: break
            entries.remove(oldest)
            removed += oldest
            total -= oldest.bytes
        }
        return removed
    }

    private fun expired(cachedAt: Long, currentTime: Long): Boolean =
        cachedAt <= 0 || cachedAt > currentTime + FUTURE_SKEW_MILLIS || currentTime - cachedAt > ttlMillis

    private fun writeIndex(scope: ServerScope, entries: List<IndexEntry>): StorageProblem? {
        val bytes = OfflineIndex.encode(scope, entries) ?: return StorageProblem.Rejected
        return when (val put = BlobIo.put(store, BlobName.index(BlobPrefix.RECIPE_INDEX, scope), bytes)) {
            is StoreResult.Ok -> null
            is StoreResult.Failed -> put.problem
        }
    }

    private fun deleteEntriesOf(scope: ServerScope) {
        BlobIo.deleteUnder(store, BlobPrefix.RECIPE_ENTRY + scope.id + ".")
    }

    private fun combine(first: StorageProblem?, second: StorageProblem?): OfflineWrite =
        when {
            first != null -> OfflineWrite.Failed(first)
            second != null -> OfflineWrite.Failed(second)
            else -> OfflineWrite.Saved
        }

    companion object {

        /** Recetas diferentes que se conservan por servidor y cuenta. */
        const val DEFAULT_MAX_ENTRIES = 50

        /** Tope por receta (el detalle suele ocupar decenas de KB). */
        const val DEFAULT_MAX_ENTRY_BYTES = 512 * 1024

        /** Tope total por servidor y cuenta. */
        const val DEFAULT_MAX_TOTAL_BYTES = 8 * 1024 * 1024

        /** Un mes: más viejo que eso una receta guardada ya no es de fiar. */
        const val DEFAULT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * Borrados de purga por guardado (caducadas más huérfanos). Acota el
         * trabajo de una escritura: lo que no entre se limpia en la siguiente.
         */
        internal const val MAX_PURGE_WORK = 32

        /** Margen para relojes desajustados al comparar marcas de tiempo. */
        private const val FUTURE_SKEW_MILLIS = 5L * 60 * 1000
    }
}

/** Resumen de una receta cacheada, para ofrecerla sin conexión. */
class CachedRecipeInfo(
    val slug: String,
    val cachedAtMillis: Long,
    val bytes: Int,
) {
    // El slug es un identificador, no el título; aun así no hace falta en un log.
    override fun toString(): String = "CachedRecipeInfo(cachedAtMillis=$cachedAtMillis, bytes=$bytes)"
}
