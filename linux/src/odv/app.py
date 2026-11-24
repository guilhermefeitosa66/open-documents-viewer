"""The Adw.Application: command line, actions, windows."""

import os

from gi.repository import Adw, Gio, GLib, Gtk

from odv import APP_ID, APP_NAME, __version__, theme
from odv.bench import Bench
from odv.i18n import _

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")


class Application(Adw.Application):
    def __init__(self, t0: float):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.HANDLES_OPEN)
        self.bench = Bench(t0)
        self.set_option_context_parameter_string("[FILE|FOLDER…]")
        self.set_option_context_summary(_("A fast, read-only viewer for Markdown, text, code and data files."))
        self.add_main_option("version", ord("v"), GLib.OptionFlags.NONE, GLib.OptionArg.NONE,
                             "Show the version and quit", None)
        self.add_main_option("new-window", ord("n"), GLib.OptionFlags.NONE, GLib.OptionArg.NONE,
                             "Open the files in a new window", None)
        self.add_main_option("bench", 0, GLib.OptionFlags.NONE, GLib.OptionArg.NONE,
                             "Print start-up and file-open timings, then quit", None)
        self._new_window = False

    def do_handle_local_options(self, options):
        opts = options.end().unpack()
        if "version" in opts:
            print(f"{APP_NAME} {__version__}")
            return 0
        if "bench" in opts:
            self.bench.enabled = True
            self.set_flags(self.get_flags() | Gio.ApplicationFlags.NON_UNIQUE)
        if "new-window" in opts:
            self._new_window = True
        return -1

    def do_startup(self):
        Adw.Application.do_startup(self)
        theme.install()
        Gtk.IconTheme.get_for_display(_display()).add_search_path(
            os.path.join(DATA_DIR, "icons"))
        Gtk.Window.set_default_icon_name(APP_ID)
        for name, cb, accels in (
            ("quit", lambda *a: self._quit(), ["<Control>q"]),
            ("about", self._about, []),
            ("new-window", lambda *a: self._window(new=True).present(), ["<Control>n"]),
        ):
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", cb)
            self.add_action(action)
            if accels:
                self.set_accels_for_action(f"app.{name}", accels)

    def _quit(self):
        win = self.get_active_window()
        if win is not None and hasattr(win, "_on_close_request"):
            win._on_close_request(win)
        self.quit()

    def _window(self, new=False):
        from odv.window import Window

        win = None if new else self.get_active_window()
        return win or Window(self)

    def do_activate(self):
        from odv import session, settings

        fresh = self.get_active_window() is None
        win = self._window()
        if fresh and not self.bench.enabled and settings.get("restore_session"):
            state = session.load()
            if state:
                win.restore_session(state)
        win.present()

    def do_open(self, files, n_files, hint):
        win = self._window(new=self._new_window)
        self._new_window = False
        for f in files:
            path = f.get_path()
            if path:
                win.open_path(path)
        win.present()

    def _about(self, *_):
        kwargs = dict(application_name=APP_NAME, application_icon=APP_ID, version=__version__,
                      developer_name="Guilherme Feitosa", license_type=Gtk.License.APACHE_2_0,
                      website="https://libre-apps.org",
                      issue_url="https://github.com/guilhermefeitosa66/open-documents-viewer/issues",
                      comments=_("Fast, offline, read-only. No ads, no account, no tracking."))
        if hasattr(Adw, "AboutDialog"):
            Adw.AboutDialog(**kwargs).present(self.get_active_window())
        else:
            Adw.AboutWindow(transient_for=self.get_active_window(), **kwargs).present()


def _display():
    from gi.repository import Gdk

    return Gdk.Display.get_default()
