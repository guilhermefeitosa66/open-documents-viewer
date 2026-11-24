package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

enum class InlineStyle { BOLD, ITALIC, CODE, STRIKE, LINK, UNDERLINE, COLOR, SIZE }

data class InlineSpan(
    val start: Int,
    val end: Int,
    val style: InlineStyle,
    val url: String? = null,
    /** ARGB, for [InlineStyle.COLOR] (documents from word processors). */
    val color: Int = 0,
    /** Relative text size, for [InlineStyle.SIZE]. */
    val scale: Float = 1f,
)

/** Text with styled ranges; turned into an AnnotatedString by the UI. */
data class InlineText(val text: String, val spans: List<InlineSpan> = emptyList()) {
    companion object {
        val EMPTY = InlineText("")
    }
}

enum class CellAlign { START, CENTER, END }

/**
 * A markdown document flattened into a list of blocks, so it can be shown in a lazy list: nested
 * structure (lists in lists, quotes) becomes [indent] and [quoteDepth] on each block.
 */
sealed interface MdBlock {
    val indent: Int
    val quoteDepth: Int
    /** The text a search looks at. */
    val plainText: String

    data class Heading(val level: Int, val content: InlineText, override val indent: Int, override val quoteDepth: Int) : MdBlock {
        override val plainText get() = content.text
    }

    data class Paragraph(val content: InlineText, override val indent: Int, override val quoteDepth: Int) : MdBlock {
        override val plainText get() = content.text
    }

    data class ListItem(
        val marker: String,
        val checked: Boolean?,
        val content: InlineText,
        override val indent: Int,
        override val quoteDepth: Int,
    ) : MdBlock {
        override val plainText get() = content.text
    }

    data class CodeBlock(val info: String, val code: String, override val indent: Int, override val quoteDepth: Int) : MdBlock {
        override val plainText get() = code
    }

    data class Table(
        val header: List<InlineText>,
        val rows: List<List<InlineText>>,
        val align: List<CellAlign>,
        override val indent: Int,
        override val quoteDepth: Int,
    ) : MdBlock {
        override val plainText get() = (listOf(header) + rows).joinToString("\n") { r -> r.joinToString(" | ") { it.text } }
    }

    data class Rule(override val indent: Int, override val quoteDepth: Int) : MdBlock {
        override val plainText get() = ""
    }

    data class Html(val raw: String, override val indent: Int, override val quoteDepth: Int) : MdBlock {
        override val plainText get() = raw
    }

    /** An image on its own line. Local images are loaded by the view; remote ones never. */
    data class Image(val src: String, val alt: String, override val indent: Int, override val quoteDepth: Int) : MdBlock {
        override val plainText get() = alt
    }
}

