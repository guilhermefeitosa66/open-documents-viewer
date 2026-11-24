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


def settle(win, timeout=10.0):
    """Wait (as a scenario step) until every open document has loaded."""
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        docs = [d for pane in win.panes for d in pane.docs()]
        if all(d._loaded_sent for d in docs):
            break
        yield 0.1
    yield 0.3


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
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["mode0"] = doc.mode
        r["formatted"] = buffer_text(doc)
        win.toggle_mode()
        yield from settle(win)
        r["mode1"] = doc.mode
        r["source"] = buffer_text(doc)
        r["switch"] = win.active_pane.mode_switch.source.get_active()

    r = run_app(scenario)
    assert r["mode0"] == "formatted" and r["mode1"] == "source"
    assert "Sample document" in r["formatted"] and "# Sample" not in r["formatted"]
    assert r["source"].startswith("# Sample document")
    assert r["switch"]


def test_code_and_config_files_open_in_source():
    files = ["sample.py", "sample.toml", ".env"]

    def scenario(win, r):
        for f in files:
            win.open_path(os.path.join(FIX, f))
        yield from settle(win)
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
        yield from settle(win)
        doc = win.active_pane.current_doc()
        table = doc.views["formatted"]
        r["rows"] = table.model.get_n_items()
        r["cols"] = [c.get_title() for c in table.columnview.get_columns()]
        r["numeric"] = table._numeric
        doc.set_mode("source")
        yield from settle(win)
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
        yield from settle(win)
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
        yield from settle(win)
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
        yield from settle(win)
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
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["solid"] = doc.has_css_class("document-area")
        theme.set_background(False)
        yield 0.3
        r["picture_off"] = win.background.get_visible()
        theme.set_theme("pastel")
        theme.set_background(True)
        yield from settle(win)
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
    from odv import settings

    assert saved == {**settings.DEFAULTS, "theme": "libre-apps", "background": True}


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
        yield from settle(win)
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
            yield from settle(win)
            r["switch"] = win.active_pane.mode_switch.formatted.get_label()

        r = run_app(scenario)
        assert r["switch"] == "Formatado"
    finally:
        i18n.set_language("en")


def test_find_in_table():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.csv"))
        yield from settle(win)
        doc = win.active_pane.current_doc()
        win.find()
        doc.search_entry.set_text("recife")
        yield 0.8
        r["mode"] = doc.mode
        sel = doc.views["formatted"].selection.get_selection()
        r["row"] = sel.get_nth(0) if sel.get_size() else None
        r["red"] = doc.search_entry.has_css_class("error")
        doc.search_entry.set_text("nowhere")
        yield 0.8
        r["red2"] = doc.search_entry.has_css_class("error")

    r = run_app(scenario)
    assert r["mode"] == "formatted"  # the table stays a table
    assert r["row"] == 1 and not r["red"]
    assert r["red2"]


def test_search_in_folder_opens_at_the_line(tmp_path):
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "guide.md").write_text("# Guide\n\nintro\n\nThe magic word is here.\n")
    (tmp_path / "other.py").write_text("print('nothing')\n")

    def scenario(win, r):
        win.open_folder(str(tmp_path))
        yield 0.3
        win.search_folder()
        win.search_panel.entry.set_text("magic")
        for _ in range(30):
            yield 0.2
            if win.search_panel.status.get_label().endswith("files."):
                break
        r["status"] = win.search_panel.status.get_label()
        rows = []
        i = 0
        while (row := win.search_panel.list.get_row_at_index(i)) is not None:
            rows.append(getattr(row, "_target", None))
            i += 1
        r["targets"] = rows
        win.search_panel._on_row(None, win.search_panel.list.get_row_at_index(1))
        yield from settle(win)
        doc = win.active_pane.current_doc()
        buf = doc.views["source"].buffer
        b = buf.get_selection_bounds()
        r["doc"] = (doc.name, doc.mode)
        r["selected"] = (b[0].get_line() + 1, buf.get_text(b[0], b[1], False)) if b else None

    r = run_app(scenario)
    assert r["status"] == "1 matches in 1 files."
    assert r["targets"] == [None, (str(tmp_path / "docs" / "guide.md"), 5)]
    assert r["doc"] == ("guide.md", "source")
    assert r["selected"] == (5, "magic")


