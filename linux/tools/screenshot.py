"""Drive the app through a scenario and save a PNG of the window.

    python3 tools/screenshot.py --out shot.png [--dark] [--folder DIR]
        [--split] [--mode source] [--size 1280x800] [FILE...]

Used for README/site captures and to check the UI against the design. Runs
under Xvfb in the test container (see Makefile: make screenshots).
"""

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src"))

import gi  # noqa: E402

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Graphene", "1.0")
from gi.repository import Adw, Gio, GLib, Graphene, Gtk  # noqa: E402

from odv.app import Application  # noqa: E402


def save(widget, path):
    w, h = widget.get_width(), widget.get_height()
    snap = Gtk.Snapshot()
    Gtk.WidgetPaintable.new(widget).snapshot(snap, w, h)
    node = snap.to_node()
    rect = Graphene.Rect()
    rect.init(0, 0, w, h)
    widget.get_native().get_renderer().render_texture(node, rect).save_to_png(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--dark", action="store_true")
    ap.add_argument("--folder")
    ap.add_argument("--split", action="store_true")
    ap.add_argument("--mode")
    ap.add_argument("--theme")
    ap.add_argument("--no-background", action="store_true")
    ap.add_argument("--prefs", action="store_true")
    ap.add_argument("--search", help="search this text in the folder")
    ap.add_argument("--size", default="1280x800")
    ap.add_argument("--wait", type=float, default=2.0)
    ap.add_argument("files", nargs="*")
    args = ap.parse_args()
    width, height = (int(v) for v in args.size.split("x"))

    app = Application(time.perf_counter())
    app.set_flags(app.get_flags() | Gio.ApplicationFlags.NON_UNIQUE)

    def drive():
        from odv import theme

        if args.dark:
            Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK)
        if args.theme:
            theme.set_theme(args.theme)
        if args.no_background:
            theme.set_background(False)
        win = app.get_active_window()
        if args.search:
            def search():
                win.search_folder()
                win.search_panel.entry.set_text(args.search)
                return False

            GLib.timeout_add(400, search)
        if args.prefs:
            GLib.timeout_add(500, lambda: (win.show_preferences(), False)[1])
        if args.folder:
            win.open_folder(os.path.abspath(args.folder))
        for f in args.files:
            win.open_path(f)
        if args.mode:
            doc = win.active_pane.current_doc()
            doc and doc.set_mode(args.mode)
        if args.split:
            GLib.timeout_add(300, lambda: (win.toggle_split(), False)[1])
        GLib.timeout_add(int(args.wait * 1000), lambda: (save(win, args.out), app.quit(), False)[2])
        return False

    import odv.window

    _init = odv.window.Window.__init__

    def sized_init(self, *a, **kw):
        _init(self, *a, **kw)
        self.set_default_size(width, height)

    odv.window.Window.__init__ = sized_init
    app.connect("activate", lambda a: GLib.timeout_add(200, drive))
    sys.exit(app.run([sys.argv[0]]))


if __name__ == "__main__":
    main()
