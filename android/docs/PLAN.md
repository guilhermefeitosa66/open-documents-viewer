# Open Documents Viewer for Android: plan for v0.1

Status: **approved 2026-11-24; v0.1 implemented** (see CLAUDE.md for what shipped and the measurements). Themes (libre-apps-themes) were added to v0.1 on request.
Screens: Claude Design canvas "Open Documents Viewer — Android" (link in the conversation).

## 1. What the app is

A fast, offline, read-only viewer for text documents on Android. Kotlin + Jetpack Compose,
no WebView, no network permission, no account, no ads, no telemetry. Sister app of
`linux/` (GTK4, in this same repository): same name, same idea, same libre-apps palette, each with
the UX of its own platform.

## 2. Desktop features, translated to a phone

| Desktop feature | On Android | Why |
|---|---|---|
| Open file | **Yes, v0.1.** `ACTION_OPEN_DOCUMENT` (SAF) from the home screen. | The basic job. SAF needs no storage permission. |
| "Open with" from the file manager | **Yes, v0.1, the main entry point.** `ACTION_VIEW` and `ACTION_SEND` (share) intent filters. Shared *plain text* (`EXTRA_TEXT`) opens too, so a markdown snippet from a chat can be read rendered. | On a phone most files are opened from another app (Files, Telegram, Termux, Obsidian, Gmail), not from inside the viewer. |
| Formatted / source toggle | **Yes, v0.1.** A floating two-state pill at the bottom of the screen ("Formatted · Source"), in thumb reach; hidden on types with a single view (code, txt). | The core idea shared with desktop. Bottom placement because the top bar of a 390 dp screen has no room for a segmented control next to the file name. |
| Search in document | **Yes, v0.1.** Search bar replaces the title; count "2 of 5", previous/next, match ticks on the scrollbar; case / whole word / regex toggles. | On a small screen, scrolling to find something in a long file is the slowest thing you can do. |
| Recent files | **Yes, v0.1, it is the home screen.** | Replaces the desktop's tabs and the "last session". |
| Tabs | **No, replaced by a quick switcher (v0.1).** Tap the title (or swipe it sideways) to get a bottom sheet with the last documents, each reopened at the position where you left it. | Real tabs waste vertical space and are hard to hit on a phone. Android users already switch apps through recents; a switcher that remembers scroll position gives the same benefit with no chrome. |
| Folder tree (VS Code style) | **Yes, but simpler and in v0.2.** `ACTION_OPEN_DOCUMENT_TREE`, one level at a time with a breadcrumb, not an expanding tree. | Useful for Termux checkouts and Obsidian vaults, and it makes relative links between `.md` files work (only possible with tree access). A deep tree does not fit a phone width, a drill-down list does. Not in v0.1 to keep the first release small. |
| Split view | **Only on large screens (v0.2).** At the *expanded* window size class (tablet, open foldable, desktop mode): recents/folder list on the left, document on the right. Two documents side by side: no own UI; the system split-screen already does it if each external "open with" goes to its own task (to evaluate in v0.2). | On a phone two panes of 195 dp are unreadable. |
| Editing | **Never.** "Open with another app" in the menu hands the file to an editor. Text is selectable and copyable. | Same promise as desktop. |

Mobile-only extras that earn their place:

- **Word wrap toggle** for source and code (on by default for prose, off for code), and pinch to zoom the text size (remembered per type).
- **Hide values in `.env`-like files by default.** Phones get read in public; one tap reveals one value. It is a view setting, not security.
- **Pretty-print as the formatted view of JSON** (minified JSON is unreadable on a phone) and a **table view for CSV**.
- **Remember position** per document (scroll offset, view mode, search query).
- Light/dark following the system, with the libre-apps tokens.

## 3. v0.1 scope

In:

1. Home: Open file, Open folder (button visible but goes to v0.2 if it slips), recents list (remove with swipe, clear all in the menu).
2. Open from other apps: `VIEW` and `SEND` for `text/*`, JSON, XML, YAML, TOML, shell, `application/octet-stream` (many file managers send `.md`, `.env`, `.kt` with this type; binary content is detected and refused with a clear message).
3. Markdown (CommonMark + GFM tables, task lists, strikethrough, autolinks) rendered natively, with highlighted fenced code.
4. Plain text, logs.
5. Code with syntax highlighting: Kotlin, Java, C/C++, C#, Go, Rust, Swift, Dart, JavaScript/TypeScript, Python, Ruby, PHP, shell, SQL, Lua, HTML/CSS (first batch; more are just new definitions).
6. Config/data: JSON (pretty-print view), YAML, TOML, INI/properties, XML, dotenv (hidden values), CSV/TSV (simple table view).
7. Formatted/source toggle, word wrap, line numbers in source, search, copy all, share, open with another app, file info.
8. Quick switcher.
9. Light and dark theme; English, Portuguese (Brazil), Spanish.

