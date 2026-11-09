package io.github.garfieldrockero.gastronomicon.core.recipes

/*
 * Borrador de receta: lo que edita el formulario. Es texto plano y números;
 * nada de HTML ni de JSON. Todo pasa por RecipeDraftValidator antes de salir
 * de la app, y quien pinta errores solo usa DraftError (campo + tipo + fila).
 */

/**
 * Etiqueta o categoría elegida. [id] y [slug] son null solo para una etiqueta
 * nueva por nombre (Mealie la resuelve o la crea: `RecipeTagIn` admite
 * «id» y «slug» ausentes, según `docs/openapi.json`).
 */
data class DraftOrganizer(val id: String? = null, val name: String = "", val slug: String? = null)

/**
 * Línea de ingredientes.
 *
 * [unit] y [food] son SOLO LECTURA: es el nombre que ya tenía la línea en el
 * servidor, para mostrarlo. Cambiarlos o ponerlos en una línea nueva da
 * [DraftErrorKind.NotEditable], porque en Mealie son objetos con id y la forma
 * de crearlos no está verificada. Una línea nueva se escribe con [quantity] y
 * [note] («g de harina» en la nota).
 *
 * [sourceId] es el `referenceId` que Mealie dio a la línea (null en una nueva):
 * con él, el núcleo conserva de una línea editada todo lo que el formulario no
 * toca (unidad, alimento, receta referenciada…).
 */
data class DraftIngredient(
    val quantity: Double? = null,
    val unit: String = "",
    val food: String = "",
    val note: String = "",
    val title: String = "",
    val sourceId: String? = null,
)

/** Paso. [text] es texto plano con saltos de línea. [sourceId] es el `id` del paso en Mealie. */
data class DraftStep(
    val title: String = "",
    val text: String = "",
    val sourceId: String? = null,
)

/** Nota. [sourceId] es su `referenceId` en Mealie. */
data class DraftNote(
    val title: String = "",
    val text: String = "",
    val sourceId: String? = null,
)

/** Tiempos como texto libre («30 minutos»); Mealie los guarda como texto. */
data class RecipeDraft(
    val name: String = "",
    val description: String = "",
    val servings: Double? = null,
    val totalTime: String = "",
    val prepTime: String = "",
    val cookTime: String = "",
    val performTime: String = "",
    val ingredients: List<DraftIngredient> = emptyList(),
    val steps: List<DraftStep> = emptyList(),
    val notes: List<DraftNote> = emptyList(),
    val tags: List<DraftOrganizer> = emptyList(),
    val categories: List<DraftOrganizer> = emptyList(),
)

/** Campo del formulario al que se refiere un error. */
enum class DraftField {
    Name, Description, Servings, TotalTime, PrepTime, CookTime, PerformTime,
    Ingredients, IngredientQuantity, IngredientNote, IngredientTitle, IngredientUnit, IngredientFood,
    Steps, StepTitle, StepText,
    Notes, NoteTitle, NoteText,
    Tags, Categories,
}

enum class DraftErrorKind {
    /** Falta un dato obligatorio (o una línea nueva solo tiene cantidad). */
    Required,

    /** Pasa de la longitud máxima del campo. */
    TooLong,

    /** Demasiados elementos en la lista. */
    TooMany,

    /** NaN o infinito. */
    NotFinite,

    /** Número fuera de rango (negativo, cero donde no vale, demasiado grande). */
    OutOfRange,

    /** Valor con formato incorrecto (id o slug que no pasan la lista blanca). */
    Invalid,

    /** Elemento repetido (mismo id de línea, o la misma etiqueta dos veces). */
    Duplicate,

    /** Campo de solo lectura que se ha cambiado. */
    NotEditable,
}

/**
 * Un error de validación. [index] es la posición (desde 0) en la lista que
 * llegó al validador, o null si el error es de un campo suelto. Sin texto del
 * usuario: la interfaz elige el mensaje.
 */
data class DraftError(val field: DraftField, val kind: DraftErrorKind, val index: Int? = null)

sealed interface DraftValidation {
    /** [draft] es el borrador saneado: es el que hay que enviar, no el original. */
    data class Valid(val draft: RecipeDraft) : DraftValidation

    data class Invalid(val errors: List<DraftError>) : DraftValidation
}
