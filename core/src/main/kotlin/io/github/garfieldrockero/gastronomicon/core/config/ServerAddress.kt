package io.github.garfieldrockero.gastronomicon.core.config

import io.github.garfieldrockero.gastronomicon.core.ServerUrl
import java.net.URI
import java.net.URISyntaxException

/**
 * Construye la URL base del servidor a partir de los dos campos de la primera
 * pantalla: «servidor o IP» y «puerto».
 *
 * Toda la política de seguridad de la URL (HTTPS por defecto, HTTP solo hacia
 * la red local, sin usuario ni contraseña, forma canónica) sigue siendo la de
 * [ServerUrl]: aquí solo se
 * decide cómo se combinan los dos campos, que es cosa de la interfaz y no del
 * validador.
 */
object ServerAddress {

    /**
     * Solo cuenta como «trae esquema» si va seguido de «://». «host:8443» es
     * un servidor con puerto, no un esquema, porque es lo que una persona
     * quiere decir con eso en el campo servidor.
     */
    private val EXPLICIT_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /** Puerto por defecto de https y el que muestra el campo de puerto sin modo local. */
    const val DEFAULT_HTTPS_PORT = 443

    /** Puerto por defecto del esquema http: el que cede ante el puerto del servidor en modo local. */
    const val DEFAULT_HTTP_PORT = 80

    /**
     * Solo una sugerencia para rellenar el campo de puerto cuando se activa el
     * modo de red local: 9000 es el puerto con el que arranca Mealie sin proxy
     * delante. No es el puerto por defecto del esquema http (ese es el 80).
     *
     * [build] solo lo trata como «no tocado», nunca como un puerto que haya
     * elegido la persona: cede ante el puerto escrito en el servidor y, con
     * https explícito, ni siquiera se inserta (ver [build]).
     */
    const val DEFAULT_LOCAL_HTTP_PORT = 9000

    /** Valor con el que la interfaz rellena el campo de puerto según el modo. */
    fun defaultPortFor(allowLocalHttp: Boolean): Int =
        if (allowLocalHttp) DEFAULT_LOCAL_HTTP_PORT else DEFAULT_HTTPS_PORT

    /**
     * Devuelve la URL base ya normalizada (ver [ServerUrl.normalize]) o lanza
     * [IllegalArgumentException] con un mensaje en castellano listo para la
     * interfaz.
     *
     * [server] admite dominio, IPv4 o IPv6 entre corchetes, con o sin
     * esquema, con o sin barra final y con subruta. Sin esquema se usa
     * «http://» si [allowLocalHttp] es true y «https://» si es false; un
     * esquema explícito se respeta y pasa por [ServerUrl.normalize] con el
     * mismo [allowLocalHttp], que es quien decide si se admite. [port] es el
     * campo de puerto: null significa que la persona no lo ha tocado y manda
     * el puerto por defecto del esquema (443 en https, 80 en http).
     *
     * Si el campo servidor ya trae puerto propio, manda ese puerto cuando
     * [port] es null, el puerto por defecto del esquema elegido o el que
     * sugiere [defaultPortFor] (9000 en modo local): la pantalla
     * rellena el campo de puerto, así que ese valor no distingue «lo he
     * escrito» de «no lo he tocado», y quien escribe «host:8443» en el
     * servidor sin mirar el otro campo quiere el 8443. Solo se rechaza cuando
     * los dos puertos son distintos y el del campo tampoco es el por defecto:
     * ahí sí hay dos intenciones y no se adivina cuál de las dos quería decir.
     *
     * Con [allowLocalHttp] activo y un «https://» explícito en el servidor, un
     * [port] igual al sugerido ([DEFAULT_LOCAL_HTTP_PORT], 9000) cuenta como
     * null: el campo lo ha rellenado la pantalla al encender la opción, y el
     * 9000 es el puerto de Mealie sin proxy, que sirve http. Insertarlo daría
     * «https://192.168.1.50:9000», que casi seguro no contesta en TLS; quien
     * de verdad tenga https en el 9000 lo escribe en el propio servidor
     * («https://192.168.1.50:9000»). Sin la opción, el campo viene con 443 y
     * un 9000 sí lo ha escrito la persona, así que se respeta.
     */
    fun build(server: String, port: Int?, allowLocalHttp: Boolean = false): String {
        val trimmed = server.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Falta el servidor: escribe el dominio o la IP.")
        }
        if (port != null && port !in 1..65535) {
            throw IllegalArgumentException("El puerto tiene que estar entre 1 y 65535.")
        }

        val base = when {
            EXPLICIT_SCHEME.containsMatchIn(trimmed) -> trimmed
            allowLocalHttp -> "http://$trimmed"
            else -> "https://$trimmed"
        }

