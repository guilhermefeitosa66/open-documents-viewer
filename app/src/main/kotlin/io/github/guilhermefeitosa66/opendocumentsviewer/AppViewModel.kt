package io.github.guilhermefeitosa66.opendocumentsviewer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.FolderSession
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentLoader
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.document.FileTransfer
import io.github.guilhermefeitosa66.opendocumentsviewer.document.Shareable
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LoadedDocument
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.RecentEntry
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.RecentsStore
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.ViewPosition
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.DocumentSession
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.LoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val loader = DocumentLoader(app)
    private val store = RecentsStore(File(app.filesDir, "recents.json"))
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var recents by mutableStateOf<List<RecentEntry>>(emptyList())
        private set
    var recentsLoaded by mutableStateOf(false)
        private set
    var session by mutableStateOf<DocumentSession?>(null)
        private set
    var message by mutableStateOf<Int?>(null)

    var proseScale by mutableFloatStateOf(prefs.getFloat(KEY_PROSE, 1f))
        private set
    var codeScale by mutableFloatStateOf(prefs.getFloat(KEY_CODE, 1f))
        private set

    var themeId by mutableStateOf(prefs.getString(KEY_THEME, null) ?: io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Themes.DEFAULT_ID)
        private set
    var backgroundEnabled by mutableStateOf(prefs.getBoolean(KEY_BACKGROUND, true))
        private set
    var settingsOpen by mutableStateOf(false)

    fun selectTheme(id: String) {
        themeId = id
        prefs.edit().putString(KEY_THEME, id).apply()
    }

    fun setBackground(enabled: Boolean) {
        backgroundEnabled = enabled
        prefs.edit().putBoolean(KEY_BACKGROUND, enabled).apply()
    }

    /** Fraction of a "Save a copy" in progress (-1 = size unknown), or null when none runs. */
    var copyProgress by mutableStateOf<Float?>(null)
        private set
    private var pendingSave: Shareable? = null

    fun prepareSave(item: Shareable) {
        pendingSave = item
    }

    fun saveTo(target: Uri) {
        val item = pendingSave ?: return
        pendingSave = null
        copyProgress = -1f
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val size = (item as? Shareable.File)?.let { loader.meta(it.uri).size } ?: -1L
                    FileTransfer.copy(context.contentResolver, item, target, size) { p -> copyProgress = p }
                }
            }
            copyProgress = null
            message = when {
                result.isSuccess -> R.string.copy_saved
                result.exceptionOrNull() is SecurityException -> R.string.error_permission_body
                else -> R.string.copy_failed
            }
        }
    }

    /** Whether the app can still read [uri] (permissions lent by other apps expire). */
    fun canRead(uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.close()
        true
    } catch (_: Exception) {
        false
    }

    private var openStartedAt = 0L

    init {
        viewModelScope.launch {
            recents = withContext(Dispatchers.IO) { store.load() }
            recentsLoaded = true
        }
    }

    /** A document open in this session (a light tab). Its session may be unloaded to save memory. */
    data class OpenTab(
        val key: String,
        val name: String,
        val mimeType: String?,
        val folderDocumentId: String?,
        val external: Boolean,
        /** Text shared into the app: kept, since it cannot be read again from anywhere. */
        val shared: LoadedDocument? = null,
    )

    /** Open documents, most recently used first. */
    val tabs = androidx.compose.runtime.mutableStateListOf<OpenTab>()

    /** Loaded sessions by tab key, least recently used first. */
    private val loaded = LinkedHashMap<String, DocumentSession>()
    private var sharedCounter = 0

    fun keyOf(s: DocumentSession?): String? = loaded.entries.firstOrNull { it.value === s }?.key

    private fun activate(key: String, s: DocumentSession) {
        loaded.remove(key)
        loaded[key] = s
        tabs.indexOfFirst { it.key == key }.takeIf { it > 0 }?.let { tabs.add(0, tabs.removeAt(it)) }
        session = s
        // Only a few documents stay rendered; the others reopen at their saved position.
        while (loaded.size > MAX_LOADED) {
            val (k, old) = loaded.entries.first()
            if (old === s) break
            savePosition(old)
            old.close()
            loaded.remove(k)
        }
        while (tabs.size > MAX_TABS) closeTab(tabs.last().key)
    }

    private fun addTab(tab: OpenTab) {
        if (tabs.none { it.key == tab.key }) tabs.add(0, tab)
    }

    private fun renameTab(key: String, name: String, mime: String?) {
        val i = tabs.indexOfFirst { it.key == key }
        if (i >= 0) tabs[i] = tabs[i].copy(name = name, mimeType = mime ?: tabs[i].mimeType)
    }

    fun openUri(
        uri: Uri,
        mimeHint: String?,
        external: Boolean,
        grantFlags: Int = 0,
        fromPicker: Boolean = false,
        folderDocumentId: String? = null,
        anchor: String? = null,
    ) {
        openStartedAt = SystemClock.uptimeMillis()
        closeSession()
        val key = uri.toString()
        loaded[key]?.let { s ->
            activate(key, s)
            if (anchor != null) s.jumpToAnchor(anchor)
            return
        }
        viewModelScope.launch {
            val persisted = withContext(Dispatchers.IO) { persist(uri, grantFlags, fromPicker) }
            val entry = withContext(Dispatchers.IO) { store.find(key) }
            val s = DocumentSession(
                recentKey = key,
                external = external,
                initialFormatted = entry?.formatted ?: true,
                initialWrap = entry?.wrap,
                initialPosition = if (anchor != null) ViewPosition() else entry?.position ?: ViewPosition(),
                folderDocumentId = folderDocumentId,
                pendingAnchor = anchor,
            )
            addTab(OpenTab(key, entry?.name ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty(), mimeHint, folderDocumentId, external))
            activate(key, s)
            s.start({ loader.load(uri, mimeHint) }) { doc ->
                renameTab(key, doc.name, doc.mimeType)
                remember(doc, s, persisted || entry?.persisted == true)
            }
        }
    }

    fun openSharedText(text: String, subject: String?) {
        openStartedAt = SystemClock.uptimeMillis()
        closeSession()
        val type = DocumentTypes.detectSharedText(text)
        val name = subject?.takeIf { it.isNotBlank() }?.take(80) ?: context.getString(R.string.shared_text)
        val doc = LoadedDocument(
            uri = null, name = name, mimeType = "text/plain", size = text.length.toLong(), text = text,
            charset = "UTF-16", type = type, truncated = false, location = null,
        )
        val key = "shared:${sharedCounter++}"
        openShared(key, doc)
    }

    private fun openShared(key: String, doc: LoadedDocument) {
        val s = DocumentSession(recentKey = null, external = true, initialFormatted = true, initialWrap = null, initialPosition = ViewPosition())
        addTab(OpenTab(key, doc.name, doc.mimeType, null, external = true, shared = doc))
        activate(key, s)
        s.startWithText(doc) {}
    }

    /** Switches to an open document, reloading it if it was unloaded. */
    fun activateTab(tab: OpenTab) {
        val s = loaded[tab.key]
        when {
            s != null -> {
                closeSession()
                activate(tab.key, s)
            }
            tab.shared != null -> {
                closeSession()
                openShared(tab.key, tab.shared)
            }
            else -> openUri(Uri.parse(tab.key), tab.mimeType, tab.external, folderDocumentId = tab.folderDocumentId)
        }
    }

    /** Second document beside the main one (wide screens only), or null. Never in [loaded]. */
    var split by mutableStateOf<DocumentSession?>(null)
        private set

    /** Split view of the current document: the other view (formatted beside source). */
    fun splitSameDocument() {
        val s = session ?: return
        val doc = s.doc ?: return
        closeSplit()
        val t = DocumentSession(recentKey = null, external = false, initialFormatted = !s.preferFormatted, initialWrap = s.wrapOverride, initialPosition = ViewPosition())
        split = t
        t.startWithText(doc) {}
    }

    /** Split view with another document: a tab or a recent file. */
    fun splitWith(key: String, mimeType: String?, shared: LoadedDocument?) {
        closeSplit()
        val t = DocumentSession(recentKey = null, external = false, initialFormatted = true, initialWrap = null, initialPosition = ViewPosition())
        split = t
        if (shared != null) t.startWithText(shared) {}
        else t.start({ loader.load(Uri.parse(key), mimeType) }) {}
    }

    fun closeSplit() {
        split?.close()
        split = null
    }

    fun closeTab(key: String) {
        loaded.remove(key)?.let { s ->
            savePosition(s)
            s.close()
            if (session === s) session = null
        }
        tabs.removeAll { it.key == key }
    }

    fun openRecent(entry: RecentEntry) {
        if (entry.isFolder) openFolder(Uri.parse(entry.uri), fromPicker = false)
        else openUri(Uri.parse(entry.uri), entry.mimeType, external = false)
    }

    /** The granted folder being browsed; documents opened from it return to it. */
    var folder by mutableStateOf<FolderSession?>(null)
        private set

    fun openFolder(treeUri: Uri, fromPicker: Boolean) {
        closeSession()
        folder?.close()
        folder = null
        viewModelScope.launch {
            val resolver = context.contentResolver
            val ok = withContext(Dispatchers.IO) {
                if (fromPicker) {
                    try {
                        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {
                    }
                }
                resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
            }
            if (!ok) {
                message = R.string.error_permission_body
                return@launch
            }
            val name = withContext(Dispatchers.IO) { FolderSession.rootName(resolver, treeUri) }
            folder = FolderSession(treeUri, name, resolver)
            val entry = RecentEntry(
                uri = treeUri.toString(), name = name, mimeType = DocumentsContract.Document.MIME_TYPE_DIR, size = -1,
                openedAt = System.currentTimeMillis(), persisted = true, location = null,
            )
            recents = withContext(Dispatchers.IO) { store.upsert(entry) }
        }
    }

    /**
     * Follows a relative link of [from] inside its granted folder. Returns false when the link cannot
     * be followed (no folder, outside it, not found).
     */
    fun followRelativeLink(from: DocumentSession, link: String) {
        val f = folder
        val docId = from.folderDocumentId
        if (f == null || docId == null) {
            message = R.string.relative_links_need_folder
            return
        }
        val (path, fragment) = io.github.guilhermefeitosa66.opendocumentsviewer.folder.RelativePath.split(link)
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) { f.resolve(docId, path) }
            when {
                target == null -> message = R.string.link_not_found
                target.isDirectory -> message = R.string.link_is_folder
                else -> openUri(f.documentUri(target), target.mimeType, external = false, folderDocumentId = target.documentId, anchor = fragment)
            }
        }
    }

    fun closeFolder() {
        folder?.close()
        folder = null
    }

    /** Leaves the viewer, keeping the reading position; the document stays open as a tab. */
    fun closeSession() {
        val s = session ?: return
        savePosition(s)
        session = null
    }

    fun savePosition(s: DocumentSession? = session) {
        s ?: return
        val key = s.recentKey ?: return
        if (s.load !is LoadState.Ready) return
        val pos = s.position()
        val formatted = s.preferFormatted
        val wrap = s.wrapOverride
        viewModelScope.launch {
            recents = withContext(Dispatchers.IO) { store.updatePosition(key, pos, formatted, wrap) }
        }
    }

    fun removeRecent(entry: RecentEntry) {
        viewModelScope.launch {
            recents = withContext(Dispatchers.IO) {
                if (entry.persisted) releasePermission(entry.uri)
                store.remove(entry.uri)
            }
        }
    }

    fun clearRecents() {
        viewModelScope.launch {
            recents = withContext(Dispatchers.IO) {
                store.load().filter { it.persisted }.forEach { releasePermission(it.uri) }
                store.clear()
            }
        }
    }

    fun zoom(prose: Boolean, factor: Float) {
        if (prose) {
            proseScale = (proseScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        } else {
            codeScale = (codeScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        prefs.edit().putFloat(KEY_PROSE, proseScale).putFloat(KEY_CODE, codeScale).apply()
    }

    fun onFirstContent() {
        if (openStartedAt > 0) {
            Log.i(TAG, "document shown in ${SystemClock.uptimeMillis() - openStartedAt} ms")
            openStartedAt = 0
        }
    }

    private fun remember(doc: LoadedDocument, s: DocumentSession, persisted: Boolean) {
        val key = s.recentKey ?: return
        val existing = store.find(key)
        val entry = RecentEntry(
            uri = key,
            name = doc.name,
            mimeType = doc.mimeType,
            size = doc.size,
            openedAt = System.currentTimeMillis(),
            persisted = persisted,
            location = doc.location,
            formatted = existing?.formatted ?: true,
            wrap = existing?.wrap,
            position = existing?.position ?: ViewPosition(),
        )
        viewModelScope.launch {
            recents = withContext(Dispatchers.IO) { store.upsert(entry) }
        }
    }

    /** Keeps read access across restarts when the provider allows it. */
    private fun persist(uri: Uri, grantFlags: Int, fromPicker: Boolean): Boolean {
        val resolver = context.contentResolver
        if (fromPicker || grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return try {
            val u = uri.toString()
            resolver.persistedUriPermissions.any { it.isReadPermission && (it.uri == uri || u.startsWith(it.uri.toString() + "/document/")) }
        } catch (_: Exception) {
            false
        }
    }

    private fun releasePermission(uri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        split?.close()
        loaded.values.forEach { it.close() }
        loaded.clear()
    }

    companion object {
        const val TAG = "OpenDocs"
        private const val KEY_PROSE = "prose_scale"
        private const val KEY_CODE = "code_scale"
        private const val KEY_THEME = "theme"
        private const val KEY_BACKGROUND = "background"
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.6f
        /** Documents kept rendered in memory; older tabs reopen on demand. */
        const val MAX_LOADED = 3
        const val MAX_TABS = 12
    }
}
