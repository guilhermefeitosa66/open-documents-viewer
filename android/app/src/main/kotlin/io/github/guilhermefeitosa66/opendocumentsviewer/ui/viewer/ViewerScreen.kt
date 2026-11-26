package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.FileTransfer
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LoadFailure
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LoadedDocument
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Dotenv
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.EnvLine
import io.github.guilhermefeitosa66.opendocumentsviewer.search.SearchResult
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv

/** What the viewer asks the app to do. */
interface ViewerActions {
    fun back()
    fun openSwitcher()
    fun copyAll(doc: LoadedDocument)
    /** Copies a piece of text (a PDF selection). */
    fun copyText(text: String, label: String)
    /** Open PDF Viewer's package when it is installed, for "Open in Open PDF Viewer". */
    val pdfViewerPackage: String? get() = null
    fun openInPdfViewer(doc: LoadedDocument) {}
    fun share(doc: LoadedDocument)
    fun shareAsText(doc: LoadedDocument)
    fun saveCopy(doc: LoadedDocument)
    fun openWith(doc: LoadedDocument)
    fun openLink(url: String, session: DocumentSession)
    fun zoom(prose: Boolean, factor: Float)
    fun onFirstContent()
}

/** Reading comfort settings and the full-screen switch, provided by the activity. */
data class ReadingPrefs(
    val limitWidth: Boolean = true,
    val fullScreen: Boolean = false,
    val onFullScreen: (Boolean) -> Unit = {},
)

val LocalReading = androidx.compose.runtime.staticCompositionLocalOf { ReadingPrefs() }

/** Comfortable measure for prose on large screens. */
val MaxProseWidth = 760.dp

/** Split view controls, offered only on wide screens. */
class SplitControls(
    val inSplit: Boolean,
    val onSplitSame: () -> Unit,
    val onSplitOther: () -> Unit,
    val onCloseSplit: () -> Unit,
)

@Composable
fun ViewerScreen(
    session: DocumentSession,
    proseSize: TextUnit,
    codeSize: TextUnit,
    actions: ViewerActions,
    split: SplitControls? = null,
) {
    val colors = Odv.colors
    var showInfo by remember { mutableStateOf(false) }
    var showEncoding by remember { mutableStateOf(false) }
    val doc = session.doc
    val prose = session.mode == ViewMode.FORMATTED && (session.kind == DocumentKind.MARKDOWN || session.kind == DocumentKind.HTML || session.kind == DocumentKind.RTF || session.kind == DocumentKind.NOTEBOOK) ||
        session.mode == ViewMode.SOURCE && session.kind == DocumentKind.PLAIN && session.wrap
    val fontSize = if (prose) proseSize else codeSize

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        val reading = LocalReading.current
        if (session.searchOpen) {
            SearchBar(session)
            HorizontalDivider(color = colors.line)
        } else if (!reading.fullScreen) {
            TopBar(session, actions, split, onInfo = { showInfo = true }, onEncoding = { showEncoding = true })
            HorizontalDivider(color = colors.line)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (session.showsPicture) Modifier else Modifier.pinchToZoom { actions.zoom(prose, it) }),
        ) {
            if (reading.fullScreen && !session.searchOpen) {
                IconButton(
                    onClick = { reading.onFullScreen(false) },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).zIndex(1f)
                        .clip(RoundedCornerShape(50)).background(colors.card.copy(alpha = 0.85f)),
                ) {
                    Icon(painterResource(R.drawable.ic_fullscreen_exit), stringResource(R.string.exit_full_screen), tint = colors.ink)
                }
            }
            when (val load = session.load) {
                LoadState.Loading -> Loading()
                is LoadState.Failed -> Failure(load.failure, onPassword = session::unlock)
                is LoadState.Ready -> {
                    Content(session, fontSize, actions)
                    LaunchedEffect(Unit) { actions.onFirstContent() }
                }
            }
        }
    }

    if (showInfo && doc != null) InfoDialog(doc, session, onDismiss = { showInfo = false })
    if (showEncoding && doc != null) EncodingDialog(doc, session, onDismiss = { showEncoding = false })
}

