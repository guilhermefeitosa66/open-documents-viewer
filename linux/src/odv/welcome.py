"""The empty window: open a file or folder, or pick a recent one."""

import os

from gi.repository import Adw, Gtk, Pango

from odv import APP_ID, APP_NAME, recents
from odv.filetypes import sidebar_class
from odv.i18n import _


def tilde(path: str) -> str:
    home = os.path.expanduser("~")
    return "~" + path[len(home):] if path == home or path.startswith(home + os.sep) else path


class WelcomePage(Adw.Bin):
    def __init__(self, open_path):
        super().__init__()
        self._open_path = open_path
        status = Adw.StatusPage(icon_name=APP_ID, title=APP_NAME,
                                description=_("Open a file or a folder. Nothing is edited, nothing leaves this computer."))
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18, halign=Gtk.Align.CENTER)
        buttons = Gtk.Box(spacing=12, halign=Gtk.Align.CENTER)
        open_file = Gtk.Button(label=_("Open File…"), action_name="win.open-file", tooltip_text="Ctrl+O")
        open_file.add_css_class("pill")
        open_file.add_css_class("suggested-action")
        open_folder = Gtk.Button(label=_("Open Folder…"), action_name="win.open-folder", tooltip_text="Ctrl+Shift+O")
        open_folder.add_css_class("pill")
        buttons.append(open_file)
        buttons.append(open_folder)
        box.append(buttons)
        hint = Gtk.Label(label=_("or drop files and folders anywhere in this window"))
        hint.add_css_class("welcome-hint")
        hint.add_css_class("caption")
        box.append(hint)

        self.recent_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=12)
        head = Gtk.Box()
        title = Gtk.Label(label=_("Recent"), xalign=0.0, hexpand=True)
        title.add_css_class("heading")
        clear = Gtk.Button(label=_("Clear"))
        clear.add_css_class("flat")
        clear.connect("clicked", self._clear)
        head.append(title)
        head.append(clear)
        self.recent_box.append(head)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.list.add_css_class("boxed-list")
        self.list.connect("row-activated", lambda lb, row: self._open_path(row._path))
        self.recent_box.append(self.list)
        clamp = Adw.Clamp(maximum_size=520, child=self.recent_box)
        box.append(clamp)
        status.set_child(box)
        status.add_css_class("welcome-status")
        card = Gtk.Box(halign=Gtk.Align.CENTER)
        card.add_css_class("welcome-panel")
        card.append(status)
        panel = Adw.Clamp(maximum_size=640, child=card, valign=Gtk.Align.CENTER, margin_top=24,
                          margin_bottom=24, margin_start=24, margin_end=24)
        scroller = Gtk.ScrolledWindow(child=panel, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.set_child(scroller)
        self.refresh()

    def refresh(self) -> None:
        self.list.remove_all()
        items = [i for i in recents.load() if os.path.exists(i["path"])][:6]
        for item in items:
            path = item["path"]
            row = Adw.ActionRow(title=GLib_escape(os.path.basename(path) or path),
                                subtitle=GLib_escape(tilde(os.path.dirname(path))), activatable=True)
            row.set_subtitle_lines(1)
            row._path = path
            if item.get("dir"):
                icon = Gtk.Image(icon_name="folder-symbolic")
                icon.add_css_class("folder-icon")
                row.add_prefix(icon)
            else:
                dot = Gtk.Box(valign=Gtk.Align.CENTER, css_classes=["kind-dot", f"kind-{sidebar_class(path)}"])
                row.add_prefix(dot)
            when = Gtk.Label(label=recents.when(item.get("at", 0)), ellipsize=Pango.EllipsizeMode.END)
            when.add_css_class("dim-label")
            when.add_css_class("caption")
            row.add_suffix(when)
            self.list.append(row)
        self.recent_box.set_visible(bool(items))

    def _clear(self, _btn):
        recents.clear()
        self.refresh()


def GLib_escape(text: str) -> str:
    from gi.repository import GLib

    return GLib.markup_escape_text(text)