        // Se reparsea solo para ver si el campo servidor trae puerto propio; los
        // errores de forma se dejan a ServerUrl.normalize, que los explica mejor.
        val parsed = try {
            URI(base)
        } catch (ignored: URISyntaxException) {
            null
        }
        val embeddedPort = parsed?.port ?: -1
        // Un esquema que no sea http ni https lo rechazará ServerUrl; aquí da
        // igual qué puerto por defecto se le suponga.
        val schemeDefaultPort =
            if (parsed?.scheme.equals("http", ignoreCase = true)) DEFAULT_HTTP_PORT else DEFAULT_HTTPS_PORT

        // El 9000 sugerido es un puerto de http: en https se trata como «no
        // tocado» y manda el 443 (ver el KDoc).
        val fieldPort =
            if (
                allowLocalHttp && port == DEFAULT_LOCAL_HTTP_PORT &&
                parsed?.scheme.equals("https", ignoreCase = true)
            ) {
                null
            } else {
                port
            }

        // El valor con el que la interfaz rellena el campo (defaultPortFor) también
        // cede: en modo local el campo viene con 9000, y quien escribe
        // «192.168.1.50:8080» en el servidor no lo ha tocado.
        val fieldDefaultPort = defaultPortFor(allowLocalHttp)
        if (
            embeddedPort != -1 && fieldPort != null && fieldPort != schemeDefaultPort &&
            fieldPort != fieldDefaultPort && embeddedPort != fieldPort
        ) {
            throw IllegalArgumentException(
                "El campo del servidor ya lleva su propio puerto y no coincide con el del campo " +
                    "de puerto. Deja el puerto en un solo sitio.",
            )
        }

        val withPort = if (embeddedPort == -1 && fieldPort != null) insertPort(base, fieldPort) else base
        return ServerUrl.normalize(withPort, allowLocalHttp)
    }

    /**
     * Parte la URL guardada en lo que se escribe en los campos «Servidor» y
     * «Puerto» de la primera pantalla, con el puerto siempre explícito (443 en
     * https y 80 en http si la URL no lo lleva). Devuelve null si la URL no es
     * válida (la política es la de [ServerUrl], con http admitido porque aquí
     * solo se lee algo ya guardado).
     *
     * [SplitAddress.server] no lleva esquema; sí la subruta si la hay, y el
     * host IPv6 va entre corchetes. El esquema se devuelve aparte para que la
     * interfaz sepa si la sesión era http (ver [SplitAddress.toUrl]).
     */
    fun split(url: String): SplitAddress? {
        val canonical = try {
            ServerUrl.normalize(url, allowLocalHttp = true)
        } catch (ignored: IllegalArgumentException) {
            return null
        }
        val uri = try {
            URI(canonical)
        } catch (ignored: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port != -1) uri.port else if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
        return SplitAddress(scheme = scheme, server = host + (uri.rawPath ?: ""), port = port)
    }

    /**
     * Añade el puerto del campo «puerto» justo después del servidor y antes de
     * la barra. Se hace sobre la cadena y no recomponiendo la URI porque la
     * subruta hay que conservarla tal cual la escribió la persona.
     */
    private fun insertPort(base: String, port: Int): String {
        val schemeEnd = base.indexOf("://")
        // Si no hay «://», base empieza por «http://» o «https://», así que este -1 no
        // puede darse; por si acaso, no se toca nada.
        if (schemeEnd == -1) return base
        val rest = base.substring(schemeEnd + 3)
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        // El «:» final se quita para no duplicarlo si venía un puerto vacío
        // («host:»), que ya se ha visto que no cuenta como puerto propio.
        val authority = (if (cut == -1) rest else rest.substring(0, cut)).removeSuffix(":")
        val tail = if (cut == -1) "" else rest.substring(cut)
        return base.substring(0, schemeEnd + 3) + authority + ":" + port + tail
    }
}

/**
 * URL guardada partida en los dos campos de la pantalla.
 *
 * @property scheme «http» o «https».
 * @property server host (IPv6 entre corchetes) más la subruta si la hay, sin esquema ni puerto.
 * @property port siempre explícito, 1..65535.
 */
data class SplitAddress(val scheme: String, val server: String, val port: Int) {

    /** Si la URL guardada era http, el interruptor de red local tiene que estar encendido para editarla. */
    val isLocalHttp: Boolean get() = scheme == "http"

    /**
     * Vuelve a juntar los campos: `ServerAddress.split(x)!!.toUrl() == x` para
     * toda URL canónica. Usa [isLocalHttp] como modo, no el interruptor de la
     * pantalla, para que un https con el interruptor encendido no cambie de esquema.
     */
    fun toUrl(): String = ServerAddress.build(server, port, allowLocalHttp = isLocalHttp)
}
