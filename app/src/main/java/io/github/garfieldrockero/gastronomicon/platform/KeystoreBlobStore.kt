package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.garfieldrockero.gastronomicon.core.offline.BlobHeader
import io.github.garfieldrockero.gastronomicon.core.offline.BlobKeys
import io.github.garfieldrockero.gastronomicon.core.offline.EncryptedBlobStore
import io.github.garfieldrockero.gastronomicon.core.offline.StorageProblem
import io.github.garfieldrockero.gastronomicon.core.offline.StoreResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Almacén cifrado de los datos offline (caché de recetas y borradores) con una
 * clave AES-256 del Android Keystore no exportable. Cumple el contrato de
 * [EncryptedBlobStore] para `core/offline`.
 *
 * - **Un fichero por clave** en `filesDir/offline/` (directorio privado; nunca la
 *   caché, que el sistema puede vaciar). Fuera de copias de seguridad: el
 *   manifiesto lleva `allowBackup="false"` y `extraccion_datos.xml` excluye
 *   todos los dominios, también `file`.
 * - **El nombre del fichero es el SHA-256 de la clave**, no la clave. La
 *   cabecera del fichero guarda la clave en claro para poder enumerar claves y
 *   borrarlas sin descifrar nada (el cierre de sesión tiene que funcionar con el
 *   Keystore roto o la clave perdida). La clave es un identificador opaco
 *   —prefijo y hashes— y, como dice [BlobKeys], no es un secreto: lo que protege
 *   el contenido es el cifrado del cuerpo. El formato de la cabecera vive en
 *   [BlobHeader].
 * - **La clave del blob es el AAD de AES-GCM** (MUST del contrato): un cuerpo
 *   copiado a otra entrada no descifra.
 * - **IV nuevo y aleatorio en cada escritura**, de 12 bytes (lo que exige
 *   AES-GCM en el Keystore), guardado junto al cuerpo.
 * - **Escritura atómica**: fichero temporal en el mismo directorio, `sync()` y
 *   `rename`. Un corte a media escritura no deja una entrada con el nombre
 *   definitivo.
 * - **Fallos tipados, sin excepciones y sin logs.** Keystore ocupado o E/S es
 *   [StorageProblem.Unavailable] y no borra nada; unos bytes que no descifran o
 *   una cabecera rota son [StorageProblem.Corrupt] y descartan **solo esa
 *   entrada**; la clave se tira únicamente al cerrar sesión ([clearAll]) o si el
 *   Keystore dice que está invalidada, y entonces se vacía todo lo offline.
 * - **Bloqueante y sincronizado**, como `SessionStore`: varios objetos pueden
 *   compartir el almacén ([RecipeCache] y [DraftStore] lo hacen) y cada
 *   operación es atómica.
 *
 * [clearAll] es el borrado de cierre de sesión: vacía el directorio y borra la
 * clave sin descifrar ni una entrada. Si no puede (disco, Keystore ocupado) deja
 * una marca de borrado pendiente, sin datos, que [retryPendingClear] reintenta al
 * arrancar: un fallo no se queda como éxito silencioso.
 */
class KeystoreBlobStore(context: Context) : EncryptedBlobStore {

    // applicationContext para no retener una Activity.
    private val appContext: Context = context.applicationContext

    private val directory: File = File(appContext.filesDir, DIRECTORY_NAME)

