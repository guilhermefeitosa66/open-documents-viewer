# Changelog

All notable changes to this project. Versions follow [Semantic Versioning](https://semver.org).

## [0.18.0] - 2026-11-24

### Added
- Encodings: UTF-16 without a byte order mark is recognized, and UTF-32 files with one. "Encoding…" in the menu reads
  the file again with another encoding (UTF-8/16/32, Western, Central European, Cyrillic, Greek, Turkish, Hebrew,
  Arabic, Baltic, Vietnamese, Thai, Japanese, Chinese, Korean...) or goes back to detecting it; the position is kept.
- File information shows the line endings (LF, CRLF, CR or mixed).

## [0.17.0] - 2026-11-24

### Added
- CSV/TSV table: tap a column header to sort (ascending, descending, back to the file's order). Numbers sort by value
  (`1.234,56`, `1,234.56`, currency and percent signs), text sorts naturally (`item 2` before `item 10`), blanks stay
  last. Only the display changes; the file is never touched. Search follows the order shown.
- Drag the edge of a column header to make that column wider or narrower.

## [0.16.0] - 2026-11-24

### Changed
- New app icon: four tiles on near-black, for slides, spreadsheets, documents and code. The themed
  (monochrome) icon shows the four glyphs.

## [0.15.0] - 2026-11-24

### Added
- Tree view as the formatted view of JSON, JSON Lines/NDJSON, YAML (all documents of a file) and XML (elements,
  attributes, text): tap to open or close a branch, long press to copy the node's path (`$.deps[3].name`,
  `/catalog/book[2]/title`). Small files open fully expanded. Search finds visible nodes.
- JSON keeps its indented text as an option in the menu ("Show as indented text").

### Dependencies
- snakeyaml-engine 2.9 (Apache-2.0), used only when a YAML file is opened.

## [0.14.0] - 2026-11-24

### Added
- More languages and formats: Julia, Haskell, Elixir, Erlang, Clojure/Lisp/Scheme/Racket, OCaml/Reason, F#, Zig, Nim,
  Windows batch, GraphQL, Protocol Buffers, Terraform/HCL, Nix, LaTeX, BibTeX, nginx/Apache/Caddy config, diff/patch,
  logs (severity colored, timestamps dimmed), gettext .po/.pot, SubRip/WebVTT subtitles, reStructuredText, AsciiDoc, Org.
- File names: Jenkinsfile, Procfile, Brewfile, Guardfile, justfile, .mailmap and more.
- Files without an extension are recognized by their shebang (`#!/usr/bin/env python3`, `#!/bin/bash`, node, ruby...).
- Logs, subtitles and light markup stay wrapped prose with highlighting.

## [0.13.0] - 2026-11-24

### Added
- Jupyter notebooks (`.ipynb`): markdown cells rendered, code cells highlighted in the kernel's language with
  their execution counts, outputs shown (text streams, errors without terminal color codes, HTML tables such as
  pandas DataFrames, embedded PNG/JPEG/GIF images). Widgets and scripts are not run: a notice says what was left out.
  The JSON source is one tap away. Nothing is executed.

## [0.12.0] - 2026-11-24

### Added
- RTF: formatted view with bold, italic, underline, strike, sizes (large lines become headings, so the outline works),
  document colors (replaced by the theme's text color when they would be unreadable), Windows code pages and Unicode
  escapes, bullet/numbered lines as lists, simple tables. The RTF source is highlighted.
- A short notice when part of a document (pictures, embedded objects) could not be shown.

## [0.11.0] - 2026-11-24

### Added
- Images: PNG, JPEG, GIF (animated), WebP (animated), BMP, ICO, and HEIC/HEIF/AVIF where Android decodes them.
  Fitted to the screen, pinch to zoom, pan, double tap to zoom; dimensions in the subtitle and file info.
  Large images are decoded at most 4096 px on the longest side.
- SVG: rendered (AndroidSVG) as the formatted view, crisp at any zoom; the XML source is one tap away.
- "Open with" and "Share" accept images.

### Dependencies
- AndroidSVG 1.4 (Apache-2.0), used only when an SVG file is opened.

## [0.10.0] - 2026-11-24

### Added
- HTML (`.html`, `.htm`, `.xhtml`): a native, safe formatted view (no JavaScript, no network) rendered like markdown:
  headings, paragraphs, lists (with task boxes and nesting), quotes, code blocks with highlighting, tables, rules, links
  and anchors (`id`s); the source view keeps the highlighted HTML. Scripts, styles, frames and forms are dropped.
- Images in markdown and HTML: embedded `data:` images and files next to the document (when its folder is open) are
  shown; online images are never fetched and show a placeholder with their description.

### Dependencies
- jsoup 1.24.1 (MIT), used only when an HTML file is opened.

## [0.9.0] - 2026-11-24

### Added
- Full screen reading (menu): hides the app bar and system bars, with a small exit button; back also exits.
- Settings › Reading: keep the screen on while a document is open; comfortable line width on large screens
  (markdown and wrapped plain text are centered at a readable measure, on by default).

## [0.8.0] - 2026-11-24

### Added
- The shown document reloads when its file changes (size or last-modified date differ when the app comes back, or the
  provider reports a change). View mode, search and reading position are kept; a short "File changed: updated" notice appears.

## [0.7.0] - 2026-11-24

### Added
- Contents (outline) button for documents with headings: a sheet lists them, indented by level; tapping one scrolls there
  (switching to the formatted view if needed). Markdown now; later formats plug into the same outline.

## [0.6.0] - 2026-11-24

### Added
- Wide screens (tablets, open foldables, wide landscape, from 840 dp): the start screen or the opened folder stays on the left,
  the document on the right.
- Split view on wide screens: the same document formatted beside its source, or another document (a tab or a recent file)
  beside the current one. Back closes the split. Phones keep the single-pane layout.

## [0.5.0] - 2026-11-24

### Added
- Light tabs: documents opened in a session stay open; the switcher (tap the title) lists them with a close button,
  followed by other recent files. Each keeps its position and view mode.
- Memory limit: only the 3 most recently used documents stay rendered; older tabs reopen on demand at their saved position
  (shared text is kept, since it cannot be read again). At most 12 tabs.

### Changed
- Back from a document returns to the start screen (or folder) without closing it.

## [0.4.0] - 2026-11-24

### Added
- Relative links in markdown (`docs/setup.md`, `../README.md`, `My%20Notes.md#section`) open the linked file inside
  the app when it lives in an opened folder; the `#anchor` part scrolls to the heading. Links never leave the granted folder.
- Links to the web, mail or phone ask for confirmation before leaving the app.

## [0.3.0] - 2026-11-24

### Added
- "Open folder" (Storage Access Framework tree): an expandable tree with a breadcrumb (enter a folder to go deeper),
  filter by name (keeps the folders leading to matches), hidden files on demand, sort by name, date or size.
- Granted folders appear in the recent files; documents opened from a folder go back to it.

### Changed
- The recent list counts items (files and folders).

## [0.2.0] - 2026-11-24

### Added
- Share the file itself (real MIME type and name, read grant through the chooser) from the viewer and from each recent file's menu.
- "Share as text" for text files up to ~100 KB.
- "Save a copy…": the user picks a folder (Storage Access Framework); the file is streamed there with progress.
- Clear message when the permission lent by another app has expired.

## [0.1.0] - 2026-11-24

### Added
- Read-only viewer for markdown (rendered, CommonMark + GFM), plain text and logs, code with syntax
  highlighting (30+ languages), JSON (pretty-printed), CSV/TSV (table), `.env` (secret-looking values
  hidden), YAML, TOML, INI, XML/HTML.
- Formatted / Source toggle, search (match case, whole word, regex), line wrap, line numbers, pinch to zoom.
- Recent files with reading position, quick switcher, "Open with" and "Share" from other apps (SAF, no permissions).
- Themes: libre-apps (follows the system), Pastel, Dracula, Kitty, Neon (Tokyo Night); optional background image on the start screen.
- English, Portuguese and Spanish.
