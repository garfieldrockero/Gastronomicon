package io.github.garfieldrockero.gastronomicon.core.session

import io.github.garfieldrockero.gastronomicon.core.ServerUrl
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken

/**
 * Lo que hay que recordar entre arranques para hablar con el servidor: su URL
 * base y el token de API.
 *
 * [serverUrl] tiene que venir ya en forma canónica (salida de
 * [ServerUrl.normalize] o de `ServerAddress.build`). Se exige igualdad exacta
 * en vez de normalizar aquí dentro para que lo que se guarda y lo que se
 * recupera sea siempre la misma cadena, y para no esconder un cambio de
 * esquema silencioso. Se valida con el modo local admitido porque si el HTTP
 * está permitido o no es una preferencia de ajustes que `Session` no conoce;
 * quien la lee de nuevo vuelve a aplicar su política antes de conectar.
 *
 * [toString] solo enseña el esquema y una máscara: el token es un secreto y la
 * URL del servidor de una persona es un dato suyo que no debe acabar en un log
 * ni en un informe de fallos. El esquema se deja porque es lo único útil para
 * diagnosticar («¿iba por http?») y no identifica a nadie.
 *
 * Se usa clase normal y no `data class` para que el `toString` y el `copy` que
 * generaría no puedan filtrar nada.
 */
class Session(val serverUrl: String, val token: ApiToken) {

    init {
        // Mensaje propio y fijo: el de ServerUrl podría hablar de la URL y aquí
        // no queremos que una excepción la reproduzca.
        val canonical = try {
            ServerUrl.normalize(serverUrl, allowLocalHttp = true)
        } catch (ignored: IllegalArgumentException) {
            throw IllegalArgumentException("La URL del servidor de la sesión no es válida.")
        }
        require(canonical == serverUrl) {
            "La URL del servidor de la sesión tiene que estar normalizada."
        }
    }

    /** Esquema ya validado («http» o «https»). */
    val scheme: String get() = serverUrl.substringBefore("://")

    // El token se compara por valor, pero ni equals ni hashCode lo exponen:
    // hashCode de un String no permite recuperarlo de forma práctica, y aun así
    // se mezcla con el de la URL para no dar un hash que dependa solo del token.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Session && serverUrl == other.serverUrl && token.value == other.token.value)

    override fun hashCode(): Int = 31 * serverUrl.hashCode() + token.value.hashCode()

    override fun toString(): String = "Session(scheme=$scheme, server=***, token=***)"
}
