package io.github.garfieldrockero.gastronomicon.core.html

/**
 * Convierte el HTML de las instrucciones de Mealie en un modelo pequeño
 * ([SanitizedHtml]) que la interfaz pinta sin WebView ni JavaScript.
 *
 * Lista blanca: p, br, b, strong, i, em, u, ul, ol, li, div y span (solo su
 * texto). Cualquier otra etiqueta se descarta conservando su texto, y los
 * enlaces quedan como texto plano sin dirección ni acción. Se descarta también
 * el contenido de script, style, iframe, object, embed, svg, math, form y
 * similares. Los atributos no se leen jamás.
 *
 * Además de lo que se descarta, la salida dice si había imágenes o vídeo
 * ([SanitizedHtml.omittedMedia]): el criterio de qué se tira no cambia, solo se
 * anota para que la interfaz pueda avisar de que ese paso tenía algo que la app
 * no pinta.
 *
 * Es seguro ante entrada hostil porque la salida no puede contener nada
 * ejecutable: solo cadenas ya limpias y banderas de estilo. Los límites de
 * tamaño y de profundidad solo protegen la memoria y el tiempo.
 */
object HtmlSanitizer {

    /** Caracteres máximos de entrada; lo que sobra se descarta. */
    const val MAX_INPUT_LENGTH = 100_000

    /** Elementos de la lista blanca abiertos a la vez; más allá se cae a texto plano. */
    const val MAX_DEPTH = 24

    /** Tamaño máximo del texto plano al que se cae si el anidado es excesivo. */
    const val MAX_FALLBACK_LENGTH = 20_000

    fun sanitize(html: String?): SanitizedHtml {
        if (html.isNullOrEmpty()) return SanitizedHtml.EMPTY

        var input: String = html
        var truncated = false
        if (input.length > MAX_INPUT_LENGTH) {
            var end = MAX_INPUT_LENGTH
            // No partir un par sustituto: dejaría un carácter suelto.
            if (Character.isHighSurrogate(input[end - 1])) end--
            input = input.substring(0, end)
            truncated = true
        }
        // Un NUL dentro de un nombre de etiqueta no debe esconderla al filtro.
        if (input.indexOf('\u0000') >= 0) input = input.replace("\u0000", "")

        val structured = TreeBuilder(structured = true)
        HtmlTokenizer.tokenize(input, structured)
        if (!structured.overflowed) {
            return SanitizedHtml(
                blocks = structured.finish(),
                truncated = truncated,
                omittedMedia = structured.omittedMedia,
            )
        }

        // Anidado excesivo: se falla cerrado a texto plano truncado. La marca de
        // imágenes o vídeo se mantiene: el primer recorrido ya vio esas etiquetas
        // y lo que se enseña sale del segundo, así que se suma lo de los dos.
        val plain = TreeBuilder(structured = false)
        HtmlTokenizer.tokenize(input, plain)
        return SanitizedHtml(
            blocks = plain.finish(),
            truncated = true,
            omittedMedia = structured.omittedMedia || plain.omittedMedia,
        )
    }

    /** Elementos cuyo contenido (y no solo la etiqueta) se descarta. */
    private val SKIP_CONTENT = setOf(
        "object", "applet", "svg", "math", "form", "template", "select", "button",
        "head", "video", "audio", "canvas",
    )

    /**
     * Etiquetas que aportan una imagen, un vídeo o su reproductor. Ninguna está
     * en la lista blanca: todas se descartan igual que antes; esta lista solo
     * sirve para encender [SanitizedHtml.omittedMedia]. `iframe` va incluido
     * porque Mealie mete así los vídeos de YouTube en un paso.
     */
    private val MEDIA_TAGS = setOf(
        "img", "picture", "source", "video", "audio", "iframe", "embed", "object", "svg",
    )

    private class ListFrame(val ordered: Boolean) {
        val items = ArrayList<ListItem>()
        val spans = ArrayList<InlineSpan>()
        val children = ArrayList<HtmlBlock>()
        var hasItem = false
    }

    private class TreeBuilder(private val structured: Boolean) : HtmlTokenSink {
        private var done = false
        override val finished: Boolean get() = done
        var overflowed = false
            private set

        /** Se ha descartado alguna imagen o vídeo; se publica en [SanitizedHtml.omittedMedia]. */
        var omittedMedia = false
            private set

        private val blocks = ArrayList<HtmlBlock>()
        private val paragraph = ArrayList<InlineSpan>()
        private val frames = ArrayList<ListFrame>()
        private val open = ArrayList<String>()
        private var bold = 0
        private var italic = 0
        private var underline = 0
        private var skipName: String? = null
        private var skipDepth = 0
        private var plainLength = 0

        // Racha de marcas combinantes que sobrevive entre llamadas a onText: si se
        // reiniciara en cada una, intercalar etiquetas vacías esquivaría el tope.
        private var combiningRun = 0

