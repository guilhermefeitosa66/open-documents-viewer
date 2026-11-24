package io.github.guilhermefeitosa66.opendocumentsviewer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentLoader
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
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

    private var openStartedAt = 0L

    init {
        viewModelScope.launch {
            recents = withContext(Dispatchers.IO) { store.load() }
            recentsLoaded = true
        }
    }

    fun openUri(uri: Uri, mimeHint: String?, external: Boolean, grantFlags: Int = 0, fromPicker: Boolean = false) {
        openStartedAt = SystemClock.uptimeMillis()
        closeSession()
        viewModelScope.launch {
            val key = uri.toString()
            val persisted = withContext(Dispatchers.IO) { persist(uri, grantFlags, fromPicker) }
            val entry = withContext(Dispatchers.IO) { store.find(key) }
            val s = DocumentSession(
                recentKey = key,
                external = external,
                initialFormatted = entry?.formatted ?: true,
                initialWrap = entry?.wrap,
                initialPosition = entry?.position ?: ViewPosition(),
            )
            session = s
            s.start({ loader.load(uri, mimeHint) }) { doc -> remember(doc, s, persisted || entry?.persisted == true) }
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
        val s = DocumentSession(recentKey = null, external = true, initialFormatted = true, initialWrap = null, initialPosition = ViewPosition())
        session = s
        s.startWithText(doc) {}
    }

    fun openRecent(entry: RecentEntry) {
        openUri(Uri.parse(entry.uri), entry.mimeType, external = false)
    }

    /** Leaves the viewer, keeping the reading position. */
    fun closeSession() {
        val s = session ?: return
        savePosition(s)
        s.close()
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
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
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
        session?.close()
    }

    companion object {
        const val TAG = "OpenDocs"
        private const val KEY_PROSE = "prose_scale"
        private const val KEY_CODE = "code_scale"
        private const val KEY_THEME = "theme"
        private const val KEY_BACKGROUND = "background"
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.6f
    }
}
