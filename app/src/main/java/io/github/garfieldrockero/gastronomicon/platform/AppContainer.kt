package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanClient
import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.offline.DraftStore
import io.github.garfieldrockero.gastronomicon.core.offline.MealPlanCache
import io.github.garfieldrockero.gastronomicon.core.offline.RecipeCache
import io.github.garfieldrockero.gastronomicon.core.offline.ShoppingListCache
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingClient
import io.github.garfieldrockero.gastronomicon.ui.mealplan.MealPlanAccess
import io.github.garfieldrockero.gastronomicon.ui.mealplan.StoreMealPlanAccess
import io.github.garfieldrockero.gastronomicon.ui.settings.AppPreferences
import io.github.garfieldrockero.gastronomicon.ui.settings.ConnectionTester
import io.github.garfieldrockero.gastronomicon.ui.settings.ReminderAccess
import io.github.garfieldrockero.gastronomicon.ui.shopping.ShoppingAccess
import io.github.garfieldrockero.gastronomicon.ui.shopping.StoreShoppingAccess
import io.github.garfieldrockero.gastronomicon.widget.ShoppingWidgetUpdater

/**
 * Las piezas reales de Android que usa la app, montadas en un solo sitio.
 * Sin estado propio: cada una guarda lo suyo (el Keystore, las preferencias).
 */
class AppContainer(context: Context) {

    // applicationContext para no retener una Activity.
    private val appContext: Context = context.applicationContext

    /**
     * Tema, interruptor de HTTP local y el ámbito de los datos sin conexión.
     * Ninguno es un secreto: el token tiene su propio almacén cifrado.
     */
    val preferences: AppPreferences = SharedPreferencesAppPreferences(appContext)

    // El almacén real, sin el envoltorio de abajo. Va antes que el ámbito porque este
    // necesita poder leer la sesión guardada (para no pedir un «Guardar» de más).
    private val keystoreSessionStore: SessionStore = KeystoreSessionStore(appContext)

    /**
     * Ámbito de las recetas guardadas y los borradores: el hash de servidor y
     * cuenta, nunca la URL ni el id de usuario. Se escribe al probar la conexión
     * y guardar la sesión, y se borra a la vez que lo offline (ver
     * [OfflineClearingSessionStore]).
     */
    val offlineScope: OfflineScopeStore = OfflineScopeStore(preferences) { keystoreSessionStore.load() }

    /**
     * Datos offline (fase 8): ficheros cifrados en el directorio privado de la app,
     * nunca en la caché. Uno solo para toda la app, compartido por la caché de
     * recetas y los borradores (el almacén serializa sus operaciones).
     */
    val offlineStore: KeystoreBlobStore = KeystoreBlobStore(appContext)

    /**
     * Copia cifrada del planificador para el recordatorio (fase 9). La escribe
     * [CachingMealPlanAccess] al abrir el planificador; la lee
     * [ReminderReceiver] sin red y sin la sesión.
     */
    val mealPlanCache: MealPlanCache = MealPlanCache(offlineStore)

    /**
     * Alarma del recordatorio. Va antes que [sessionStore] porque este la usa
     * para cancelar el aviso al cerrar la sesión (decisión D7).
     */
    val reminderScheduler: ReminderScheduler = ReminderScheduler(appContext, preferences)

    /** Lo que usan Ajustes para el interruptor, la hora y el permiso de notificaciones. */
    val reminderAccess: ReminderAccess = AndroidReminderAccess(appContext, preferences)

