package io.github.garfieldrockero.gastronomicon.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.garfieldrockero.gastronomicon.core.network.ActiveNetworkProvider
import io.github.garfieldrockero.gastronomicon.core.network.NetworkKind

/**
 * Traduce la red activa de Android a [NetworkKind]. Sin estado: se consulta en
 * cada llamada, porque la red puede cambiar entre dos peticiones.
 *
 * Usa `activeNetwork` porque es la red por la que sale ahora el tráfico de la
 * app; mirar todas las redes conectadas podría aprobar la wifi de casa mientras
 * las peticiones salen por datos móviles.
 *
 * `TRANSPORT_VPN` se comprueba primero porque una VPN declara también el
 * transporte de la red de debajo (una VPN sobre datos móviles marca VPN y
 * CELLULAR a la vez). Si se mirase el wifi o los datos antes, se clasificaría
 * mal el túnel.
 *
 * Falla hacia el «no»: ante cualquier fallo o duda devuelve [NetworkKind.None]
 * u [NetworkKind.Other], que bloquean el HTTP en claro. Necesita
 * `ACCESS_NETWORK_STATE`, que ya está en el manifiesto.
 */
class AndroidActiveNetworkProvider(context: Context) : ActiveNetworkProvider {

    // applicationContext para no retener una Activity.
    private val appContext: Context = context.applicationContext

    override fun current(): NetworkKind = try {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val capabilities = if (manager != null && network != null) {
            manager.getNetworkCapabilities(network)
        } else {
            null
        }
        when {
            capabilities == null -> NetworkKind.None
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.Vpn
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
            else -> NetworkKind.Other
        }
    } catch (e: SecurityException) {
        NetworkKind.None
    } catch (e: RuntimeException) {
        NetworkKind.None
    }
}
