package io.github.garfieldrockero.gastronomicon.platform

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import io.github.garfieldrockero.gastronomicon.core.recipes.ImageRecodePlan
import io.github.garfieldrockero.gastronomicon.core.recipes.RecodePlan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Qué ha pasado al preparar una foto para subirla. Sin textos ni mensajes de
 * excepción: la pantalla pone el suyo (ver `CONTEXTO.md`, estados obligatorios).
 */
sealed interface ImageRecodeResult {

    /**
     * JPEG listo para subir: recodificado, reducido y sin metadatos.
     *
     * [extension] es `jpeg` (no `jpg`) porque es lo único que acepta
     * `MealieClient.uploadImage`, que compara la extensión con el tipo MIME.
     */
    class Ok(val bytes: ByteArray, val extension: String = ImageRecoder.EXTENSION) : ImageRecodeResult

    /**
     * La foto de entrada pasa de [ImageRecodePlan.MAX_INPUT_PIXELS], pesa más de
     * [ImageRecoder.MAX_INPUT_BYTES] o no baja de [ImageRecodePlan.MAX_OUTPUT_BYTES]
     * ni con la calidad más baja.
     */
    data object TooLarge : ImageRecodeResult

    /** El fichero elegido no es una imagen que se pueda decodificar (o sus medidas no tienen sentido). */
    data object NotAnImage : ImageRecodeResult

    /** No se ha podido leer o procesar. Se puede reintentar con otra foto. */
    data object Failed : ImageRecodeResult
}

/**
 * Deja una foto lista para subirla a Mealie: lee los bytes del [Uri] (galería o
 * cámara), la decodifica, la endereza, la reduce y la comprime a JPEG.
 *
 * - **Las cuentas están en `core`** ([ImageRecodePlan]): tope de píxeles de entrada,
 *   `inSampleSize`, lado máximo de 2048 px y escalera de calidades 85, 75, 65, 55
 *   hasta caber en 5 MiB. Aquí solo está lo que necesita Android, y en ningún caso
 *   se recalcula nada de eso en la pantalla.
 * - **EXIF fuera**: `Bitmap.compress` escribe un JPEG nuevo, así que la foto que sale
 *   de aquí no lleva ni coordenadas, ni fecha, ni modelo del móvil. Del original se
 *   lee **solo** la orientación, para girar el bitmap; no se copia ningún otro tag.
 * - **Entrada acotada y solo `content://`**: se leen como mucho [MAX_INPUT_BYTES] y se
 *   decodifican primero las medidas (`inJustDecodeBounds`) para rechazar una foto
 *   desmesurada antes de reservar memoria para el bitmap. Un esquema que no sea
 *   `content://` (`file://`, `android.resource://`…) se rechaza sin abrirlo: el Uri lo
 *   elige un proveedor de terceros cuando el móvil no tiene el selector de fotos.
 * - **Fuera del hilo principal y cancelable**: corre en `Dispatchers.IO` y comprueba
 *   entre pasos que la corrutina siga viva; si se cancela, no devuelve bytes.
 * - **Sin logs**: ni el Uri, ni el nombre del fichero, ni el mensaje de la excepción
 *   salen de aquí.
 *
 * [recode] con `discardSource = true` borra al terminar el fichero temporal de la
 * cámara (también si falla). Una foto elegida de la galería **no se borra nunca**:
 * el borrado solo toca los Uri del FileProvider propio (ver `CameraFiles.discard`).
 */
class ImageRecoder(context: Context) {