@Composable
private fun Content(session: DocumentSession, fontSize: TextUnit, actions: ViewerActions) {
    val colors = Odv.colors
    val doc = session.doc ?: return
    val bottomInset = WindowInsets.navigationBars
    val density = LocalDensity.current
    val navBottom = with(density) { bottomInset.getBottom(density).toDp() }
    val pillSpace = if (session.hasToggle) 92.dp else 24.dp
    val padding = PaddingValues(top = 12.dp, bottom = pillSpace + navBottom)
    val matches = session.matchesByItem
    val current = session.matches.getOrNull(session.currentMatch)
    val linkListener = remember(session) {
        LinkInteractionListener { link ->
            val url = (link as? androidx.compose.ui.text.LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            actions.openLink(url, session)
        }
    }
    val list = session.activeList
    // Items before the first data item (the .env note), for scrolling to a match.
    var leadingItems = 0

    if (doc.type.kind == DocumentKind.IMAGE) {
        Box(Modifier.fillMaxSize()) {
            RasterContent(session, doc)
            // iWork files: only the preview picture they carry is shown.
            if (doc.name.lowercase().substringAfterLast('.') in setOf("pages", "numbers", "key")) {
                PartialNotice(R.string.iwork_preview, Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp))
            }
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        if (doc.truncated) TruncatedBanner(Modifier.align(Alignment.TopCenter))

        when (session.mode) {
            ViewMode.SOURCE -> {
                val src = session.source
                if (src == null) Loading() else {
                    val proseRows = session.kind == DocumentKind.PLAIN && session.wrap && LocalReading.current.limitWidth
                    RowsView(
                        src, session.sourceList, session.wrap, session.lineNumbers, fontSize, matches, current, session.revealRequest, padding,
                        maxTextWidth = if (proseRows) MaxProseWidth else null,
                    )
                    FastScroller(session.sourceList, src.index.rowCount, session.matches, current, Modifier.align(Alignment.CenterEnd))
                }
            }
            ViewMode.FORMATTED -> when (val f = session.formatted) {
                null, Formatted.Unavailable -> Loading()
                is Formatted.Markdown -> {
                    MarkdownView(f, session.formattedList, fontSize, matches, current, linkListener, padding)
                    FastScroller(session.formattedList, f.blocks.size, session.matches, current, Modifier.align(Alignment.CenterEnd))
                }
                is Formatted.Json -> {
                    RowsView(f.content, session.formattedList, session.wrap, session.lineNumbers, fontSize, matches, current, session.revealRequest, padding)
                    FastScroller(session.formattedList, f.content.index.rowCount, session.matches, current, Modifier.align(Alignment.CenterEnd))
                }
                is Formatted.Table -> {
                    TableView(f.table, session, session.formattedList, fontSize, matches, current, padding)
                    FastScroller(session.formattedList, f.table.rows.size, session.matches, current, Modifier.align(Alignment.CenterEnd))
                }
                is Formatted.Pdf -> {
                    PdfView(
                        f, session, session.formattedList, matches, current, padding,
                        onLink = { actions.openLink(it, session) },
                        onCopy = { actions.copyText(it, doc.name) },
                    )
                }
                is Formatted.Slides -> {
                    SlideView(f.pres, session, session.doc?.bytes, current, padding, LocalReading.current)
                }
                is Formatted.Sheet -> {
                    SheetView(f.wb, session, session.formattedList, fontSize, matches, current, padding)
                }
                is Formatted.Tree -> {
                    val pretty = session.prettyJson
                    if (session.jsonAsText && session.kind == DocumentKind.JSON) {
                        if (pretty == null) Loading() else {
                            RowsView(pretty, session.formattedList, session.wrap, session.lineNumbers, fontSize, matches, current, session.revealRequest, padding)
                            FastScroller(session.formattedList, pretty.index.rowCount, session.matches, current, Modifier.align(Alignment.CenterEnd))
                        }
                    } else {
                        TreeView(session, session.formattedList, fontSize, matches, current, padding)
                        FastScroller(session.formattedList, session.treeLines.size, session.matches, current, Modifier.align(Alignment.CenterEnd))
                    }
                }
                is Formatted.Svg -> {
                    val decoded by androidx.compose.runtime.produceState<ImageLoading.Decoded?>(null, f) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ImageLoading.svgDrawable(f.svg) }
                        session.imageSize = value?.size
                    }
                    decoded?.let { ZoomableDrawable(it.drawable, Modifier.background(colors.paper2)) } ?: Loading()
                }
                is Formatted.Env -> {
                    val anySecret = remember(f) { f.lines.any { it is EnvLine.Variable && Dotenv.looksSecret(it.key, it.value) } }
                    if (anySecret && !session.revealAllSecrets) leadingItems = 1
                    EnvView(
                        f.lines, session.formattedList, fontSize, session.revealAllSecrets, session.revealed,
                        onToggle = { i -> session.revealed[i] = !(session.revealed[i] ?: false) },
                        matchesByItem = matches, current = current, contentPadding = padding,
                    )
                }
            }
        }

        // Notices last, so they sit above the document.
        ReloadNotice(session.reloadCount, Modifier.align(Alignment.TopCenter))
        if (session.mode == ViewMode.FORMATTED && !session.presenting &&
            ((session.formatted as? Formatted.Markdown)?.partial == true || (session.formatted as? Formatted.Slides)?.pres?.partial == true)
        ) {
            PartialNotice(
                when (session.kind) {
                    DocumentKind.DOC -> R.string.partial_text_only
                    DocumentKind.PPT -> R.string.partial_slides_text
                    else -> R.string.partial_render
                },
                Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp),
            )
        }

        val lowerName = session.doc?.name?.lowercase().orEmpty()
        if (lowerName.substringAfterLast('.') in setOf("docm", "dotm", "xlsm", "xltm", "pptm", "ppsm", "potm")) {
            PartialNotice(R.string.macros_never_run, Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp))
        }

        if (session.hasToggle && !session.searchOpen && !session.presenting) {
            ModePill(session, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp))
        }
    }

    val jump = session.jumpTarget
    LaunchedEffect(jump) {
        if (jump != null) {
            session.formattedList.animateScrollToItem(jump)
            session.jumpTarget = null
        }
    }

    val lead = leadingItems
    LaunchedEffect(session.revealRequest) {
        val m = current ?: return@LaunchedEffect
        val viewport = list.layoutInfo.viewportSize.height
        list.scrollToItem(m.item + lead, -viewport / 3)
    }
}

