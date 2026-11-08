package io.github.garfieldrockero.gastronomicon.ui.components

/**
 * Deja del servidor solo el último dominio («mealie.example.com» → «***.com»);
 * con una IP, nada («192.168.1.50» → «***»).
 * No es un dato para mostrar: existe únicamente para que un log diga de qué
 * servidor se hablaba sin publicar la dirección completa de la persona.
 */
fun maskedServer(server: String): String {
    val host = server.trim()
        .substringAfter("://", server.trim())
        .substringBefore('/')
        .substringBefore(':')
    if (host.isEmpty()) return ""
    val domain = host.substringAfterLast('.', "")
    // Con una IP (v4 o v6) el último tramo es un número del propio equipo de
    // la persona: no se enseña nada. Con un nombre, solo su último dominio.
    // Un último tramo que empieza por dígito (también con basura detrás, como
    // «192.168.1.50?x») es de una IP, no de un dominio: ninguno acaba en cifra.
    val isIp = host.startsWith("[") || domain.firstOrNull()?.isDigit() == true ||
        host.all { it.isDigit() || it == '.' }
    return if (domain.isEmpty() || isIp) "***" else "***.$domain"
}
