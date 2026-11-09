package io.github.garfieldrockero.gastronomicon.platform

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** De dónde viene la foto elegida. Decide si el fichero es temporal y hay que borrarlo. */
enum class PhotoSource {
    /** Selector de fotos del sistema: el fichero es de la persona y nunca se borra. */
    Gallery,

    /** Cámara del sistema: el fichero es un temporal nuestro, en `cacheDir/camara`. */
    Camera,
}

/**
 * Qué ha pasado al pedir una foto. Sin mensajes de excepción: la pantalla decide qué
 * texto poner. La única excepción es [PhotoPickResult.NO_CAMERA_MESSAGE], que es fijo.
 */
sealed interface PhotoPickResult {

    /** Hay una foto que se puede recodificar. */
    data class Picked(val uri: Uri, val source: PhotoSource) : PhotoPickResult

    /** Se cerró el selector o la cámara sin dejar foto. No hay nada que contar. */
    data object Cancelled : PhotoPickResult

    /**
     * El móvil no tiene cámara o no hay ninguna app que haga fotos. Se avisa y se
     * ofrece elegir de la galería.
     */
    data object NoCamera : PhotoPickResult

    /** No se ha podido preparar el fichero temporal o abrir el selector. */
    data object Failed : PhotoPickResult

    companion object {
        /**
         * Texto fijo para [NoCamera]. Vive aquí y no en `strings.xml` porque lo pone la
         * parte de plataforma; al montar el formulario se puede mover a los recursos.
         */
        const val NO_CAMERA_MESSAGE =
            "Este móvil no tiene una app de cámara. Puedes elegir la foto de la galería."
    }
}

/**
 * Los dos selectores de foto de la app, para usar desde Compose con
 * `ActivityResultContracts`:
 *
 * - **Galería**: `PickVisualMedia` (la fototeca del sistema). Sin permisos de
 *   almacenamiento ni de imágenes: el sistema solo nos deja leer lo que se elige.
 * - **Cámara**: `TakePicture` con la app de cámara del sistema y un fichero nuevo en
 *   `cacheDir/camara/` compartido por el FileProvider propio. Sin CameraX y sin permiso
 *   `CAMERA`.
 *
 * El temporal de la cámara se borra en cuanto se cancela la cámara, y quien recodifique
 * la foto debe borrarlo al acabar (`ImageRecoder.recode(uri, discardSource = true)`) o
 * llamar a [discard]. Lo que quede de un arranque anterior se borra entero al empezar el
 * proceso con [purgeCameraFilesAsync].
 *
 * Un fallo al abrir el selector no rompe la pantalla: se entrega un resultado tipado.
 */
class PhotoPicker internal constructor(
    private val context: Context,
    private val gallery: ActivityResultLauncher<PickVisualMediaRequest>,
    private val camera: ActivityResultLauncher<Uri>,
    private val pending: PendingCamera,
    private val deliver: (PhotoPickResult) -> Unit,
) {

    /** Abre la fototeca del sistema para elegir una foto ya hecha. */
    fun pickFromGallery() {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        try {
            gallery.launch(request)
        } catch (ignored: RuntimeException) {
            deliver(PhotoPickResult.Failed)
        }
    }

    /** Abre la cámara del sistema. La foto queda en un temporal que hay que recodificar. */
    fun takePhoto() {
        // Con una cámara en curso (el Uri está pendiente) un segundo toque no borra el
        // temporal del primero ni abre otra cámara: no hay nada que hacer hasta que vuelva.
        // Un Uri huérfano (el proceso murió con la cámara abierta; su fichero ya se borró
        // al arrancar) no bloquea: se descarta y se sigue.
        if (pending.uri != null && CameraFiles.exists(context, pending.uri)) return

        // Un temporal que se quedó de un intento anterior no se reutiliza.
        CameraFiles.discard(context, pending.uri)
        pending.uri = null

        if (!hasCamera(context)) {
            deliver(PhotoPickResult.NoCamera)
            return
        }
        val file = CameraFiles.newFile(context)
        if (file == null) {
            deliver(PhotoPickResult.Failed)
            return
        }
        val uri = try {
            FileProvider.getUriForFile(context, CameraFiles.authority(context), file)
        } catch (ignored: IllegalArgumentException) {
            file.delete()
            deliver(PhotoPickResult.Failed)
            return
        }
        pending.uri = uri
        try {
            camera.launch(uri)
        } catch (ignored: ActivityNotFoundException) {
            pending.uri = null
            file.delete()
            deliver(PhotoPickResult.NoCamera)
        } catch (ignored: RuntimeException) {
            pending.uri = null
            file.delete()
            deliver(PhotoPickResult.Failed)
        }
    }

    /**
     * Borra el temporal de la cámara si [uri] es uno de los nuestros. Las fotos de la
     * galería no se tocan nunca.
     */
    fun discard(uri: Uri?) {
        CameraFiles.discard(context, uri)
    }

    private fun hasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
}

