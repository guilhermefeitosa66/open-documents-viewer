#!/bin/sh
# Vendor the Python libraries the distros do not package (pypdfium2, with
# its PDFium build) under LIB/vendor; odv.formats.pdf adds that folder to
# sys.path when a PDF opens. The wheel is pinned by hash.
set -eu
LIB="${1:?usage: vendor.sh LIBDIR}"
CACHE=build/vendor-cache
mkdir -p "$CACHE" "$LIB/vendor"
if ! ls "$CACHE"/pypdfium2-*.whl >/dev/null 2>&1; then
  python3 -m pip download --quiet --no-deps --only-binary :all: --platform manylinux_2_17_x86_64 \
    --python-version 3.12 --implementation py --require-hashes \
    -r packaging/docker/vendor-requirements.txt -d "$CACHE"
fi
WHEEL="$(ls "$CACHE"/pypdfium2-*.whl | head -n1)"
python3 - "$WHEEL" "$LIB/vendor" <<'PY'
import sys, zipfile
wheel, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(wheel) as z:
    for name in z.namelist():
        top = name.split("/")[0]
        if top in ("pypdfium2", "pypdfium2_raw") or "/licenses/" in name:
            z.extract(name, dest)
PY
test -f "$LIB/vendor/pypdfium2_raw/libpdfium.so" || { echo "vendor.sh: libpdfium.so missing" >&2; exit 1; }
find "$LIB/vendor" -type d -exec chmod 755 {} + && find "$LIB/vendor" -type f -exec chmod 644 {} +
chmod 755 "$LIB/vendor/pypdfium2_raw/libpdfium.so"
