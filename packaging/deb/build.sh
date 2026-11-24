#!/usr/bin/env bash
# Builds dist/open-documents-viewer_<version>_all.deb and install-tests it.
# Runs inside packaging/docker/deb.Dockerfile: use `packaging/build.sh deb`.
set -euo pipefail
trap 'sh packaging/common/hand_back.sh || true' EXIT

VERSION="$(sed -n 's/^__version__ = "\(.*\)"/\1/p' src/odv/__init__.py)"
PKG=open-documents-viewer
ID=io.github.guilhermefeitosa66.OpenDocumentsViewer
ROOT="build/deb/${PKG}_${VERSION}_all"
rm -rf build/deb && mkdir -p "$ROOT/DEBIAN" dist

sh packaging/common/stage.sh "$ROOT"
desktop-file-validate "$ROOT/usr/share/applications/$ID.desktop"
appstreamcli validate --no-net --explain "$ROOT/usr/share/metainfo/$ID.metainfo.xml"

SIZE="$(du -sk "$ROOT" | cut -f1)"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: $PKG
Version: $VERSION
Section: utils
Priority: optional
Architecture: all
Installed-Size: $SIZE
Depends: python3 (>= 3.10), python3-gi (>= 3.42), python3-gi-cairo, python3-yaml, gir1.2-gtk-4.0 (>= 4.12), gir1.2-adw-1 (>= 1.4), gir1.2-gtksource-5, python3-markdown-it
Recommends: adwaita-icon-theme, librsvg2-common, webp-pixbuf-loader, heif-gdk-pixbuf, libavif-gdk-pixbuf
Maintainer: Guilherme Feitosa <guilhermefeitosa66@gmail.com>
Homepage: https://libre-apps.org
Description: fast, read-only viewer for Markdown, text, code and data files
 Opens documents fast and only shows them: Markdown rendered with native
 widgets (with a switch to the source), code and config files with syntax
 highlighting, CSV/TSV as tables of any size. Folder tree, tabs and split
 view. Offline, no account, no tracking.
CONTROL

# Bytecode is compiled on the user's machine, for the python3 that is there:
# start-up time depends on it, and a .pyc built here could be for another
# interpreter version.
cat > "$ROOT/DEBIAN/postinst" <<'SH'
#!/bin/sh
set -e
if [ "$1" = configure ]; then
  python3 -m compileall -q /usr/lib/open-documents-viewer >/dev/null 2>&1 || true
fi
SH
cat > "$ROOT/DEBIAN/prerm" <<'SH'
#!/bin/sh
set -e
find /usr/lib/open-documents-viewer -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
SH
chmod 755 "$ROOT/DEBIAN/postinst" "$ROOT/DEBIAN/prerm"

DEB="dist/${PKG}_${VERSION}_all.deb"
fakeroot dpkg-deb --root-owner-group -Zxz --build "$ROOT" "$DEB"
echo "built $DEB ($(stat -c %s "$DEB") bytes)"

echo "==> install test"
apt-get update -qq
apt-get install -y -qq --no-install-recommends "./$DEB" librsvg2-common fonts-dejavu-core >/dev/null
test -f /usr/lib/open-documents-viewer/odv/__pycache__/main.cpython-312.pyc || { echo "ERROR: postinst did not compile" >&2; exit 1; }
open-documents-viewer --version
LOG="$(mktemp)"
GTK_A11Y=none timeout 60 xvfb-run -a dbus-run-session -- open-documents-viewer --bench \
  tests/fixtures/sample.md tests/fixtures/sample.py tests/fixtures/sample.csv > "$LOG" 2>&1 || true
cat "$LOG" | grep -v '^$' | grep -v 'a11y' || true
test "$(grep -c '^odv-bench loaded' "$LOG")" = 3 || { echo "ERROR: the installed app did not open the files" >&2; exit 1; }
echo "==> theme check (Neon, with its background picture)"
mkdir -p /tmp/cfg/open-documents-viewer
echo '{"theme": "neon"}' > /tmp/cfg/open-documents-viewer/settings.json
XDG_CONFIG_HOME=/tmp/cfg GTK_A11Y=none timeout 60 xvfb-run -a dbus-run-session -- python3 -s -c '
import sys, time; sys.path.insert(0, "/usr/lib/open-documents-viewer"); sys.argv = ["odv"]
import gi; gi.require_version("Gtk", "4.0"); gi.require_version("Adw", "1")
from gi.repository import GLib, Gio
from odv.app import Application
app = Application(time.perf_counter()); app.set_flags(app.get_flags() | Gio.ApplicationFlags.NON_UNIQUE)
def check():
    w = app.get_active_window()
    from odv import theme
    ok = theme.theme_id() == "neon" and w.background.get_paintable() is not None
    print("theme-check", "OK" if ok else "FAIL"); app.quit(); return False
app.connect("activate", lambda a: GLib.timeout_add(3000, check))
app.run(sys.argv)' > "$LOG" 2>&1 || true
grep -q "theme-check OK" "$LOG" || { echo "ERROR: the Neon theme or its picture did not load" >&2; cat "$LOG" >&2; exit 1; }
echo "theme check OK"
apt-get remove -y -qq "$PKG" >/dev/null
test ! -e /usr/lib/open-documents-viewer || { echo "ERROR: files left after removal:" >&2; find /usr/lib/open-documents-viewer >&2; exit 1; }
echo "==> DEB OK"
