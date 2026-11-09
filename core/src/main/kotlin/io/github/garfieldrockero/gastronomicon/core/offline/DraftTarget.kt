package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug

/**
 * A qué receta pertenece un borrador: a una que se está editando o al
 * formulario de receta nueva.
 *
 * El identificador va en la clave del almacén, así que tiene que pasar la
 * lista blanca de [RecipeSlug]. El constructor de [Recipe] lo exige (es un
 * error de programación pasar un slug sin validar); [of] es la puerta tolerante
 * para datos que vienen de disco.
 */
sealed interface DraftTarget {

    val id: String

    /** Formulario de receta nueva (una sola entrada por servidor). */
    data object New : DraftTarget {
        override val id: String get() = DraftTarget.NEW_ID
    }

    /** Receta existente, identificada por su slug. */
    data class Recipe(val slug: String) : DraftTarget {

        init {
            require(RecipeSlug.isValid(slug)) {
                "El identificador de la receta del borrador no es válido."
            }
        }

        override val id: String get() = slug

        // El slug es un identificador de la receta: no hace falta en un log.
        override fun toString(): String = "DraftTarget.Recipe(***)"
    }

    companion object {

        const val NEW_ID = "new"

        /** Convierte un identificador leído del índice, o null si no es válido. */
        fun of(id: String): DraftTarget? = when {
            id == NEW_ID -> DraftTarget.New
            RecipeSlug.isValid(id) -> DraftTarget.Recipe(id)
            else -> null
        }
    }
}
