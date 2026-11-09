package io.github.garfieldrockero.gastronomicon.core.recipes

/** Qué lista de organizadores de Mealie se pide para el formulario. */
enum class OrganizerKind(val path: String) {
    Categories("/api/organizers/categories"),
    Tags("/api/organizers/tags"),
}

/**
 * Etiqueta o categoría del servidor, ya saneada, para elegirla en el formulario.
 *
 * [id] es un UUID en minúsculas y [slug] pasa la lista blanca de [RecipeSlug]:
 * lo que no cumple se descarta al leer la lista, así que lo que llega a la
 * interfaz es siempre seguro de reenviar.
 */
data class Organizer(val id: String, val name: String, val slug: String) {
    fun toDraft(): DraftOrganizer = DraftOrganizer(id = id, name = name, slug = slug)
}