def test_quick_open_and_command_palette(tmp_path):
    tmp_path = tmp_path / "project"
    tmp_path.mkdir()
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "parser.py").write_text("x = 1\n")
    (tmp_path / "README.md").write_text("# hi\n")

    def scenario(win, r):
        from odv import theme

        win.open_folder(str(tmp_path))
        dialog = win.quick_open()
        yield 0.6
        r["count"] = dialog.status.get_label()
        dialog.entry.set_text("prs")
        yield 0.4
        first = dialog.list.get_row_at_index(0)
        r["first"] = first._item.label if first else None
        dialog.entry.emit("activate")
        yield from settle(win)
        r["opened"] = win.active_pane.current_doc().name
        palette = win.command_palette()
        palette.entry.set_text("theme drac")
        yield 0.4
        r["command"] = palette.list.get_row_at_index(0)._item.label
        palette.entry.emit("activate")
        yield 0.5
        r["theme"] = theme.theme_id()
        theme.set_theme("libre-apps")

    r = run_app(scenario)
    assert r["count"] == "2 items"
    assert r["first"] == "parser.py"
    assert r["opened"] == "parser.py"
    assert r["command"] == "Theme: Dracula"
    assert r["theme"] == "dracula"


def test_outline_panel(tmp_path):
    md = tmp_path / "long.md"
    parts = []
    for i in range(1, 9):
        parts.append(f"## Section {i}\n\n" + "".join(f"Paragraph {i}.{j} with some words.\n\n" for j in range(30)))
    md.write_text("# Long\n\n" + "".join(parts))

    def scenario(win, r):
        win.open_path(str(md))
        yield from settle(win)
        win.show_outline()
        yield 0.3
        panel = win.outline_panel
        rows = []
        i = 0
        while (row := panel.list.get_row_at_index(i)) is not None:
            rows.append(row._entry.title)
            i += 1
        r["rows"] = rows
        doc = win.active_pane.current_doc()
        panel._on_row(None, panel.list.get_row_at_index(6))  # Section 6
        yield 0.6
        r["formatted_pos"] = doc.outline_position()
        r["selected"] = panel.list.get_selected_row().get_index() if panel.list.get_selected_row() else None
        doc.set_mode("source")
        yield 0.8
        panel._on_row(None, panel.list.get_row_at_index(3))  # Section 3
        yield 0.6
        r["source_pos"] = doc.outline_position()

    r = run_app(scenario)
    assert r["rows"] == ["Long"] + [f"Section {i}" for i in range(1, 9)]
    assert r["formatted_pos"] == 6 and r["selected"] == 6
    assert r["source_pos"] == 3


def test_links_between_files_and_external(tmp_path):
    other = tmp_path / "other.md"
    other.write_text("# Other\n\n" + "".join(f"## Part {i}\n\n" + "words\n\n" * 40 for i in range(1, 6)))
    main = tmp_path / "main.md"
    main.write_text("# Main\n\nGo to [part four](other.md#part-4) or [the web](https://libre-apps.org).\n")

    def scenario(win, r):
        win.open_path(str(main))
        yield from settle(win)
        view = win.active_pane.current_doc().views["formatted"]
        view.follow("other.md#part-4")
        yield 1.5
        doc = win.active_pane.current_doc()
        r["opened"] = (doc.name, len(win.active_pane.docs()))
        r["section"] = doc.outline()[doc.outline_position()].title
        view.follow("missing.md")
        yield 0.3
        view.follow("https://libre-apps.org")
        yield 0.5
        dialog = win.get_visible_dialog()
        r["dialog"] = type(dialog).__name__ if dialog else None
        if dialog:
            dialog.close()

    r = run_app(scenario)
    assert r["opened"] == ("other.md", 2)
    assert r["section"] == "Part 4"
    assert r["dialog"] == "AlertDialog"


