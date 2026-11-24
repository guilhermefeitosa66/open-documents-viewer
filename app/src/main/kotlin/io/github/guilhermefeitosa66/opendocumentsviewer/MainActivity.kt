package io.github.guilhermefeitosa66.opendocumentsviewer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toArgb
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.settings.SettingsScreen
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.folder.FolderScreen
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Themes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.compose.material.icons.filled.Close
import io.github.guilhermefeitosa66.opendocumentsviewer.document.FileTransfer
import io.github.guilhermefeitosa66.opendocumentsviewer.folder.RelativePath
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LoadedDocument
import io.github.guilhermefeitosa66.opendocumentsviewer.document.Shareable
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.RecentEntry
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.home.HomeScreen
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.home.RecentRow
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.OdvTheme
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.DocumentSession
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.Formatted
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.ViewMode
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.ViewerActions
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.ViewerScreen
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.SplitControls
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri, null, external = false, fromPicker = true)
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openFolder(uri, fromPicker = true)
    }

    private fun openFolderPicker() {
        try {
            pickFolder.launch(null)
        } catch (_: ActivityNotFoundException) {
            vm.message = R.string.error_no_picker
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val theme = Themes.byId(vm.themeId)
            val colors = theme.colors(isSystemInDarkTheme())
            // System bar icons and the window behind Compose follow the chosen theme, not only the system.
            LaunchedEffect(colors) {
                val bar = if (colors.dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
                window.setBackgroundDrawable(ColorDrawable(colors.paper.toArgb()))
            }
            OdvTheme(theme) {
                Surface(color = colors.paper, contentColor = colors.ink) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        vm.savePosition()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { vm.openUri(it, intent.type, external = true, grantFlags = intent.flags) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    vm.openUri(stream, intent.type, external = true, grantFlags = intent.flags)
                } else {
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    if (!text.isNullOrEmpty()) vm.openSharedText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val session = vm.session
        var switcher by remember { mutableStateOf(false) }
        var splitPicking by remember { mutableStateOf(false) }
        // Tablets, open foldables and wide landscape: list beside the document, and split view.
        val wide = LocalConfiguration.current.screenWidthDp >= WIDE_DP

        if (wide && !vm.settingsOpen) {
            WideLayout(session, onSwitcher = { switcher = true }, onSplitPick = { splitPicking = true })
        } else if (session == null && vm.settingsOpen) {
            SettingsScreen(
                themeId = vm.themeId,
                backgroundEnabled = vm.backgroundEnabled,
                onTheme = vm::selectTheme,
                onBackground = vm::setBackground,
                onBack = { vm.settingsOpen = false },
            )
            BackHandler { vm.settingsOpen = false }
        } else if (session == null && vm.folder != null) {
            val folder = vm.folder!!
            FolderScreen(
                folder = folder,
                onBack = { if (!folder.up()) vm.closeFolder() },
                onOpenFile = { entry -> vm.openUri(folder.documentUri(entry), entry.mimeType, external = false, folderDocumentId = entry.documentId) },
            )
            BackHandler {
                when {
                    folder.filter.isNotEmpty() -> folder.filter = ""
                    !folder.up() -> vm.closeFolder()
                }
            }
        } else if (session == null) {
            val theme = Themes.byId(vm.themeId)
            HomeScreen(
                onSettings = { vm.settingsOpen = true },
                background = theme.background?.takeIf { vm.backgroundEnabled },
                recents = vm.recents,
                loaded = vm.recentsLoaded,
                onOpenFile = ::openPicker,
                onOpenFolder = ::openFolderPicker,
                onOpenRecent = vm::openRecent,
                onRemove = vm::removeRecent,
                onClearAll = vm::clearRecents,
                onShareRecent = { if (!it.isFolder) shareItem(shareableOf(it)) },
                onSaveRecent = { if (!it.isFolder) saveItem(shareableOf(it)) },
            )
        } else {
            val base = 16f
            ViewerScreen(
                session = session,
                proseSize = (base * vm.proseScale).sp,
                codeSize = (13.5f * vm.codeScale).sp,
                actions = remember(session) { actions(session) { switcher = true } },
            )
            BackHandler {
                when {
                    session.searchOpen -> session.closeSearch()
                    else -> leaveViewer(session)
                }
            }
        }

        if (splitPicking && session != null) {
            ModalBottomSheet(onDismissRequest = { splitPicking = false }, containerColor = Odv.colors.card) {
                val currentKey = vm.keyOf(session)
                QuickSwitcher(
                    tabs = vm.tabs.filter { it.key != currentKey },
                    recents = vm.recents.filter { r -> !r.isFolder && r.uri != currentKey && vm.tabs.none { it.key == r.uri } },
                    currentKey = null,
                    onTab = { tab -> splitPicking = false; vm.splitWith(tab.key, tab.mimeType, tab.shared) },
                    onCloseTab = null,
                    onPick = { entry -> splitPicking = false; vm.splitWith(entry.uri, entry.mimeType, null) },
                    onAllRecents = null,
                    onOpenFile = null,
                    title = stringResource(R.string.split_pick_title),
                )
            }
        }

        vm.copyProgress?.let { CopyProgressDialog(it) }

        pendingExternalLink?.let { url ->
            ExternalLinkDialog(
                url = url,
                onOpen = {
                    pendingExternalLink = null
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        vm.message = R.string.error_no_app_for_link
                    }
                },
                onDismiss = { pendingExternalLink = null },
            )
        }

        val message = vm.message
        LaunchedEffect(message) {
            if (message != null) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                vm.message = null
            }
        }

        if (switcher && session != null) {
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()
            fun hide(then: () -> Unit) {
                scope.launch { sheet.hide() }.invokeOnCompletion { switcher = false; then() }
            }
            ModalBottomSheet(onDismissRequest = { switcher = false }, sheetState = sheet, containerColor = Odv.colors.card) {
                val currentKey = vm.keyOf(session)
                QuickSwitcher(
                    tabs = vm.tabs,
                    recents = vm.recents.filter { r -> !r.isFolder && vm.tabs.none { it.key == r.uri } },
                    currentKey = currentKey,
                    onTab = { tab -> hide { if (tab.key != currentKey) vm.activateTab(tab) } },
                    onCloseTab = { tab -> vm.closeTab(tab.key); if (vm.session == null) switcher = false },
                    onPick = { entry -> hide { vm.openRecent(entry) } },
                    onAllRecents = { hide { vm.closeSession() } },
                    onOpenFile = { hide { openPicker() } },
                )
            }
        }
    }

    @Composable
    private fun ListPane() {
        val folder = vm.folder
        if (folder != null) {
            FolderScreen(
                folder = folder,
                onBack = { if (!folder.up()) vm.closeFolder() },
                onOpenFile = { entry -> vm.openUri(folder.documentUri(entry), entry.mimeType, external = false, folderDocumentId = entry.documentId) },
            )
        } else {
            val theme = Themes.byId(vm.themeId)
            HomeScreen(
                onSettings = { vm.settingsOpen = true },
                background = theme.background?.takeIf { vm.backgroundEnabled },
                recents = vm.recents,
                loaded = vm.recentsLoaded,
                onOpenFile = ::openPicker,
                onOpenFolder = ::openFolderPicker,
                onOpenRecent = vm::openRecent,
                onRemove = vm::removeRecent,
                onClearAll = vm::clearRecents,
                onShareRecent = { if (!it.isFolder) shareItem(shareableOf(it)) },
                onSaveRecent = { if (!it.isFolder) saveItem(shareableOf(it)) },
            )
        }
    }

    @Composable
    private fun WideLayout(session: DocumentSession?, onSwitcher: () -> Unit, onSplitPick: () -> Unit) {
        val colors = Odv.colors
        val split = vm.split
        Row(Modifier.fillMaxSize()) {
            // The list makes room when two documents share the screen.
            if (split == null || session == null) {
                Box(Modifier.width(LIST_PANE_DP.dp).fillMaxHeight()) { ListPane() }
                androidx.compose.material3.VerticalDivider(color = colors.line)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (session != null) {
                    ViewerScreen(
                        session = session,
                        proseSize = (16f * vm.proseScale).sp,
                        codeSize = (13.5f * vm.codeScale).sp,
                        actions = remember(session) { actions(session) { onSwitcher() } },
                        split = SplitControls(
                            inSplit = split != null,
                            onSplitSame = vm::splitSameDocument,
                            onSplitOther = onSplitPick,
                            onCloseSplit = vm::closeSplit,
                        ),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(colors.paper), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.wide_empty), color = colors.ink2, fontSize = 16.sp)
                    }
                }
            }
            if (split != null && session != null) {
                androidx.compose.material3.VerticalDivider(color = colors.line)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ViewerScreen(
                        session = split,
                        proseSize = (16f * vm.proseScale).sp,
                        codeSize = (13.5f * vm.codeScale).sp,
                        actions = remember(split) { splitActions(split, onSplitPick) },
                        split = SplitControls(true, {}, onSplitPick, vm::closeSplit),
                    )
                }
            }
        }
        BackHandler(enabled = session != null || vm.folder != null) {
            when {
                split?.searchOpen == true -> split.closeSearch()
                split != null -> vm.closeSplit()
                session?.searchOpen == true -> session.closeSearch()
                session != null -> leaveViewer(session)
                vm.folder?.filter?.isNotEmpty() == true -> vm.folder?.filter = ""
                vm.folder?.up() == true -> Unit
                else -> vm.closeFolder()
            }
        }
    }

    /** The second pane's actions: back closes the split, the title picks another document. */
    private fun splitActions(split: DocumentSession, onPick: () -> Unit): ViewerActions {
        val base = actions(split) { onPick() }
        return object : ViewerActions by base {
            override fun back() = vm.closeSplit()
        }
    }

    private fun leaveViewer(session: DocumentSession) {
        if (session.external) {
            vm.closeSession()
            finish()
        } else {
            vm.closeSession()
        }
    }

    private fun openPicker() {
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            vm.message = R.string.error_no_picker
        }
    }

    private fun actions(session: DocumentSession, openSwitcher: () -> Unit) = object : ViewerActions {
        override fun back() = leaveViewer(session)

        override fun openSwitcher() = openSwitcher()

        override fun copyAll(doc: LoadedDocument) {
            val text = currentText(session, doc)
            if (text.length > MAX_CLIP_CHARS) {
                vm.message = R.string.too_large_to_copy
                return
            }
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText(doc.name, text))
            // Android 13+ shows its own confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) vm.message = R.string.copied
        }

        override fun share(doc: LoadedDocument) = shareItem(shareableOf(doc))

        override fun shareAsText(doc: LoadedDocument) {
            startChooser(FileTransfer.shareIntent(shareableOf(doc), asText = true, text = doc.text), getString(R.string.share_as_text))
        }

        override fun saveCopy(doc: LoadedDocument) = saveItem(shareableOf(doc))

        override fun openWith(doc: LoadedDocument) {
            val uri = doc.uri ?: return
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, doc.mimeType ?: "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startChooser(view, getString(R.string.open_with))
        }

        override fun openLink(url: String, session: DocumentSession) {
            when {
                url.startsWith("#") -> session.jumpToAnchor(url.removePrefix("#"))
                RelativePath.isRelative(url) -> vm.followRelativeLink(session, url)
                else -> pendingExternalLink = url
            }
        }

        override fun zoom(prose: Boolean, factor: Float) = vm.zoom(prose, factor)

        override fun onFirstContent() {
            vm.onFirstContent()
            reportFullyDrawn()
        }
    }

    /** A link out of the app (browser, mail...), waiting for the user to confirm. */
    private var pendingExternalLink by mutableStateOf<String?>(null)

    private val saveCopyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.data
        if (result.resultCode == RESULT_OK && target != null) vm.saveTo(target)
    }

    private fun shareableOf(doc: LoadedDocument): Shareable =
        doc.uri?.let { Shareable.File(it, doc.name, doc.mimeType) } ?: Shareable.Text(doc.text, doc.name)

    private fun shareableOf(entry: RecentEntry): Shareable = Shareable.File(Uri.parse(entry.uri), entry.name, entry.mimeType)

    /** Shares the file itself (WhatsApp, Gmail, Drive...), if this app can still read it. */
    fun shareItem(item: Shareable) {
        if (item is Shareable.File && !vm.canRead(item.uri)) {
            vm.message = R.string.error_permission_body
            return
        }
        startChooser(FileTransfer.shareIntent(item), getString(R.string.share))
    }

    /** "Save a copy...": the user picks a folder, then the file is streamed there. */
    fun saveItem(item: Shareable) {
        if (item is Shareable.File && !vm.canRead(item.uri)) {
            vm.message = R.string.error_permission_body
            return
        }
        vm.prepareSave(item)
        try {
            saveCopyLauncher.launch(FileTransfer.createDocumentIntent(item))
        } catch (_: ActivityNotFoundException) {
            vm.message = R.string.error_no_picker
        }
    }

    /** The text of what is on screen: the pretty JSON when that is shown, the file otherwise. */
    private fun currentText(session: DocumentSession, doc: LoadedDocument): String {
        val f = session.formatted
        return if (session.mode == ViewMode.FORMATTED && f is Formatted.Json) f.content.text else doc.text
    }

    /** A chooser without this app in it (it would only open the file again here). */
    private fun startChooser(intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title).apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this@MainActivity, MainActivity::class.java)))
        }
        try {
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            vm.message = R.string.error_no_app
        }
    }

    companion object {
        /** Window width from which the list and the document sit side by side (expanded size class). */
        const val WIDE_DP = 840
        const val LIST_PANE_DP = 360

        /** Binder transactions above ~1 MB fail; stay well under. */
        const val MAX_CLIP_CHARS = 400_000
    }
}

