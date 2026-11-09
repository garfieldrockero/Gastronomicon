package io.github.garfieldrockero.gastronomicon.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.garfieldrockero.gastronomicon.R
import io.github.garfieldrockero.gastronomicon.core.html.HtmlBlock
import io.github.garfieldrockero.gastronomicon.core.html.InlineSpan
import io.github.garfieldrockero.gastronomicon.core.html.ListItem
import io.github.garfieldrockero.gastronomicon.core.html.SanitizedHtml
import io.github.garfieldrockero.gastronomicon.ui.theme.Spacing

/**
 * Pinta el HTML que `core/` ya ha saneado: párrafos, listas, negrita, cursiva,
 * subrayado y saltos de línea. Solo componentes de Compose: no hay vistas web, ni
 * enlaces, ni nada que se pueda pulsar o ejecutar, porque el saneador no deja pasar
 * enlaces ni atributos.
 */
@Composable
fun SanitizedHtmlContent(
    html: SanitizedHtml,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        HtmlBlocks(blocks = html.blocks, style = style)
        if (html.truncated) {
            Text(
                text = stringResource(R.string.detail_html_truncated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HtmlBlocks(blocks: List<HtmlBlock>, style: TextStyle) {
    for (block in blocks) {
        when (block) {
            is HtmlBlock.Paragraph -> Text(text = annotate(block.spans), style = style)
            is HtmlBlock.UnorderedList -> HtmlItems(block.items, ordered = false, style = style)
            is HtmlBlock.OrderedList -> HtmlItems(block.items, ordered = true, style = style)
        }
    }
}

@Composable
private fun HtmlItems(items: List<ListItem>, ordered: Boolean, style: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                if (ordered) {
                    Text(
                        text = "${index + 1}.",
                        style = style,
                        modifier = Modifier.widthIn(min = MarkerMinWidth),
                    )
                } else {
                    // La viñeta es solo adorno: TalkBack no tiene que leerla.
                    Text(
                        text = "•",
                        style = style,
                        modifier = Modifier
                            .widthIn(min = MarkerMinWidth)
                            .clearAndSetSemantics { },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(text = annotate(item.spans), style = style)
                    if (item.children.isNotEmpty()) {
                        Column(modifier = Modifier.padding(start = Spacing.small)) {
                            HtmlBlocks(blocks = item.children, style = style)
                        }
                    }
                }
            }
        }
    }
}

private fun annotate(spans: List<InlineSpan>): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        when (span) {
            is InlineSpan.Text -> {
                val spanStyle = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.underline) TextDecoration.Underline else null,
                )
                withStyle(spanStyle) { append(span.text) }
            }
            InlineSpan.LineBreak -> append("\n")
        }
    }
}

private val MarkerMinWidth = 24.dp
