package io.github.garfieldrockero.gastronomicon.core.network

/**
 * Todo lo que puede fallar en una petición.
 *
 * Es una jerarquía sellada lanzada como excepción: la interfaz necesita
 * distinguir los casos (reintentar, pedir token nuevo, mostrar un mensaje) y
 * con tipos sellados el «when» es exhaustivo sin parsear texto; y como
 * excepción viaja igual que cualquier otro fallo de E/S sin arrastrar una
 * librería de resultados.
 *
 * Los fallos de configuración (URL, token) no van aquí: esos se validan al
 * guardar en Ajustes y son IllegalArgumentException con mensaje propio, como
 * hace ServerUrl.
 *
 * Nunca se adjunta la excepción original como causa: su mensaje puede traer la
 * URL, el host o detalles del entorno, y una traza completa lo imprimiría en
 * cualquier log. Los mensajes de aquí son fijos, en castellano y sin datos de
 * la petición.
 *
 * Por lo mismo, tampoco se rellena la traza ni se admiten causas acumuladas:
 * los subtipos «object» son instancias compartidas por toda la app, y una
 * traza o una excepción suprimida las iría acumulando entre peticiones (y
 * retendría en memoria las que ya fallaron). Se pasan los cuatro argumentos a
 * propósito: mensaje, causa nula, sin supresión y sin traza.
 */
sealed class NetworkError(message: String) : Exception(message, null, false, false) {

    /** No hay red, la conexión se ha rechazado o se ha cortado a mitad de petición. */
    object NoConnection : NetworkError(
        "No se ha podido conectar con el servidor o la conexión se ha cortado. " +
            "Comprueba la dirección y tu conexión.",
    )

    /** El nombre del servidor no resuelve. */
    object DnsFailure : NetworkError(
        "No se ha encontrado el servidor: revisa la dirección.",
    )

    /** El certificado o el TLS no han pasado la validación del sistema. */
    object TlsError : NetworkError(
        "No se ha podido establecer la conexión segura con el servidor.",
    )

    /** El servidor o la red han tardado más de lo configurado. */
    object Timeout : NetworkError(
        "El servidor ha tardado demasiado en responder.",
    )

    /** La respuesta supera el límite de bytes configurado en el transporte. */
    object ResponseTooLarge : NetworkError(
        "La respuesta del servidor es demasiado grande.",
    )

    /** El servidor manda a una dirección distinta del origen configurado. */
    object RedirectRejected : NetworkError(
        "El servidor ha intentado redirigir la petición a otra dirección distinta de la configurada; " +
            "se ha bloqueado para no enviar el token fuera de tu servidor.",
    )

    /** Demasiadas redirecciones seguidas: casi seguro un bucle. */
    object TooManyRedirects : NetworkError(
        "El servidor redirige la petición demasiadas veces seguidas.",
    )

    /**
     * La petición no se ha podido ni componer, así que no ha salido de la app.
     *
     * Es distinto de [InvalidResponse] a propósito: aquí el servidor no ha
     * dicho nada raro, el problema está en la dirección que se iba a pedir.
     */
    object InvalidRequest : NetworkError(
        "La petición no es válida y no se ha enviado. Revisa la dirección del servidor.",
    )

    /**
     * La URL base es http y la red activa no es una de las que pueden ser la
     * de casa (ver [allowsLocalHttp]): con datos móviles una IP privada no es
     * el servidor de casa y el token iría en claro hacia otro sitio. Se lanza
     * sin abrir conexión.
     */
    object LocalHttpNotAllowedOnThisNetwork : NetworkError(
        "Sin HTTPS solo se puede conectar desde la wifi de casa o por VPN. " +
            "Con datos móviles no se envía nada.",
    )

    /** La respuesta no sigue el protocolo que se espera de un servidor HTTP. */
    object InvalidResponse : NetworkError(
        "La respuesta del servidor no es válida.",
    )

    /** El servidor ha contestado con un código de error HTTP. */
    class HttpError(val statusCode: Int) : NetworkError(messageFor(statusCode))

    companion object {
        /**
         * El número siempre viaja en el mensaje: ayuda a diagnosticar y no es
         * ningún secreto (no viene de lo que escribe la persona).
         */
        private fun messageFor(statusCode: Int): String = when (statusCode) {
            401 -> "El token no es válido o ha caducado (código 401). Genera otro en Mealie y " +
                "actualízalo en Ajustes."
            403 -> "El servidor ha denegado el acceso (código 403): el token no tiene permiso para esto."
            404 -> "No se ha encontrado en el servidor lo que se ha pedido (código 404)."
            422 -> "El servidor ha rechazado los datos por incompletos o no válidos (código 422)."
            in 500..599 -> "El servidor ha fallado (código $statusCode)."
            else -> "El servidor ha respondido con un error (código $statusCode)."
        }
    }
}