@Composable
private fun TopBar(session: DocumentSession, actions: ViewerActions, split: SplitControls?, onInfo: () -> Unit, onEncoding: () -> Unit) {
    val colors = Odv.colors
    val doc = session.doc
    var menu by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var goToPage by remember { mutableStateOf(false) }
    (session.formatted as? Formatted.Pdf)?.let { f ->
        if (goToPage) GoToPageDialog(f.doc.pageCount, onDismiss = { goToPage = false }) { p -> goToPage = false; session.pdfGrid = false; session.pdfJump = p to 0f }
    }
    val reading = LocalReading.current
    if (showOutline) OutlineSheet(session, onDismiss = { showOutline = false })
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::back) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = stringResource(R.string.switch_document), role = Role.Button) { actions.openSwitcher() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                doc?.name ?: (session.load as? LoadState.Failed)?.name ?: stringResource(R.string.loading),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subtitle(session),
                    fontSize = 12.sp, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Filled.ArrowDropDown, null, tint = colors.ink2, modifier = Modifier.size(18.dp))
            }
        }
        if (doc != null) {
            val showsRows = !session.showsPicture && (session.mode == ViewMode.SOURCE || session.formatted is Formatted.Json)
            if (showsRows) {
                IconToggleButton(checked = session.wrap, onCheckedChange = { session.wrapOverride = it }) {
                    Icon(
                        painterResource(R.drawable.ic_wrap),
                        stringResource(if (session.wrap) R.string.wrap_on else R.string.wrap_off),
                        tint = if (session.wrap) colors.accentInk else colors.ink2,
                    )
                }
            }
            if (session.mode == ViewMode.FORMATTED && session.formatted is Formatted.Env) {
                IconToggleButton(checked = session.revealAllSecrets, onCheckedChange = { session.revealAllSecrets = it }) {
                    Icon(
                        painterResource(if (session.revealAllSecrets) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                        stringResource(if (session.revealAllSecrets) R.string.hide_values else R.string.show_values),
                        tint = colors.accentInk,
                    )
                }
            }
            if (session.outline.size >= 2) {
                IconButton(onClick = { showOutline = true }) {
                    Icon(painterResource(R.drawable.ic_outline), stringResource(R.string.outline))
                }
            }
            if (!session.showsPicture) IconButton(onClick = session::openSearch) {
                Icon(Icons.Filled.Search, stringResource(R.string.search))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (doc.type.kind != DocumentKind.IMAGE) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.copy_all)) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; actions.copyAll(doc) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; actions.share(doc) })
                    if (doc.uri != null && doc.bytes == null && doc.text.length <= FileTransfer.SHARE_AS_TEXT_MAX_CHARS) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.share_as_text)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ShortText, null) }, onClick = { menu = false; actions.shareAsText(doc) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.save_copy)) }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) }, onClick = { menu = false; actions.saveCopy(doc) })
                    if (doc.uri != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_with)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) }, onClick = { menu = false; actions.openWith(doc) })
                    }
                    if (session.mode == ViewMode.SOURCE || session.formatted is Formatted.Json) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (session.lineNumbers) R.string.hide_line_numbers else R.string.show_line_numbers)) },
                            leadingIcon = { Icon(Icons.Outlined.FormatListNumbered, null) },
                            onClick = { menu = false; session.lineNumbers = !session.lineNumbers },
                        )
                    }
                    if (split != null) {
                        HorizontalDivider(color = Odv.colors.line)
                        if (split.inSplit) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.close_split)) }, leadingIcon = { Icon(Icons.Outlined.CloseFullscreen, null) }, onClick = { menu = false; split.onCloseSplit() })
                        } else {
                            if (session.hasToggle) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.split_same)) }, leadingIcon = { Icon(Icons.Outlined.VerticalSplit, null) }, onClick = { menu = false; split.onSplitSame() })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.split_other)) }, leadingIcon = { Icon(Icons.Outlined.ViewColumn, null) }, onClick = { menu = false; split.onSplitOther() })
                        }
                    }
                    if (session.kind == DocumentKind.JSON && session.formatted is Formatted.Tree && session.mode == ViewMode.FORMATTED) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (session.jsonAsText) R.string.show_as_tree else R.string.show_as_text)) },
                            leadingIcon = { Icon(if (session.jsonAsText) Icons.Outlined.AccountTree else Icons.Outlined.DataObject, null) },
                            onClick = { menu = false; session.showJsonAsText(!session.jsonAsText) },
                        )
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.full_screen)) }, leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) }, onClick = { menu = false; reading.onFullScreen(true) })
                    if (doc.uri != null && doc.bytes == null && doc.type.kind != DocumentKind.PDF) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.encoding_menu)) }, leadingIcon = { Icon(Icons.Outlined.Translate, null) }, onClick = { menu = false; onEncoding() })
                    }
                    if (doc.type.kind == DocumentKind.PDF && doc.uri != null && actions.pdfViewerPackage != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_in_pdf_viewer)) }, leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) }, onClick = { menu = false; actions.openInPdfViewer(doc) })
                    }
                    if (session.formatted is Formatted.Pdf && session.mode == ViewMode.FORMATTED) {
                        HorizontalDivider(color = Odv.colors.line)
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_go_to_page)) }, leadingIcon = { Icon(Icons.Outlined.Pin, null) }, onClick = { menu = false; goToPage = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_pages)) }, leadingIcon = { Icon(Icons.Outlined.GridView, null) }, onClick = { menu = false; session.pdfGrid = !session.pdfGrid })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_fit_width)) }, leadingIcon = { Icon(Icons.Outlined.SettingsEthernet, null) }, onClick = { menu = false; session.pdfZoom = 1f })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_fit_page)) }, leadingIcon = { Icon(Icons.Outlined.FitScreen, null) }, onClick = { menu = false; session.pdfFitPage() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.file_info)) }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { menu = false; onInfo() })
                }
            }
        }
    }
}

