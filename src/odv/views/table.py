"""The table view for CSV/TSV: a lazy Gtk.ColumnView over odv.csvindex.

Rows appear while the index is still being built in a worker thread, and a
row is parsed only when it scrolls into view.
"""

import csv
import io
import threading

from gi.repository import Gdk, Gio, GLib, GObject, Gtk, Pango

from odv import comfort, settings
from odv.csvindex import CsvIndex, detect_numeric

MAX_COLUMNS = 256


class RowItem(GObject.Object):
    __gtype_name__ = "OdvRowItem"

    def __init__(self, index: int):
        super().__init__()
        self.index = index


class RowModel(GObject.Object, Gio.ListModel):
    """Data rows 1..n of the index (row 0 is the header)."""

    __gtype_name__ = "OdvRowModel"

    def __init__(self):
        super().__init__()
        self.n = 0

    def do_get_item_type(self):
        return RowItem.__gtype__

    def do_get_n_items(self):
        return self.n

    def do_get_item(self, position):
        if position >= self.n:
            return None
        return RowItem(position + 1)

    def grow(self, n: int) -> None:
        if n > self.n:
            old = self.n
            self.n = n
            self.items_changed(old, 0, n - old)


class TableView(Gtk.ScrolledWindow):
    __gsignals__ = {
        "progress": (GObject.SignalFlags.RUN_FIRST, None, (float,)),
        "first-rows": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "complete": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "failed": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
    }

    def __init__(self, path: str, delimiter: str | None):
        super().__init__(vexpand=True, hexpand=True)
        self.path = path
        self.index = None
        self._first = False
        self.model = RowModel()
        self.selection = Gtk.MultiSelection(model=self.model)
        self.columnview = Gtk.ColumnView(model=self.selection, show_row_separators=False,
                                         show_column_separators=True, reorderable=False)
        self.columnview.add_css_class("data-table")
        comfort.apply_zoom(self.columnview, "table")
        settings.on_change(self._on_setting)
        self.set_child(self.columnview)
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.columnview.add_controller(keys)
        try:
            self.index = CsvIndex(path, delimiter)
        except OSError as e:
            message = e.strerror or str(e)  # `e` is unbound once the except block ends
            GLib.idle_add(lambda: (self.emit("failed", message), False)[1])
            return
        self._build_columns()
        threading.Thread(target=self.index.build, daemon=True).start()
        GLib.timeout_add(80, self._poll)

    @property
    def total_rows(self) -> int:
        return self.model.n

    def _build_columns(self):
        # Row 0 is readable before the index exists: its end is found here.
        head = bytes(self.index._mm[:1 << 20])
        first = head.split(b"\n", 1)[0]
        text = first.decode("utf-8", "replace").lstrip("\ufeff").rstrip("\r")
        header = next(csv.reader(io.StringIO(text), self.index.dialect), []) if text else []
        self._numeric: list[bool] = []
        self._add_column("#", -1, numeric=True, width=72)
        for j, name in enumerate(header[:MAX_COLUMNS]):
            self._add_column(name or f"Column {j + 1}", j, numeric=False)

    def _add_column(self, title, j, numeric, width=150):
        factory = Gtk.SignalListItemFactory()
        factory.connect("setup", self._setup_cell, numeric or j < 0)
        factory.connect("bind", self._bind_cell, j)
        col = Gtk.ColumnViewColumn(title=title, factory=factory, resizable=True, fixed_width=width)
        self.columnview.append_column(col)

    def _setup_cell(self, _factory, item, right):
        ins = Gtk.Inscription(xalign=1.0 if right else 0.0, nat_chars=12, min_chars=4,
                              text_overflow=Gtk.InscriptionOverflow.ELLIPSIZE_END)
        if right:
            ins.add_css_class("row-number")
        item.set_child(ins)

    def _bind_cell(self, _factory, item, j):
        idx = item.get_item().index
        if j < 0:
            item.get_child().set_text(str(idx))
            return
        cells = self.index.row(idx)
        text = cells[j] if j < len(cells) else ""
        ins = item.get_child()
        ins.set_xalign(1.0 if j < len(self._numeric) and self._numeric[j] else 0.0)
        ins.set_text(text.replace("\n", " ⏎ ") if "\n" in text else text)

    def _poll(self):
        if self.index is None or self.index.cancelled:
            return False
        rows = self.index.rows_indexed - 1
        first = not self._first and (rows > 0 or self.index.complete)
        if first:
            # Before the first rows are bound, so numbers come out right-aligned.
            self._numeric = detect_numeric([self.index.row(i) for i in range(1, min(rows, 200) + 1)])
        if rows > self.model.n:
            self.model.grow(rows)
        if first:
            self._first = True
            self.emit("first-rows")
        self.emit("progress", self.index.progress)
        if self.index.complete:
            self.emit("complete")
            return False
        return True

    def stop(self) -> None:
        if self.index and not self.index.complete:
            self.index.cancelled = True
            self.emit("complete")

    def _on_key(self, _ctrl, keyval, _code, state):
        if state & Gdk.ModifierType.CONTROL_MASK and keyval in (Gdk.KEY_c, Gdk.KEY_C):
            self.copy_selection()
            return True
        return False

    def copy_selection(self) -> None:
        bitset = self.selection.get_selection()
        n = bitset.get_size()
        if n == 0:
            return
        lines = []
        for k in range(min(n, 100000)):
            lines.append("\t".join(self.index.row(bitset.get_nth(k) + 1)))
        self.get_clipboard().set("\n".join(lines))

    def find(self, needle: str, forward: bool, on_result) -> None:
        """Select the next row containing ``needle``; ``on_result(found)``."""
        if self.index is None or not needle:
            on_result(False)
            return
        selected = self.selection.get_selection()
        current = selected.get_nth(0) + 1 if selected.get_size() else 0
        index = self.index

        def work():
            row = index.find(needle, current, forward)
            GLib.idle_add(lambda: (self._show_row(row, on_result), False)[1])

        threading.Thread(target=work, daemon=True).start()

    def _show_row(self, row, on_result):
        if row is None or row < 1 or row > self.model.n:
            on_result(False)
            return
        position = row - 1
        self.selection.select_item(position, True)
        self.columnview.scroll_to(position, None, Gtk.ListScrollFlags.FOCUS | Gtk.ListScrollFlags.SELECT, None)
        on_result(True)

    def _on_setting(self, key, _value):
        if key == "zoom":
            comfort.apply_zoom(self.columnview, "table")

    def shutdown(self):
        settings.remove_listener(self._on_setting)
        if self.index:
            self.index.cancelled = True
            GLib.timeout_add(500, lambda: (self.index.close(), False)[1])
