package io.github.garfieldrockero.gastronomicon.core.offline

import io.github.garfieldrockero.gastronomicon.core.html.HtmlBlock
import io.github.garfieldrockero.gastronomicon.core.html.HtmlSanitizer
import io.github.garfieldrockero.gastronomicon.core.html.InlineSpan
import io.github.garfieldrockero.gastronomicon.core.html.ListItem
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml

/**
 * Convierte [SanitizedHtml] a un HTML mínimo para guardarlo en disco.
 *
 * No se guarda la estructura del modelo porque habría que volver a validarla a
 * mano; guardando HTML se reutiliza [HtmlSanitizer] al leer, que es el mismo
 * filtro que protege la app del servidor. La escritura solo emite etiquetas de
 * la lista blanca (`p`, `br`, `b`, `i`, `u`, `ul`, `ol`, `li`) y escapa el
 * texto, así que el resultado siempre es reparable por el saneador.
 *
 * Devuelve null si el HTML pasaría del tope de [HtmlSanitizer] o si el modelo
 * viniera absurdamente anidado: en ese caso la entrada no se guarda.
 */
internal object HtmlWriter {

    /** Margen de anidado por encima del que acepta el saneador. */
    private const val MAX_DEPTH = 32

    fun toHtml(html: SanitizedHtml, limit: Int = HtmlSanitizer.MAX_INPUT_LENGTH): String? {
        val writer = Writer(limit)
        return writer.write(html.blocks)
    }

    private class Writer(private val limit: Int) {

        private val out = StringBuilder()
        private var overflow = false

        fun write(blocks: List<HtmlBlock>): String? {
            appendBlocks(blocks, 0)
            return if (overflow) null else out.toString()
        }

        private fun append(text: String) {
            if (overflow) return
            if (out.length + text.length > limit) {
                overflow = true
                return
            }
            out.append(text)
        }

        private fun appendBlocks(blocks: List<HtmlBlock>, depth: Int) {
            if (overflow) return
            if (depth > MAX_DEPTH) {
                overflow = true
                return
            }
            for (block in blocks) {
                when (block) {
                    is HtmlBlock.Paragraph -> {
                        if (block.spans.isEmpty()) continue
                        append("<p>")
                        appendSpans(block.spans)
                        append("</p>")
                    }
                    is HtmlBlock.UnorderedList -> appendList(block.items, ordered = false, depth = depth)
                    is HtmlBlock.OrderedList -> appendList(block.items, ordered = true, depth = depth)
                }
            }
        }

        private fun appendList(items: List<ListItem>, ordered: Boolean, depth: Int) {
            if (items.isEmpty()) return
            append(if (ordered) "<ol>" else "<ul>")
            for (item in items) appendItem(item, depth + 1)
            append(if (ordered) "</ol>" else "</ul>")
        }

        private fun appendItem(item: ListItem, depth: Int) {
            append("<li>")
            appendSpans(item.spans)
            appendBlocks(item.children, depth)
            append("</li>")
        }

        private fun appendSpans(spans: List<InlineSpan>) {
            for (span in spans) {
                when (span) {
                    InlineSpan.LineBreak -> append("<br>")
                    is InlineSpan.Text -> appendText(span)
                }
            }
        }

        private fun appendText(span: InlineSpan.Text) {
            if (span.bold) append("<b>")
            if (span.italic) append("<i>")
            if (span.underline) append("<u>")
            append(escape(span.text))
            if (span.underline) append("</u>")
            if (span.italic) append("</i>")
            if (span.bold) append("</b>")
        }

        private fun escape(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
