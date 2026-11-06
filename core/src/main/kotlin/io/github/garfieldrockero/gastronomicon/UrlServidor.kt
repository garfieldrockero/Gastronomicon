package io.github.garfieldrockero.gastronomicon

import java.net.URI
import java.net.URISyntaxException

/**
 * Validación y normalización de la URL del servidor Mealie que cada persona
 * pone en Ajustes.
 *
 * Esa URL es la base de todas las peticiones de la app y todas llevan el token
 * de API en la cabecera, así que aquí se fija la política de seguridad de la
 * dirección: solo HTTPS, y nada de usuario, contraseña, consulta o fragmento.
 * Es pura sintaxis: no se hace red ni se resuelve DNS.
 */
object UrlServidor {

    /** Único esquema admitido: el token viaja en cada petición y nunca en claro. */
    private const val ESQUEMA = "https"

    /**
     * Devuelve la URL base normalizada (esquema en minúsculas y sin barra final)
     * o lanza [IllegalArgumentException] con un mensaje en castellano pensado
     * para mostrarse tal cual en la pantalla de Ajustes.
     */
    fun normalizar(entrada: String): String {
        val limpia = entrada.trim()
        if (limpia.isEmpty()) {
            throw IllegalArgumentException("La URL del servidor está vacía.")
        }
        // Un espacio suelto casi siempre es un pegado torcido: merece su propio
        // mensaje en vez del genérico de «URL no válida» que daría el parser.
        if (limpia.any { it.isWhitespace() }) {
            throw IllegalArgumentException(
                "La URL no puede contener espacios, tabuladores ni saltos de línea.",
            )
        }

        val uri = try {
            URI(limpia)
        } catch (ignorada: URISyntaxException) {
            // Sin la entrada en el mensaje: puede llevar pegada una contraseña o un token.
            throw IllegalArgumentException("La URL no es válida. Revísala antes de guardar.")
        }

        // Primero el esquema, que es el error más común, y después lo demás.
        val esquema = uri.scheme
            ?: throw IllegalArgumentException(
                "Falta el esquema: la URL tiene que empezar por «https://».",
            )
        if (!esquema.equals(ESQUEMA, ignoreCase = true)) {
            throw IllegalArgumentException(mensajeEsquema(esquema))
        }

        // Un host nulo también cubre authorities que java.net.URI no ha sabido
        // partir en host[:puerto] (un puerto con letras, por ejemplo).
        val host = uri.host?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException(
                "Falta o no se entiende el nombre del servidor: la parte entre «https://» " +
                    "y la primera barra, con el puerto si lo lleva.",
            )
        if (uri.userInfo != null) {
            throw IllegalArgumentException(
                "La URL no puede llevar usuario ni contraseña: el token se configura aparte, en Ajustes.",
            )
        }
        // Una zona IPv6 (fe80::1%eth0) no tiene sentido para un servidor público y
        // añade ambigüedad al host: se rechaza.
        if ('%' in host) {
            throw IllegalArgumentException(
                "La URL no puede llevar un identificador de zona en el nombre del servidor.",
            )
        }
        if (uri.rawQuery != null) {
            throw IllegalArgumentException(
                "La URL no puede llevar consulta (lo que va después de «?»).",
            )
        }
        if (uri.rawFragment != null) {
            throw IllegalArgumentException(
                "La URL no puede llevar fragmento (lo que va después de «#»).",
            )
        }

        // Se reconstruye en lugar de recortar la cadena de entrada para garantizar
        // que el resultado vuelve a parsear bien. La ruta se toma cruda
        // (rawPath) porque «path» descodificaría los escapes y podría inventar
        // caracteres que no estaban en lo que escribió la persona.
        // getHost() devuelve las IPv6 con corchetes (los corchetes forman parte de
        // la authority). Se limpian y se vuelven a poner para que la reconstrucción
        // sea correcta tanto si el JDK los incluye como si no: sin ellos, el puerto
        // se confundiría con parte de la dirección.
        val hostLimpio = host.removePrefix("[").removeSuffix("]")
        val hostMostrado = if (':' in hostLimpio) "[$hostLimpio]" else hostLimpio
        val ruta = (uri.rawPath ?: "").trimEnd('/')
        val puerto = when {
            uri.port == -1 -> ""
            uri.port !in 1..65535 -> throw IllegalArgumentException(
                "El puerto de la URL no es válido: tiene que estar entre 1 y 65535.",
            )
            else -> ":${uri.port}"
        }
        return "$ESQUEMA://$hostMostrado$puerto$ruta"
    }

    /** Mensaje específico para http, que es el esquema equivocado más esperable. */
    private fun mensajeEsquema(esquema: String): String =
        if (esquema.equals("http", ignoreCase = true)) {
            "No se admite «http»: la app solo se conecta por HTTPS para que el token no viaje en claro."
        } else {
            "Esquema «$esquema» no admitido: la URL tiene que empezar por «https://»."
        }
}