        override fun onText(raw: String) {
            if (skipName != null) return
            val text = cleanText(raw)
            if (text.isEmpty()) return
            if (!structured) {
                appendPlain(text)
                return
            }
            currentSpans().add(InlineSpan.Text(text, bold > 0, italic > 0, underline > 0))
            frames.lastOrNull()?.hasItem = true
        }

        override fun onStartTag(name: String, selfClosing: Boolean) {
            // Antes de cualquier descarte: importa que había una imagen o un
            // vídeo aunque estuviera dentro de un contenedor que se tira entero
            // (un <form>, por ejemplo), porque la persona ve el hueco igual.
            if (name in MEDIA_TAGS) omittedMedia = true
            skipName?.let {
                if (name == it) skipDepth++
                return
            }
            if (name in SKIP_CONTENT) {
                // Se ignora «/>»: tratarlo como vacío dejaría pasar contenido hostil.
                skipName = name
                skipDepth = 1
                return
            }
            if (!structured) {
                appendSeparator()
                return
            }
            when (name) {
                "br" -> lineBreak()
                "p" -> {
                    // <p> sin cerrar: el siguiente <p> lo cierra, como en los navegadores.
                    closeImpliedIfAny("p", "div", "li", "ul", "ol")
                    push(name)
                }
                "li" -> {
                    closeImpliedIfAny("li", "ul", "ol")
                    push(name)
                }
                "b", "strong", "i", "em", "u", "ul", "ol", "div", "span" -> push(name)
                else -> Unit
            }
        }

        override fun onEndTag(name: String) {
            skipName?.let {
                if (name == it && --skipDepth == 0) skipName = null
                return
            }
            if (!structured) {
                appendSeparator()
                return
            }
            if (name == "br") {
                lineBreak()
                return
            }
            val index = open.lastIndexOf(name)
            if (index < 0) return
            // Cerrar un elemento cierra también los que dejó abiertos dentro.
            while (open.size > index) closeElement(open.removeAt(open.lastIndex))
        }

        fun finish(): List<HtmlBlock> {
            while (open.isNotEmpty()) closeElement(open.removeAt(open.lastIndex))
            while (frames.isNotEmpty()) closeList()
            flushParagraph()
            return blocks
        }

        /** Cierra hasta [name] si está abierto más recientemente que cualquiera de [barriers]. */
        private fun closeImpliedIfAny(name: String, vararg barriers: String) {
            val index = open.lastIndexOf(name)
            if (index < 0) return
            if (barriers.any { it != name && open.lastIndexOf(it) > index }) return
            while (open.size > index) closeElement(open.removeAt(open.lastIndex))
        }

        private fun push(name: String) {
            if (open.size >= MAX_DEPTH) {
                overflowed = true
                done = true
                return
            }
            open.add(name)
            when (name) {
                "b", "strong" -> bold++
                "i", "em" -> italic++
                "u" -> underline++
                "p", "div" -> blockBreak()
                "ul", "ol" -> {
                    if (frames.isEmpty()) blockBreak()
                    frames.add(ListFrame(ordered = name == "ol"))
                }
                "li" -> {
                    val frame = frames.lastOrNull()
                    if (frame == null) {
                        blockBreak()
                    } else {
                        closeItem(frame)
                        frame.hasItem = true
                    }
                }
            }
        }

        private fun closeElement(name: String) {
            when (name) {
                "b", "strong" -> if (bold > 0) bold--
                "i", "em" -> if (italic > 0) italic--
                "u" -> if (underline > 0) underline--
                "p", "div" -> blockBreak()
                "li" -> frames.lastOrNull()?.let { closeItem(it) }
                "ul", "ol" -> closeList()
            }
        }

        private fun currentSpans(): MutableList<InlineSpan> = frames.lastOrNull()?.spans ?: paragraph

        private fun lineBreak() {
            currentSpans().add(InlineSpan.LineBreak)
            frames.lastOrNull()?.hasItem = true
        }

        /** Fin de párrafo: dentro de una lista solo separa con un salto, fuera abre bloque nuevo. */
        private fun blockBreak() {
            val frame = frames.lastOrNull()
            if (frame == null) {
                flushParagraph()
            } else if (frame.spans.isNotEmpty() && frame.spans.last() !is InlineSpan.LineBreak) {
                frame.spans.add(InlineSpan.LineBreak)
            }
        }

        private fun flushParagraph() {
            val spans = normalize(paragraph)
            paragraph.clear()
            if (spans.isNotEmpty()) blocks.add(HtmlBlock.Paragraph(spans))
        }

        private fun closeItem(frame: ListFrame) {
            if (!frame.hasItem) return
            val spans = normalize(frame.spans)
            val children = frame.children.toList()
            frame.spans.clear()
            frame.children.clear()
            frame.hasItem = false
            if (spans.isNotEmpty() || children.isNotEmpty()) frame.items.add(ListItem(spans, children))
        }