object MarkdownModel {
    private val parser: Parser by lazy {
        Parser.builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                    AutolinkExtension.create(),
                )
            )
            .build()
    }

    fun parse(markdown: String): List<MdBlock> {
        val doc = parser.parse(markdown)
        val out = ArrayList<MdBlock>()
        children(doc).forEach { block(it, 0, 0, out) }
        return out
    }

    private fun children(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

    private fun block(node: Node, indent: Int, quote: Int, out: MutableList<MdBlock>) {
        when (node) {
            is Heading -> out += MdBlock.Heading(node.level, inline(node), indent, quote)
            is Paragraph -> {
                val only = node.firstChild
                if (only is Image && only.next == null) out += MdBlock.Image(only.destination.orEmpty(), altOf(only), indent, quote)
                else out += MdBlock.Paragraph(inline(node), indent, quote)
            }
            is BlockQuote -> children(node).forEach { block(it, indent, quote + 1, out) }
            is BulletList -> children(node).forEach { if (it is ListItem) listItem(it, "•", indent, quote, out) }
            is OrderedList -> {
                var n = node.markerStartNumber ?: 1
                val delimiter = node.markerDelimiter ?: "."
                children(node).forEach { if (it is ListItem) listItem(it, "${n++}$delimiter", indent, quote, out) }
            }
            is FencedCodeBlock -> out += MdBlock.CodeBlock(node.info.orEmpty().trim(), node.literal.orEmpty().trimEnd('\n'), indent, quote)
            is IndentedCodeBlock -> out += MdBlock.CodeBlock("", node.literal.orEmpty().trimEnd('\n'), indent, quote)
            is ThematicBreak -> out += MdBlock.Rule(indent, quote)
            is HtmlBlock -> out += MdBlock.Html(node.literal.orEmpty().trimEnd('\n'), indent, quote)
            is TableBlock -> out += table(node, indent, quote)
            is LinkReferenceDefinition -> Unit
            else -> children(node).forEach { block(it, indent, quote, out) }
        }
    }

    private fun altOf(image: Image): String = buildString {
        fun walk(n: Node) {
            if (n is Text) append(n.literal)
            generateSequence(n.firstChild) { it.next }.forEach(::walk)
        }
        walk(image)
    }.ifEmpty { image.title.orEmpty() }

    private fun listItem(item: ListItem, marker: String, indent: Int, quote: Int, out: MutableList<MdBlock>) {
        var checked: Boolean? = null
        var content = InlineText.EMPTY
        var contentTaken = false
        val rest = ArrayList<Node>()
        for (child in children(item)) {
            when {
                child is TaskListItemMarker -> checked = child.isChecked
                !contentTaken && child is Paragraph -> {
                    // The marker can also sit inside the paragraph, depending on the parser version.
                    val marker0 = child.firstChild
                    if (marker0 is TaskListItemMarker) checked = marker0.isChecked
                    content = inline(child)
                    contentTaken = true
                }
                else -> { contentTaken = true; rest += child }
            }
        }
        out += MdBlock.ListItem(marker, checked, content, indent, quote)
        rest.forEach { block(it, indent + 1, quote, out) }
    }

    private fun table(node: TableBlock, indent: Int, quote: Int): MdBlock.Table {
        var header = emptyList<InlineText>()
        val rows = ArrayList<List<InlineText>>()
        var align = emptyList<CellAlign>()
        fun cells(row: TableRow) = children(row).filterIsInstance<TableCell>().toList()
        for (section in children(node)) {
            for (row in children(section).filterIsInstance<TableRow>()) {
                val cs = cells(row)
                if (section is TableHead) {
                    header = cs.map { inline(it) }
                    align = cs.map {
                        when (it.alignment) {
                            TableCell.Alignment.CENTER -> CellAlign.CENTER
                            TableCell.Alignment.RIGHT -> CellAlign.END
                            else -> CellAlign.START
                        }
                    }
                } else rows += cs.map { inline(it) }
            }
        }
        return MdBlock.Table(header, rows, align, indent, quote)
    }

    private fun inline(node: Node): InlineText {
        val sb = StringBuilder()
        val spans = ArrayList<InlineSpan>()
        fun visit(n: Node) {
            val start = sb.length
            when (n) {
                is Text -> sb.append(n.literal)
                is Code -> {
                    sb.append(n.literal)
                    spans += InlineSpan(start, sb.length, InlineStyle.CODE)
                }
                is SoftLineBreak -> sb.append(' ')
                is HardLineBreak -> sb.append('\n')
                is HtmlInline -> sb.append(n.literal)
                is TaskListItemMarker -> Unit
                is Image -> {
                    sb.append('[')
                    children(n).forEach(::visit)
                    sb.append(']')
                    spans += InlineSpan(start, sb.length, InlineStyle.ITALIC)
                }
                else -> {
                    children(n).forEach(::visit)
                    val style = when (n) {
                        is StrongEmphasis -> InlineStyle.BOLD
                        is Emphasis -> InlineStyle.ITALIC
                        is Strikethrough -> InlineStyle.STRIKE
                        is Link -> InlineStyle.LINK
                        else -> null
                    }
                    if (style != null && sb.length > start) {
                        spans += InlineSpan(start, sb.length, style, (n as? Link)?.destination)
                    }
                }
            }
        }
        children(node).forEach(::visit)
        return InlineText(sb.toString(), spans)
    }
}
