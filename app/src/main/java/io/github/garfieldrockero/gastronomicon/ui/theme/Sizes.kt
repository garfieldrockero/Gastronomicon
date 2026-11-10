package io.github.garfieldrockero.gastronomicon.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Medidas fijas de la app que no son huecos. Viven aquí para que todas las
 * pantallas usen la misma y nadie la rebaje en una de ellas.
 *
 * Si algo mide lo mismo en dos pantallas (una fila que se pulsa, una foto, el
 * hueco del botón flotante), su número se decide aquí y no en cada pantalla.
 */
object Sizes {
    /**
     * Alto mínimo de cualquier cosa que se pulse: la app se usa de pie, con
     * prisa y a veces con los dedos mojados, y 48 dp es lo que pide la guía
     * de accesibilidad de Android.
     */
    val MinTouchTarget = 48.dp

    /** Foto de una receta en la lista: cuadrada, y más pequeña con el texto grande. */
    val RecipeRowPhoto = 88.dp
    val RecipeRowPhotoLargeText = 56.dp

    /** Recorte de la foto de una fila (no es una forma del tema: es el marco de la foto). */
    val PhotoCorner = 12.dp

    /** Botones del selector de raciones del detalle. */
    val StepperButton = 56.dp

    /** Fila de un ingrediente: la fila entera es la casilla. */
    val IngredientRow = 56.dp

    /** Fila de una cosa de la compra: más alta, para marcar con una mano en el súper. */
    val ShoppingRow = 64.dp

    /** Alto mínimo de las acciones de la compra (recargar, quitar las marcadas). */
    val ActionMinHeight = 56.dp

    /** Botón redondo de añadir de la compra. */
    val AddButton = 56.dp

    /** Icono de las acciones de la hoja del «+»: algo mayor que el de una barra. */
    val SheetActionIcon = 28.dp

    /** Rueda pequeña dentro de una fila o de un botón. */
    val SmallSpinner = 24.dp

    /**
     * Hueco al final de una lista para que el botón flotante no tape la última
     * fila. Es el mismo en la lista de recetas y en el planificador.
     */
    val FabClearance = 88.dp

    /**
     * Cuánto se agranda la casilla de verificación: la fila entera es el botón y
     * la casilla solo muestra el estado, así que se ve más grande en todas las
     * pantallas por igual.
     */
    const val CheckboxScale = 1.4f
}
