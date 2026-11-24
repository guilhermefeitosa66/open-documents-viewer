"""The source view: raw text with line numbers and syntax highlighting.

GtkSourceView is imported on first use, not at start-up. Without it (a broken
install) the view falls back to a plain monospace Gtk.TextView.
"""

import os

from gi.repository import Gio, GLib, Gtk

from odv import comfort, settings, theme
from odv.textio import HIGHLIGHT_LIMIT, split_chunks

_gs = None


def gtksource():
    global _gs
    if _gs is None:
        try:
            import gi

            gi.require_version("GtkSource", "5")
            from gi.repository import GtkSource

            GtkSource.init()
            GtkSource.StyleSchemeManager.get_default().append_search_path(
                os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "styles"))
            _gs = GtkSource
        except (ImportError, ValueError):
            _gs = False
    return _gs


def feed_buffer(buffer, chunks, on_first, on_done, budget_ms: float = 8.0) -> callable:
    """Insert ``chunks`` at the end of ``buffer``: the first one now, the
    rest from idle callbacks under a time budget so the UI keeps drawing.
    Returns a function that cancels the feed."""
    state = {"i": 0, "cancelled": False}

    def insert_one():
        buffer.insert(buffer.get_end_iter(), chunks[state["i"]])
        state["i"] += 1

    def step():
        if state["cancelled"]:
            return False
        deadline = GLib.get_monotonic_time() + budget_ms * 1000
        while state["i"] < len(chunks) and GLib.get_monotonic_time() < deadline:
            insert_one()
        if state["i"] >= len(chunks):
            on_done()
            return False
        return True

    if chunks:
        insert_one()
    on_first()
    if state["i"] >= len(chunks):
        on_done()
    else:
        GLib.idle_add(step, priority=GLib.PRIORITY_LOW)

    def cancel():
        state["cancelled"] = True

    return cancel


class SourceView(Gtk.Box):
    """The text, in a scroller, with GtkSourceView's minimap beside it when on."""

    def __init__(self, path: str, lang_hint: str | None, wrap: bool):
        super().__init__(vexpand=True, hexpand=True)
        self.path = path
        self._cancel = None
        self.scroller = Gtk.ScrolledWindow(vexpand=True, hexpand=True)
        self.minimap = None
        gs = gtksource()
        if gs:
            self.buffer = gs.Buffer()
            self.buffer.set_highlight_matching_brackets(False)
            self.view = gs.View(buffer=self.buffer, show_line_numbers=True)
            self._language(gs, lang_hint)
            self._on_theme(theme.is_dark())
            theme.on_change(self._on_theme)
        else:
            self.buffer = Gtk.TextBuffer()
            self.view = Gtk.TextView(buffer=self.buffer)
        self.buffer.set_enable_undo(False)
        self.view.set_editable(False)
        self.view.set_cursor_visible(False)
        self.view.set_monospace(True)
        self.view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR if wrap else Gtk.WrapMode.NONE)
        self.view.set_top_margin(10)
        self.view.set_bottom_margin(24)
        self.view.set_left_margin(10)
        self.view.set_right_margin(16)
        self.scroller.set_child(self.view)
        self.append(self.scroller)
        self._apply_settings()
        settings.on_change(self._on_setting)

    def get_vadjustment(self):
        return self.scroller.get_vadjustment()

    def set_wrap(self, wrap: bool) -> None:
        self.view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR if wrap else Gtk.WrapMode.NONE)

    def get_wrap(self) -> bool:
        return self.view.get_wrap_mode() != Gtk.WrapMode.NONE

    def _on_setting(self, key, _value):
        if key in ("zoom", "line_numbers", "minimap"):
            self._apply_settings()

    def _apply_settings(self):
        comfort.apply_zoom(self.view, "code")
        gs = gtksource()
        if not gs:
            return
        self.view.set_show_line_numbers(settings.get("line_numbers"))
        if settings.get("minimap") and self.minimap is None:
            self.minimap = gs.Map(view=self.view, width_request=96)
            self.append(self.minimap)
        elif not settings.get("minimap") and self.minimap is not None:
            self.remove(self.minimap)
            self.minimap = None

    def _language(self, gs, hint):
        lm = gs.LanguageManager.get_default()
        lang = lm.get_language(hint) if hint else None
        if lang is None:
            ctype, _ = Gio.content_type_guess(os.path.basename(self.path), None)
            lang = lm.guess_language(os.path.basename(self.path), ctype)
        if lang is not None:
            self.buffer.set_language(lang)

    def _on_theme(self, dark: bool):
        gs = gtksource()
        sm = gs.StyleSchemeManager.get_default()
        scheme = (sm.get_scheme(theme.current().get("scheme", "odv-light"))
                  or sm.get_scheme("Adwaita-dark" if dark else "Adwaita") or sm.get_scheme("classic"))
        if scheme:
            self.buffer.set_style_scheme(scheme)

    def set_text(self, text: str, on_first, on_done) -> None:
        if gtksource():
            self.buffer.set_highlight_syntax(len(text) <= HIGHLIGHT_LIMIT)
        self._cancel = feed_buffer(self.buffer, split_chunks(text), on_first, on_done)

    def shutdown(self) -> None:
        if self._cancel:
            self._cancel()
        theme.remove_listener(self._on_theme)
        settings.remove_listener(self._on_setting)
