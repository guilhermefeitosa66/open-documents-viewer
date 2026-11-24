package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LineIndex
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LoadFailure
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LoadedDocument
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Delimited
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.DelimitedTable
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Dotenv
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.EnvLine
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.JsonFormatter
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MarkdownModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.ViewPosition
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.search.SearchOptions
import io.github.guilhermefeitosa66.opendocumentsviewer.search.SearchResult
import io.github.guilhermefeitosa66.opendocumentsviewer.search.TextSearch
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Language
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Languages
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.RowHighlighter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rows of text with a highlighter: the source view, and the formatted view of JSON. */
class TextContent(val text: String, val index: LineIndex, val highlighter: RowHighlighter?) {
    /** Becomes true when every row's lexer state is known (rows are re-highlighted then). */
    var statesReady by mutableStateOf(highlighter == null)
        internal set

    companion object {
        /** Below this, lexer states are computed before the first frame: no visible re-highlight. */
        private const val INLINE_STATES_CHARS = 400_000

        fun build(text: String, language: Language?, isActive: () -> Boolean): TextContent {
            val index = LineIndex.build(text)
            val highlighter = language?.let { RowHighlighter(text, index, it.lexer) }
            val content = TextContent(text, index, highlighter)
            if (highlighter != null && text.length <= INLINE_STATES_CHARS) {
                highlighter.computeStates(isActive)
                content.statesReady = true
            }
            return content
        }
    }
}

/** An entry of a document's table of contents: jumping goes to list item [item] of the formatted view. */
data class OutlineItem(val level: Int, val title: String, val item: Int)

sealed interface Formatted {
    /** [partial]: some content of the original could not be shown (a discreet notice says so). */
    data class Markdown(
        val blocks: List<MdBlock>,
        val anchors: Map<String, Int>,
        val partial: Boolean = false,
        /** A table of contents of its own (e-books); otherwise the outline is built from the headings. */
        val toc: List<OutlineItem>? = null,
        /** E-mail attachments, saved through links `attachment:<index>`. */
        val attachments: List<io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MailModel.Attachment> = emptyList(),
    ) : Formatted
    data class Json(val content: TextContent) : Formatted
    data class Table(val table: DelimitedTable) : Formatted
    data class Env(val lines: List<EnvLine>) : Formatted
    data class Svg(val svg: com.caverock.androidsvg.SVG) : Formatted
    data class Tree(val doc: io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeDocument) : Formatted
    /** The formatted view could not be built (invalid JSON...): the source is shown instead. */
    data object Unavailable : Formatted
}

sealed interface LoadState {
    data object Loading : LoadState
    data class Failed(val failure: LoadFailure, val name: String? = null) : LoadState
    data class Ready(val doc: LoadedDocument) : LoadState
}

enum class ViewMode { FORMATTED, SOURCE }

