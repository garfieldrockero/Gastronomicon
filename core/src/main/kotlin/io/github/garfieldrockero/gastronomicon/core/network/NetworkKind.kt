package io.github.garfieldrockero.gastronomicon.core.network

/**
 * Tipo de la red por la que sale el móvil en este momento.
 *
 * El núcleo no conoce Android: la app lo traduce desde ConnectivityManager
 * (ver [ActiveNetworkProvider]) y aquí solo se decide qué significa.
 */
enum class NetworkKind {
    Wifi,
    Ethernet,
    Vpn,
    Cellular,

    /** Sin red activa. */
    None,

    /** Cualquier otra cosa que la app no sepa clasificar (Bluetooth, satélite, USB…). */
    Other,
}

/**
 * Lo implementa la app con ConnectivityManager. Se consulta antes de cada
 * salto de una petición http y no se guarda nada: la red puede cambiar entre
 * una petición y la siguiente, e incluso entre una redirección y su destino.
 *
 * Si la app no sabe qué red hay, tiene que devolver [NetworkKind.Other] o
 * [NetworkKind.None], nunca adivinar: los dos bloquean el HTTP en claro.
 */
fun interface ActiveNetworkProvider {
    fun current(): NetworkKind
}

/**
 * ¿Se puede mandar el token sin cifrar por esta red?
 *
 * HTTP en claro solo se admite hacia IP privadas (lo impone ServerUrl), pero
 * una IP privada no dice en qué red está: con datos móviles, «192.168.1.50»
 * es una dirección de la red del operador (o de nadie), no el servidor de
 * casa, y el token iría en claro hacia allí. Por eso solo valen las redes que
 * pueden ser la de casa:
 * - [NetworkKind.Wifi] y [NetworkKind.Ethernet]: la red local en sí.
 * - [NetworkKind.Vpn]: es como se llega a casa desde fuera (WireGuard,
 *   OpenVPN…). El tramo por Internet ya va cifrado por el túnel, y las IP
 *   privadas que se alcanzan por él son las de la red a la que lleva. Límite
 *   asumido: una VPN comercial también cuenta como Vpn, y ahí una IP privada
 *   es la red del proveedor de la VPN; el núcleo no puede distinguirlas y
 *   bloquear la VPN dejaría sin servidor a quien entra en casa desde fuera.
 *
 * Todo lo demás falla hacia el «no»: [NetworkKind.Cellular], [NetworkKind.None]
 * y [NetworkKind.Other], que es lo que la app devuelve cuando no está segura.
 * El «when» no tiene «else» para que un tipo nuevo obligue a decidir.
 */
fun NetworkKind.allowsLocalHttp(): Boolean = when (this) {
    NetworkKind.Wifi -> true
    NetworkKind.Ethernet -> true
    NetworkKind.Vpn -> true
    NetworkKind.Cellular -> false
    NetworkKind.None -> false
    NetworkKind.Other -> false
}
