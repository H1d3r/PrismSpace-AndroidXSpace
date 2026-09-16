package com.yzddmr6.prismspace.prism.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing

// ---------------------------------------------------------------------------
// Minimal Markdown renderer for release notes (our own controlled format).
// Parser is pure and unit-tested; rendering is Compose-only.
// Supported blocks: #..### headings, -/* bullets, 1. numbered items, > quotes, --- dividers,
// paragraphs. Supported inline spans: **bold**, *italic*, `code`, [text](url), ~~strike~~.
// ---------------------------------------------------------------------------

sealed interface MarkdownSpan {
    data class Text(val text: String) : MarkdownSpan
    data class Bold(val text: String) : MarkdownSpan
    data class Italic(val text: String) : MarkdownSpan
    data class Code(val text: String) : MarkdownSpan
    data class Strike(val text: String) : MarkdownSpan
    data class Link(val text: String, val url: String) : MarkdownSpan
}

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Bullet(val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Numbered(val index: Int, val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Quote(val spans: List<MarkdownSpan>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

private val HEADING = Regex("""^(#{1,3})\s+(.*)$""")
private val BULLET = Regex("""^[-*]\s+(.*)$""")
private val NUMBERED = Regex("""^(\d+)[.)]\s+(.*)$""")
private val QUOTE = Regex("""^>\s?(.*)$""")
private val DIVIDER = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")

fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    val paragraph = StringBuilder()
    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(parseMarkdownSpans(text))
        paragraph.clear()
    }
    for (rawLine in markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        val line = rawLine.trimEnd()
        when {
            line.isBlank() -> flushParagraph()
            DIVIDER.matches(line) -> { flushParagraph(); blocks += MarkdownBlock.Divider }
            HEADING.find(line) != null -> {
                flushParagraph()
                val m = HEADING.find(line)!!
                blocks += MarkdownBlock.Heading(m.groupValues[1].length, parseMarkdownSpans(m.groupValues[2].trim()))
            }
            BULLET.find(line) != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(parseMarkdownSpans(BULLET.find(line)!!.groupValues[1].trim()))
            }
            NUMBERED.find(line) != null -> {
                flushParagraph()
                val m = NUMBERED.find(line)!!
                blocks += MarkdownBlock.Numbered(m.groupValues[1].toInt(), parseMarkdownSpans(m.groupValues[2].trim()))
            }
            QUOTE.find(line) != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(parseMarkdownSpans(QUOTE.find(line)!!.groupValues[1].trim()))
            }
            else -> if (paragraph.isNotEmpty()) paragraph.append(' ').append(line.trim()) else paragraph.append(line.trim())
        }
    }
    flushParagraph()
    return blocks
}

/** Inline tokenizer: earliest marker wins; `**`/`__`/`~~` are tried before their single-char forms. */
fun parseMarkdownSpans(text: String): List<MarkdownSpan> {
    val spans = ArrayList<MarkdownSpan>()
    var plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) { spans += MarkdownSpan.Text(plain.toString()); plain = StringBuilder() }
    }
    fun consumeMarker(i: Int, marker: String, make: (String) -> MarkdownSpan): Int? {
        val end = text.indexOf(marker, i + marker.length)
        if (end <= i + marker.length) return null       // empty or unterminated → literal
        flush()
        spans += make(text.substring(i + marker.length, end))
        return end + marker.length
    }
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("[", i) -> {
                val close = text.indexOf("](", i)
                val parenEnd = if (close > i) text.indexOf(')', close + 2) else -1
                if (close > i + 1 && parenEnd > close + 2) {
                    flush()
                    spans += MarkdownSpan.Link(text.substring(i + 1, close), text.substring(close + 2, parenEnd))
                    i = parenEnd + 1
                } else { plain.append(text[i]); i++ }
            }
            text.startsWith("**", i) -> i = consumeMarker(i, "**") { MarkdownSpan.Bold(it) } ?: run { plain.append(text[i]); i + 1 }
            text.startsWith("__", i) -> i = consumeMarker(i, "__") { MarkdownSpan.Bold(it) } ?: run { plain.append(text[i]); i + 1 }
            text.startsWith("~~", i) -> i = consumeMarker(i, "~~") { MarkdownSpan.Strike(it) } ?: run { plain.append(text[i]); i + 1 }
            text.startsWith("`", i) -> i = consumeMarker(i, "`") { MarkdownSpan.Code(it) } ?: run { plain.append(text[i]); i + 1 }
            text.startsWith("*", i) -> i = consumeMarker(i, "*") { MarkdownSpan.Italic(it) } ?: run { plain.append(text[i]); i + 1 }
            text.startsWith("_", i) -> i = consumeMarker(i, "_") { MarkdownSpan.Italic(it) } ?: run { plain.append(text[i]); i + 1 }
            else -> { plain.append(text[i]); i++ }
        }
    }
    flush()
    return spans
}

private const val LINK_ANNOTATION_TAG = "URL"

private fun spansToAnnotatedString(spans: List<MarkdownSpan>, linkStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        fun appendStyled(text: String, style: SpanStyle) = withStyle(style) { append(text) }
        for (span in spans) when (span) {
            is MarkdownSpan.Text -> append(span.text)
            is MarkdownSpan.Bold -> appendStyled(span.text, SpanStyle(fontWeight = FontWeight.Bold))
            is MarkdownSpan.Italic -> appendStyled(span.text, SpanStyle(fontStyle = FontStyle.Italic))
            is MarkdownSpan.Code -> appendStyled(span.text, SpanStyle(fontFamily = FontFamily.Monospace))
            is MarkdownSpan.Strike -> appendStyled(span.text, SpanStyle(textDecoration = TextDecoration.LineThrough))
            is MarkdownSpan.Link -> {
                pushStringAnnotation(LINK_ANNOTATION_TAG, span.url)
                appendStyled(span.text, linkStyle)
                pop()
            }
        }
    }

@Composable
private fun MarkdownSpansText(
    spans: List<MarkdownSpan>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    val text = remember(spans) {
        buildAnnotatedString {
            if (prefix != null) append(prefix)
            append(spansToAnnotatedString(spans, linkStyle))
        }
    }
    ClickableText(
        text = text,
        style = style.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier = modifier,
        onClick = { offset ->
            text.getStringAnnotations(LINK_ANNOTATION_TAG, offset, offset)
                .firstOrNull()?.let { onLinkClick(it.item) }
        },
    )
}

@Composable
fun MarkdownText(
    markdown: String,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PrismSpacing.Xs)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> MarkdownSpansText(
                    block.spans, onLinkClick,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is MarkdownBlock.Paragraph -> MarkdownSpansText(block.spans, onLinkClick)
                is MarkdownBlock.Bullet -> MarkdownSpansText(block.spans, onLinkClick, prefix = "• ")
                is MarkdownBlock.Numbered -> MarkdownSpansText(block.spans, onLinkClick, prefix = "${block.index}. ")
                is MarkdownBlock.Quote -> MarkdownSpansText(
                    block.spans, onLinkClick,
                    modifier = Modifier.padding(start = PrismSpacing.Md),
                    style = MaterialTheme.typography.bodySmall,
                )
                MarkdownBlock.Divider -> Divider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = PrismSpacing.Xs),
                )
            }
        }
    }
}