    // applicationContext: esto sobrevive a la pantalla que lo crea.
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /**
     * Recodifica la imagen de [uri]. Con [discardSource], borra el fichero temporal de
     * la cámara al acabar, haya salido bien o mal.
     *
     * [discardSource] dice además de dónde viene la foto: solo la de la cámara puede ser
     * un Uri del FileProvider propio (`content://<paquete>.camara/…`). Una foto de
     * galería con esa autoridad se rechaza sin leerla.
     */
    suspend fun recode(uri: Uri, discardSource: Boolean = false): ImageRecodeResult =
        // El `finally` va fuera de `withContext`: si la corrutina ya está cancelada al
        // entrar, el bloque interior no llega a correr y el temporal se quedaría.
        try {
            withContext(Dispatchers.IO) {
                try {
                    val result = recodeBlocking(uri, fromCamera = discardSource)
                    // Cancelada a mitad: el resultado se tira, no se sube nada.
                    currentCoroutineContext().ensureActive()
                    result
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (ignored: OutOfMemoryError) {
                    ImageRecodeResult.Failed
                } catch (ignored: Exception) {
                    ImageRecodeResult.Failed
                }
            }
        } finally {
            if (discardSource) CameraFiles.discard(appContext, uri)
        }

    /** Borra un temporal de la cámara que no se va a recodificar (solo toca el FileProvider propio). */
    fun discard(uri: Uri) {
        CameraFiles.discard(appContext, uri)
    }

    // ------------------------------------------------------------- privados

    private suspend fun recodeBlocking(uri: Uri, fromCamera: Boolean): ImageRecodeResult {
        val source = when (val read = readInput(uri, fromCamera)) {
            is InputRead.Read -> read.bytes
            InputRead.TooBig -> return ImageRecodeResult.TooLarge
            InputRead.Unreadable -> return ImageRecodeResult.Failed
        }

        // Primero las medidas: no se reserva ni un byte del bitmap hasta saber que cabe.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val plan = when (val wanted = ImageRecodePlan.plan(bounds.outWidth, bounds.outHeight)) {
            is RecodePlan.Ready -> wanted
            RecodePlan.InvalidDimensions -> return ImageRecodeResult.NotAnImage
            is RecodePlan.TooManyPixels -> return ImageRecodeResult.TooLarge
        }

        currentCoroutineContext().ensureActive()
        val orientation = readOrientation(source)

        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply { inSampleSize = plan.inSampleSize },
        ) ?: return ImageRecodeResult.NotAnImage

