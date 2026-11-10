package io.github.garfieldrockero.gastronomicon.core.html

/**
 * Tramo en línea de un bloque. Es el único contenido que sale del saneador: no
 * hay enlaces, URLs ni atributos, de modo que la interfaz no puede ejecutar ni
 * abrir nada que venga del servidor.
 */
sealed interface InlineSpan {

    /** Texto ya limpio, con los estilos que le afectan. */
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    ) : InlineSpan

    /** Salto de línea (`<br>`). */
    data object LineBreak : InlineSpan
}

/** Elemento de una lista; [children] son las listas anidadas dentro de él. */
data class ListItem(
    val spans: List<InlineSpan>,
    val children: List<HtmlBlock> = emptyList(),
)

/** Bloque de nivel superior que Compose sabe pintar. */
sealed interface HtmlBlock {
    data class Paragraph(val spans: List<InlineSpan>) : HtmlBlock

    data class UnorderedList(val items: List<ListItem>) : HtmlBlock

    data class OrderedList(val items: List<ListItem>) : HtmlBlock
}

/**
 * Resultado del saneado de un HTML.
 *
 * [truncated] avisa de que el contenido se ha recortado (entrada demasiado
 * grande o anidado demasiado profundo) para que la interfaz pueda indicarlo.
 *
 * [omittedMedia] avisa de que el HTML traía imágenes o vídeo (`img`, `iframe`,
 * `video`, `audio`…) que el saneador ha tirado, para que la interfaz pueda
 * decirlo en vez de dejar un hueco sin explicación. No cambia qué se descarta:
 * es información de lo que ya se descartaba. Al pasar por la caché offline se
 * pierde (allí solo se guarda el texto y las listas), así que una copia sin red
 * no puede mostrar ese aviso.
 */
data class SanitizedHtml(
    val blocks: List<HtmlBlock>,
    val truncated: Boolean = false,
    val omittedMedia: Boolean = false,
) {

    /**
     * Versión en texto plano, útil para accesibilidad, previsualizaciones y
     * pruebas. Los bloques se separan con una línea en blanco.
     */
    fun toPlainText(): String {
        val sb = StringBuilder()
        appendBlocks(sb, blocks, 0)
        return sb.toString().trimEnd()
    }

    private fun appendBlocks(sb: StringBuilder, list: List<HtmlBlock>, depth: Int) {
        val indent = "  ".repeat(depth)
        list.forEachIndexed { index, block ->
            if (depth == 0 && index > 0) sb.append('\n')
            when (block) {
                is HtmlBlock.Paragraph -> sb.append(indent).append(spansText(block.spans)).append('\n')
                is HtmlBlock.UnorderedList -> appendItems(sb, block.items, depth, ordered = false)
                is HtmlBlock.OrderedList -> appendItems(sb, block.items, depth, ordered = true)
            }
        }
    }

    private fun appendItems(sb: StringBuilder, items: List<ListItem>, depth: Int, ordered: Boolean) {
        val indent = "  ".repeat(depth)
        items.forEachIndexed { index, item ->
            val marker = if (ordered) "${index + 1}. " else "• "
            sb.append(indent).append(marker).append(spansText(item.spans)).append('\n')
            appendBlocks(sb, item.children, depth + 1)
        }
    }

    private fun spansText(spans: List<InlineSpan>): String {
        val sb = StringBuilder()
        for (span in spans) {
            when (span) {
                is InlineSpan.Text -> sb.append(span.text)
                InlineSpan.LineBreak -> sb.append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        val EMPTY = SanitizedHtml(emptyList(), truncated = false)
    }
}
