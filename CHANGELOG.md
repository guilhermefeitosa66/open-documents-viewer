# Changelog

All notable changes to this project. Versions follow [Semantic Versioning](https://semver.org).

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
