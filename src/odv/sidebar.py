"""The folder tree in the sidebar. Folders are listed when first expanded."""

import os

from gi.repository import Gio, GObject, Gtk, Pango

from odv.filetypes import sidebar_class

HIDDEN = {".git", ".hg", ".svn"}


class FileNode(GObject.Object):
    __gtype_name__ = "OdvFileNode"

    def __init__(self, path: str, name: str, is_dir: bool):
        super().__init__()
        self.path = path
        self.name = name
        self.is_dir = is_dir


def list_dir(path: str) -> list[FileNode]:
    try:
        with os.scandir(path) as it:
            entries = []
            for e in it:
                if e.name in HIDDEN:
                    continue
                try:
                    is_dir = e.is_dir()
                except OSError:
                    is_dir = False
                entries.append(FileNode(e.path, e.name, is_dir))
    except OSError:
        return []
    entries.sort(key=lambda n: (not n.is_dir, n.name.casefold()))
    return entries


class FolderTree(Gtk.ScrolledWindow):
    def __init__(self, on_open_file):
        super().__init__(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self._on_open_file = on_open_file
        self.root = None
        self.tree = None
        factory = Gtk.SignalListItemFactory()
        factory.connect("setup", self._setup)
        factory.connect("bind", self._bind)
        self.listview = Gtk.ListView(factory=factory, single_click_activate=True)
        self.listview.add_css_class("navigation-sidebar")
        self.listview.connect("activate", self._activate)
        self.set_child(self.listview)

    def _store(self, path: str) -> Gio.ListStore:
        store = Gio.ListStore(item_type=FileNode)
        store.splice(0, 0, list_dir(path))
        return store

    def set_root(self, path: str) -> int:
        self.root = path
        root_store = self._store(path)
        self.tree = Gtk.TreeListModel.new(root_store, False, False, self._create_children)
        self.selection = Gtk.SingleSelection(model=self.tree, autoselect=False, can_unselect=True)
        self.listview.set_model(self.selection)
        return root_store.get_n_items()

    def _create_children(self, node):
        return self._store(node.path) if node.is_dir else None

    def _setup(self, _factory, item):
        expander = Gtk.TreeExpander(indent_for_icon=True)
        box = Gtk.Box(spacing=6)
        icon = Gtk.Image(icon_name="folder-symbolic", pixel_size=16)
        icon.add_css_class("folder-icon")
        dot = Gtk.Box(valign=Gtk.Align.CENTER)
        label = Gtk.Label(xalign=0.0, ellipsize=Pango.EllipsizeMode.MIDDLE, hexpand=True)
        box.append(icon)
        box.append(dot)
        box.append(label)
        expander.set_child(box)
        item.set_child(expander)
        item._parts = (expander, icon, dot, label)

    def _bind(self, _factory, item):
        expander, icon, dot, label = item._parts
        row = item.get_item()
        expander.set_list_row(row)
        node = row.get_item()
        label.set_label(node.name)
        item.get_child().set_tooltip_text(node.path)
        icon.set_visible(node.is_dir)
        dot.set_visible(not node.is_dir)
        dot.set_css_classes(["kind-dot", f"kind-{sidebar_class(node.name)}"])

    def _activate(self, _listview, position):
        row = self.tree.get_row(position)
        if row is None:
            return
        node = row.get_item()
        if node.is_dir:
            row.set_expanded(not row.get_expanded())
        else:
            self._on_open_file(node.path)
