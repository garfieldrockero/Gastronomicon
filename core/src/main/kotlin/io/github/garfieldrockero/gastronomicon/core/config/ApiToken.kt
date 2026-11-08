package io.github.garfieldrockero.gastronomicon.core.config

/**
 * Token de API de Mealie, tal como lo genera cada persona en su servidor.
 *
 * Es un secreto que viaja en la cabecera «Authorization» de cada petición, así
 * que [toString] está fijado a una máscara constante: ni un log, ni un mensaje
 * de error, ni una captura de pantalla pueden enseñar el valor (ni siquiera su
 * longitud). El valor real solo sale por [value] y por
 * [authorizationHeaderValue], que existe para montar la cabecera.
 *
 * Solo se admite ASCII imprimible: es lo que una cabecera HTTP acepta sin
 * depender de la implementación, y el HttpURLConnection de Android (OkHttp por
 * debajo) revienta con lo no ASCII lanzando una excepción cuyo mensaje puede
 * repetir el «Bearer <token>» entero.
 *
 * La validación vive en el propio constructor para que no exista ninguna ruta
 * que cree un ApiToken sin pasar por ella. La interfaz debe construir el token
 * con [fromInput], que recorta los extremos del pegado y después valida igual
 * de estricto.
 */
@JvmInline
value class ApiToken(val value: String) {

    init {
        if (value.isEmpty()) {
            throw IllegalArgumentException("El token está vacío: genéralo en tu Mealie y pégalo aquí.")
        }
        if (value.length > MAX_LENGTH) {
            throw IllegalArgumentException("El token es demasiado largo: Mealie no genera nada parecido.")
        }
        // Copiar la cabecera entera en vez de solo el token es el error más
        // humano; merece su propio mensaje antes que el genérico de espacios.
        if (value.startsWith("Bearer ", ignoreCase = true)) {
            throw IllegalArgumentException("Pega solo el token, sin la palabra «Bearer» delante.")
        }
        // Un espacio dentro del token casi siempre es un copiado a medias o un
        // pegado con formato; mejor un error claro que un 401 inexplicable.
        if (value.any { it.isWhitespace() || Character.isSpaceChar(it) }) {
            throw IllegalArgumentException(
                "El token no puede llevar espacios en medio ni saltos de línea. Vuelve a copiarlo.",
            )
        }
        // Caracteres de control o invisibles (BOM, U+202E): romperían la
        // cabecera HTTP o falsearían lo que se ve en pantalla.
        if (value.any { isControlOrInvisible(it) }) {
            throw IllegalArgumentException(
                "El token contiene caracteres de control o invisibles. Vuelve a copiarlo.",
            )
        }
        // Solo ASCII imprimible (0x21-0x7E, sin espacio): lo no ASCII se corta
        // aquí, con mensaje propio, antes de que llegue a una cabecera y sea la
        // implementación la que reviente repitiendo el valor en su excepción.
        if (value.any { it.code !in PRINTABLE_ASCII }) {
            throw IllegalArgumentException(
                "El token solo admite letras sin acentos, números y símbolos ASCII. Vuelve a copiarlo.",
            )
        }
    }

    /** Valor listo para la cabecera «Authorization»: único sitio al que el secreto debe salir. */
    fun authorizationHeaderValue(): String = "Bearer $value"

    /** Máscara fija: ni la longitud del token real se deja entrever. */
    override fun toString(): String = MASK

    companion object {
        /**
         * Punto de entrada para lo que escribe o pega la persona.
         *
         * Al copiar el token de Mealie casi siempre se arrastra un espacio o un
         * salto de línea de los extremos; rechazarlo con un error que no puede
         * repetir el valor deja a la persona mirando un token que parece
         * correcto. Aquí se recortan los extremos y después se valida igual de
         * estricto: el constructor no se relaja y un espacio en medio sigue
         * siendo un error (sería otro token).
         */
        fun fromInput(raw: String): ApiToken = ApiToken(raw.trim())

        /** 4096 sobra para cualquier token real y sigue siendo una cabecera razonable. */
        const val MAX_LENGTH: Int = 4096

        /**
         * ASCII imprimible sin el espacio (0x21-0x7E): letras sin acentos,
         * números y símbolos, que es lo que una cabecera HTTP acepta sin
         * depender de la implementación.
         */
        private val PRINTABLE_ASCII = 0x21..0x7E

        private const val MASK = "ApiToken(***)"
    }
}

/**
 * Control, formato (BOM, U+202E), uso privado, sustitutos y sin asignar: nada
 * de eso puede acabar dentro de una cabecera HTTP.
 */
private fun isControlOrInvisible(c: Char): Boolean {
    val type = Character.getType(c)
    return type == Character.CONTROL.toInt() ||
        type == Character.FORMAT.toInt() ||
        type == Character.PRIVATE_USE.toInt() ||
        type == Character.SURROGATE.toInt() ||
        type == Character.UNASSIGNED.toInt()
}
