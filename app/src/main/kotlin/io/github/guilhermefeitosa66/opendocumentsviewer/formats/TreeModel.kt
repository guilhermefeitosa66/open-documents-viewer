package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

enum class NodeKind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, ELEMENT, ATTRIBUTE, TEXT }

/** One node of a JSON/YAML/XML tree. [path] is what "copy path" puts on the clipboard. */
class TreeNode(
    val id: Int,
    val key: String?,
    val kind: NodeKind,
    val value: String?,
    val path: String,
    val children: List<TreeNode>,
) {
    val isContainer: Boolean get() = children.isNotEmpty() || kind == NodeKind.OBJECT || kind == NodeKind.ARRAY || kind == NodeKind.ELEMENT
}

class TreeDocument(val root: TreeNode, val nodeCount: Int)

/** A visible row of the tree: the node and its depth. */
data class TreeLine(val node: TreeNode, val depth: Int)

object TreeModel {
    const val MAX_DEPTH = 1000

    /** Rows shown for the expanded set (root's children at depth 0). */
    fun visible(doc: TreeDocument, expanded: Set<Int>): List<TreeLine> {
        val out = ArrayList<TreeLine>()
        fun walk(n: TreeNode, depth: Int) {
            for (c in n.children) {
                out += TreeLine(c, depth)
                if (c.id in expanded) walk(c, depth + 1)
            }
        }
        walk(doc.root, 0)
        return out
    }

    /** Small documents open fully expanded; big ones show the first two levels. */
    fun initialExpanded(doc: TreeDocument): Set<Int> {
        val out = HashSet<Int>()
        fun walk(n: TreeNode, depth: Int) {
            if (doc.nodeCount > 300 && depth >= 1) return
            for (c in n.children) if (c.children.isNotEmpty()) { out += c.id; walk(c, depth + 1) }
        }
        walk(doc.root, 0)
        return out
    }

    // ---- Building ---------------------------------------------------------------------------

    private class Builder {
        var next = 0
        fun node(key: String?, kind: NodeKind, value: String?, path: String, children: List<TreeNode> = emptyList()) =
            TreeNode(next++, key, kind, value, path, children)
    }

    private val simpleKey = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    private fun childPath(parent: String, key: String) = if (simpleKey.matches(key)) "$parent.$key" else "$parent[\"${key.replace("\"", "\\\"")}\"]"

    /** From already-parsed values (YAML via snakeyaml-engine): Map, List, scalars. */
    fun fromValues(roots: List<Any?>): TreeDocument {
        val b = Builder()
        fun build(key: String?, v: Any?, path: String, depth: Int): TreeNode {
            if (depth > MAX_DEPTH) return b.node(key, NodeKind.STRING, "…", path)
            return when (v) {
                is Map<*, *> -> {
                    val kids = v.entries.map { (k, value) -> val ks = k.toString(); build(ks, value, childPath(path, ks), depth + 1) }
                    b.node(key, NodeKind.OBJECT, null, path, kids)
                }
                is List<*> -> b.node(key, NodeKind.ARRAY, null, path, v.mapIndexed { i, x -> build("$i", x, "$path[$i]", depth + 1) })
                null -> b.node(key, NodeKind.NULL, "null", path)
                is Boolean -> b.node(key, NodeKind.BOOLEAN, v.toString(), path)
                is Number -> b.node(key, NodeKind.NUMBER, v.toString(), path)
                is ByteArray -> b.node(key, NodeKind.STRING, "<${v.size} bytes>", path)
                else -> b.node(key, NodeKind.STRING, v.toString(), path)
            }
        }
        val kids = if (roots.size == 1) listOf(build(null, roots[0], "$", 0))
        else roots.mapIndexed { i, r -> build("document ${i + 1}", r, "$[$i]", 0) }
        val root = b.node(null, NodeKind.ARRAY, null, "", kids)
        return TreeDocument(root, b.next)
    }

    /** JSON (lenient: comments, trailing commas, single quotes). Throws on malformed input. */
    fun fromJson(text: String): TreeDocument = fromValues(listOf(JsonReader(text).readDocument()))

    /** JSON Lines: one value per non-blank line. */
    fun fromJsonLines(text: String): TreeDocument =
        fromValues(listOf(text.lineSequence().filter { it.isNotBlank() }.map { JsonReader(it).readDocument() }.toList()))

    fun fromYaml(text: String): TreeDocument {
        val load = org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().setAllowDuplicateKeys(true).build())
        val docs = load.loadAllFromString(text).toList()
        return fromValues(if (docs.isEmpty()) listOf(null) else docs)
    }

    fun fromXml(text: String): TreeDocument {
        val doc = Jsoup.parse(text, "", Parser.xmlParser())
        val b = Builder()
        fun build(e: Element, path: String, depth: Int): TreeNode {
            val kids = ArrayList<TreeNode>()
            for (a in e.attributes()) kids += b.node("@" + a.key, NodeKind.ATTRIBUTE, a.value, "$path/@${a.key}")
            if (depth < MAX_DEPTH) {
                val counts = HashMap<String, Int>()
                val totals = e.children().groupingBy { it.tagName() }.eachCount()
                for (child in e.childNodes()) {
                    when (child) {
                        is Element -> {
                            val tag = child.tagName()
                            val n = (counts[tag] ?: 0) + 1
                            counts[tag] = n
                            val p = if ((totals[tag] ?: 1) > 1) "$path/$tag[$n]" else "$path/$tag"
                            kids += build(child, p, depth + 1)
                        }
                        is TextNode -> child.text().trim().takeIf { it.isNotEmpty() }?.let {
                            kids += b.node(null, NodeKind.TEXT, it, "$path/text()")
                        }
                    }
                }
            }
            return b.node(e.tagName(), NodeKind.ELEMENT, null, path, kids)
        }
        val tops = doc.children().map { build(it, "/" + it.tagName(), 0) }
        return TreeDocument(b.node(null, NodeKind.ARRAY, null, "", tops), b.next)
    }
}

