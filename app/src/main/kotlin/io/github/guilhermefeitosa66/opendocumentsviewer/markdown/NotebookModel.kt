package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Jupyter notebook as document blocks: markdown cells rendered, code cells highlighted in the
 * kernel's language, and their outputs (text, errors, tables from HTML, PNG/JPEG images).
 * Nothing is executed.
 */
object NotebookModel {
    class Result(val blocks: List<MdBlock>, val partial: Boolean)

    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    fun parse(json: String): Result {
        val root = JSONObject(json)
        val meta = root.optJSONObject("metadata")
        val language = meta?.optJSONObject("kernelspec")?.optString("language")?.takeIf { it.isNotBlank() }
            ?: meta?.optJSONObject("language_info")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "python"
        val cells = root.optJSONArray("cells") ?: JSONArray()
        val out = ArrayList<MdBlock>()
        var partial = false
        for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            val source = text(cell.opt("source"))
            when (cell.optString("cell_type")) {
                "markdown" -> out += MarkdownModel.parse(source)
                "raw" -> if (source.isNotBlank()) out += MdBlock.CodeBlock("", source.trimEnd('\n'), 0, 0)
                "code" -> {
                    val count = cell.opt("execution_count").let { if (it is Number) it.toInt() else null }
                    val label = if (count != null) "In [$count]:" else "In [ ]:"
                    out += MdBlock.Paragraph(InlineText(label, listOf(InlineSpan(0, label.length, InlineStyle.CODE))), 0, 0)
                    if (source.isNotBlank()) out += MdBlock.CodeBlock(language, source.trimEnd('\n'), 0, 0)
                    val outputs = cell.optJSONArray("outputs") ?: JSONArray()
                    for (k in 0 until outputs.length()) {
                        val o = outputs.optJSONObject(k) ?: continue
                        if (!output(o, out)) partial = true
                    }
                }
            }
        }
        return Result(out, partial)
    }

    /** Adds one output; false when it had nothing we can show. */
    private fun output(o: JSONObject, out: MutableList<MdBlock>): Boolean {
        when (o.optString("output_type")) {
            "stream" -> {
                val t = text(o.opt("text")).replace(ansi, "").trimEnd('\n')
                if (t.isNotEmpty()) out += MdBlock.CodeBlock("output", t, 1, 0)
                return true
            }
            "error" -> {
                val trace = o.optJSONArray("traceback")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }
                val t = (trace ?: (o.optString("ename") + ": " + o.optString("evalue"))).replace(ansi, "").trimEnd('\n')
                out += MdBlock.CodeBlock("error", t, 1, 0)
                return true
            }
            "execute_result", "display_data" -> {
                val data = o.optJSONObject("data") ?: return false
                for (mime in listOf("image/png", "image/jpeg", "image/gif")) {
                    if (data.has(mime)) {
                        val b64 = text(data.opt(mime)).replace("\n", "")
                        out += MdBlock.Image("data:$mime;base64,$b64", "output", 1, 0)
                        return true
                    }
                }
                if (data.has("image/svg+xml") || data.has("application/vnd.jupyter.widget-view+json") || data.has("application/javascript")) {
                    if (!data.has("text/plain") && !data.has("text/html")) return false
                }
                if (data.has("text/markdown")) { out += MarkdownModel.parse(text(data.opt("text/markdown"))).map { indent(it) }; return true }
                if (data.has("text/html")) {
                    val html = HtmlModel.parse(text(data.opt("text/html"))).blocks
                    // pandas DataFrames and similar arrive as HTML tables.
                    if (html.any { it is MdBlock.Table }) { out += html.map { indent(it) }; return true }
                }
                if (data.has("text/plain")) {
                    out += MdBlock.CodeBlock("output", text(data.opt("text/plain")).replace(ansi, "").trimEnd('\n'), 1, 0)
                    return true
                }
                return false
            }
        }
        return true
    }

    private fun indent(b: MdBlock): MdBlock = when (b) {
        is MdBlock.Heading -> b.copy(indent = b.indent + 1)
        is MdBlock.Paragraph -> b.copy(indent = b.indent + 1)
        is MdBlock.ListItem -> b.copy(indent = b.indent + 1)
        is MdBlock.CodeBlock -> b.copy(indent = b.indent + 1)
        is MdBlock.Table -> b.copy(indent = b.indent + 1)
        is MdBlock.Rule -> b.copy(indent = b.indent + 1)
        is MdBlock.Html -> b.copy(indent = b.indent + 1)
        is MdBlock.Image -> b.copy(indent = b.indent + 1)
    }

    /** Notebook strings are either a string or a list of lines. */
    private fun text(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> buildString { for (i in 0 until v.length()) append(v.optString(i)) }
        else -> ""
    }
}
