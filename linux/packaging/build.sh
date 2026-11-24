#!/usr/bin/env bash
# packaging/build.sh {deb|appimage}: builds in a container (only Docker is
# needed on the host); artifacts land in dist/.
set -euo pipefail
TARGET="${1:-}"
case "$TARGET" in deb|appimage) ;; *) echo "usage: $0 {deb|appimage}" >&2; exit 2 ;; esac
cd "$(dirname "${BASH_SOURCE[0]}")/.."
IMAGE="odv-build-$TARGET"
docker build -q -t "$IMAGE" -f "packaging/docker/$TARGET.Dockerfile" packaging/docker >/dev/null
docker run --rm --name "$IMAGE-$$" -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -v "$PWD:/work" -w /work "$IMAGE" bash "packaging/$TARGET/build.sh"
