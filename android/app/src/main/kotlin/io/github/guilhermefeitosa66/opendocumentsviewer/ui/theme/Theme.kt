package io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.TokenType

/** The libre-apps tokens (site-libre-apps-org/brand), plus what a document viewer needs. */
@Immutable
data class OdvColors(
    val paper: Color,
    val paper2: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val accent: Color,
    val accentInk: Color,
    val onAccent: Color,
    val gutter: Color,
    val codeBackground: Color,
    val inlineCode: Color,
    val quoteBackground: Color,
    val match: Color,
    val currentMatch: Color,
    val onCurrentMatch: Color,
    val pill: Color,
    val onPill: Color,
    val pillSelected: Color,
    val dark: Boolean,
    val syntax: SyntaxColors,
    /** Markdown headings; null means the text color. */
    val headingColor: Color? = null,
    /** A bar beside quotes, for themes that define one. */
    val quoteBar: Color? = null,
    val rule: Color = line,
    /** Text selection; null keeps the platform default derived from the accent. */
    val selection: Color? = null,
)

/** A color theme: libre-apps follows the system light/dark, the shared extras have one scheme. */
@Immutable
data class AppTheme(
    val id: String,
    /** Display name; null for the default, which is named by a string resource. */
    val name: String?,
    /** Asset path of the portrait background image, if the theme has one. */
    val background: String?,
    val light: OdvColors,
    val dark: OdvColors = light,
) {
    val followsSystem: Boolean get() = light != dark
    fun colors(systemDark: Boolean): OdvColors = if (systemDark) dark else light
}

object Themes {
    const val DEFAULT_ID = "libre-apps"
    val LIBRE_APPS = AppTheme(DEFAULT_ID, null, null, LightColors, DarkColors)
    val all: List<AppTheme> by lazy { listOf(LIBRE_APPS) + ThemeCatalog.extras }
    fun byId(id: String?): AppTheme = all.firstOrNull { it.id == id } ?: LIBRE_APPS
}

@Immutable
data class SyntaxColors(
    val keyword: Color,
    val literal: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val annotation: Color,
    val variable: Color,
    val key: Color,
    val section: Color,
    val tag: Color,
    val attribute: Color,
    val punctuation: Color,
    val heading: Color,
    val markup: Color,
    val link: Color,
    val code: Color,
) {
    fun of(type: TokenType): Color = when (type) {
        TokenType.KEYWORD -> keyword
        TokenType.LITERAL -> literal
        TokenType.TYPE -> this.type
        TokenType.STRING -> string
        TokenType.NUMBER -> number
        TokenType.COMMENT -> comment
        TokenType.FUNCTION -> function
        TokenType.ANNOTATION -> annotation
        TokenType.VARIABLE -> variable
        TokenType.KEY -> key
        TokenType.SECTION -> section
        TokenType.TAG -> tag
        TokenType.ATTRIBUTE -> attribute
        TokenType.PUNCTUATION -> punctuation
        TokenType.HEADING -> heading
        TokenType.MARKUP -> markup
        TokenType.LINK -> link
        TokenType.CODE -> code
    }
}

// Light syntax colors keep at least 4.5:1 on paper (#F7EEDF).
private val LightSyntax = SyntaxColors(
    keyword = Color(0xFF8E3A1E),
    literal = Color(0xFF8A4B00),
    type = Color(0xFF1D5F70),
    string = Color(0xFF486220),
    number = Color(0xFF8A4B00),
    comment = Color(0xFF7A6352),
    function = Color(0xFF6E3A78),
    annotation = Color(0xFF6E3A78),
    variable = Color(0xFF1D5F70),
    key = Color(0xFF8E3A1E),
    section = Color(0xFF1D5F70),
    tag = Color(0xFF8E3A1E),
    attribute = Color(0xFF8A4B00),
    punctuation = Color(0xFF6B5242),
    heading = Color(0xFF8E3A1E),
    markup = Color(0xFFA2461F),
    link = Color(0xFF1D5F70),
    code = Color(0xFF486220),
)

