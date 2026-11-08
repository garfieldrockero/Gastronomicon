package io.github.garfieldrockero.gastronomicon.core.network

/**
 * Envía una petición al servidor y devuelve la respuesta final, o lanza un
 * [NetworkError].
 *
 * Es síncrono a propósito: el núcleo no arrastra corutinas (no están entre las
 * dependencias aprobadas) y quien llama decide desde qué hilo lo usa.
 */
interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}
