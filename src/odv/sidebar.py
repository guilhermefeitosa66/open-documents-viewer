"""The folder tree in the sidebar. Folders are listed when first expanded."""

import os
import threading

from gi.repository import Gio, GLib, GObject, Gtk, Pango

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


class FolderPanel(Gtk.Box):
    """The folder tree with a name filter on top. While the filter has text,
    the tree gives way to a flat list of the matching files of the whole
    folder (listed once in a thread, the same set as find in folder)."""

    LIMIT = 500

    def __init__(self, tree: FolderTree, on_open_file):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True)
        from odv.i18n import _

        self.tree = tree
        self._on_open_file = on_open_file
        self._files = None  # (root, [paths]) once listed
        self._timer = 0
        self.entry = Gtk.SearchEntry(placeholder_text=_("Filter files"), margin_start=8, margin_end=8,
                                     margin_top=6, margin_bottom=4)
        self.entry.connect("search-changed", self._queue)
        self.entry.connect("activate", lambda e: self._open_first())
        self.append(self.entry)
        self.results = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.results.add_css_class("navigation-sidebar")
        self.results.connect("row-activated", lambda lb, row: self._on_open_file(row._path))
        self.stack = Gtk.Stack(vexpand=True)
        self.stack.add_named(tree, "tree")
        self.stack.add_named(Gtk.ScrolledWindow(child=self.results, hscrollbar_policy=Gtk.PolicyType.NEVER),
                             "results")
        self.append(self.stack)

    def set_root(self, path: str) -> int:
        self._files = None
        self.entry.set_text("")
        self.stack.set_visible_child_name("tree")
        return self.tree.set_root(path)

    def _queue(self, _entry):
        if self._timer:
            GLib.source_remove(self._timer)
        self._timer = GLib.timeout_add(150, self._refresh)

    def _refresh(self):
        self._timer = 0
        text = self.entry.get_text().strip().casefold()
        if not text or self.tree.root is None:
            self.stack.set_visible_child_name("tree")
            return False
        root = self.tree.root
        if self._files is None or self._files[0] != root:
            self._files = (root, None)

            def work():
                from odv import foldersearch

                files = foldersearch.list_files(root)
                GLib.idle_add(lambda: (self._listed(root, files), False)[1])

            threading.Thread(target=work, daemon=True).start()
            return False
        if self._files[1] is None:
            return False  # still listing; _listed refreshes
        self._show(text)
        return False

    def _listed(self, root, files):
        if self._files and self._files[0] == root:
            self._files = (root, files)
            self._refresh()

    def _show(self, text):
        root, files = self._files
        self.results.remove_all()
        shown = 0
        for path in files:
            rel = os.path.relpath(path, root)
            if text not in rel.casefold():
                continue
            row = Gtk.ListBoxRow()
            box = Gtk.Box(spacing=6)
            dot = Gtk.Box(valign=Gtk.Align.CENTER, css_classes=["kind-dot", f"kind-{sidebar_class(path)}"])
            name = Gtk.Label(label=os.path.basename(path), xalign=0.0)
            folder = Gtk.Label(label=os.path.dirname(rel), xalign=0.0, hexpand=True,
                               ellipsize=Pango.EllipsizeMode.START)
            folder.add_css_class("dim-label")
            folder.add_css_class("caption")
            box.append(dot)
            box.append(name)
            box.append(folder)
            row.set_child(box)
            row.set_tooltip_text(path)
            row._path = path
            self.results.append(row)
            shown += 1
            if shown >= self.LIMIT:
                break
        self.stack.set_visible_child_name("results")

    def _open_first(self):
        row = self.results.get_row_at_index(0)
        if row is not None and self.stack.get_visible_child_name() == "results":
            self._on_open_file(row._path)