def test_session_round_trip(tmp_path):
    from odv import session

    folder = tmp_path / "proj"
    folder.mkdir()
    a = folder / "a.md"
    a.write_text("# A\n\n" + "text\n\n" * 400)
    b = folder / "b.py"
    b.write_text("".join(f"x{i} = {i}\n" for i in range(400)))

    def first(win, r):
        win.open_folder(str(folder))
        win.open_path(str(a))
        win.open_path(str(b))
        yield from settle(win)
        win.toggle_split()
        yield from settle(win)
        left = win.panes[0].current_doc()
        left.views["source"].get_vadjustment().set_value(900)
        yield 0.3
        win._on_close_request(win)
        r["saved"] = session.load()

    r = run_app(first)
    saved = r["saved"]
    assert saved["folder"] == str(folder)
    assert [t["path"] for t in saved["panes"][0]["tabs"]] == [str(a), str(b)]
    assert saved["panes"][0]["selected"] == 1 and saved["panes"][0]["tabs"][1]["scroll"] >= 800
    assert len(saved["panes"]) == 2

    def second(win, r):
        yield 2.0
        r["folder"] = win.folder
        r["panes"] = [[d.name for d in p.docs()] for p in win.panes]
        doc = win.panes[0].current_doc()
        r["current"] = doc.name
        r["scroll"] = doc.scroll_position()

    r = run_app(second)
    assert r["folder"] == str(folder)
    assert r["panes"][0] == ["a.md", "b.py"] and len(r["panes"]) == 2
    assert r["current"] == "b.py" and r["scroll"] >= 800


def test_tab_moved_to_the_other_pane_reloads_there(tmp_path):
    path = tmp_path / "moving.txt"
    path.write_text("one\n")

    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.py"))
        win.open_path(str(path))
        yield 0.8
        win.toggle_split()
        yield 0.5
        left, right = win.panes
        page = left.find_page(str(path))
        left.view.transfer_page(page, right.view, 0)
        yield 0.3
        path.write_text("two\n")
        yield 1.5
        doc = right.find_page(str(path))
        r["right"] = buffer_text(doc.get_child()).strip() if doc else None
        r["left"] = [d.name for d in left.docs()]

    r = run_app(scenario)
    assert r["right"] == "two"
    assert "moving.txt" not in r["left"]


def test_reading_comfort():
    from odv import settings

    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.py"))
        win.open_path(os.path.join(FIX, "sample.md"))
        yield from settle(win)
        md = win.active_pane.current_doc()
        win._zoom(1)
        win._zoom(1)
        yield 0.2
        r["prose_classes"] = [c for c in md.views["formatted"].view.get_css_classes() if c.startswith("zoom")]
        settings.set("prose_width", "narrow")
        r["clamp"] = md.views["formatted"].clamp.get_maximum_size()
        win.active_pane.view.set_selected_page(win.active_pane.find_page(os.path.join(FIX, "sample.py")))
        yield 0.3
        code = win.active_pane.current_doc()
        src = code.views["source"]
        r["code_classes"] = [c for c in src.view.get_css_classes() if c.startswith("zoom")]
        win.toggle_wrap()
        r["wrap"] = src.get_wrap()
        settings.set("minimap", True)
        settings.set("line_numbers", False)
        r["minimap"] = src.minimap is not None
        r["numbers"] = src.view.get_show_line_numbers()
        settings.set("minimap", False)
        r["minimap_off"] = src.minimap is None
        win.toggle_focus_mode()
        r["focus"] = (win.content_view.get_reveal_top_bars(), win.active_pane.bar.get_visible())
        win.toggle_focus_mode()
        r["unfocus"] = (win.content_view.get_reveal_top_bars(), win.active_pane.bar.get_visible())
        settings.set("line_numbers", True)

    r = run_app(scenario)
    assert r["prose_classes"] == ["zoom-120"]
    assert r["clamp"] == 680
    assert r["code_classes"] == []  # zoom is per kind
    assert r["wrap"] is True
    assert r["minimap"] and r["minimap_off"] and r["numbers"] is False
    assert r["focus"] == (False, False) and r["unfocus"] == (True, True)


