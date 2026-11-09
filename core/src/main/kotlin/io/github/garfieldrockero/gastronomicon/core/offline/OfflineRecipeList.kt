package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSummary

/**
 * Una receta guardada, con lo justo para pintar una fila de la lista sin
 * conexión: el resumen (nombre, descripción, etiquetas…) y la fecha de la copia.
 *
 * El resumen sale de la receta ya saneada que hay en el almacén, así que la
 * interfaz lo pinta tal cual. No lleva el detalle: abrir la receta lee su
 * entrada con [RecipeCache.load].
 */
class CachedSummary internal constructor(
    val summary: RecipeSummary,
    val cachedAtMillis: Long,
) {
    // Sin el nombre ni el slug: es contenido de la persona y puede acabar en un log.
    override fun toString(): String = "CachedSummary(cachedAtMillis=$cachedAtMillis)"
}

/**
 * Las recetas guardadas de un ámbito, con nombre y datos para la lista, de la
 * copia más reciente a la más antigua (el orden lo pone [RecipeCache.list]).
 *
 * La caché guarda el detalle completo de cada receta abierta; aquí se lee cada
 * una para poder enseñar su nombre, que es lo único que la persona reconoce. Lo
 * que no se pueda leer (caducado, manipulado, almacén sin respuesta) se salta:
 * es mejor una lista más corta que una lista con huecos o con errores.
 *
 * [max] acota cuántas se devuelven, por si algún día la caché admite más de las
 * que caben en la pantalla.
 */
fun RecipeCache.listSummaries(
    scope: ServerScope,
    max: Int = RecipeCache.DEFAULT_MAX_ENTRIES,
): OfflineList<CachedSummary> {
    if (max <= 0) return OfflineList.Ok(emptyList())
    val infos = when (val listed = list(scope)) {
        is OfflineList.Failed -> return OfflineList.Failed(listed.problem)
        is OfflineList.Ok -> listed.items
    }
    val result = ArrayList<CachedSummary>(minOf(infos.size, max))
    for (info in infos) {
        if (result.size >= max) break
        val hit = load(scope, info.slug)
        if (hit is OfflineRead.Hit) {
            result += CachedSummary(hit.value.recipe.toSummary(), info.cachedAtMillis)
        }
    }
    return OfflineList.Ok(result)
}

/**
 * El resumen de la lista a partir de una receta ya saneada.
 *
 * No sanea nada: todo lo que hay en [Recipe] pasó por `RecipeMapper` al llegar
 * y por `RecipeCacheCodec` al leerlo del disco. Es solo el paso de modelo
 * completo a fila, para no tener que pedirle al servidor lo que ya está aquí.
 */
fun Recipe.toSummary(): RecipeSummary = RecipeSummary(
    id = id,
    slug = slug,
    name = name,
    description = description,
    hasImage = hasImage,
    servings = servings,
    rating = rating,
    times = times,
    categories = categories,
    tags = tags,
)
