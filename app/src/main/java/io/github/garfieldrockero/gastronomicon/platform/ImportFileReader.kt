package io.github.garfieldrockero.gastronomicon.platform

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.github.garfieldrockero.gastronomicon.core.client.MealieClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Qué ha salido de leer un archivo elegido con el selector del sistema.
 *
 * Sin rutas, sin nombres de fichero y sin mensajes de excepción: lo que se
 * enseña lo decide la pantalla, y el nombre del archivo nunca hace falta.
 */
sealed interface ImportFileResult {

    /**
     * El archivo era texto UTF-8 válido. [byteCount] son los bytes leídos, que
     * es lo único que se puede enseñar del archivo sin pintar su contenido.
     */
    class Text(val content: String, val byteCount: Int) : ImportFileResult

    /**
     * Pasa de `MealieClient.MAX_IMPORT_DATA_BYTES`: se ha dejado de leer en
     * cuanto se ha sabido y no se ha cargado entero.
     */
    data object TooLarge : ImportFileResult

    /** Los bytes no son UTF-8 válido: un ZIP, un PDF o una codificación rota. */
    data object NotText : ImportFileResult

    /** No se ha podido abrir o leer (proveedor que falla, permiso caducado, memoria). */
    data object Unreadable : ImportFileResult
}

/**
 * Lee el archivo que se elige para importar una receta (JSON o HTML).
 *
 * - **Solo `content://`**: es lo único que devuelve el selector del sistema
 *   (`ActivityResultContracts.OpenDocument`). `file://` o `android.resource://`
 *   se rechazan sin abrirlos.
 * - **Lectura acotada**: se leen como mucho `MAX_IMPORT_DATA_BYTES + 1` bytes
 *   ([MealieClient.MAX_IMPORT_DATA_BYTES]); en cuanto se pasa, se deja de leer y
 *   se devuelve [ImportFileResult.TooLarge] sin tener el archivo entero en
 *   memoria. El tope se cuenta en bytes, igual que en el núcleo.
 * - **UTF-8 estricto**: `String(bytes, UTF_8)` cambiaría en silencio los bytes
 *   inválidos por U+FFFD y eso se enviaría al servidor; aquí se decodifica con
 *   `CodingErrorAction.REPORT` y, si falla, es [ImportFileResult.NotText].
 * - **Fuera del hilo principal y cancelable**: corre en `Dispatchers.IO` y
 *   comprueba la cancelación cada pocas lecturas; cancelada, no devuelve texto.
 * - **Sin logs**: ni el Uri, ni el nombre, ni los bytes, ni el mensaje de la
 *   excepción salen de aquí. El stream se cierra siempre.
 *
 * Lo que solo se puede ver ya como texto (controles, surrogados sueltos) y el
 * tamaño exacto los vuelve a comprobar el núcleo (`importFromData`) antes de
 * enviar nada: aquí no se duplica esa regla.
 */
class ImportFileReader(context: Context) {

    // applicationContext: esto sobrevive a la pantalla que lo crea.
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Lee [uri] y devuelve su texto, o el motivo por el que no se puede usar. */
    suspend fun read(uri: Uri): ImportFileResult = withContext(Dispatchers.IO) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@withContext ImportFileResult.Unreadable
        }
        var stream: InputStream? = null
        try {
            val opened = resolver.openInputStream(uri) ?: return@withContext ImportFileResult.Unreadable
            stream = opened
            val bytes = readBounded(opened) ?: return@withContext ImportFileResult.TooLarge
            // Cancelada a mitad: lo leído se tira, no se envía nada.
            currentCoroutineContext().ensureActive()
            val text = decodeUtf8(bytes) ?: return@withContext ImportFileResult.NotText
            ImportFileResult.Text(text, bytes.size)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ignored: OutOfMemoryError) {
            ImportFileResult.Unreadable
        } catch (ignored: Exception) {
            // FileNotFoundException, SecurityException, IllegalArgumentException de un
            // Uri malformado…: para la persona es lo mismo, el archivo no se ha podido usar.
            ImportFileResult.Unreadable
        } finally {
            stream?.let { closeQuietly(it) }
        }
    }

    /**
     * Lee como mucho `MAX_IMPORT_DATA_BYTES + 1` bytes. Devuelve `null` en cuanto
     * se pasa del tope: nunca se acumula más de lo permitido.
     */
    private suspend fun readBounded(input: InputStream): ByteArray? {
        val collected = ByteArrayOutputStream(INITIAL_CAPACITY)
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        var reads = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > MealieClient.MAX_IMPORT_DATA_BYTES) return null
            collected.write(buffer, 0, count)
            if (++reads % CANCEL_CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
        }
        return collected.toByteArray()
    }

    /** UTF-8 estricto: `null` si los bytes no son texto válido. */
    private fun decodeUtf8(bytes: ByteArray): String? =
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (ignored: CharacterCodingException) {
            null
        }

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (ignored: IOException) {
            // Nada que hacer: ya no se usa.
        } catch (ignored: RuntimeException) {
            // Algunos proveedores lanzan al cerrar; tampoco hay nada que hacer.
        }
    }

    private companion object {
        private const val BUFFER_BYTES = 8 * 1024

        /** Cada cuántas lecturas se comprueba la cancelación: 8 × 8 KiB = 64 KiB. */
        private const val CANCEL_CHECK_EVERY = 8

        /**
         * Reserva inicial: una receta en HTML ronda unos pocos KiB, y así un
         * archivo normal no hace crecer el búfer. Nunca se reserva el tope entero
         * delante: un archivo de 1 MiB todavía no está en memoria.
         */
        private const val INITIAL_CAPACITY = 64 * 1024
    }
}
