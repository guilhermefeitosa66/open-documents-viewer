package io.github.guilhermefeitosa66.opendocumentsviewer.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.FolderEntry
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.FolderSession
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.SortOrder
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.formatSize

@Composable
fun FolderScreen(folder: FolderSession, onBack: () -> Unit, onOpenFile: (FolderEntry) -> Unit) {
    val colors = Odv.colors
    var filtering by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            if (filtering) {
                FilterField(folder.filter, onChange = { folder.filter = it }, modifier = Modifier.weight(1f))
                IconButton(onClick = { filtering = false; folder.filter = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.close_search)) }
            } else {
                Text(
                    folder.current.name, modifier = Modifier.weight(1f).padding(start = 4.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { filtering = true }) { Icon(Icons.Filled.Search, stringResource(R.string.filter_by_name)) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SortItem(R.string.sort_by_name, folder.sort == SortOrder.NAME, Icons.Outlined.SortByAlpha) { folder.sort = SortOrder.NAME; menu = false }
                    SortItem(R.string.sort_by_date, folder.sort == SortOrder.MODIFIED, Icons.Outlined.Schedule) { folder.sort = SortOrder.MODIFIED; menu = false }
                    SortItem(R.string.sort_by_size, folder.sort == SortOrder.SIZE, Icons.Outlined.DataUsage) { folder.sort = SortOrder.SIZE; menu = false }
                    HorizontalDivider(color = colors.line)
                    DropdownMenuItem(
                        text = { Text(stringResource(if (folder.showHidden) R.string.hide_hidden_files else R.string.show_hidden_files)) },
                        leadingIcon = { Icon(if (folder.showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null) },
                        onClick = { folder.showHidden = !folder.showHidden; menu = false },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.refresh)) }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { folder.refresh(); menu = false })
                }
            }
        }

        // Breadcrumb: the granted folder, then each folder entered.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            folder.levels.forEachIndexed { i, level ->
                val last = i == folder.levels.lastIndex
                if (i > 0) Text("›", color = colors.ink2, modifier = Modifier.padding(horizontal = 2.dp))
                Text(
                    level.name,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (last) Modifier else Modifier.background(colors.paper2).clickable { folder.goTo(i) })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 14.sp,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium,
                    color = colors.ink,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = colors.line)

        val rows = folder.rows
        when {
            folder.loadingLevel -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (folder.error) R.string.folder_error else if (folder.filter.isNotBlank()) R.string.no_matches_in_folder else R.string.folder_empty),
                    color = colors.ink2, fontSize = 15.sp,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 24.dp)) {
                items(rows, key = { it.entry.documentId }) { row ->
                    TreeRowView(
                        entry = row.entry,
                        depth = row.depth,
                        expanded = row.expanded,
                        loading = row.loading,
                        onTap = { if (row.entry.isDirectory) folder.onTap(row.entry) else onOpenFile(row.entry) },
                        onEnter = { folder.enter(row.entry) },
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun SortItem(label: Int, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), fontWeight = if (selected) FontWeight.Bold else null) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
        trailingIcon = if (selected) ({ Icon(painterResource(R.drawable.ic_check), null, tint = Odv.colors.accent, modifier = Modifier.size(18.dp)) }) else null,
    )
}

@Composable
private fun FilterField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val colors = Odv.colors
    val focus = remember { FocusRequester() }
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(colors.card).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(stringResource(R.string.filter_by_name), color = colors.ink2, fontSize = 16.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = 16.sp), cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun TreeRowView(
    entry: FolderEntry,
    depth: Int,
    expanded: Boolean,
    loading: Boolean,
    onTap: () -> Unit,
    onEnter: () -> Unit,
) {
    val colors = Odv.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
            .padding(start = 8.dp + 18.dp * depth, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) {
            Icon(
                Icons.Filled.KeyboardArrowDown, null, tint = colors.ink2,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 0f else -90f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_folder), null, tint = colors.accentInk, modifier = Modifier.size(22.dp))
        } else {
            Spacer(Modifier.width(26.dp))
            val badge = remember(entry.name) { DocumentTypes.detect(entry.name, entry.mimeType).badge.take(4) }
            Text(badge, modifier = Modifier.width(30.dp), fontFamily = MonoFamily, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = colors.ink2, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            entry.name, modifier = Modifier.weight(1f), fontSize = 15.sp,
            fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
            color = if (entry.hidden) colors.ink2 else colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
        } else if (!entry.isDirectory && entry.size >= 0) {
            Text(formatSize(entry.size), fontSize = 12.sp, color = colors.ink2, modifier = Modifier.padding(end = 10.dp))
        }
        if (entry.isDirectory) {
            IconButton(onClick = onEnter) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.enter_folder, entry.name), tint = colors.ink2)
            }
        }
    }
}