        // El bitmap grande se libera en cuanto se escala: así no hay dos en memoria a la vez.
        // `working` es el que se está usando; `oriented`, el que se comprime.
        var working: Bitmap = decoded
        var oriented: Bitmap? = null
        return try {
            if (working.width != plan.width || working.height != plan.height) {
                val scaled = Bitmap.createScaledBitmap(working, plan.width, plan.height, true)
                if (scaled !== working) {
                    working.recycle()
                    working = scaled
                }
            }
            currentCoroutineContext().ensureActive()
            val straightened = orient(working, orientation)
            oriented = straightened
            if (straightened !== working) working.recycle()
            compress(straightened, plan)
        } finally {
            if (!working.isRecycled) working.recycle()
            val finished = oriented
            if (finished != null && !finished.isRecycled) finished.recycle()
        }
    }

    /**
     * Lee el fichero con tope de bytes, sin fiarse del tamaño que declare el proveedor:
     * el tamaño solo sirve para reservar memoria y para rechazar antes de leer.
     *
     * Solo se abre `content://`. El descriptor se abre una vez y se cierra siempre; el
     * tamaño declarado, si lo hay, se acota a [MAX_INPUT_BYTES].
     */
    private suspend fun readInput(uri: Uri, fromCamera: Boolean): InputRead {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return InputRead.Unreadable
        // Una foto de galería no puede ser un temporal nuestro: si un proveedor hostil
        // devuelve un Uri de nuestro FileProvider, se rechaza (solo expone `camara/`).
        if (!fromCamera && uri.authority == CameraFiles.authority(appContext)) {
            return InputRead.Unreadable
        }
        val descriptor: AssetFileDescriptor = try {
            resolver.openAssetFileDescriptor(uri, "r") ?: return InputRead.Unreadable
        } catch (ignored: Exception) {
            return InputRead.Unreadable
        }
        return try {
            val declared = declaredSize(descriptor)
            if (declared > MAX_INPUT_BYTES) {
                InputRead.TooBig
            } else {
                descriptor.createInputStream().use { readLimited(it, declared) }
            }
        } catch (ignored: IOException) {
            InputRead.Unreadable
        } catch (ignored: SecurityException) {
            InputRead.Unreadable
        } finally {
            // El stream ya cierra el descriptor; repetirlo es inofensivo y cubre el fallo
            // al crearlo.
            try {
                descriptor.close()
            } catch (ignored: IOException) {
                // Nada que hacer: el descriptor ya no se usa.
            }
        }
    }

    /** Tamaño que declara el descriptor, o 0 si no lo da. Nunca negativo. */
    private fun declaredSize(descriptor: AssetFileDescriptor): Long =
        try {
            // `UNKNOWN_LENGTH` (-1) y los 0 sin sentido se tratan como «no lo sé».
            val length = descriptor.length
            if (length > 0L) length else 0L
        } catch (ignored: Exception) {
            0L
        }

    /**
     * Lee con tope y reserva de una vez lo que el proveedor haya declarado, para no
     * duplicar el búfer al crecer. Comprueba la cancelación cada [CANCEL_CHECK_EVERY]
     * lecturas: un proveedor lento o que no termina no deja el hilo de IO colgado.
     */
    private suspend fun readLimited(input: InputStream, capacity: Long): InputRead {
        // `capacity` ya está acotada a [MAX_INPUT_BYTES], así que cabe en un Int.
        val collected = ByteArrayOutputStream(capacity.toInt())
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var reads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MAX_INPUT_BYTES) return InputRead.TooBig
            collected.write(buffer, 0, count)
            if (++reads % CANCEL_CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
        }
        val bytes = collected.toByteArray()
        return if (bytes.isEmpty()) InputRead.Unreadable else InputRead.Read(bytes)
    }

    /**
     * Solo la orientación. `android.media.ExifInterface` del framework (no
     * `androidx.exifinterface`): un PNG o un WebP sin cabecera EXIF lanza y se queda
     * en la orientación normal, que es lo correcto.
     */
    private fun readOrientation(source: ByteArray): Int =
        try {
            ExifInterface(ByteArrayInputStream(source)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (ignored: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        } catch (ignored: RuntimeException) {
            ExifInterface.ORIENTATION_NORMAL
        }

    /** Gira (o voltea) según la orientación EXIF. Sin orientación, el mismo bitmap. */
    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Escalera de calidades de `core`: la primera que quepa, o no se sube. */
    private fun compress(bitmap: Bitmap, plan: RecodePlan.Ready): ImageRecodeResult {
        // Un solo búfer, vaciado entre intentos: no se reservan cuatro JPEG seguidos.
        val out = ByteArrayOutputStream()
        for (quality in plan.jpegQualities) {
            out.reset()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                return ImageRecodeResult.Failed
            }
            if (out.size().toLong() <= plan.maxOutputBytes) {
                return ImageRecodeResult.Ok(out.toByteArray())
            }
        }
        return ImageRecodeResult.TooLarge
    }

    companion object {
        /**
         * Extensión que se devuelve con los bytes. `MealieClient.uploadImage` solo
         * acepta `webp`, `jpeg` y `png`: aquí se produce JPEG, así que es `jpeg`.
         */
        const val EXTENSION = "jpeg"

        /**
         * Tope del fichero de entrada. Una foto de móvil de 50 megapíxeles ronda los
         * 20-25 MiB en JPEG; 24 MiB deja margen para una foto normal y evita reservar de
         * golpe más memoria de la que un móvil justo aguanta (se leen en RAM y luego se
         * decodifican). Una foto que pase de aquí no es una foto de cámara normal.
         */
        const val MAX_INPUT_BYTES = 24L * 1024 * 1024

        private const val BUFFER_BYTES = 8 * 1024

        /** Cada cuántas lecturas (de [BUFFER_BYTES]) se comprueba la cancelación: ~128 KiB. */
        private const val CANCEL_CHECK_EVERY = 16
    }
}

/** Lo que ha dado la lectura del fichero, antes de decodificarlo. */
private sealed interface InputRead {
    class Read(val bytes: ByteArray) : InputRead

    data object TooBig : InputRead

    data object Unreadable : InputRead
}
