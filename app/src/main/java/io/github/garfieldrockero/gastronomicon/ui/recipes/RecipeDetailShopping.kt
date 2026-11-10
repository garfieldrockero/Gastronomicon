package io.github.garfieldrockero.gastronomicon.ui.recipes

/*
 * Lo que la pantalla de una receta puede pedir para añadir a la compra.
 *
 * Todo lo demás de «añadir a la compra» (el estado, los desenlaces, el diálogo y
 * el aviso) vive en `ui/components/AddToShopping.kt` desde la fase 16b: lo usan
 * el detalle de una receta y el planificador, así que no es de ninguna de las dos.
 */

/**
 * Acciones del «añadir a la compra» tal como las cablea la pantalla del detalle
 * (menú y diálogo). Son funciones vacías por defecto para que una preview o una
 * pantalla sin compra las pueda omitir.
 */
class RecipeShoppingActions(
    /** Abre el diálogo (menú de la receta). */
    val onOpenClick: () -> Unit = {},
    val onSelectList: (String) -> Unit = {},
    val onScaleChange: (String) -> Unit = {},
    val onRetryLists: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onNoticeDismiss: () -> Unit = {},
    /**
     * Lleva a la pestaña Compra («Ver la compra» y «Volver a mirar»). Null
     * mientras la navegación no lo cablee: entonces el aviso no ofrece el botón,
     * para no dejar un botón que no hace nada.
     */
    val onOpenShopping: (() -> Unit)? = null,
)