private val DarkSyntax = SyntaxColors(
    keyword = Color(0xFFE8B062),
    literal = Color(0xFFF29A5E),
    type = Color(0xFF86BFD0),
    string = Color(0xFFB9CC8C),
    number = Color(0xFFF29A5E),
    comment = Color(0xFF9F8A78),
    function = Color(0xFFF3C9A0),
    annotation = Color(0xFFD9A6E0),
    variable = Color(0xFF86BFD0),
    key = Color(0xFFE8B062),
    section = Color(0xFF86BFD0),
    tag = Color(0xFFE8B062),
    attribute = Color(0xFFF3C9A0),
    punctuation = Color(0xFFBBA58F),
    heading = Color(0xFFE8B062),
    markup = Color(0xFFD9973A),
    link = Color(0xFF86BFD0),
    code = Color(0xFFB9CC8C),
)

val LightColors = OdvColors(
    paper = Color(0xFFF7EEDF),
    paper2 = Color(0xFFEFE2CC),
    card = Color(0xFFFCF7EE),
    ink = Color(0xFF2A1A12),
    ink2 = Color(0xFF6B5242),
    line = Color(0xFFE2D2B9),
    accent = Color(0xFFB5502B),
    accentInk = Color(0xFF8E3A1E),
    onAccent = Color(0xFFFFFFFF),
    gutter = Color(0xFF7D6656),
    codeBackground = Color(0xFFFCF7EE),
    inlineCode = Color(0xFFEFE2CC),
    quoteBackground = Color(0xFFEFE2CC),
    match = Color(0xFFF5D7A1),
    currentMatch = Color(0xFFD9973A),
    onCurrentMatch = Color(0xFF1E1612),
    pill = Color(0xFF2A1A12),
    onPill = Color(0xFFF3E7D8),
    pillSelected = Color(0xFFF7EEDF),
    dark = false,
    syntax = LightSyntax,
)

val DarkColors = OdvColors(
    paper = Color(0xFF1E1612),
    paper2 = Color(0xFF251B16),
    card = Color(0xFF2A1F19),
    ink = Color(0xFFF3E7D8),
    ink2 = Color(0xFFBBA58F),
    line = Color(0xFF3E2E24),
    accent = Color(0xFFD9973A),
    accentInk = Color(0xFFE8B062),
    onAccent = Color(0xFF1E1612),
    gutter = Color(0xFF9C8775),
    codeBackground = Color(0xFF2A1F19),
    inlineCode = Color(0xFF3E2E24),
    quoteBackground = Color(0xFF2A1F19),
    match = Color(0xFF5A4126),
    currentMatch = Color(0xFFD9973A),
    onCurrentMatch = Color(0xFF1E1612),
    pill = Color(0xFFF3E7D8),
    onPill = Color(0xFF2A1A12),
    pillSelected = Color(0xFF1E1612),
    dark = true,
    syntax = DarkSyntax,
)

val LocalOdvColors = staticCompositionLocalOf { LightColors }

val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

private fun scheme(c: OdvColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.paper2,
        onPrimaryContainer = c.ink,
        secondary = c.accentInk,
        onSecondary = c.onAccent,
        secondaryContainer = c.paper2,
        onSecondaryContainer = c.ink,
        tertiary = c.accentInk,
        background = c.paper,
        onBackground = c.ink,
        surface = c.paper,
        onSurface = c.ink,
        surfaceVariant = c.paper2,
        onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.paper,
        surfaceContainerLow = c.card,
        surfaceContainer = c.card,
        surfaceContainerHigh = c.card,
        surfaceContainerHighest = c.paper2,
        surfaceBright = c.card,
        inverseSurface = c.pill,
        inverseOnSurface = c.onPill,
        outline = c.ink2,
        outlineVariant = c.line,
    )
}

@Composable
fun OdvTheme(theme: AppTheme = Themes.LIBRE_APPS, content: @Composable () -> Unit) {
    val colors = theme.colors(isSystemInDarkTheme())
    val selection = colors.selection
    val selectionColors = if (selection != null) TextSelectionColors(handleColor = colors.accent, backgroundColor = selection)
    else TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = 0.35f))
    CompositionLocalProvider(LocalOdvColors provides colors) {
        MaterialTheme(colorScheme = scheme(colors)) {
            CompositionLocalProvider(LocalTextSelectionColors provides selectionColors, content = content)
        }
    }
}

object Odv {
    val colors: OdvColors
        @Composable get() = LocalOdvColors.current
}
