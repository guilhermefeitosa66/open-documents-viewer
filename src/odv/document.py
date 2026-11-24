"""One open file: its views (formatted and/or source), find bar and banners."""

import os
import threading
import time

from gi.repository import Adw, Gio, GLib, GObject, Gtk

from odv import settings
from odv.bench import after_next_paint
from odv.filetypes import classify
from odv.textio import MAX_TEXT_BYTES, BinaryFile, human_size, read_text
from odv.i18n import _

BIG_FILE = 16 * 1024 * 1024


def run_in_thread(func, on_done, on_error):
    def worker():
        try:
            result = func()
        except Exception as e:  # noqa: BLE001 - surfaced in the UI
            err = e  # `e` is unbound once the except block ends
            GLib.idle_add(lambda: (on_error(err), False)[1])
            return
        GLib.idle_add(lambda: (on_done(result), False)[1])

    threading.Thread(target=worker, daemon=True).start()


class DocumentPage(Gtk.Box):
    __gsignals__ = {
        "first-content": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "loaded": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "mode-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "changed-on-disk": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "outline-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "position-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self, path: str, open_path, mode: str | None = None, scroll: float | None = None,
                 goto: tuple | None = None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.add_css_class("document-area")  # always solid, never the background picture
        self.path = os.path.abspath(path)
        self.name = os.path.basename(self.path)
        self.kind = classify(self.path)
        self.has_formatted = self.kind.has_formatted
        self.mode = mode if (mode and self.has_formatted) else ("formatted" if self.has_formatted else "source")
        self._open_path = open_path
        self.started = time.perf_counter()
        self._text = None  # odv.textio.TextFile once read
        self._reading = False
        self._first_sent = False
        self._loaded_sent = False
        self.views: dict[str, Gtk.Widget] = {}
        self.closed = False
        self._restore_scroll = scroll
        self._goto = goto  # (line, needle) to show once the source view is ready
        self._outline = []  # odv.mdrender.Heading-like entries: level, title, offset, line
        self._md_doc = None
        self._position_timer = 0
        self._ready: set[str] = set()
        self._monitor = None
        self._change_timer = 0
        self.deleted = False

        self.banner = Adw.Banner(revealed=False)
        self.banner.connect("button-clicked", self._on_banner_button)
        self.append(self.banner)

        self.search_entry = Gtk.SearchEntry(placeholder_text=_("Find"), width_chars=28)
        self.search_entry.connect("search-changed", lambda e: self.find(True, from_selection_start=True))
        self.search_entry.connect("activate", lambda e: self.find(True))
        self.search_entry.connect("next-match", lambda e: self.find(True))
        self.search_entry.connect("previous-match", lambda e: self.find(False))
        self.search_entry.connect("stop-search", lambda e: self.search_bar.set_search_mode(False))
        prev_btn = Gtk.Button(icon_name="go-up-symbolic", tooltip_text=_("Previous match (Shift+Enter)"))
        prev_btn.connect("clicked", lambda b: self.find(False))
        next_btn = Gtk.Button(icon_name="go-down-symbolic", tooltip_text=_("Next match (Enter)"))
        next_btn.connect("clicked", lambda b: self.find(True))
        box = Gtk.Box(spacing=6)
        box.append(self.search_entry)
        nav = Gtk.Box(css_classes=["linked"])
        nav.append(prev_btn)
        nav.append(next_btn)
        box.append(nav)
        self.search_bar = Gtk.SearchBar(child=box, show_close_button=True)
        self.search_bar.connect_entry(self.search_entry)
        self.append(self.search_bar)

        self.progress = Gtk.ProgressBar(visible=False, valign=Gtk.Align.START)
        self.progress.add_css_class("osd")
        self.stack = Gtk.Stack(vexpand=True, hexpand=True, transition_type=Gtk.StackTransitionType.NONE)
        self.stack.add_named(Adw.Spinner() if hasattr(Adw, "Spinner") else Gtk.Spinner(spinning=True,
                             halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, width_request=32,
                             height_request=32), "loading")
        overlay = Gtk.Overlay(child=self.stack, vexpand=True)
        overlay.add_overlay(self.progress)
        self.append(overlay)

        self._show(self.mode)

    # -- signals to the outside -----------------------------------------------
    def _first_content(self):
        if not self._first_sent:
            self._first_sent = True
            after_next_paint(self, lambda: self.emit("first-content"))

    def _loaded(self):
        if not self._loaded_sent:
            self._loaded_sent = True
            after_next_paint(self, lambda: self.emit("loaded"))
            self._watch()
            if self._restore_scroll:
                self._scroll_to(self._restore_scroll, tries=40)

    # -- watching the file ----------------------------------------------------
    def _watch(self):
        if self._monitor is not None or self.closed:
            return
        try:
            self._monitor = Gio.File.new_for_path(self.path).monitor_file(Gio.FileMonitorFlags.WATCH_MOVES, None)
        except GLib.Error:
            return
        self._monitor.set_rate_limit(400)
        self._monitor.connect("changed", self._on_file_event)

    def _on_file_event(self, _monitor, _file, _other, _event):
        # Editors save in many ways (write in place, write and rename, delete
        # and create): wait for things to settle, then look at the file.
        if self._change_timer:
            GLib.source_remove(self._change_timer)
        self._change_timer = GLib.timeout_add(300, self._settle)

    def _settle(self):
        self._change_timer = 0
        if self.closed or not settings.get("watch"):
            return False
        if not os.path.exists(self.path):
            self.deleted = True
            self.banner.set_title(_("This file was deleted or moved. What you see is the last version read."))
            self.banner.set_button_label(None)
            self.banner.set_revealed(True)
            return False
        self.deleted = False
        self.emit("changed-on-disk")
        return False

    # -- outline ----------------------------------------------------------------
    def _set_outline(self, entries) -> None:
        self._outline = list(entries)
        self.emit("outline-changed")

    def outline(self) -> list:
        return self._outline

    def jump_to(self, entry) -> None:
        view = self.views.get(self.mode)
        if self.mode == "formatted" and hasattr(view, "scroll_to_offset"):
            view.scroll_to_offset(entry.offset)
        else:
            self.goto(entry.line, select=False)

    def outline_position(self) -> int:
        """Index of the outline entry the reader is in, or -1."""
        if not self._outline:
            return -1
        view = self.views.get(self.mode)
        if view is None:
            return -1
        if self.mode == "formatted" and hasattr(view, "top_offset"):
            here, key = view.top_offset(), (lambda e: e.offset)
        elif hasattr(view, "view"):
            rect = view.view.get_visible_rect()
            found = view.view.get_iter_at_location(rect.x, rect.y + 4)
            it = found[1] if isinstance(found, tuple) else found
            here, key = it.get_line() + 1, (lambda e: e.line)
        else:
            return -1
        index = -1
        for i, entry in enumerate(self._outline):
            if key(entry) <= here:
                index = i
            else:
                break
        return index

    def _on_scrolled(self, _adj):
        if not self._outline or self._position_timer:
            return
        self._position_timer = GLib.timeout_add(120, self._emit_position)

    def _emit_position(self):
        self._position_timer = 0
        if not self.closed:
            self.emit("position-changed")
        return False

    def _view_ready(self, mode: str) -> None:
        self._ready.add(mode)
        if mode == "source" and self._goto:
            self._apply_goto()

    def goto(self, line: int, needle: str | None = None, select: bool = True) -> None:
        """Show ``line`` (1-based) in the source view, selecting ``needle`` on it
        (or the whole line; nothing with ``select=False``)."""
        self._goto = (line, needle, select)
        if self.mode != "source":
            self.set_mode("source")
        if "source" in self._ready:
            self._apply_goto()

    def _apply_goto(self):
        line, needle, select = (tuple(self._goto) + (True,))[:3]
        self._goto = None
        view = self.views.get("source")
        if view is None:
            return
        tv, buf = view.view, view.buffer
        found = buf.get_iter_at_line(max(0, line - 1))
        it = found[1] if isinstance(found, tuple) else found
        end = it.copy()
        if not end.ends_line():
            end.forward_to_line_end()
        match = it.forward_search(needle, Gtk.TextSearchFlags.CASE_INSENSITIVE, end) if needle else None
        if match:
            buf.select_range(match[0], match[1])
        elif select:
            buf.select_range(it, end)
        # A search result sits a third of the way down; an outline jump at the top.
        yalign = 0.3 if select or needle else 0.0
        mark = buf.create_mark(None, it, True)
        tv.scroll_to_mark(mark, 0.0, True, 0.0, yalign)
        GLib.timeout_add(120, lambda: (tv.scroll_to_mark(mark, 0.0, True, 0.0, yalign), buf.delete_mark(mark),
                                       False)[2])

    def scroll_position(self) -> float:
        view = self.views.get(self.mode)
        if view is None or not hasattr(view, "get_vadjustment"):
            return 0.0
        return view.get_vadjustment().get_value()

    def _scroll_to(self, value: float, tries: int):
        view = self.views.get(self.mode)
        if view is None or not hasattr(view, "get_vadjustment"):
            return
        adj = view.get_vadjustment()

        def attempt(left=[tries]):  # noqa: B006 - a counter shared by the retries
            if self.closed:
                return False
            # Text views measure lines lazily: wait until the range reaches it.
            adj.set_value(min(value, max(0.0, adj.get_upper() - adj.get_page_size())))
            left[0] -= 1
            return adj.get_value() + 1 < value and left[0] > 0

        GLib.timeout_add(50, attempt)

    # -- views ----------------------------------------------------------------
    def set_mode(self, mode: str) -> None:
        if mode == self.mode or (mode == "formatted" and not self.has_formatted):
            return
        self.mode = mode
        self._show(mode)
        self.emit("mode-changed")

    def toggle_mode(self) -> None:
        if self.has_formatted:
            self.set_mode("source" if self.mode == "formatted" else "formatted")

    def _show(self, mode: str) -> None:
        if mode in self.views:
            self.stack.set_visible_child(self.views[mode])
            self._update_banner()
            return
        self.stack.set_visible_child_name("loading")
        if mode == "formatted" and self.kind.kind == "delimited":
            self._build_table()
        elif self._text is not None:
            self._build_text_view(mode)
        else:
            self._read()

    def _read(self):
        if self._reading:
            return
        self._reading = True

        def work():
            tf = read_text(self.path)
            doc = None
            if self.kind.kind == "markdown":  # parsed even for the source view: the outline
                from odv import mdrender

                doc = mdrender.build(tf.text)
            return tf, doc

        def done(result):
            self._reading = False
            if self.closed:
                return
            self._text, doc = result
            if doc is not None:
                self._md_doc = doc
                if self.mode != "formatted":
                    self._set_outline(doc.outline)
            self._build_text_view(self.mode, doc if self.mode == "formatted" else None)

        def failed(err):
            self._reading = False
            if isinstance(err, BinaryFile):
                self._status(_("This is a binary file"), _("This file type cannot be shown yet."),
                             "dialog-information-symbolic")
            else:
                self._status(_("Could not open this file"), getattr(err, "strerror", None) or str(err),
                             "dialog-warning-symbolic")

        run_in_thread(work, done, failed)

    def _status(self, title, description, icon):
        page = Adw.StatusPage(title=title, description=GLib.markup_escape_text(description), icon_name=icon)
        self.stack.add_child(page)
        self.stack.set_visible_child(page)
        self._first_content()
        self._loaded()

    def _build_text_view(self, mode: str, doc=None) -> None:
        if mode in self.views:
            return
        tf = self._text
        if mode == "formatted" and self.kind.kind == "markdown":
            doc = doc or self._md_doc
            if doc is None:
                # Toggled to formatted after the source was read: parse now.
                def work():
                    from odv import mdrender

                    return mdrender.build(tf.text)

                run_in_thread(work, lambda d: self._build_text_view("formatted", d), lambda e: None)
                return
            from odv.views.markdown import MarkdownView

            view = MarkdownView(self.path, self._open_path)
            self._add_view(mode, view)
            self._set_outline(doc.outline)
            view.set_doc(doc, self._first_content, self._loaded)
        else:
            from odv.views.source import SourceView

            view = SourceView(self.path, self.kind.lang, self.kind.wrap)
            self._add_view(mode, view)
            view.set_text(tf.text, self._first_content, lambda: (self._loaded(), self._view_ready("source")))

    def _add_view(self, mode, view):
        self.views[mode] = view
        if hasattr(view, "get_vadjustment"):
            view.get_vadjustment().connect("value-changed", self._on_scrolled)
        self.stack.add_child(view)
        if self.mode == mode:
            self.stack.set_visible_child(view)
        self._update_banner()

    def _build_table(self):
        from odv.views.table import TableView

        view = TableView(self.path, self.kind.delimiter)
        big = view.index is not None and view.index.size >= BIG_FILE
        view.connect("first-rows", lambda v: self._first_content())
        view.connect("progress", self._on_table_progress, big)
        view.connect("complete", self._on_table_complete)
        view.connect("failed", lambda v, msg: self._status(_("Could not open this file"), msg, "dialog-warning-symbolic"))
        self._add_view("formatted", view)

    def _on_table_progress(self, view, fraction, big):
        if fraction < 1.0 and view.index:
            self.progress.set_visible(True)
            self.progress.set_fraction(fraction)
            if big and self.mode == "formatted":
                self.banner.set_title(_(
                    "Large file ({size}): rows appear as they load ({percent}%). Scrolling and selecting "
                    "already work.").format(size=human_size(view.index.size), percent=f"{fraction * 100:.0f}"))
                self.banner.set_button_label(_("Stop loading"))
                self.banner.set_revealed(True)

    def _on_table_complete(self, view):
        self.progress.set_visible(False)
        self._update_banner()
        self._first_content()
        self._loaded()

    def _update_banner(self):
        view = self.views.get(self.mode)
        tf = self._text
        if self.mode == "source" and tf is not None and tf.truncated:
            self.banner.set_title(_("Showing the first {shown} of {total}.").format(
                shown=human_size(MAX_TEXT_BYTES), total=human_size(tf.size)))
            self.banner.set_button_label(None)
            self.banner.set_revealed(True)
        elif self.mode == "formatted" and view is not None and getattr(view, "index", None) is not None \
                and not view.index.complete and view.index.size >= BIG_FILE:
            self.banner.set_revealed(True)
        else:
            self.banner.set_revealed(False)

    def _on_banner_button(self, _banner):
        view = self.views.get("formatted")
        if view is not None and hasattr(view, "stop"):
            view.stop()
            self.banner.set_revealed(False)

    # -- find -----------------------------------------------------------------
    def start_search(self) -> None:
        self.search_bar.set_search_mode(True)
        self.search_entry.grab_focus()

    def _text_view(self):
        view = self.views.get(self.mode)
        return getattr(view, "view", None) if view is not None and hasattr(view, "buffer") else None

    def _set_found(self, found: bool) -> None:
        (self.search_entry.remove_css_class if found else self.search_entry.add_css_class)("error")

    def find(self, forward: bool, from_selection_start: bool = False) -> bool:
        needle = self.search_entry.get_text()
        view = self.views.get(self.mode)
        if view is not None and hasattr(view, "columnview"):
            if not needle:
                self._set_found(True)
                return False
            view.find(needle, forward, self._set_found)
            return True
        tv = self._text_view()
        if tv is None or not needle:
            self.search_entry.remove_css_class("error")
            return False
        buf = tv.get_buffer()
        flags = Gtk.TextSearchFlags.CASE_INSENSITIVE | Gtk.TextSearchFlags.TEXT_ONLY
        bounds = buf.get_selection_bounds()
        if forward:
            start = (bounds[0] if from_selection_start else bounds[1]) if bounds else buf.get_start_iter()
            match = start.forward_search(needle, flags, None) or buf.get_start_iter().forward_search(needle, flags, None)
        else:
            start = bounds[0] if bounds else buf.get_end_iter()
            match = start.backward_search(needle, flags, None) or buf.get_end_iter().backward_search(needle, flags, None)
        if match:
            buf.select_range(match[0], match[1])
            tv.scroll_to_iter(match[0], 0.15, False, 0.0, 0.0)
            self.search_entry.remove_css_class("error")
            return True
        self.search_entry.add_css_class("error")
        return False

    # -- lifecycle ------------------------------------------------------------
    def reload(self, keep_scroll: bool = False) -> "DocumentPage":
        return DocumentPage(self.path, self._open_path, self.mode,
                            scroll=self.scroll_position() if keep_scroll else None)

    def shutdown(self) -> None:
        self.closed = True
        if self._position_timer:
            GLib.source_remove(self._position_timer)
            self._position_timer = 0
        if self._monitor is not None:
            self._monitor.cancel()
            self._monitor = None
        if self._change_timer:
            GLib.source_remove(self._change_timer)
            self._change_timer = 0
        for view in self.views.values():
            if hasattr(view, "shutdown"):
                view.shutdown()
