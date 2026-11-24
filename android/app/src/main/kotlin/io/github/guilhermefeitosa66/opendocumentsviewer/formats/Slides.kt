package io.github.guilhermefeitosa66.opendocumentsviewer.formats

/**
 * The presentation model shared by the PPTX, ODP and PPT readers, drawn by the slide view.
 * Positions and sizes are in EMU (914400 per inch, 12700 per point). Colors are ARGB, 0 = none.
 * Pictures are paths inside the document's archive (read when drawn).
 */
class SlideRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Points. */
    val size: Float = 18f,
    val color: Int = 0,
    val mono: Boolean = false,
)

class SlideParagraph(
    val runs: List<SlideRun>,
    /** 'l', 'c', 'r', 'j'. */
    val align: Char = 'l',
    val level: Int = 0,
    val bullet: String? = null,
) {
    val text: String get() = runs.joinToString("") { it.text }
}

class SlideShape(
    /** rect, roundRect, ellipse, line, image, table, text. */
    val kind: String,
    var x: Long, var y: Long, var w: Long, var h: Long,
    val fill: Int = 0,
    val line: Int = 0,
    /** Points. */
    val lineWidth: Float = 1f,
    /** Archive path of the picture, for "image" (or a fill picture). */
    val image: String? = null,
    val paragraphs: List<SlideParagraph> = emptyList(),
    /** 't', 'm', 'b'. */
    val anchor: Char = 't',
    /** Table: rows of cells, each a list of paragraphs. */
    val rows: List<List<List<SlideParagraph>>> = emptyList(),
    val colWidths: List<Long> = emptyList(),
    /** Degrees. */
    val rotation: Float = 0f,
    /** Left, top, right, bottom, EMU. */
    val insets: LongArray = longArrayOf(91440, 45720, 91440, 45720),
)

class Slide(
    val shapes: List<SlideShape>,
    val background: Int = 0xFFFFFFFF.toInt(),
    val backgroundImage: String? = null,
    val notes: String = "",
    val title: String = "",
    val hidden: Boolean = false,
) {
    fun text(): String = buildList {
        for (s in shapes) {
            s.paragraphs.forEach { add(it.text) }
            s.rows.forEach { r -> r.forEach { c -> c.forEach { add(it.text) } } }
        }
    }.filter { it.isNotBlank() }.joinToString("\n")
}

class Presentation(
    val width: Long = 9144000,
    val height: Long = 6858000,
    val slides: List<Slide>,
    /** Only part of the content could be read (older formats): a notice says so. */
    val partial: Boolean = false,
) {
    /** Titles, text and notes of every slide: the source view and "Copy all". */
    fun plainText(): String = buildString {
        slides.forEachIndexed { i, s ->
            append("# ").append(i + 1).append(". ").append(s.title.ifBlank { "—" }).append('\n')
            val body = s.text()
            if (body.isNotBlank()) append(body).append('\n')
            if (s.notes.isNotBlank()) append("\n> ").append(s.notes.replace("\n", "\n> ")).append('\n')
            append('\n')
        }
    }
}