    /**
     * Solo la marca de borrado pendiente (un booleano): nada del contenido ni de
     * la sesión. Archivo privado y fuera de copias de seguridad como todo.
     */
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(key: String): StoreResult<ByteArray?> = synchronized(LOCK) {
        if (!BlobKeys.isValid(key)) return StoreResult.Failed(StorageProblem.Rejected)
        val file = fileFor(key) ?: return StoreResult.Failed(StorageProblem.Rejected)
        if (!file.isFile) return StoreResult.Ok<ByteArray?>(null)
        if (file.length() > MAX_FILE_BYTES) return StoreResult.Failed(StorageProblem.TooLarge)

        val record = try {
            readRecord(file)
        } catch (ignored: IOException) {
            // Un fallo de E/S no dice que el contenido esté roto: no se borra.
            return StoreResult.Failed(StorageProblem.Unavailable)
        }
        if (record == null) return discard(file, StorageProblem.Corrupt)

        // El fichero tiene que ser el de ESTA clave: si la de dentro es otra, alguien
        // lo ha movido (o quedó a medias) y no se sirve como si fuera esta entrada.
        if (record.key != key) return discard(file, StorageProblem.Corrupt)

        val secret = try {
            existingKey()
        } catch (e: Exception) {
            if (isKeyDead(e)) return wipeAfterDeadKey()
            return StoreResult.Failed(StorageProblem.Unavailable)
        }
        // Sin clave, el cuerpo no se puede abrir: no hay forma de recuperarlo.
        if (secret == null) return discard(file, StorageProblem.Corrupt)

        val plain = try {
            openWith(key, record.iv, record.cipherText, secret)
        } catch (e: Exception) {
            if (isKeyDead(e)) {
                // La clave ya no sirve para nada: lo guardado con ella es irrecuperable,
                // así que se vacía todo y la próxima escritura empieza de cero.
                return wipeAfterDeadKey()
            }
            if (isBlobCorrupt(e)) {
                // El tag que no cuadra es cosa de ESTE fichero (un bit cambiado, un corte
                // del disco). Se descarta solo él: la clave sigue sirviendo para lo demás
                // y los borradores son trabajo de la persona.
                return discard(file, StorageProblem.Corrupt)
            }
            // Keystore ocupado, dispositivo bloqueado…: se reintenta más tarde.
            return StoreResult.Failed(StorageProblem.Unavailable)
        }
        StoreResult.Ok<ByteArray?>(plain)
    }

    override fun put(key: String, value: ByteArray): StoreResult<Unit> = synchronized(LOCK) {
        if (!BlobKeys.isValid(key)) return StoreResult.Failed(StorageProblem.Rejected)
        if (value.size > MAX_VALUE_BYTES) return StoreResult.Failed(StorageProblem.TooLarge)
        val target = fileFor(key) ?: return StoreResult.Failed(StorageProblem.Rejected)
        if (!ensureDirectory()) return StoreResult.Failed(StorageProblem.Unavailable)

        val sealedValue = seal(key, value) ?: return StoreResult.Failed(StorageProblem.Unavailable)
        // Si el Keystore diera un IV de otra longitud, no se escribe: no se podría leer.
        val record = BlobHeader.encode(key, sealedValue.iv, sealedValue.cipherText)
            ?: return StoreResult.Failed(StorageProblem.Unavailable)
        if (!writeAtomically(target, record)) return StoreResult.Failed(StorageProblem.Unavailable)
        StoreResult.Ok(Unit)
    }

    override fun delete(key: String): StoreResult<Unit> = synchronized(LOCK) {
        if (!BlobKeys.isValid(key)) return StoreResult.Failed(StorageProblem.Rejected)
        val file = fileFor(key) ?: return StoreResult.Failed(StorageProblem.Rejected)
        if (!file.exists()) return StoreResult.Ok(Unit)
        // `delete()` devuelve false también si ya no está: solo es un problema si sigue ahí.
        if (file.delete() || !file.exists()) return StoreResult.Ok(Unit)
        StoreResult.Failed(StorageProblem.Unavailable)
    }

    override fun listKeys(prefix: String): StoreResult<List<String>> = synchronized(LOCK) {
        if (!BlobKeys.isValidPrefix(prefix)) return StoreResult.Failed(StorageProblem.Rejected)
        if (!directory.isDirectory) return StoreResult.Ok<List<String>>(emptyList())

        val listed = try {
            directory.listFiles()
        } catch (ignored: SecurityException) {
            null
        }
        if (listed == null) return StoreResult.Failed(StorageProblem.Unavailable)
        val files = listed

        val keys = ArrayList<String>(minOf(files.size, LIST_LIMIT))
        for (file in files) {
            if (keys.size >= LIST_LIMIT) break
            if (!file.isFile) continue
            val key = readHeaderKey(file) ?: continue
            // Solo se anuncia una clave si el fichero es el suyo: así no se listan
            // temporales, restos ni ficheros con la cabecera cambiada.
            if (nameOf(key) != file.name) continue
            if (!key.startsWith(prefix)) continue
            keys += key
        }
        keys.sort()
        StoreResult.Ok<List<String>>(keys)
    }

