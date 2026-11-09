package io.github.garfieldrockero.gastronomicon.core.offline

/**
 * Almacén de blobs cifrados donde viven la caché de recetas y los borradores.
 *
 * `core/` no sabe nada de Android: la implementación real (Keystore + AES-GCM,
 * ficheros privados de la app) vive en `app/platform`. Aquí solo se define el
 * contrato.
 *
 * Reglas que la implementación TIENE que cumplir:
 *
 * - **Cifrar en reposo.** Cada valor se guarda cifrado con una clave del
 *   Keystore de Android (AES-GCM, IV nuevo por escritura). No se guarda nada en
 *   claro ni en `SharedPreferences`.
 * - **La clave del blob es el AAD (MUST).** Al cifrar y al descifrar se pasa
 *   [key] en UTF-8 como *associated data* de AES-GCM. Sin eso, un fichero
 *   copiado o renombrado a otra clave descifraría y se serviría como si fuera
 *   la entrada de esa clave. El núcleo comprueba además la identidad que lleva
 *   dentro cada blob (ámbito, slug o destino), pero el AAD es la barrera de la
 *   implementación y no es opcional: sin él, la garantía no existe.
 * - **No registrar nada.** Ni claves, ni bytes, ni mensajes con contenido: los
 *   fallos se comunican solo con [StorageProblem].
 * - **Errores tipados.** Nunca lanzar por un fallo normal de E/S o del
 *   Keystore: se devuelve [StoreResult.Failed]. Un fallo transitorio es
 *   [StorageProblem.Unavailable] y NO debe borrar nada; unos bytes que no
 *   descifran son [StorageProblem.Corrupt] y sí se pueden borrar.
 * - **Claves planas.** [BlobKeys] define qué claves son válidas; la
 *   implementación debe rechazar cualquier otra (devolver
 *   [StoreResult.Failed] con [StorageProblem.Rejected]). Las claves nunca
 *   llevan la URL del servidor ni el token.
 * - **Sin rutas compartidas.** La clave se usa como nombre de fichero dentro de
 *   un directorio privado; el resto de la app no puede leer ese directorio.
 * - **Seguro entre hilos.** [RecipeCache] y [DraftStore] se sincronizan, pero
 *   pueden compartir el mismo almacén, así que cada operación debe ser atómica.
 *
 * Todos los métodos son bloqueantes, como `SessionStore`: quien llama decide el
 * hilo. No hay reintentos.
 */
interface EncryptedBlobStore {

    /**
     * Bytes guardados en [key], o `null` si no existe.
     *
     * [StoreResult.Ok] con valor `null` significa «no hay nada»; un fallo del
     * almacén es [StoreResult.Failed] y no se puede confundir con una ausencia.
     */
    fun get(key: String): StoreResult<ByteArray?>

    /** Guarda [value] en [key], sustituyendo lo que hubiera. */
    fun put(key: String, value: ByteArray): StoreResult<Unit>

    /** Borra [key]. No pasa nada si no existía. */
    fun delete(key: String): StoreResult<Unit>

    /**
     * Claves que empiezan por [prefix], en cualquier orden.
     *
     * La implementación puede acotar la lista (p. ej. 4096 claves) para no
     * agotar memoria con un almacén manipulado. El núcleo lo tiene en cuenta:
     * los borrados repiten el listado hasta vaciar el prefijo, y las purgas
     * trabajan con un tope por operación.
     */
    fun listKeys(prefix: String): StoreResult<List<String>>
}

/** Por qué falló una operación de almacenamiento. Nunca lleva datos dentro. */
enum class StorageProblem {

    /** El almacén no está disponible ahora (Keystore ocupado, E/S). No borrar nada. */
    Unavailable,

    /** Los bytes no descifran o están corruptos. La entrada se puede borrar. */
    Corrupt,

    /** La clave no es válida o el almacén la rechaza. */
    Rejected,

    /** El valor pasa del tope del almacén. */
    TooLarge,
}

/** Resultado de una operación del almacén: un valor o un problema tipado. */
sealed interface StoreResult<out T> {

    data class Ok<out T>(val value: T) : StoreResult<T>

    data class Failed(val problem: StorageProblem) : StoreResult<Nothing>
}