/** Where the reader is and what they see, for one open document. */
class DocumentSession(
    /** Key in the recents list; null for shared text, which is not kept. */
    val recentKey: String?,
    /** Opened by another app ("open with", share): back returns to that app. */
    val external: Boolean,
    initialFormatted: Boolean,
    initialWrap: Boolean?,
    private val initialPosition: ViewPosition,
    /** The document's id inside the granted folder it was opened from, for relative links. */
    val folderDocumentId: String? = null,
    /** "#section" to scroll to once the formatted view is ready (a link "other.md#section"). */
    private var pendingAnchor: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var load by mutableStateOf<LoadState>(LoadState.Loading)
        private set
    var source by mutableStateOf<TextContent?>(null)
        private set
    var formatted by mutableStateOf<Formatted?>(null)
        private set

    var preferFormatted by mutableStateOf(initialFormatted)
    var wrapOverride by mutableStateOf(initialWrap)
    var lineNumbers by mutableStateOf(true)
    var revealAllSecrets by mutableStateOf(false)
    val revealed = mutableStateMapOf<Int, Boolean>()

    val sourceList = LazyListState()
    val formattedList = LazyListState()
    private var restored = false

    val doc: LoadedDocument? get() = (load as? LoadState.Ready)?.doc

    val kind: DocumentKind? get() = doc?.type?.kind

    val mode: ViewMode by derivedStateOf {
        val k = kind
        if (k != null && k.hasFormattedView && preferFormatted && formatted != Formatted.Unavailable) ViewMode.FORMATTED else ViewMode.SOURCE
    }

    /** Headings of the formatted view (markdown now; other formats add theirs). */
    val outline: List<OutlineItem> by derivedStateOf {
        when (val f = formatted) {
            is Formatted.Markdown -> f.toc?.takeIf { it.isNotEmpty() } ?: f.blocks.mapIndexedNotNull { i, b ->
                (b as? MdBlock.Heading)?.let { OutlineItem(it.level, it.content.text, i) }
            }
            else -> emptyList()
        }
    }

    fun jumpToOutline(item: OutlineItem) {
        if (mode != ViewMode.FORMATTED) preferFormatted = true
        jumpTarget = item.item
    }

    val hasToggle: Boolean get() = kind?.hasFormattedView == true && formatted != Formatted.Unavailable

    /** An image is on screen (raster, or SVG rendered): no text search, gestures zoom the picture. */
    val showsPicture: Boolean get() = kind == DocumentKind.IMAGE || (mode == ViewMode.FORMATTED && formatted is Formatted.Svg)

    /** Wrapping: on by default for prose, off for code. */
    val wrap: Boolean get() = wrapOverride ?: (kind?.prose ?: true)

    val activeList: LazyListState get() = if (mode == ViewMode.FORMATTED) formattedList else sourceList

    // Search.
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
        private set
    var options by mutableStateOf(SearchOptions())
        private set
    var searchResult by mutableStateOf<SearchResult?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var currentMatch by mutableIntStateOf(-1)
        private set
    /** Incremented each time the view should bring the current match into view. */
    var revealRequest by mutableIntStateOf(0)
        private set
    private var searchJob: Job? = null

    val matches: List<Match> get() = (searchResult as? SearchResult.Found)?.matches.orEmpty()

    val matchesByItem: Map<Int, List<Match>> by derivedStateOf { matches.groupBy { it.item } }

    /** Encoding picked by the user in "Encoding…" (Java charset name); null = detected. Read by the loader. */
    var charsetOverride by mutableStateOf<String?>(null)
        private set
    private var loader: (suspend () -> Result<LoadedDocument>)? = null

    /** Reads the file again with [charset] (null: detect it again). Position is kept. */
    fun reencode(charset: String?) {
        val l = loader ?: return
        charsetOverride = charset
        reload(l)
    }

    fun start(loader: suspend () -> Result<LoadedDocument>, onShown: (LoadedDocument) -> Unit) {
        this.loader = loader
        scope.launch {
            val result = withContext(Dispatchers.IO) { loader() }
            val doc = result.getOrElse {
                val ex = it as? io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentLoader.LoadException
                load = LoadState.Failed(ex?.failure ?: LoadFailure.Other(it.message ?: it.javaClass.simpleName), ex?.name)
                return@launch
            }
            show(doc, onShown)
        }
    }

    fun startWithText(doc: LoadedDocument, onShown: (LoadedDocument) -> Unit) {
        scope.launch { show(doc, onShown) }
    }

    private suspend fun show(doc: LoadedDocument, onShown: (LoadedDocument) -> Unit) {
        val wantFormatted = doc.type.kind.hasFormattedView && preferFormatted
        // Build what is shown first; the other view is built right after, off the main thread.
        if (wantFormatted) {
            assignFormatted(withContext(Dispatchers.Default) { buildFormatted(doc) })
        } else {
            source = withContext(Dispatchers.Default) { buildSource(doc) }
        }
        load = LoadState.Ready(doc)
        applyPendingAnchor()
        source?.let(::finishStates)
        (formatted as? Formatted.Json)?.content?.let(::finishStates)
        restorePosition()
        onShown(doc)
        scope.launch {
            if (source == null) source = withContext(Dispatchers.Default) { buildSource(doc) }.also(::finishStates)
            if (formatted == null && doc.type.kind.hasFormattedView) {
                assignFormatted(withContext(Dispatchers.Default) { buildFormatted(doc) }
                    .also { f -> (f as? Formatted.Json)?.content?.let(::finishStates) })
                applyPendingAnchor()
            }
            if (formatted == Formatted.Unavailable) restorePosition()
        }
    }

    /** Walks a large file's lexer states in the background, then lets the rows re-highlight. */
    private fun finishStates(content: TextContent) {
        val h = content.highlighter ?: return
        if (content.statesReady) return
        scope.launch {
            withContext(Dispatchers.Default) {
                val job = kotlinx.coroutines.currentCoroutineContext()
                h.computeStates { job.isActive }
            }
            content.statesReady = true
        }
    }

    private suspend fun buildSource(doc: LoadedDocument): TextContent {
        val language = doc.type.language ?: when (doc.type.kind) {
            DocumentKind.CSV -> when (Delimited.detectDelimiter(doc.text)) {
                ';' -> Languages.CSV_SEMICOLON
                '\t' -> Languages.TSV
                else -> Languages.CSV
            }
            DocumentKind.TSV -> Languages.TSV
            else -> null
        }
        val job = kotlinx.coroutines.currentCoroutineContext()
        return TextContent.build(doc.text, language) { job.isActive }
    }

    private fun headingAnchors(blocks: List<MdBlock>): Map<String, Int> {
        val anchors = HashMap<String, Int>()
        blocks.forEachIndexed { i, b -> if (b is MdBlock.Heading) anchors.putIfAbsent(Anchors.slug(b.content.text), i) }
        return anchors
    }

    private suspend fun buildFormatted(doc: LoadedDocument): Formatted {
        val job = kotlinx.coroutines.currentCoroutineContext()
        return when (doc.type.kind) {
            DocumentKind.MARKDOWN -> {
                val blocks = MarkdownModel.parse(doc.text)
                job.ensureActive()
                val anchors = HashMap<String, Int>()
                blocks.forEachIndexed { i, b ->
                    if (b is MdBlock.Heading) {
                        var slug = Anchors.slug(b.content.text)
                        if (anchors.containsKey(slug)) {
                            var n = 1
                            while (anchors.containsKey("$slug-$n")) n++
                            slug = "$slug-$n"
                        }
                        anchors[slug] = i
                    }
                }
                Formatted.Markdown(blocks, anchors)
            }
            DocumentKind.HTML -> {
                val parsed = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.HtmlModel.parse(doc.text)
                job.ensureActive()
                val anchors = HashMap<String, Int>()
                parsed.blocks.forEachIndexed { i, b ->
                    if (b is MdBlock.Heading) anchors.putIfAbsent(Anchors.slug(b.content.text), i)
                }
                anchors.putAll(parsed.ids)
                Formatted.Markdown(parsed.blocks, anchors)
            }
            DocumentKind.NOTEBOOK -> try {
                val nb = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.NotebookModel.parse(doc.text)
                val anchors = HashMap<String, Int>()
                nb.blocks.forEachIndexed { i, b -> if (b is MdBlock.Heading) anchors.putIfAbsent(Anchors.slug(b.content.text), i) }
                Formatted.Markdown(nb.blocks, anchors, nb.partial)
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            DocumentKind.EPUB -> (doc.model as? io.github.guilhermefeitosa66.opendocumentsviewer.markdown.EpubModel.Book)?.let { book ->
                val p = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.EpubModel.parse(book)
                Formatted.Markdown(p.blocks, p.anchors, toc = p.toc.map { (e, i) -> OutlineItem(e.level, e.title, i) })
            } ?: Formatted.Unavailable
            DocumentKind.DOCX, DocumentKind.ODT -> (doc.model as? io.github.guilhermefeitosa66.opendocumentsviewer.markdown.DocxModel.Result)?.let { r ->
                val anchors = HashMap(headingAnchors(r.blocks))
                anchors.putAll(r.anchors)
                Formatted.Markdown(r.blocks, anchors, r.partial)
            } ?: Formatted.Unavailable
            DocumentKind.EMAIL -> try {
                val m = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MailModel.parse(doc.text, doc.charset)
                job.ensureActive()
                val blocks = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MailModel.blocks(m, FormatLabels.mail)
                Formatted.Markdown(blocks, headingAnchors(blocks), attachments = m.attachments)
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            DocumentKind.VCARD -> try {
                val blocks = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.CardModel.vcardBlocks(doc.text, FormatLabels.card)
                if (blocks.isEmpty()) Formatted.Unavailable else Formatted.Markdown(blocks, headingAnchors(blocks))
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            DocumentKind.ICALENDAR -> try {
                val blocks = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.CardModel.icsBlocks(doc.text, FormatLabels.calendar)
                if (blocks.isEmpty()) Formatted.Unavailable else Formatted.Markdown(blocks, headingAnchors(blocks))
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            DocumentKind.FB2 -> try {
                val html = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.Fb2Model.toHtml(doc.text)
                job.ensureActive()
                val parsed = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.HtmlModel.parse(org.jsoup.Jsoup.parse(html), useTitle = false)
                val anchors = HashMap<String, Int>()
                parsed.blocks.forEachIndexed { i, b -> if (b is MdBlock.Heading) anchors.putIfAbsent(Anchors.slug(b.content.text), i) }
                anchors.putAll(parsed.ids)
                Formatted.Markdown(parsed.blocks, anchors)
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            DocumentKind.RTF -> {
                val (blocks, partial) = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.RtfModel.parseWithInfo(doc.text)
                val anchors = HashMap<String, Int>()
                blocks.forEachIndexed { i, b -> if (b is MdBlock.Heading) anchors.putIfAbsent(Anchors.slug(b.content.text), i) }
                Formatted.Markdown(blocks, anchors, partial)
            }
            DocumentKind.YAML -> try {
                Formatted.Tree(io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeModel.fromYaml(doc.text))
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            DocumentKind.XML -> try {
                Formatted.Tree(io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeModel.fromXml(doc.text))
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            DocumentKind.JSON -> {
                val lowerName = doc.name.lowercase()
                val lines = lowerName.endsWith(".jsonl") || lowerName.endsWith(".ndjson")
                val tree = if (doc.text.length <= TREE_MAX_CHARS) try {
                    if (lines) io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeModel.fromJsonLines(doc.text)
                    else io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeModel.fromJson(doc.text)
                } catch (_: Exception) { null } else null
                if (tree != null) return Formatted.Tree(tree)
                val lower = doc.name.lowercase()
                val pretty = if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) JsonFormatter.formatLines(doc.text) else JsonFormatter.format(doc.text)
                if (pretty == null) Formatted.Unavailable
                else Formatted.Json(TextContent.build(pretty, Languages.JSON) { job.isActive })
            }
            DocumentKind.CSV, DocumentKind.TSV -> {
                val d = if (doc.type.kind == DocumentKind.TSV) '\t' else Delimited.detectDelimiter(doc.text)
                Formatted.Table(Delimited.parse(doc.text, d))
            }
            DocumentKind.DOTENV -> Formatted.Env(Dotenv.parse(doc.text))
            DocumentKind.SVG -> try {
                Formatted.Svg(com.caverock.androidsvg.SVG.getFromString(doc.text))
            } catch (_: Exception) {
                Formatted.Unavailable
            }
            else -> Formatted.Unavailable
        }
    }

    private suspend fun restorePosition() {
        if (restored) return
        restored = true
        if (initialPosition.item > 0 || initialPosition.offset > 0) {
            // Wait a frame so the list knows its items.
            delay(16)
            activeList.scrollToItem(initialPosition.item, initialPosition.offset)
        }
    }

    /** A list item the formatted view should scroll to (a markdown #anchor link). */
    var jumpTarget by mutableStateOf<Int?>(null)

    private fun applyPendingAnchor() {
        val a = pendingAnchor ?: return
        if (formatted !is Formatted.Markdown) return
        pendingAnchor = null
        if (mode != ViewMode.FORMATTED) preferFormatted = true
        restored = true
        jumpToAnchor(a)
    }

    fun jumpToAnchor(anchor: String): Boolean {
        val f = formatted as? Formatted.Markdown ?: return false
        jumpTarget = f.anchors[anchor.lowercase()] ?: return false
        return true
    }

    // ---- Tree view (JSON, YAML, XML) ------------------------------------------------------

    var treeExpanded by mutableStateOf<Set<Int>>(emptySet())

    /** Visible rows of the tree for the current expansion. */
    val treeLines: List<io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeLine> by derivedStateOf {
        val t = formatted as? Formatted.Tree ?: return@derivedStateOf emptyList()
        io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeModel.visible(t.doc, treeExpanded)
    }

    /** Sets the formatted content, opening a new tree at its default expansion. */
    private fun assignFormatted(f: Formatted?) {
        if (f is Formatted.Tree && (formatted as? Formatted.Tree)?.doc !== f.doc) {
            treeExpanded = io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeModel.initialExpanded(f.doc)
        }
        val oldTable = (formatted as? Formatted.Table)?.table
        formatted = f
        // A reload brings a new table: keep the chosen order, sorted again from the new rows.
        if (f is Formatted.Table && oldTable != null && f.table !== oldTable) {
            tableRows = null
            tableSort?.let { applySort(f.table, it) }
        }
    }

    fun toggleNode(id: Int) {
        treeExpanded = if (id in treeExpanded) treeExpanded - id else treeExpanded + id
    }

    // ---- Table (CSV/TSV) -----------------------------------------------------------------

    data class TableSort(val column: Int, val ascending: Boolean)

    var tableSort by mutableStateOf<TableSort?>(null)
        private set
    /** Rows as displayed (header first), sorted when [tableSort] is set; the file is never changed. */
    var tableRows by mutableStateOf<List<List<String>>?>(null)
        private set
    /** Column widths the user dragged, in dp. */
    val columnWidths = mutableStateMapOf<Int, Float>()

    /** Tap on a header: ascending, then descending, then the file's order. */
    fun sortTable(column: Int) {
        val t = (formatted as? Formatted.Table)?.table ?: return
        val now = tableSort
        val next = when {
            now == null || now.column != column -> TableSort(column, true)
            now.ascending -> TableSort(column, false)
            else -> null
        }
        tableSort = next
        if (next == null) {
            tableRows = null
            if (searchOpen) runSearch()
            return
        }
        applySort(t, next)
    }

    private fun applySort(t: io.github.guilhermefeitosa66.opendocumentsviewer.formats.DelimitedTable, next: TableSort) {
        scope.launch {
            val sorted = withContext(Dispatchers.Default) {
                val header = t.rows.take(1)
                header + t.rows.drop(1).sortedWith(io.github.guilhermefeitosa66.opendocumentsviewer.formats.CellSort.comparator(next.column, next.ascending))
            }
            if (tableSort == next && (formatted as? Formatted.Table)?.table === t) {
                tableRows = sorted
                if (searchOpen) runSearch()
            }
        }
    }

    /** JSON shown as indented text instead of a tree (menu). */
    var jsonAsText by mutableStateOf(false)
    var prettyJson by mutableStateOf<TextContent?>(null)
        private set

    fun showJsonAsText(on: Boolean) {
        jsonAsText = on
        val doc = doc ?: return
        if (on && prettyJson == null) scope.launch {
            prettyJson = withContext(Dispatchers.Default) {
                val pretty = JsonFormatter.format(doc.text) ?: doc.text
                TextContent.build(pretty, Languages.JSON) { true }
            }.also(::finishStates)
        }
    }

    /** Pixel size of an image document (or SVG), once decoded. */
    var imageSize by mutableStateOf<androidx.compose.ui.unit.IntSize?>(null)

    /** Incremented after each reload because the file changed (the UI shows a short notice). */
    var reloadCount by mutableIntStateOf(0)
        private set
    private var reloading = false

    /**
     * Reads the file again and swaps the content in place, keeping the view mode, the search and
     * the reading position. The old content stays on screen until the new one is ready.
     */
    fun reload(loader: suspend () -> Result<LoadedDocument>) {
        if (reloading || load !is LoadState.Ready) return
        reloading = true
        val pos = position()
        scope.launch {
            try {
                val doc = withContext(Dispatchers.IO) { loader() }.getOrNull() ?: return@launch
                val newSource = withContext(Dispatchers.Default) { buildSource(doc) }
                val newFormatted = if (doc.type.kind.hasFormattedView) withContext(Dispatchers.Default) { buildFormatted(doc) } else null
                load = LoadState.Ready(doc)
                source = newSource
                assignFormatted(newFormatted)
                finishStates(newSource)
                (newFormatted as? Formatted.Json)?.content?.let(::finishStates)
                delay(16)
                activeList.scrollToItem(pos.item, pos.offset)
                if (searchOpen) runSearch()
                reloadCount++
            } finally {
                reloading = false
            }
        }
    }

    fun position(): ViewPosition = ViewPosition(activeList.firstVisibleItemIndex, activeList.firstVisibleItemScrollOffset)

    fun toggleMode() {
        preferFormatted = !preferFormatted
        if (searchOpen) runSearch()
    }

    // ---- Search -------------------------------------------------------------------------

    fun openSearch() {
        searchOpen = true
    }

    fun closeSearch() {
        searchOpen = false
        searchJob?.cancel()
        searchResult = null
        currentMatch = -1
    }

    fun updateQuery(q: String) {
        query = q
        runSearch(debounce = true)
    }

    fun updateOptions(o: SearchOptions) {
        options = o
        runSearch()
    }

    private fun runSearch(debounce: Boolean = false) {
        searchJob?.cancel()
        val q = query
        if (q.isEmpty()) {
            searchResult = null
            currentMatch = -1
            searching = false
            return
        }
        val opts = options
        val viewMode = mode
        val firstVisible = activeList.firstVisibleItemIndex
        val treeLinesSnapshot = treeLines
        val shownRows = tableRows ?: (formatted as? Formatted.Table)?.table?.rows.orEmpty()
        searchJob = scope.launch {
            if (debounce) delay(150)
            searching = true
            val result = withContext(Dispatchers.Default) {
                val job = kotlinx.coroutines.currentCoroutineContext()
                val active = { job.isActive }
                when {
                    viewMode == ViewMode.SOURCE -> source?.let { searchText(it, q, opts, active) }
                    else -> when (val f = formatted) {
                        is Formatted.Json -> searchText(f.content, q, opts, active)
                        is Formatted.Markdown -> TextSearch.search(f.blocks.map { it.plainText }, q, opts, active)
                        is Formatted.Table -> TextSearch.search(shownRows.map { it.joinToString("  ") }, q, opts, active)
                        is Formatted.Env -> TextSearch.search(f.lines.map(::envText), q, opts, active)
                        is Formatted.Tree -> TextSearch.search(treeLinesSnapshot.map { (it.node.key ?: "") + " " + (it.node.value ?: "") }, q, opts, active)
                        else -> null
                    }
                }
            }
            ensureActive()
            searching = false
            searchResult = result
            val found = (result as? SearchResult.Found)?.matches.orEmpty()
            currentMatch = TextSearch.firstAtOrAfter(found, firstVisible)
            if (currentMatch >= 0) revealRequest++
        }
    }

    private fun searchText(c: TextContent, q: String, o: SearchOptions, active: () -> Boolean) =
        TextSearch.searchRows(c.text, c.index.rowCount, c.index::rowOf, c.index::start, c.index::end, q, o, active)

    fun nextMatch() = step(1)
    fun previousMatch() = step(-1)

    private fun step(delta: Int) {
        val n = matches.size
        if (n == 0) return
        currentMatch = ((if (currentMatch < 0) 0 else currentMatch + delta) % n + n) % n
        revealRequest++
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        /** Above this, JSON is shown as indented text only (a tree would cost too much memory). */
        const val TREE_MAX_CHARS = 8_000_000

        fun envText(line: EnvLine): String = when (line) {
            is EnvLine.Comment -> line.text
            is EnvLine.Variable -> line.key + " " + line.value
            is EnvLine.Other -> line.text
            EnvLine.Blank -> ""
        }
    }
}

object Anchors {
    /** GitHub-style heading slug: lower case, spaces to dashes, punctuation dropped. */
    fun slug(text: String): String = buildString {
        for (c in text.trim().lowercase()) {
            when {
                c.isLetterOrDigit() || c == '-' || c == '_' -> append(c)
                c == ' ' -> append('-')
            }
        }
    }
}