    /**
     * Borra todo lo guardado y la clave del Keystore, **sin descifrar nada**: vale
     * con la clave perdida o el Keystore roto. Es lo que se llama al cambiar de
     * sesión y al borrarla.
     *
     * Devuelve [StoreResult.Ok] solo si el directorio queda vacío y el alias ya no
     * existe. Si no, deja la marca de borrado pendiente (que [retryPendingClear]
     * reintenta al arrancar) y devuelve [StoreResult.Failed] con el problema:
     * repetirlo es inocuo.
     */
    fun clearAll(): StoreResult<Unit> = synchronized(LOCK) {
        val problem = wipeEverything()
        if (problem == null) StoreResult.Ok(Unit) else StoreResult.Failed(problem)
    }

    /**
     * Reintenta un borrado que se quedó a medias (disco, Keystore ocupado).
     *
     * Devuelve `null` si no había nada pendiente: en un arranque normal no toca ni
     * el disco ni el Keystore. Se llama al montar el contenedor.
     */
    fun retryPendingClear(): StoreResult<Unit>? = synchronized(LOCK) {
        if (isPendingWipe()) clearAll() else null
    }

    // Sin la ruta ni nada del contenido: esto puede acabar en un informe.
    override fun toString(): String = "KeystoreBlobStore"

    // ------------------------------------------------------------- ficheros

    /** Fichero de [key]: el SHA-256 de la clave como nombre. Null si falta SHA-256. */
    private fun fileFor(key: String): File? {
        val name = nameOf(key) ?: return null
        return File(directory, name)
    }

