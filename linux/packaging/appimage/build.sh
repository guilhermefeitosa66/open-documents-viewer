#!/usr/bin/env bash
# Builds dist/OpenDocumentsViewer-<version>-x86_64.AppImage and launch-tests
# it. Runs inside packaging/docker/appimage.Dockerfile: use
# `packaging/build.sh appimage`. Adapted from OpenEmux's
# packaging/appimage/build.sh, which documents the why of each step.
set -euo pipefail
trap 'sh packaging/common/hand_back.sh || true' EXIT

TRIPLET=x86_64-linux-gnu
RECIPE=packaging/appimage/AppImageBuilder.yml
APPDIR_LIB="$PWD/AppDir/usr/lib/$TRIPLET"
RUNTIME_SRC=/opt/appimage-runtime-x86_64

VERSION="$(sed -n 's/^__version__ = "\(.*\)"/\1/p' src/odv/__init__.py)"
grep -q "^    version: \"${VERSION}\"$" "$RECIPE" || {
  echo "FAIL: $RECIPE does not carry version \"$VERSION\" (src/odv/__init__.py)." >&2; exit 1; }
echo "==> building Open Documents Viewer $VERSION AppImage"

echo "==> phase 1: assemble the AppDir"
appimage-builder --recipe "$RECIPE" --skip-tests --skip-appimage

echo "==> regenerating gdk-pixbuf loaders.cache from the bundled loaders"
GPB_DIR="$APPDIR_LIB/gdk-pixbuf-2.0"
QUERY_BIN="/usr/lib/$TRIPLET/gdk-pixbuf-2.0/gdk-pixbuf-query-loaders"
tmp_cache="$(mktemp)"
LD_LIBRARY_PATH="$APPDIR_LIB:$PWD/AppDir/lib/$TRIPLET" \
GDK_PIXBUF_MODULEDIR="$GPB_DIR/2.10.0/loaders" "$QUERY_BIN" > "$tmp_cache"
sed -i "s|$GPB_DIR/2.10.0/loaders/||g" "$tmp_cache"
grep -q svg "$tmp_cache" || { echo "ERROR: loaders.cache has no SVG loader." >&2; exit 1; }
mv "$tmp_cache" "$GPB_DIR/2.10.0/loaders.cache"

for typelib in Gtk-4.0 Adw-1 GtkSource-5; do
  test -f "$APPDIR_LIB/girepository-1.0/$typelib.typelib" || { echo "ERROR: $typelib.typelib missing." >&2; exit 1; }
done
test -d AppDir/usr/lib/python3/dist-packages/markdown_it || { echo "ERROR: markdown_it missing." >&2; exit 1; }
if grep -q '^TryExec=' AppDir/usr/share/applications/*.desktop; then
  echo "ERROR: the bundled desktop entry carries TryExec; integrators would hide it." >&2; exit 1
fi

# Bytecode for the bundled sources: the mount is read-only, so without it
# every start would compile the app again. Same interpreter as the bundle's.
python3 -m compileall -q -j0 --invalidation-mode unchecked-hash AppDir/usr/lib/open-documents-viewer

echo "==> phase 2: pack with the static type2 runtime (zstd)"
BUNDLE="OpenDocumentsViewer-${VERSION}-x86_64.AppImage"
PAYLOAD="$PWD/AppDir.squashfs"
rm -f "$PAYLOAD" "$BUNDLE"
mksquashfs AppDir "$PAYLOAD" -root-owned -noappend -reproducible -comp zstd -Xcompression-level 19 >/dev/null
cat "$RUNTIME_SRC" "$PAYLOAD" > "$BUNDLE"
rm -f "$PAYLOAD"
chmod +x "$BUNDLE"
mkdir -p dist
mv "$BUNDLE" dist/
BUNDLE="dist/$BUNDLE"
echo "packaged $BUNDLE ($(stat -c %s "$BUNDLE") bytes)"

echo "==> launch test (extract-and-run, under Xvfb)"
LOG="$(mktemp)"
cp -r tests/fixtures /tmp/fixtures
(cd /tmp/fixtures && APPIMAGE_EXTRACT_AND_RUN=1 GTK_A11Y=none timeout 90 xvfb-run -a dbus-run-session -- \
  "/work/$BUNDLE" --bench sample.md sample.py sample.json .env sample.csv > "$LOG" 2>&1) || true
grep -v '^$' "$LOG" | grep -v a11y || true
if grep -qE "not found|No module named|ModuleNotFoundError|Traceback" "$LOG"; then
  echo "ERROR: the bundle failed to start." >&2; exit 1
fi
test "$(grep -c '^odv-bench loaded' "$LOG")" = 5 || { echo "ERROR: the bundle did not open the 5 files." >&2; exit 1; }
echo "==> APPIMAGE OK"
