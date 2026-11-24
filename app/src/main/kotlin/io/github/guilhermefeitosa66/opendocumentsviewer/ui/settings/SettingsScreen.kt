package io.github.guilhermefeitosa66.opendocumentsviewer.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.AppTheme
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.OdvColors
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Themes
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.rememberBackground

@Composable
fun SettingsScreen(
    themeId: String,
    backgroundEnabled: Boolean,
    onTheme: (String) -> Unit,
    onBackground: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val colors = Odv.colors
    val selected = Themes.byId(themeId)
    Column(Modifier.fillMaxSize().background(colors.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.settings), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            SectionTitle(stringResource(R.string.theme))
            // Two previews per row.
            Themes.all.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { theme ->
                        ThemePreview(
                            theme = theme,
                            selected = theme.id == selected.id,
                            showImage = backgroundEnabled,
                            onClick = { onTheme(theme.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(R.string.background_section))
            val hasImage = selected.background != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .toggleable(value = backgroundEnabled, enabled = hasImage, role = Role.Switch, onValueChange = onBackground)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.background_image), fontSize = 16.sp, color = if (hasImage) colors.ink else colors.ink2)
                    Text(
                        stringResource(if (hasImage) R.string.background_image_desc else R.string.background_none),
                        fontSize = 13.sp, lineHeight = 18.sp, color = colors.ink2,
                    )
                }
                Switch(
                    checked = backgroundEnabled && hasImage,
                    onCheckedChange = null,
                    enabled = hasImage,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Odv.colors.ink2,
    )
}

@Composable
private fun ThemePreview(theme: AppTheme, selected: Boolean, showImage: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val current = Odv.colors
    val shape = RoundedCornerShape(18.dp)
    val widthPx = with(LocalDensity.current) { 160.dp.roundToPx() }
    val image by rememberBackground(if (showImage) theme.background else null, widthPx)
    Column(
        modifier
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) current.accent else current.line, shape),
        ) {
            if (theme.followsSystem) {
                // Light and dark side by side: this one follows the system.
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(theme.light.paper))
                    Box(Modifier.weight(1f).fillMaxHeight().background(theme.dark.paper))
                }
                Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    MiniDocument(theme.light, Modifier.weight(1f))
                    MiniDocument(theme.dark, Modifier.weight(1f))
                }
                Image(
                    painterResource(io.github.guilhermefeitosa66.opendocumentsviewer.ui.home.calangoFor(Odv.colors)), null,
                    Modifier.align(Alignment.TopCenter).padding(top = 18.dp).size(36.dp),
                )
            } else {
                val c = theme.light
                Box(Modifier.fillMaxSize().background(c.paper))
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                MiniDocument(c, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp))
            }
        }
        Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                theme.name ?: stringResource(R.string.theme_default),
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = current.ink,
                modifier = Modifier.weight(1f),
            )
            if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = current.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/** A few lines of code in the theme's colors, on its solid document color. */
@Composable
private fun MiniDocument(c: OdvColors, modifier: Modifier) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.headingColor ?: c.ink, fontWeight = FontWeight.Bold)) { append("# Notes\n") }
        withStyle(SpanStyle(color = c.syntax.keyword)) { append("fun ") }
        withStyle(SpanStyle(color = c.syntax.function)) { append("open") }
        withStyle(SpanStyle(color = c.ink)) { append("() {\n  ") }
        withStyle(SpanStyle(color = c.syntax.string)) { append("\"fast\"") }
        withStyle(SpanStyle(color = c.ink)) { append("\n  ") }
        withStyle(SpanStyle(color = c.syntax.comment)) { append("// offline") }
        withStyle(SpanStyle(color = c.ink)) { append("\n}") }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.paper)
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Text(text, fontFamily = MonoFamily, fontSize = 8.sp, lineHeight = 11.sp, maxLines = 5, softWrap = false)
    }
}
