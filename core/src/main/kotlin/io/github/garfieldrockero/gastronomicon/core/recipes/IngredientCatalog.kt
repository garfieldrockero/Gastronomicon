package io.github.garfieldrockero.gastronomicon.core.recipes

/*
 * Catálogo de unidades y alimentos de Mealie, ya saneado y acotado.
 *
 * Según `docs/openapi.json` (`IngredientUnit-Output` e `IngredientFood-Output`),
 * los dos son objetos con `id` (UUID) y `name`: un ingrediente de una receta no
 * guarda «g» como texto, guarda la referencia a esta unidad. Por eso el
 * formulario tiene que elegir de una lista y no escribir libremente, y por eso
 * lo que llega a la interfaz pasa antes por aquí: id con forma de UUID, nombre
 * sin controles ni marcas invisibles y recortado al tope.
 *
 * [IngredientUnit.abbreviation] y [IngredientUnit.pluralAbbreviation] se
 * conservan porque Mealie las usa para componer el texto del ingrediente
 * («2 cdas»), pero la app no las escribe: solo se enseñan.
 */

/** Unidad del servidor (`GET /api/units`), saneada y lista para elegir. */
data class IngredientUnit(
    val id: String,
    val name: String,
    val pluralName: String? = null,
    val abbreviation: String = "",
    val pluralAbbreviation: String? = null,
    val useAbbreviation: Boolean = false,
)

/** Alimento del servidor (`GET /api/foods`), saneado y listo para elegir. */
data class IngredientFood(
    val id: String,
    val name: String,
    val pluralName: String? = null,
)