def test_file_actions(tmp_path):
    from odv import fileactions

    original = os.path.join(FIX, "sample.py")
    before = open(original, "rb").read()

    def scenario(win, r):
        win.open_path(original)
        win.open_path(os.path.join(FIX, "sample.md"))
        yield from settle(win)
        md = win.active_pane.current_doc()
        res = fileactions.print_doc(win, md, export_to=str(tmp_path / "md.pdf"))
        r["md_print"] = (res, (tmp_path / "md.pdf").read_bytes()[:4])
        win.active_pane.view.set_selected_page(win.active_pane.find_page(original))
        yield 0.3
        code = win.active_pane.current_doc()
        fileactions.print_doc(win, code, export_to=str(tmp_path / "code.pdf"))
        r["code_print"] = (tmp_path / "code.pdf").read_bytes()[:4]
        done = []
        fileactions.copy_to(win, original, str(tmp_path / "copy.py"), done.append)
        fileactions.copy_to(win, original, original, done.append)
        yield 0.8
        r["copies"] = done
        dialog = win.file_action("properties")
        yield 0.3
        r["dialog"] = win.get_visible_dialog() is dialog
        dialog.close()
        win.file_action("copy-path")
        win.command_palette().close()

    r = run_app(scenario)
    assert r["md_print"][1] == b"%PDF" and r["code_print"] == b"%PDF"
    assert sorted(r["copies"]) == [False, True]
    assert (tmp_path / "copy.py").read_bytes() == before
    assert open(original, "rb").read() == before
    assert r["dialog"]


def test_html_file():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.html"))
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["mode"] = doc.mode
        r["text"] = buffer_text(doc)
        r["outline"] = [e.title for e in doc.outline()]
        doc.views["formatted"].follow("#details")
        yield 0.3
        doc.set_mode("source")
        yield 0.8
        r["source"] = buffer_text(doc)

    r = run_app(scenario)
    assert r["mode"] == "formatted"
    assert "Sample & page" in r["text"] and "never shown" not in r["text"]
    assert r["outline"] == ["Sample & page", "Details", "Last part"]
    assert r["source"].startswith("<!doctype html>")


IMG = os.path.join(FIX, "images")


def test_images():
    def scenario(win, r):
        win.open_path(os.path.join(IMG, "a-red.png"))
        yield from settle(win)
        doc = win.active_pane.current_doc()
        view = doc.views["formatted"]
        r["switch"] = win.active_pane.mode_switch.get_visible()
        r["size"] = (view.width, view.height, view.format_name)
        r["zoom"] = [doc.zoom(1), doc.zoom(1), doc.zoom(0)]
        r["info"] = view.info.get_label()
        view.step(1)  # next image, same tab
        yield from settle(win)
        r["next"] = (win.active_pane.current_doc().name, len(win.active_pane.docs()))
        win.active_pane.current_doc().views["formatted"].step(1)
        yield from settle(win)
        anim = win.active_pane.current_doc().views["formatted"]
        r["animated"] = anim._anim_iter is not None
        win.open_path(os.path.join(IMG, "d-shape.svg"))
        yield from settle(win)
        svg = win.active_pane.current_doc()
        r["svg_switch"] = win.active_pane.mode_switch.get_visible()
        svg.views["formatted"].set_scale(3.0)
        yield 0.8
        r["svg_texture"] = svg.views["formatted"].picture.get_paintable().get_width()
        svg.set_mode("source")
        yield 0.6
        r["svg_source"] = buffer_text(svg).startswith("<svg")
        win.open_path(os.path.join(IMG, "e-broken.png"))
        yield from settle(win)
        r["broken"] = type(win.active_pane.current_doc().stack.get_visible_child()).__name__

    r = run_app(scenario)
    assert r["switch"] is False
    assert r["size"] == (40, 30, "PNG")
    assert r["zoom"][0] > 100 and r["zoom"][1] > r["zoom"][0]
    assert "40 × 30" in r["info"] and "PNG" in r["info"]
    assert r["next"] == ("b-blue.png", 1)
    assert r["animated"] is True
    assert r["svg_switch"] is True and r["svg_texture"] == 360 and r["svg_source"]
    assert r["broken"] == "StatusPage"


def test_rtf_file():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.rtf"))
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["mode"] = doc.mode
        r["text"] = buffer_text(doc)
        table = doc.views["formatted"].buffer.get_tag_table()
        r["tags"] = all(table.lookup(t) is not None for t in ("fg-B5502B", "scale-150"))
        doc.set_mode("source")
        yield 0.6
        r["source"] = buffer_text(doc)[:6]

    r = run_app(scenario)
    assert r["mode"] == "formatted" and "Accents: olá, coração, € euro." in r["text"]
    assert r["tags"] and r["source"] == "{\\rtf1"


