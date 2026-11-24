"""GUI tests. They need a display: run them under Xvfb (make test)."""

import os
import shutil
import subprocess
import sys
import time

import pytest

FIX = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src")

pytestmark = pytest.mark.skipif(not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"),
                                reason="needs a display (run under xvfb-run)")


def run_app(scenario, timeout=20):
    """Run the app and drive it with ``scenario(win, result)``, a generator
    that yields seconds to wait between steps. Returns ``result``."""
    import gi

    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from gi.repository import Gio, GLib

    from odv.app import Application

    app = Application(time.perf_counter())
    app.set_flags(app.get_flags() | Gio.ApplicationFlags.NON_UNIQUE)
    result = {}

    def on_activate(_app):
        state = {}

        def step():
            try:
                # The window exists once the class handler of "activate" ran.
                if "gen" not in state:
                    state["gen"] = scenario(app.get_active_window(), result)
                delay = next(state["gen"])
            except StopIteration:
                app.quit()
                return False
            except Exception as e:  # noqa: BLE001
                result["error"] = e
                app.quit()
                return False
            GLib.timeout_add(int(delay * 1000), step)
            return False

        GLib.timeout_add(100, step)

    app.connect("activate", on_activate)
    GLib.timeout_add_seconds(timeout, lambda: (result.setdefault("timeout", True), app.quit(), False)[2])
    app.run([sys.argv[0]])
    if "error" in result:
        raise result["error"]
    assert "timeout" not in result, "scenario timed out"
    return result


def buffer_text(doc):
    view = doc.views[doc.mode]
    buf = view.buffer
    return buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)


@pytest.fixture(autouse=True)
def state_dir(tmp_path, monkeypatch):
    from odv import settings

    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    settings.reset_cache()
    yield
    settings.reset_cache()


def test_markdown_formatted_then_source():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.md"))
        yield 1.0
        doc = win.active_pane.current_doc()
        r["mode0"] = doc.mode
        r["formatted"] = buffer_text(doc)
        win.toggle_mode()
        yield 1.0
        r["mode1"] = doc.mode
        r["source"] = buffer_text(doc)
        r["switch"] = win.active_pane.mode_switch.source.get_active()

    r = run_app(scenario)
    assert r["mode0"] == "formatted" and r["mode1"] == "source"
    assert "Sample document" in r["formatted"] and "# Sample" not in r["formatted"]
    assert r["source"].startswith("# Sample document")
    assert r["switch"]


def test_code_and_config_files_open_in_source():
    files = ["sample.py", "sample.json", ".env"]

    def scenario(win, r):
        for f in files:
            win.open_path(os.path.join(FIX, f))
        yield 1.5
        r["docs"] = [(d.name, d.mode, buffer_text(d)) for d in win.active_pane.docs()]
        r["switch_visible"] = win.active_pane.mode_switch.get_visible()

    r = run_app(scenario)
    assert [d[0] for d in r["docs"]] == files
    assert all(mode == "source" for _, mode, _ in r["docs"])
    assert r["docs"][2][2].startswith("DATABASE_URL=")
    assert not r["switch_visible"]


def test_csv_table():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.csv"))
        yield 1.0
        doc = win.active_pane.current_doc()
        table = doc.views["formatted"]
        r["rows"] = table.model.get_n_items()
        r["cols"] = [c.get_title() for c in table.columnview.get_columns()]
        r["numeric"] = table._numeric
        doc.set_mode("source")
        yield 1.0
        r["source"] = buffer_text(doc)

    r = run_app(scenario)
    assert r["rows"] == 3
    assert r["cols"] == ["#", "id", "city", "qty"]
    assert r["numeric"] == [True, False, True]
    assert r["source"].startswith("id,city,qty")


def test_split_view_and_find():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.md"))
        yield 0.8
        win.toggle_split()
        yield 1.0
        r["panes"] = len(win.panes)
        right = win.panes[1].current_doc()
        r["right_mode"] = right.mode
        win.find()
        right.search_entry.set_text("hello")
        yield 0.5
        buf = right.views["source"].buffer
        b = buf.get_selection_bounds()
        r["selected"] = buf.get_text(b[0], b[1], False) if b else None
        win.toggle_split()
        yield 0.3
        r["panes_after"] = len(win.panes)

    r = run_app(scenario)
    assert r["panes"] == 2 and r["right_mode"] == "source"
    assert r["selected"] == "hello"
    assert r["panes_after"] == 1


def test_folder_tree_and_links():
    def scenario(win, r):
        win.open_folder(FIX)
        yield 0.5
        names = []
        model = win.tree.tree
        for i in range(model.get_n_items()):
            names.append(model.get_item(i).get_item().name)
        r["names"] = names
        win.tree._activate(None, names.index("sample.md"))
        yield 1.0
        doc = win.active_pane.current_doc()
        r["opened"] = doc.name
        doc.views["formatted"].follow("sample.py")
        yield 0.8
        r["after_link"] = win.active_pane.current_doc().name
        r["tabs"] = len(win.active_pane.docs())

    r = run_app(scenario)
    assert r["names"][0] == "sample.csv" or ".env" in r["names"]
    assert "binary.png" in r["names"] and "sample.md" in r["names"]
    assert r["opened"] == "sample.md"
    assert r["after_link"] == "sample.py" and r["tabs"] == 2


def test_binary_and_missing_files():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "binary.png"))
        win.open_path(os.path.join(FIX, "does-not-exist.txt"))
        yield 1.0
        doc = win.active_pane.current_doc()
        r["visible"] = type(doc.stack.get_visible_child()).__name__
        r["tabs"] = len(win.active_pane.docs())

    r = run_app(scenario)
    assert r["visible"] == "StatusPage"
    assert r["tabs"] == 1


