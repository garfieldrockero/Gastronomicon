package io.github.garfieldrockero.gastronomicon.core.widget

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem

/**
 * Línea de estado del widget: un texto **fijo** elegido por tipo, nunca un
 * mensaje del servidor.
 *
 * El widget no puede enseñar un diálogo ni un `Toast` fiable desde segundo
 * plano, así que el estado va dentro de la propia vista. Ninguno de estos
 * textos lleva URL, usuario, token ni cuerpo de respuesta: los mensajes se
 * eligen con [of] a partir de tipos ([ShoppingWidgetMarkResult] y
 * [ConnectionProblem]), no leyendo lo que contestó el servidor.
 */
sealed interface ShoppingWidgetStatus {

    /** Texto que se pinta. Vacío significa «no hay línea de estado». */
    val text: String

    /** Sin línea de estado: la lista se pinta tal cual. */
    data object Hidden : ShoppingWidgetStatus {
        override val text: String = ""
    }

    /** Hay una escritura en curso (toque optimista). */
    data object Saving : ShoppingWidgetStatus {
        override val text: String = "Guardando…"
    }

    /** El elemento acaba de entrar en el carro. */
    data class Marked(val itemText: String) : ShoppingWidgetStatus {
        override val text: String
            get() = if (itemText.isEmpty()) "En el carro" else "En el carro: «$itemText»"

        // Sin el texto del producto: es contenido del servidor.
        override fun toString(): String = "Marked(itemChars=${itemText.length})"
    }

    /** El `GET` fresco dijo que ya estaba marcado: no se mandó ningún `PUT`. */
    data object AlreadyChecked : ShoppingWidgetStatus {
        override val text: String = "Ya estaba en el carro"
    }

    /** No hay sesión guardada; platform decide cuándo es este caso. */
    data object NoSession : ShoppingWidgetStatus {
        override val text: String = "Configura tu servidor en Gastronomicón"
    }

    /** No hay ninguna lista cacheada todavía. */
    data object NoCache : ShoppingWidgetStatus {
        override val text: String = "Abre Gastronomicón y elige tu lista"
    }

    /** El almacén cifrado no responde ahora: la copia no se borra. */
    data object StorageUnavailable : ShoppingWidgetStatus {
        override val text: String = "No se pudo leer la lista ahora"
    }

    /** Sin red o plazo agotado; se sigue enseñando la última copia. */
    data object Offline : ShoppingWidgetStatus {
        override val text: String = "Sin conexión; se muestra lo último"
    }

    /** El servidor es http local y el móvil no va por la red de casa. */
    data object NeedsHomeNetwork : ShoppingWidgetStatus {
        override val text: String = "Conéctate a tu wifi o VPN de casa"
    }

    /** 401/403: la copia se conserva y Ajustes pide un token nuevo. */
    data object SessionExpired : ShoppingWidgetStatus {
        override val text: String = "Tu sesión ha caducado; abre Ajustes"
    }

    /** La lista ya no existe en Mealie: hay que borrar la copia. */
    data object ListGone : ShoppingWidgetStatus {
        override val text: String = "Esa lista ya no está; abre la app"
    }

    /** El elemento se borró en otro sitio entre el pintado y el toque. */
    data object ItemGone : ShoppingWidgetStatus {
        override val text: String = "Ese producto ya no estaba"
    }

    /** 5xx, respuesta inválida o dirección que no lleva a Mealie. */
    data object ServerNotResponding : ShoppingWidgetStatus {
        override val text: String = "Tu Mealie no responde; se muestra lo último"
    }

    /**
     * El `PUT` pudo aplicarse y no se sabe. No se reintenta solo: se revierte
     * la fila y se pide comprobar en la app.
     */
    data object Unconfirmed : ShoppingWidgetStatus {
        override val text: String = "Puede que no se haya guardado; abre la app para comprobarlo"
    }

    /** La lista no cabe en la caché del widget. */
    data object TooLarge : ShoppingWidgetStatus {
        override val text: String = "La lista es demasiado grande; abre la app"
    }

    /** El móvil está bloqueado: platform decide, el núcleo solo lo refleja. */
    data object Locked : ShoppingWidgetStatus {
        override val text: String = "Desbloquea para cambiar la lista"
    }

    /** El servidor rechazó el cambio de forma definitiva (4xx sin detalle útil). */
    data object Rejected : ShoppingWidgetStatus {
        override val text: String = "El servidor no aceptó el cambio"
    }

    /** La configuración del servidor no vale (http hacia algo que no es una IP local). */
    data object Settings : ShoppingWidgetStatus {
        override val text: String = "Revisa el servidor en Ajustes"
    }

    companion object {

        /** Estado que corresponde al desenlace de un toque. */
        fun of(result: ShoppingWidgetMarkResult): ShoppingWidgetStatus = when (result) {
            ShoppingWidgetMarkResult.Invalid -> Rejected
            ShoppingWidgetMarkResult.Locked -> Locked
            ShoppingWidgetMarkResult.InFlight -> Saving
            ShoppingWidgetMarkResult.ListGone -> ListGone
            ShoppingWidgetMarkResult.Unauthorized -> SessionExpired
            is ShoppingWidgetMarkResult.Marked ->
                if (result.alreadyChecked) AlreadyChecked else Marked(result.itemText)
            is ShoppingWidgetMarkResult.ItemGone -> ItemGone
            is ShoppingWidgetMarkResult.ReadFailed -> of(result.problem)
            is ShoppingWidgetMarkResult.NotApplied -> of(result.problem)
            is ShoppingWidgetMarkResult.Unconfirmed -> Unconfirmed
            ShoppingWidgetMarkResult.ServerRejected -> Rejected
        }

        /**
         * Texto para un [ConnectionProblem]. Un fallo de lectura nunca dice
         * «no se ha guardado»: eso solo lo sabe el desenlace de un `PUT`.
         */
        fun of(problem: ConnectionProblem): ShoppingWidgetStatus = when (problem) {
            ConnectionProblem.NoNetwork,
            ConnectionProblem.ServerNotResponding -> Offline
            ConnectionProblem.Unauthorized,
            ConnectionProblem.InvalidToken -> SessionExpired
            ConnectionProblem.LocalHttpNeedsHomeNetwork -> NeedsHomeNetwork
            ConnectionProblem.NotLocalAddress -> Settings
            ConnectionProblem.ServerError,
            ConnectionProblem.InvalidAddress,
            ConnectionProblem.SecureConnectionFailed,
            ConnectionProblem.Unknown -> ServerNotResponding
        }
    }
}
