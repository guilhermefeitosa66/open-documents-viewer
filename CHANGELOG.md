# Changelog

All notable changes to this project. Versions follow [Semantic Versioning](https://semver.org).

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
