package io.github.garfieldrockero.gastronomicon.core.offline

/**
 * Borrado total de los datos offline.
 *
 * Es lo que hay que llamar al **cerrar la sesión o cambiar el token**: los
 * datos de una cuenta no deben sobrevivir a ese cambio. Borra las recetas y los
 * borradores de **todos** los servidores y cuentas, sin descifrar nada: solo
 * lista claves y las borra, así que funciona aunque el Keystore esté roto o la
 * clave se haya perdido.
 *
 * No toma el cerrojo de [RecipeCache] ni el de [DraftStore]: se llama cuando no
 * hay operaciones en curso (el cierre de sesión). Si otra operación escribe a la
 * vez, puede dejar una entrada suelta; el siguiente `clearAll` la recoge.
 */
object OfflineWipe {

    /**
     * Borra todo lo que el núcleo offline ha escrito en [store]. Devuelve el
     * primer problema encontrado o [OfflineWrite.Saved] si no queda nada.
     */
    fun clearAll(store: EncryptedBlobStore): OfflineWrite {
        var problem: StorageProblem? = null
        for (prefix in BlobPrefix.ALL) {
            val result = BlobIo.deleteUnder(store, prefix)
            if (result != null && problem == null) problem = result
        }
        return if (problem == null) OfflineWrite.Saved else OfflineWrite.Failed(problem)
    }
}