    init {
        // Si un borrado de datos offline se quedó a medias (disco lleno, Keystore
        // ocupado), se reintenta aquí: sin esto el fallo pasaría por «borrado». Solo
        // toca el disco cuando hay una marca pendiente; el arranque normal no hace
        // nada. Lo que no se pueda borrar ahora sigue cifrado.
        offlineStore.retryPendingClear()
        // Lo mismo con el ámbito de lo offline: si borrarlo se quedó marcado (un
        // `commit()` que no cuajó y el proceso murió), se repite aquí para no servir
        // las copias de la cuenta anterior. Sin marca no toca nada.
        offlineScope.retryPendingClear()
        // Al abrir la app se vuelve a poner la alarma del recordatorio: «Forzar
        // detención» y una actualización la borran, y así se recupera sin esperar
        // al reinicio. Si no toca (apagado, sin sesión) solo se cancela.
        reminderScheduler.schedule()
    }

    /** Caché de las recetas ya abiertas, para poder verlas sin conexión. */
    val recipeCache: RecipeCache = RecipeCache(offlineStore)

    /** Borradores del formulario de receta, para no perder lo escrito. */
    val draftStore: DraftStore = DraftStore(offlineStore)

    /** Lo que usan las pantallas de recetas: caché, borradores y ámbito, en uno. */
    val offline: OfflineRecipes = StoreOfflineRecipes(recipeCache, draftStore) { offlineScope.current() }

    /**
     * Copia cifrada de la lista de la compra abierta, para el widget. Reutiliza
     * el mismo [KeystoreBlobStore]: un blob por servidor y cuenta (`wg1.`), sin
     * URL, usuario ni token. Se borra con la sesión como el resto de lo offline.
     */
    val shoppingWidgetCache: ShoppingListCache = ShoppingListCache(offlineStore)

    /**
     * Publica la copia del widget cuando la pantalla de la compra cambia de
     * estado. La escritura va en un hilo de fondo y se queda con el último
     * estado; al guardar, avisa al widget con una difusión explícita
     * ([ShoppingWidgetUpdater]). Sin red.
     */
    val shoppingWidgetSink: ShoppingWidgetSink = ShoppingWidgetPublisher(
        cache = shoppingWidgetCache,
        scope = { offlineScope.current() },
        notifyWidget = { ShoppingWidgetUpdater.refresh(appContext) },
    )

    /**
     * La sesión guardada. Al cambiar de servidor o de token, y al borrarla, se
     * vacía además todo lo offline de la cuenta anterior (ver
     * [OfflineClearingSessionStore]): no queda caché de una sesión que ya no está.
     * El ámbito se apunta después de guardar bien la sesión ([ScopeRecordingSessionStore]),
     * así que probar un servidor sin guardarlo no deja nada.
     */
    val sessionStore: SessionStore = ReminderSessionStore(
        ScopeRecordingSessionStore(
            OfflineClearingSessionStore(keystoreSessionStore) {
                offlineScope.clear()
                val result = offlineStore.clearAll()
                // La copia del widget ya no vale: que se repinte vacío aunque el
                // borrado se quede marcado para el arranque siguiente.
                ShoppingWidgetUpdater.refresh(appContext)
                result
            },
            offlineScope,
        ),
        reminderScheduler,
    )

    val activeNetwork: ActiveNetworkProvider = AndroidActiveNetworkProvider(appContext)
    val connectionTester: ConnectionTester = MealieConnectionTester(activeNetwork, offlineScope)
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
     * Va decorada para dejar la copia cifrada que lee el recordatorio
     * ([CachingMealPlanAccess]): la pantalla no cambia y el receptor no hace red.
     */
    val mealPlanAccess: MealPlanAccess = CachingMealPlanAccess(
        delegate = StoreMealPlanAccess(sessionStore) { session ->
            MealPlanClient.create(session, preferences.allowLocalHttp, activeNetwork)
        },
        cache = mealPlanCache,
        scope = { offlineScope.current() },
    )

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

    /**
     * Pide al widget que se repinte desde la copia cifrada. Se llama al abrir la
     * app (`MainActivity.onStart`): así un widget recién añadido, o el que
     * quedó con la copia anterior, se pone al día sin consultar la red.
     */
    fun refreshShoppingWidget() {
        ShoppingWidgetUpdater.refresh(appContext)
    }
}
