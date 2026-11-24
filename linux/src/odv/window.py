"""The main window: sidebar, one or two panes of tabs, header bar."""

import os
import threading

from gi.repository import Adw, Gdk, Gio, GLib, Gtk

from odv import APP_NAME, recents, theme
from odv.document import DocumentPage
from odv.sidebar import FolderTree
from odv.welcome import WelcomePage, tilde
from odv.i18n import _


class ModeSwitch(Gtk.Box):
    """Formatted | Source, for the selected tab of one pane."""

    def __init__(self, on_mode):
        super().__init__(valign=Gtk.Align.CENTER, margin_end=6)
        self.add_css_class("linked")
        self.add_css_class("mode-switch")
        self._on_mode = on_mode
        self._sync = False
        self.formatted = Gtk.ToggleButton(label=_("Formatted"), tooltip_text=_("Formatted view (Ctrl+E)"))
        self.source = Gtk.ToggleButton(label=_("Source"), group=self.formatted, tooltip_text=_("Source view (Ctrl+E)"))
        for mode, btn in (("formatted", self.formatted), ("source", self.source)):
            btn.connect("toggled", self._toggled, mode)
            self.append(btn)

    def _toggled(self, btn, mode):
        if btn.get_active() and not self._sync:
            self._on_mode(mode)

    def show_doc(self, doc):
        visible = doc is not None and doc.has_formatted and doc.has_source
        self.set_visible(visible)
        if not visible:
            return
        self._sync = True
        self.formatted.set_label(_(doc.kind.formatted_label))
        (self.formatted if doc.mode == "formatted" else self.source).set_active(True)
        self._sync = False


class Pane(Gtk.Box):
    def __init__(self, window):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        self.window = window
        self.add_css_class("pane")
        self.view = Adw.TabView(vexpand=True)
        self.bar = Adw.TabBar(view=self.view, autohide=False)
        self.mode_switch = ModeSwitch(self._set_mode)
        self.mode_switch.set_visible(False)
        self.bar.set_end_action_widget(self.mode_switch)
        self.append(self.bar)
        self.append(self.view)
        self.view.connect("notify::selected-page", self._on_selected)
        self.view.connect("close-page", self._on_close_page)
        tab_menu = Gio.Menu()
        for label, action in ((_("Copy Path"), "win.copy-path"), (_("Show in Folder"), "win.show-in-folder"),
                              (_("Open With…"), "win.open-with"), (_("Save a Copy…"), "win.save-copy"),
                              (_("Print…"), "win.print"), (_("Reopen with Encoding…"), "win.reopen-encoding"),
                              (_("Properties"), "win.properties")):
            tab_menu.append(label, action)
        self.view.set_menu_model(tab_menu)
        # The tab menu acts on the tab it was opened on.
        self.view.connect("setup-menu", lambda v, page: page and (v.set_selected_page(page),
                                                                   self.window.set_active_pane(self)))
        self.view.connect("page-attached", lambda *a: self.window.on_pages_changed(self))
        self.view.connect("page-detached", lambda *a: GLib.idle_add(lambda: self.window.on_pages_changed(self)))
        focus = Gtk.EventControllerFocus()
        focus.connect("enter", lambda c: self.window.set_active_pane(self))
        self.add_controller(focus)
        click = Gtk.GestureClick(propagation_phase=Gtk.PropagationPhase.CAPTURE)
        click.connect("pressed", lambda *a: self.window.set_active_pane(self))
        self.add_controller(click)

    def current_doc(self):
        page = self.view.get_selected_page()
        return page.get_child() if page else None

    def docs(self):
        return [self.view.get_nth_page(i).get_child() for i in range(self.view.get_n_pages())]

    def find_page(self, path):
        for i in range(self.view.get_n_pages()):
            page = self.view.get_nth_page(i)
            if page.get_child().path == path:
                return page
        return None

    def open(self, path, mode=None, select=True, line=None, needle=None, anchor=None):
        path = os.path.abspath(path)
        page = self.find_page(path)
        goto = (line, needle) if line else None
        if page is None:
            doc = DocumentPage(path, self.window.open_path, "source" if goto else mode, goto=goto, anchor=anchor)
            page = self._append(doc)
        elif goto:
            page.get_child().goto(line, needle)
        elif anchor:
            page.get_child().go_to_anchor(anchor)
        if select:
            self.view.set_selected_page(page)
        return page.get_child()

    def _append(self, doc, position=None):
        if position is None:
            page = self.view.append(doc)
        else:
            page = self.view.insert(doc, position)
        page.set_title(doc.name)
        page.set_tooltip(GLib.markup_escape_text(tilde(doc.path)))
        page.set_loading(True)
        started = self.window.bench.file_started()
        doc.connect("first-content", lambda d: (page.set_loading(False),
                                                self.window.bench.file_first_content(d.name, started)))
        doc.connect("loaded", lambda d: self.window.bench.file_loaded(d.name, started))
        # Tabs can be dragged to the other pane: always ask the window where
        # the document lives now instead of assuming this pane.
        doc.connect("mode-changed", lambda d: self.window.pane_of(d) and self.window.pane_of(d)._sync_switch())
        doc.connect("changed-on-disk", lambda d: self.window.pane_of(d) and self.window.pane_of(d).reload_doc(d))
        doc.connect("navigate", lambda d, path: self.window.pane_of(d) and self.window.pane_of(d).navigate(d, path))
        return page

    def navigate(self, doc, path):
        """Show ``path`` in the tab of ``doc`` (next/previous image)."""
        page = self.find_page(doc.path)
        if page is not None and self.find_page(path) is None:
            self.replace(page, DocumentPage(path, self.window.open_path))
        elif self.find_page(path) is not None:
            self.view.set_selected_page(self.find_page(path))

    def reload_doc(self, doc):
        page = self.view.get_page(doc)
        if page is None:
            return
        selected = self.view.get_selected_page() is page
        self.replace(page, doc.reload(keep_scroll=True), select=selected)
        self.window.toast(_("Reloaded: the file changed on disk."), timeout=2)

    def replace(self, page, new_doc, select=True):
        pos = self.view.get_page_position(page)
        new_page = self._append(new_doc, pos)
        if select:
            self.view.set_selected_page(new_page)
        self.view.close_page(page)

    def _set_mode(self, mode):
        doc = self.current_doc()
        if doc:
            doc.set_mode(mode)

    def _sync_switch(self):
        self.mode_switch.show_doc(self.current_doc())

    def _on_selected(self, *_):
        self._sync_switch()
        self.window.on_selection_changed(self)

    def _on_close_page(self, view, page):
        page.get_child().shutdown()
        view.close_page_finish(page, True)
        return True

    def close_all(self):
        for i in reversed(range(self.view.get_n_pages())):
            self.view.close_page(self.view.get_nth_page(i))