Out of v0.1: folder browsing, split pane on tablets, office formats (stage 2), PDF (stage 3).

## 4. Architecture

Single Gradle module `app` to start (split later only if build time asks for it). No DI framework,
no Room, no navigation library: two screens and a sheet do not need them, and every library
initialized at startup costs cold start time.

```
app/src/main/kotlin/.../
  App.kt, MainActivity.kt         single activity, handles all intents
  document/   open via ContentResolver, size and binary sniffing, charset detection
              (BOM, UTF-8 validation, fallback windows-1252), line index
  syntax/     own incremental lexer engine + language definitions (data, not code)
  markdown/   commonmark-java AST -> list of immutable render blocks
  formats/    json pretty-printer, csv splitter, dotenv parser
  recents/    small JSON file in app storage (no DataStore/Room), written off the main thread
  ui/home, ui/viewer, ui/theme
```

The pure Kotlin parts (`document`, `syntax`, `markdown`, `formats`) have no Android imports and are
unit tested on the JVM, where speed tests are cheap.

### Performance plan (the number one requirement)

- **No WebView.** Rendering markdown in a WebView costs a Chromium process start (hundreds of ms on a
  mid phone) and memory; Compose text is enough for CommonMark + GFM. Revisit only for math/mermaid,
  with a measurement.
- **Everything virtualized.** Source/code/text views are a `LazyColumn` of lines; markdown is a
  `LazyColumn` of top-level blocks. Only visible lines are highlighted; the lexer keeps the state at
  the start of each line (inside a block comment, a string...) computed in one background pass, so
  any line can be tokenized on demand. Results are cached in a small LRU.
- **Progressive open.** Read and show the first screen before the whole file is indexed; parsing,
  line indexing and lexer state run on `Dispatchers.Default` and the list grows as it goes.
- **Large files.** Up to ~8 MB in memory. Above that, a "large file" mode reads windows of the file
  through the file descriptor (seekable providers) with plain text only, no formatted view; a
  file with very long lines (minified JSON) is shown wrapped in fixed chunks so no single item is huge.
- **Startup.** Baseline profile, R8 full mode, nothing heavy in `Application.onCreate`, the home reads
  only the small recents file. Fonts: system UI font; one bundled monospace font (see open questions).
- **Measured, with budgets.** Macrobenchmark module with `StartupTimingMetric` (cold start) and a
  custom trace section for "intent received -> first lines drawn". Initial budgets on a mid-range
  device (to be confirmed after the first measurement): cold start to first frame < 400 ms; 100 KB
  markdown from "open with" to content < 200 ms; 10 MB log keeps 60 fps scrolling with no jank > 50 ms.
  A regression against the budget is a bug.

Known limitation to tell the truth about: Compose text selection does not span several items of a
`LazyColumn`, so selecting across paragraphs is limited. "Copy all" and copying a block cover the
common cases; a custom selection layer can come later if it hurts.

## 5. Stack and licenses

| Dependency | License | Use |
|---|---|---|
| Kotlin, kotlinx-coroutines | Apache-2.0 | language, background work |
| AndroidX Activity, Lifecycle, Core | Apache-2.0 | platform |
| Jetpack Compose (UI, Foundation, Material 3) | Apache-2.0 | UI |
| commonmark-java + GFM extensions (tables, strikethrough, task lists, autolink) | BSD-2-Clause | markdown parser, fast, small, with source positions (for search mapping) |
| JetBrains Mono (font, optional) | OFL-1.1 | code font |
| AndroidX Benchmark / ProfileInstaller (test and profile only) | Apache-2.0 | measurements, baseline profile |

Syntax highlighting is our own code. Alternatives looked at: TextMate grammars (tm4e, EPL-2.0, heavy
regex engine and a large startup cost), Sora editor (LGPL-2.1, and it is an editor), tree-sitter on
Android (native libs per ABI, larger APK, bindings with mixed licenses), Prism4j (unmaintained). A
small state-machine lexer with per-language tables is faster on a phone, has no license strings
attached and is enough for reading (not for semantic analysis).

