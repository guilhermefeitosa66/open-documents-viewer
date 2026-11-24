#!/bin/sh
# Stage the install tree under DESTDIR, the layout every native format shares:
#   /usr/lib/open-documents-viewer/odv   the Python package (no bytecode)
#   /usr/bin/open-documents-viewer       the launcher
#   desktop entry, AppStream metainfo, icon
set -eu
DESTDIR="${1:?usage: stage.sh DESTDIR}"
ID=io.github.guilhermefeitosa66.OpenDocumentsViewer
LIB="$DESTDIR/usr/lib/open-documents-viewer"
mkdir -p "$LIB"
# Sources only: no __pycache__, no editor leftovers.
(cd src && find odv -type f \( -name '*.py' -o -name '*.svg' -o -name '*.xml' -o -name '*.png' \
  -o -name '*.json' -o -name '*.lang' \) -not -path '*/__pycache__/*' -print) | while read -r f; do
  install -Dm644 "src/$f" "$LIB/$f"
done
# Every file the app reads at run time must have made it.
for theme in pastel dracula kitty neon; do
  for f in "themes/backgrounds/$theme.png" "themes/thumbs/$theme.png" "styles/odv-$theme.xml"; do
    test -f "$LIB/odv/data/$f" || { echo "stage.sh: missing odv/data/$f" >&2; exit 1; }
  done
done
test -f "$LIB/odv/data/themes/themes.json" || { echo "stage.sh: missing themes.json" >&2; exit 1; }
for lang in odv-log odv-graphql odv-subtitles; do
  test -f "$LIB/odv/data/language-specs/$lang.lang" || { echo "stage.sh: missing $lang.lang" >&2; exit 1; }
done
install -Dm755 packaging/common/open-documents-viewer "$DESTDIR/usr/bin/open-documents-viewer"
install -Dm644 "packaging/common/$ID.desktop" "$DESTDIR/usr/share/applications/$ID.desktop"
install -Dm644 "packaging/common/$ID.metainfo.xml" "$DESTDIR/usr/share/metainfo/$ID.metainfo.xml"
install -Dm644 "src/odv/data/icons/hicolor/scalable/apps/$ID.svg" \
  "$DESTDIR/usr/share/icons/hicolor/scalable/apps/$ID.svg"
install -Dm644 LICENSE "$DESTDIR/usr/share/doc/open-documents-viewer/LICENSE"
install -Dm644 THIRD_PARTY_NOTICES.md "$DESTDIR/usr/share/doc/open-documents-viewer/THIRD_PARTY_NOTICES.md"
