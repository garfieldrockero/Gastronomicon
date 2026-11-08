package io.github.garfieldrockero.gastronomicon.core

/**
 * Decide si un host cuenta como «de la red local» para permitir HTTP en claro.
 *
 * Android no deja limitar el HTTP en claro por rangos de IP en la
 * configuración de seguridad de red, así que esta función es la única
 * frontera entre «el token viaja sin cifrar por la wifi de casa» y «el token
 * viaja sin cifrar por Internet». Por eso es deliberadamente estrecha y falla
 * hacia el «no»: cualquier forma que no se entienda del todo devuelve false.
 *
 * Admite solo:
 * - IPv4 literal en 10.0.0.0/8, 172.16.0.0/12 o 192.168.0.0/16;
 * - IPv6 literal en fc00::/7 (direcciones locales únicas, ULA).
 *
 * Fuera a propósito:
 * - 127.0.0.0/8, ::1 y «localhost»: en el móvil apuntan al propio móvil, no
 *   al servidor, y abrirían HTTP hacia cualquier proceso local.
 * - 169.254.0.0/16 y fe80::/10 (enlace local): exigen zona y no son
 *   direcciones estables de un servidor.
 * - IPv4 mapeada en IPv6 (::ffff:a.b.c.d): es otra forma de escribir una
 *   IPv4 y abriría un segundo camino hacia la misma comprobación; con una
 *   sola forma admitida no hay que razonar sobre cómo la trata cada pila de
 *   red. Al comparar bits ya queda fuera, porque su primer byte es 0.
 * - Cualquier nombre, «.local» incluido (decidido el 7-11-2026): lo que se
 *   comprueba aquí es texto, y un nombre se resuelve después a la IP que diga
 *   el DNS o el mDNS de la red en la que esté el móvil. En una wifi ajena,
 *   «mealie.local» puede contestarlo cualquiera, y una red puede resolver un
 *   nombre a una IP pública; solo una IP literal dice de verdad adónde va el
 *   token. Lo mismo vale para «.lan», «.home» o «.home.arpa».
 * - 100.64.0.0/10 (CGNAT, también Tailscale): no es red privada de la casa.
 *
 * Es pura sintaxis, sin DNS.
 */
internal object LocalNetwork {

    /**
     * [host] llega ya en la forma canónica de [ServerUrl]: minúsculas, sin
     * corchetes y sin punto final. Las mayúsculas devuelven false en lugar de
     * normalizarse aquí: la normalización es cosa de [ServerUrl] y esta
     * función no debe aceptar nada que esa forma no haya producido.
     */
    fun isLocalHost(host: String): Boolean {
        if (host.isEmpty()) return false
        return when {
            ':' in host -> isUniqueLocalIpv6(host)
            host.all { it in '0'..'9' || it == '.' } -> isPrivateIpv4(host)
            // Cualquier nombre: no local (ver el KDoc del objeto).
            else -> false
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = parseIpv4(host) ?: return false
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    /** Solo la forma decimal normal, igual que exige [ServerUrl]: sin ceros a la izquierda. */
    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val result = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3) return null
            if (!part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            result[index] = value
        }
        return result
    }

    /** fc00::/7: los siete bits altos del primer byte son 1111110. */
    private fun isUniqueLocalIpv6(host: String): Boolean {
        val bytes = parseIpv6(host) ?: return false
        return (bytes[0].toInt() and 0xFE) == 0xFC
    }

    /**
     * Expande la IPv6 a sus 16 bytes. Se hace a mano y no con InetAddress
     * porque InetAddress puede resolver DNS si no reconoce la forma y esta
     * función tiene que ser pura. Devuelve null ante cualquier forma dudosa.
     */
    private fun parseIpv6(text: String): ByteArray? {
        if ('%' in text) return null
        if (!text.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null

        val doubleColon = text.indexOf("::")
        if (doubleColon != -1 && text.indexOf("::", doubleColon + 1) != -1) return null

        val head: List<String>
        val tail: List<String>
        if (doubleColon == -1) {
            head = text.split(':')
            tail = emptyList()
        } else {
            val left = text.substring(0, doubleColon)
            val right = text.substring(doubleColon + 2)
            head = if (left.isEmpty()) emptyList() else left.split(':')
            tail = if (right.isEmpty()) emptyList() else right.split(':')
        }

        val headWords = parseGroups(head, allowIpv4Last = tail.isEmpty() && doubleColon == -1)
            ?: return null
        val tailWords = parseGroups(tail, allowIpv4Last = true) ?: return null

        val total = headWords.size + tailWords.size
        if (doubleColon == -1) {
            if (total != 8) return null
        } else if (total > 7) {
            // «::» tiene que sustituir al menos un grupo de ceros.
            return null
        }

        val words = IntArray(8)
        for ((i, word) in headWords.withIndex()) words[i] = word
        val offset = 8 - tailWords.size
        for ((i, word) in tailWords.withIndex()) words[offset + i] = word

        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[2 * i] = (words[i] shr 8).toByte()
            bytes[2 * i + 1] = (words[i] and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Convierte grupos hexadecimales en palabras de 16 bits. Una IPv4 con
     * puntos solo vale como último grupo de la dirección y cuenta como dos.
     */
    private fun parseGroups(groups: List<String>, allowIpv4Last: Boolean): List<Int>? {
        val words = ArrayList<Int>(8)
        for ((index, group) in groups.withIndex()) {
            if ('.' in group) {
                if (!allowIpv4Last || index != groups.lastIndex) return null
                val octets = parseIpv4(group) ?: return null
                words.add((octets[0] shl 8) or octets[1])
                words.add((octets[2] shl 8) or octets[3])
            } else {
                if (group.isEmpty() || group.length > 4) return null
                if (!group.all { it in '0'..'9' || it in 'a'..'f' }) return null
                words.add(group.toInt(16))
            }
        }
        return words
    }
}
