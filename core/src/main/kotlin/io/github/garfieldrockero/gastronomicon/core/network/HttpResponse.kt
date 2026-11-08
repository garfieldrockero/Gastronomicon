package io.github.garfieldrockero.gastronomicon.core.network

import java.util.Locale

/**
 * Respuesta HTTP final (ya sin redirecciones) de una petición al servidor.
 *
 * El transporte solo entrega respuestas 2xx: los códigos de error HTTP no
 * llegan aquí, van como [NetworkError.HttpError]. Las cabeceras se guardan con
 * el nombre en minúsculas porque en HTTP no distinguen mayúsculas: así
 * «Location», «location» o «LOCATION» son la misma clave venga de la
 * implementación que venga.
 */
class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    val isSuccess: Boolean
        get() = statusCode in 200..299

    /** Primer valor de una cabecera, buscado sin distinguir mayúsculas. */
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.firstOrNull()

    /** El cuerpo como texto: la API de Mealie es JSON y el JSON es UTF-8. */
    fun bodyAsText(): String = String(body, Charsets.UTF_8)
}
