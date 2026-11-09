package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot

/**
 * Caché cifrada de la última lista de la compra abierta, para pintar el
 * widget sin red (decisión D1 del diseño).
 *
 * Misma disciplina que [RecipeCache]:
 *
 * - **Un solo blob por ámbito** (servidor y cuenta, [ServerScope]); la clave es
 *   [BlobPrefix.WIDGET] más el id del ámbito. Ni la URL, ni el usuario, ni el
 *   token entran en la clave ni en el valor.
 * - **Dato no confiable al leer:** versión, ámbito, UUID, contadores y textos se
 *   revalidan en [ShoppingListCacheCodec]. Si algo no cuadra, se borra la
 *   entrada y se responde `Miss` (mejor sin widget que con datos mezclados).
 * - **Falla cerrado:** un fallo del almacén es [OfflineRead.Failed] y no borra
 *   nada; unos bytes que no descifran ([StorageProblem.Corrupt]) se descartan.
 * - **Topes:** [DEFAULT_MAX_BYTES] por blob y [DEFAULT_TTL_MILLIS] de
 *   caducidad. Lo que se pasa de tamaño no se sirve; lo caducado se borra.
 * - **Sin reintentos ni logs.**
 *
 * `clear(scope)` se llama al cerrar una cuenta; `clear()` y
 * [OfflineWipe.clearAll] al cerrar la sesión o cambiar el token. La caché del
 * widget muere con la sesión.
 *
 * Escribe la app (tras una operación buena de la compra y tras actualizar por
 * red), no el widget en segundo plano. Los métodos son bloqueantes, como
 * [RecipeCache]: quien llama decide el hilo.
 */
class ShoppingListCache(
    private val store: EncryptedBlobStore,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    init {
        require(maxBytes > 0) { "El tamaño máximo de la caché del widget debe ser positivo." }
        require(ttlMillis > 0) { "La caducidad de la caché del widget debe ser positiva." }
    }

    /**
     * Sustituye la instantánea del ámbito [scope].
     *
     * Si no cabe en [maxBytes] se borra la copia anterior y se responde
     * [OfflineWrite.TooLarge]: servir una lista vieja como si fuera la actual
     * sería peor que no servir ninguna. Una instantánea inválida
     * ([OfflineWrite.Rejected]) no toca el almacén.
     */
    fun save(scope: ServerScope, snapshot: ShoppingWidgetSnapshot): OfflineWrite = synchronized(lock) {
        val key = key(scope)
        val bytes = ShoppingListCacheCodec.encode(snapshot, scope) ?: return OfflineWrite.Rejected
        if (bytes.size > maxBytes) {
            BlobIo.delete(store, key)
            return OfflineWrite.TooLarge
        }
        return when (val put = BlobIo.put(store, key, bytes)) {
            is StoreResult.Ok -> OfflineWrite.Saved
            is StoreResult.Failed -> OfflineWrite.Failed(put.problem)
        }
    }

    /**
     * La última instantánea del ámbito, o `Miss` si no hay, ha caducado o no
     * ha pasado la validación. [OfflineRead.Failed] es que el almacén no
     * responde ahora: no significa que no haya copia y no se borra nada.
     */
    fun load(scope: ServerScope): OfflineRead<ShoppingWidgetSnapshot> = synchronized(lock) {
        val key = key(scope)
        val bytes = when (val read = BlobIo.get(store, key)) {
            is StoreResult.Failed -> {
                // Un blob que no descifra no se puede recuperar: se descarta.
                if (read.problem == StorageProblem.Corrupt) {
                    BlobIo.delete(store, key)
                    return OfflineRead.Miss
                }
                return OfflineRead.Failed(read.problem)
            }
            is StoreResult.Ok -> read.value ?: return OfflineRead.Miss
        }
        if (bytes.size > maxBytes) {
            BlobIo.delete(store, key)
            return OfflineRead.Miss
        }
        val snapshot = ShoppingListCacheCodec.decode(bytes, scope, maxBytes)
        if (snapshot == null || expired(snapshot.updatedAtMillis, now())) {
            BlobIo.delete(store, key)
            return OfflineRead.Miss
        }
        OfflineRead.Hit(snapshot)
    }

    /** Borra la copia de [scope]. No toca las recetas, los borradores ni otras cuentas. */
    fun clear(scope: ServerScope): OfflineWrite = synchronized(lock) {
        resultOf(BlobIo.deleteUnder(store, BlobPrefix.WIDGET + scope.id))
    }

    /** Borra la caché del widget de todos los servidores y cuentas. */
    fun clear(): OfflineWrite = synchronized(lock) {
        resultOf(BlobIo.deleteUnder(store, BlobPrefix.WIDGET))
    }

    // ------------------------------------------------------------- interno

    private fun key(scope: ServerScope): String = BlobPrefix.WIDGET + scope.id

    private fun expired(updatedAt: Long, currentTime: Long): Boolean =
        updatedAt <= 0 || updatedAt > currentTime + FUTURE_SKEW_MILLIS ||
            currentTime - updatedAt > ttlMillis

    private fun resultOf(problem: StorageProblem?): OfflineWrite =
        if (problem == null) OfflineWrite.Saved else OfflineWrite.Failed(problem)

    companion object {

        /**
         * Tope del blob. Con ocho filas de 240 caracteres no se llega ni de
         * lejos; el tope está para un blob manipulado, no para uso normal.
         */
        const val DEFAULT_MAX_BYTES = 64 * 1024

        /**
         * Una semana. Si la app no ha abierto la compra en una semana, la lista
         * del widget tiene demasiadas papeletas de ser vieja y se prefiere
         * pedir que se abra la app a que alguien compre de más.
         */
        const val DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Margen para relojes desajustados al comparar marcas de tiempo. */
        private const val FUTURE_SKEW_MILLIS = 5L * 60 * 1000
    }
}
