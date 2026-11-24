package io.github.guilhermefeitosa66.opendocumentsviewer.folder

import java.net.URLDecoder

/** Relative links between files of a granted folder, resolved by walking the listings. */
object RelativePath {
    /** Splits "docs/a%20b.md#setup" into the decoded path and the fragment. */
    fun split(link: String): Pair<String, String?> {
        val hash = link.indexOf('#')
        val path = if (hash >= 0) link.substring(0, hash) else link
        val fragment = if (hash >= 0) link.substring(hash + 1).ifEmpty { null } else null
        val clean = path.substringBefore('?')
        val decoded = try { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { clean }
        return decoded to fragment
    }

    /** A link to another file (no scheme, not just an anchor). */
    fun isRelative(link: String): Boolean {
        if (link.isEmpty() || link.startsWith("#")) return false
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) return false
        return !link.startsWith("//")
    }

    fun resolve(
        path: String,
        start: String,
        parent: (String) -> String?,
        children: (String) -> List<FolderEntry>?,
    ): FolderEntry? {
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || path.startsWith("/")) return null
        var dir = start
        for ((i, seg) in segments.withIndex()) {
            val last = i == segments.lastIndex
            if (seg == "..") {
                dir = parent(dir) ?: return null
                continue
            }
            val list = children(dir) ?: return null
            val entry = list.firstOrNull { it.name == seg } ?: list.firstOrNull { it.name.equals(seg, ignoreCase = true) } ?: return null
            if (last) return entry
            if (!entry.isDirectory) return null
            dir = entry.documentId
        }
        return null
    }
}
