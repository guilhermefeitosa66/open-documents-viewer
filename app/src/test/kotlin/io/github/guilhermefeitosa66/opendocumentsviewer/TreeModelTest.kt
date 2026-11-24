package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.formats.NodeKind
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeModel
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeModelTest {
    private fun all(n: TreeNode): List<TreeNode> = n.children.flatMap { listOf(it) + all(it) }

    @Test
    fun jsonTreeWithPaths() {
        val doc = TreeModel.fromJson("""{"name": "odv", "deps": [{"id": 1, "ok": true}], "weird key": null, /* c */ "n": -1.5,}""")
        val nodes = all(doc.root)
        val name = nodes.first { it.key == "name" }
        assertEquals(NodeKind.STRING, name.kind)
        assertEquals("$.name", name.path)
        assertEquals("$.deps[0].ok", nodes.first { it.key == "ok" }.path)
        assertEquals("$[\"weird key\"]", nodes.first { it.key == "weird key" }.path)
        assertEquals(NodeKind.NUMBER, nodes.first { it.key == "n" }.kind)
    }

    @Test
    fun jsonLines() {
        val doc = TreeModel.fromJsonLines("{\"a\":1}\n\n{\"a\":2}\n")
        assertEquals(2, doc.root.children.single().children.size)
    }

    @Test
    fun yamlDocuments() {
        val doc = TreeModel.fromYaml(javaClass.getResource("/samples/sample.yaml")!!.readText())
        val nodes = all(doc.root)
        assertEquals(2, doc.root.children.size)
        assertTrue(nodes.any { it.key == "runs-on" && it.value == "ubuntu-latest" })
        assertTrue(nodes.any { it.path == "$[0].jobs.test.steps[1].run" })
    }

    @Test
    fun xmlElementsAttributesText() {
        val doc = TreeModel.fromXml(javaClass.getResource("/samples/sample.xml")!!.readText())
        val nodes = all(doc.root)
        assertTrue(nodes.any { it.kind == NodeKind.ATTRIBUTE && it.key == "@lang" && it.value == "en" })
        assertTrue(nodes.any { it.path == "/catalog/book[2]/title" })
        assertTrue(nodes.any { it.kind == NodeKind.TEXT && it.value == "Hello" && it.path == "/catalog/note/text()" })
    }

    @Test
    fun expansionAndVisibleRows() {
        val doc = TreeModel.fromJson("""{"a": {"b": {"c": 1}}, "d": 2}""")
        val expanded = TreeModel.initialExpanded(doc)
        val rows = TreeModel.visible(doc, expanded)
        assertTrue(rows.any { it.node.key == "c" && it.depth == 3 })
        val collapsed = TreeModel.visible(doc, emptySet())
        assertEquals(1, collapsed.size)
    }
}
