package io.github.garfieldrockero.gastronomicon.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la frontera de «host local» que decide si se permite HTTP en
 * claro. Android no la impone por rangos de IP, así que si esta función deja
 * pasar algo de más, el token viaja sin cifrar por Internet: se prueban los
 * bordes exactos de cada rango y las formas que intentan colarse.
 */
class LocalNetworkTest {

    // ---------------------------------------------------------------- 10.0.0.0/8

    @Test
    fun `10 barra 8 se acepta en sus dos extremos`() {
        assertLocal("10.0.0.0")
        assertLocal("10.255.255.255")
        assertLocal("10.0.0.1")
    }

    @Test
    fun `justo fuera de 10 barra 8 se rechaza`() {
        assertNotLocal("9.255.255.255")
        assertNotLocal("11.0.0.0")
    }

    // ---------------------------------------------------------------- 172.16.0.0/12

    @Test
    fun `172 16 barra 12 se acepta en sus dos extremos`() {
        assertLocal("172.16.0.0")
        assertLocal("172.31.255.255")
        assertLocal("172.20.10.5")
    }

    @Test
    fun `justo fuera de 172 16 barra 12 se rechaza`() {
        assertNotLocal("172.15.255.255")
        assertNotLocal("172.32.0.0")
    }

    // ---------------------------------------------------------------- 192.168.0.0/16

    @Test
    fun `192 168 barra 16 se acepta en sus dos extremos`() {
        assertLocal("192.168.0.0")
        assertLocal("192.168.255.255")
        assertLocal("192.168.1.50")
    }

    @Test
    fun `justo fuera de 192 168 barra 16 se rechaza`() {
        assertNotLocal("192.167.255.255")
        assertNotLocal("192.169.0.0")
    }

    // ---------------------------------------------------------------- IPv4 no local

    @Test
    fun `bucle local, enlace local, cero y publicas se rechazan`() {
        assertNotLocal("127.0.0.1")
        assertNotLocal("127.255.255.255")
        assertNotLocal("169.254.1.1")
        assertNotLocal("0.0.0.0")
        assertNotLocal("8.8.8.8")
        assertNotLocal("255.255.255.255")
        // CGNAT (también Tailscale): no es red privada de la casa.
        assertNotLocal("100.64.0.1")
    }

    @Test
    fun `las IPv4 con formas raras se rechazan aunque parezcan privadas`() {
        assertNotLocal("010.0.0.1")
        assertNotLocal("192.168.01.1")
        assertNotLocal("10.0.0")
        assertNotLocal("10.0.0.0.1")
        assertNotLocal("10.0.0.256")
        assertNotLocal("10..0.1")
        assertNotLocal("167772161")
        assertNotLocal("10.0.0.1.")
        assertNotLocal("")
    }

    // ---------------------------------------------------------------- IPv6

    @Test
    fun `fc00 barra 7 se acepta`() {
        assertLocal("fc00::1")
        assertLocal("fd12:3456::1")
        assertLocal("fd00::5")
        assertLocal("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        assertLocal("fc00:0:0:0:0:0:0:0")
        assertLocal("fd00:0:0:0:0:0:0:1")
        assertLocal("fd00::192.168.1.1")
    }

    @Test
    fun `justo fuera de fc00 barra 7 se rechaza`() {
        assertNotLocal("fbff::1")
        assertNotLocal("fe00::1")
        assertNotLocal("fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
    }

    @Test
    fun `la comparacion es por bits y no por el texto del prefijo`() {
        // «fc» como texto pero el primer grupo es 0x00fc: no es ULA.
        assertNotLocal("fc::1")
        assertNotLocal("fd::1")
        // Con ceros a la izquierda sigue siendo 0x00fd.
        assertNotLocal("00fd::1")
    }

    @Test
    fun `enlace local, bucle local, documentacion y no especificada se rechazan`() {
        assertNotLocal("fe80::1")
        assertNotLocal("::1")
        assertNotLocal("::")
        assertNotLocal("2001:db8::1")
        assertNotLocal("2606:4700:4700::1111")
    }

    @Test
    fun `la IPv4 mapeada en IPv6 se rechaza aunque sea privada`() {
        assertNotLocal("::ffff:192.168.1.1")
        assertNotLocal("::ffff:10.0.0.1")
        assertNotLocal("::ffff:c0a8:101")
        assertNotLocal("::192.168.1.1")
    }

    @Test
    fun `las IPv6 malformadas se rechazan aunque empiecen por fd`() {
        assertNotLocal("fd00::1::2")
        assertNotLocal("fd00:::1")
        assertNotLocal("fd00:1")
        assertNotLocal("fd00:0:0:0:0:0:0:0:1")
        assertNotLocal("fd000::1")
        assertNotLocal("fd00::g")
        assertNotLocal("fd00::1%eth0")
        assertNotLocal("fd00:")
        assertNotLocal(":fd00::1")
        assertNotLocal("fd00::1.2.3.4.5")
        // «::» tiene que sustituir al menos un grupo.
        assertNotLocal("fd00:0:0:0::0:0:0:1")
        // La IPv4 incrustada solo puede ir al final.
        assertNotLocal("fd00:1.2.3.4::1")
    }

    @Test
    fun `la IPv6 en mayusculas no se acepta aqui porque llega ya en minusculas`() {
        assertNotLocal("FD00::1")
    }

    // ---------------------------------------------------------------- nombres

    @Test
    fun `los nombres local ya no cuentan como locales`() {
        // Decidido el 7-11-2026: un nombre lo resuelve la red en la que esté el
        // móvil, y en una wifi ajena «mealie.local» puede contestarlo cualquiera.
        assertNotLocal("mealie.local")
        assertNotLocal("cocina.casa.local")
        assertNotLocal("mi-servidor.local")
        assertNotLocal("nas2.local")
    }

    @Test
    fun `local sin etiqueta delante se rechaza`() {
        assertNotLocal("local")
        assertNotLocal(".local")
        assertNotLocal("..local")
        assertNotLocal("mealie..local")
    }

    @Test
    fun `los nombres que solo se parecen a local se rechazan`() {
        assertNotLocal("mealie.local.evil.com")
        assertNotLocal("mealielocal")
        assertNotLocal("mealie.localhost")
        assertNotLocal("localhost")
        assertNotLocal("mealie.locall")
        assertNotLocal("mealie-local")
        assertNotLocal("mealie.local.")
    }

    @Test
    fun `otros sufijos de red de casa y dominios publicos se rechazan`() {
        assertNotLocal("mealie.lan")
        assertNotLocal("mealie.home")
        assertNotLocal("mealie.home.arpa")
        assertNotLocal("mealie.internal")
        assertNotLocal("mealie.example.com")
        assertNotLocal("192.168.1.50.nip.io")
    }

    @Test
    fun `un nombre que acaba en una IP privada no es una IP`() {
        // La última etiqueta numérica no convierte el nombre en IPv4 literal.
        assertNotLocal("mealie.192.168.1.50")
        assertNotLocal("a.10.0.0.1")
    }

    @Test
    fun `los nombres en mayusculas tampoco cuentan`() {
        assertNotLocal("MEALIE.LOCAL")
        assertNotLocal("mealie.Local")
    }

    // ---------------------------------------------------------------- ayuda

    private fun assertLocal(host: String) {
        assertTrue("Se esperaba que «$host» contara como local", LocalNetwork.isLocalHost(host))
    }

    private fun assertNotLocal(host: String) {
        assertFalse("Se esperaba que «$host» no contara como local", LocalNetwork.isLocalHost(host))
    }
}
