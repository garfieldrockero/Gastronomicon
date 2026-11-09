package io.github.garfieldrockero.gastronomicon.core.shopping

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer

/** Por qué un texto de la compra se ha rechazado antes de salir de la app. */
enum class ShoppingInputProblem {
    /** No queda nada visible después de sanearlo. */
    EmptyText,

    /** Pasa del tope de longitud: no se recorta en silencio. */
    TextTooLong,

    /** La cantidad no es un número positivo razonable. */
    InvalidQuantity,
}

/** Texto ya saneado y el problema que lo rechaza, si lo hay. */
data class ShoppingInput(val text: String, val problem: ShoppingInputProblem?) {
    val isValid: Boolean get() = problem == null
}

/**
 * Limpieza y límites de los textos de la lista de la compra.
 *
 * Todo pasa por [PlainTextSanitizer] (fuera controles, bidireccionales y marcas
 * invisibles). Los topes son propios y no se han copiado de la API: Mealie no
 * documenta una longitud máxima para la nota de un elemento, así que se elige
 * una cifra de pantalla y se aplica igual al leer y al escribir.
 */
object ShoppingText {

    /** Tope de la nota de un elemento. */
    const val MAX_ITEM_TEXT = 200

    /** Tope del nombre de una lista: se aplica igual al leer y al crear. */
    const val MAX_LIST_NAME = 200

    /** Lo que se pinta cuando el servidor no manda nombre de lista. */
    const val DEFAULT_LIST_NAME = "Lista de la compra"

    /** Título del grupo de elementos sin etiqueta. */
    const val DEFAULT_NO_SECTION_TITLE = "Sin sección"

    /**
     * Texto de un elemento para enviar: saneado, con [ShoppingInputProblem]
     * cuando está vacío o pasa del tope. [ShoppingInput.text] lleva lo que se
     * habría guardado (recortado al tope) solo para poder enseñarlo en un aviso;
     * nunca se envía cuando [ShoppingInput.problem] no es null.
     */
    fun item(raw: String?): ShoppingInput = checked(raw, MAX_ITEM_TEXT)

    /** Nota ya saneada y recortada, sin decidir si vale: la edición permite dejarla vacía. */
    fun sanitizeItem(raw: String?): String = PlainTextSanitizer.sanitize(raw, MAX_ITEM_TEXT)

    /** Nombre de lista saneado y recortado; puede quedar vacío. */
    fun listName(raw: String?): String = PlainTextSanitizer.sanitize(raw, MAX_LIST_NAME)

    /**
     * Nombre de lista para crear una: saneado y con el motivo del rechazo si
     * queda vacío o pasa del tope. [ShoppingInput.text] lleva el nombre ya
     * recortado solo para poder enseñarlo en un aviso; no se envía cuando
     * [ShoppingInput.problem] no es null.
     *
     * Va aparte de [listName] porque leer un nombre puede quedar vacío (Mealie
     * lo permite) y recortarlo en silencio, pero crear una lista sin nombre no
     * vale y pasar del tope no debe perder lo que la persona escribió.
     */
    fun listNameInput(raw: String?): ShoppingInput = checked(raw, MAX_LIST_NAME)

    private fun checked(raw: String?, max: Int): ShoppingInput {
        // Se sanea a max+1 para distinguir «justo cabe» de «se pasa», igual que
        // el validador de recetas.
        val text = PlainTextSanitizer.sanitize(raw, max + 1)
        if (text.codePointCount(0, text.length) > max) {
            return ShoppingInput(PlainTextSanitizer.sanitize(raw, max), ShoppingInputProblem.TextTooLong)
        }
        if (text.isEmpty()) return ShoppingInput("", ShoppingInputProblem.EmptyText)
        return ShoppingInput(text, null)
    }
}
