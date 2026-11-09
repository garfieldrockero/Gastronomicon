package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft

/**
 * Borradores del formulario de receta.
 *
 * Hay como mucho una entrada por destino ([DraftTarget]): una por receta en
 * edición y una para el formulario de receta nueva, por servidor y cuenta. Se
 * borra al guardar con éxito o al descartar; no hay cola de escrituras: un
 * borrador no se reenvía solo al servidor.
 *
 * Como la caché, guarda en [EncryptedBlobStore], acota número y tamaño, caduca
 * y vuelve a sanear todo lo que lee. El blob lleva su identidad (ámbito y
 * destino) y se comprueba al leer: un borrador copiado a otra clave no se
 * sirve. `clear(scope)` se llama al cerrar una cuenta; [OfflineWipe.clearAll],
 * al cerrar la sesión.
 */
class DraftStore(
    private val store: EncryptedBlobStore,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    init {
        require(maxEntries > 0) { "El número máximo de borradores debe ser positivo." }
        require(maxEntryBytes > 0) { "El tamaño máximo por borrador debe ser positivo." }
        require(maxTotalBytes > 0) { "El tamaño máximo total debe ser positivo." }
        require(ttlMillis > 0) { "La caducidad debe ser positiva." }
    }

    /**
     * Guarda (o sustituye) el borrador de [target].
     *
     * El contenido se recorta a los máximos del formulario: es texto sin
     * guardar y se prefiere conservar lo que quepa a perderlo todo. Si aun así
     * pasa del tope de bytes, no se guarda y se devuelve [OfflineWrite.TooLarge].
     */
    fun save(scope: ServerScope, target: DraftTarget, draft: RecipeDraft): OfflineWrite = synchronized(lock) {
        val entryKey = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, target.id)
            ?: return OfflineWrite.Failed(StorageProblem.Unavailable)

        val entries = when (val state = readIndex(scope)) {
            is IndexState.Failed -> return OfflineWrite.Failed(state.problem)
            IndexState.Missing -> ArrayList<IndexEntry>()
            is IndexState.Loaded -> ArrayList(state.entries)
        }

        val savedAt = now()
        val bytes = DraftStoreCodec.encode(draft, savedAt, scope, target)
        if (bytes == null || bytes.size > maxEntryBytes) {
            // Un borrador que ya no cabe no debe dejar el anterior como si fuera el actual.
            entries.removeAll { it.itemId == target.id }
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

        entries.removeAll { it.itemId == target.id || it.key == entryKey }
        entries += IndexEntry(key = entryKey, itemId = target.id, bytes = bytes.size, cachedAt = savedAt)
        val purged = dropExpired(entries, savedAt)
        val evicted = evict(entries)
        for (entry in evicted) BlobIo.delete(store, entry.key)

        val problem = writeIndex(scope, entries)
        if (problem != null) {
            BlobIo.delete(store, entryKey)
            return OfflineWrite.Failed(problem)
        }
        dropOrphans(scope, entries, MAX_PURGE_WORK - purged)
        OfflineWrite.Saved
    }

    /** Borrador guardado para [target], o `Miss` si no hay, caducó o no pasó el saneado. */
    fun load(scope: ServerScope, target: DraftTarget): OfflineRead<CachedDraft> = synchronized(lock) {
        val entryKey = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, target.id)
            ?: return OfflineRead.Failed(StorageProblem.Unavailable)

        val entries = when (val state = readIndex(scope)) {
            is IndexState.Failed -> return OfflineRead.Failed(state.problem)
            IndexState.Missing -> return OfflineRead.Miss
            is IndexState.Loaded -> state.entries
        }
        val info = entries.firstOrNull { it.itemId == target.id } ?: return OfflineRead.Miss

        val currentTime = now()
        if (expired(info.cachedAt, currentTime) || info.bytes > maxEntryBytes) {
            forget(scope, target)
            return OfflineRead.Miss
        }

        val bytes = when (val read = BlobIo.get(store, entryKey)) {
            is StoreResult.Failed -> {
                if (read.problem == StorageProblem.Corrupt) {
                    forget(scope, target)
                    return OfflineRead.Miss
                }
                return OfflineRead.Failed(read.problem)
            }
            is StoreResult.Ok -> read.value ?: run {
                forget(scope, target)
                return OfflineRead.Miss
            }
        }
        if (bytes.size != info.bytes || bytes.size > maxEntryBytes) {
            // El tamaño del índice y el del blob tienen que coincidir.
            forget(scope, target)
            return OfflineRead.Miss
        }

        val cached = DraftStoreCodec.decode(bytes, scope, target, maxEntryBytes)
        if (cached == null || cached.savedAtMillis != info.cachedAt ||
            expired(cached.savedAtMillis, currentTime)
        ) {
            forget(scope, target)
            return OfflineRead.Miss
        }
        OfflineRead.Hit(cached)
    }

    /** Borradores guardados y no caducados, del más reciente al más antiguo. */
    fun list(scope: ServerScope): OfflineList<DraftInfo> = synchronized(lock) {
        val entries = when (val state = readIndex(scope)) {
            is IndexState.Failed -> return OfflineList.Failed(state.problem)
            IndexState.Missing -> return OfflineList.Ok<DraftInfo>(emptyList())
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
        val items = ArrayList<DraftInfo>(alive.size)
        for (entry in alive.sortedByDescending { it.cachedAt }) {
            val target = DraftTarget.of(entry.itemId) ?: continue
            items += DraftInfo(target = target, savedAtMillis = entry.cachedAt, bytes = entry.bytes)
        }
        OfflineList.Ok(items)
    }

    /**
     * Borra el borrador de [target]. Se llama al guardar con éxito o al
     * descartar. No pasa nada si no había ninguno.
     */
    fun delete(scope: ServerScope, target: DraftTarget): OfflineWrite = synchronized(lock) {
        val entryKey = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, target.id)
            ?: return OfflineWrite.Failed(StorageProblem.Unavailable)
        val entries = when (val state = readIndex(scope)) {
            is IndexState.Failed -> return OfflineWrite.Failed(state.problem)
            IndexState.Missing -> ArrayList<IndexEntry>()
            is IndexState.Loaded -> ArrayList(state.entries)
        }
        entries.removeAll { it.itemId == target.id || it.key == entryKey }
        when (val deleted = BlobIo.delete(store, entryKey)) {
            is StoreResult.Failed -> return OfflineWrite.Failed(deleted.problem)
            is StoreResult.Ok -> Unit
        }
        val problem = writeIndex(scope, entries)
        if (problem != null) OfflineWrite.Failed(problem) else OfflineWrite.Saved
    }

    /**
     * Borra los borradores de una cuenta. No toca la caché de recetas: para eso
     * está [RecipeCache.clear] o, al cerrar la sesión, [OfflineWipe.clearAll].
     */
    fun clear(scope: ServerScope): OfflineWrite = synchronized(lock) {
        val first = BlobIo.deleteUnder(store, BlobPrefix.DRAFT_ENTRY + scope.id + ".")
        val second = BlobIo.deleteUnder(store, BlobPrefix.DRAFT_INDEX + scope.id)
        combine(first, second)
    }

    /**
     * Borra todos los borradores de todos los servidores y cuentas. No toca la
     * caché de recetas: al cambiar o borrar la sesión hay que llamar a
     * [OfflineWipe.clearAll], que barre todo lo offline.
     */
    fun clear(): OfflineWrite = synchronized(lock) {
        val first = BlobIo.deleteUnder(store, BlobPrefix.DRAFT_ENTRY)
        val second = BlobIo.deleteUnder(store, BlobPrefix.DRAFT_INDEX)
        combine(first, second)
    }

    // ------------------------------------------------------------- interno

    private sealed interface IndexState {
        data class Loaded(val entries: List<IndexEntry>) : IndexState
        data object Missing : IndexState
        data class Failed(val problem: StorageProblem) : IndexState
    }

    private fun readIndex(scope: ServerScope): IndexState {
        val key = BlobName.index(BlobPrefix.DRAFT_INDEX, scope)
        return when (val read = BlobIo.get(store, key)) {
            is StoreResult.Failed -> {
                if (read.problem == StorageProblem.Corrupt) {
                    BlobIo.delete(store, key)
                    deleteEntriesOf(scope)
                    IndexState.Missing
                } else {
                    IndexState.Failed(read.problem)
                }
            }
            is StoreResult.Ok -> {
                val bytes = read.value ?: return IndexState.Missing
                val entries = OfflineIndex.decode(bytes, scope, BlobPrefix.DRAFT_ENTRY) {
                    DraftTarget.of(it) != null
                }
                if (entries == null) {
                    BlobIo.delete(store, key)
                    deleteEntriesOf(scope)
                    IndexState.Missing
                } else {
                    IndexState.Loaded(entries)
                }
            }
        }
    }

    private fun forget(scope: ServerScope, target: DraftTarget) {
        val key = BlobName.item(BlobPrefix.DRAFT_ENTRY, scope, target.id) ?: return
        BlobIo.delete(store, key)
        when (val state = readIndex(scope)) {
            is IndexState.Loaded -> {
                val kept = state.entries.filterNot { it.itemId == target.id || it.key == key }
                if (kept.size != state.entries.size) writeIndex(scope, kept)
            }
            else -> Unit
        }
    }

    /**
     * Quita del índice los caducados y borra su blob, hasta [MAX_PURGE_WORK]
     * entradas por operación. Devuelve cuántos se han borrado.
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

    /** Borra borradores del ámbito que no están en el índice. Mejor esfuerzo. */
    private fun dropOrphans(scope: ServerScope, entries: List<IndexEntry>, budget: Int) {
        if (budget <= 0) return
        val prefix = BlobPrefix.DRAFT_ENTRY + scope.id + "."
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
        return when (val put = BlobIo.put(store, BlobName.index(BlobPrefix.DRAFT_INDEX, scope), bytes)) {
            is StoreResult.Ok -> null
            is StoreResult.Failed -> put.problem
        }
    }

    private fun deleteEntriesOf(scope: ServerScope) {
        BlobIo.deleteUnder(store, BlobPrefix.DRAFT_ENTRY + scope.id + ".")
    }

    private fun combine(first: StorageProblem?, second: StorageProblem?): OfflineWrite =
        when {
            first != null -> OfflineWrite.Failed(first)
            second != null -> OfflineWrite.Failed(second)
            else -> OfflineWrite.Saved
        }

    companion object {

        /** Recetas con borrador que se conservan por servidor y cuenta. */
        const val DEFAULT_MAX_ENTRIES = 20

        /** Tope por borrador. */
        const val DEFAULT_MAX_ENTRY_BYTES = 512 * 1024

        /** Tope total por servidor y cuenta. */
        const val DEFAULT_MAX_TOTAL_BYTES = 2 * 1024 * 1024

        /** Dos semanas: un borrador más viejo ya no se ofrece al abrir el formulario. */
        const val DEFAULT_TTL_MILLIS = 14L * 24 * 60 * 60 * 1000

        /**
         * Borrados de purga por guardado (caducados más huérfanos). Acota el
         * trabajo de una escritura: lo que no entre se limpia en la siguiente.
         */
        internal const val MAX_PURGE_WORK = 32

        private const val FUTURE_SKEW_MILLIS = 5L * 60 * 1000
    }
}

/** Resumen de un borrador guardado, para ofrecer recuperarlo. */
class DraftInfo(
    val target: DraftTarget,
    val savedAtMillis: Long,
    val bytes: Int,
) {
    override fun toString(): String = "DraftInfo(savedAtMillis=$savedAtMillis, bytes=$bytes)"
}