@Composable
private fun subtitle(session: DocumentSession): String {
    val doc = session.doc ?: return ""
    val parts = ArrayList<String>()
    parts += if (doc.type.kind == DocumentKind.SVG || doc.type.kind == DocumentKind.HTML || doc.type.kind == DocumentKind.RTF ||
        doc.type.kind == DocumentKind.EPUB || doc.type.kind == DocumentKind.FB2) doc.type.label
        else if (doc.type.kind == DocumentKind.DOCX || doc.type.kind == DocumentKind.DOC) "Word"
        else if (doc.type.kind == DocumentKind.ODT || doc.type.kind == DocumentKind.ODS || doc.type.kind == DocumentKind.ODP) "OpenDocument"
        else if (doc.type.kind == DocumentKind.XLSX || doc.type.kind == DocumentKind.XLS) "Excel"
        else if (doc.type.kind == DocumentKind.PPTX || doc.type.kind == DocumentKind.PPT) "PowerPoint"
        else if (doc.type.kind == DocumentKind.PDF) "PDF"
        else if (doc.type.kind == DocumentKind.EMAIL) stringResource(R.string.kind_email)
        else if (doc.type.kind == DocumentKind.VCARD) stringResource(R.string.kind_contacts)
        else if (doc.type.kind == DocumentKind.ICALENDAR) stringResource(R.string.kind_calendar)
        else if (doc.type.kind == DocumentKind.NOTEBOOK) "Jupyter" else doc.type.language?.name ?: when (doc.type.kind) {
        DocumentKind.MARKDOWN -> "Markdown"
        DocumentKind.HTML -> "HTML"
        DocumentKind.IMAGE -> doc.type.label
        DocumentKind.SVG -> "SVG"
        DocumentKind.CSV -> "CSV"
        DocumentKind.TSV -> "TSV"
        else -> stringResource(R.string.kind_text)
    }
    if (session.showsPicture || doc.type.kind == DocumentKind.IMAGE) {
        session.imageSize?.let { parts += "${it.width} × ${it.height}" }
    } else if (doc.type.kind != DocumentKind.EPUB && doc.type.kind != DocumentKind.DOCX && doc.type.kind != DocumentKind.ODT && doc.type.kind != DocumentKind.DOC && doc.type.kind != DocumentKind.XLSX && doc.type.kind != DocumentKind.ODS && doc.type.kind != DocumentKind.XLS && doc.type.kind != DocumentKind.PPTX && doc.type.kind != DocumentKind.ODP && doc.type.kind != DocumentKind.PPT && doc.type.kind != DocumentKind.PDF) {
        session.source?.let { parts += pluralStringResource(R.plurals.lines, it.index.lineCount, it.index.lineCount) }
    }
    doc.location?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
private fun SearchBar(session: DocumentSession) {
    val colors = Odv.colors
    val focus = remember { FocusRequester() }
    val result = session.searchResult
    val count = (result as? SearchResult.Found)?.matches?.size ?: 0
    Column(Modifier.fillMaxWidth().background(colors.paper2).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = session::closeSearch) {
                Icon(Icons.Filled.Close, stringResource(R.string.close_search))
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(colors.card)
                    .border(1.dp, colors.accent, RoundedCornerShape(23.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (session.query.isEmpty()) {
                        Text(stringResource(R.string.search_hint), color = colors.ink2, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = session.query,
                        onValueChange = session::updateQuery,
                        singleLine = true,
                        textStyle = TextStyle(color = colors.ink, fontSize = 16.sp),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { session.nextMatch() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                val label = when {
                    result is SearchResult.BadPattern -> stringResource(R.string.bad_pattern)
                    session.query.isEmpty() -> ""
                    session.searching && result == null -> "…"
                    count == 0 -> stringResource(R.string.no_matches)
                    else -> stringResource(
                        if ((result as SearchResult.Found).truncated) R.string.match_position_many else R.string.match_position,
                        session.currentMatch + 1, count,
                    )
                }
                Text(label, fontSize = 13.sp, color = colors.ink2, maxLines = 1)
            }
            IconButton(onClick = session::previousMatch, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.previous_match))
            }
            IconButton(onClick = session::nextMatch, enabled = count > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.next_match))
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val o = session.options
            OptionChip("Aa", o.matchCase, stringResource(R.string.match_case)) { session.updateOptions(o.copy(matchCase = it)) }
            OptionChip(stringResource(R.string.whole_word), o.wholeWord, null) { session.updateOptions(o.copy(wholeWord = it)) }
            OptionChip(".*", o.regex, stringResource(R.string.regex), mono = true) { session.updateOptions(o.copy(regex = it)) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, description: String?, mono: Boolean = false, onChange: (Boolean) -> Unit) {
    val colors = Odv.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(if (selected) colors.accent else colors.paper2)
            .border(1.dp, if (selected) colors.accent else colors.line, shape)
            .clickable(role = Role.Checkbox, onClickLabel = description) { onChange(!selected) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) MonoFamily else null,
            color = if (selected) colors.onAccent else colors.ink2,
        )
    }
}

@Composable
private fun ModePill(session: DocumentSession, modifier: Modifier) {
    val colors = Odv.colors
    val formatted = session.mode == ViewMode.FORMATTED
    Row(
        modifier
            .shadow(10.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(colors.pill)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PillButton(stringResource(R.string.formatted), R.drawable.ic_formatted, formatted) { if (!formatted) session.toggleMode() }
        PillButton(stringResource(R.string.source), R.drawable.ic_code, !formatted) { if (formatted) session.toggleMode() }
    }
}

@Composable
private fun PillButton(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = Odv.colors
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.pillSelected else colors.pill)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (selected) colors.ink else colors.onPill
        Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun TruncatedBanner(modifier: Modifier) {
    val colors = Odv.colors
    Surface(modifier.padding(8.dp), color = colors.paper2, shape = RoundedCornerShape(10.dp), shadowElevation = 2.dp) {
        Text(stringResource(R.string.truncated), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, color = colors.ink)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Odv.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun Failure(failure: LoadFailure, onPassword: ((String) -> Unit)? = null) {
    val colors = Odv.colors
    if ((failure == LoadFailure.NeedsPassword || failure == LoadFailure.WrongPassword) && onPassword != null) {
        PasswordPrompt(wrong = failure == LoadFailure.WrongPassword, onPassword = onPassword)
        return
    }
    val (title, body) = when (failure) {
        LoadFailure.NeedsPassword, LoadFailure.WrongPassword, LoadFailure.Protected -> R.string.error_protected_title to R.string.error_protected_body
        LoadFailure.Binary -> R.string.error_binary_title to R.string.error_binary_body
        LoadFailure.Encrypted -> R.string.error_drm_title to R.string.error_drm_body
        LoadFailure.NotFound -> R.string.error_not_found_title to R.string.error_not_found_body
        LoadFailure.PermissionLost -> R.string.error_permission_title to R.string.error_permission_body
        is LoadFailure.Other -> R.string.error_other_title to R.string.error_other_body
    }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.ink)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(body), fontSize = 15.sp, color = colors.ink2)
        if (failure is LoadFailure.Other) {
            Spacer(Modifier.height(8.dp))
            Text(failure.message, fontSize = 12.sp, fontFamily = MonoFamily, color = colors.ink2)
        }
    }
}

/** An encrypted Office file: the password opens it in memory only (never saved, never sent). */
@Composable
private fun PasswordPrompt(wrong: Boolean, onPassword: (String) -> Unit) {
    val colors = Odv.colors
    var pw by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.password_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.ink)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.password_body), fontSize = 15.sp, color = colors.ink2)
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            singleLine = true,
            label = { Text(stringResource(R.string.password_label)) },
            isError = wrong,
            supportingText = if (wrong) ({ Text(stringResource(R.string.password_wrong)) }) else null,
            visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Go,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (pw.isNotEmpty()) onPassword(pw) }),
            trailingIcon = {
                IconButton(onClick = { shown = !shown }) {
                    Icon(painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility), stringResource(if (shown) R.string.hide_values else R.string.show_values))
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(onClick = { onPassword(pw) }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.password_open)) }
    }
}

