# Third-party software

Open Documents Viewer is licensed under the Apache License 2.0 (see `LICENSE`).
It uses the libraries below, all loaded dynamically at run time. None of them
changes the license of the app.

| Component | License | Use |
|---|---|---|
| [Python](https://www.python.org) | PSF-2.0 | Runtime |
| [PyGObject](https://pygobject.gnome.org) | LGPL-2.1-or-later | GTK bindings |
| [GTK 4](https://gtk.org) | LGPL-2.1-or-later | User interface |
| [libadwaita](https://gnome.pages.gitlab.gnome.org/libadwaita/) | LGPL-2.1-or-later | User interface |
| [GtkSourceView 5](https://gitlab.gnome.org/GNOME/gtksourceview) | LGPL-2.1-or-later | Syntax highlighting |
| [markdown-it-py](https://github.com/executablebooks/markdown-it-py) | MIT | Markdown parsing |
| [PyYAML](https://github.com/yaml/pyyaml) | MIT | YAML tree view |
| [olefile](https://github.com/decalage2/olefile) | BSD-2-Clause | Reading Word/Excel/PowerPoint 97–2003 containers |
| [xlrd](https://github.com/python-excel/xlrd) | BSD-3-Clause | Excel 97–2003 workbooks |
| [pypdfium2](https://github.com/pypdfium2-team/pypdfium2), bundled with its [PDFium](https://pdfium.googlesource.com/pdfium/) build | Apache-2.0 OR BSD-3-Clause; PDFium BSD-3-Clause, its parts (FreeType, libjpeg-turbo, OpenJPEG, lcms2, libpng, zlib, ICU, HarfBuzz…) under their own permissive licenses, shipped in `vendor/pypdfium2-*.dist-info/licenses/` | Showing and printing PDF files |
| [msoffcrypto-tool](https://github.com/nolze/msoffcrypto-tool) and its dependency [cryptography](https://github.com/pyca/cryptography) (optional) | MIT; Apache-2.0 OR BSD-3-Clause | Opening password-protected Word/Excel/PowerPoint files, in memory |
| GdkPixbuf loaders: [webp-pixbuf-loader](https://github.com/aruiz/webp-pixbuf-loader), libheif's, libavif's (optional) | LGPL-2.0-or-later, LGPL-3.0-or-later, BSD-2-Clause | WebP, HEIF and AVIF images |

Optional, not shipped and not linked: [LibreOffice](https://www.libreoffice.org)
(MPL-2.0), when installed, is run as a separate program for the faithful view.

Tests only (not shipped): [xlwt](https://github.com/python-excel/xlwt) (BSD-3-Clause) writes sample .xls files; [qpdf](https://github.com/qpdf/qpdf) (Apache-2.0) encrypts a sample PDF.

The AppImage also carries the Ubuntu 24.04 packages these depend on (GLib,
Pango, cairo, HarfBuzz, librsvg, the Adwaita icon theme and others), each
under its own license, as listed in `/usr/share/doc/*/copyright` inside the
bundle.

The source view colour schemes in `src/odv/data/styles/` derive from
GtkSourceView's Adwaita schemes (LGPL-2.1-or-later).

## Themes

The themes in `src/odv/data/themes/` come from the shared libre-apps theme
pack. The background pictures are original artwork. The Dracula and Neon
(Tokyo Night) colours follow the public palettes of
[Dracula](https://draculatheme.com) and
[Tokyo Night](https://github.com/enkia/tokyo-night-vscode-theme), both under
the MIT License. Pastel and Kitty are libre-apps palettes.
