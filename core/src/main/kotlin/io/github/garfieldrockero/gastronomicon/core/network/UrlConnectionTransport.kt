package io.github.garfieldrockero.gastronomicon.core.network

import io.github.garfieldrockero.gastronomicon.core.ServerUrl
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Transporte sobre el «HttpURLConnection» que traen el JDK y Android: cero
 * dependencias nuevas y el mismo código en pruebas y en producción.
 *
 * Seguridad:
 * - Las redirecciones se siguen a mano porque HttpURLConnection reenviaría la
 *   cabecera «Authorization» puesta a mano a donde hiciera falta (otro host u
 *   otro esquema incluidos). Aquí solo se sigue si el destino tiene el mismo
 *   esquema, host, puerto y, además, queda dentro de la ruta de la URL base
 *   (con la base en la raíz, cualquier ruta del mismo origen): Mealie puede
 *   desplegarse en una subruta como «/mealie» y no hay razón para que el token
 *   visite el resto del dominio. Si no se cumple, se rechaza antes de abrir la
 *   segunda conexión y al destino no le llega nada.
 * - Un 301 o un 302 sobre una escritura (POST, PUT, DELETE) no se sigue: casi
 *   todos los clientes lo convierten en GET sin cuerpo, y una escritura que se
 *   queda a medias sin avisar es peor que un error visible. Solo 307 y 308
 *   conservan método y cuerpo; 303 es el «ya está, mira aquí» y se sigue como
 *   GET sin cuerpo. En GET se siguen todas.
 * - El token solo puede viajar hacia el origen de la URL base: la primera
 *   petición sale de ella y cada salto se valida contra ella.
 * - No hay reintentos: una escritura repetida a ciegas es peor que un error
 *   visible.
 * - No se toca HostnameVerifier ni SSLSocketFactory: la confianza es la del
 *   sistema y el certificado del servidor es público (Let's Encrypt).
 * - Con una base http (solo posible hacia una IP privada, ver ServerUrl), antes
 *   de cada salto se pregunta a [ActiveNetworkProvider] por qué red sale el
 *   móvil y, si no es una de las que pueden ser la de casa (ver
 *   [allowsLocalHttp]), se corta con
 *   [NetworkError.LocalHttpNotAllowedOnThisNetwork] sin abrir conexión. Se
 *   mira en cada salto y no solo al empezar porque el móvil puede pasar de la
 *   wifi a los datos en mitad de una cadena de redirecciones. Con base https
 *   no se pregunta: el token ya va cifrado por cualquier red.
 * - Hay un plazo total por petición además de los tiempos de espera de
 *   conexión y de lectura: un servidor que responde a cuentagotas agota cada
 *   lectura sin llegar nunca a agotar la lectura anterior. Al empezar cada
 *   salto se mira lo que queda del plazo y se recorta con él el tiempo de
 *   conexión y el de lectura, sin hilos ni temporizadores.
 *
 * Límites del plazo total (asumidos a sabiendas, porque cortarlo de verdad
 * pediría un hilo vigilante que cierre la conexión desde fuera):
 * - Se comprueba al empezar cada salto y entre lecturas del cuerpo, no
 *   durante una lectura ya empezada: una lectura que arranca justo antes de
 *   que venza puede esperar su tiempo de lectura entero. El peor caso es el
 *   plazo total más un tiempo de lectura (con los valores por defecto,
 *   60 s + 30 s).
 * - La resolución DNS no la limita HttpURLConnection: ni el tiempo de
 *   conexión ni el de lectura la cubren, así que un resolvedor lento puede
 *   pasarse del plazo antes de que este código vuelva a mirarlo.
 *
 * Conexiones: una respuesta 2xx leída entera deja la conexión en el pool del
 * sistema para la siguiente petición (ver [performHop]); cualquier otro
 * desenlace la cierra.
 */
class UrlConnectionTransport internal constructor(
    private val baseUri: URI,
    private val token: ApiToken,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    // Por defecto Wifi solo para las pruebas del módulo, que van por http a
    // 127.0.0.1; la app no llega aquí: create() exige un proveedor real
    // cuando hay http.
    private val activeNetwork: ActiveNetworkProvider = ActiveNetworkProvider { NetworkKind.Wifi },
) : HttpTransport {

    init {
        // El constructor es «internal» para que las pruebas del módulo usen
        // HTTP plano con el HttpServer del JDK; la app solo puede llegar aquí
        // por create(), que pasa por ServerUrl y solo deja HTTP hacia la red
        // local cuando se pide a propósito.
        if (
            connectTimeoutMs <= 0 || readTimeoutMs <= 0 ||
            maxResponseBytes <= 0 || totalTimeoutMs <= 0
        ) {
            throw IllegalArgumentException(
                "Los tiempos de espera y el límite de respuesta tienen que ser mayores que cero.",
            )
        }
        // Un plazo disparatado desbordaría la suma en nanosegundos y dejaría el
        // plazo inservible; diez minutos sobran para cualquier petición.
        if (totalTimeoutMs > MAX_TOTAL_TIMEOUT_MS) {
            throw IllegalArgumentException(
                "El plazo total de una petición no puede pasar de diez minutos.",
            )
        }
        val scheme = baseUri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("El transporte solo admite http o https.")
        }
    }

    /**
     * Las redirecciones no pueden cambiar de esquema (ver
     * [validateRedirectTarget]), así que el esquema de la base es el de todos
     * los saltos y basta con mirarlo una vez.
     */
    private val isCleartext: Boolean = baseUri.scheme.equals("http", ignoreCase = true)

    /** Host de la base en minúsculas: es el que toda redirección debe respetar. */
    private val baseHost: String = requireNotNull(baseUri.host?.lowercase(Locale.ROOT)) {
        "La URL base del transporte necesita nombre de servidor."
    }

    /**
     * Segmentos de la ruta base: el ámbito en el que puede moverse una
     * redirección. Vacía si la base está en la raíz del dominio.
     */
    private val baseSegments: List<String> = segmentsOf(baseUri.rawPath.orEmpty())

    /** Resultado de un salto: o respuesta final o redirección que decidir. */
    private sealed interface Hop {
        class Final(val response: HttpResponse) : Hop
        class Redirect(val target: URI, val statusCode: Int) : Hop
    }

    override fun execute(request: HttpRequest): HttpResponse {
        // Un solo plazo para toda la petición, saltos incluidos: si se
        // reiniciara en cada salto, una cadena de redirecciones lentas dejaría
        // la app esperando sin fin.
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs)
        // Concatenar (y no «resolve») conserva la subruta de la base, que es
        // como se despliega Mealie cuando no está en la raíz del dominio.
        var target = try {
            URI(baseUri.toString().trimEnd('/') + request.path +
                (request.query?.let { "?$it" } ?: ""))
        } catch (ignored: URISyntaxException) {
            // Inalcanzable: HttpRequest solo admite «pchar» y escapes «%XX» bien
            // formados (en la ruta y también en la consulta, que se valida igual), así que la concatenación con la base siempre parsea. Si
            // algún día dejara de serlo, no se propaga la excepción (lleva la
            // URL entera en el mensaje) ni se culpa al servidor: el problema
            // está en la dirección que se iba a pedir.
            throw NetworkError.InvalidRequest
        }
        var method = request.method
        var headers = request.headers
        var body = request.body
        var redirects = 0
        while (true) {
            when (val hop = performHop(target, method, headers, body, deadlineNanos)) {
                is Hop.Final -> return hop.response
                is Hop.Redirect -> {
                    // Se valida antes de abrir la segunda conexión: al destino
                    // rechazado no le llega ni una cabecera.
                    val safeTarget = validateRedirectTarget(hop.target)
                    // Una escritura no se repite a ciegas: con 301 y 302 la
                    // mayoría de los clientes cambian el método a GET sin
                    // cuerpo (y aquí ni siquiera eso: se corta). Solo 307 y
                    // 308 conservan método y cuerpo tal cual; 303 es el «ya
                    // está hecho, mira aquí» y se sigue como GET sin cuerpo.
                    if (
                        method != HttpMethod.GET &&
                        hop.statusCode != STATUS_SEE_OTHER &&
                        hop.statusCode != STATUS_TEMPORARY_REDIRECT &&
                        hop.statusCode != STATUS_PERMANENT_REDIRECT
                    ) {
                        throw NetworkError.RedirectRejected
                    }
                    if (redirects >= MAX_REDIRECTS) throw NetworkError.TooManyRedirects
                    redirects++
                    target = safeTarget
                    if (hop.statusCode == STATUS_SEE_OTHER) {
                        // 303 obliga a GET; el Content-Type acompañaba al
                        // cuerpo, que ya no existe.
                        method = HttpMethod.GET
                        body = null
                        headers = request.headers.filterKeys {
                            !it.equals("Content-Type", ignoreCase = true)
                        }
                    }
                }
            }
        }
    }

    private fun performHop(
        target: URI,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
        deadlineNanos: Long,
    ): Hop {
        // El plazo se mira al empezar cada salto y lo que queda recorta los
        // tiempos de espera: si quedan 200 ms, ni la conexión ni la lectura
        // pueden esperar los 30 s configurados. Sin hilos ni temporizadores:
        // basta con no pedirle a HttpURLConnection más de lo que queda.
        val remainingMs = remainingMillis(deadlineNanos)
        if (remainingMs == 0L) throw NetworkError.Timeout
        // Justo antes de abrir la conexión, para que entre la pregunta y el
        // envío pase lo menos posible. Un proveedor que falla cuenta como «no»:
        // ante la duda, el token no sale en claro.
        if (isCleartext && !cleartextAllowedNow()) {
            throw NetworkError.LocalHttpNotAllowedOnThisNetwork
        }
        val hopConnectTimeoutMs = minOf(connectTimeoutMs.toLong(), remainingMs).toInt()
        val hopReadTimeoutMs = minOf(readTimeoutMs.toLong(), remainingMs).toInt()

        val connection = try {
            // En http en claro jamás se pasa por el proxy del sistema (manual, PAC
            // o de MDM): llevaría el token sin cifrar a algo que puede estar fuera
            // de la red local y anularía la frontera «solo IP privadas». Con https
            // el proxy solo ve un túnel CONNECT cifrado, así que se deja el del sistema.
            val opened = if (isCleartext) {
                target.toURL().openConnection(java.net.Proxy.NO_PROXY)
            } else {
                target.toURL().openConnection()
            }
            opened as HttpURLConnection
        } catch (error: IOException) {
            throw NetworkError.InvalidResponse
        } catch (error: RuntimeException) {
            // Un destino que no se puede ni abrir (esquema raro, autoridad
            // imposible): error fijo, sin arrastrar el mensaje original.
            throw NetworkError.InvalidResponse
        }
        // Solo se pone a true cuando el cuerpo de una 2xx se ha leído hasta el
        // final y su flujo se ha cerrado: es el único estado en el que la
        // conexión está limpia para volver al pool (keep-alive) y ahorrarse un
        // TCP y un TLS nuevos en la siguiente petición. Es seguro porque el
        // pool solo reutiliza una conexión para el mismo esquema, host y
        // puerto (el mismo origen que ya se ha validado), no guarda cabeceras
        // y aquí la cabecera Authorization se pone de nuevo en cada petición.
        // En todo lo demás (redirección, error HTTP, excepción, límite de
        // bytes, plazo agotado) quedan bytes sin leer o un estado dudoso, así
        // que se desconecta para que nadie herede esa conexión.
        var reusable = false
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = hopConnectTimeoutMs
            connection.readTimeout = hopReadTimeoutMs
            connection.requestMethod = method.name

            // Hasta aquí solo hay destinos del origen base: las redirecciones
            // a otro origen se han rechazado antes de abrir conexión.
            connection.setRequestProperty("Authorization", token.authorizationHeaderValue())
            if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                connection.setRequestProperty("Accept", "application/json")
            }
            for ((name, value) in headers) {
                connection.setRequestProperty(name, value)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size.toLong())
                connection.outputStream.use { it.write(body) }
            } else if (method != HttpMethod.GET) {
                // POST, PUT y DELETE sin cuerpo: el JDK, si no está en modo
                // «streaming», puede reintentar un POST por su cuenta
                // (sun.net.http.retryPost). Con salida declarada y longitud
                // fija cero se queda en un único envío, que es la política de
                // este transporte.
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0L)
                connection.outputStream.use { }
            }

            val statusCode = connection.responseCode
            if (statusCode == -1) throw NetworkError.InvalidResponse
            if (statusCode in REDIRECT_STATUSES) {
                val location = connection.getHeaderField("Location")
                    ?: throw NetworkError.InvalidResponse
                val resolved = try {
                    target.resolve(location)
                } catch (error: IllegalArgumentException) {
                    // Un «Location» que ni siquiera parsea como referencia.
                    throw NetworkError.InvalidResponse
                }
                // 303 obliga a GET; 301/302/307/308 conservan método y cuerpo.
                // El que decide si se sigue es execute(): una escritura con
                // 301 o 302 no pasa de aquí.
                return Hop.Redirect(resolved, statusCode)
            }
            if (statusCode !in 200..299) throw NetworkError.HttpError(statusCode)
            // El mapa de cabeceras del JDK y de Android es un
            // Map<String, List<String>> de Java que trae la clave nula de la
            // línea de estado y valores que pueden venir a nulos. Se copia con
            // el tipo anulable ya declarado en vez de castear: un «as» aquí
            // sería una conversión sin comprobar y un aviso suprimido.
            val rawHeaders: Map<String?, List<String?>?> = buildMap {
                for ((name, values) in connection.headerFields) {
                    put(name, values)
                }
            }
            val responseBody = readBody(connection, deadlineNanos)
            reusable = true
            return Hop.Final(HttpResponse(statusCode, lowercaseHeaders(rawHeaders), responseBody))
        } catch (error: NetworkError) {
            throw error
        } catch (error: IOException) {
            throw mapNetworkError(error)
        } catch (error: RuntimeException) {
            // Cualquier reventón de la implementación (ProtocolException no es
            // lo único que puede salir de aquí) se queda en un error fijo: la
            // causa original no se adjunta nunca.
            throw NetworkError.InvalidResponse
        } finally {
            if (!reusable) connection.disconnect()
        }
    }

    private fun cleartextAllowedNow(): Boolean =
        try {
            activeNetwork.current().allowsLocalHttp()
        } catch (error: RuntimeException) {
            false
        }

    private fun validateRedirectTarget(location: URI): URI {
        val targetScheme = location.scheme?.lowercase(Locale.ROOT)
        val baseScheme = baseUri.scheme?.lowercase(Locale.ROOT)
        // Comparar contra el ORIGEN DE LA BASE (y no contra el salto anterior)
        // garantiza que ninguna cadena de redirecciones sale de él: el primer
        // salto sale de la base y cada destino tiene que volver a coincidir.
        if (targetScheme == null || baseScheme == null) throw NetworkError.InvalidResponse
        if (targetScheme != baseScheme) throw NetworkError.RedirectRejected
        val targetHost = location.host ?: throw NetworkError.InvalidResponse
        // Comparación textual del host, sin resolver DNS: «localhost» y
        // «127.0.0.1» son el mismo sitio para la red, pero solo el texto se
        // puede comprobar sin preguntarle a nadie más.
        if (targetHost.lowercase(Locale.ROOT) != baseHost) throw NetworkError.RedirectRejected
        if (effectivePort(location) != effectivePort(baseUri)) throw NetworkError.RedirectRejected
        // Credenciales embebidas en la redirección: no se enviarían, pero
        // mejor ni siquiera abrir esa conexión.
        if (location.userInfo != null) throw NetworkError.RedirectRejected
        // Además del origen, el destino tiene que quedarse en la ruta base: si
        // Mealie vive en «/mealie», el token no tiene por qué visitar el resto
        // del dominio (otra aplicación, un panel de administración…).
        if (!isWithinBase(redirectSegments(location.rawPath.orEmpty()))) {
            throw NetworkError.RedirectRejected
        }
        return location
    }

    /**
     * ¿Cae el destino dentro de la ruta base?
     *
     * Se compara segmento a segmento y no como prefijo de texto: con
     * «/mealie» de base, el prefijo dejaría pasar «/mealiex», que para el
     * servidor es otra ruta completamente distinta. Con la base en la raíz no
     * hay nada que comprobar.
     */
    private fun isWithinBase(target: List<String>): Boolean {
        if (target.size < baseSegments.size) return false
        for (index in baseSegments.indices) {
            if (target[index] != baseSegments[index]) return false
        }
        return true
    }

    /**
     * Segmentos de una ruta, sin las barras. Las barras dobles no cambian el
     * ámbito y no se tienen en cuenta aquí (la petición normal sí las rechaza).
     */
    private fun segmentsOf(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /**
     * Segmentos del destino de una redirección, rechazando los que podrían
     * cambiarlo al resolverse: «.» y «..» (también escapados) reescriben la
     * ruta y podrían sacar la petición de la base —«/mealie/../otra» se
     * convertiría en «/otra»—, y «%2f» escondería una barra que cambia los
     * segmentos. Se corta aquí y no se compara nada más.
     */
    private fun redirectSegments(path: String): List<String> {
        val segments = segmentsOf(path)
        for (segment in segments) {
            if (segment == "." || segment == "..") throw NetworkError.RedirectRejected
            val lower = segment.lowercase(Locale.ROOT)
            if ("%2e" in lower || "%2f" in lower) throw NetworkError.RedirectRejected
            // «..;» lo leen algunos servidores como «..», y «%25» permite doble
            // codificación de lo anterior.
            if (';' in segment || "%25" in lower) throw NetworkError.RedirectRejected
        }
        return segments
    }

    /** Puerto explícito o el de por defecto del esquema: 443 para https, 80 para http. */
    private fun effectivePort(uri: URI): Int {
        if (uri.port != -1) return uri.port
        return if (uri.scheme?.equals("https", ignoreCase = true) == true) 443 else 80
    }

    private fun readBody(connection: HttpURLConnection, deadlineNanos: Long): ByteArray {
        // Con Content-Length se puede rechazar antes de descargar nada.
        connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { declared ->
            if (declared > maxResponseBytes) throw NetworkError.ResponseTooLarge
        }
        // El flujo se cierra aquí y no en el «finally» de la conexión: así se
        // libera en cuanto se termina (o se falla) de leer, y una respuesta
        // leída hasta el final devuelve la conexión al pool al cerrarlo.
        connection.inputStream.use { input ->
            val buffer = ByteArray(READ_CHUNK)
            val output = ByteArrayOutputStream()
            var total = 0L
            while (true) {
                // El plazo total se comprueba entre lecturas: un servidor que
                // manda algo cada poco nunca agota el tiempo de lectura, pero
                // sí puede pasarse del plazo de la petición. La resta (y no una
                // comparación directa entre nanoTime y el plazo) es lo que
                // sigue valiendo cuando nanoTime da la vuelta.
                if (System.nanoTime() - deadlineNanos > 0) throw NetworkError.Timeout
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                // Al primer trozo que se pasa del límite se corta: no se sigue
                // leyendo el resto de una respuesta que ya no se va a usar.
                if (total > maxResponseBytes) throw NetworkError.ResponseTooLarge
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    /**
     * Cabeceras del último salto, con el nombre en minúsculas y sin la línea
     * de estado.
     *
     * El mapa del JDK (y el de Android) es un «Map<String, List<String>>» de
     * Java con una clave nula —la línea de estado— y valores que pueden venir
     * a nulos; se declara con los dos niveles anulables para no comerse un
     * NullPointerException al recorrerlo.
     */
    private fun lowercaseHeaders(raw: Map<String?, List<String?>?>): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        for ((key, values) in raw) {
            val name = key ?: continue
            if (values == null) continue
            val list = result.getOrPut(name.lowercase(Locale.ROOT)) { mutableListOf() }
            for (value in values) {
                if (value != null) list.add(value)
            }
        }
        return result
    }

    /**
     * Traduce las IOException típicas. Sin mirar el mensaje de la original:
     * puede traer URL, host o detalles del entorno que no deben llegar a un
     * log.
     */
    private fun mapNetworkError(error: IOException): NetworkError = when (error) {
        is SocketTimeoutException -> NetworkError.Timeout
        is UnknownHostException -> NetworkError.DnsFailure
        is SSLException -> NetworkError.TlsError
        is SocketException -> NetworkError.NoConnection // incluye ConnectException
        else -> NetworkError.InvalidResponse
    }

    /**
     * Milisegundos que quedan del plazo, o 0 si ya no queda nada.
     *
     * Se resta en vez de comparar nanoTime con el plazo porque nanoTime no es
     * un reloj: solo su diferencia tiene sentido (y así el desbordamiento de
     * la suma da igual). Nunca devuelve menos de 1 ms mientras quede algo,
     * porque un tiempo de espera de 0 en HttpURLConnection significa «sin
     * límite», justo lo contrario de lo que se busca.
     */
    private fun remainingMillis(deadlineNanos: Long): Long {
        val left = deadlineNanos - System.nanoTime()
        if (left <= 0) return 0
        return TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1L)
    }

    companion object {
        /** Seguir como mucho cinco; a la sexta se considera bucle. */
        const val MAX_REDIRECTS = 5

        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000
        private const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        /** 60 s para la petición entera: leer una receta o subir una imagen da de sobra. */
        private const val DEFAULT_TOTAL_TIMEOUT_MS = 60_000L

        /** Tope del plazo total: más allá, la suma en nanosegundos pierde sentido. */
        private const val MAX_TOTAL_TIMEOUT_MS = 600_000L
        private const val READ_CHUNK = 8192

        /** 303 «See Other»: se sigue como GET sin cuerpo. */
        private const val STATUS_SEE_OTHER = 303

        /** 307 «Temporary Redirect»: conserva método y cuerpo. */
        private const val STATUS_TEMPORARY_REDIRECT = 307

        /** 308 «Permanent Redirect»: conserva método y cuerpo. */
        private const val STATUS_PERMANENT_REDIRECT = 308

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /**
         * Camino público y único para la app: normaliza la URL con la política
         * de ServerUrl. Con [allowLocalHttp] a false solo pasa https; con true
         * pasa también http, pero solo hacia IP literales privadas (ver
         * ServerUrl.normalize). No hay otra vía pública hacia http.
         *
         * Con [allowLocalHttp] a true, [activeNetwork] es obligatorio y si es
         * null se lanza [IllegalArgumentException]: falla cerrado para que no
         * pueda existir un transporte http que no mire por qué red sale el
         * token. Se exige aunque la URL resulte ser https, porque quien
         * enciende la opción puede guardar después una URL http y el
         * transporte se crea con la misma llamada; con https no se consulta.
         *
         * Las redirecciones siguen exigiendo el mismo esquema que la base: un
         * Mealie local que redirige de http a https da
         * [NetworkError.RedirectRejected]. No se sigue a propósito: aceptarlo
         * abriría la puerta a que un http de la red local mandara el token a
         * otro esquema, y si el servidor ya sirve https, lo que toca es
         * configurarlo con «https://».
         */
        fun create(
            serverUrl: String,
            token: ApiToken,
            allowLocalHttp: Boolean = false,
            activeNetwork: ActiveNetworkProvider? = null,
            connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
            maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
            totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
        ): UrlConnectionTransport {
            if (allowLocalHttp && activeNetwork == null) {
                throw IllegalArgumentException(
                    "Para usar HTTP en la red local hace falta saber por qué red sale el móvil.",
                )
            }
            val normalized = ServerUrl.normalize(serverUrl, allowLocalHttp)
            val base = try {
                URI(normalized)
            } catch (ignored: URISyntaxException) {
                // ServerUrl ya la reconstruye para que parsee; si aun así falla,
                // el mensaje de la excepción llevaría la URL entera y no se usa.
                throw IllegalArgumentException(
                    "La URL del servidor no es válida. Revísala antes de guardar.",
                )
            }
            return UrlConnectionTransport(
                baseUri = base,
                token = token,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                maxResponseBytes = maxResponseBytes,
                totalTimeoutMs = totalTimeoutMs,
                // Sin la opción solo puede ser https y el proveedor no se
                // consulta nunca; el «no» fijo es por si acaso.
                activeNetwork = activeNetwork ?: ActiveNetworkProvider { NetworkKind.None },
            )
        }
    }
}
