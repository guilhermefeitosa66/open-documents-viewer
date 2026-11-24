"""The tree view for JSON, NDJSON, YAML and XML: collapsible nodes, the
selected node's path (copyable), find. Children are made lazily, so huge
arrays expand instantly."""

import threading

from gi.repository import Gdk, Gio, GLib, GObject, Gtk, Pango

from odv import comfort, settings
from odv.formats import treedata
from odv.i18n import _


class Node(GObject.Object):
    __gtype_name__ = "OdvTreeNode"

    def __init__(self, key, value, keys):
        super().__init__()
        self.key = key
        self.value = value
        self.keys = keys


class ChildModel(GObject.Object, Gio.ListModel):
    """The children of one value, made into Nodes only when asked for."""

    __gtype_name__ = "OdvTreeChildren"

    def __init__(self, value, keys):
        super().__init__()
        self._items = treedata.children(value)
        self._keys = keys
        self._cache = {}

    def do_get_item_type(self):
        return Node.__gtype__

    def do_get_n_items(self):
        return len(self._items)

    def do_get_item(self, position):
        if position >= len(self._items):
            return None
        node = self._cache.get(position)
        if node is None:
            key, value = self._items[position]
            node = self._cache[position] = Node(key, value, self._keys + [key])
        return node


class TreeView(Gtk.Box):
    __gsignals__ = {
        "loaded": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "failed": (GObject.SignalFlags.RUN_FIRST, None, (str, int)),
    }

    def __init__(self, kind: str):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        self.kind = kind
        self.data = None
        self._matches: list[list] = []
        self._match_needle = None
        factory = Gtk.SignalListItemFactory()
        factory.connect("setup", self._setup)
        factory.connect("bind", self._bind)
        self.listview = Gtk.ListView(factory=factory)
        self.listview.add_css_class("data-tree")
        self.scroller = Gtk.ScrolledWindow(child=self.listview, vexpand=True)
        self.append(self.scroller)
        bar = Gtk.Box(spacing=6, margin_start=10, margin_end=6, margin_top=4, margin_bottom=4)
        self.path_label = Gtk.Label(xalign=0.0, hexpand=True, selectable=True,
                                    ellipsize=Pango.EllipsizeMode.MIDDLE)
        self.path_label.add_css_class("monospace")
        self.path_label.add_css_class("caption")
        copy = Gtk.Button(icon_name="edit-copy-symbolic", tooltip_text=_("Copy the node path (Ctrl+C)"))
        copy.add_css_class("flat")
        copy.connect("clicked", lambda b: self.copy_path())
        bar.append(self.path_label)
        bar.append(copy)
        self.append(Gtk.Separator())
        self.append(bar)
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.listview.add_controller(keys)
        comfort.apply_zoom(self.listview, "code")
        settings.on_change(self._on_setting)

    def get_vadjustment(self):
        return self.scroller.get_vadjustment()

    # -- loading ----------------------------------------------------------------
    def load(self, text: str) -> None:
        def work():
            try:
                data = treedata.parse(self.kind, text)
            except treedata.ParseError as e:
                message, line = str(e), e.line or 0
                GLib.idle_add(lambda: (self.emit("failed", message, line), False)[1])
                return
            GLib.idle_add(lambda: (self._show(data), False)[1])

        threading.Thread(target=work, daemon=True).start()

    def _show(self, data):
        self.data = data
        root = ChildModel(data, []) if treedata.is_container(data) else ChildModel([data], [])
        self.tree = Gtk.TreeListModel.new(root, False, False, self._children)
        self.selection = Gtk.SingleSelection(model=self.tree, autoselect=False, can_unselect=True)
        self.selection.connect("notify::selected-item", self._on_selected)
        self.listview.set_model(self.selection)
        # Open the first level of a small document.
        if self.tree.get_n_items() <= 50:
            for i in reversed(range(self.tree.get_n_items())):
                row = self.tree.get_row(i)
                if row is not None and row.is_expandable() and len(treedata.children(row.get_item().value)) <= 50:
                    row.set_expanded(True)
        self.path_label.set_label(treedata.path_string([], self.kind))
        self.emit("loaded")

    def _children(self, node):
        return ChildModel(node.value, node.keys) if treedata.is_container(node.value) else None

    # -- rows -------------------------------------------------------------------
    def _setup(self, _factory, item):
        expander = Gtk.TreeExpander()
        box = Gtk.Box(spacing=8)
        key = Gtk.Label(xalign=0.0)
        key.add_css_class("tree-key")
        value = Gtk.Label(xalign=0.0, hexpand=True, ellipsize=Pango.EllipsizeMode.END)
        box.append(key)
        box.append(value)
        expander.set_child(box)
        item.set_child(expander)
        item._parts = (expander, key, value)

    def _bind(self, _factory, item):
        expander, key, value = item._parts
        row = item.get_item()
        expander.set_list_row(row)
        node = row.get_item()
        k = node.key
        key.set_label(f"[{k}]" if isinstance(k, int) else str(k))
        value.set_label(treedata.preview(node.value))
        value.set_css_classes(["tree-value", "tree-" + treedata.type_class(node.value)])

    def _on_selected(self, *_args):
        row = self.selection.get_selected_item()
        keys = row.get_item().keys if row is not None else []
        self.path_label.set_label(treedata.path_string(keys, self.kind))

    def copy_path(self) -> None:
        self.get_clipboard().set(self.path_label.get_label())

    def _on_key(self, _ctrl, keyval, _code, state):
        if state & Gdk.ModifierType.CONTROL_MASK and keyval in (Gdk.KEY_c, Gdk.KEY_C):
            self.copy_path()
            return True
        return False

    def _on_setting(self, key, _value):
        if key == "zoom":
            comfort.apply_zoom(self.listview, "code")

    # -- find -------------------------------------------------------------------
    def find(self, needle: str, forward: bool, on_result) -> None:
        if self.data is None or not needle:
            on_result(False)
            return
        if needle != self._match_needle:
            self._match_needle = needle
            self._matches = treedata.search(self.data, needle)
            self._match_index = -1
        if not self._matches:
            on_result(False)
            return
        step = 1 if forward else -1
        self._match_index = (self._match_index + step) % len(self._matches)
        self.reveal(self._matches[self._match_index])
        on_result(True)

    def reveal(self, keys: list) -> None:
        """Expand down to the node at ``keys`` and select it."""
        wrapped = not treedata.is_container(self.data)
        path = ([0] if wrapped else []) + list(keys)
        value = [self.data] if wrapped else self.data
        index = next(i for i, (k, _v) in enumerate(treedata.children(value)) if k == path[0])
        row = self.tree.get_child_row(index)
        value = treedata.children(value)[index][1]
        for key in path[1:]:
            row.set_expanded(True)
            index = next(i for i, (k, _v) in enumerate(treedata.children(value)) if k == key)
            row = row.get_child_row(index)
            value = treedata.children(value)[index][1]
        position = row.get_position()
        self.selection.set_selected(position)
        self.listview.scroll_to(position, Gtk.ListScrollFlags.FOCUS, None)

    def shutdown(self):
        settings.remove_listener(self._on_setting)
