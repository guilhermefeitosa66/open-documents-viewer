"""The outline of the current document, in the sidebar."""

from gi.repository import Gtk, Pango

from odv.i18n import _


class OutlinePanel(Gtk.Box):
    def __init__(self):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True)
        self.doc = None
        self._handlers = []
        self._syncing = False
        self.empty = Gtk.Label(label=_("No outline for this document."), wrap=True, margin_top=24,
                               margin_start=16, margin_end=16, justify=Gtk.Justification.CENTER)
        self.empty.add_css_class("welcome-hint")
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.list.add_css_class("navigation-sidebar")
        self.list.connect("row-activated", self._on_row)
        self.scroller = Gtk.ScrolledWindow(child=self.list, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.append(self.empty)
        self.append(self.scroller)
        self.set_doc(None)

    def set_doc(self, doc) -> None:
        for obj, handler in self._handlers:
            obj.disconnect(handler)
        self._handlers = []
        self.doc = doc
        if doc is not None:
            self._handlers = [(doc, doc.connect("outline-changed", lambda d: self._rebuild())),
                              (doc, doc.connect("position-changed", lambda d: self._sync())),
                              (doc, doc.connect("mode-changed", lambda d: self._sync()))]
        self._rebuild()

    def _rebuild(self) -> None:
        self.list.remove_all()
        entries = self.doc.outline() if self.doc is not None else []
        top = min((e.level for e in entries), default=1)
        for entry in entries:
            label = Gtk.Label(label=entry.title, xalign=0.0, ellipsize=Pango.EllipsizeMode.END,
                              margin_start=12 * (entry.level - top))
            if entry.level == top:
                label.add_css_class("heading")
            row = Gtk.ListBoxRow(child=label, tooltip_text=entry.title)
            row._entry = entry
            self.list.append(row)
        self.empty.set_visible(not entries)
        self.scroller.set_visible(bool(entries))
        self._sync()

    def _sync(self) -> None:
        if self.doc is None:
            return
        index = self.doc.outline_position()
        row = self.list.get_row_at_index(index) if index >= 0 else None
        self._syncing = True
        if row is not None:
            self.list.select_row(row)
        else:
            self.list.unselect_all()
        self._syncing = False

    def _on_row(self, _list, row):
        if self.doc is not None and not self._syncing:
            self.doc.jump_to(row._entry)
