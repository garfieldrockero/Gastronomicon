package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblem
import java.time.Instant
import java.time.ZoneId

/**
 * Cuándo se puede enseñar la copia guardada de una receta y con qué fecha.
 *
 * Las dos cosas son reglas, no pintura: por eso viven aquí y la interfaz se
 * limita a usar el resultado.
 */

/**
 * Si con este fallo tiene sentido ofrecer la copia guardada.
 *
 * Solo cuenta como «sin conexión» que el móvil no haya podido hablar con el
 * servidor: sin red, sin respuesta (tiempo agotado o conexión rechazada), TLS
 * interceptado o salida http bloqueada por no estar en la red de casa. Enseñar
 * la copia con el aviso «Sin conexión» en cualquier otro caso sería mentir, y
 * además taparía lo que hay que arreglar:
 *
 * - 401/403 ([ConnectionProblem.Unauthorized]): el token o los permisos.
 * - 404: nunca llega aquí (el detalle y la lista lo tratan como «ya no está»).
 * - 5xx ([ConnectionProblem.ServerError]): el servidor está y contesta mal.
 * - [ConnectionProblem.InvalidAddress]: la dirección no resuelve o no lleva a
 *   un Mealie. Un 404 de la API cae también aquí, así que no se ofrece copia:
 *   sería decir «sin conexión» de un servidor que sí ha contestado.
 * - [ConnectionProblem.NotLocalAddress] e [ConnectionProblem.InvalidToken]: no
 *   se ha enviado nada; lo que falla es la configuración.
 * - [ConnectionProblem.Unknown]: no se sabe qué pasó; se falla cerrado.
 */
fun ConnectionProblem.allowsOfflineCopy(): Boolean = when (this) {
    ConnectionProblem.NoNetwork,
    ConnectionProblem.ServerNotResponding,
    ConnectionProblem.SecureConnectionFailed,
    ConnectionProblem.LocalHttpNeedsHomeNetwork -> true

    ConnectionProblem.Unauthorized,
    ConnectionProblem.ServerError,
    ConnectionProblem.InvalidAddress,
    ConnectionProblem.NotLocalAddress,
    ConnectionProblem.InvalidToken,
    ConnectionProblem.Unknown -> false
}

/**
 * Fecha de una copia guardada, ya en texto castellano para pintarla.
 *
 * La interfaz no calcula fechas: pide aquí el texto y lo mete en la frase
 * («Sin conexión · copia guardada hoy a las 20:14»). El resultado no lleva
 * zona horaria ni segundos: se lee de pie y con una mano.
 *
 * - Mismo día: «hoy a las 20:14».
 * - El día anterior: «ayer a las 9:05».
 * - Más atrás: «el 5 de noviembre de 2026».
 * - Una marca imposible (0 o negativa) da cadena vacía; quien pinte decide qué
 *   hacer sin fecha.
 *
 * [nowMillis] y [zone] se pasan para poder probarlo; en la app van por defecto.
 */
object OfflineCopyDate {

    // Meses escritos a mano, como en `MealPlanLabels`: la app es en castellano y así
    // el texto no cambia con la configuración regional del móvil.
    private val MONTHS = arrayOf(
        "enero",
        "febrero",
        "marzo",
        "abril",
        "mayo",
        "junio",
        "julio",
        "agosto",
        "septiembre",
        "octubre",
        "noviembre",
        "diciembre",
    )

    fun format(
        cachedAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (cachedAtMillis <= 0L) return ""
        val cached = Instant.ofEpochMilli(cachedAtMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = cached.toLocalDate()
        val time = "${cached.hour}:${cached.minute.toString().padStart(2, '0')}"
        return when {
            day == today -> "hoy a las $time"
            day == today.minusDays(1) -> "ayer a las $time"
            else -> "el ${day.dayOfMonth} de ${MONTHS[day.monthValue - 1]} de ${day.year}"
        }
    }
}