@Composable
private fun InfoDialog(doc: LoadedDocument, session: DocumentSession, onDismiss: () -> Unit) {
    val colors = Odv.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable
                fun line(label: Int, value: String) {
                    Row {
                        Text(stringResource(label), color = colors.ink2, modifier = Modifier.width(96.dp), fontSize = 14.sp)
                        Text(value, fontSize = 14.sp, color = colors.ink)
                    }
                }
                line(R.string.info_type, doc.type.language?.name ?: doc.type.label)
                if (doc.size >= 0) line(R.string.info_size, formatSize(doc.size))
                session.imageSize?.let { line(R.string.info_dimensions, "${it.width} × ${it.height}") }
                if (doc.bytes == null) {
                    session.source?.let { line(R.string.info_lines, it.index.lineCount.toString()) }
                    line(R.string.info_encoding, doc.charset)
                    val endings = remember(doc) { io.github.guilhermefeitosa66.opendocumentsviewer.document.TextDecoder.lineEndings(doc.text) }
                    if (endings.isNotEmpty()) {
                        line(R.string.info_line_endings, if (endings == "Mixed") stringResource(R.string.line_endings_mixed) else endings)
                    }
                }
                doc.location?.let { line(R.string.info_location, it) }
                doc.mimeType?.let { line(R.string.info_mime, it) }
            }
        },
    )
}

