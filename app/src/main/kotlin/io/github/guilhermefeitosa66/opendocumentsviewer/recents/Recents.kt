package io.github.guilhermefeitosa66.opendocumentsviewer.recents

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the reader was in a document, restored when it is opened again. */
data class ViewPosition(val item: Int = 0, val offset: Int = 0)

data class RecentEntry(
    val uri: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val openedAt: Long,
    /** True when the app holds a persistable permission: the file can be reopened any time. */
    val persisted: Boolean,
    /** Human readable origin ("Downloads", "open-tuner"...), when the provider tells it. */
    val location: String?,
    val formatted: Boolean = true,
    val wrap: Boolean? = null,
    val position: ViewPosition = ViewPosition(),
)

object RecentsCodec {
    fun encode(entries: List<RecentEntry>): String {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("uri", e.uri)
                    .put("name", e.name)
                    .put("mime", e.mimeType ?: JSONObject.NULL)
                    .put("size", e.size)
                    .put("openedAt", e.openedAt)
                    .put("persisted", e.persisted)
                    .put("location", e.location ?: JSONObject.NULL)
                    .put("formatted", e.formatted)
                    .put("wrap", e.wrap ?: JSONObject.NULL)
                    .put("item", e.position.item)
                    .put("offset", e.position.offset)
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<RecentEntry> {
        val array = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<RecentEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val uri = o.optString("uri").takeIf { it.isNotEmpty() } ?: continue
            out += RecentEntry(
                uri = uri,
                name = o.optString("name", "?"),
                mimeType = o.optStringOrNull("mime"),
                size = o.optLong("size", -1),
                openedAt = o.optLong("openedAt", 0),
                persisted = o.optBoolean("persisted", false),
                location = o.optStringOrNull("location"),
                formatted = o.optBoolean("formatted", true),
                wrap = if (o.isNull("wrap") || !o.has("wrap")) null else o.optBoolean("wrap"),
                position = ViewPosition(o.optInt("item", 0), o.optInt("offset", 0)),
            )
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}

/**
 * The recent documents, kept in one small JSON file in app storage. Only metadata and the reading
 * position are stored, never file contents: a `.env` the user glanced at must not end up copied
 * inside the viewer.
 */
class RecentsStore(private val file: File) {
    private val lock = Any()
    private var cache: List<RecentEntry>? = null

    fun load(): List<RecentEntry> = synchronized(lock) {
        cache ?: (if (file.exists()) RecentsCodec.decode(file.readText()) else emptyList()).also { cache = it }
    }

    fun upsert(entry: RecentEntry) = update { list -> listOf(entry) + list.filter { it.uri != entry.uri } }

    fun updatePosition(uri: String, position: ViewPosition, formatted: Boolean, wrap: Boolean?) = update { list ->
        list.map { if (it.uri == uri) it.copy(position = position, formatted = formatted, wrap = wrap) else it }
    }

    fun remove(uri: String) = update { list -> list.filter { it.uri != uri } }

    fun clear() = update { emptyList() }

    fun find(uri: String): RecentEntry? = load().firstOrNull { it.uri == uri }

    private fun update(change: (List<RecentEntry>) -> List<RecentEntry>): List<RecentEntry> = synchronized(lock) {
        val next = change(load()).take(MAX_ENTRIES)
        cache = next
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(RecentsCodec.encode(next))
        tmp.renameTo(file)
        next
    }

    companion object {
        const val MAX_ENTRIES = 60
    }
}
