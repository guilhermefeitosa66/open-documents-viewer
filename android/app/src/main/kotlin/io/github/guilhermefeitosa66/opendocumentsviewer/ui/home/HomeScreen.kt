package io.github.guilhermefeitosa66.opendocumentsviewer.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.wrapContentHeight
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.rememberBackground
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.BuildInfo
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.RecentEntry
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.OdvColors

@Composable
fun HomeScreen(
    recents: List<RecentEntry>,
    loaded: Boolean,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenRecent: (RecentEntry) -> Unit,
    onRemove: (RecentEntry) -> Unit,
    onClearAll: () -> Unit,
    onShareRecent: (RecentEntry) -> Unit,
    onSaveRecent: (RecentEntry) -> Unit,
    onSettings: () -> Unit,
    /** Asset of the theme's background image, or null for a plain color. */
    background: String?,
) {
    val colors = Odv.colors
    var menu by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val widthPx = LocalConfiguration.current.screenWidthDp.let { with(LocalDensity.current) { it.dp.roundToPx() } }
    val image by rememberBackground(background, widthPx)
    // Over an image, text sits on translucent surfaces; without one, on the plain page.
    val veil = if (image != null) colors.card.copy(alpha = 0.85f) else Color.Transparent

    Box(Modifier.fillMaxSize().background(colors.paper)) {
    image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(veil).statusBarsPadding().height(64.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(calangoFor(Odv.colors)), null, Modifier.size(28.dp))
            Text(
                stringResource(R.string.home_title),
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink,
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (recents.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.clear_recents)) }, onClick = { menu = false; confirmClear = true })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.about)) }, onClick = { menu = false; about = true })
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onOpenFile,
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_file), null, Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.open_file), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            }
            OutlinedButton(
                onClick = onOpenFolder,
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, colors.line),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.card, contentColor = colors.ink),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(24.dp), tint = colors.accentInk)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.open_folder), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }

        if (loaded && recents.isEmpty()) {
            EmptyState(Modifier.weight(1f), veil)
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(12.dp)).background(veil).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recent).uppercase(),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = colors.ink2,
                    modifier = Modifier.weight(1f),
                )
                if (recents.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.files, recents.size, recents.size), fontSize = 13.sp, color = colors.ink2)
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(recents, key = { it.uri }) { entry ->
                    SwipeToRemove(onRemove = { onRemove(entry) }) {
                        RecentRow(
                            entry, current = false, onClick = { onOpenRecent(entry) },
                            container = if (image != null) veil else null,
                            trailing = { RecentMenu(entry, onShareRecent, onSaveRecent, onRemove) },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = colors.line)
        Text(
            stringResource(R.string.home_footer),
            modifier = Modifier.fillMaxWidth().background(veil).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
            fontSize = 13.sp, lineHeight = 19.sp, color = colors.ink2,
        )
    }
    }

    if (about) AboutDialog { about = false }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_recents_title)) },
            text = { Text(stringResource(R.string.clear_recents_body)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearAll() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val colors = Odv.colors
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value != SwipeToDismissBoxValue.Settled) onRemove()
        value != SwipeToDismissBoxValue.Settled
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(colors.paper2).padding(horizontal = 20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Text(stringResource(R.string.remove_from_recents), color = colors.accentInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { content() }
}

@Composable
fun RecentRow(
    entry: RecentEntry,
    current: Boolean,
    onClick: () -> Unit,
    container: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    showMeta: Boolean = true,
) {
    val colors = Odv.colors
    val type = remember(entry.name, entry.mimeType) { DocumentTypes.detect(entry.name, entry.mimeType) }
    val (bg, fg) = badgeColors(type.kind, colors)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .padding(vertical = if (container != null) 3.dp else 0.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container ?: if (current) colors.paper2 else colors.paper)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = if (trailing != null) 0.dp else 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
            if (entry.isFolder) {
                Icon(painterResource(R.drawable.ic_folder), null, tint = colors.accentInk, modifier = Modifier.size(22.dp))
            } else {
                Text(type.badge.take(4), fontFamily = MonoFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showMeta) Text(recentMeta(entry), fontSize = 13.sp, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (current) Text(stringResource(R.string.open_now), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accentInk)
        trailing?.invoke()
    }
}

@Composable
private fun recentMeta(entry: RecentEntry): String {
    val when_ = DateUtils.getRelativeTimeSpanString(entry.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    return if (!entry.persisted) {
        listOfNotNull(entry.location, stringResource(R.string.reopen_from_origin)).joinToString(" · ")
    } else {
        listOfNotNull(entry.location, when_).joinToString(" · ")
    }
}

private fun badgeColors(kind: DocumentKind, c: OdvColors): Pair<Color, Color> = when (kind) {
    DocumentKind.MARKDOWN -> c.accent.copy(alpha = 0.22f).compositeOver(c.paper) to c.accentInk
    DocumentKind.CSV, DocumentKind.TSV, DocumentKind.DOTENV, DocumentKind.JSON ->
        c.syntax.number.copy(alpha = 0.22f).compositeOver(c.paper) to c.ink
    else -> c.paper2 to c.ink
}

@Composable
private fun EmptyState(modifier: Modifier, veil: Color) {
    val colors = Odv.colors
    Column(modifier.fillMaxWidth().padding(32.dp).wrapContentHeight().clip(RoundedCornerShape(20.dp)).background(veil).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(calangoFor(Odv.colors)), null, Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.empty_body), fontSize = 14.sp, color = colors.ink2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val colors = Odv.colors
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { uri.openUri(BuildInfo.SOURCE_URL) }) { Text(stringResource(R.string.source_code)) } },
        icon = { Image(painterResource(calangoFor(Odv.colors)), null, Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.about_version, BuildInfo.versionName()), color = colors.ink2, fontSize = 14.sp)
                Text(stringResource(R.string.about_body), fontSize = 14.sp, color = colors.ink)
                Text(stringResource(R.string.about_licenses), fontSize = 12.sp, lineHeight = 17.sp, color = colors.ink2)
                TextButton(onClick = { uri.openUri("https://libre-apps.org/open-documents-viewer/") }) {
                    Text(stringResource(R.string.about_site))
                }
            }
        },
    )
}

/** The calango in the color that reads on the current theme. */
fun calangoFor(colors: OdvColors): Int = if (colors.dark) R.drawable.calango_ochre else R.drawable.calango

@Composable
private fun RecentMenu(
    entry: RecentEntry,
    onShare: (RecentEntry) -> Unit,
    onSave: (RecentEntry) -> Unit,
    onRemove: (RecentEntry) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options_for, entry.name), tint = Odv.colors.ink2)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!entry.isFolder) {
                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { open = false; onShare(entry) })
                DropdownMenuItem(text = { Text(stringResource(R.string.save_copy)) }, onClick = { open = false; onSave(entry) })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_recents)) }, onClick = { open = false; onRemove(entry) })
        }
    }
}
