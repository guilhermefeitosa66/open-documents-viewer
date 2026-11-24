"""Quick open (Ctrl+P) and the command palette (Ctrl+Shift+P): one dialog,
a search entry over a fuzzy-ranked list. Up/Down move, Enter runs, Esc closes."""

import os
import threading

from gi.repository import Adw, Gdk, GLib, Gtk, Pango

from odv import fuzzy
from odv.i18n import _

LIMIT = 60


class Item:
    __slots__ = ("label", "detail", "key", "run")

    def __init__(self, label: str, detail: str, run, key: str | None = None):
        self.label = label
        self.detail = detail
        self.run = run
        self.key = key or label


class PaletteDialog(Adw.Dialog):
    def __init__(self, placeholder: str, items: list[Item] | None = None, loader=None):
        super().__init__(content_width=640, content_height=460, title=placeholder)
        self._items = items or []
        self._timer = 0
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=12, margin_bottom=12,
                      margin_start=12, margin_end=12)
        self.entry = Gtk.SearchEntry(placeholder_text=placeholder, hexpand=True)
        self.entry.connect("search-changed", self._queue)
        self.entry.connect("activate", lambda e: self._run_selected())
        self.entry.connect("stop-search", lambda e: self.close())
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.entry.add_controller(keys)
        box.append(self.entry)
        self.status = Gtk.Label(xalign=0.0)
        self.status.add_css_class("caption")
        self.status.add_css_class("welcome-hint")
        box.append(self.status)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.BROWSE)
        self.list.add_css_class("navigation-sidebar")
        self.list.connect("row-activated", lambda lb, row: self._run(row))
        box.append(Gtk.ScrolledWindow(child=self.list, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER))
        self.set_child(box)
        self.set_focus(self.entry)
        if loader is not None:
            self.status.set_label(_("Listing files…"))

            def work():
                items = loader()
                GLib.idle_add(lambda: (self._set_items(items), False)[1])

            threading.Thread(target=work, daemon=True).start()
        else:
            self._refresh()

    def _set_items(self, items):
        self._items = items
        self._refresh()

    def _queue(self, _entry):
        if self._timer:
            GLib.source_remove(self._timer)
        self._timer = GLib.timeout_add(60, self._refresh)

    def _refresh(self):
        self._timer = 0
        query = self.entry.get_text()
        shown = fuzzy.rank(query, self._items, key=lambda i: i.key, limit=LIMIT) if query else self._items[:LIMIT]
        self.list.remove_all()
        for item in shown:
            row = Gtk.ListBoxRow()
            line = Gtk.Box(spacing=10, margin_top=4, margin_bottom=4)
            label = Gtk.Label(label=item.label, xalign=0.0, ellipsize=Pango.EllipsizeMode.END)
            detail = Gtk.Label(label=item.detail, xalign=0.0, hexpand=True, ellipsize=Pango.EllipsizeMode.START)
            detail.add_css_class("dim-label")
            detail.add_css_class("caption")
            line.append(label)
            line.append(detail)
            row.set_child(line)
            row._item = item
            self.list.append(row)
        first = self.list.get_row_at_index(0)
        if first:
            self.list.select_row(first)
        n = len(self._items)
        self.status.set_label(_("No matches.") if query and not shown else
                              (_("{n} items").format(n=n) if n else ""))
        return False

    def _on_key(self, _ctrl, keyval, _code, _state):
        if keyval in (Gdk.KEY_Down, Gdk.KEY_Up):
            row = self.list.get_selected_row()
            index = (row.get_index() if row else -1) + (1 if keyval == Gdk.KEY_Down else -1)
            target = self.list.get_row_at_index(max(0, index))
            if target:
                self.list.select_row(target)
                target.grab_focus()
                self.entry.grab_focus_without_selecting()
            return True
        return False

    def _run_selected(self):
        row = self.list.get_selected_row()
        if row:
            self._run(row)

    def _run(self, row):
        item = getattr(row, "_item", None)
        if item is None:
            return
        self.close()
        GLib.idle_add(lambda: (item.run(), False)[1])


def file_items(root: str, open_path) -> list[Item]:
    from odv import foldersearch

    items = []
    for path in foldersearch.list_files(root):
        rel = os.path.relpath(path, root)
        items.append(Item(os.path.basename(path), os.path.dirname(rel), lambda p=path: open_path(p), key=rel))
    return items
