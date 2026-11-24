"""Find in folder: the sidebar panel. The search runs in a thread
(odv.foldersearch); results arrive file by file."""

import os
import threading

from gi.repository import GLib, Gtk, Pango

from odv.i18n import _

MAX_ROWS = 2000


class SearchPanel(Gtk.Box):
    def __init__(self, open_at):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6, vexpand=True)
        self._open_at = open_at
        self.root = None
        self._generation = 0
        self._timer = 0
        self._rows = 0
        self.entry = Gtk.SearchEntry(placeholder_text=_("Search in folder"), margin_start=8, margin_end=8,
                                     margin_top=6)
        self.entry.connect("search-changed", self._on_changed)
        self.entry.connect("activate", lambda e: self._start())
        self.append(self.entry)
        self.status = Gtk.Label(xalign=0.0, margin_start=12, margin_end=12, wrap=True)
        self.status.add_css_class("caption")
        self.status.add_css_class("welcome-hint")
        self.append(self.status)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.list.add_css_class("navigation-sidebar")
        self.list.connect("row-activated", self._on_row)
        self.append(Gtk.ScrolledWindow(child=self.list, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER))

    def set_root(self, root: str) -> None:
        self.root = root
        self._cancel()
        self.list.remove_all()
        self.status.set_label("")
        if self.entry.get_text():
            self._start()

    def focus(self) -> None:
        self.entry.grab_focus()

    def _on_changed(self, _entry):
        if self._timer:
            GLib.source_remove(self._timer)
        self._timer = GLib.timeout_add(250, self._start)

    def _cancel(self):
        self._generation += 1

    def _start(self):
        self._timer = 0
        self._cancel()
        self.list.remove_all()
        self._rows = 0
        needle = self.entry.get_text()
        if not self.root or len(needle) < 2:
            self.status.set_label("" if not needle else _("Type at least 2 characters."))
            return False
        generation = self._generation
        self.status.set_label(_("Searching…"))
        state = {"files": 0, "hits": 0}

        def cancelled():
            return generation != self._generation

        def on_file(fh):
            GLib.idle_add(lambda: (self._add(fh, generation, state), False)[1])

        def work():
            from odv import foldersearch

            try:
                _results, truncated = foldersearch.search(self.root, needle, on_file, cancelled)
            except foldersearch.Cancelled:
                return
            GLib.idle_add(lambda: (self._done(generation, state, truncated), False)[1])

        threading.Thread(target=work, daemon=True).start()
        return False

    def _add(self, fh, generation, state):
        if generation != self._generation:
            return
        state["files"] += 1
        state["hits"] += len(fh.hits)
        if self._rows >= MAX_ROWS:
            return
        rel = os.path.relpath(fh.path, self.root)
        header = Gtk.ListBoxRow(activatable=False, selectable=False)
        box = Gtk.Box(spacing=6, margin_top=6)
        name = Gtk.Label(label=rel, xalign=0.0, hexpand=True, ellipsize=Pango.EllipsizeMode.START)
        name.add_css_class("heading")
        count = Gtk.Label(label=str(len(fh.hits)))
        count.add_css_class("dim-label")
        box.append(name)
        box.append(count)
        header.set_child(box)
        header.set_tooltip_text(fh.path)
        self.list.append(header)
        needle_len = len(self.entry.get_text())
        for hit in fh.hits:
            row = Gtk.ListBoxRow()
            line = Gtk.Box(spacing=8)
            num = Gtk.Label(label=str(hit.line), width_chars=4, xalign=1.0)
            num.add_css_class("dim-label")
            num.add_css_class("monospace")
            t, c = hit.text, hit.column
            markup = (GLib.markup_escape_text(t[:c]) + "<b>" + GLib.markup_escape_text(t[c:c + needle_len])
                      + "</b>" + GLib.markup_escape_text(t[c + needle_len:]))
            text = Gtk.Label(xalign=0.0, hexpand=True, ellipsize=Pango.EllipsizeMode.END, use_markup=True)
            text.set_markup(markup)
            text.add_css_class("monospace")
            line.append(num)
            line.append(text)
            row.set_child(line)
            row._target = (fh.path, hit.line)
            self.list.append(row)
            self._rows += 1
            if self._rows >= MAX_ROWS:
                break
        self.status.set_label(_("{hits} matches in {files} files…").format(hits=state["hits"], files=state["files"]))

    def _done(self, generation, state, truncated):
        if generation != self._generation:
            return
        if state["files"] == 0:
            self.status.set_label(_("No matches."))
        elif truncated or self._rows >= MAX_ROWS:
            self.status.set_label(_("{hits} matches in {files} files (showing the first ones).").format(
                hits=state["hits"], files=state["files"]))
        else:
            self.status.set_label(_("{hits} matches in {files} files.").format(
                hits=state["hits"], files=state["files"]))

    def _on_row(self, _list, row):
        target = getattr(row, "_target", None)
        if target:
            self._open_at(target[0], target[1], self.entry.get_text())
