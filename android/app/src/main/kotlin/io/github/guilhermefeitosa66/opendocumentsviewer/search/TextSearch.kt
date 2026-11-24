package io.github.guilhermefeitosa66.opendocumentsviewer.search

data class SearchOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** One match: which item of the list (row, block, table row...) and the range inside its text. */
data class Match(val item: Int, val start: Int, val end: Int)

sealed interface SearchResult {
    data class Found(val matches: List<Match>, val truncated: Boolean) : SearchResult
    data class BadPattern(val message: String) : SearchResult
}

/**
 * Searches a list of item texts (whatever the current view shows as list items), so a match can
 * be scrolled to and highlighted in the same item that displays it.
 */
object TextSearch {
    const val MAX_MATCHES = 10_000

    fun search(
        items: List<String>,
        query: String,
        options: SearchOptions,
        isActive: () -> Boolean = { true },
    ): SearchResult {
        if (query.isEmpty()) return SearchResult.Found(emptyList(), false)
        val regex = try {
            buildRegex(query, options)
        } catch (e: IllegalArgumentException) {
            return SearchResult.BadPattern(e.message?.lineSequence()?.firstOrNull() ?: "invalid pattern")
        }
        val matches = ArrayList<Match>()
        for ((index, text) in items.withIndex()) {
            if (index and 255 == 0 && !isActive()) break
            if (regex == null) {
                var from = 0
                while (true) {
                    val at = text.indexOf(query, from, ignoreCase = !options.matchCase)
                    if (at < 0) break
                    matches += Match(index, at, at + query.length)
                    if (matches.size >= MAX_MATCHES) return SearchResult.Found(matches, true)
                    from = at + query.length
                }
            } else {
                for (m in regex.findAll(text)) {
                    if (m.range.isEmpty()) continue
                    matches += Match(index, m.range.first, m.range.last + 1)
                    if (matches.size >= MAX_MATCHES) return SearchResult.Found(matches, true)
                }
            }
        }
        return SearchResult.Found(matches, false)
    }

    /**
     * Searches one big text and reports each match against the row that holds its start, clipped
     * to that row: used for the source view, where rows are slices of the whole file.
     */
    fun searchRows(
        text: String,
        rowCount: Int,
        rowOf: (Int) -> Int,
        rowStart: (Int) -> Int,
        rowEnd: (Int) -> Int,
        query: String,
        options: SearchOptions,
        isActive: () -> Boolean = { true },
    ): SearchResult {
        if (query.isEmpty() || rowCount == 0) return SearchResult.Found(emptyList(), false)
        val regex = try {
            buildRegex(query, options)
        } catch (e: IllegalArgumentException) {
            return SearchResult.BadPattern(e.message?.lineSequence()?.firstOrNull() ?: "invalid pattern")
        }
        val matches = ArrayList<Match>()
        fun add(start: Int, end: Int): Boolean {
            val row = rowOf(start)
            val rs = rowStart(row)
            val re = rowEnd(row)
            if (start >= re && end > start && start != rs) {
                // The match starts on a line break; it belongs to the next row.
                if (row + 1 < rowCount) matches += Match(row + 1, 0, minOf(end, rowEnd(row + 1)) - rowStart(row + 1))
            } else {
                matches += Match(row, start - rs, minOf(end, re) - rs)
            }
            return matches.size < MAX_MATCHES && (matches.size and 255 != 0 || isActive())
        }
        if (regex == null) {
            var from = 0
            while (true) {
                val at = text.indexOf(query, from, ignoreCase = !options.matchCase)
                if (at < 0) break
                if (!add(at, at + query.length)) break
                from = at + query.length
            }
        } else {
            for (m in regex.findAll(text)) {
                if (m.range.isEmpty()) continue
                if (!add(m.range.first, m.range.last + 1)) break
            }
        }
        return SearchResult.Found(matches, matches.size >= MAX_MATCHES)
    }

    /** Null means "a plain substring search is enough" (the fast path). */
    private fun buildRegex(query: String, options: SearchOptions): Regex? {
        if (!options.regex && !options.wholeWord) return null
        var pattern = if (options.regex) query else Regex.escape(query)
        if (options.wholeWord) pattern = "(?<![\\p{L}\\p{N}_])(?:$pattern)(?![\\p{L}\\p{N}_])"
        val flags = if (options.matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, flags)
    }

    /** Index of the match to show first: the first one at or after [item]. */
    fun firstAtOrAfter(matches: List<Match>, item: Int): Int {
        if (matches.isEmpty()) return -1
        var lo = 0
        var hi = matches.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (matches[mid].item < item) lo = mid + 1 else hi = mid
        }
        return if (lo == matches.size) 0 else lo
    }
}
