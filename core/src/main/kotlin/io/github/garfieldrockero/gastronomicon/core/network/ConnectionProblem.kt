package io.github.garfieldrockero.gastronomicon.core.network

import io.github.garfieldrockero.gastronomicon.core.NotLocalAddressException

/**
 * Motivo de un fallo al conectar con Mealie, ya reducido a lo que la persona
 * puede hacer para arreglarlo.
 *
 * Vive en el núcleo y no en la interfaz porque decidir que un 404 es «la
 * dirección está mal» o que un 403 es «el token no vale» es lógica, y la
 * interfaz solo elige el texto de cada caso.
 */
enum class ConnectionProblem {
    /** Sin red o conexión cortada: el móvil no ha llegado a hablar con el servidor. */
    NoNetwork,

    /**
     * Se agotó el tiempo de conexión o de lectura. Va aparte de [NoNetwork]
     * porque con la wifi bien y el servidor apagado o colgado decir «revisa la
     * wifi» es falso: lo que toca es mirar si el servidor está en marcha.
     */
    ServerNotResponding,

    /** El servidor rechaza el token (401) o no le da permiso (403). */
    Unauthorized,

    /** El servidor ha fallado por su cuenta (5xx). */
    ServerError,

    /** La dirección no lleva a un Mealie: no resuelve o no hay API ahí. */
    InvalidAddress,

    /**
     * No se ha podido verificar la conexión segura. Va aparte de
     * [InvalidAddress] porque en una red con portal cautivo o que intercepta el
     * TLS la dirección está bien y decir lo contrario despista.
     */
    SecureConnectionFailed,

    /**
     * El servidor está en http y el móvil no va por la wifi de casa, ethernet
     * ni VPN (por ejemplo, con datos móviles): no se ha enviado nada. Va
     * aparte de [NoNetwork] porque sí hay red, y lo que se arregla es la red
     * por la que se sale o pasar el servidor a https.
     */
    LocalHttpNeedsHomeNetwork,

    /**
     * La dirección está bien escrita, pero es http hacia algo que no es una
     * IP privada (un dominio, una IP pública, un nombre «.local»…). Nunca sale
     * de [toConnectionProblem]: es para cuando `ServerAddress.build` o
     * `ServerUrl.normalize` lo rechazan, a través de [addressProblemOf].
     */
    NotLocalAddress,

    /**
     * El token no tiene una forma válida y ni siquiera se ha enviado.
     *
     * Nunca sale de [toConnectionProblem]: es para cuando
     * `ApiToken.fromInput` rechaza lo que ha pegado la persona, antes de
     * cualquier petición.
     */
    InvalidToken,

    /** Cualquier otra cosa: redirecciones bloqueadas, respuestas raras o demasiado grandes. */
    Unknown,
}

/**
 * Traduce un [NetworkError] al motivo que se enseña al probar la conexión.
 *
 * El «when» no tiene «else» a propósito: si se añade un subtipo a
 * [NetworkError], esto deja de compilar hasta que alguien decida qué significa
 * para la persona, en vez de caer callado en [ConnectionProblem.Unknown].
 */
fun NetworkError.toConnectionProblem(): ConnectionProblem = when (this) {
    is NetworkError.HttpError -> when (statusCode) {
        401, 403 -> ConnectionProblem.Unauthorized
        // Un 404 al probar la conexión casi siempre es Mealie en otra subruta
        // (o no es Mealie): el servidor contesta, pero la API no está ahí.
        404 -> ConnectionProblem.InvalidAddress
        in 500..599 -> ConnectionProblem.ServerError
        else -> ConnectionProblem.Unknown
    }
    NetworkError.NoConnection -> ConnectionProblem.NoNetwork
    NetworkError.Timeout -> ConnectionProblem.ServerNotResponding
    NetworkError.ConnectionRefused -> ConnectionProblem.ServerNotResponding
    NetworkError.DnsFailure -> ConnectionProblem.InvalidAddress
    NetworkError.TlsError -> ConnectionProblem.SecureConnectionFailed
    NetworkError.InvalidRequest -> ConnectionProblem.InvalidAddress
    NetworkError.RedirectRejected -> ConnectionProblem.Unknown
    NetworkError.TooManyRedirects -> ConnectionProblem.Unknown
    NetworkError.InvalidResponse -> ConnectionProblem.Unknown
    NetworkError.ResponseTooLarge -> ConnectionProblem.Unknown
    NetworkError.LocalHttpNotAllowedOnThisNetwork -> ConnectionProblem.LocalHttpNeedsHomeNetwork
    // El envoltorio no cambia el motivo: la persona ve lo mismo que si no
    // supiera si el servidor llegó a recibir la petición.
    is NetworkError.AfterSend -> original.toConnectionProblem()
}

/**
 * Motivo de un rechazo de la dirección al guardarla (`ServerAddress.build` o
 * `ServerUrl.normalize`), para que la interfaz no tenga que distinguir casos
 * por su cuenta: [NotLocalAddressException] es [ConnectionProblem.NotLocalAddress]
 * y cualquier otro rechazo es [ConnectionProblem.InvalidAddress]. Se decide por
 * el tipo, nunca por el texto del mensaje.
 */
fun addressProblemOf(error: IllegalArgumentException): ConnectionProblem =
    if (error is NotLocalAddressException) {
        ConnectionProblem.NotLocalAddress
    } else {
        ConnectionProblem.InvalidAddress
    }
