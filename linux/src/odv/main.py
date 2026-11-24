"""Entry point. Keep this module (and everything it imports before the first
frame) small: start-up time is a requirement, not a detail."""

import sys
import time


def main(t0: float | None = None, argv: list[str] | None = None) -> int:
    t0 = time.perf_counter() if t0 is None else t0
    # The AppImage launcher changes directory before starting Python; go back
    # to where the user ran it so relative paths on the command line resolve.
    import os

    cwd = os.environ.pop("ODV_CWD", None)
    if cwd and os.path.isdir(cwd):
        os.chdir(cwd)
    import gi

    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from odv.app import Application

    app = Application(t0)
    return app.run(sys.argv if argv is None else argv)
