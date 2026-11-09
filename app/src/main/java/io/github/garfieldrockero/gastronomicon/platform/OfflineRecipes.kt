package io.github.garfieldrockero.gastronomicon.platform

import io.github.garfieldrockero.gastronomicon.core.offline.CachedDraft
import io.github.garfieldrockero.gastronomicon.core.offline.CachedRecipe
import io.github.garfieldrockero.gastronomicon.core.offline.CachedSummary
import io.github.garfieldrockero.gastronomicon.core.offline.DraftStore
import io.github.garfieldrockero.gastronomicon.core.offline.DraftTarget
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineList
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineRead
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineWrite
import io.github.garfieldrockero.gastronomicon.core.offline.RecipeCache
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.core.offline.listSummaries
import io.github.garfieldrockero.gastronomicon.core.recipes.Recipe
import io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraft

/**
 * Lo que las pantallas de recetas usan de la fase 8: la copia de las recetas ya
 * abiertas y los borradores del formulario, siempre en el ámbito de la cuenta
 * guardada.
 *
 * Es una interfaz para que los ViewModels se puedan probar y previsualizar sin
 * almacén: con `null` no se guarda ni se ofrece nada sin conexión.
 *
 * **Todo es bloqueante** (igual que `SessionStore`): se llama desde un hilo de
 * fondo y no lanza por fallos normales, devuelve [OfflineWrite], [OfflineRead] u
 * [OfflineList].
 */
interface OfflineRecipes {

    /**
     * Ámbito de la cuenta guardada, o null si no se conoce (nunca se ha probado
     * la conexión con esta sesión, o el ámbito guardado no es válido). Con null
     * no se ofrece nada sin conexión: falla cerrado.
     */
    fun scope(): ServerScope?

    fun saveRecipe(scope: ServerScope, recipe: Recipe): OfflineWrite

    fun loadRecipe(scope: ServerScope, slug: String): OfflineRead<CachedRecipe>

    /** Recetas guardadas con nombre, para la lista sin conexión. */
    fun listRecipes(scope: ServerScope): OfflineList<CachedSummary>

    fun saveDraft(scope: ServerScope, target: DraftTarget, draft: RecipeDraft): OfflineWrite

    fun loadDraft(scope: ServerScope, target: DraftTarget): OfflineRead<CachedDraft>

    fun deleteDraft(scope: ServerScope, target: DraftTarget): OfflineWrite
}

/** La implementación real: el almacén cifrado del [AppContainer]. Sin lógica propia. */
class StoreOfflineRecipes(
    private val recipes: RecipeCache,
    private val drafts: DraftStore,
    private val scopeProvider: () -> ServerScope?,
) : OfflineRecipes {

    override fun scope(): ServerScope? = scopeProvider()

    override fun saveRecipe(scope: ServerScope, recipe: Recipe): OfflineWrite = recipes.save(scope, recipe)

    override fun loadRecipe(scope: ServerScope, slug: String): OfflineRead<CachedRecipe> = recipes.load(scope, slug)

    override fun listRecipes(scope: ServerScope): OfflineList<CachedSummary> = recipes.listSummaries(scope)

    override fun saveDraft(scope: ServerScope, target: DraftTarget, draft: RecipeDraft): OfflineWrite =
        drafts.save(scope, target, draft)

    override fun loadDraft(scope: ServerScope, target: DraftTarget): OfflineRead<CachedDraft> =
        drafts.load(scope, target)

    override fun deleteDraft(scope: ServerScope, target: DraftTarget): OfflineWrite = drafts.delete(scope, target)

    override fun toString(): String = "StoreOfflineRecipes"
}
