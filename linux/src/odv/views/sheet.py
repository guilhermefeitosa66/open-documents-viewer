"""The spreadsheet view: sheet tabs, a lazy grid with the file's number
formats, styles and column widths, a bar with the current cell's value and
formula, find, and copy as TSV. Rows stream in while a sheet is read."""

import threading

from gi.repository import Gdk, Gio, GLib, GObject, Gtk, Pango

from odv import comfort, settings
from odv.formats import sheet as model
from odv.i18n import _


class RowItem(GObject.Object):
    __gtype_name__ = "OdvSheetRow"

    def __init__(self, row: int):
        super().__init__()
        self.row = row


class RowModel(GObject.Object, Gio.ListModel):
    __gtype_name__ = "OdvSheetRows"

    def __init__(self):
        super().__init__()
        self.rows: list[int] = []  # visible sheet rows, in order

    def do_get_item_type(self):
        return RowItem.__gtype__

    def do_get_n_items(self):
        return len(self.rows)

    def do_get_item(self, position):
        return RowItem(self.rows[position]) if position < len(self.rows) else None

    def set_rows(self, rows: list[int]) -> None:
        old = len(self.rows)
        if rows[:old] == self.rows:
            self.rows = rows
            if len(rows) > old:
                self.items_changed(old, 0, len(rows) - old)
        else:
            self.rows = rows
            self.items_changed(0, old, len(rows))


