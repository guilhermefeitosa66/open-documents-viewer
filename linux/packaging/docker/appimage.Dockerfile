# Build image for the AppImage. Adapted from OpenEmux (packaging/docker/
# appimage.Dockerfile), which documents each choice at length: Ubuntu 24.04
# pinned by digest, appimage-builder 1.1.0, and the static type2-runtime so
# the bundle needs no libfuse.so.2 on the user's machine.
FROM ubuntu:24.04@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 python3-pip python3-setuptools python3-wheel \
      ca-certificates gnupg wget file desktop-file-utils \
      squashfs-tools patchelf fakeroot strace xz-utils zsync \
      libgdk-pixbuf2.0-bin libglib2.0-bin gtk-update-icon-cache librsvg2-common \
      binutils gcc libc6-dev \
      xvfb xauth dbus-x11 \
      libgtk-4-1 libadwaita-1-0 gir1.2-gtk-4.0 gir1.2-adw-1 \
 && rm -rf /var/lib/apt/lists/*
RUN python3 -m pip install --break-system-packages --no-cache-dir \
      "packaging<22" "appimage-builder==1.1.0"
ARG APPIMAGE_RUNTIME_BASE=https://github.com/AppImage/type2-runtime/releases/download/20251108
ARG APPIMAGE_RUNTIME_SHA256_X86_64=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d
RUN set -eu; \
    test "$(uname -m)" = x86_64 || { echo "x86_64 only for now" >&2; exit 1; }; \
    wget -q -O /opt/appimage-runtime-x86_64 "$APPIMAGE_RUNTIME_BASE/runtime-x86_64"; \
    echo "$APPIMAGE_RUNTIME_SHA256_X86_64  /opt/appimage-runtime-x86_64" | sha256sum -c -; \
    chmod 0644 /opt/appimage-runtime-x86_64
WORKDIR /work
