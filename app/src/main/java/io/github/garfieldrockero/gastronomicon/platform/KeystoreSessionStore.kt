package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import io.github.garfieldrockero.gastronomicon.core.session.Session
import io.github.garfieldrockero.gastronomicon.core.session.SessionPayloadFormat
import io.github.garfieldrockero.gastronomicon.core.session.SessionStore
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Fallo al leer o guardar. Mensaje fijo y sin causa: nunca puede arrastrar URL ni token. */
class SessionStorageException : Exception("No se pudo acceder a la sesión de forma segura.")

/**
 * Guarda la sesión cifrada con una clave AES-256 del Android Keystore (no
 * exportable) en SharedPreferences privadas. Sin dependencias nuevas y sin Log:
 * ningún mensaje de este archivo puede contener datos de la sesión.
 *
 * Política ante lo corrupto: solo si el fallo es permanente (tag GCM inválido,
 * clave invalidada o irrecuperable, clave ausente, formato raro, URL/token no
 * válidos) se borran datos y clave y se devuelve null, de modo que la persona
 * vuelve a la pantalla inicial. Un fallo transitorio del Keystore (servicio
 * ocupado, dispositivo bloqueado...) NO borra nada: [load] lanza
 * [SessionStorageException] y se podrá reintentar.
 */
class KeystoreSessionStore(context: Context) : SessionStore {

    // Contexto de aplicación para no retener una Activity.
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Session? = synchronized(LOCK) {
        val stored = prefs.getString(KEY_BLOB, null) ?: return null
        try {
            val envelope = SessionPayloadFormat.parseEnvelope(stored) ?: return failAndWipe()
            val key = existingKey() ?: return failAndWipe()
            val plain = decrypt(key, envelope)
            try {
                val fields = SessionPayloadFormat.parsePlain(plain) ?: return failAndWipe()
                Session(fields.serverUrl, ApiToken(fields.token))
            } catch (e: IllegalArgumentException) {
                // Solo el parseo: Session/ApiToken rechazan el contenido, permanente.
                // Una IAE de decrypt/existingKey cae abajo y no borra la sesión.
                return failAndWipe()
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            if (isPermanentKeyFailure(e)) failAndWipe() else throw SessionStorageException()
        }
    }

    override fun save(session: Session) {
        synchronized(LOCK) {
            val plain = SessionPayloadFormat.encodePlain(session.serverUrl, session.token.value)
            try {
                val value = try {
                    encryptToEnvelope(plain)
                } catch (e: Exception) {
                    // Solo una clave invalidada se regenera; un fallo transitorio no toca la clave.
                    if (!isPermanentKeyFailure(e)) throw SessionStorageException()
                    try {
                        deleteKey()
                        encryptToEnvelope(plain)
                    } catch (ignoredAgain: Exception) {
                        throw SessionStorageException()
                    }
                }
                // commit() y no apply(): hay que saber si se escribió antes de dar la
                // sesión por guardada, y las lecturas siguientes deben verla ya.
                // Una sola clave, así que la escritura es atómica.
                if (!prefs.edit().putString(KEY_BLOB, value).commit()) throw SessionStorageException()
            } finally {
                plain.fill(0)
            }
        }
    }

    override fun clear() {
        synchronized(LOCK) { wipe() }
    }

    override fun toString(): String = "KeystoreSessionStore"

    private fun failAndWipe(): Session? {
        wipe()
        return null
    }

    private fun wipe() {
        try {
            prefs.edit().remove(KEY_BLOB).commit()
        } catch (ignored: Exception) {
            // Se sigue intentando borrar la clave.
        }
        deleteKey()
    }

    private fun encryptToEnvelope(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Sin IV propio: el Cipher genera uno nuevo en cada init (Keystore no admite fijarlo).
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(SessionPayloadFormat.AAD)
        val cipherText = cipher.doFinal(plain)
        return SessionPayloadFormat.encodeEnvelope(cipher.iv, cipherText)
    }

    private fun decrypt(key: SecretKey, envelope: SessionPayloadFormat.Envelope): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv))
        cipher.updateAAD(SessionPayloadFormat.AAD)
        return cipher.doFinal(envelope.cipherText)
    }

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

    /** Fallos que no se arreglan reintentando: la clave o el contenido ya no sirven. */
    private fun isPermanentKeyFailure(e: Throwable): Boolean =
        causedBy<AEADBadTagException>(e) ||
            causedBy<KeyPermanentlyInvalidatedException>(e) ||
            (causedBy<UnrecoverableKeyException>(e) && !hasTransientKeystoreFailure(e))

    /**
     * Desde API 33 el Keystore indica si un fallo es transitorio. Antes no hay
     * forma de saberlo y UnrecoverableKeyException se sigue tratando como permanente.
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

    private fun deleteKey() {
        try {
            val ks = keyStore()
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (ignored: Exception) {
            // Si el Keystore no responde no hay más que hacer; los datos ya no están.
        }
    }

    private companion object {
        /** Compartido por todas las instancias: crear/borrar la clave no se pisa. */
        val LOCK = Any()

        const val PREFS_NAME = "gastronomicon_session"
        const val KEY_BLOB = "session"
        const val KEY_ALIAS = "gastronomicon.session.key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
