# Open Documents Viewer (Android)

A fast, offline, read-only viewer for documents: text, code and data, office files, e-books and PDF. Part of
[libre-apps](https://libre-apps.org). No ads, no account, no network permission, no telemetry. Nothing is ever edited:
"Open with another app" hands the file to an editor.

## Formats

| Family | Formats | How it is shown |
|---|---|---|
| Markdown | `.md` and friends | Rendered natively (CommonMark + GFM), outline of headings, relative links inside a granted folder |
| Code | 60+ languages and config formats, by extension, file name or shebang | Syntax highlighting, line numbers, wrap on/off |
| Data | JSON, JSON Lines, YAML, XML, TOML, INI, `.env`, CSV/TSV | JSON/YAML/XML as a collapsible tree; CSV as a table (sort, resize columns); `.env` with secrets hidden |
| Text | Plain text, logs, subtitles, gettext, diff | Encoding detected (UTF-8/16/32, BOM or not, legacy code pages) or picked by hand; line endings shown |
| Web | HTML, Jupyter notebooks, RTF | Rendered natively and safely (no scripts, no remote content) |
| Images | PNG, JPEG, GIF/WebP (animated), BMP, ICO, HEIC/AVIF where supported, SVG | Zoom and pan |
| E-books | EPUB 2/3 (no DRM), FictionBook `.fb2` | Reflowed chapters, the book's table of contents, pictures |
| Mail and contacts | `.eml`, `.vcf`, `.ics` | Headers and message (attachments can be saved), contact cards, events in your time zone |
| Word processing | `.docx/.docm/.dotx`, `.odt/.ott/.fodt`, `.doc` (text only) | Reflowed: headings, styles, lists, tables, pictures, notes, header and footer |
| Spreadsheets | `.xlsx/.xlsm/.xltx`, `.ods/.ots/.fods`, `.xls` | Sheet tabs, a grid virtualized both ways, number formats, styles, merged cells, frozen panes; tap a cell for its value and formula (never recalculated) |
| Presentations | `.pptx/.ppsx/.potx`, `.odp/.otp/.fodp`, `.odg`, `.ppt` (text only) | Slides drawn to scale, notes, thumbnails, full-screen presenting |
| PDF | `.pdf` | PDFium: continuous scrolling, zoom, thumbnails, search, text selection, bookmarks, links, passwords |
| Others | Apple Pages/Numbers/Keynote | The preview picture the file carries |

Password-protected Word, Excel and PowerPoint 2007+ files and PDFs ask for their password (used in memory only). Macros
are never run.

Also: search with match case, whole word and regex; recent files with reading position; folders (tree, filter,
breadcrumb); light tabs; two panes and split view on tablets; share or save a copy; pinch to zoom; themes libre-apps
(follows the system), Pastel, Dracula, Kitty and Neon (Tokyo Night); English, Portuguese and Spanish.

The Linux app lives in [`../linux`](../linux) in this same repository.

## Build

```sh
tools/fetch-pdfium.sh                            # once: pinned PDFium binaries (SHA-256 checked) into pdfium/prebuilt/
./gradlew --no-daemon :app:testDebugUnitTest   # unit tests (JVM)
./gradlew --no-daemon :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (compileSdk 36). minSdk 26.

## Dependencies and licenses

| Dependency | License |
|---|---|
| Kotlin, kotlinx-coroutines | Apache-2.0 |
| AndroidX (Activity, Lifecycle, Core), Jetpack Compose, Material 3, Material Icons (core and extended) | Apache-2.0 |
| [commonmark-java](https://github.com/commonmark/commonmark-java) and its GFM extensions | BSD-2-Clause |
| [jsoup](https://jsoup.org) (HTML parser) | MIT |
| [AndroidSVG](https://github.com/BigBadaboom/androidsvg) | Apache-2.0 |
| [snakeyaml-engine](https://bitbucket.org/snakeyaml/snakeyaml-engine) | Apache-2.0 |
| [PDFium](https://pdfium.googlesource.com/pdfium/) (prebuilt by [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries), MIT packaging; `tools/fetch-pdfium.sh`) | BSD-3-Clause / Apache-2.0 |
| Own readers for DOCX, ODT, DOC, XLSX, ODS, XLS, PPTX, ODP, PPT, EPUB, MIME mail and OOXML encryption | Apache-2.0 (this app) |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) (bundled font) | SIL OFL 1.1 |
| Dracula and Tokyo Night color palettes (themes) | MIT |

Theme backgrounds are original art from the libre-apps themes. Syntax highlighting is this app's own code.

## License

[Apache License 2.0](LICENSE).
