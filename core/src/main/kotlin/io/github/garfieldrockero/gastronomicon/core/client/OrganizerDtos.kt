package io.github.garfieldrockero.gastronomicon.core.client

import io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizer
import io.github.garfieldrockero.gastronomicon.core.recipes.Organizer
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidator
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlug
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Lectura de `GET /api/organizers/categories` y `/api/organizers/tags`
 * (`RecipeCategoryPagination` y `RecipeTagPagination` de docs/openapi.json).
 * Como el resto de DTO de lectura: todo anulable, solo lo que se usa, y nada
 * llega a la interfaz sin pasar por [OrganizerMapper].
 */

@Serializable
internal class OrganizerDto(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
)

/** `next` y `previous` no se declaran: no se siguen. */
@Serializable
internal class OrganizerPageDto(
    val page: Int? = null,
    @SerialName("per_page") val perPage: Int? = null,
    val total: Long? = null,
    val items: List<OrganizerDto?>? = null,
)

internal object OrganizerMapper {

    /** Tope de elementos por página que se aceptan, aunque el servidor mande más. */
    const val MAX_ITEMS = 100

    /**
     * Null si falta el id (UUID), el slug (lista blanca) o el nombre: sin ellos
     * no se puede reenviar al servidor sin inventarlo.
     */
    fun organizer(dto: OrganizerDto): Organizer? {
        val id = RecipeDraftValidator.validUuid(dto.id) ?: return null
        val slug = dto.slug?.takeIf { RecipeSlug.isValid(it) } ?: return null
        val name = PlainTextSanitizer.sanitize(dto.name, RecipeDraftValidator.MAX_ORGANIZER_NAME)
        if (name.isEmpty()) return null
        return Organizer(id = id, name = name, slug = slug)
    }
}