**App license: Apache-2.0**, like OpenTuner and SLAP Mobile. All dependencies above are compatible.

- `minSdk 26` (Android 8.0): covers practically all active devices, gives adaptive icons and modern
  `java.time`/NIO without desugaring, and keeps old-version workarounds out. `targetSdk`: latest stable.
- applicationId: `io.github.guilhermefeitosa66.opendocumentsviewer` (same pattern as OpenTuner),
  fixed after the first publication.
- No `INTERNET` permission in the merged manifest; a dependency that adds it is refused.

## 6. Visual identity

The libre-apps tokens from `site-libre-apps-org/brand`: paper `#F7EEDF` / night `#1E1612`
backgrounds, terracotta `#B5502B` accent in light, ochre `#D9973A` in dark, rust `#8E3A1E` for
small accent text (terracotta fails 4.5:1 on paper for text). Syntax colors derived from the same
palette, checked for contrast in both themes. App icon: the calango on terracotta with a document
fold (to design with the desktop agent so both apps share it).

## 7. Delivery steps

1. Repository, Gradle skeleton, CI (build + unit tests), README, LICENSE, CLAUDE.md, macrobenchmark
   module with the first cold start number.
2. Text and code: document loading, line index, virtualized source view, highlighting, search.
3. Markdown formatted view, toggle, JSON/CSV/dotenv formatted views.
4. Home with recents, open with/share, quick switcher, remembered position.
5. Polish, translations, icon, first release on GitHub (APK), site page branch.
6. v0.2: folder browsing, relative links, tablet two-pane.

## 8. Open questions

1. Name shown under the launcher icon: "Open Documents Viewer" gets cut on most launchers. Use a
   short label (e.g. "Open Docs") with the full name in the store and site? To agree with desktop.
2. Recents opened through "open with" only have a temporary permission. Keep a private copy of the
   file so it can be reopened later, or show "reopen it from the app it came from"? Proposal: no
   copy (a `.env` copy kept by the viewer would be a surprise), unless the user opened it through
   the app's own picker.
3. Register `application/octet-stream` (appears in the chooser for more files, but `.md`/`.env`
   sent by many file managers only work this way)? Proposal: yes.
4. Bundle JetBrains Mono (~+250 KB, same look as the desktop) or use the system monospace? Proposal:
   bundle, two weights.
5. Hide `.env` values by default? Proposal: yes.

## 9. After v0.1: every document format (November 2026)

Stages A to D of the task list took the viewer from text to "any document", still read-only and offline.

- **Files and navigation (A):** share and save a copy, folders through SAF trees, relative links, light tabs,
  two panes and split view from 840 dp, outline, reload on change, full screen.
- **More text formats (B):** native safe HTML, images and SVG, RTF, Jupyter, 60+ languages, JSON/YAML/XML trees,
  sortable CSV, encodings picked by hand, EPUB and FB2, `.eml`/`.vcf`/`.ics`.
- **Office (C):** own readers instead of Apache POI (too heavy and awkward on Android): WordprocessingML and ODF
  text into the markdown block model, SpreadsheetML/ODS/BIFF8 into a shared `Workbook` model drawn by a virtualized
  grid, PresentationML/ODP/PPT into a shared slide model drawn on a canvas. An own OLE2 (CFB) reader serves the
  97–2003 formats; the Word and PowerPoint ones give text only. Encrypted OOXML is decrypted in memory (ECMA-376
  agile and standard) with the platform's crypto. Number formats, the ODS/PPTX/ODP/DOC/PPT readers are ports of the
  Linux app's code.
- **PDF (D):** PDFium through a read-only subset of Open PDF Viewer's JNI layer (same author), built in the app;
  the binaries are fetched pinned by `tools/fetch-pdfium.sh`. Open PDF Viewer is offered for PDF tools.

Rules kept: every format loads on demand (nothing new in the cold start path; PDFium is loaded with the first
PDF), parsing runs off the main thread, big sheets and documents are virtualized, archives are kept as bytes and
pictures are read from them when shown (`zip:` sources), nothing is copied into app storage, and a partial render
says so with a short notice instead of an error screen.

Cost: the release APK went from 1.8 MB (v0.1) to 21.6 MB, almost all of it PDFium for three ABIs. Splitting the
APK per ABI (or an App Bundle in the store) brings each install back to about 8 MB.