def test_notebook_file():
    def scenario(win, r):
        win.open_path(os.path.join(FIX, "sample.ipynb"))
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["mode"] = doc.mode
        r["text"] = buffer_text(doc)
        r["outline"] = [e.title for e in doc.outline()]
        view = doc.views["formatted"].view
        pictures = []
        child = view.get_first_child()
        while child is not None:
            if isinstance(child, Gtk.Picture):
                pictures.append(child)
            child = child.get_next_sibling()
        r["pictures"] = len(pictures)
        doc.set_mode("source")
        yield 0.6
        r["source"] = buffer_text(doc).lstrip()[:1]

    from gi.repository import Gtk  # noqa: F401 - used in the scenario

    globals()["Gtk"] = Gtk
    r = run_app(scenario)
    assert r["mode"] == "formatted" and "In [1]:" in r["text"]
    assert r["outline"] == ["Notebook title", "Results"]
    assert r["pictures"] == 1
    assert r["source"] == "{"


def test_custom_languages_and_shebang():
    files = ["sample.log", "schema.graphql", "subs.srt", "script-noext"]

    def scenario(win, r):
        for f in files:
            win.open_path(os.path.join(FIX, f))
        yield from settle(win)
        langs = {}
        for doc in win.active_pane.docs():
            lang = doc.views["source"].buffer.get_language()
            langs[doc.name] = lang.get_id() if lang else None
        r["langs"] = langs

    r = run_app(scenario)
    assert r["langs"] == {"sample.log": "odv-log", "schema.graphql": "odv-graphql", "subs.srt": "odv-subtitles",
                          "script-noext": "python3"}


def test_data_trees():
    def scenario(win, r):
        for f in ("sample.yaml", "sample.xml", "broken.json"):
            win.open_path(os.path.join(FIX, f))
        yield from settle(win)
        docs = {d.name: d for d in win.active_pane.docs()}
        yaml_doc = docs["sample.yaml"]
        tree = yaml_doc.views["formatted"]
        r["yaml_rows"] = tree.tree.get_n_items()
        win.active_pane.view.set_selected_page(win.active_pane.find_page(yaml_doc.path))
        yaml_doc.start_search()
        yaml_doc.search_entry.set_text("find me")
        yield 0.5
        r["path"] = tree.path_label.get_label()
        xml_tree = docs["sample.xml"].views["formatted"]
        xml_tree.reveal(["library", "book", 1, "title"])
        r["xml_path"] = xml_tree.path_label.get_label()
        broken = docs["broken.json"]
        r["broken"] = (broken.mode, broken.banner.get_revealed(), "line 4" in broken.banner.get_title())

    r = run_app(scenario)
    assert r["yaml_rows"] >= 3
    assert r["path"] == "$.items[1].note"
    assert r["xml_path"] == "/library/book[2]/title"
    assert r["broken"] == ("source", True, True)


def test_table_sorting(tmp_path):
    path = tmp_path / "sort.csv"
    path.write_text("name,qty\nbanana,10\napple,2\ncherry,7\n")

    def scenario(win, r):
        from gi.repository import Gtk

        win.open_path(str(path))
        yield from settle(win)
        table = win.active_pane.current_doc().views["formatted"]
        cols = table.columnview.get_columns()
        qty = cols.get_item(2)
        table.columnview.sort_by_column(qty, Gtk.SortType.DESCENDING)
        yield 0.6
        r["desc"] = [table.model.row_at(i) for i in range(3)]
        table.columnview.sort_by_column(cols.get_item(1), Gtk.SortType.ASCENDING)
        yield 0.6
        r["by_name"] = [table.index.row(table.model.row_at(i))[0] for i in range(3)]
        table.selection.select_item(0, True)
        r["copied_row"] = table.model.row_at(0)
        table.columnview.sort_by_column(None, Gtk.SortType.ASCENDING)
        yield 0.3
        r["original"] = [table.model.row_at(i) for i in range(3)]

    r = run_app(scenario)
    assert r["desc"] == [1, 3, 2]
    assert r["by_name"] == ["apple", "banana", "cherry"] and r["copied_row"] == 2
    assert r["original"] == [1, 2, 3]