/** A forgiving JSON reader to Kotlin values (LinkedHashMap keeps key order). */
private class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): Any? {
        skip()
        val v = value(0)
        skip()
        if (i < s.length) throw IllegalArgumentException("unexpected '${s[i]}' at $i")
        return v
    }

    private fun skip() {
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() || c == '﻿' -> i++
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> { while (i < s.length && s[i] != '\n') i++ }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> { val e = s.indexOf("*/", i + 2); i = if (e < 0) s.length else e + 2 }
                else -> return
            }
        }
    }

    private fun value(depth: Int): Any? {
        if (depth > TreeModel.MAX_DEPTH) throw IllegalArgumentException("too deep")
        skip()
        if (i >= s.length) throw IllegalArgumentException("unexpected end")
        return when (val c = s[i]) {
            '{' -> {
                i++
                val m = LinkedHashMap<String, Any?>()
                while (true) {
                    skip()
                    if (i < s.length && s[i] == '}') { i++; break }
                    val key = if (s[i] == '"' || s[i] == '\'') string() else bare()
                    skip(); expect(':')
                    m[key] = value(depth + 1)
                    skip()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    skip(); expect('}'); break
                }
                m
            }
            '[' -> {
                i++
                val l = ArrayList<Any?>()
                while (true) {
                    skip()
                    if (i < s.length && s[i] == ']') { i++; break }
                    l += value(depth + 1)
                    skip()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    skip(); expect(']'); break
                }
                l
            }
            '"', '\'' -> string()
            else -> {
                val w = bare()
                when {
                    w == "true" -> true
                    w == "false" -> false
                    w == "null" -> null
                    w.isNotEmpty() && (w[0].isDigit() || w[0] == '-' || w[0] == '+' || w[0] == '.') -> w.toLongOrNull() ?: w.toDoubleOrNull() ?: w
                    w.isEmpty() -> throw IllegalArgumentException("unexpected '$c' at $i")
                    else -> w
                }
            }
        }
    }

    private fun expect(c: Char) {
        if (i >= s.length || s[i] != c) throw IllegalArgumentException("expected '$c' at $i")
        i++
    }

    private fun bare(): String {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_$.+-")) i++
        return s.substring(start, i)
    }

    private fun string(): String {
        val q = s[i++]
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when {
                c == q -> return sb.toString()
                c == '\\' && i < s.length -> {
                    when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, minOf(i + 4, s.length)).toIntOrNull(16)?.toChar() ?: '?'); i += 4 }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }
}
