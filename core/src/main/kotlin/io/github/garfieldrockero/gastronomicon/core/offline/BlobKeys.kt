package io.github.garfieldrockero.gastronomicon.core.offline

/**
 * Lista blanca de claves del [EncryptedBlobStore].
 *
 * Las claves las construye el núcleo a partir del hash del servidor y del hash
 * del identificador, así que no llevan ni la URL ni el nombre de la receta. La
 * implementación de `app/platform` usa la clave como nombre de fichero: por eso
 * solo se admiten letras ASCII, dígitos, punto, guion y guion bajo, sin barras,
 * sin espacios y sin `..` (que podría escapar del directorio).
 */
object BlobKeys {

    /** Longitud máxima de una clave o de un prefijo. */
    const val MAX_LENGTH = 200

    private val SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    fun isValid(key: String?): Boolean =
        key != null && isWellFormed(key) && key.length <= MAX_LENGTH

    /**
     * Un prefijo puede ser más corto que una clave, pero se valida igual: se
     * pasa tal cual al almacén y no puede contener barras ni `..`.
     */
    fun isValidPrefix(prefix: String?): Boolean =
        prefix != null && prefix.isNotEmpty() && isWellFormed(prefix) && prefix.length <= MAX_LENGTH

    private fun isWellFormed(text: String): Boolean =
        SHAPE.matches(text) && !text.contains("..")
}

/**
 * Prefijos de las claves que escribe el núcleo offline.
 *
 * Viven en un solo sitio para que un borrado total ([OfflineWipe]) no se deje
 * ninguna familia: si se añade un prefijo nuevo, se añade aquí y aparece solo en
 * el borrado.
 */
internal object BlobPrefix {

    /** Una receta concreta de un ámbito. */
    const val RECIPE_ENTRY = "rc1."

    /** Índice de recetas de un ámbito. */
    const val RECIPE_INDEX = "rci1."

    /** Un borrador concreto de un ámbito. */
    const val DRAFT_ENTRY = "dr1."

    /** Índice de borradores de un ámbito. */
    const val DRAFT_INDEX = "dri1."

    /** Todas las familias conocidas; lo recorre [OfflineWipe.clearAll]. */
    val ALL: List<String> = listOf(RECIPE_ENTRY, RECIPE_INDEX, DRAFT_ENTRY, DRAFT_INDEX)
}