def test_closing_last_tab_shows_welcome():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.py"))
        yield 0.5
        r["before"] = win.stack.get_visible_child_name()
        win.close_tab()
        yield 0.5
        r["after"] = win.stack.get_visible_child_name()

    r = run_app(scenario)
    assert (r["before"], r["after"]) == ("docs", "welcome")


def test_startup_benchmark():
    """Cold start and file open, through the real command line."""
    budget = float(os.environ.get("ODV_STARTUP_BUDGET_MS", "1500"))
    env = dict(os.environ, PYTHONPATH=SRC)
    files = [os.path.join(FIX, f) for f in ("sample.md", "sample.py", "sample.csv")]
    out = subprocess.run([sys.executable, "-m", "odv", "--bench", *files], env=env,
                         capture_output=True, text=True, timeout=60).stdout
    lines = [ln.split() for ln in out.splitlines() if ln.startswith("odv-bench")]
    startup = next(ln for ln in lines if ln[1] == "startup")
    ms = float(startup[2].split("=")[1])
    loaded = [ln for ln in lines if ln[1] == "loaded"]
    assert len(loaded) == 3, out
    assert ms < budget, f"start-up took {ms} ms (budget {budget} ms)"


@pytest.fixture
def config_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    return tmp_path / "config"


def test_theme_background_rules(config_dir):
    from gi.repository import Adw

    def scenario(win, r):
        from odv import theme

        r["default"] = theme.theme_id()
        r["picture_before"] = win.background.get_visible()
        theme.set_theme("dracula")
        for _ in range(50):  # decoded in a thread: wait for it, up to 10 s
            if win.background.get_paintable() is not None:
                break
            yield 0.2
        r["dark"] = Adw.StyleManager.get_default().get_dark()
        r["picture"] = win.background.get_visible() and win.background.get_paintable() is not None
        win.open_path(os.path.join(FIX, "sample.md"))
        yield 1.0
        doc = win.active_pane.current_doc()
        r["solid"] = doc.has_css_class("document-area")
        theme.set_background(False)
        yield 0.3
        r["picture_off"] = win.background.get_visible()
        theme.set_theme("pastel")
        theme.set_background(True)
        yield 1.0
        r["pastel_dark"] = Adw.StyleManager.get_default().get_dark()
        r["scheme"] = doc.views["formatted"].buffer.get_tag_table().lookup("h1").get_property("foreground-rgba").to_string()
        win.show_preferences()
        yield 0.8
        theme.set_theme("libre-apps")
        yield 0.3
        r["libre_picture"] = win.background.get_visible()

    r = run_app(scenario)
    assert r["default"] == "libre-apps" and not r["picture_before"]
    assert r["dark"] and r["picture"] and r["solid"]
    assert not r["picture_off"]
    assert r["pastel_dark"] is False
    assert r["scheme"] == "rgb(108,85,184)"  # Pastel's markdown heading, #6C55B8
    assert not r["libre_picture"]
    import json

    saved = json.loads((config_dir / "open-documents-viewer" / "settings.json").read_text())
    assert saved == {"theme": "libre-apps", "background": True, "watch": True}


def test_background_waits_for_first_frame(config_dir):
    """The picture must not be decoded before the window has drawn once."""
    (config_dir / "open-documents-viewer").mkdir(parents=True)
    (config_dir / "open-documents-viewer" / "settings.json").write_text('{"theme": "neon"}')
    env = dict(os.environ, PYTHONPATH=SRC, XDG_CONFIG_HOME=str(config_dir))
    out = subprocess.run([sys.executable, "-m", "odv", "--bench", os.path.join(FIX, "sample.csv")], env=env,
                         capture_output=True, text=True, timeout=60).stdout
    events = [ln.split()[1] for ln in out.splitlines() if ln.startswith("odv-bench")]
    assert events[0] == "startup", out
    if "background" in events:
        assert events.index("background") > events.index("startup")


def test_reload_when_the_file_changes(tmp_path):
    from odv import settings

    path = tmp_path / "notes.txt"
    path.write_text("".join(f"line {i}\n" for i in range(3000)))

    def scenario(win, r):
        win.open_path(str(path))
        yield 1.0
        doc = win.active_pane.current_doc()
        adj = doc.views["source"].get_vadjustment()
        adj.set_value(2000)
        yield 0.3
        r["before"] = adj.get_value()
        path.write_text("changed\n" + "".join(f"line {i}\n" for i in range(3000)))
        yield 2.0
        doc2 = win.active_pane.current_doc()
        r["replaced"] = doc2 is not doc
        r["text"] = buffer_text(doc2).split("\n", 1)[0]
        r["after"] = doc2.views["source"].get_vadjustment().get_value()
        r["tabs"] = len(win.active_pane.docs())
        settings.set("watch", False)
        path.write_text("ignored\n")
        yield 1.5
        r["ignored"] = win.active_pane.current_doc() is doc2
        settings.set("watch", True)
        path.unlink()
        yield 1.5
        r["deleted"] = doc2.deleted and doc2.banner.get_revealed()

    r = run_app(scenario)
    assert r["replaced"] and r["text"] == "changed" and r["tabs"] == 1
    assert r["before"] > 1000 and abs(r["after"] - r["before"]) < 60, r
    assert r["ignored"]
    assert r["deleted"]


def test_interface_in_portuguese():
    from odv import i18n

    i18n.set_language("pt")
    try:
        def scenario(win, r):
            yield 0.5
            r["button"] = win.welcome.list.get_parent()  # built
            win.open_path(os.path.join(FIX, "sample.md"))
            yield 1.0
            r["switch"] = win.active_pane.mode_switch.formatted.get_label()

        r = run_app(scenario)
        assert r["switch"] == "Formatado"
    finally:
        i18n.set_language("en")