    private fun nameOf(key: String): String? = try {
        hex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))
    } catch (ignored: NoSuchAlgorithmException) {
        null
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(digits[value ushr 4]).append(digits[value and 0x0F])
        }
        return out.toString()
    }

    private fun ensureDirectory(): Boolean = directory.isDirectory || directory.mkdirs()

    /**
     * Escribe [bytes] en [target] pasando por un temporal del mismo directorio. El
     * `rename` dentro del mismo sistema de ficheros es atómico: o está el fichero
     * viejo entero o el nuevo entero.
     */
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val temp = try {
            File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
        } catch (ignored: IOException) {
            return false
        }
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                // Que los bytes estén en disco antes del rename; si no, un corte deja
                // un fichero con el nombre bueno y el contenido a medias.
                out.fd.sync()
            }
        } catch (ignored: IOException) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        return true
    }

    /** Descarta el fichero (mejor esfuerzo) y devuelve el fallo tipado. */
    private fun discard(file: File, problem: StorageProblem): StoreResult<ByteArray?> {
        // Si no se puede borrar, se queda y volverá a fallar al leerlo; el núcleo lo
        // reintenta y, al cerrar la sesión, `clearAll` lo barre igual.
        file.delete()
        return StoreResult.Failed(problem)
    }

    /** Borra todos los ficheros del directorio, también los temporales. Sin descifrar. */
    private fun deleteFiles(): Boolean {
        if (!directory.isDirectory) return true
        val listed = try {
            directory.listFiles()
        } catch (ignored: SecurityException) {
            null
        }
        if (listed == null) return false
        var allGone = true
        for (file in listed) {
            if (!file.delete() && file.exists()) allGone = false
        }
        return allGone
    }

    // ------------------------------------------------------------- borrado total

    /**
     * Marca, vacía y desmarca. La marca se escribe **antes** de tocar los ficheros:
     * si el proceso muere a media limpieza, el arranque siguiente la repite en vez
     * de dar el borrado por hecho. Devuelve el problema o `null` si quedó vacío.
     */
    private fun wipeEverything(): StorageProblem? {
        markPendingWipe()
        val filesGone = deleteFiles()
        val keyGone = deleteKey()
        if (!filesGone || !keyGone) return StorageProblem.Unavailable
        // Si quitar la marca falla, el arranque repite un borrado ya inocuo.
        removePendingWipe()
        return null
    }

    /**
     * La clave ha dejado de servir: se vacía lo offline (ya no descifra nada) y se
     * devuelve el fallo tipado, para que el núcleo descarte la entrada.
     */
    private fun wipeAfterDeadKey(): StoreResult<ByteArray?> {
        wipeEverything()
        return StoreResult.Failed(StorageProblem.Corrupt)
    }

    /**
     * `commit()` y no `apply()`: la marca tiene que estar en disco antes de borrar
     * los ficheros, o un corte dejaría un borrado a medias sin rastro. Si el
     * `commit()` falla, el borrado se intenta igual (mejor esfuerzo).
     */
    private fun markPendingWipe() {
        try {
            prefs.edit().putBoolean(KEY_PENDING_WIPE, true).commit()
        } catch (ignored: Exception) {
            // Sin marca no habrá reintento al arrancar; no hay nada más que hacer.
        }
    }

    private fun removePendingWipe() {
        try {
            prefs.edit().remove(KEY_PENDING_WIPE).commit()
        } catch (ignored: Exception) {
            // Se queda marcado: el arranque repetirá un borrado que ya no tiene trabajo.
        }
    }

    private fun isPendingWipe(): Boolean = try {
        prefs.getBoolean(KEY_PENDING_WIPE, false)
    } catch (ignored: Exception) {
        // Un valor de otro tipo (archivo manipulado) no es una marca: se sigue normal.
        false
    }

    // ------------------------------------------------------------- formato

    /** Fichero entero ya separado en sus partes. */
    private class Record(val key: String, val iv: ByteArray, val cipherText: ByteArray)

    private fun parseRecord(bytes: ByteArray): Record? {
        val header = BlobHeader.parse(bytes, bytes.size) ?: return null
        val bodySize = bytes.size - header.bodyOffset
        // Al menos la etiqueta GCM y como mucho el tope del valor.
        if (bodySize < TAG_BYTES || bodySize - TAG_BYTES > MAX_VALUE_BYTES) return null
        return Record(header.key, header.iv, bytes.copyOfRange(header.bodyOffset, bytes.size))
    }

    /** Lee un fichero entero y lo parte. Null si la cabecera o el tamaño no cuadran. */
    private fun readRecord(file: File): Record? {
        val size = file.length()
        if (size <= 0L || size > MAX_FILE_BYTES) return null
        val bytes = ByteArray(size.toInt())
        FileInputStream(file).use { input ->
            if (readUpTo(input, bytes) != bytes.size) return null
        }
        return parseRecord(bytes)
    }

    /** Clave guardada en la cabecera, sin abrir el cuerpo. Null si no se puede leer. */
    private fun readHeaderKey(file: File): String? {
        val buffer = ByteArray(BlobHeader.MAX_HEADER_BYTES)
        val read = try {
            FileInputStream(file).use { input -> readUpTo(input, buffer) }
        } catch (ignored: IOException) {
            return null
        }
        return BlobHeader.parse(buffer, read)?.key
    }

    /** Llena [buffer] hasta el final del fichero; devuelve los bytes leídos. */
    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    // ------------------------------------------------------------- cifrado

    /** IV nuevo y cuerpo cifrado listos para escribir. */
    private class Sealed(val iv: ByteArray, val cipherText: ByteArray)

    /** Cifra [plain] con la clave del blob como AAD. Null si el Keystore no deja ahora. */
    private fun seal(key: String, plain: ByteArray): Sealed? {
        val first = try {
            sealWith(key, plain, existingKey() ?: createKey())
        } catch (e: Exception) {
            if (!isKeyDead(e)) return null
            null
        }
        if (first != null) return first
        // La clave está invalidada o es irrecuperable: lo guardado con ella ya no
        // descifra, así que se vacía todo y se cifra con una clave nueva.
        wipeEverything()
        return try {
            sealWith(key, plain, createKey())
        } catch (ignored: Exception) {
            null
        }
    }

    private fun sealWith(key: String, plain: ByteArray, secret: SecretKey): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Sin IV propio: el Cipher genera uno nuevo en cada init (el Keystore no admite fijarlo).
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        cipher.updateAAD(aadOf(key))
        val cipherText = cipher.doFinal(plain)
        return Sealed(cipher.iv, cipherText)
    }

    private fun openWith(key: String, iv: ByteArray, cipherText: ByteArray, secret: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aadOf(key))
        return cipher.doFinal(cipherText)
    }

    /** La clave del blob, tal cual, como dato asociado de AES-GCM (MUST del contrato). */
    private fun aadOf(key: String): ByteArray = key.toByteArray(Charsets.UTF_8)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun createKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true)
            } catch (e: Exception) {
                // Sin StrongBox se cae a la clave normal (TEE/hardware); otro fallo se propaga.
                if (!causedBy<StrongBoxUnavailableException>(e)) throw e
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    /**
     * El cuerpo de ESTA entrada no descifra: un bit cambiado, un corte del disco o
     * un fichero ajeno. Se descarta el fichero y la clave se queda: los demás
     * blobs (y los borradores) no tienen por qué estar mal.
     */
    private fun isBlobCorrupt(e: Throwable): Boolean = causedBy<AEADBadTagException>(e)

    /**
     * La clave en sí ha dejado de servir: restauración de una copia, cambio del
     * bloqueo de pantalla, clave irrecuperable de verdad. Es lo único que
     * justifica tirar la clave, y con ella todo lo offline.
     */
    private fun isKeyDead(e: Throwable): Boolean =
        causedBy<KeyPermanentlyInvalidatedException>(e) ||
            (causedBy<UnrecoverableKeyException>(e) && !hasTransientKeystoreFailure(e))

    /**
     * Desde API 33 el Keystore indica si un fallo es transitorio. Antes no hay forma
     * de saberlo y UnrecoverableKeyException se sigue tratando como permanente.
     */
    private fun hasTransientKeystoreFailure(e: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            if (current is android.security.KeyStoreException && current.isTransientFailure) return true
            current = current.cause
            depth++
        }
        return false
    }

    private inline fun <reified T : Throwable> causedBy(e: Throwable): Boolean {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            if (current is T) return true
            current = current.cause
            depth++
        }
        return false
    }

    /** Borra la clave; true solo si, tras borrar, el alias ya no existe. */
    private fun deleteKey(): Boolean = try {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        !ks.containsAlias(KEY_ALIAS)
    } catch (ignored: Exception) {
        // Si el Keystore no responde no se puede confirmar el borrado.
        false
    }

    private companion object {
        /** Compartido por todas las instancias: crear/borrar la clave y escribir no se pisan. */
        val LOCK = Any()

        const val DIRECTORY_NAME = "offline"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gastronomicon.offline.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_BYTES = 16

        /** Preferencias privadas de la marca de borrado pendiente: solo un booleano. */
        const val PREFS_NAME = "gastronomicon_offline"
        const val KEY_PENDING_WIPE = "borrado_pendiente"

        /** Tope del valor en claro: 1 MiB (el núcleo no pasa de 512 KiB por entrada). */
        const val MAX_VALUE_BYTES = 1048576

        /** Tope del fichero: 1 MiB más la cabecera, el IV y la etiqueta GCM. */
        const val MAX_FILE_BYTES = 1049088

        /** Tope de claves por listado, como permite el contrato. */
        const val LIST_LIMIT = 4096

        const val TEMP_PREFIX = "tmp-"
        const val TEMP_SUFFIX = ".part"
    }
}