/** "Go to page…" for PDFs. */
@Composable
private fun GoToPageDialog(count: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()?.takeIf { it in 1..count }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_go_to_page)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { v -> text = v.filter(Char::isDigit).take(6) }, singleLine = true,
                label = { Text("1 – $count") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { n?.let { onGo(it - 1) } }),
            )
        },
        confirmButton = { TextButton(onClick = { n?.let { onGo(it - 1) } }, enabled = n != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Encoding…": read the file again with another encoding, or detect it again. */
@Composable
private fun EncodingDialog(doc: LoadedDocument, session: DocumentSession, onDismiss: () -> Unit) {
    val colors = Odv.colors
    val choices = remember { io.github.guilhermefeitosa66.opendocumentsviewer.document.TextDecoder.CHOICES }
    val current = session.charsetOverride
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.encoding_title)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                val options = listOf<String?>(null) + choices
                items(options.size) { i ->
                    val cs = options[i]
                    val selected = cs == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss(); if (!selected) session.reencode(cs) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (cs == null) stringResource(R.string.encoding_auto) + (if (current == null) " · ${doc.charset}" else "") else cs,
                            fontSize = 15.sp, color = colors.ink,
                        )
                    }
                }
            }
        },
    )
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(session: DocumentSession, onDismiss: () -> Unit) {
    val colors = Odv.colors
    val minLevel = session.outline.minOfOrNull { it.level } ?: 1
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.card) {
        Text(
            stringResource(R.string.outline),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink,
        )
        androidx.compose.foundation.lazy.LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            items(session.outline.size) { i ->
                val item = session.outline[i]
                Text(
                    item.title.ifBlank { "…" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { session.jumpToOutline(item); onDismiss() }
                        .heightIn(min = 44.dp)
                        .padding(start = 24.dp + 16.dp * (item.level - minLevel), end = 24.dp, top = 10.dp, bottom = 10.dp),
                    fontSize = if (item.level <= minLevel) 16.sp else 15.sp,
                    fontWeight = if (item.level <= minLevel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (item.level <= minLevel + 1) colors.ink else colors.ink2,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Updated" for a moment after the file changed and was reloaded. */
@Composable
private fun ReloadNotice(count: Int, modifier: Modifier) {
    if (count == 0) return
    var visible by remember(count) { mutableStateOf(true) }
    LaunchedEffect(count) {
        kotlinx.coroutines.delay(2500)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(visible, modifier.padding(top = 8.dp)) {
        val colors = Odv.colors
        Surface(color = colors.pill, contentColor = colors.onPill, shape = RoundedCornerShape(50), shadowElevation = 3.dp) {
            Text(stringResource(R.string.file_updated), Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RasterContent(session: DocumentSession, doc: LoadedDocument) {
    val colors = Odv.colors
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    var failed by remember(doc) { mutableStateOf(false) }
    val decoded by androidx.compose.runtime.produceState<ImageLoading.Decoded?>(null, doc) {
        val bytes = doc.bytes
        value = if (bytes == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImageLoading.decode(bytes, res) }
        session.imageSize = value?.size
        failed = value == null
    }
    when {
        decoded != null -> ZoomableDrawable(decoded!!.drawable, Modifier.background(colors.paper2))
        failed -> Failure(LoadFailure.Other(stringResource(R.string.image_cannot_decode)))
        else -> Loading()
    }
}

/** Some of the original (pictures, embedded objects...) could not be shown: say so once, briefly. */
@Composable
private fun PartialNotice(message: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4000)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(visible, modifier.padding(top = 8.dp)) {
        val colors = Odv.colors
        Surface(color = colors.paper2, contentColor = colors.ink, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp) {
            Text(stringResource(message), Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp)
        }
    }
}
