package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.folder.FolderEntry
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.FolderTree
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTreeTest {
    private fun dir(id: String, modified: Long = 0) = FolderEntry(id, id.substringAfterLast('/'), "vnd.android.document/directory", -1, modified, true)
    private fun file(id: String, size: Long = 1, modified: Long = 0) = FolderEntry(id, id.substringAfterLast('/'), "text/plain", size, modified, false)

    private fun tree() = FolderTree("root").apply {
        setChildren("root", listOf(file("root/b.md", 10, 3), dir("root/src", 1), file("root/.env", 5, 9), file("root/A.txt", 99, 1)))
        setChildren("root/src", listOf(file("root/src/main.kt"), file("root/src/util.kt")))
    }

    @Test
    fun foldersFirstThenNameIgnoringCase() {
        assertEquals(listOf("src", "A.txt", "b.md"), tree().rows(SortOrder.NAME, false, "").map { it.entry.name })
    }

    @Test
    fun hiddenAndSort() {
        val t = tree()
        assertEquals(listOf("src", ".env", "b.md", "A.txt"), t.rows(SortOrder.MODIFIED, true, "").map { it.entry.name })
        assertEquals(listOf("src", "A.txt", "b.md", ".env"), t.rows(SortOrder.SIZE, true, "").map { it.entry.name })
    }

    @Test
    fun expandShowsChildrenIndented() {
        val t = tree()
        t.toggle("root/src")
        val rows = t.rows(SortOrder.NAME, false, "")
        assertEquals(listOf("src", "main.kt", "util.kt", "A.txt", "b.md"), rows.map { it.entry.name })
        assertEquals(1, rows[1].depth)
        t.toggle("root/src")
        assertEquals(3, t.rows(SortOrder.NAME, false, "").size)
    }

    @Test
    fun filterKeepsParentsOfMatches() {
        val t = tree()
        t.toggle("root/src")
        assertEquals(listOf("src", "util.kt"), t.rows(SortOrder.NAME, false, "UTIL").map { it.entry.name })
        assertEquals(listOf("b.md"), t.rows(SortOrder.NAME, false, ".md").map { it.entry.name })
    }
}
