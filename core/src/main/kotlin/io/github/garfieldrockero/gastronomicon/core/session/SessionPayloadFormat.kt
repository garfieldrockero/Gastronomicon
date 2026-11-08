package io.github.garfieldrockero.gastronomicon.core.session

import java.util.Base64

/**
 * Formato en claro y envoltorio del dato cifrado de la sesión. Es Kotlin puro
 * (sin Android) para poder probarlo con JUnit.
 *
 * Texto plano: `1\n<url>\n<token>`. La URL normalizada y el token (ASCII
 * imprimible sin espacios) no pueden contener saltos de línea, así que el
 * separador es inequívoco; el parseo es estricto y rechaza cualquier otra forma.
 *
 * Envoltorio guardado: `1:<iv base64>:<cifrado base64>`.
 *
 * Nada de esto incluye los valores en excepciones ni en `toString`.
 */
object SessionPayloadFormat {

    const val VERSION = "1"

    /** Etiqueta autenticada (AAD): ata el cifrado a este formato y versión. */
    val AAD: ByteArray = "gastronomicon.session.v1".toByteArray(Charsets.UTF_8)

    const val IV_LENGTH = 12

    /** Etiqueta de AES-GCM (128 bits): un cifrado de 16 bytes o menos no lleva ni un byte de datos. */
    const val TAG_LENGTH = 16

    /** Par URL/token en bruto; la validación de dominio la hace `Session`. */
    class Fields(val serverUrl: String, val token: String) {
        override fun toString(): String = "Fields(***)"
    }

    class Envelope(val iv: ByteArray, val cipherText: ByteArray) {
        override fun toString(): String = "Envelope(***)"
    }

    fun encodePlain(serverUrl: String, token: String): ByteArray {
        require('\n' !in serverUrl && '\n' !in token) { "Valor no serializable." }
        return "$VERSION\n$serverUrl\n$token".toByteArray(Charsets.UTF_8)
    }

    /** Devuelve null si el contenido no cumple el formato exacto. */
    fun parsePlain(bytes: ByteArray): Fields? {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (ignored: java.nio.charset.CharacterCodingException) {
            return null
        }
        val parts = text.split('\n')
        if (parts.size != 3 || parts[0] != VERSION) return null
        if (parts[1].isEmpty() || parts[2].isEmpty()) return null
        return Fields(parts[1], parts[2])
    }

    fun encodeEnvelope(iv: ByteArray, cipherText: ByteArray): String {
        val enc = Base64.getEncoder()
        return "$VERSION:${enc.encodeToString(iv)}:${enc.encodeToString(cipherText)}"
    }

    /** Devuelve null si falta algo, la versión no coincide o el IV no mide 12 bytes
     * o el cifrado no supera la etiqueta GCM. */
    fun parseEnvelope(stored: String?): Envelope? {
        if (stored == null) return null
        val parts = stored.split(':')
        if (parts.size != 3 || parts[0] != VERSION) return null
        return try {
            val dec = Base64.getDecoder()
            val iv = dec.decode(parts[1])
            val ct = dec.decode(parts[2])
            if (iv.size != IV_LENGTH || ct.size <= TAG_LENGTH) null else Envelope(iv, ct)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }
}
