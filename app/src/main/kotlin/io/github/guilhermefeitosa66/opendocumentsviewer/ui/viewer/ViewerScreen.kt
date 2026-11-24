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
    fun share(doc: LoadedDocument)
    fun openWith(doc: LoadedDocument)
    fun openLink(url: String, session: DocumentSession)
    fun zoom(prose: Boolean, factor: Float)
    fun onFirstContent()
}

@Composable
fun ViewerScreen(
    session: DocumentSession,
    proseSize: TextUnit,
    codeSize: TextUnit,
    actions: ViewerActions,
) {
    val colors = Odv.colors
    var showInfo by remember { mutableStateOf(false) }
    val doc = session.doc
    val prose = session.mode == ViewMode.FORMATTED && session.kind == DocumentKind.MARKDOWN ||
        session.mode == ViewMode.SOURCE && session.kind == DocumentKind.PLAIN && session.wrap
    val fontSize = if (prose) proseSize else codeSize

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        if (session.searchOpen) SearchBar(session) else TopBar(session, actions, onInfo = { showInfo = true })
        HorizontalDivider(color = colors.line)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pinchToZoom { actions.zoom(prose, it) },
        ) {
            when (val load = session.load) {
                LoadState.Loading -> Loading()
                is LoadState.Failed -> Failure(load.failure)
                is LoadState.Ready -> {
                    Content(session, fontSize, actions)
                    LaunchedEffect(Unit) { actions.onFirstContent() }
                }
            }
        }
    }

    if (showInfo && doc != null) InfoDialog(doc, session, onDismiss = { showInfo = false })
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

    Box(Modifier.fillMaxSize()) {
        if (doc.truncated) TruncatedBanner(Modifier.align(Alignment.TopCenter))
        when (session.mode) {
            ViewMode.SOURCE -> {
                val src = session.source
                if (src == null) Loading() else {
                    RowsView(src, session.sourceList, session.wrap, session.lineNumbers, fontSize, matches, current, session.revealRequest, padding)
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
                    TableView(f.table, session.formattedList, fontSize, matches, current, padding)
                    FastScroller(session.formattedList, f.table.rows.size, session.matches, current, Modifier.align(Alignment.CenterEnd))
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

        if (session.hasToggle && !session.searchOpen) {
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
private fun TopBar(session: DocumentSession, actions: ViewerActions, onInfo: () -> Unit) {
    val colors = Odv.colors
    val doc = session.doc
    var menu by remember { mutableStateOf(false) }
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
            val showsRows = session.mode == ViewMode.SOURCE || session.formatted is Formatted.Json
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
            IconButton(onClick = session::openSearch) {
                Icon(Icons.Filled.Search, stringResource(R.string.search))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.copy_all)) }, onClick = { menu = false; actions.copyAll(doc) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; actions.share(doc) })
                    if (doc.uri != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.open_with)) }, onClick = { menu = false; actions.openWith(doc) })
                    }
                    if (session.mode == ViewMode.SOURCE || session.formatted is Formatted.Json) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (session.lineNumbers) R.string.hide_line_numbers else R.string.show_line_numbers)) },
                            onClick = { menu = false; session.lineNumbers = !session.lineNumbers },
                        )
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.file_info)) }, onClick = { menu = false; onInfo() })
                }
            }
        }
    }
}

@Composable
private fun subtitle(session: DocumentSession): String {
    val doc = session.doc ?: return ""
    val parts = ArrayList<String>()
    parts += doc.type.language?.name ?: when (doc.type.kind) {
        DocumentKind.MARKDOWN -> "Markdown"
        DocumentKind.CSV -> "CSV"
        DocumentKind.TSV -> "TSV"
        else -> stringResource(R.string.kind_text)
    }
    session.source?.let { parts += pluralStringResource(R.plurals.lines, it.index.lineCount, it.index.lineCount) }
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
private fun Failure(failure: LoadFailure) {
    val colors = Odv.colors
    val (title, body) = when (failure) {
        LoadFailure.Binary -> R.string.error_binary_title to R.string.error_binary_body
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
                session.source?.let { line(R.string.info_lines, it.index.lineCount.toString()) }
                line(R.string.info_encoding, doc.charset)
                doc.location?.let { line(R.string.info_location, it) }
                doc.mimeType?.let { line(R.string.info_mime, it) }
            }
        },
    )
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

