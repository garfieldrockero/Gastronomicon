package io.github.garfieldrockero.gastronomicon.core

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Validación y normalización de la URL del servidor Mealie que cada persona
 * pone en la pantalla de configuración.
 *
 * Esa URL es la base de todas las peticiones de la app y todas llevan el token
 * de API en la cabecera, así que aquí se fija la política de seguridad de la
 * dirección: solo HTTPS, sin usuario, contraseña, consulta o fragmento, y con
 * una forma canónica (host en minúsculas, sin barra ni punto finales y sin el
 * puerto 443, que es el de por defecto).
 * Es pura sintaxis: no se hace red ni se resuelve DNS.
 */
object ServerUrl {

    /** Único esquema admitido: el token viaja en cada petición y nunca en claro. */
    private const val SCHEME = "https"

    /**
     * Devuelve la URL base normalizada o lanza [IllegalArgumentException] con un
     * mensaje en castellano pensado para mostrarse tal cual en la interfaz.
     */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("La URL del servidor está vacía.")
        }
        // Un espacio suelto casi siempre es un pegado torcido: merece su propio
        // mensaje en vez del genérico de «URL no válida» que daría el parser.
        if (trimmed.any { it.isWhitespace() }) {
            throw IllegalArgumentException(
                "La URL no puede contener espacios, tabuladores ni saltos de línea.",
            )
        }
        // Caracteres de control y de formato: el espacio de ancho cero y el que
        // invierte el sentido del texto sirven para falsear la URL que se ve.
        if (trimmed.any { isInvisible(it) }) {
            throw IllegalArgumentException(
                "La URL contiene caracteres invisibles o de control. Vuelve a escribirla.",
            )
        }

        val uri = try {
            URI(trimmed)
        } catch (ignored: URISyntaxException) {
            // Sin la entrada en el mensaje: puede llevar pegada una contraseña o un token.
            throw IllegalArgumentException("La URL no es válida. Revísala antes de guardar.")
        }

        // Primero el esquema, que es el error más común, y después lo demás.
        val scheme = uri.scheme
            ?: throw IllegalArgumentException(
                "Falta el esquema: la URL tiene que empezar por «https://».",
            )
        if (!scheme.equals(SCHEME, ignoreCase = true)) {
            throw IllegalArgumentException(
                "Esquema no admitido: la URL tiene que empezar por «https://».",
            )
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
                "La URL no puede llevar usuario ni contraseña: el token se configura aparte.",
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
        val cleanHost = host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        val hostWithoutDot = cleanHost.removeSuffix(".")
        if (
            hostWithoutDot.isEmpty() ||
            hostWithoutDot == "." ||
            hostWithoutDot.startsWith(".") ||
            hostWithoutDot.endsWith(".") ||
            hostWithoutDot.contains("..")
        ) {
            throw IllegalArgumentException("El nombre del servidor no es válido.")
        }
        val displayHost = if (':' in hostWithoutDot) {
            validateIpv6(hostWithoutDot)
            "[$hostWithoutDot]"
        } else {
            validateHost(hostWithoutDot)
            validateIpv4IfNeeded(hostWithoutDot)
            hostWithoutDot
        }

        val path = normalizePath(uri.rawPath ?: "")
        val port = when {
            // 443 es el puerto por defecto de HTTPS: la forma canónica no lo lleva.
            uri.port == -1 || uri.port == 443 -> ""
            uri.port !in 1..65535 -> throw IllegalArgumentException(
                "El puerto de la URL no es válido: tiene que estar entre 1 y 65535.",
            )
            else -> ":${uri.port}"
        }
        val output = "$SCHEME://$displayHost$port$path"
        // La salida debe ser ASCII imprimible: así no hay caracteres que puedan
        // cambiar de aspecto ni escapes ambiguos en lo que se muestra.
        if (output.any { it.code !in 0x21..0x7E }) {
            throw IllegalArgumentException("La URL contiene caracteres no admitidos.")
        }
        return output
    }

    /** Caracteres de control, formato (incluye BOM y U+202E), uso privado o sin asignar. */
    private fun isInvisible(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.PRIVATE_USE.toInt() ||
            type == Character.SURROGATE.toInt() ||
            type == Character.UNASSIGNED.toInt()
    }

    /** Etiquetas DNS: letras, dígitos y guion, sin guion en los extremos. */
    private fun validateHost(host: String) {
        if (host.length > 253) {
            throw IllegalArgumentException("El nombre del servidor es demasiado largo.")
        }
        for (label in host.split('.')) {
            if (label.isEmpty() || label.length > 63) {
                throw IllegalArgumentException("El nombre del servidor no es válido.")
            }
            if (label.first() == '-' || label.last() == '-') {
                throw IllegalArgumentException("El nombre del servidor no es válido.")
            }
            if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) {
                throw IllegalArgumentException(
                    "El nombre del servidor solo admite letras sin tildes, números, guiones y puntos.",
                )
            }
        }
    }

    /**
     * Una última etiqueta numérica se interpreta como IPv4. Se exige la forma
     * decimal normal para que no se cuele una dirección distinta de la escrita.
     */
    private fun validateIpv4IfNeeded(host: String) {
        val labels = host.split('.')
        val last = labels.last()
        if (last.isEmpty() || !last.all { it in '0'..'9' }) return
        if (labels.size != 4) {
            throw IllegalArgumentException("La dirección IPv4 no es válida.")
        }
        for (label in labels) {
            if (label.isEmpty() || label.length > 3) {
                throw IllegalArgumentException("La dirección IPv4 no es válida.")
            }
            if (label.length > 1 && label.startsWith('0')) {
                throw IllegalArgumentException("La dirección IPv4 no es válida.")
            }
            val value = label.toIntOrNull()
                ?: throw IllegalArgumentException("La dirección IPv4 no es válida.")
            if (value !in 0..255) {
                throw IllegalArgumentException("La dirección IPv4 no es válida.")
            }
        }
    }

    /** Comprobación básica de la IPv6 ya troceada por [URI]; sin zonas. */
    private fun validateIpv6(host: String) {
        if (!host.matches(Regex("[0-9a-f:.]+"))) {
            throw IllegalArgumentException("La dirección IPv6 no es válida.")
        }
    }

    /**
     * Normaliza la ruta: sin barra final, sin segmentos vacíos internos y sin
     * segmentos «.» o «..» (también escapados). Solo ASCII imprimible.
     */
    private fun normalizePath(raw: String): String {
        val withoutTrailingSlashes = raw.trimEnd('/')
        if (withoutTrailingSlashes.isEmpty()) return ""
        if (withoutTrailingSlashes.any { it.code !in 0x21..0x7E }) {
            throw IllegalArgumentException(
                "La ruta de la URL solo admite ASCII; escribe los caracteres especiales con percent-encoding.",
            )
        }
        val body = withoutTrailingSlashes.removePrefix("/")
        val segments = body.split('/')
        for (segment in segments) {
            if (segment.isEmpty()) {
                throw IllegalArgumentException("La ruta de la URL no puede tener segmentos vacíos.")
            }
            if (segment == "." || segment == "..") {
                throw IllegalArgumentException("La ruta de la URL no puede llevar «.» ni «..».")
            }
            if (segment.contains("%2e", ignoreCase = true) || segment.contains("%2f", ignoreCase = true)) {
                throw IllegalArgumentException("La ruta de la URL no puede llevar «.» ni «/» escapados.")
            }
        }
        // Escapes de control o de barra: se rechazan antes de que lleguen a la red.
        Regex("%([0-9A-Fa-f]{2})").findAll(withoutTrailingSlashes).forEach { match ->
            val value = match.groupValues[1].toInt(16)
            if (value <= 0x1F || value == 0x7F || value == 0x2F) {
                throw IllegalArgumentException("La ruta de la URL lleva un escape no admitido.")
            }
        }
        return "/" + segments.joinToString("/")
    }
}
