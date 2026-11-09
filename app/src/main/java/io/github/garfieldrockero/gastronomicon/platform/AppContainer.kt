package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanClient
import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingClient
import io.github.garfieldrockero.gastronomicon.ui.mealplan.MealPlanAccess
import io.github.garfieldrockero.gastronomicon.ui.mealplan.StoreMealPlanAccess
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.settings.ConnectionTester
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingAccess
import io.github.garfieldrockero.gastronomicon.ui.shopping.StoreShoppingAccess

/**
 * Las piezas reales de Android que usa la app, montadas en un solo sitio.
 * Sin estado propio: cada una guarda lo suyo (el Keystore, las preferencias).
 */
class AppContainer(context: Context) {

    // applicationContext para no retener una Activity.
    private val appContext: Context = context.applicationContext

    val sessionStore: SessionStore = KeystoreSessionStore(appContext)
    val preferences: AppPreferences = SharedPreferencesAppPreferences(appContext)
    val activeNetwork: ActiveNetworkProvider = AndroidActiveNetworkProvider(appContext)
    val connectionTester: ConnectionTester = MealieConnectionTester(activeNetwork)
    val appVersion: String = appContext.appVersionName()

    /** Un cliente nuevo por operación, sacado de la sesión guardada (ver [StoreClientAccess]). */
    val clientAccess: ClientAccess = StoreClientAccess(sessionStore) { session -> newClient(session) }

    /**
     * Lista de la compra: un cliente nuevo por operación, con la sesión guardada
     * (ver [StoreShoppingAccess]). Vuelve a leer el interruptor HTTP y la red en
     * cada llamada; no se guarda ningún cliente.
     */
    val shoppingAccess: ShoppingAccess = StoreShoppingAccess(sessionStore) { session ->
        ShoppingClient.create(session, preferences.allowLocalHttp, activeNetwork)
    }

    /**
     * Planificador: una fuente nueva por operación, con la sesión guardada
     * (ver [StoreMealPlanAccess]). Misma política de sesión, red y HTTP local.
     */
    val mealPlanAccess: MealPlanAccess = StoreMealPlanAccess(sessionStore) { session ->
        MealPlanClient.create(session, preferences.allowLocalHttp, activeNetwork)
    }

    /** Imágenes de receta: caché solo en memoria. Se vacía al cambiar de sesión. */
    val imageLoader: ImageLoader = ImageLoader(clientAccess)

    /**
     * Prepara la foto elegida (galería o cámara) para subirla: la reduce, la
     * endereza y le quita los metadatos. Solo guarda el `applicationContext`, así
     * que se puede tener aquí sin retener la pantalla.
     */
    val imageRecoder: ImageRecoder = ImageRecoder(appContext)

    /**
     * Cliente para una sesión ya cargada. Vuelve a leer el interruptor
     * «Conectar sin HTTPS» en cada llamada y pasa la red activa, que se
     * consulta en cada salto: la sesión guardada no sabe si el interruptor
     * sigue encendido ni por qué red se sale ahora. Lanza
     * [IllegalArgumentException] si la sesión es http y el interruptor está
     * apagado (falla cerrado). Se usa desde un hilo de fondo.
     *
     * El cliente se crea en CADA operación y no se guarda en un ViewModel ni en
     * ningún campo: así una sesión borrada o un interruptor apagado se notan al
     * momento y no queda un cliente vivo con el token y el HTTP ya revocados.
     */
    fun newClient(session: Session): MealieClient =
        MealieClient.create(session, preferences.allowLocalHttp, activeNetwork)
}
