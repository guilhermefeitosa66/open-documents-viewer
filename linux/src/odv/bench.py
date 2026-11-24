"""Start-up and file-open timings, printed with --bench.

Lines start with ``odv-bench`` and carry ``key=value`` pairs so scripts can
parse them (see tools/bench.py and tests/test_startup.py)."""

import os
import sys
import time

from gi.repository import GLib


def process_age_ms() -> float | None:
    """Milliseconds since the kernel started this process (10 ms resolution)."""
    try:
        with open("/proc/self/stat") as f:
            fields = f.read().rsplit(")", 1)[1].split()
        start_ticks = int(fields[19])
        with open("/proc/uptime") as f:
            uptime = float(f.read().split()[0])
        return (uptime - start_ticks / os.sysconf("SC_CLK_TCK")) * 1000.0
    except (OSError, ValueError, IndexError):
        return None


def after_next_paint(widget, callback) -> None:
    """Run ``callback`` once the next frame containing ``widget`` is painted."""
    clock = widget.get_frame_clock()
    if clock is None:
        GLib.idle_add(lambda: (callback(), False)[1])
        return
    handler = None

    def on_paint(clock):
        clock.disconnect(handler)
        callback()

    handler = clock.connect("after-paint", on_paint)
    widget.queue_draw()


class Bench:
    def __init__(self, t0: float):
        self.t0 = t0
        self.enabled = False
        self._frame_done = False
        self._pending = 0
        self._app = None

    def _ms(self, since: float) -> float:
        return (time.perf_counter() - since) * 1000.0

    def _emit(self, event: str, **values) -> None:
        if not self.enabled:
            return
        parts = [f"{k}={v:.1f}" if isinstance(v, float) else f"{k}={v}" for k, v in values.items()]
        print("odv-bench", event, *parts, flush=True, file=sys.stdout)

    def first_frame(self, app) -> None:
        if self._frame_done:
            return
        self._frame_done = True
        self._app = app
        age = process_age_ms()
        self._emit("startup", main_to_frame_ms=self._ms(self.t0),
                   process_to_frame_ms=age if age is not None else -1.0)
        self._maybe_quit()

    def background_shown(self) -> None:
        self._emit("background", main_to_picture_ms=self._ms(self.t0))

    def file_started(self) -> float:
        self._pending += 1
        return time.perf_counter()

    def file_first_content(self, name: str, started: float) -> None:
        self._emit("open", file=name.replace(" ", "_"), first_content_ms=self._ms(started))

    def file_loaded(self, name: str, started: float) -> None:
        self._pending = max(0, self._pending - 1)
        self._emit("loaded", file=name.replace(" ", "_"), loaded_ms=self._ms(started))
        self._maybe_quit()

    def _maybe_quit(self) -> None:
        if self.enabled and self._frame_done and self._pending == 0 and self._app:
            GLib.timeout_add(30, lambda: (self._app.quit(), False)[1])
