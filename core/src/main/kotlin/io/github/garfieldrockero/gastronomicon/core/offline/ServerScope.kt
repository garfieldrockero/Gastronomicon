package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.ServerUrl
import io.github.garfieldrockero.gastronomicon.core.session.Session
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Identificador opaco del **servidor y la cuenta** a los que pertenece un dato
 * guardado.
 *
 * Es el SHA-256 de la URL canónica y del id de usuario, en hexadecimal, y nada
 * más: **no** contiene la URL, el nombre del host, el id de usuario ni el
 * token. Así dos servidores distintos no comparten recetas ni borradores aunque
 * usen el mismo slug, y dos cuentas del MISMO servidor tampoco: cambiar de
 * usuario en el mismo Mealie no enseña la caché ni los borradores de la
 * anterior.
 *
 * El id de usuario es el `id` de `GET /api/users/self` (lo lee
 * `MealieClient.currentUser()`), que no es secreto, pero también se hashea: el
 * nombre del fichero no debe decir qué cuenta lo escribió. El token no
 * participa nunca en el hash.
 *
 * Si no se conoce el id de usuario **no hay ámbito**: se falla cerrado y la
 * interfaz no ofrece nada sin conexión. Es preferible perder la caché a
 * mezclar dos cuentas.
 *
 * No es un secreto ni un cifrado: es un nombre de fichero. Lo que protege el
 * contenido es el cifrado del [EncryptedBlobStore].
 */
class ServerScope private constructor(val id: String) {

    override fun equals(other: Any?): Boolean =
        this === other || (other is ServerScope && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    // Sin la URL ni el hash: un ámbito puede acabar en un log o en un informe.
    override fun toString(): String = "ServerScope(***)"

    companion object {

        /** Separador de dominio: el mismo host en otra app no da el mismo hash. */
        private const val SALT = "gastronomicon/offline/2"

        /**
         * Forma admitida del id de usuario. Mealie usa un UUID, pero aquí no se
         * exige: basta con que sea un identificador corto y sin nada raro. Se
         * hashea igualmente, así que no hay inyección posible; se acota para
         * que un valor absurdo no llegue a construir claves.
         */
        private val USER_SHAPE = Regex("[A-Za-z0-9._:-]{1,128}")

        /**
         * Ámbito de [serverUrl] para la cuenta [userId], o null si la URL o el
         * id no son válidos.
         *
         * Normaliza con [ServerUrl] (HTTP local admitido: aquí solo se compara)
         * para que «https://Host/» y «https://host» den el mismo ámbito.
         *
         * [userId] tiene que ser el `id` ya saneado de `currentUser()`: se
         * compara tal cual, sin recortar ni cambiar mayúsculas, para que el
         * mismo dato dé siempre el mismo ámbito.
         */
        fun of(serverUrl: String, userId: String): ServerScope? {
            val canonical = try {
                ServerUrl.normalize(serverUrl, allowLocalHttp = true)
            } catch (ignored: IllegalArgumentException) {
                return null
            }
            if (!USER_SHAPE.matches(userId)) return null
            val digest = try {
                MessageDigest.getInstance("SHA-256")
            } catch (ignored: NoSuchAlgorithmException) {
                // SHA-256 es obligatorio en cualquier JVM/Android; si falta, se
                // falla cerrado en vez de usar un hash más débil.
                return null
            }
            val hash = Hashing.hex(
                digest.digest("$SALT\u0000$canonical\u0000$userId".toByteArray(Charsets.UTF_8)),
            )
            return ServerScope(hash)
        }

        /**
         * Atajo para el caso normal: la sesión ya guarda la URL canónica y la
         * interfaz ya ha leído el id con `MealieClient.currentUser()`.
         */
        fun of(session: Session, userId: String): ServerScope? = of(session.serverUrl, userId)
    }
}

/** Utilidades de hash compartidas por [ServerScope] y [BlobName]. */
internal object Hashing {

    private val HEX = "0123456789abcdef".toCharArray()

    /** SHA-256 en hexadecimal minúscula, o null si la JVM no lo ofrece. */
    fun sha256Hex(text: String): String? {
        val digest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (ignored: NoSuchAlgorithmException) {
            return null
        }
        return hex(digest.digest(text.toByteArray(Charsets.UTF_8)))
    }

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out[i++] = HEX[value ushr 4]
            out[i++] = HEX[value and 0x0F]
        }
        return String(out)
    }
}

/**
 * Construcción de claves del almacén.
 *
 * El identificador (slug de receta o destino del borrador) se vuelve a hashear:
 * así la clave tiene longitud fija y no depende de que el slug sea corto, y el
 * nombre del fichero no revela el título de la receta.
 */
internal object BlobName {

    /** 128 bits del hash bastan para no colisionar entre recetas de un servidor. */
    private const val HASH_CHARS = 32

    fun item(prefix: String, scope: ServerScope, itemId: String): String? {
        val hash = Hashing.sha256Hex(itemId) ?: return null
        return prefix + scope.id + "." + hash.substring(0, HASH_CHARS)
    }

    fun index(prefix: String, scope: ServerScope): String = prefix + scope.id
}
