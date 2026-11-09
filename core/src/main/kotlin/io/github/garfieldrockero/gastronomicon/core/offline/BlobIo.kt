package io.github.garfieldrockero.gastronomicon.core.offline

/**
 * Envoltorio del [EncryptedBlobStore] para [RecipeCache], [DraftStore] y
 * [OfflineWipe].
 *
 * El contrato dice que el almacén no lanza, pero si una implementación lo hace,
 * la interfaz no se entera: se traduce a un problema tipado. Y el borrado por
 * prefijo pagina: `listKeys` puede devolver como mucho 4096 claves, así que se
 * repite el listado hasta vaciar, con un tope de vueltas para no quedarse
 * girando contra un almacén que miente.
 */
internal object BlobIo {

    /**
     * Vueltas máximas de un borrado por prefijo. Con el tope de 4096 claves por
     * listado cubre un millón de entradas; más que eso no es un almacén, es una
     * anomalía y se devuelve un fallo en vez de seguir.
     */
    private const val MAX_CLEAR_BATCHES = 256

    fun get(store: EncryptedBlobStore, key: String): StoreResult<ByteArray?> =
        try {
            store.get(key)
        } catch (ignored: RuntimeException) {
            StoreResult.Failed(StorageProblem.Unavailable)
        }

    fun put(store: EncryptedBlobStore, key: String, value: ByteArray): StoreResult<Unit> =
        try {
            store.put(key, value)
        } catch (ignored: RuntimeException) {
            StoreResult.Failed(StorageProblem.Unavailable)
        }

    fun delete(store: EncryptedBlobStore, key: String): StoreResult<Unit> =
        try {
            store.delete(key)
        } catch (ignored: RuntimeException) {
            StoreResult.Failed(StorageProblem.Unavailable)
        }

    fun list(store: EncryptedBlobStore, prefix: String): StoreResult<List<String>> =
        try {
            store.listKeys(prefix)
        } catch (ignored: RuntimeException) {
            StoreResult.Failed(StorageProblem.Unavailable)
        }

    /**
     * Borra todo lo que empieza por [prefix], repitiendo el listado hasta que
     * no quede nada. Devuelve el primer problema o `null` si el prefijo quedó
     * vacío.
     *
     * No descifra nada: solo lista y borra, que es lo que hace falta al cerrar
     * la sesión.
     */
    fun deleteUnder(store: EncryptedBlobStore, prefix: String): StorageProblem? {
        if (!BlobKeys.isValidPrefix(prefix)) return StorageProblem.Rejected
        var problem: StorageProblem? = null
        var batches = 0
        while (batches < MAX_CLEAR_BATCHES) {
            val keys = when (val listed = list(store, prefix)) {
                is StoreResult.Ok -> listed.value
                is StoreResult.Failed -> return problem ?: listed.problem
            }
            if (keys.isEmpty()) return problem
            val targets = keys.filter { BlobKeys.isValid(it) && it.startsWith(prefix) }
            if (targets.isEmpty()) {
                // El almacén dice que hay claves pero ninguna es válida: no se
                // borra a ciegas y se informa de que no se ha podido vaciar.
                return problem ?: StorageProblem.Rejected
            }
            var deletedAny = false
            for (key in targets) {
                when (val deleted = delete(store, key)) {
                    is StoreResult.Ok -> deletedAny = true
                    is StoreResult.Failed -> if (problem == null) problem = deleted.problem
                }
            }
            // Sin progreso no se puede vaciar: mejor un fallo que un bucle infinito.
            if (!deletedAny) return problem ?: StorageProblem.Unavailable
            batches++
        }
        // Se agotaron las vueltas sin confirmar que el prefijo quedara vacío.
        return problem ?: StorageProblem.Unavailable
    }
}