/**
 * Devuelve un [PhotoPicker] listo para [PhotoPicker.pickFromGallery] y
 * [PhotoPicker.takePhoto]. [onResult] recibe cada desenlace; se puede leer estado de la
 * pantalla sin que el selector se quede con una versión vieja.
 */
@Composable
fun rememberPhotoPicker(onResult: (PhotoPickResult) -> Unit): PhotoPicker {
    val context = LocalContext.current.applicationContext
    val latest = rememberUpdatedState(onResult)
    val deliver: (PhotoPickResult) -> Unit = remember { { result -> latest.value(result) } }
    // El Uri del temporal se guarda en el estado de la instancia: si el sistema recrea la
    // pantalla con la cámara abierta, al volver se recupera en vez de perder la foto.
    val savedPending = rememberSaveable { mutableStateOf<String?>(null) }
    val pending = remember { PendingCamera(savedPending) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        deliver(
            if (uri == null) {
                PhotoPickResult.Cancelled
            } else {
                PhotoPickResult.Picked(uri, PhotoSource.Gallery)
            },
        )
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pending.uri
        pending.uri = null
        if (saved && uri != null) {
            deliver(PhotoPickResult.Picked(uri, PhotoSource.Camera))
        } else {
            // Cancelada o fallida: el temporal no se queda en la caché.
            CameraFiles.discard(context, uri)
            deliver(PhotoPickResult.Cancelled)
        }
    }

    return remember(context, gallery, camera) {
        PhotoPicker(context, gallery, camera, pending, deliver)
    }
}

/**
 * Bandera del proceso: la limpieza de la caché de fotos se hace una sola vez. La actividad
 * se recrea (un giro, volver de segundo plano) y no puede repetirla: borraría la foto que
 * la cámara está escribiendo ahora mismo.
 */
private val camaraPurgada = AtomicBoolean(false)

/**
 * Momento en el que arranca el proceso (al cargarse este fichero, antes de que la pantalla
 * deje abrir la cámara). Todo lo anterior es un resto de una sesión pasada; lo posterior
 * sería una foto en curso y no se toca.
 */
private val procesoIniciadoEn = System.currentTimeMillis()

/**
 * Vacía `cacheDir/camara/` al empezar el proceso. El formulario no guarda su estado, así que
 * en un arranque en frío no puede haber ninguna foto en curso: todo lo que ya estuviera ahí
 * es un resto (con sus EXIF y su GPS) que sobra. Solo corre la primera vez por proceso,
 * fuera del hilo principal; las fotos de la galería no se tocan.
 */
fun purgeCameraFilesAsync(context: Context) {
    if (!camaraPurgada.compareAndSet(false, true)) return
    val appContext = context.applicationContext
    thread(isDaemon = true, name = "fotos-temporales") {
        CameraFiles.purgeAll(appContext, procesoIniciadoEn)
    }
}

/**
 * Uri del temporal de la cámara que está abierto ahora mismo, para poder borrarlo. El
 * valor vive en un estado que sobrevive a la recreación de la pantalla (un giro o falta
 * de memoria mientras la cámara está delante): así la foto recién hecha no se pierde.
 */
