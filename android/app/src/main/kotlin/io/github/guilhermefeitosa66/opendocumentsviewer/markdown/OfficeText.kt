package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

/** Builds an [InlineText] piece by piece (word processor readers). */
internal class InlineBuilder {
    private val sb = StringBuilder()
    private val spans = ArrayList<InlineSpan>()

    val length: Int get() = sb.length

    fun append(s: String, style: InlineSpan?) {
        val start = sb.length
        sb.append(s)
        style?.let { spans += it.copy(start = start, end = sb.length) }
    }

    fun appendStyled(s: String, styles: List<InlineSpan>) {
        val start = sb.length
        sb.append(s)
        for (st in styles) {
            // Merge with the previous span of the same look (runs split by spell checking...).
            val i = spans.indexOfLast { it.style == st.style && it.end == start && it.url == st.url && it.color == st.color && it.scale == st.scale }
            if (i >= 0) spans[i] = spans[i].copy(end = sb.length)
            else spans += st.copy(start = start, end = sb.length)
        }
    }

    fun build() = InlineText(sb.toString(), spans.toList())
}

internal fun InlineText.trimmed(): InlineText {
    val lead = text.length - text.trimStart().length
    val end = text.trimEnd().length
    if (lead == 0 && end == text.length) return this
    if (end <= lead) return InlineText("")
    return InlineText(
        text.substring(lead, end),
        spans.mapNotNull { s ->
            val a = (s.start - lead).coerceIn(0, end - lead)
            val b = (s.end - lead).coerceIn(0, end - lead)
            if (b > a) s.copy(start = a, end = b) else null
        },
    )
}

internal object OfficeText {
    /**
     * SIZE spans hold an absolute size while reading; here they become relative to the size most
     * text uses ([charsBySize]: size → characters), like RTF. Headings keep their own size.
     */
    fun scaleSizes(blocks: List<MdBlock>, charsBySize: Map<Int, Int>): List<MdBlock> {
        val body = charsBySize.maxByOrNull { it.value }?.key ?: return blocks
        fun fix(t: InlineText): InlineText {
            if (t.spans.none { it.style == InlineStyle.SIZE }) return t
            return t.copy(spans = t.spans.mapNotNull { s ->
                if (s.style != InlineStyle.SIZE) s else {
                    val r = (s.scale / body).coerceIn(0.6f, 2.4f)
                    if (r in 0.92f..1.08f) null else s.copy(scale = r)
                }
            })
        }
        return blocks.map { b ->
            when (b) {
                is MdBlock.Paragraph -> b.copy(content = fix(b.content))
                is MdBlock.ListItem -> b.copy(content = fix(b.content))
                is MdBlock.Heading -> b.copy(content = b.content.copy(spans = b.content.spans.filter { it.style != InlineStyle.SIZE }))
                is MdBlock.Table -> b.copy(header = b.header.map(::fix), rows = b.rows.map { r -> r.map(::fix) })
                else -> b
            }
        }
    }

    /** List numbers: "1", "a", "A", "i", "I" styles. */
    fun number(n: Int, fmt: String): String = DocxModel.format(n, fmt)
}
