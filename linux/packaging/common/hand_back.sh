#!/bin/sh
# Builds run as root in a container; give what they wrote back to the user.
set -eu
[ -n "${HOST_UID:-}" ] && [ -n "${HOST_GID:-}" ] || exit 0
for d in dist build AppDir appimage-build appimage-builder-cache .bundle.yml .flatpak-builder .flatpak-build-dir flatpak-repo; do
  [ -e "$d" ] && chown -R "$HOST_UID:$HOST_GID" "$d" 2>/dev/null || true
done
