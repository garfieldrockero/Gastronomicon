package io.github.garfieldrockero.gastronomicon.core.html

/** Receptor de los tokens del [HtmlTokenizer]. */
internal interface HtmlTokenSink {
    /** Cuando pasa a true el tokenizador deja de leer. */
    val finished: Boolean

    /** Texto sin decodificar. */
    fun onText(raw: String)

    /** [name] va en minúsculas y recortado; los atributos no se entregan. */
    fun onStartTag(name: String, selfClosing: Boolean)

    fun onEndTag(name: String)
}

/**
 * Tokenizador de HTML escrito a mano, de una sola pasada y sin regex.
 *
 * Solo entrega nombres de etiqueta y texto: los atributos se recorren para
 * saber dónde acaba la etiqueta (un «>» dentro de comillas no la cierra) y se
 * tiran, porque nada del saneador los usa. Lo que no se puede cerrar con
 * seguridad (etiqueta o comentario sin terminar) se descarta hasta el final:
 * ante la duda, se pierde contenido y no se cuela nada.
 */
internal object HtmlTokenizer {

    // Un nombre más largo que esto no puede coincidir con ninguna etiqueta conocida.
    private const val MAX_NAME_LENGTH = 16

    /**
     * Elementos cuyo contenido es texto crudo en HTML: lo que hay dentro no son
     * etiquetas y se descarta entero hasta su cierre.
     */
    private val RAW_TEXT = setOf(
        "script", "style", "iframe", "noscript", "textarea", "title", "xmp", "noembed", "noframes",
    )

    private class TagScan {
        var name = ""
        var end = 0
        var selfClosing = false
        var complete = false
    }

    fun tokenize(s: String, sink: HtmlTokenSink) {
        val n = s.length
        val scan = TagScan()
        var i = 0
        while (i < n && !sink.finished) {
            if (s[i] != '<') {
                val next = s.indexOf('<', i).let { if (it < 0) n else it }
                sink.onText(s.substring(i, next))
                i = next
                continue
            }
            if (i + 1 >= n) {
                sink.onText("<")
                break
            }
            val c = s[i + 1]
            when {
                s.startsWith("<!--", i) -> {
                    i = skipComment(s, i)
                }
                // <!doctype>, <![CDATA[ y <?xml ?>: se tratan como comentarios.
                c == '!' || c == '?' -> i = skipPastGreaterThan(s, i + 2)
                c == '/' -> {
                    if (i + 2 < n && isAsciiLetter(s[i + 2])) {
                        scanTag(s, i + 2, scan)
                        if (!scan.complete) break
                        sink.onEndTag(scan.name)
                        i = scan.end
                    } else {
                        i = skipPastGreaterThan(s, i + 2)
                    }
                }
                isAsciiLetter(c) -> {
                    scanTag(s, i + 1, scan)
                    if (!scan.complete) break
                    sink.onStartTag(scan.name, scan.selfClosing)
                    i = scan.end
                    if (scan.name == "plaintext") {
                        i = n
                    } else if (scan.name in RAW_TEXT) {
                        i = findRawTextEnd(s, i, scan.name)
                    }
                }
                // Un «<» que no abre nada es texto, como hacen los navegadores.
                else -> {
                    sink.onText("<")
                    i++
                }
            }
        }
    }

    private fun scanTag(s: String, start: Int, tag: TagScan) {
        val n = s.length
        var i = start
        while (i < n && !isTagNameEnd(s[i])) i++
        tag.name = lowerAscii(s, start, minOf(i, start + MAX_NAME_LENGTH))
        tag.selfClosing = false
        tag.complete = false

        while (true) {
            while (i < n && isWhitespace(s[i])) i++
            if (i >= n) {
                tag.end = n
                return
            }
            val c = s[i]
            if (c == '>') {
                tag.complete = true
                tag.end = i + 1
                return
            }
            if (c == '/') {
                i++
                if (i < n && s[i] == '>') tag.selfClosing = true
                continue
            }
            // Un «=» inicial cuenta como parte del nombre, igual que en los navegadores.
            i++
            while (i < n && !isWhitespace(s[i]) && s[i] != '/' && s[i] != '>' && s[i] != '=') i++
            while (i < n && isWhitespace(s[i])) i++
            if (i < n && s[i] == '=') {
                i++
                while (i < n && isWhitespace(s[i])) i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val close = s.indexOf(s[i], i + 1)
                    if (close < 0) {
                        // Comilla sin cerrar: el resto del texto es valor, no hay etiqueta.
                        tag.end = n
                        return
                    }
                    i = close + 1
                } else {
                    while (i < n && !isWhitespace(s[i]) && s[i] != '>') i++
                }
            }
        }
    }

    /** Posición del cierre «</nombre» (sin consumirlo) o el final si no existe. */
    private fun findRawTextEnd(s: String, from: Int, name: String): Int {
        var idx = s.indexOf("</", from)
        while (idx >= 0) {
            val after = idx + 2 + name.length
            if (s.regionMatches(idx + 2, name, 0, name.length, ignoreCase = true) &&
                (after >= s.length || isTagNameEnd(s[after]))
            ) {
                return idx
            }
            idx = s.indexOf("</", idx + 2)
        }
        return s.length
    }

    /**
     * Posición tras el comentario que empieza en [start] («<!--»), según HTML5:
     * «<!-->» y «<!--->» son comentarios vacíos ya cerrados, y tanto «-->» como
     * «--!>» lo cierran. Si no hay cierre se descarta hasta el final. Hay que
     * imitar al navegador: si no, un comentario que él cierra aquí seguiría
     * abierto y se tragaría contenido legítimo (o al revés).
     */
    private fun skipComment(s: String, start: Int): Int {
        val body = start + 4
        if (s.startsWith(">", body)) return body + 1
        if (s.startsWith("->", body)) return body + 2
        // Un solo recorrido: buscar «-->» y «--!>» con dos indexOf por comentario
        // es cuadrático (el segundo recorre hasta el final del texto cada vez).
        var i = body
        val last = s.length - 2
        while (i < last) {
            if (s[i] == '-' && s[i + 1] == '-') {
                if (s[i + 2] == '>') return i + 3
                if (s[i + 2] == '!' && i + 3 < s.length && s[i + 3] == '>') return i + 4
            }
            i++
        }
        return s.length
    }

    private fun skipPastGreaterThan(s: String, from: Int): Int {
        val close = s.indexOf('>', from)
        return if (close < 0) s.length else close + 1
    }

    private fun lowerAscii(s: String, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        for (k in start until end) {
            val c = s[k]
            sb.append(if (c in 'A'..'Z') c + 32 else c)
        }
        return sb.toString()
    }

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun isWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    private fun isTagNameEnd(c: Char): Boolean = isWhitespace(c) || c == '/' || c == '>'
}