def test_reopen_with_encoding(tmp_path):
    from odv import fileactions

    path = tmp_path / "accents.txt"
    path.write_bytes("ação\r\n".encode("utf-8"))

    def scenario(win, r):
        win.open_path(str(path))
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["before"] = buffer_text(doc).strip()
        rows = dict(fileactions.properties(doc.path, doc._text.encoding, 1, doc._text.line_endings))
        r["props"] = (rows["Encoding"], rows["Line endings"])
        dialog = fileactions.choose_encoding(win, doc)
        yield 0.3
        listbox = dialog.get_child().get_content().get_child().get_child().get_child()
        row = next(listbox.get_row_at_index(i) for i in range(30)
                   if listbox.get_row_at_index(i)._codec == "cp1252")
        listbox.emit("row-activated", row)
        yield from settle(win)
        doc2 = win.active_pane.current_doc()
        r["after"] = (buffer_text(doc2).strip(), doc2.forced_encoding, len(win.active_pane.docs()))

    r = run_app(scenario)
    assert r["before"] == "ação" and r["props"] == ("utf-8", "CRLF")
    assert r["after"] == ("aÃ§Ã£o", "cp1252", 1)


def test_epub_file(tmp_path):
    import sys

    sys.path.insert(0, os.path.dirname(__file__))
    from books import make_epub

    book = str(make_epub(tmp_path / "book.epub"))

    def scenario(win, r):
        win.open_path(book)
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["first"] = (doc.mode, doc.has_source, win.active_pane.mode_switch.get_visible())
        r["outline"] = [e.title for e in doc.outline()]
        doc.views["formatted"].follow("#OEBPS/text/c2.xhtml#scene")
        yield 1.3
        r["section"] = doc.outline()[doc.outline_position()].title
        win.close_tab()
        yield 0.3
        win.open_path(book)  # reopens where it was left
        yield from settle(win)
        yield 1.3
        doc2 = win.active_pane.current_doc()
        r["reopened"] = doc2.outline()[doc2.outline_position()].title

    r = run_app(scenario)
    assert r["first"] == ("formatted", False, False)
    assert r["outline"] == ["One (from the nav)", "Two (from the nav)", "Scene"]
    assert r["section"] == "Scene" and r["reopened"] == "Scene"


def test_sidebar_filter(tmp_path):
    root = tmp_path / "proj"
    (root / "docs" / "deep").mkdir(parents=True)
    (root / "docs" / "deep" / "install-guide.md").write_text("# hi\n")
    (root / "readme.md").write_text("# readme\n")
    (root / "main.py").write_text("x = 1\n")

    def scenario(win, r):
        win.open_folder(str(root))
        yield 0.3
        panel = win.folder_panel
        panel.entry.set_text("guide")
        for _ in range(30):
            yield 0.1
            if panel.stack.get_visible_child_name() == "results":
                break
        rows = []
        i = 0
        while (row := panel.results.get_row_at_index(i)) is not None:
            rows.append(os.path.relpath(row._path, root))
            i += 1
        r["rows"] = rows
        panel.entry.emit("activate")
        yield from settle(win)
        r["opened"] = win.active_pane.current_doc().name
        panel.entry.set_text("")
        yield 0.4
        r["back"] = panel.stack.get_visible_child_name()

    r = run_app(scenario)
    assert r["rows"] == [os.path.join("docs", "deep", "install-guide.md")]
    assert r["opened"] == "install-guide.md" and r["back"] == "tree"


def test_email_contacts_calendar():
    def scenario(win, r):
        for f in ("sample.eml", "contacts.vcf", "events.ics"):
            win.open_path(os.path.join(FIX, f))
        yield from settle(win)
        docs = {d.name: d for d in win.active_pane.docs()}
        r["modes"] = sorted(d.mode for d in docs.values())
        r["eml"] = buffer_text(docs["sample.eml"]).split("\n", 1)[0]
        r["outline"] = [e.title for e in docs["events.ics"].outline()]
        docs["contacts.vcf"].set_mode("source")
        yield 0.6
        r["vcf_source"] = buffer_text(docs["contacts.vcf"]).startswith("BEGIN:VCARD")

    r = run_app(scenario)
    assert r["modes"] == ["formatted", "formatted", "formatted"]
    assert r["eml"] == "Relatório de março"
    assert r["outline"] == ["First meeting", "Second", "Entregar"]
    assert r["vcf_source"]


