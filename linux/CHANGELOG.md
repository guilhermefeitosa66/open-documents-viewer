# Changelog

All notable changes to this project. Versions follow semantic versioning.

## 0.33.0 (2026-11-24)

- Added: a faithful view for office files (Word, Excel, PowerPoint and OpenDocument, old and new, and RTF): a third button next to Formatted/Source converts the file to PDF with LibreOffice, when installed, in a temporary folder with a throw-away profile, and shows it in the PDF view (zoom, find, outline, print). Without LibreOffice it explains how to install it. The text-only Word 97–2003 and PowerPoint 97–2003 views offer it in their banner.

## 0.32.0 (2026-11-24)

- Added: PDF files, with pypdfium2 (PDFium): continuous scroll with pages rendered on demand in a thread and a small cache, zoom (buttons, Ctrl+wheel, pinch), fit width or page, go to page, thumbnails, the outline in the sidebar, find with highlights, text selection and copy, internal and web links, password-protected PDFs and printing; the source view shows the extracted text. pypdfium2 is bundled in the .deb (now amd64) and the AppImage.

## 0.31.0 (2026-11-24)

- Added: more office files: OpenDocument drawings (.odg, .otg, .fodg), templates (.dotx, .dotm, .ott, .xltx, .xltm, .ots, .potx, .potm, .otp, .dot, .xlt), flat OpenDocument (.fodt, .fods, .fodp), macro-enabled files (.docm, .xlsm, .pptm…) with a note that macros never run, and Apple iWork files (.pages, .numbers, .key) through the preview picture saved inside them. Password-protected Word, Excel and PowerPoint files ask for the password and are decrypted in memory only (msoffcrypto-tool, optional); encrypted OpenDocument files say they cannot be opened yet.

## 0.30.0 (2026-11-24)

- Changed: New app icon: four tiles on near-black, for slides, spreadsheets, documents and code.

## 0.29.0 (2026-11-24)

- Added: PowerPoint 97–2003 presentations (.ppt, .pps, .pot): each slide's text placed where the file puts it, with titles, bullets, notes and hidden slides skipped; a banner says pictures and formatting are not shown; password-protected files say so.

## 0.28.0 (2026-11-24)

- Added: OpenDocument presentations (.odp, .otp and flat .fodp), read-only, in the same slides view: master-page placeholders and drawings, page backgrounds, text boxes with lists, custom shapes, pictures, tables, groups, lines and speaker notes.

## 0.27.0 (2026-11-24)

- Added: PowerPoint presentations (.pptx, .ppsx, .potx), read-only: slides drawn with their backgrounds, shapes, text (theme fonts and colours, bullets, numbering), pictures, groups and simple tables; thumbnails, keyboard navigation, speaker notes, find, an outline of slide titles and a full-screen presentation mode (F5; Ctrl+R still reloads). Hidden slides are skipped.

## 0.26.0 (2026-11-24)

- Added: Excel 97–2003 workbooks (.xls), read-only, in the sheet view: values with their number and date formats, styles, merges, widths and hidden rows, columns and sheets.

## 0.25.0 (2026-11-24)

- Added: OpenDocument spreadsheets (.ods), read-only, in the same sheet view: the text exactly as the file shows it, formulas, styles, widths, merges, hidden rows, columns and sheets.

## 0.24.0 (2026-11-24)

- Added: Excel workbooks (.xlsx, .xlsm), read-only: sheet tabs, a grid with the file's number, date and currency formats, bold/italic/colours/fills, column widths, merged cells, hidden rows, columns and sheets respected; a bar shows the selected cell's value and formula; find in cells; copy rows as TSV. Formulas are shown, never recalculated, and macros never run.

## 0.23.0 (2026-11-24)

- Added: Word 97–2003 documents (.doc): their text, with tables and page breaks, and a banner saying formatting is not shown; password-protected files say so.

## 0.22.0 (2026-11-24)

- Added: OpenDocument text (.odt), read-only, with the same scope as Word documents: headings and outline, inline styles, alignment, lists, tables, images, links, header and footer, notes and page breaks.

## 0.21.0 (2026-11-24)

- Added: Word documents (.docx), read-only and fluid: headings and outline, bold, italic, underline, strike, colours, sizes, alignment, numbered and bulleted lists, tables, images, links, header and footer, footnotes and page breaks.

## 0.20.0 (2026-11-24)

- Added: e-mail messages (.eml): headers, the body (HTML rendered safely, inline images shown, remote ones and scripts never loaded) and attachments you can save.
- Added: contacts (.vcf) and calendars (.ics) as readable cards and a dated list of events and tasks.

## 0.19.0 (2026-11-24)

- Added: Markdown shows local images (remote ones are never loaded).
- Added: a name filter above the folder tree lists the matching files of the whole folder.

