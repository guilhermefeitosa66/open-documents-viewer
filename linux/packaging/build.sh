#!/usr/bin/env bash
# packaging/build.sh {deb|appimage|flatpak}: builds in a container (only Docker is
# needed on the host); artifacts land in dist/.
set -euo pipefail
TARGET="${1:-}"
case "$TARGET" in deb|appimage|flatpak) ;; *) echo "usage: $0 {deb|appimage|flatpak}" >&2; exit 2 ;; esac
cd "$(dirname "${BASH_SOURCE[0]}")/.."
IMAGE="odv-build-$TARGET"
docker build -q -t "$IMAGE" -f "packaging/docker/$TARGET.Dockerfile" packaging/docker >/dev/null
EXTRA=()
# flatpak-builder's sandbox needs a privileged container; the GNOME runtime is
# kept in a volume so it is downloaded once, not on every build.
[ "$TARGET" = flatpak ] && EXTRA=(--privileged -v odv-flatpak-cache:/root/.local/share/flatpak)
docker run --rm --name "$IMAGE-$$" -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" "${EXTRA[@]}" \
  -v "$PWD:/work" -w /work "$IMAGE" bash "packaging/$TARGET/build.sh"