def test_docx_file(tmp_path):
    import sys

    sys.path.insert(0, os.path.dirname(__file__))
    from office import make_docx

    path = str(make_docx(tmp_path / "report.docx"))

    def scenario(win, r):
        win.open_path(path)
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["state"] = (doc.mode, doc.has_source, win.active_pane.mode_switch.get_visible())
        r["text"] = buffer_text(doc)
        r["outline"] = [e.title for e in doc.outline()]
        table = doc.views["formatted"].buffer.get_tag_table()
        r["align"] = table.lookup("align-center") is not None

    r = run_app(scenario)
    assert r["state"] == ("formatted", False, False)
    assert "Plain bold, red italic and a link." in r["text"]
    assert r["outline"] == ["Report Title", "Introduction", "Details"] and r["align"]


def test_odt_file(tmp_path):
    import sys

    sys.path.insert(0, os.path.dirname(__file__))
    from office import make_odt

    path = str(make_odt(tmp_path / "report.odt"))

    def scenario(win, r):
        win.open_path(path)
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["mode"] = doc.mode
        r["outline"] = [e.title for e in doc.outline()]
        r["text"] = buffer_text(doc)

    r = run_app(scenario)
    assert r["mode"] == "formatted" and r["outline"] == ["Report Title", "Introduction", "Details"]
    assert "1. First step" in r["text"]


def test_doc_file(tmp_path):
    import sys

    sys.path.insert(0, os.path.dirname(__file__))
    from cfb import make_doc

    path = str(make_doc(tmp_path / "old.doc", "Old title\rSome text.\r"))

    def scenario(win, r):
        win.open_path(path)
        yield from settle(win)
        doc = win.active_pane.current_doc()
        r["text"] = buffer_text(doc)
        r["banner"] = (doc.banner.get_revealed(), doc.banner.get_title().startswith("Plain text"))

    r = run_app(scenario)
    assert r["text"].startswith("Old title\nSome text.")
    assert r["banner"] == (True, True)


def test_xlsx_file(tmp_path):
    import sys

    sys.path.insert(0, os.path.dirname(__file__))
    from office import make_xlsx

    path = str(make_xlsx(tmp_path / "sales.xlsx"))
    big = str(make_xlsx(tmp_path / "big.xlsx", extra_rows=30000))

    def scenario(win, r):
        win.open_path(path)
        yield from settle(win)
        doc = win.active_pane.current_doc()
        view = doc.views["formatted"]
        r["state"] = (doc.mode, doc.has_source)
        r["tabs"] = [b.get_label() for b in iter_children(view.tabs)]
        r["columns"] = [c.get_title() for c in view.columnview.get_columns()]
        r["rows"] = list(view.model.rows)
        doc.start_search()
        doc.search_entry.set_text("total")
        yield 0.4
        r["current"] = view.current
        view.set_current(3, 1)
        r["bar"] = (view.ref_label.get_label(), view.value_label.get_label())
        r["copy"] = view.copy()
        win.open_path(big)
        yield from settle(win)
        bigview = win.active_pane.current_doc().views["formatted"]
        r["big_rows"] = len(bigview.model.rows)

    r = run_app(scenario)
    assert r["state"] == ("formatted", False)
    assert r["tabs"] == ["Sales"]  # the hidden sheet is not shown
    assert r["columns"] == ["", "A", "B", "C"]  # column D is hidden
    assert r["rows"] == [0, 1, 3, 4]  # row 3 is hidden
    assert r["current"] == (3, 0)
    assert r["bar"] == ("B4", "=SUM(B2:B2)   →   1,234.50") and r["copy"] == "1,234.50"
    assert r["big_rows"] == 30004


def iter_children(widget):
    child = widget.get_first_child()
    while child is not None:
        yield child
        child = child.get_next_sibling()
