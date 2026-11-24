#!/bin/sh
# Environment for the AppImage, then Python. Reached from odv-launcher (a
# static ELF, because appimage-builder requires app_info.exec to be one).
# Adapted from OpenEmux's packaging/appimage/openemux-run.sh, which explains
# each step in detail.
set -eu

APPDIR="${APPDIR:-$(cd -- "$(dirname -- "$0")/../.." && pwd)}"
export APPDIR
LIBDIR="$APPDIR/usr/lib/x86_64-linux-gnu"

export GI_TYPELIB_PATH="$LIBDIR/girepository-1.0${GI_TYPELIB_PATH:+:$GI_TYPELIB_PATH}"
export XDG_DATA_DIRS="$APPDIR/usr/share:${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"

PIXBUF_VERSION_DIR="$LIBDIR/gdk-pixbuf-2.0/2.10.0"
if [ -d "$PIXBUF_VERSION_DIR/loaders" ]; then
    export GDK_PIXBUF_MODULEDIR="$PIXBUF_VERSION_DIR/loaders"
    if [ -s "$PIXBUF_VERSION_DIR/loaders.cache" ]; then
        export GDK_PIXBUF_MODULE_FILE="$PIXBUF_VERSION_DIR/loaders.cache"
    fi
fi

if [ -f "$APPDIR/usr/share/glib-2.0/schemas/gschemas.compiled" ]; then
    export GSETTINGS_SCHEMA_DIR="$APPDIR/usr/share/glib-2.0/schemas"
fi

export PYTHONPATH="$APPDIR/usr/lib/open-documents-viewer:$APPDIR/usr/lib/python3/dist-packages"

# The bundled interpreter's ELF interpreter path is relative (see the recipe).
cd "$APPDIR" || exit 1

# odv-launcher parked LD_LIBRARY_PATH so the host's /bin/sh is not loaded
# against the bundle's libraries; only python3 needs them.
if [ -n "${APPDIR_SHELL_LD_LIBRARY_PATH+x}" ]; then
    export LD_LIBRARY_PATH="$APPDIR_SHELL_LD_LIBRARY_PATH"
fi
unset APPDIR_SHELL_LD_LIBRARY_PATH

# Relative paths on the command line were relative to where the user ran the
# AppImage, not to $APPDIR. AppRun records that directory as APPRUN_CWD (the
# type2 runtime as OWD); odv.main changes back to it.
export ODV_CWD="${APPRUN_CWD:-${OWD:-}}"

exec "$APPDIR/usr/bin/python3" -s -m odv "$@"
