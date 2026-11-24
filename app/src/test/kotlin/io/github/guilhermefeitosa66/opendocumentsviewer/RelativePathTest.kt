package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.folder.FolderEntry
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.RelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativePathTest {
    private fun d(id: String) = FolderEntry(id, id.substringAfterLast('/'), "vnd.android.document/directory", -1, 0, true)
    private fun f(id: String) = FolderEntry(id, id.substringAfterLast('/'), "text/markdown", 1, 0, false)

    // root/{README.md, docs/{setup.md, My Notes.md}, src/{a.kt}}
    private val children = mapOf(
        "root" to listOf(f("root/README.md"), d("root/docs"), d("root/src")),
        "root/docs" to listOf(f("root/docs/setup.md"), f("root/docs/My Notes.md")),
        "root/src" to listOf(f("root/src/a.kt")),
    )
    private val parents = children.flatMap { (p, cs) -> cs.map { it.documentId to p } }.toMap()
    private fun resolve(from: String, link: String) =
        RelativePath.resolve(RelativePath.split(link).first, parents.getValue(from), { parents[it] }) { children[it] }?.documentId

    @Test
    fun resolvesSiblingsChildrenAndParents() {
        assertEquals("root/docs/setup.md", resolve("root/README.md", "docs/setup.md"))
        assertEquals("root/docs/setup.md", resolve("root/README.md", "./docs/setup.md"))
        assertEquals("root/README.md", resolve("root/docs/setup.md", "../README.md"))
        assertEquals("root/src/a.kt", resolve("root/docs/setup.md", "../src/a.kt"))
        assertEquals("root/docs/My Notes.md", resolve("root/README.md", "docs/My%20Notes.md"))
        assertEquals("root/README.md", resolve("root/docs/setup.md", "../readme.md"))
    }

    @Test
    fun neverLeavesTheGrantedFolder() {
        assertNull(resolve("root/README.md", "../outside.md"))
        assertNull(resolve("root/README.md", "/etc/passwd"))
        assertNull(resolve("root/README.md", "missing.md"))
    }

    @Test
    fun splitsFragments() {
        assertEquals("docs/setup.md" to "install", RelativePath.split("docs/setup.md#install"))
        assertEquals("a.md" to null, RelativePath.split("a.md"))
    }

    @Test
    fun relativeOrNot() {
        assertTrue(RelativePath.isRelative("docs/a.md"))
        assertTrue(RelativePath.isRelative("../a.md#x"))
        assertFalse(RelativePath.isRelative("https://x.org"))
        assertFalse(RelativePath.isRelative("mailto:a@b.c"))
        assertFalse(RelativePath.isRelative("#top"))
        assertFalse(RelativePath.isRelative("//cdn.example/x"))
    }
}
