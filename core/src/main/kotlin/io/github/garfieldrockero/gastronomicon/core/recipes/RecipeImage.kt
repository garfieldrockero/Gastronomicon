package io.github.garfieldrockero.gastronomicon.core.recipes

import io.github.garfieldrockero.gastronomicon.core.network.HttpMethod
import io.github.garfieldrockero.gastronomicon.core.network.HttpRequest
import java.util.Locale

/**
 * Tamaños de imagen de una receta.
 *
 * Los nombres de fichero son los que usa Mealie, pero **no vienen en
 * `docs/openapi.json`** (informe de A1: «a confirmar contra el servidor»).
 * Si el servidor de la persona usa otros, se cambian aquí y en ningún otro
 * sitio.
 */
enum class ImageSize(val fileName: String) {
    Original("original.webp"),
    Min("min-original.webp"),
    Tiny("tiny-original.webp"),
}

/**
 * Ruta de la imagen de una receta, construida siempre por el núcleo.
 *
 * El campo «image» del servidor solo se usa para saber si hay imagen: su
 * valor jamás se convierte en URL, así que un servidor (o una receta
 * importada) no puede hacer que la app pida una imagen a otro origen.
 */
object RecipeImage {

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** El id en minúsculas si tiene forma de UUID, o null. Es el `id`, no el slug. */
    fun normalizeId(raw: String?): String? =
        if (raw != null && UUID.matches(raw)) raw.lowercase(Locale.ROOT) else null

    /** `/api/media/recipes/{id}/images/{fichero}`, o null si el id no es un UUID. */
    fun path(recipeId: String?, size: ImageSize = ImageSize.Min): String? {
        val id = normalizeId(recipeId) ?: return null
        return "/api/media/recipes/$id/images/${size.fileName}"
    }

    /**
     * Lo único que la app acepta como imagen; va en `Accept` para que el
     * servidor no mande otra cosa (el transporte pone `application/json` si
     * no hay ninguna). La comprobación real se hace al recibirla.
     */
    const val ACCEPT = "image/webp,image/jpeg,image/png"

    /** La petición lista para el transporte autenticado, o null si no hay id válido. */
    fun request(recipeId: String?, size: ImageSize = ImageSize.Min): HttpRequest? =
        path(recipeId, size)?.let { HttpRequest(HttpMethod.GET, it, headers = mapOf("Accept" to ACCEPT)) }
}
