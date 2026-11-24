package io.github.guilhermefeitosa66.opendocumentsviewer.folder

/** One entry of a folder listing. */
data class FolderEntry(
    val documentId: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean,
) {
    val hidden: Boolean get() = name.startsWith(".")
}

enum class SortOrder { NAME, MODIFIED, SIZE }

/** A row of the flattened tree: an entry at a depth, expanded or not. */
data class TreeRow(val entry: FolderEntry, val depth: Int, val expanded: Boolean, val loading: Boolean)

/**
 * The browsing state of a granted folder, kept free of Android types so it can be tested:
 * which folders are expanded, their loaded children, and how the visible rows come out of that
 * (sort, hidden files, name filter).
 */
class FolderTree(val rootId: String) {
    private val children = HashMap<String, List<FolderEntry>>()
    private val expanded = LinkedHashSet<String>()
    private val loading = HashSet<String>()

    fun isLoaded(id: String) = children.containsKey(id)
    fun isExpanded(id: String) = id in expanded

    fun setChildren(id: String, entries: List<FolderEntry>) {
        children[id] = entries
        loading.remove(id)
    }

    fun markLoading(id: String) {
        loading += id
    }

    fun toggle(id: String): Boolean {
        if (!expanded.remove(id)) expanded += id
        return id in expanded
    }

    fun collapseAll() = expanded.clear()

    fun rows(sort: SortOrder, showHidden: Boolean, filter: String): List<TreeRow> {
        val out = ArrayList<TreeRow>()
        val f = filter.trim()
        fun visit(id: String, depth: Int) {
            for (e in sorted(children[id].orEmpty(), sort)) {
                if (!showHidden && e.hidden) continue
                val isOpen = e.isDirectory && e.documentId in expanded
                if (f.isEmpty()) {
                    out += TreeRow(e, depth, isOpen, e.documentId in loading)
                    if (isOpen) visit(e.documentId, depth + 1)
                } else {
                    // Filtering: show matching files and the folders on the way to them (only loaded ones).
                    val before = out.size
                    val self = TreeRow(e, depth, isOpen, e.documentId in loading)
                    out += self
                    if (isOpen) visit(e.documentId, depth + 1)
                    val childMatched = out.size > before + 1
                    if (!e.name.contains(f, ignoreCase = true) && !childMatched) out.removeAt(before)
                }
            }
        }
        visit(rootId, 0)
        return out
    }

    companion object {
        fun sorted(entries: List<FolderEntry>, sort: SortOrder): List<FolderEntry> {
            val byName = compareBy<FolderEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            val order = when (sort) {
                SortOrder.NAME -> byName
                SortOrder.MODIFIED -> compareByDescending<FolderEntry> { it.modified }.then(byName)
                SortOrder.SIZE -> compareByDescending<FolderEntry> { it.size }.then(byName)
            }
            // Folders first, like file explorers.
            return entries.sortedWith(compareByDescending<FolderEntry> { it.isDirectory }.then(order))
        }
    }
}
