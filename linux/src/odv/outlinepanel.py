"""The outline of the current document, in the sidebar.

A Gtk.ListView: only the visible rows are built, so a document with
thousands of headings, slides or bookmarks costs a few milliseconds here
(a Gtk.ListBox with a widget per entry took over a second for 4,000)."""

from gi.repository import Gio, GObject, Gtk, Pango

from odv.i18n import _


class OutlineItem(GObject.Object):
    __gtype_name__ = "OdvOutlineItem"

    def __init__(self, entry, depth: int):
        super().__init__()
        self.entry = entry
        self.depth = depth


class OutlinePanel(Gtk.Box):
    def __init__(self):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True)
        self.doc = None
        self._handlers = []
        self._syncing = False
        self._shown = None  # the outline list currently in the model
        self.empty = Gtk.Label(label=_("No outline for this document."), wrap=True, margin_top=24,
                               margin_start=16, margin_end=16, justify=Gtk.Justification.CENTER)
        self.empty.add_css_class("welcome-hint")
        self.model = Gio.ListStore(item_type=OutlineItem)
        self.selection = Gtk.SingleSelection(model=self.model, autoselect=False, can_unselect=True)
        factory = Gtk.SignalListItemFactory()
        factory.connect("setup", self._setup_row)
        factory.connect("bind", self._bind_row)
        self.list = Gtk.ListView(model=self.selection, factory=factory, single_click_activate=True)
        self.list.add_css_class("navigation-sidebar")
        self.list.connect("activate", lambda _lv, position: self.activate(position))
        self.scroller = Gtk.ScrolledWindow(child=self.list, vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.append(self.empty)
        self.append(self.scroller)
        self.set_doc(None)

    @staticmethod
    def _setup_row(_factory, item):
        item.set_child(Gtk.Label(xalign=0.0, ellipsize=Pango.EllipsizeMode.END))

    @staticmethod
    def _bind_row(_factory, item):
        data = item.get_item()
        label = item.get_child()
        label.set_label(data.entry.title)
        label.set_tooltip_text(data.entry.title)
        label.set_margin_start(12 * data.depth)
        if data.depth == 0:
            label.add_css_class("heading")
        else:
            label.remove_css_class("heading")

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
        entries = self.doc.outline() if self.doc is not None else []
        if entries is not self._shown:  # set_doc and outline-changed often bring the same list
            self._shown = entries
            top = min((e.level for e in entries), default=1)
            self.model.splice(0, self.model.get_n_items(), [OutlineItem(e, e.level - top) for e in entries])
        self.empty.set_visible(not entries)
        self.scroller.set_visible(bool(entries))
        self._sync()

    def rows(self) -> list[str]:
        return [self.model.get_item(i).entry.title for i in range(self.model.get_n_items())]

    def selected(self) -> int:
        index = self.selection.get_selected()
        return -1 if index == Gtk.INVALID_LIST_POSITION else index

    def _sync(self) -> None:
        if self.doc is None:
            return
        index = self.doc.outline_position()
        self._syncing = True
        if 0 <= index < self.model.get_n_items():
            self.selection.set_selected(index)
        else:
            self.selection.unselect_all()
        self._syncing = False

    def activate(self, position: int) -> None:
        if self.doc is not None and not self._syncing and 0 <= position < self.model.get_n_items():
            self.doc.jump_to(self.model.get_item(position).entry)
