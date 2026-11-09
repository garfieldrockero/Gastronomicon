package io.github.garfieldrockero.gastronomicon.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.garfieldrockero.gastronomicon.core.client.ImageResult
import io.github.garfieldrockero.gastronomicon.core.network.NetworkError
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Desenlace de cargar una imagen. Sin mensajes técnicos: la interfaz solo pone o no la foto. */
sealed interface ImageLoadResult {
    class Loaded(val bitmap: Bitmap) : ImageLoadResult

    /** No hay imagen utilizable: no existe, no es de un tipo válido, pesa demasiado o no se puede decodificar. */
    data object Missing : ImageLoadResult

    /** El servidor rechazó el token. La imagen se queda en el marcador; el aviso de sesión lo da la pantalla. */
    data object Unauthorized : ImageLoadResult

    /** Fallo de red o de servidor. Se puede volver a intentar al volver a mostrarla. */
    data object Failed : ImageLoadResult
}

/**
 * Carga imágenes de receta sin librerías: [ClientAccess] + `fetchImage` del
 * núcleo (fuera del hilo principal) y `BitmapFactory`.
 *
 * - Decodifica en dos pasos: primero solo las medidas (`inJustDecodeBounds`,
 *   que no reserva el bitmap) y después con `inSampleSize`. Antes de reservar
 *   nada se rechaza lo que declara medidas absurdas o más de
 *   [MAX_SOURCE_PIXELS], y se sube el `inSampleSize` hasta que el bitmap
 *   resultante quepa en [MAX_BITMAP_BYTES]: un PNG pequeño de peso pero enorme
 *   de medidas no llega a reservar su tamaño real.
 * - Caché SOLO en memoria (`LruCache` acotada por bytes). Sin caché en disco,
 *   decidido a propósito: las fotos de una cocina ajena no quedan en el móvil.
 * - Un tope de [MAX_CONCURRENT] cargas a la vez para que una lista que se
 *   desplaza deprisa no abra decenas de conexiones.
 * - Cancelable: la descarga ya empezada termina por su cuenta
 *   (HttpURLConnection es bloqueante), pero si la corrutina se ha cancelado su
 *   resultado se descarta: ni se decodifica ni entra en la caché.
 * - Los «no hay imagen» se recuerdan (acotados) para no repetir la petición
 *   cada vez que la fila vuelve a la pantalla.
 *
 * [clear] vacía todo: hay que llamarlo cuando cambia la sesión, porque las
 * claves son ids de receta del servidor anterior. Una carga que empezó antes
 * de [clear] y acaba después se descarta (devuelve `Failed`, sin caché).
 *
 * Ciclo de vida: [AppContainer] se crea por Activity, así que al girar la
 * pantalla hay un ImageLoader nuevo y vacío (las fotos se vuelven a pedir).
 * Una carga del anterior sigue hasta acabar su petición y se descarta sola; no
 * se filtra la Activity porque el contenedor solo guarda `applicationContext`
 * y este objeto no guarda ningún contexto, solo [ClientAccess].
 */
