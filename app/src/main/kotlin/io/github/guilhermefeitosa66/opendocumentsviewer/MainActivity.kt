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
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LoadedDocument
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openUri(uri, null, external = false, fromPicker = true)
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

        if (session == null && vm.settingsOpen) {
            SettingsScreen(
                themeId = vm.themeId,
                backgroundEnabled = vm.backgroundEnabled,
                onTheme = vm::selectTheme,
                onBackground = vm::setBackground,
                onBack = { vm.settingsOpen = false },
            )
            BackHandler { vm.settingsOpen = false }
        } else if (session == null) {
            val theme = Themes.byId(vm.themeId)
            HomeScreen(
                onSettings = { vm.settingsOpen = true },
                background = theme.background?.takeIf { vm.backgroundEnabled },
                recents = vm.recents,
                loaded = vm.recentsLoaded,
                onOpenFile = ::openPicker,
                onOpenRecent = vm::openRecent,
                onRemove = vm::removeRecent,
                onClearAll = vm::clearRecents,
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
                QuickSwitcher(
                    recents = vm.recents,
                    currentKey = session.recentKey,
                    onPick = { entry -> hide { if (entry.uri != session.recentKey) vm.openRecent(entry) } },
                    onAllRecents = { hide { vm.closeSession() } },
                    onOpenFile = { hide { openPicker() } },
                )
            }
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

        override fun share(doc: LoadedDocument) {
            val send = Intent(Intent.ACTION_SEND).apply {
                if (doc.uri != null) {
                    type = doc.mimeType ?: "text/plain"
                    putExtra(Intent.EXTRA_STREAM, doc.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, doc.text.take(MAX_CLIP_CHARS))
                }
                putExtra(Intent.EXTRA_SUBJECT, doc.name)
            }
            startChooser(send, getString(R.string.share))
        }

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
                url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:") || url.startsWith("tel:") -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: ActivityNotFoundException) {
                        vm.message = R.string.error_no_app_for_link
                    }
                }
                else -> vm.message = R.string.relative_links_later
            }
        }

        override fun zoom(prose: Boolean, factor: Float) = vm.zoom(prose, factor)

        override fun onFirstContent() {
            vm.onFirstContent()
            reportFullyDrawn()
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
        /** Binder transactions above ~1 MB fail; stay well under. */
        const val MAX_CLIP_CHARS = 400_000
    }
}

@Composable
private fun QuickSwitcher(
    recents: List<RecentEntry>,
    currentKey: String?,
    onPick: (RecentEntry) -> Unit,
    onAllRecents: () -> Unit,
    onOpenFile: () -> Unit,
) {
    val colors = Odv.colors
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.switch_document),
            modifier = Modifier.padding(horizontal = 24.dp),
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.ink,
        )
        LazyColumn(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            items(recents.take(8), key = { it.uri }) { entry ->
                RecentRow(entry, current = entry.uri == currentKey, onClick = { onPick(entry) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