        private fun closeList() {
            val frame = frames.removeAt(frames.lastIndex)
            closeItem(frame)
            if (frame.items.isEmpty()) return
            val block = if (frame.ordered) {
                HtmlBlock.OrderedList(frame.items)
            } else {
                HtmlBlock.UnorderedList(frame.items)
            }
            val parent = frames.lastOrNull()
            if (parent != null) {
                parent.children.add(block)
                parent.hasItem = true
            } else {
                blocks.add(block)
            }
        }

        /** Una etiqueta separa palabras, pero no debe agotar el límite con una ristra de etiquetas. */
        private fun appendSeparator() {
            val last = paragraph.lastOrNull()
            if (last is InlineSpan.Text && !last.text.endsWith(' ')) paragraph.add(InlineSpan.Text(" "))
        }

        private fun appendPlain(text: String) {
            val room = MAX_FALLBACK_LENGTH - plainLength
            var piece = text
            if (piece.length > room) {
                var end = room
                if (end > 0 && Character.isHighSurrogate(piece[end - 1])) end--
                piece = piece.substring(0, end)
                done = true
            }
            if (piece.isEmpty()) return
            paragraph.add(InlineSpan.Text(piece))
            plainLength += piece.length
            if (plainLength >= MAX_FALLBACK_LENGTH) done = true
        }

        /**
         * Colapsa espacios, quita los de los extremos y los saltos sobrantes
         * (máximo dos seguidos, ninguno al principio ni al final) y une tramos
         * contiguos con el mismo estilo.
         */
        private fun normalize(raw: List<InlineSpan>): List<InlineSpan> {
            val out = ArrayList<InlineSpan>()
            // El tramo en curso se acumula en un StringBuilder: unir cadenas
            // inmutables de una en una sería cuadrático con miles de tramos de un
            // carácter (el tokenizador emite uno por cada «<» suelto).
            val pending = StringBuilder()
            var style: InlineSpan.Text? = null
            fun flush() {
                val current = style
                if (current != null && pending.isNotEmpty()) {
                    out.add(current.copy(text = pending.toString()))
                }
                pending.setLength(0)
                style = null
            }
            var atStart = true
            var breaks = 0
            for (span in raw) {
                when (span) {
                    InlineSpan.LineBreak -> {
                        flush()
                        trimTrailingSpace(out)
                        if (out.isEmpty() || breaks >= 2) continue
                        out.add(InlineSpan.LineBreak)
                        breaks++
                        atStart = true
                    }
                    is InlineSpan.Text -> {
                        var wasStart = atStart
                        val sb = StringBuilder(span.text.length)
                        for (ch in span.text) {
                            if (ch == ' ' || ch == '\n') {
                                if (!wasStart) {
                                    sb.append(' ')
                                    wasStart = true
                                }
                            } else {
                                sb.append(ch)
                                wasStart = false
                            }
                        }
                        if (sb.isEmpty()) continue
                        atStart = wasStart
                        breaks = 0
                        val current = style
                        if (current != null &&
                            current.bold == span.bold && current.italic == span.italic &&
                            current.underline == span.underline
                        ) {
                            pending.append(sb)
                        } else {
                            flush()
                            style = span
                            pending.append(sb)
                        }
                    }
                }
            }
            flush()
            while (out.isNotEmpty() && out.last() is InlineSpan.LineBreak) out.removeAt(out.lastIndex)
            trimTrailingSpace(out)
            return out
        }

        private fun trimTrailingSpace(out: MutableList<InlineSpan>) {
            while (out.isNotEmpty()) {
                val last = out.last()
                if (last !is InlineSpan.Text || !last.text.endsWith(' ')) return
                val trimmed = last.text.dropLast(1)
                if (trimmed.isEmpty()) {
                    out.removeAt(out.lastIndex)
                } else {
                    out[out.lastIndex] = last.copy(text = trimmed)
                    return
                }
            }
        }

        /** Decodifica entidades una sola vez y filtra caracteres de control, bidi e invisibles. */
        private fun cleanText(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                var consumed = 0
                var cp = -1
                if (raw[i] == '&') {
                    val match = HtmlEntities.matchAt(raw, i)
                    if (match != null) {
                        cp = match.codePoint
                        consumed = match.length
                    }
                }
                if (consumed == 0) {
                    cp = raw.codePointAt(i)
                    consumed = Character.charCount(cp)
                }
                i += consumed
                if (cp < 0) continue
                val mapped = PlainTextSanitizer.normalizeCodePoint(cp)
                if (mapped < 0) continue
                // Mismo tope de marcas combinantes que en el texto plano.
                if (!PlainTextSanitizer.acceptCombining(mapped, combiningRun)) continue
                combiningRun = PlainTextSanitizer.nextRun(mapped, combiningRun)
                sb.appendCodePoint(mapped)
            }
            return sb.toString()
        }
    }
}