class ImageLoader(
    private val access: ClientAccess,
    maxCacheBytes: Int = DEFAULT_CACHE_BYTES,
) {
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val missing = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_MISSING
    }

    private val limiter = Semaphore(MAX_CONCURRENT)

    // Sube en cada clear(): una carga que empezó con la sesión anterior no deja nada en la caché.
    @Volatile
    private var epoch = 0

    // Hace atómicos «comprobar epoch + escribir» y «subir epoch + vaciar», para que un put
    // de la sesión anterior no pueda colarse entre el aumento del contador y el vaciado.
    // Orden de bloqueos: primero este, después `missing`.
    private val epochLock = Any()

    /** Imagen ya en memoria, sin red; null si no está. Para pintar al instante al volver a una fila. */
    fun cached(recipeId: String, size: ImageSize, maxSidePx: Int): Bitmap? =
        cache.get(key(recipeId, size, maxSidePx))

    /**
     * Pide la imagen de la receta [recipeId] (su id, no el slug) a ~[maxSidePx]
     * píxeles por el lado largo.
     */
    suspend fun load(recipeId: String, size: ImageSize, maxSidePx: Int): ImageLoadResult {
        val key = key(recipeId, size, maxSidePx)
        cache.get(key)?.let { return ImageLoadResult.Loaded(it) }
        if (synchronized(missing) { missing.containsKey(key) }) return ImageLoadResult.Missing
        val startedAt = epoch
        return limiter.withPermit {
            withContext(Dispatchers.IO) { loadBlocking(key, startedAt, recipeId, size, maxSidePx) }
        }
    }

    fun clear() {
        synchronized(epochLock) {
            epoch++
            cache.evictAll()
            synchronized(missing) { missing.clear() }
        }
    }

    /**
     * Olvida lo que se guardó de la receta [recipeId]: su foto ha cambiado (se
     * acaba de subir o de borrar), así que la próxima vez que se pinte hay que
     * pedirla de nuevo.
     *
     * Solo toca las entradas de esa receta: la lista y el resto de fotos siguen
     * en memoria. Tampoco sube [epoch]: una carga de la MISMA receta que
     * empezara antes de la subida podría dejar la foto vieja en la caché, así
     * que quien la pide después de subir la foto recarga el detalle (y con él,
     * la foto) en vez de fiarse de una carga anterior.
     */
    fun invalidate(recipeId: String) {
        val prefix = "$recipeId|"
        synchronized(epochLock) {
            val keys = cache.snapshot().keys
            for (key in keys) {
                if (key.startsWith(prefix)) cache.remove(key)
            }
            synchronized(missing) {
                // Copia: no se puede quitar de la colección mientras se recorre.
                for (key in missing.keys.toList()) {
                    if (key.startsWith(prefix)) missing.remove(key)
                }
            }
        }
    }

    // ------------------------------------------------------------- privados

    private suspend fun loadBlocking(key: String, startedAt: Int, recipeId: String, size: ImageSize, maxSidePx: Int): ImageLoadResult {
        val result = try {
            access.newClient().fetchImage(recipeId, size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin sesión, almacén caído, HTTP apagado…: sin imagen por ahora.
            return if (e is NetworkError.HttpError && e.statusCode == 401) {
                ImageLoadResult.Unauthorized
            } else {
                ImageLoadResult.Failed
            }
        }
        currentCoroutineContext().ensureActive()
        return when (result) {
            is ImageResult.Success -> {
                val bitmap = decode(result.bytes, maxSidePx)
                if (bitmap == null) {
                    remember(key, startedAt)
                    ImageLoadResult.Missing
                } else {
                    // Si la sesión cambió durante la carga (clear()), la foto es del servidor
                    // anterior: ni a la caché ni a la pantalla. Comprobar y escribir, juntos.
                    val current = synchronized(epochLock) {
                        (startedAt == epoch).also { if (it) cache.put(key, bitmap) }
                    }
                    if (current) ImageLoadResult.Loaded(bitmap) else ImageLoadResult.Failed
                }
            }
            ImageResult.NotFound, ImageResult.InvalidImage, ImageResult.TooLarge -> {
                remember(key, startedAt)
                ImageLoadResult.Missing
            }
            ImageResult.Unauthorized -> ImageLoadResult.Unauthorized
            is ImageResult.Failure -> ImageLoadResult.Failed
        }
    }

    private fun remember(key: String, startedAt: Int) {
        synchronized(epochLock) {
            if (startedAt == epoch) synchronized(missing) { missing[key] = true }
        }
    }

    /** Null si no se puede decodificar o es desmesurada: se trata como «sin imagen». */
    private fun decode(bytes: ByteArray, maxSidePx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            if (width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) return null
            if (width.toLong() * height.toLong() > MAX_SOURCE_PIXELS) return null

            // Todo esto se decide con las medidas, antes de reservar ningún bitmap.
            var sample = sampleSizeFor(width, height, maxSidePx.coerceIn(MIN_SIDE_PX, MAX_SIDE_PX))
            while (decodedBytes(width, height, sample) > MAX_BITMAP_BYTES) {
                if (sample >= MAX_SAMPLE_SIZE) return null
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            // Red de seguridad: la cuenta de arriba supone 4 bytes por píxel.
            if (bitmap.allocationByteCount > MAX_BITMAP_BYTES) {
                bitmap.recycle()
                return null
            }
            bitmap
        } catch (ignored: OutOfMemoryError) {
            null
        } catch (ignored: RuntimeException) {
            null
        }
    }

    private fun key(recipeId: String, size: ImageSize, maxSidePx: Int) = "$recipeId|${size.name}|$maxSidePx"

    companion object {
        /** Unos 16 MiB: de sobra para las miniaturas de una lista. */
        const val DEFAULT_CACHE_BYTES = 16 * 1024 * 1024

        /** Más de 40 megapíxeles declarados no se decodifica, ni reducido. */
        const val MAX_SOURCE_PIXELS = 40L * 1000 * 1000

        /** Más de esto por un lado, aunque pocos píxeles en total (una tira), tampoco se decodifica. */
        const val MAX_SOURCE_SIDE = 16_384

        private const val MAX_SAMPLE_SIZE = 64

        /** Tope de un bitmap ya decodificado. */
        const val MAX_BITMAP_BYTES = 12 * 1024 * 1024

        const val MAX_CONCURRENT = 3
        private const val MAX_MISSING = 500
        private const val MIN_SIDE_PX = 64
        private const val MAX_SIDE_PX = 2048

        /** Lo que ocuparía el bitmap con ese `inSampleSize`, suponiendo 4 bytes por píxel (el peor caso). */
        internal fun decodedBytes(width: Int, height: Int, sample: Int): Long {
            val w = (width.toLong() + sample - 1) / sample
            val h = (height.toLong() + sample - 1) / sample
            return w * h * 4
        }

        /** Potencia de dos más grande que deja el lado largo en [targetSide] o más. */
        internal fun sampleSizeFor(width: Int, height: Int, targetSide: Int): Int {
            var sample = 1
            val longSide = maxOf(width, height)
            while (longSide / (sample * 2) >= targetSide) sample *= 2
            return sample
        }
    }
}