class SheetView(Gtk.Box):
    __gsignals__ = {
        "loaded": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "first-rows": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "failed": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
    }

    def __init__(self, path: str, opener):
        """``opener(path)`` returns an object with ``workbook`` and
        ``read_sheet(index, on_rows)`` (XlsxReader and friends)."""
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        self.path = path
        self.reader = None
        self.workbook = None
        self.sheet_index = None
        self.sheet = None
        self.current = None  # (row, col)
        self._covered = set()
        self._first = False
        self._loading = set()
        self._cell_widgets = []

        bar = Gtk.Box(spacing=8, margin_start=8, margin_end=8, margin_top=4, margin_bottom=4)
        self.ref_label = Gtk.Label(width_chars=8, xalign=0.0)
        self.ref_label.add_css_class("monospace")
        self.ref_label.add_css_class("heading")
        self.value_label = Gtk.Label(xalign=0.0, hexpand=True, selectable=True,
                                     ellipsize=Pango.EllipsizeMode.END)
        self.value_label.add_css_class("monospace")
        bar.append(self.ref_label)
        bar.append(self.value_label)
        self.append(bar)
        self.append(Gtk.Separator())

        self.model = RowModel()
        self.selection = Gtk.MultiSelection(model=self.model)
        self.columnview = Gtk.ColumnView(model=self.selection, show_column_separators=True,
                                         show_row_separators=True, reorderable=False)
        self.columnview.add_css_class("data-table")
        self.columnview.add_css_class("sheet-grid")
        self.scroller = Gtk.ScrolledWindow(child=self.columnview, vexpand=True)
        self.append(self.scroller)
        self.tabs = Gtk.Box(css_classes=["linked"], margin_start=6, margin_end=6, margin_top=4, margin_bottom=4)
        tab_scroller = Gtk.ScrolledWindow(child=self.tabs, vscrollbar_policy=Gtk.PolicyType.NEVER,
                                          propagate_natural_height=True)
        self.append(Gtk.Separator())
        self.append(tab_scroller)
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.columnview.add_controller(keys)
        comfort.apply_zoom(self.columnview, "table")
        settings.on_change(self._on_setting)

        def work():
            try:
                reader = opener(path)
            except Exception as e:  # noqa: BLE001 - shown in the UI
                message = str(e)
                GLib.idle_add(lambda: (self.emit("failed", message), False)[1])
                return
            GLib.idle_add(lambda: (self._opened(reader), False)[1])

        threading.Thread(target=work, daemon=True).start()

    def get_vadjustment(self):
        return self.scroller.get_vadjustment()

    # -- workbook and sheets ----------------------------------------------------------
    def _opened(self, reader):
        self.reader = reader
        self.workbook = reader.workbook
        group = None
        first = None
        for i, sh in enumerate(self.workbook.sheets):
            if sh.hidden:
                continue
            button = Gtk.ToggleButton(label=sh.name, group=group)
            group = group or button
            button.connect("toggled", lambda b, i=i: b.get_active() and self.show_sheet(i))
            self.tabs.append(button)
            first = first if first is not None else (i, button)
        if first is None:
            self.emit("failed", _("This workbook has no visible sheets."))
            return
        first[1].set_active(True)

    def show_sheet(self, index: int) -> None:
        self.sheet_index = index
        self.sheet = self.workbook.sheets[index]
        self.current = None
        self._build_columns()
        self._refresh_rows()
        self._show_current()
        if not self.sheet.complete and index not in self._loading:
            self._loading.add(index)
            sheet_ref = self.sheet

            def on_rows(_sheet):
                GLib.idle_add(lambda: (self._rows_arrived(sheet_ref), False)[1])

            threading.Thread(target=lambda: self.reader.read_sheet(index, on_rows), daemon=True).start()
        elif self.sheet.complete:
            self._signal_loaded()

    def _rows_arrived(self, sheet_ref):
        if sheet_ref is not self.sheet:
            return
        if len(self.columnview.get_columns()) - 1 < self._visible_col_count():
            self._build_columns()
        self._refresh_rows()
        if not self._first and (sheet_ref.rows or sheet_ref.complete):
            self._first = True
            self.emit("first-rows")
        if sheet_ref.complete:
            self._signal_loaded()

    def _signal_loaded(self):
        if not self._first:
            self._first = True
            self.emit("first-rows")
        self.emit("loaded")

    def _visible_col_count(self):
        return len([c for c in range(self.sheet.max_col) if c not in self.sheet.hidden_cols])

    def _refresh_rows(self):
        sh = self.sheet
        rows = [r for r in range(sh.max_row) if r not in sh.hidden_rows]
        self.model.set_rows(rows)
        self._covered = {(r, c) for r1, c1, r2, c2 in sh.merges
                         for r in range(r1, r2 + 1) for c in range(c1, c2 + 1) if (r, c) != (r1, c1)}

    def _build_columns(self):
        for col in list(self.columnview.get_columns()):
            self.columnview.remove_column(col)
        self._add_column("", -1, 56)
        sh = self.sheet
        for c in range(max(sh.max_col, 1)):
            if c in sh.hidden_cols:
                continue
            width = sh.col_widths.get(c)
            px = int(width * 7 + 10) if width else 90
            self._add_column(model.col_name(c), c, max(24, min(px, 900)))

    def _add_column(self, title, col, width):
        factory = Gtk.SignalListItemFactory()
        factory.connect("setup", self._setup, col)
        factory.connect("bind", self._bind, col)
        column = Gtk.ColumnViewColumn(title=title, factory=factory, resizable=True, fixed_width=width)
        self.columnview.append_column(column)

    def _setup(self, _factory, item, col):
        ins = Gtk.Inscription(xalign=1.0 if col < 0 else 0.0, min_chars=1, nat_chars=8,
                              text_overflow=Gtk.InscriptionOverflow.ELLIPSIZE_END)
        if col < 0:
            ins.add_css_class("row-number")
        else:
            click = Gtk.GestureClick()
            click.connect("pressed", lambda g, n, x, y, ins=ins: self._clicked(ins))
            ins.add_controller(click)
        item.set_child(ins)

    def _bind(self, _factory, item, col):
        ins = item.get_child()
        row = item.get_item().row
        if col < 0:
            ins.set_text(str(row + 1))
            return
        ins._pos = (row, col)
        cell = self.sheet.rows.get(row, {}).get(col) if (row, col) not in self._covered else None
        (ins.add_css_class if self.current == (row, col) else ins.remove_css_class)("cell-current")
        if cell is None:
            ins.set_text("")
            return
        text = model.display(self.workbook, cell)
        style = self.workbook.styles[cell.style] if cell.style < len(self.workbook.styles) else model.Style()
        numeric = isinstance(cell.value, (int, float)) and not isinstance(cell.value, bool)
        default = "center" if isinstance(cell.value, bool) or cell.error else ("right" if numeric else "left")
        align = style.align or default
        ins.set_xalign({"right": 1.0, "center": 0.5, "centerContinuous": 0.5}.get(align, 0.0))
        attrs = []
        if style.bold:
            attrs.append('weight="bold"')
        if style.italic:
            attrs.append('style="italic"')
        if style.underline:
            attrs.append('underline="single"')
        if style.strike:
            attrs.append('strikethrough="true"')
        if style.color:
            attrs.append(f'foreground="#{style.color}"')
        if style.fill:
            attrs.append(f'background="#{style.fill}"')
        if attrs:
            ins.set_markup(f"<span {' '.join(attrs)}>{GLib.markup_escape_text(text)}</span>")
        else:
            ins.set_text(text)

    # -- current cell ---------------------------------------------------------------------
    def _clicked(self, ins):
        pos = getattr(ins, "_pos", None)
        if pos is not None:
            self.set_current(*pos)

    def set_current(self, row: int, col: int, scroll: bool = False) -> None:
        self.current = (row, col)
        self._show_current()
        position = self.model.rows.index(row) if row in self.model.rows else None
        if position is not None:
            self.selection.select_item(position, True)
            if scroll:
                self.columnview.scroll_to(position, None, Gtk.ListScrollFlags.FOCUS, None)
        # Rebind the visible rows so the highlight moves.
        n = len(self.model.rows)
        self.model.items_changed(0, n, n)
        if position is not None and scroll:
            self.columnview.scroll_to(position, None, Gtk.ListScrollFlags.FOCUS, None)

    def _show_current(self):
        if self.current is None:
            self.ref_label.set_label("")
            self.value_label.set_label("")
            return
        row, col = self.current
        self.ref_label.set_label(f"{model.col_name(col)}{row + 1}")
        cell = self.sheet.rows.get(row, {}).get(col)
        if cell is None:
            self.value_label.set_label("")
        elif cell.formula:
            self.value_label.set_label(f"{cell.formula}   →   {model.display(self.workbook, cell)}")
        else:
            self.value_label.set_label(model.display(self.workbook, cell))

    # -- find, copy ----------------------------------------------------------------------------
    def find(self, needle: str, forward: bool, on_result) -> None:
        if self.sheet is None or not needle:
            on_result(False)
            return
        needle = needle.casefold()
        cells = sorted((r, c) for r, row in self.sheet.rows.items() if r not in self.sheet.hidden_rows
                       for c, cell in row.items() if c not in self.sheet.hidden_cols
                       and needle in model.display(self.workbook, cell).casefold())
        if not cells:
            on_result(False)
            return
        here = self.current or ((-1, -1) if forward else (10**9, 10**9))
        if forward:
            target = next((p for p in cells if p > here), cells[0])
        else:
            target = next((p for p in reversed(cells) if p < here), cells[-1])
        self.set_current(*target, scroll=True)
        on_result(True)

    def _on_key(self, _ctrl, keyval, _code, state):
        if state & Gdk.ModifierType.CONTROL_MASK and keyval in (Gdk.KEY_c, Gdk.KEY_C):
            self.copy()
            return True
        return False

    def copy(self) -> str:
        bitset = self.selection.get_selection()
        if bitset.get_size() > 1 or self.current is None:
            lines = []
            cols = [c for c in range(self.sheet.max_col) if c not in self.sheet.hidden_cols]
            for k in range(min(bitset.get_size(), 100000)):
                r = self.model.rows[bitset.get_nth(k)]
                row = self.sheet.rows.get(r, {})
                lines.append("\t".join(model.display(self.workbook, row[c]) if c in row else "" for c in cols))
            text = "\n".join(lines)
        else:
            cell = self.sheet.rows.get(self.current[0], {}).get(self.current[1])
            text = model.display(self.workbook, cell) if cell else ""
        self.get_clipboard().set(text)
        return text

    def _on_setting(self, key, _value):
        if key == "zoom":
            comfort.apply_zoom(self.columnview, "table")

    def shutdown(self):
        settings.remove_listener(self._on_setting)
