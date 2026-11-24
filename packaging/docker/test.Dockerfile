# Test image: the runtime the .deb targets (Ubuntu 24.04) plus Xvfb and
# pytest, so the GUI tests and the start-up benchmark run headless.
FROM ubuntu:24.04@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 python3-gi python3-gi-cairo python3-markdown-it python3-pytest \
      gir1.2-gtk-4.0 gir1.2-adw-1 gir1.2-gtksource-5 \
      adwaita-icon-theme librsvg2-common fonts-dejavu-core git python3-yaml python3-olefile webp-pixbuf-loader heif-gdk-pixbuf libavif-gdk-pixbuf xvfb xauth dbus-x11 locales \
 && rm -rf /var/lib/apt/lists/*
ENV LANG=C.UTF-8
WORKDIR /work
