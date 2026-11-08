package io.github.garfieldrockero.gastronomicon.core.config

import io.github.garfieldrockero.gastronomicon.core.ServerUrl
import java.net.URI

/**
 * Origen de un servidor: esquema + host + puerto, con el puerto siempre
 * explícito (443 en https y 80 en http cuando la URL no lo escribe).
 *
 * Es la unidad que decide adónde puede viajar el token: la ruta (subcarpeta
 * tras un proxy) no cuenta, pero esquema, host o puerto distintos son otro
 * servidor. El host va en minúsculas y, en IPv6, entre corchetes, tal como lo
 * deja [ServerUrl.normalize].
 */
class ServerOrigin private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ServerOrigin && scheme == other.scheme && host == other.host && port == other.port)

    override fun hashCode(): Int = 31 * (31 * scheme.hashCode() + host.hashCode()) + port

    override fun toString(): String = "$scheme://$host:$port"

    companion object {
        /**
         * Origen de [url] (con o sin puerto, con o sin ruta), o null si no es
         * una URL válida. Admite http porque aquí solo se compara; quien
         * conecte vuelve a aplicar su política de red local.
         */
        fun of(url: String): ServerOrigin? {
            val canonical = try {
                ServerUrl.normalize(url, allowLocalHttp = true)
            } catch (ignored: IllegalArgumentException) {
                return null
            }
            val uri = try {
                URI(canonical)
            } catch (ignored: java.net.URISyntaxException) {
                return null
            }
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
            // URI.host conserva los corchetes de IPv6; se deja tal cual.
            return ServerOrigin(scheme, host, port)
        }
    }
}