@Composable
private fun QuickSwitcher(
    tabs: List<AppViewModel.OpenTab>,
    recents: List<RecentEntry>,
    currentKey: String?,
    onTab: (AppViewModel.OpenTab) -> Unit,
    onCloseTab: ((AppViewModel.OpenTab) -> Unit)?,
    onPick: (RecentEntry) -> Unit,
    onAllRecents: (() -> Unit)?,
    onOpenFile: (() -> Unit)?,
    title: String? = null,
) {
    val colors = Odv.colors
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        LazyColumn(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)) {
            if (title != null) item {
                Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            }
            if (tabs.isNotEmpty()) item {
                SwitcherHeader(stringResource(R.string.open_documents))
            }
            items(tabs, key = { "tab:" + it.key }) { tab ->
                val entry = RecentEntry(tab.key, tab.name, tab.mimeType, -1, 0, persisted = true, location = null)
                RecentRow(
                    entry, current = tab.key == currentKey, onClick = { onTab(tab) },
                    trailing = if (onCloseTab == null) null else ({
                        androidx.compose.material3.IconButton(onClick = { onCloseTab(tab) }) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.Close,
                                stringResource(R.string.close_document, tab.name), tint = colors.ink2,
                            )
                        }
                    }),
                    showMeta = false,
                )
            }
            if (recents.isNotEmpty()) {
                item { SwitcherHeader(stringResource(R.string.recent)) }
                items(recents.take(6), key = { it.uri }) { entry ->
                    RecentRow(entry, current = false, onClick = { onPick(entry) })
                }
            }
        }
        if (onAllRecents != null && onOpenFile != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAllRecents, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(stringResource(R.string.all_recents), color = colors.ink)
            }
            Button(
                onClick = onOpenFile,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(stringResource(R.string.open_file))
            }
        }
    }
}

@Composable
private fun SwitcherHeader(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Odv.colors.ink2,
    )
}

@Composable
private fun CopyProgressDialog(progress: Float) {
    val colors = Odv.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(stringResource(R.string.copy_saving)) },
        text = {
            if (progress < 0f) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.paper2)
            } else {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.paper2,
                )
            }
        },
    )
}

@Composable
private fun ExternalLinkDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = Odv.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.open_link_body), color = colors.ink2)
                Text(url, fontFamily = io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily, fontSize = 13.sp, color = colors.ink, maxLines = 4)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onOpen) { Text(stringResource(R.string.open_link_confirm)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
