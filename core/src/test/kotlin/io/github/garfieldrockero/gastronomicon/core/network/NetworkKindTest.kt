package io.github.garfieldrockero.gastronomicon.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la política de red para el HTTP en claro. Es la segunda mitad de
 * la frontera (la primera es la IP privada de ServerUrl): si deja pasar una
 * red de más, el token viaja sin cifrar por la red del operador.
 */
class NetworkKindTest {

    @Test
    fun `wifi, ethernet y VPN permiten http local`() {
        assertTrue(NetworkKind.Wifi.allowsLocalHttp())
        assertTrue(NetworkKind.Ethernet.allowsLocalHttp())
        // La VPN es como se llega a casa desde fuera.
        assertTrue(NetworkKind.Vpn.allowsLocalHttp())
    }

    @Test
    fun `datos moviles, sin red y otras no permiten http local`() {
        assertFalse(NetworkKind.Cellular.allowsLocalHttp())
        assertFalse(NetworkKind.None.allowsLocalHttp())
        // Lo que la app no sabe clasificar falla hacia el no.
        assertFalse(NetworkKind.Other.allowsLocalHttp())
    }

    @Test
    fun `solo tres tipos de red permiten http local`() {
        // Si alguien añade un tipo nuevo, el «when» obliga a decidir; esto
        // obliga además a que la decisión por defecto no sea «sí».
        val allowed = NetworkKind.entries.filter { it.allowsLocalHttp() }.toSet()
        assertEquals(setOf(NetworkKind.Wifi, NetworkKind.Ethernet, NetworkKind.Vpn), allowed)
    }
}