internal class PendingCamera(private val saved: MutableState<String?>) {

    var uri: Uri?
        get() = saved.value?.let { Uri.parse(it) }
        set(value) {
            saved.value = value?.toString()
        }
}

/**
 * Todo lo que toca el disco de la foto de la cámara, en un solo sitio: crear el
 * temporal, borrarlo (solo si es nuestro) y vaciar la carpeta al arrancar.
 */
internal object CameraFiles {

    /** Sufijo de la autoridad del FileProvider, igual que en `AndroidManifest.xml`. */
    const val AUTHORITY_SUFFIX = ".camara"

    private const val DIRECTORY_NAME = "camara"
    private const val NAME_PREFIX = "foto-"
    private const val NAME_SUFFIX = ".jpg"

    /**
     * Autoridad del FileProvider. Se deriva del paquete en tiempo de ejecución, igual que
     * `${applicationId}` en el manifiesto: si cambiara la identidad de la app, las dos
     * seguirían coincidiendo.
     */
    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /** Un fichero nuevo y vacío dentro de `cacheDir/camara`. Null si no hay dónde. */
    fun newFile(context: Context): File? {
        val dir = directory(context) ?: return null
        return try {
            // Nombre aleatorio del sistema, sin datos de la persona.
            File.createTempFile(NAME_PREFIX, NAME_SUFFIX, dir)
        } catch (ignored: IOException) {
            null
        }
    }

    /**
     * Borra [uri] **solo** si es un temporal nuestro: misma autoridad que el FileProvider
     * y un nombre suelto dentro de `cacheDir/camara`. Una foto de la galería no cumple
     * ninguna de las dos cosas, así que nunca se borra.
     */
    fun discard(context: Context, uri: Uri?) {
        val file = fileOf(context, uri) ?: return
        try {
            file.delete()
        } catch (ignored: SecurityException) {
            // Sin permiso para borrar: se queda en la caché y lo limpia Android.
        }
    }

    /** True si [uri] es un temporal nuestro que sigue en el disco. */
    fun exists(context: Context, uri: Uri?): Boolean {
        val file = fileOf(context, uri) ?: return false
        return file.isFile
    }

    /**
     * Borra lo que ya estuviera en `camara/` antes de [before] (el arranque del proceso).
     * No hay ninguna foto en curso que conservar y cualquier resto lleva EXIF (y GPS) de
     * más. Un fichero con marca posterior es de este arranque y no se toca.
     */
    fun purgeAll(context: Context, before: Long) {
        val dir = existingDirectory(context) ?: return
        val files = try {
            dir.listFiles()
        } catch (ignored: SecurityException) {
            null
        } ?: return
        for (file in files) {
            if (!file.isFile) continue
            try {
                if (file.lastModified() < before) file.delete()
            } catch (ignored: SecurityException) {
                return
            }
        }
    }

    // ------------------------------------------------------------- privados

    private fun directory(context: Context): File? {
        val dir = File(context.cacheDir, DIRECTORY_NAME)
        if (dir.isDirectory) return dir
        return try {
            if (dir.mkdirs()) dir else null
        } catch (ignored: SecurityException) {
            null
        }
    }

    /** La carpeta si ya existe; no la crea. Para purgar y para comprobar un Uri. */
    private fun existingDirectory(context: Context): File? {
        val dir = File(context.cacheDir, DIRECTORY_NAME)
        return if (dir.isDirectory) dir else null
    }

    /** El fichero si [uri] es un temporal nuestro; null en cualquier otro caso. */
    private fun fileOf(context: Context, uri: Uri?): File? {
        if (uri == null) return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        if (uri.authority != authority(context)) return null
        val dir = existingDirectory(context) ?: return null
        // El FileProvider pone el nombre del fichero como último segmento.
        val name = uri.lastPathSegment ?: return null
        if (name.isEmpty()) return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val candidate = File(dir, name)
        return try {
            val canonical = candidate.canonicalFile
            if (canonical.parentFile == dir.canonicalFile) canonical else null
        } catch (ignored: IOException) {
            null
        }
    }
}
