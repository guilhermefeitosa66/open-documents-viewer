# Changelog

All notable changes to this project. Versions follow [Semantic Versioning](https://semver.org).

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