class Window(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title=APP_NAME, default_width=1180, default_height=760)
        self.bench = app.bench
        self.folder = None
        self.panes: list[Pane] = []
        self.active_pane = None

        # Sidebar: the folder tree, or find in folder
        self.tree = FolderTree(self.open_path)
        from odv.searchpanel import SearchPanel

        from odv.outlinepanel import OutlinePanel

        self.search_panel = SearchPanel(self.open_at)
        self.outline_panel = OutlinePanel()
        self.sidebar_stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE)
        from odv.sidebar import FolderPanel

        self.folder_panel = FolderPanel(self.tree, self.open_path)
        self.sidebar_stack.add_named(self.folder_panel, "files")
        self.sidebar_stack.add_named(self.search_panel, "search")
        self.sidebar_stack.add_named(self.outline_panel, "outline")
        self.sidebar_title = Adw.WindowTitle(title="")
        sidebar_header = Adw.HeaderBar(title_widget=self.sidebar_title, show_title=False)
        switch = Gtk.Box(css_classes=["linked"])
        self.files_toggle = Gtk.ToggleButton(icon_name="folder-symbolic", tooltip_text=_("Folder"), active=True)
        self.search_toggle = Gtk.ToggleButton(icon_name="system-search-symbolic", group=self.files_toggle,
                                              tooltip_text=_("Search in folder (Ctrl+Shift+F)"))
        self.outline_toggle = Gtk.ToggleButton(icon_name="view-list-symbolic", group=self.files_toggle,
                                               tooltip_text=_("Outline (Ctrl+Shift+L)"))
        for name, button in (("files", self.files_toggle), ("search", self.search_toggle),
                             ("outline", self.outline_toggle)):
            button.connect("toggled", self._on_sidebar_toggled, name)
            switch.append(button)
        sidebar_header.pack_start(switch)
        sidebar_view = Adw.ToolbarView(content=self.sidebar_stack)
        sidebar_view.add_top_bar(sidebar_header)

        # Content
        self.title = Adw.WindowTitle(title=APP_NAME)
        header = Adw.HeaderBar(title_widget=self.title)
        self.sidebar_button = Gtk.ToggleButton(icon_name="sidebar-show-symbolic",
                                               tooltip_text=_("Show sidebar (F9)"))
        header.pack_start(self.sidebar_button)
        open_button = Adw.SplitButton(label=_("Open"), action_name="win.open-file", tooltip_text=_("Open a file (Ctrl+O)"))
        open_menu = Gio.Menu()
        open_menu.append(_("Open File…"), "win.open-file")
        open_menu.append(_("Open Folder…"), "win.open-folder")
        open_button.set_menu_model(open_menu)
        header.pack_start(open_button)
        menu = Gio.Menu()
        section = Gio.Menu()
        section.append(_("Find"), "win.find")
        section.append(_("Reload"), "win.reload")
        section.append(_("Split View"), "win.split")
        menu.append_section(None, section)
        file_section = Gio.Menu()
        file_section.append(_("Save a Copy…"), "win.save-copy")
        file_section.append(_("Print…"), "win.print")
        file_section.append(_("Properties"), "win.properties")
        menu.append_section(None, file_section)
        view_section = Gio.Menu()
        view_section.append(_("Full Screen"), "win.fullscreen")
        view_section.append(_("Focus Mode"), "win.focus-mode")
        menu.append_section(None, view_section)
        prefs = Gio.Menu()
        prefs.append(_("Preferences"), "win.preferences")
        menu.append_section(None, prefs)
        about = Gio.Menu()
        about.append(_("Keyboard Shortcuts"), "win.show-help-overlay")
        about.append(_("About {app}").format(app=APP_NAME), "app.about")
        menu.append_section(None, about)
        header.pack_end(Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, tooltip_text=_("Main menu"),
                                       primary=True))
        self.split_button = Gtk.ToggleButton(icon_name="view-dual-symbolic", tooltip_text=_("Split view (Ctrl+\\)"),
                                             action_name="win.split")
        header.pack_end(self.split_button)

        self.paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL, shrink_start_child=False,
                               shrink_end_child=False, wide_handle=False)
        self.welcome = WelcomePage(self.open_path)
        empty_status = Adw.StatusPage(icon_name="folder-open-symbolic", title=_("No file open"),
                                      description=_("Pick a file in the sidebar."))
        empty_card = Gtk.Box(halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
        empty_card.add_css_class("empty-panel")
        empty_card.append(empty_status)
        self.empty = Adw.Clamp(maximum_size=420, child=empty_card, valign=Gtk.Align.CENTER)
        self.stack = Gtk.Stack()
        self.stack.add_named(self.welcome, "welcome")
        self.stack.add_named(self.empty, "empty")
        self.stack.add_named(self.paned, "docs")
        content_view = Adw.ToolbarView(content=self.stack)
        content_view.add_top_bar(header)
        self.content_view = content_view

        self.split_view = Adw.OverlaySplitView(sidebar=sidebar_view, content=content_view, show_sidebar=False,
                                               min_sidebar_width=220, max_sidebar_width=360,
                                               sidebar_width_fraction=0.22)
        self.split_view.bind_property("show-sidebar", self.sidebar_button, "active",
                                      GObject_BIDIRECTIONAL())
        self._toaster = Adw.ToastOverlay(child=self.split_view)
        # The theme's background picture sits under the whole UI. It shows on
        # the start screen and through the translucent bars; documents are
        # always on a solid colour. Loaded after the first frame.
        self.background = Gtk.Picture(content_fit=Gtk.ContentFit.COVER, can_shrink=True, visible=False)
        layers = Gtk.Overlay(child=self.background)
        layers.add_overlay(self._toaster)
        layers.set_measure_overlay(self._toaster, True)
        self.set_content(layers)
        self._painted = False
        theme.on_change(self._on_theme_changed)
        self.connect("destroy", lambda w: theme.remove_listener(self._on_theme_changed))
        self._add_pane()
        self._actions()
        self._sync_sidebar_buttons()

        self.connect("close-request", self._on_close_request)
        drop = Gtk.DropTarget.new(Gdk.FileList, Gdk.DragAction.COPY)
        drop.connect("drop", self._on_drop)
        self.add_controller(drop)
        self.connect("map", self._on_map)

    # -- set-up ---------------------------------------------------------------
    def _actions(self):
        def add(name, cb, accels=(), state=None):
            if state is None:
                action = Gio.SimpleAction.new(name, None)
            else:
                action = Gio.SimpleAction.new_stateful(name, None, GLib.Variant.new_boolean(state))
            action.connect("activate", lambda a, p: cb())
            self.add_action(action)
            if accels:
                self.get_application().set_accels_for_action(f"win.{name}", list(accels))
            return action

        add("open-file", self.choose_files, ("<Control>o",))
        add("open-folder", self.choose_folder, ("<Control><Shift>o",))
        add("close-tab", self.close_tab, ("<Control>w",))
        add("toggle-mode", self.toggle_mode, ("<Control>e",))
        add("find", self.find, ("<Control>f",))
        add("reload", self.reload, ("<Control>r",))
        add("present-or-reload", self.present_or_reload, ("F5",))
        add("toggle-sidebar", self.toggle_sidebar, ("F9",))
        add("next-tab", lambda: self._cycle(1), ("<Control>Page_Down", "<Control>Tab"))
        add("prev-tab", lambda: self._cycle(-1), ("<Control>Page_Up", "<Control><Shift>Tab"))
        add("focus-other-pane", self.focus_other_pane, ("<Control>k",))
        add("preferences", self.show_preferences, ("<Control>comma",))
        add("search-folder", self.search_folder, ("<Control><Shift>f",))
        add("quick-open", self.quick_open, ("<Control>p",))
        add("command-palette", self.command_palette, ("<Control><Shift>p",))
        add("outline", self.show_outline, ("<Control><Shift>l",))
        add("zoom-in", lambda: self._zoom(1), ("<Control>plus", "<Control>equal", "<Control>KP_Add"))
        add("zoom-out", lambda: self._zoom(-1), ("<Control>minus", "<Control>KP_Subtract"))
        add("zoom-reset", lambda: self._zoom(0), ("<Control>0", "<Control>KP_0"))
        add("toggle-wrap", self.toggle_wrap, ("<Alt>z",))
        add("fullscreen", self.toggle_fullscreen, ("F11",))
        add("focus-mode", self.toggle_focus_mode, ("<Control><Shift>Return",))
        for name, accels in (("copy-path", ("<Control><Alt>c",)), ("show-in-folder", ()), ("open-with", ()),
                             ("save-copy", ("<Control><Shift>s",)), ("print", ("<Control><Alt>p",)),
                             ("properties", ("<Alt>Return",)), ("reopen-encoding", ())):
            add(name, lambda n=name: self.file_action(n), accels)
        self.split_action = add("split", self.toggle_split, ("<Control>backslash",), state=False)
        self.get_application().set_accels_for_action("win.show-help-overlay", ["<Control>question"])
        self.set_help_overlay(_shortcuts_window())

    def _on_map(self, *_):
        from odv.bench import after_next_paint

        def painted():
            self._painted = True
            self.bench.first_frame(self.get_application())
            self.load_background()

        after_next_paint(self, painted)

    # -- background picture ---------------------------------------------------
    _textures: dict = {}  # path -> Gdk.Texture, shared by every window

    def _on_theme_changed(self, _dark):
        if self._painted:
            self.load_background()

    def load_background(self):
        path = theme.background_path()
        if path is None:
            self.background.set_visible(False)
            self.background.set_paintable(None)
            self.remove_css_class("odv-has-background")
            return
        texture = Window._textures.get(path)
        if texture is not None:
            self._show_background(path, texture)
            return

        def decode():
            try:
                tex = Gdk.Texture.new_from_filename(path)
            except GLib.Error:
                return
            GLib.idle_add(lambda: (self._show_background(path, tex), False)[1], priority=GLib.PRIORITY_LOW)

        threading.Thread(target=decode, daemon=True).start()

    def _show_background(self, path, texture):
        Window._textures[path] = texture
        if theme.background_path() != path:
            return  # the theme changed while it was decoding
        self.background.set_paintable(texture)
        self.background.set_visible(True)
        self.add_css_class("odv-has-background")
        self.bench.background_shown()

    # -- panes ----------------------------------------------------------------
    def _add_pane(self):
        pane = Pane(self)
        self.panes.append(pane)
        if len(self.panes) == 1:
            self.paned.set_start_child(pane)
        else:
            self.paned.set_end_child(pane)
        self._style_panes()
        self.set_active_pane(pane)
        return pane

    def _style_panes(self):
        split = len(self.panes) > 1
        for p in self.panes:
            (p.add_css_class if split else p.remove_css_class)("pane-split")

    # -- session ----------------------------------------------------------------
    def session_state(self) -> dict:
        panes = []
        for pane in self.panes:
            docs = pane.docs()
            current = pane.current_doc()
            panes.append({
                "tabs": [{"path": d.path, "mode": d.mode, "scroll": round(d.scroll_position(), 1)} for d in docs],
                "selected": docs.index(current) if current in docs else 0,
            })
        width, height = self.get_default_size()
        return {
            "folder": self.folder,
            "sidebar": self.split_view.get_show_sidebar(),
            "panes": panes,
            "active": self.panes.index(self.active_pane) if self.active_pane in self.panes else 0,
            "size": [width, height],
            "maximized": self.is_maximized(),
        }

    def restore_session(self, state: dict) -> None:
        if state.get("size"):
            self.set_default_size(*state["size"])
        if state.get("maximized"):
            self.maximize()
        if state.get("folder"):
            self.open_folder(state["folder"])
            self.split_view.set_show_sidebar(bool(state.get("sidebar", True)))
        for i, saved in enumerate(state.get("panes") or []):
            if i == 1:
                self.toggle_split(open_current=False)
            pane = self.panes[i]
            for tab in saved["tabs"]:
                doc = DocumentPage(tab["path"], self.open_path, tab.get("mode"), scroll=tab.get("scroll") or None)
                pane._append(doc)
            page = pane.view.get_nth_page(saved.get("selected", 0))
            if page:
                pane.view.set_selected_page(page)
        active = state.get("active", 0)
        if 0 <= active < len(self.panes):
            self.set_active_pane(self.panes[active])
        self._update_stack()
        self._update_title()

    def _on_close_request(self, _window):
        from odv import session, settings

        if settings.get("restore_session") and not self.bench.enabled:
            session.save(self.session_state())
        return False

    def pane_of(self, doc):
        for pane in self.panes:
            if doc in pane.docs():
                return pane
        return None

    def set_active_pane(self, pane):
        if pane is self.active_pane or pane not in self.panes:
            return
        if self.active_pane is not None:
            self.active_pane.remove_css_class("active-pane")
        self.active_pane = pane
        pane.add_css_class("active-pane")
        self._update_title()

    def on_selection_changed(self, pane):
        if pane is self.active_pane:
            self._update_title()

    def on_pages_changed(self, pane):
        if pane not in self.panes:
            return
        if pane.view.get_n_pages() == 0 and len(self.panes) > 1:
            self._remove_pane(pane)
        self._update_stack()
        self._update_title()

    def _remove_pane(self, pane):
        pane.close_all()
        self.panes.remove(pane)
        if pane is self.paned.get_start_child():
            other = self.paned.get_end_child()
            self.paned.set_end_child(None)
            self.paned.set_start_child(other)
        else:
            self.paned.set_end_child(None)
        self.active_pane = None
        self.set_active_pane(self.panes[0])
        self._style_panes()
        self.split_action.set_state(GLib.Variant.new_boolean(False))

    def toggle_split(self, open_current=True):
        if len(self.panes) > 1:
            self._remove_pane(self.panes[1])
            self._update_stack()
            return
        doc = self.active_pane.current_doc() if self.active_pane else None
        pane = self._add_pane()
        self.split_action.set_state(GLib.Variant.new_boolean(True))
        width = self.paned.get_width()
        if width > 0:
            self.paned.set_position(width // 2)
        if doc is not None and open_current:
            # Same file on both sides; Markdown and CSV show the other view.
            other = ("source" if doc.mode == "formatted" else "formatted") if doc.has_formatted else None
            pane.open(doc.path, other)
        self._update_stack()

    def focus_other_pane(self):
        if len(self.panes) > 1:
            other = self.panes[1] if self.active_pane is self.panes[0] else self.panes[0]
            self.set_active_pane(other)
            doc = other.current_doc()
            if doc:
                doc.child_focus(Gtk.DirectionType.TAB_FORWARD)

    def _cycle(self, step):
        view = self.active_pane.view
        n = view.get_n_pages()
        page = view.get_selected_page()
        if n > 1 and page:
            view.set_selected_page(view.get_nth_page((view.get_page_position(page) + step) % n))

    # -- opening --------------------------------------------------------------
    def open_path(self, path, pane=None, anchor=None):
        path = os.path.abspath(os.path.expanduser(path))
        if os.path.isdir(path):
            self.open_folder(path)
            return
        if not os.path.exists(path):
            toast = Adw.Toast(title=_("Not found: {path}").format(path=GLib.markup_escape_text(tilde(path))))
            self._toast(toast)
            return
        (pane or self.active_pane).open(path, anchor=anchor)
        recents.add(path)
        self._update_stack()

    def open_at(self, path, line, needle=None):
        """Open ``path`` in the source view at ``line``, selecting ``needle``."""
        self.active_pane.open(path, line=line, needle=needle)
        recents.add(path)
        self._update_stack()

    def quick_open(self):
        from odv.palette import PaletteDialog, file_items

        if not self.folder:
            self.choose_files()
            return
        root = self.folder
        dialog = PaletteDialog(_("Go to file"), loader=lambda: file_items(root, self.open_path))
        dialog.present(self)
        return dialog

    def command_palette(self):
        from odv import theme, themes
        from odv.palette import Item, PaletteDialog

        def act(name):
            run = lambda: self.activate_action(name, None)  # noqa: E731
            run.action = name
            return run

        doc = self.active_pane.current_doc() if self.active_pane else None
        items = [
            Item(_("Open File…"), "Ctrl+O", act("win.open-file")),
            Item(_("Open Folder…"), "Ctrl+Shift+O", act("win.open-folder")),
            Item(_("Go to file"), "Ctrl+P", act("win.quick-open")),
            Item(_("Find"), "Ctrl+F", act("win.find")),
            Item(_("Search in folder"), "Ctrl+Shift+F", act("win.search-folder")),
            Item(_("Formatted / source"), "Ctrl+E", act("win.toggle-mode")),
            Item(_("Split View"), "Ctrl+\\", act("win.split")),
            Item(_("Other pane"), "Ctrl+K", act("win.focus-other-pane")),
            Item(_("Show sidebar"), "F9", act("win.toggle-sidebar")),
            Item(_("Outline"), "Ctrl+Shift+L", act("win.outline")),
            Item(_("Copy Path"), "Ctrl+Alt+C", act("win.copy-path")),
            Item(_("Show in Folder"), "", act("win.show-in-folder")),
            Item(_("Open With…"), "", act("win.open-with")),
            Item(_("Save a Copy…"), "Ctrl+Shift+S", act("win.save-copy")),
            Item(_("Print…"), "Ctrl+Alt+P", act("win.print")),
            Item(_("Properties"), "Alt+Enter", act("win.properties")),
            Item(_("Reopen with Encoding…"), "", act("win.reopen-encoding")),
            Item(_("Zoom in"), "Ctrl++", act("win.zoom-in")),
            Item(_("Zoom out"), "Ctrl+-", act("win.zoom-out")),
            Item(_("Normal size"), "Ctrl+0", act("win.zoom-reset")),
            Item(_("Line wrap"), "Alt+Z", act("win.toggle-wrap")),
            Item(_("Full Screen"), "F11", act("win.fullscreen")),
            Item(_("Focus Mode"), "Ctrl+Shift+Enter", act("win.focus-mode")),
            Item(_("Reload"), "Ctrl+R", act("win.reload")),
            Item(_("Close tab"), "Ctrl+W", act("win.close-tab")),
            Item(_("Preferences"), "Ctrl+,", act("win.preferences")),
            Item(_("Keyboard Shortcuts"), "Ctrl+?", act("win.show-help-overlay")),
            Item(_("New window"), "Ctrl+N", act("app.new-window")),
            Item(_("Quit"), "Ctrl+Q", act("app.quit")),
        ]
        if doc is None:
            needs_doc = {"win.find", "win.toggle-mode", "win.reload", "win.close-tab", "win.copy-path",
                         "win.reopen-encoding",
                         "win.show-in-folder", "win.open-with", "win.save-copy", "win.print", "win.properties"}
            items = [i for i in items if getattr(i.run, "action", None) not in needs_doc]
        names = [(themes.DEFAULT_ID, "libre-apps")] + [(t["id"], t["name"]) for t in themes.catalogue()]
        for tid, name in names:
            items.append(Item(_("Theme: {name}").format(name=name), "", lambda t=tid: theme.set_theme(t)))
        items.append(Item(_("Background picture: on/off"), "",
                          lambda: theme.set_background(not theme.background_enabled())))
        dialog = PaletteDialog(_("Run a command"), items=items)
        dialog.present(self)
        return dialog

    # -- file actions -----------------------------------------------------------
    def file_action(self, name):
        from odv import fileactions

        doc = self.active_pane.current_doc() if self.active_pane else None
        if doc is None:
            return None
        return {"copy-path": fileactions.copy_path, "show-in-folder": fileactions.show_in_folder,
                "open-with": fileactions.open_with, "save-copy": fileactions.save_copy,
                "print": fileactions.print_doc, "properties": fileactions.show_properties,
                "reopen-encoding": fileactions.choose_encoding}[name](self, doc)

    # -- comfort ----------------------------------------------------------------
    def _zoom(self, direction):
        doc = self.active_pane.current_doc() if self.active_pane else None
        if doc is not None:
            level = doc.zoom(direction)
            self.toast(_("Zoom {level}%").format(level=level), timeout=1)

    def toggle_wrap(self):
        doc = self.active_pane.current_doc() if self.active_pane else None
        if doc is not None:
            wrapped = doc.toggle_wrap()
            if wrapped is not None:
                self.toast(_("Line wrap on") if wrapped else _("Line wrap off"), timeout=1)

    def toggle_fullscreen(self):
        if self.is_fullscreen():
            self.unfullscreen()
        else:
            self.fullscreen()

    def toggle_focus_mode(self):
        self.focus_mode = not getattr(self, "focus_mode", False)
        if self.focus_mode:
            self._sidebar_before_focus = self.split_view.get_show_sidebar()
            self.split_view.set_show_sidebar(False)
            self.toast(_("Focus mode: Ctrl+Shift+Enter brings the bars back."), timeout=2)
        else:
            self.split_view.set_show_sidebar(getattr(self, "_sidebar_before_focus", False))
        self.content_view.set_reveal_top_bars(not self.focus_mode)
        for pane in self.panes:
            pane.bar.set_visible(not self.focus_mode)

    def search_folder(self):
        if not self.folder:
            return
        self.split_view.set_show_sidebar(True)
        self.search_toggle.set_active(True)
        self.search_panel.focus()

    def show_outline(self):
        if self.split_view.get_show_sidebar() and self.outline_toggle.get_active():
            self.split_view.set_show_sidebar(False)
            return
        self.split_view.set_show_sidebar(True)
        self.outline_toggle.set_active(True)

    def _on_sidebar_toggled(self, button, name):
        if not button.get_active():
            return
        self.sidebar_stack.set_visible_child_name(name)
        if name == "search":
            self.search_panel.focus()

    def _sync_sidebar_buttons(self):
        has_folder = self.folder is not None
        self.files_toggle.set_sensitive(has_folder)
        self.search_toggle.set_sensitive(has_folder)
        if not has_folder:
            self.outline_toggle.set_active(True)

    def open_folder(self, path):
        self.folder = path
        self.folder_panel.set_root(path)
        self.search_panel.set_root(path)
        self.files_toggle.set_active(True)
        self._sync_sidebar_buttons()
        self.sidebar_title.set_title(os.path.basename(path) or path)
        self.sidebar_title.set_subtitle(tilde(os.path.dirname(path)))
        self.sidebar_button.set_visible(True)
        self.split_view.set_show_sidebar(True)
        recents.add(path)
        self._update_stack()
        self._update_title()

    def _toast(self, toast):
        self._toaster.add_toast(toast)

    def toast(self, text: str, timeout: int = 3):
        self._toaster.add_toast(Adw.Toast(title=GLib.markup_escape_text(text), timeout=timeout))

    def _update_stack(self):
        has_docs = any(p.view.get_n_pages() for p in self.panes)
        if has_docs:
            self.stack.set_visible_child_name("docs")
        elif self.folder:
            self.stack.set_visible_child_name("empty")
        else:
            self.welcome.refresh()
            self.stack.set_visible_child_name("welcome")

    def _update_title(self):
        doc = self.active_pane.current_doc() if self.active_pane else None
        if getattr(self, "outline_panel", None) is not None and self.outline_panel.doc is not doc:
            self.outline_panel.set_doc(doc)
        if doc is not None:
            self.title.set_title(doc.name)
            self.title.set_subtitle(tilde(os.path.dirname(doc.path)))
            self.set_title(f"{doc.name} – {APP_NAME}")
        elif self.folder:
            self.title.set_title(os.path.basename(self.folder))
            self.title.set_subtitle(tilde(os.path.dirname(self.folder)))
            self.set_title(APP_NAME)
        else:
            self.title.set_title(APP_NAME)
            self.title.set_subtitle("")
            self.set_title(APP_NAME)

    def _on_drop(self, _target, value, _x, _y):
        for f in value.get_files():
            if f.get_path():
                self.open_path(f.get_path())
        return True

    # -- actions ----------------------------------------------------------------
    def choose_files(self):
        dialog = Gtk.FileDialog(title=_("Open File"), modal=True)
        if self.folder:
            dialog.set_initial_folder(Gio.File.new_for_path(self.folder))

        def done(dlg, result):
            try:
                files = dlg.open_multiple_finish(result)
            except GLib.Error:
                return
            for i in range(files.get_n_items()):
                path = files.get_item(i).get_path()
                if path:
                    self.open_path(path)

        dialog.open_multiple(self, None, done)

    def choose_folder(self):
        dialog = Gtk.FileDialog(title=_("Open Folder"), modal=True)

        def done(dlg, result):
            try:
                folder = dlg.select_folder_finish(result)
            except GLib.Error:
                return
            if folder and folder.get_path():
                self.open_folder(folder.get_path())

        dialog.select_folder(self, None, done)

    def close_tab(self):
        page = self.active_pane.view.get_selected_page() if self.active_pane else None
        if page:
            self.active_pane.view.close_page(page)
        elif len(self.panes) == 1 and not self.folder:
            self.close()

    def toggle_mode(self):
        doc = self.active_pane.current_doc() if self.active_pane else None
        if doc:
            doc.toggle_mode()

    def find(self):
        doc = self.active_pane.current_doc() if self.active_pane else None
        if doc:
            doc.start_search()

    def reload(self):
        pane = self.active_pane
        page = pane.view.get_selected_page() if pane else None
        if page:
            pane.replace(page, page.get_child().reload())

    def show_preferences(self):
        from odv.preferences import PreferencesDialog

        PreferencesDialog().present_for(self)

    def present_or_reload(self):
        """F5: present a presentation, reload anything else."""
        doc = self.active_pane.current_doc() if self.active_pane else None
        if doc is not None and doc.present():
            return
        self.reload()

    def toggle_sidebar(self):
        self.split_view.set_show_sidebar(not self.split_view.get_show_sidebar())


def GObject_BIDIRECTIONAL():
    from gi.repository import GObject

    return GObject.BindingFlags.BIDIRECTIONAL | GObject.BindingFlags.SYNC_CREATE


def _shortcuts_window():
    win = Gtk.ShortcutsWindow(modal=True)
    section = Gtk.ShortcutsSection(section_name="main", visible=True)
    groups = {
        _("Files"): [("<Control>o", _("Open file")), ("<Control><Shift>o", _("Open folder")),
                     ("<Control>w", _("Close tab")), ("<Control>r", _("Reload"))],
        _("View"): [("<Control>e", _("Formatted / source")), ("<Control>f", _("Find")),
                    ("<Control>backslash", _("Split View")), ("<Control>k", _("Other pane")),
                    ("F9", _("Show sidebar")), ("<Control><Shift>l", _("Outline")),
                    ("<Control><Shift>f", _("Search in folder")),
                    ("<Control>p", _("Go to file")), ("<Control><Shift>p", _("Run a command")),
                    ("<Control><Shift>s", _("Save a Copy…")), ("<Control><Alt>p", _("Print…")),
                    ("<Alt>Return", _("Properties")), ("<Control><Alt>c", _("Copy Path")),
                    ("<Control>plus", _("Zoom in")), ("<Control>minus", _("Zoom out")),
                    ("<Control>0", _("Normal size")), ("<Alt>z", _("Line wrap")),
                    ("F11", _("Full Screen")), ("<Control><Shift>Return", _("Focus Mode")),
                    ("<Control>comma", _("Preferences")),
                    ("<Control>Page_Down", _("Next tab")), ("<Control>Page_Up", _("Previous tab"))],
    }
    for title, items in groups.items():
        group = Gtk.ShortcutsGroup(title=title, visible=True)
        for accel, label in items:
            shortcut = Gtk.ShortcutsShortcut(accelerator=accel, title=label, visible=True)
            group.add_shortcut(shortcut) if hasattr(group, "add_shortcut") else group.append(shortcut)
        section.add_group(group) if hasattr(section, "add_group") else section.append(group)
    win.add_section(section) if hasattr(win, "add_section") else win.set_child(section)
    return win
