#!/usr/bin/env bash
# Builds dist/OpenDocumentsViewer-<version>-x86_64.flatpak and install-tests
# it. Runs inside packaging/docker/flatpak.Dockerfile: use `make flatpak`.
set -euo pipefail
trap 'sh packaging/common/hand_back.sh || true' EXIT

APP_ID=io.github.guilhermefeitosa66.OpenDocumentsViewer
MANIFEST="packaging/flatpak/$APP_ID.yaml"
RUNTIME="$(sed -n "s/^runtime-version: '\(.*\)'/\1/p" "$MANIFEST")"
VERSION="$(sed -n 's/^__version__ = "\(.*\)"/\1/p' src/odv/__init__.py)"
echo "==> flatpak $VERSION (GNOME $RUNTIME)"

desktop-file-validate "packaging/common/$APP_ID.desktop"
appstreamcli validate --no-net --explain "packaging/common/$APP_ID.metainfo.xml"

flatpak remote-add --user --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install -y --user --noninteractive flathub "org.gnome.Platform//$RUNTIME" "org.gnome.Sdk//$RUNTIME"

flatpak-builder --user --force-clean --disable-rofiles-fuse --repo=flatpak-repo .flatpak-build-dir "$MANIFEST"
mkdir -p dist
BUNDLE="dist/OpenDocumentsViewer-$VERSION-x86_64.flatpak"
flatpak build-bundle flatpak-repo "$BUNDLE" "$APP_ID"
echo "built $BUNDLE ($(stat -c %s "$BUNDLE") bytes)"

echo "==> checks"
FILES=.flatpak-build-dir/files
test -f "$FILES/share/applications/$APP_ID.desktop"
test -f "$FILES/share/metainfo/$APP_ID.metainfo.xml"
grep -q "$FILES\|/app/lib/open-documents-viewer" "$FILES/bin/open-documents-viewer"
ls "$FILES"/lib/python3*/site-packages/pypdfium2_raw/libpdfium.so >/dev/null
ls "$FILES"/lib/open-documents-viewer/odv/__pycache__/main.*.pyc >/dev/null
flatpak install -y --user --noninteractive "./$BUNDLE"
flatpak info --user "$APP_ID"
echo "==> launch test inside the sandbox (Xvfb): a Markdown file and a PDF"
python3 -c 'import sys; sys.path.insert(0, "tests"); from pdfgen import make_pdf; make_pdf("/work/build/sample.pdf")'
mkdir -p /run/dbus && dbus-daemon --system --fork 2>/dev/null || true  # flatpak run asks the system bus
LOG="$(mktemp)"
GTK_A11Y=none timeout 120 xvfb-run -a dbus-run-session -- flatpak run --user "$APP_ID" --bench \
  /work/tests/fixtures/sample.md /work/build/sample.pdf > "$LOG" 2>&1 || true
grep -v '^$' "$LOG" | grep -v a11y || true
test "$(grep -c '^odv-bench loaded' "$LOG")" = 2 || { echo "ERROR: the Flatpak did not open both files" >&2; exit 1; }
echo "==> FLATPAK OK"
