package io.github.garfieldrockero.gastronomicon.widget

import android.app.KeyguardManager
import android.content.Context
import io.github.garfieldrockero.gastronomicon.core.client.ApiResult
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineRead
import io.github.garfieldrockero.gastronomicon.core.offline.OfflineWrite
import io.github.garfieldrockero.gastronomicon.core.offline.ServerScope
import io.github.garfieldrockero.gastronomicon.core.offline.ShoppingListCache
import io.github.garfieldrockero.gastronomicon.core.recipes.Page
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.core.shopping.RecipeAddition
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingClient
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingItem
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingList
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSource
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListSummary
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetMarker
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetSnapshot
import io.github.garfieldrockero.gastronomicon.core.widget.ShoppingWidgetWriteGuard
import io.github.garfieldrockero.gastronomicon.platform.AndroidActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.platform.KeystoreBlobStore
import io.github.garfieldrockero.gastronomicon.platform.KeystoreSessionStore
import io.github.garfieldrockero.gastronomicon.platform.OfflineScopeStore
import io.github.garfieldrockero.gastronomicon.platform.SharedPreferencesAppPreferences
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingAccess
import io.github.garfieldrockero.gastronomicon.ui.shopping.StoreShoppingAccess

/**
 * Las piezas reales que necesita el receptor del widget, montadas solo para él.
 *
 * No se construye un `AppContainer`: su `init` reprograma el recordatorio y
 * reintenta borrados pendientes, cosas que un widget no tiene por qué provocar.
 * Aquí solo se abre el almacén cifrado, el ámbito de la cuenta y un cliente de
 * la compra **por operación** (nunca guardado), con la misma política que la
 * app:
 *
 * - El ámbito ([ServerScope]) sale de las preferencias y muere con la sesión.
 * - El interruptor de HTTP local y la red activa se leen en **cada** operación;
 *   con datos móviles y servidor `http`, el transporte corta antes de enviar.
 * - El token solo se descifra en el hilo de fondo del receptor y no se queda en
 *   ningún campo: [sessionStore] lo da a [ShoppingClient] y se descarta.
 */
internal class ShoppingWidgetEntryPoint(context: Context) {

    private val appContext: Context = context.applicationContext

    private val preferences: AppPreferences = SharedPreferencesAppPreferences(appContext)

    private val sessionStore: SessionStore = KeystoreSessionStore(appContext)

    private val offlineStore: KeystoreBlobStore = KeystoreBlobStore(appContext)

    private val cache: ShoppingListCache = ShoppingListCache(offlineStore)

    /**
     * El ámbito de los datos sin conexión: solo se consulta [OfflineScopeStore.current],
     * que lee preferencias y falla cerrado. La sesión que se le pasa no se usa en
     * esa lectura y no se descifra al pintar.
     */
    private val scopeStore: OfflineScopeStore = OfflineScopeStore(preferences) { sessionStore.load() }

    private val activeNetwork: AndroidActiveNetworkProvider = AndroidActiveNetworkProvider(appContext)

    private val access: ShoppingAccess = StoreShoppingAccess(sessionStore) { session ->
        ShoppingClient.create(session, preferences.allowLocalHttp, activeNetwork)
    }

    /** Cuenta guardada, o `null` si no hay nada que enseñar (falla cerrado). */
    fun scope(): ServerScope? = scopeStore.current()

    fun load(scope: ServerScope): OfflineRead<ShoppingWidgetSnapshot> = cache.load(scope)

    fun save(scope: ServerScope, snapshot: ShoppingWidgetSnapshot): OfflineWrite =
        cache.save(scope, snapshot)

    fun clear(scope: ServerScope): OfflineWrite = cache.clear(scope)

    /** Cliente nuevo para UNA lectura puntual (el botón de actualizar). */
    fun newClient(): ShoppingClient = access.newClient()

    /**
     * Fuente del marcado: crea un cliente por llamada, como hace la app. El
     * marcador del núcleo la usa para el `GET` y el `PUT`.
     */
    fun source(): ShoppingListSource = WidgetSource(access)

    /**
     * ¿Se puede escribir ahora? `KeyguardManager` dice si la pantalla está
     * bloqueada; si no se puede consultar, se falla cerrado ([Locked]).
     */
    fun guard(): ShoppingWidgetWriteGuard {
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return ShoppingWidgetWriteGuard { keyguard != null && !keyguard.isKeyguardLocked }
    }

    /** Fuente de la compra que reutiliza la política de sesión de `StoreShoppingAccess`. */
    private class WidgetSource(private val access: ShoppingAccess) : ShoppingListSource {

        override fun listLists(page: Int, perPage: Int): ApiResult<Page<ShoppingListSummary>> =
            access.newClient().listLists(page, perPage)

        override fun createList(name: String): ApiResult<ShoppingListSummary> =
            access.newClient().createList(name)

        override fun getList(listId: String): ApiResult<ShoppingList> =
            access.newClient().getList(listId)

        override fun addItem(listId: String, text: String): ApiResult<Unit> =
            access.newClient().addItem(listId, text)

        override fun addRecipe(listId: String, recipeId: String, scale: Double): ApiResult<Unit> =
            access.newClient().addRecipe(listId, recipeId, scale)

        override fun addRecipes(listId: String, additions: List<RecipeAddition>): ApiResult<Unit> =
            access.newClient().addRecipes(listId, additions)

        override fun setChecked(item: ShoppingItem, checked: Boolean): ApiResult<Unit> =
            access.newClient().setChecked(item, checked)

        override fun editItem(item: ShoppingItem, note: String, quantity: Double): ApiResult<Unit> =
            access.newClient().editItem(item, note, quantity)

        override fun deleteItem(itemId: String): ApiResult<Unit> =
            access.newClient().deleteItem(itemId)

        override fun deleteChecked(items: List<ShoppingItem>): ApiResult<Unit> =
            access.newClient().deleteChecked(items)
    }
}

/**
 * Una sola instancia de [ShoppingWidgetMarker] por proceso: su conjunto de ids
 * «en vuelo» es lo que corta un doble toque antes de llegar a la red. Si el
 * proceso muere, el `GET` fresco de la siguiente pulsación ya evita duplicados.
 */
internal object ShoppingWidgetRuntime {

    @Volatile
    private var marker: ShoppingWidgetMarker? = null

    fun marker(context: Context): ShoppingWidgetMarker {
        marker?.let { return it }
        synchronized(this) {
            marker?.let { return it }
            val entry = ShoppingWidgetEntryPoint(context)
            val created = ShoppingWidgetMarker(source = entry.source(), guard = entry.guard())
            marker = created
            return created
        }
    }
}
