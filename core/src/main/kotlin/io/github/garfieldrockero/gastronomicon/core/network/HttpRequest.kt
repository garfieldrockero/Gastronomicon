package io.github.garfieldrockero.gastronomicon.core.network

import java.util.Locale

/**
 * Una petición al servidor Mealie.
 *
 * La ruta es relativa a la URL base que ya lleva el transporte (siempre
 * «/…»), así que quien construye la petición no puede apuntar a otro sitio.
 * Se valida al construirla y no al enviarla para que ni siquiera un
 * transporte de mentira (para vistas previas de la interfaz) reciba algo que
 * no deba salir de la app.
 */
class HttpRequest internal constructor(
    val method: HttpMethod,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /**
     * Consulta ya codificada, sin el «?» inicial. Va aparte de la ruta para
     * poder validarla por separado: solo se admiten caracteres sin reservar,
     * «=» y «&» como estructura y escapes «%XX» bien formados. Quien la
     * construye (por ejemplo `RecipeListQuery`) codifica los valores.
     */
    val query: String? = null,
    /**
     * Pide al transporte que, si el servidor contesta 422, adjunte su cuerpo
     * (acotado a [MAX_ERROR_BODY_BYTES]) a [NetworkError.HttpError]. Por
     * defecto no se lee: solo el formulario de recetas necesita el detalle de
     * validación, y el resto de errores se quedan en el código HTTP.
     */
    val readErrorBody: Boolean = false,
    /**
     * Tope del cuerpo de ESTA petición. Casi siempre [MAX_BODY_BYTES]; solo
     * importar con la IA lo sube, porque manda hasta 15 MiB de fotos además
     * del texto (ver [multipartWithBodyLimit]). No es una propiedad: solo lo
     * mira el `init` y después no hay nada que leer.
     *
     * El constructor es `internal` para que ese tope no se pueda ampliar desde
     * fuera del núcleo por descuido: la app no construye peticiones.
     */
    bodyLimitBytes: Int = MAX_BODY_BYTES,
) {
    init {
        // Un GET no puede llevar cuerpo: en HttpURLConnection, poner
        // doOutput u obtener el flujo de salida cambia el método a POST (también
        // con un cuerpo de cero bytes). El servidor recibiría otra petición
        // distinta de la que se cree enviar, así que se corta al construirla.
        if (method == HttpMethod.GET && body != null) {
            throw IllegalArgumentException(
                "Una petición GET no puede llevar cuerpo: en la red se convertiría en POST.",
            )
        }
        // Tope del cuerpo que sale: una subida enorme por error (o un cuerpo
        // hostil construido por otro módulo) no debe llegar a la red.
        if (body != null && body.size > bodyLimitBytes) {
            throw IllegalArgumentException("El cuerpo de la petición supera los $bodyLimitBytes bytes.")
        }
        if (!path.startsWith("/")) {
            throw IllegalArgumentException("La ruta de la petición tiene que empezar por «/».")
        }
        // La consulta tiene su propio campo (`query`); pegarla a mano en la
        // ruta la dejaría sin validar.
        if ('?' in path || '#' in path) {
            throw IllegalArgumentException(
                "La ruta de la petición no puede llevar consulta («?») ni fragmento («#»).",
            )
        }
        // Solo «pchar» del RFC 3986 más la barra, y los escapes «%XX» bien
        // formados: es la condición para que URI(base + ruta) no lance nunca una
        // URISyntaxException, cuyo mensaje arrastraría la URL entera.
        validatePathChars(path)
        // La barra final sí se admite: Mealie (FastAPI) distingue /x de /x/ y
        // decide el servidor. Lo que no se admite es un hueco entre barras:
        // «//», «/a//b» y «/a//» dejan un segmento vacío que el servidor
        // interpretaría a su manera (y que algunos proxies normalizan). La
        // barra final se aparta antes de comprobar, para no confundirla con un
        // segmento vacío.
        val segments = path.substring(1).split('/')
        val checked = if (path.endsWith("/")) segments.dropLast(1) else segments
        for (segment in checked) {
            if (segment.isEmpty()) {
                throw IllegalArgumentException(
                    "La ruta no puede tener barras dobles ni segmentos vacíos.",
                )
            }
            if (segment == "." || segment == "..") {
                throw IllegalArgumentException("La ruta no puede llevar «.» ni «..».")
            }
            if (segment.contains("%2e", ignoreCase = true) || segment.contains("%2f", ignoreCase = true)) {
                throw IllegalArgumentException("La ruta no puede llevar «.» ni «/» escapados.")
            }
        }
        if (query != null) validateQuery(query)
        for ((name, value) in headers) {
            if (!HEADER_NAME.matches(name)) {
                throw IllegalArgumentException("El nombre de una cabecera de la petición no es válido.")
            }
            if (value.any { it.code !in PRINTABLE_ASCII_WITH_SPACE }) {
                throw IllegalArgumentException(
                    "El valor de una cabecera lleva caracteres no admitidos (CR, LF o no ASCII).",
                )
            }
            if (name.lowercase(Locale.ROOT) in TRANSPORT_OWNED_HEADERS) {
                throw IllegalArgumentException(
                    "Authorization, Content-Length, Transfer-Encoding, Host y Connection " +
                        "las gestiona el transporte: no las pongas a mano.",
                )
            }
        }
    }

    companion object {
        /** Tope de la consulta ya codificada; los servidores y proxies suelen cortar cerca de 8 KB. */
        const val MAX_QUERY_LENGTH = 2048

        /** Tope del cuerpo de una petición: 5 MiB de imagen más el envoltorio multipart. */
        const val MAX_BODY_BYTES = 6 * 1024 * 1024

        /** Tope del cuerpo de error que se lee de un 422; el detalle útil son unos cientos de bytes. */
        const val MAX_ERROR_BODY_BYTES = 16 * 1024

        /** Petición con cuerpo JSON (UTF-8). [json] ya viene serializado por quien llama. */
        fun json(
            method: HttpMethod,
            path: String,
            json: String,
            readErrorBody: Boolean = false,
        ): HttpRequest = HttpRequest(
            method = method,
            path = path,
            headers = mapOf("Content-Type" to "application/json"),
            body = json.toByteArray(Charsets.UTF_8),
            readErrorBody = readErrorBody,
        )

        /** Petición `multipart/form-data` con el cuerpo ya montado por [MultipartBody]. */
        fun multipart(
            method: HttpMethod,
            path: String,
            body: MultipartBody.Built,
            readErrorBody: Boolean = false,
        ): HttpRequest = HttpRequest(
            method = method,
            path = path,
            headers = mapOf("Content-Type" to body.contentType),
            body = body.bytes,
            readErrorBody = readErrorBody,
        )

        /**
         * Como [multipart], pero con un tope de cuerpo propio, para la única
         * petición que no cabe en [MAX_BODY_BYTES]: importar con la IA, que
         * manda el texto y hasta 15 MiB de fotos juntos
         * (`MealieClient.createRecipeWithAi`).
         *
         * Es `internal` a propósito: el tope de las peticiones normales no se
         * toca y nadie fuera del núcleo puede ampliarlo. Quien llama ya ha
         * comprobado que el cuerpo cabe; si aun así no cupiera, el `init`
         * lanza y quien llama lo convierte en un rechazo local.
         */
        internal fun multipartWithBodyLimit(
            method: HttpMethod,
            path: String,
            body: MultipartBody.Built,
            maxBodyBytes: Int,
            readErrorBody: Boolean = false,
        ): HttpRequest = HttpRequest(
            method = method,
            path = path,
            headers = mapOf("Content-Type" to body.contentType),
            body = body.bytes,
            readErrorBody = readErrorBody,
            bodyLimitBytes = maxBodyBytes,
        )

        private val PRINTABLE_ASCII_WITH_SPACE = 0x20..0x7E

        /** Nombres de cabecera según RFC 7230 (token). */
        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        /** El transporte las pone o las calcula él mismo: duplicarlas sería un bug. */
        private val TRANSPORT_OWNED_HEADERS = setOf(
            "authorization",
            "content-length",
            "transfer-encoding",
            "host",
            "connection",
        )

        /**
         * Comprueba la consulta carácter a carácter. Se deja fuera «;», «+» y
         * «,» a propósito: algunos servidores y proxies los tratan como
         * separador o como espacio, y quien codifica ya los escribe como «%XX».
         */
        private fun validateQuery(query: String) {
            // Una consulta vacía enviaría un «?» suelto; quien no tiene
            // parámetros debe pasar null, y así no hay dos formas de decir «nada».
            if (query.isEmpty()) {
                throw IllegalArgumentException("La consulta no puede estar vacía: usa null si no hay parámetros.")
            }
            if (query.length > MAX_QUERY_LENGTH) {
                throw IllegalArgumentException("La consulta supera los $MAX_QUERY_LENGTH caracteres.")
            }
            var index = 0
            while (index < query.length) {
                val current = query[index]
                if (current == '%') {
                    val high = if (index + 2 < query.length) hexValue(query[index + 1]) else -1
                    val low = if (index + 2 < query.length) hexValue(query[index + 2]) else -1
                    if (high < 0 || low < 0) {
                        throw IllegalArgumentException(
                            "La consulta lleva un «%» sin dos dígitos hexadecimales detrás.",
                        )
                    }
                    val value = high * 16 + low
                    if (value <= 0x1F || value == 0x7F) {
                        throw IllegalArgumentException("La consulta lleva un escape de control.")
                    }
                    index += 3
                    continue
                }
                val allowed = current in 'a'..'z' || current in 'A'..'Z' || current in '0'..'9' ||
                    current == '-' || current == '.' || current == '_' || current == '~' ||
                    current == '=' || current == '&'
                if (!allowed) {
                    throw IllegalArgumentException(
                        "La consulta lleva un carácter sin codificar: usa percent-encoding.",
                    )
                }
                index++
            }
        }

        /**
         * Recorre la ruta comprobando «pchar» del RFC 3986 más «/», con los
         * escapes «%XX» completos y sin codificar controles ni barras.
         *
         * Se valida carácter a carácter, y no con una lista de prohibidos,
         * porque lo que tiene que quedar fuera es cualquier cosa que el parser
         * de URI no acepte (comillas, ángulos, llaves, barras verticales,
         * acentos o controles).
         */
        private fun validatePathChars(path: String) {
            var index = 0
            while (index < path.length) {
                val current = path[index]
                if (current == '%') {
                    if (index + 2 >= path.length) {
                        throw IllegalArgumentException(
                            "La ruta lleva un «%» sin dos dígitos hexadecimales detrás.",
                        )
                    }
                    // Solo «0-9a-fA-F»: Character.digit aceptaría dígitos de
                    // otros alfabetos y el «%» dejaría de ser un escape ASCII
                    // válido para el parser de URI.
                    val high = hexValue(path[index + 1])
                    val low = hexValue(path[index + 2])
                    if (high < 0 || low < 0) {
                        throw IllegalArgumentException(
                            "La ruta lleva un «%» sin dos dígitos hexadecimales detrás.",
                        )
                    }
                    val value = high * 16 + low
                    // Los controles romperían la línea de petición y el «/»
                    // escapado cambiaría el segmento que ve el servidor.
                    if (value <= 0x1F || value == 0x7F || value == 0x2F) {
                        throw IllegalArgumentException("La ruta lleva un escape no admitido.")
                    }
                    index += 3
                    continue
                }
                if (!isPathChar(current)) {
                    throw IllegalArgumentException(
                        "La ruta lleva un carácter no admitido: usa letras, números y los símbolos " +
                            "de una URL, y escribe los especiales con percent-encoding.",
                    )
                }
                index++
            }
        }

        /** «pchar» del RFC 3986 (sin escapes, que van aparte) más la barra. */
        private fun isPathChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '-' || c == '.' || c == '_' || c == '~' ||
                c == '!' || c == '$' || c == '&' || c == '\'' ||
                c == '(' || c == ')' || c == '*' || c == '+' ||
                c == ',' || c == ';' || c == '=' || c == ':' ||
                c == '@' || c == '/'

        /** Valor de un dígito hexadecimal ASCII, o -1 si no lo es. */
        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
