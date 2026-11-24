package io.github.guilhermefeitosa66.opendocumentsviewer.folder

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A folder the user granted with ACTION_OPEN_DOCUMENT_TREE, browsed as an expandable tree.
 * Folders can also be entered (the breadcrumb then grows), which keeps deep trees usable on a
 * narrow screen.
 */
class FolderSession(
    val treeUri: Uri,
    rootName: String,
    private val resolver: ContentResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    data class Level(val documentId: String, val name: String, val tree: FolderTree)

    val levels = mutableStateListOf<Level>()
    var sort by mutableStateOf(SortOrder.NAME)
    var showHidden by mutableStateOf(false)
    var filter by mutableStateOf("")
    var error by mutableStateOf(false)
        private set
    private var version by mutableIntStateOf(0)

    val current: Level get() = levels.last()

    val rows: List<TreeRow> by derivedStateOf {
        version
        current.tree.rows(sort, showHidden, filter)
    }

    /** True until the first listing of the current level arrives. */
    val loadingLevel: Boolean by derivedStateOf {
        version
        !current.tree.isLoaded(current.documentId)
    }

    init {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        levels += Level(rootId, rootName, FolderTree(rootId))
        load(current.tree, rootId)
    }

    fun onTap(entry: FolderEntry) {
        val tree = current.tree
        if (tree.toggle(entry.documentId) && !tree.isLoaded(entry.documentId)) load(tree, entry.documentId)
        version++
    }

    fun enter(entry: FolderEntry) {
        filter = ""
        levels += Level(entry.documentId, entry.name, FolderTree(entry.documentId))
        load(current.tree, entry.documentId)
        version++
    }

    /** Goes to breadcrumb [index] (0 = the granted folder). */
    fun goTo(index: Int) {
        while (levels.size > index + 1) levels.removeAt(levels.size - 1)
        version++
    }

    /** One level up; false when already at the granted folder. */
    fun up(): Boolean {
        if (levels.size <= 1) return false
        levels.removeAt(levels.size - 1)
        version++
        return true
    }

    fun refresh() {
        val tree = current.tree
        load(tree, current.documentId)
    }

    fun documentUri(entry: FolderEntry): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)

    private val parentOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val listed = java.util.concurrent.ConcurrentHashMap<String, List<FolderEntry>>()

    private fun load(tree: FolderTree, parentId: String) {
        tree.markLoading(parentId)
        scope.launch {
            val entries = withContext(Dispatchers.IO) { listCached(parentId, refresh = true) }
            if (entries == null) error = true
            tree.setChildren(parentId, entries.orEmpty())
            version++
        }
    }

    private fun listCached(parentId: String, refresh: Boolean = false): List<FolderEntry>? {
        if (!refresh) listed[parentId]?.let { return it }
        val entries = list(parentId) ?: return null
        listed[parentId] = entries
        for (e in entries) parentOf[e.documentId] = parentId
        return entries
    }

    /**
     * Finds the document a relative link points to ("../docs/setup.md"), starting from the folder
     * that holds [fromDocumentId]. Never leaves the granted folder. Runs queries: call off the main thread.
     */
    fun resolve(fromDocumentId: String, relativePath: String): FolderEntry? =
        RelativePath.resolve(relativePath, start = parentOf[fromDocumentId] ?: return null, parent = { parentOf[it] }) { dir ->
            listCached(dir)
        }

    private fun list(parentId: String): List<FolderEntry>? = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, projection, null, null, null)?.use { c ->
            val out = ArrayList<FolderEntry>(c.count)
            while (c.moveToNext()) {
                val mime = c.getString(2)
                out += FolderEntry(
                    documentId = c.getString(0),
                    name = c.getString(1) ?: "?",
                    mimeType = mime,
                    size = if (c.isNull(3)) -1 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                    isDirectory = mime == Document.MIME_TYPE_DIR,
                )
            }
            out
        }
    } catch (_: Exception) {
        null
    }

    fun close() = scope.cancel()

    companion object {
        /** Display name of a granted tree's root, for titles and recents. */
        fun rootName(resolver: ContentResolver, treeUri: Uri): String {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            return try {
                resolver.query(docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            } ?: DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').substringAfterLast('/').ifEmpty { "/" }
        }
    }
}
