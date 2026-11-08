package io.github.garfieldrockero.gastronomicon.ui.components

import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem

/**
 * El núcleo decide el motivo y aquí solo se le pone texto. Los textos viven en
 * `strings.xml` y no en el estado: así no puede colarse una excepción, la
 * respuesta cruda del servidor ni nada de lo que escribió la persona en un
 * mensaje que acaba en pantalla, en un log o en una captura. Sin «else» a
 * propósito: un motivo nuevo en el núcleo no compila hasta tener su texto.
 */
fun errorTextRes(problem: ConnectionProblem): Int = when (problem) {
    ConnectionProblem.NoNetwork -> R.string.setup_error_no_network
    ConnectionProblem.ServerNotResponding -> R.string.setup_error_server_not_responding
    ConnectionProblem.Unauthorized -> R.string.setup_error_unauthorized
    ConnectionProblem.ServerError -> R.string.setup_error_server
    ConnectionProblem.InvalidAddress -> R.string.setup_error_invalid_address
    ConnectionProblem.NotLocalAddress -> R.string.setup_error_not_local_address
    ConnectionProblem.LocalHttpNeedsHomeNetwork -> R.string.setup_error_local_http_needs_home_network
    ConnectionProblem.SecureConnectionFailed -> R.string.setup_error_secure_connection
    ConnectionProblem.InvalidToken -> R.string.setup_error_invalid_token
    ConnectionProblem.Unknown -> R.string.setup_error_unknown
}
