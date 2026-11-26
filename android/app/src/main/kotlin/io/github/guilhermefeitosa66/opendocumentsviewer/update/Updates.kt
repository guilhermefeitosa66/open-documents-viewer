package io.github.guilhermefeitosa66.opendocumentsviewer.update

import android.content.Context
import io.github.guilhermefeitosa66.opendocumentsviewer.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.CoroutineContext

/**
 * "A new version is out": the app's only network access, one request to GitHub per process when the
 * user keeps it on (Settings). Ported from OpenEmux Android.
 */
object UpdateVersion {
    private val NUMBER = Regex("""\d+(?:\.\d+)*""")

    /** The first dotted number in [value] ("android-v0.34.0-beta" → [0, 34, 0]), or null. */
    fun parse(value: String?): List<Int>? =
        value?.let { NUMBER.find(it) }?.value?.split('.')?.map { it.toIntOrNull() ?: return null }

    /** True when [candidate] is later than [current]; "1.2" and "1.2.0" are equal. */
    fun isNewer(candidate: String?, current: String?): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** A published Android release: its version and its page. */
data class Release(val version: String, val url: String)

/** The monorepo also publishes Linux releases, so `releases/latest` may not be ours: read the list. */
const val UPDATE_API_URL = "https://api.github.com/repos/guilhermefeitosa66/open-documents-viewer/releases?per_page=20"
const val DEFAULT_RELEASE_URL = "https://github.com/guilhermefeitosa66/open-documents-viewer/releases"
const val TAG_PREFIX = "android-v"

/** The first non-draft release tagged `android-v…` in a `/releases` reply (newest first); null when none. */
fun parseReleases(json: String): Release? {
    val list = try { JSONArray(json) } catch (_: JSONException) { return null }
    for (i in 0 until list.length()) {
        val obj = list.optJSONObject(i) ?: continue
        if (obj.optBoolean("draft", false)) continue
        // optString turns a JSON null into "null".
        val tag = obj.optString("tag_name").takeUnless { obj.isNull("tag_name") }.orEmpty()
        if (!tag.startsWith(TAG_PREFIX)) continue
        return releaseOf(obj, tag) ?: continue
    }
    return null
}

private fun releaseOf(obj: JSONObject, tag: String): Release? {
    val version = UpdateVersion.parse(tag.removePrefix(TAG_PREFIX))?.joinToString(".") ?: return null
    // Only a GitHub page is opened; anything else falls back to the releases list.
    val page = obj.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: DEFAULT_RELEASE_URL
    return Release(version, page)
}

/** What the check found; [Unknown] also covers "off", "still running" and "failed". */
sealed interface UpdateState {
    data object Unknown : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
}

/** Asks GitHub for the latest Android release. Never throws: a failure means "nothing to show". */
class UpdateChecker(private val current: String, private val fetch: suspend () -> String? = ::httpFetch) {
    suspend fun check(): UpdateState = try {
        when (val release = fetch()?.let(::parseReleases)) {
            null -> UpdateState.Unknown
            else -> if (UpdateVersion.isNewer(release.version, current)) UpdateState.Available(release) else UpdateState.UpToDate
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateState.Unknown
    }
}

/**
 * The check once per process, its switch and the banner's "Not now". [enabled] and [persist] are the
 * preference, kept outside so the logic runs without a Context.
 */
class UpdateStore(
    enabled: Boolean,
    private val persist: (Boolean) -> Unit,
    private val checker: UpdateChecker,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Memory only: the banner comes back on the next cold start. */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    private val _enabled = MutableStateFlow(enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var started = false
    private var job: Job? = null

    /** Starts the check unless it is off or already ran in this process; null when nothing started. */
    fun checkOnce(scope: CoroutineScope): Job? {
        if (!_enabled.value || started) return null
        started = true
        return scope.launch(dispatcher) {
            val found = checker.check()
            // Turned off while the request was out: show nothing.
            if (_enabled.value) _state.value = found
        }.also { job = it }
    }

    fun dismiss() {
        _dismissed.value = true
    }

    /** Off forgets what was found; on waits for the next start to check again. */
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        persist(on)
        if (!on) {
            job?.cancel()
            _state.value = UpdateState.Unknown
        }
    }

    companion object {
        const val PREF = "update_check"
        /** On unless the user turns it off. */
        const val DEFAULT_ENABLED = true

        fun create(context: Context): UpdateStore {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return UpdateStore(
                prefs.getBoolean(PREF, DEFAULT_ENABLED),
                { prefs.edit().putBoolean(PREF, it).apply() },
                UpdateChecker(BuildConfig.VERSION_NAME),
            )
        }
    }
}

private const val TIMEOUT_MS = 8_000

/** The reply body, or null on any non-200 (a 403 is the rate limit) or network failure. */
private suspend fun httpFetch(): String? = withContext(Dispatchers.IO) {
    try {
        val connection = URL(UPDATE_API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "OpenDocumentsViewer/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
