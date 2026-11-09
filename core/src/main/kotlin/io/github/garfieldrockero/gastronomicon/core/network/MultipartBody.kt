package io.github.garfieldrockero.gastronomicon.core.network

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Montaje a mano de un cuerpo `multipart/form-data` (RFC 7578).
 *
 * Se hace aquí y no con una librería porque la regla del proyecto es no añadir
 * dependencias y el formato es corto. Lo delicado es que nada de lo que va
 * dentro pueda romper la estructura: los nombres de campo y de fichero salen
 * de una lista blanca de caracteres (jamás de texto libre) y el delimitador es
 * aleatorio y se comprueba que no aparece en el contenido.
 */
object MultipartBody {

    /** Cuerpo listo y el valor exacto de la cabecera `Content-Type` que le corresponde. */
    class Built(val contentType: String, val bytes: ByteArray) {
        // Sin los bytes: pueden ser una foto.
        override fun toString(): String = "MultipartBody.Built(bytes=${bytes.size})"
    }

    sealed interface Part {
        val name: String

        /** Campo de texto, en UTF-8. */
        class Field(override val name: String, val value: String) : Part

        /** Fichero: [filename] y [mimeType] los pone quien llama con valores propios, no del usuario. */
        class File(
            override val name: String,
            val filename: String,
            val mimeType: String,
            val bytes: ByteArray,
        ) : Part
    }

    private val random = SecureRandom()
    private val SAFE_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private val SAFE_MIME = Regex("^[a-z0-9]+/[a-z0-9.+-]{1,64}$")
    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

    /**
     * Monta las partes. Lanza IllegalArgumentException (mensaje fijo) si un
     * nombre o un tipo no pasa la lista blanca. [boundary] solo se pasa en las
     * pruebas; en producción es aleatorio.
     */
    fun build(parts: List<Part>, boundary: String = newBoundary()): Built {
        require(parts.isNotEmpty()) { "Un cuerpo multipart necesita al menos una parte." }
        require(BOUNDARY.matches(boundary)) { "El delimitador multipart no es válido." }
        val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        for (part in parts) {
            require(SAFE_NAME.matches(part.name)) { "El nombre de un campo multipart no es válido." }
            out.write(delimiter)
            out.write(CRLF)
            when (part) {
                is Part.Field -> {
                    val value = part.value.toByteArray(Charsets.UTF_8)
                    ensureNoDelimiter(value, delimiter)
                    out.write(ascii("Content-Disposition: form-data; name=\"${part.name}\""))
                    out.write(CRLF)
                    out.write(CRLF)
                    out.write(value)
                }
                is Part.File -> {
                    require(SAFE_NAME.matches(part.filename)) { "El nombre de un fichero multipart no es válido." }
                    require(SAFE_MIME.matches(part.mimeType)) { "El tipo de un fichero multipart no es válido." }
                    ensureNoDelimiter(part.bytes, delimiter)
                    out.write(
                        ascii("Content-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.filename}\""),
                    )
                    out.write(CRLF)
                    out.write(ascii("Content-Type: ${part.mimeType}"))
                    out.write(CRLF)
                    out.write(CRLF)
                    out.write(part.bytes)
                }
            }
            out.write(CRLF)
        }
        out.write(delimiter)
        out.write("--".toByteArray(Charsets.US_ASCII))
        out.write(CRLF)
        return Built("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    private val BOUNDARY = Regex("^[A-Za-z0-9]{16,70}$")

    private fun newBoundary(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val hex = StringBuilder("gastronomicon")
        for (b in bytes) hex.append("0123456789abcdef"[(b.toInt() shr 4) and 0x0F]).append("0123456789abcdef"[b.toInt() and 0x0F])
        return hex.toString()
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    /**
     * Con 192 bits aleatorios es imposible que coincida por azar; la comprobación
     * cubre un delimitador fijado a mano (pruebas) y deja el fallo explícito en
     * vez de producir un cuerpo que el servidor leería a medias.
     */
    private fun ensureNoDelimiter(content: ByteArray, delimiter: ByteArray) {
        if (indexOf(content, delimiter) >= 0) {
            throw IllegalArgumentException("El contenido de una parte multipart contiene el delimitador.")
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        var i = 0
        val last = haystack.size - needle.size
        while (i <= last) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }
}
