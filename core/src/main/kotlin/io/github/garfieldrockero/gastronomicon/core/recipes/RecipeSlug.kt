package io.github.garfieldrockero.gastronomicon.core.recipes

/**
 * Lista blanca del identificador de receta (slug) que va dentro de una ruta.
 *
 * Solo letras ASCII, números, «-» y «_»: es lo que genera Mealie al
 * transliterar el nombre, y deja fuera todo lo que cambiaría la ruta o la
 * consulta («/», «.», «..», «?», «#», «%», «;», espacios, controles, Unicode).
 * Se aplica tanto al slug que escribe quien llama como al que manda el
 * servidor en una lista, porque un slug ajeno acabaría pegado a una ruta que
 * lleva el token.
 */
object RecipeSlug {

    const val MAX_LENGTH = 255

    private val ALLOWED = Regex("[A-Za-z0-9_-]{1,$MAX_LENGTH}")

    // «matches» exige coincidencia completa, así que un salto de línea final
    // no se cuela (con «$» y «find» sí lo haría).
    fun isValid(candidate: String?): Boolean = candidate != null && ALLOWED.matches(candidate)

    /** Devuelve [candidate] o lanza con un mensaje fijo que no repite el valor. */
    fun checked(candidate: String?): String {
        if (candidate == null || !ALLOWED.matches(candidate)) {
            throw IllegalArgumentException("El identificador de la receta (slug) no es válido.")
        }
        return candidate
    }
}