## 0.18.0 (2026-11-24)

- Added: EPUB 2 and 3 books (without DRM): chapters in reading order, images, links between chapters and the book's own table of contents as the outline; the reading position is remembered.
- Added: FictionBook (.fb2) books, with their embedded images.
- Added: HTML files show their local images (remote ones are never loaded).

## 0.17.0 (2026-11-24)

- Added: Reopen with Encoding (tab menu and command palette) with 23 encodings, from UTF-16 to Shift_JIS and Windows-1251.
- Added: UTF-16 files without a byte-order mark are recognised as text; Properties show the line endings (LF, CRLF, CR or mixed).

## 0.16.0 (2026-11-24)

- Added: click a column header to sort a CSV/TSV table (numbers as numbers, empty cells last), in a thread with a progress bar; the file is never changed and the # column keeps the original row numbers.
- Added: CSV/TSV files in Windows-1252 are detected and read correctly.

## 0.15.0 (2026-11-24)

- Added: JSON, NDJSON, YAML and XML open as a collapsible tree, with the selected node's path ($.a.b[2] or /a/b[3]) shown and copyable, and find inside the tree; invalid data falls back to the source at the error line.
- Changed: those formats now open in the tree view (the source is one click or Ctrl+E away).

## 0.14.0 (2026-11-24)

- Added: highlighting for logs (levels coloured), GraphQL and subtitles (.srt, .vtt), and recognition of many more file names: Jenkinsfile, Procfile, Vue, Svelte, plist, HCL, .editorconfig, Gemfile, systemd units, gettext and others.
- Added: scripts without an extension are highlighted from their #! line.

## 0.13.0 (2026-11-24)

- Added: images open in the viewer: PNG, JPEG, animated GIF, WebP, BMP, ICO, TIFF, AVIF/HEIC when their loader is installed, and SVG (with its XML one click away); fit, zoom, drag to pan, an info strip, and Left/Right through the images of the folder.
- Added: RTF documents, formatted (bold, italic, underline, strike, sizes, colours, lists, simple tables) with the source one click away.
- Added: Jupyter notebooks (.ipynb): Markdown cells formatted, code cells with their outputs, images included; nothing is executed.
- Fixed: errors while reading a table or an image now show a message instead of a spinner that never stops.

## 0.10.0 (2026-11-24)

- Added: HTML files (.html, .htm, .xhtml) open formatted, rendered natively and safely: no web engine, no scripts, nothing loaded from the network; the source is one click away.

## 0.9.0 (2026-11-24)

- Added: file actions in the main menu, the tab menu and the command palette: copy path, show in folder, open with another app, save a copy (Ctrl+Shift+S), print (Ctrl+Alt+P) and properties (Alt+Enter).
- Fixed: jumping to a heading (anchors, outline) lands on it even while the layout is still being measured.

## 0.8.0 (2026-11-24)

- Added: text zoom (Ctrl +/−/0 and Ctrl+wheel), kept separately for prose, code and tables.
- Added: line wrap toggle (Alt+Z), full screen (F11) and focus mode without bars (Ctrl+Shift+Enter).
- Added: Preferences for the prose text width, line numbers and a minimap in code.

## 0.7.0 (2026-11-24)

- Added: the app reopens the last session (folder, tabs, split, views and scroll positions) when started without files; Preferences can turn it off.
- Fixed: a tab dragged to the other pane now reloads and switches views in the pane it is in.

## 0.6.0 (2026-11-24)

- Added: links to another file with an anchor (other.md#section) open that file at the section.
- Added: web and mail links ask before opening outside the app; broken links say so; hovering a link shows where it goes.

## 0.5.0 (2026-11-24)

- Added: document outline in the sidebar (Ctrl+Shift+L) for Markdown, in the formatted and the source view; a click jumps to the section and the current section follows the scroll.
- Changed: the sidebar switches between folder, search and outline, and opens without a folder (F9).

## 0.4.0 (2026-11-24)

- Added: go to file (Ctrl+P) with fuzzy search over the open folder.
- Added: command palette (Ctrl+Shift+P) with every action, theme and the background switch.

## 0.3.0 (2026-11-24)

- Added: find in CSV/TSV tables (Ctrl+F), fast on files with millions of rows.
- Added: search in the open folder (Ctrl+Shift+F), respecting .gitignore and skipping binary files; results grouped by file open at the line.

## 0.2.0 (2026-11-24)

- Added: files reload when they change on disk, keeping the scroll position; a banner says when a file was deleted (Preferences can turn it off).
- Added: the interface in Brazilian Portuguese and Spanish, following the system language.

## 0.1.0 (2026-11-24)

- First release: Markdown, text, code, config and CSV files; folder tree, tabs and split view; themes.

