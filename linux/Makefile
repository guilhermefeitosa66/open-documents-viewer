PY ?= /usr/bin/python3
ARGS ?=
TEST_IMAGE = odv-test
DOCKER_X = docker run --rm -e GTK_A11Y=none -e PYTHONDONTWRITEBYTECODE=1 -v $(CURDIR):/work $(TEST_IMAGE) bash -c

.PHONY: run test bench screenshots deb appimage packages clean test-image

run:
	PYTHONPATH=src $(PY) -m odv $(ARGS)

test-image:
	docker build -q -t $(TEST_IMAGE) -f packaging/docker/test.Dockerfile packaging/docker

test: test-image
	$(DOCKER_X) 'Xvfb :55 -screen 0 1400x900x24 -nolisten tcp 2>/dev/null & sleep 1; DISPLAY=:55 dbus-run-session -- python3 -m pytest -q -p no:cacheprovider tests; s=$$?; kill %1; exit $$s'

bench: test-image
	$(DOCKER_X) 'Xvfb :55 -screen 0 1400x900x24 -nolisten tcp 2>/dev/null & sleep 1; DISPLAY=:55 dbus-run-session -- python3 tools/bench.py -n 7 $(ARGS) 2>&1 | grep median; kill %1'

deb:
	./packaging/build.sh deb

appimage:
	./packaging/build.sh appimage

packages: deb appimage
	cd dist && sha256sum *.deb *.AppImage > SHA256SUMS

clean:
	rm -rf build AppDir appimage-builder-cache .bundle.yml dist
	find . -name __pycache__ -type d -prune -exec rm -rf {} +
