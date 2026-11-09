package io.github.garfieldrockero.gastronomicon.core.offline

/**
 * Almacén falso en memoria para las pruebas.
 *
 * No cifra (no hace falta: aquí se prueba la lógica del núcleo), pero sí
 * respeta el contrato: claves validadas, copias defensivas y problemas
 * inyectables para comprobar que un fallo no se convierte en excepción.
 */
internal class FakeEncryptedBlobStore : EncryptedBlobStore {

    /** Contenido crudo, accesible para manipularlo como haría un disco hostil. */
    val blobs = LinkedHashMap<String, ByteArray>()

    var readProblem: StorageProblem? = null
    var writeProblem: StorageProblem? = null
    var deleteProblem: StorageProblem? = null
    var listProblem: StorageProblem? = null

    /** Claves que se comportan como si no descifraran. */
    val corruptKeys = HashSet<String>()

    var throwOnGet = false
    var throwOnPut = false
    var throwOnDelete = false
    var throwOnList = false

    val deleted = ArrayList<String>()
    var puts = 0
        private set

    /** Lecturas de contenido: [OfflineWipe] no debe hacer ninguna. */
    var gets = 0
        private set

    /**
     * Tope de claves por listado, como el de la implementación real. Las
     * pruebas de borrado comprueban que el núcleo pagina.
     */
    var listCap = 4_096

    override fun get(key: String): StoreResult<ByteArray?> {
        if (throwOnGet) throw IllegalStateException("fallo simulado de lectura")
        readProblem?.let { return StoreResult.Failed(it) }
        if (!BlobKeys.isValid(key)) return StoreResult.Failed(StorageProblem.Rejected)
        if (key in corruptKeys) return StoreResult.Failed(StorageProblem.Corrupt)
        val value = blobs[key] ?: return StoreResult.Ok<ByteArray?>(null)
        gets++
        return StoreResult.Ok(value.copyOf())
    }

    override fun put(key: String, value: ByteArray): StoreResult<Unit> {
        if (throwOnPut) throw IllegalStateException("fallo simulado de escritura")
        writeProblem?.let { return StoreResult.Failed(it) }
        if (!BlobKeys.isValid(key)) return StoreResult.Failed(StorageProblem.Rejected)
        blobs[key] = value.copyOf()
        puts++
        return StoreResult.Ok(Unit)
    }

    override fun delete(key: String): StoreResult<Unit> {
        if (throwOnDelete) throw IllegalStateException("fallo simulado de borrado")
        deleteProblem?.let { return StoreResult.Failed(it) }
        if (!BlobKeys.isValid(key)) return StoreResult.Failed(StorageProblem.Rejected)
        blobs.remove(key)
        deleted += key
        return StoreResult.Ok(Unit)
    }

    override fun listKeys(prefix: String): StoreResult<List<String>> {
        if (throwOnList) throw IllegalStateException("fallo simulado de listado")
        listProblem?.let { return StoreResult.Failed(it) }
        if (!BlobKeys.isValidPrefix(prefix)) return StoreResult.Failed(StorageProblem.Rejected)
        return StoreResult.Ok(blobs.keys.filter { it.startsWith(prefix) }.take(listCap))
    }
}
