package io.github.garfieldrockero.gastronomicon.core.network

/**
 * Métodos HTTP que usa la app contra Mealie.
 *
 * Sin PATCH a propósito: el «HttpURLConnection» del JDK lo rechaza en
 * setRequestMethod y la API de Mealie v3.29.0 todavía no está verificada. Si
 * algún endpoint resultara necesitarlo, se decidirá entonces (por ejemplo con
 * una cabecera de anulación de método), no a ciegas.
 */
enum class HttpMethod {
    GET, POST, PUT, DELETE,
}
