"""The PDF view: pages in one continuous scroll, rendered on demand by the
document's worker (odv.formats.pdf.Engine) and kept in a small cache;
zoom (buttons, Ctrl+wheel, pinch), fit width/page, go to page,
thumbnails, find with highlights, text selection and copy, internal and
web links, password prompt."""

from gi.repository import Adw, Gdk, GLib, GObject, Graphene, Gtk

from odv.i18n import _

SPACING = 12
MAX_TEXTURES = 16
ZOOM_STEPS = (0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0)
MAX_RENDER_PIXELS = 40_000_000  # a page bigger than this at the current zoom is rendered smaller


def _rgba(spec):
    c = Gdk.RGBA()
    c.parse(spec)
    return c


SEARCH_RGBA = _rgba("rgba(255, 200, 0, 0.45)")
SELECT_RGBA = _rgba("rgba(53, 132, 228, 0.35)")
PAPER_RGBA = _rgba("#ffffff")
EDGE_RGBA = _rgba("rgba(0, 0, 0, 0.18)")


class PageWidget(Gtk.Widget):
    __gtype_name__ = "OdvPdfPage"

    def __init__(self, view, index):
        super().__init__()
        self.view = view
        self.index = index
        self.texture = None
        self.texture_scale = 0.0
        self.pending = 0.0
        self.search_rects = []
        self.select_rects = []
        self.set_halign(Gtk.Align.CENTER)

    def points(self):
        return self.view.sizes[self.index]

    def do_measure(self, orientation, _for_size):
        w, h = self.points()
        z = self.view.z
        size = int(round((w if orientation == Gtk.Orientation.HORIZONTAL else h) * z))
        return size, size, -1, -1

    def do_snapshot(self, snapshot):
        w, h = self.get_width(), self.get_height()
        rect = Graphene.Rect().init(0, 0, w, h)
        edge = Graphene.Rect().init(-1, -1, w + 2, h + 2)
        snapshot.append_color(EDGE_RGBA, edge)
        snapshot.append_color(PAPER_RGBA, rect)
        if self.texture is not None:
            snapshot.append_texture(self.texture, rect)
        z = self.view.z
        for rects, color in ((self.search_rects, SEARCH_RGBA), (self.select_rects, SELECT_RGBA)):
            for x, y, rw, rh in rects:
                snapshot.append_color(color, Graphene.Rect().init(x * z - 1, y * z - 1, rw * z + 2, rh * z + 2))


class PdfView(Gtk.Box):
    __gsignals__ = {
        "loaded": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "failed": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
        "position-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self, path, password=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, vexpand=True, hexpand=True)
        from odv.formats.pdf import Engine

        self.path = path
        self.password = password
        self.info = None
        self.sizes = []
        self.pages = []
        self.z = 1.0
        self.fit_mode = "width"  # width, page, or None (a chosen zoom)
        self._update_id = 0
        self._last_width = 0
        self._search = None  # (needle, page, char index)
        self._selection_text = ""
        self._selecting = False
        self._select_busy = False
        self._select_next = None
        self.engine = Engine(path, lambda fn: GLib.idle_add(lambda: (fn(), False)[1]))

        self.stack = Gtk.Stack(vexpand=True, hexpand=True)
        self.append(self.stack)
        self.stack.add_named(Gtk.Box(), "loading")

        # pages
        self.pages_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=SPACING, margin_top=SPACING,
                                 margin_bottom=SPACING, margin_start=SPACING, margin_end=SPACING,
                                 halign=Gtk.Align.CENTER)
        self.scrolled = Gtk.ScrolledWindow(child=self.pages_box, vexpand=True, hexpand=True, focusable=True)
        self.scrolled.add_css_class("pdf-pages")
        self.scrolled.get_vadjustment().connect("value-changed", self._on_scroll)
        self.scrolled.get_hadjustment().connect("changed", self._on_width)
        self.thumbs = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.thumbs.add_css_class("navigation-sidebar")
        self.thumbs.connect("row-activated", lambda _lb, row: self.go(row.get_index()))
        self.thumbs_scrolled = Gtk.ScrolledWindow(child=self.thumbs, width_request=170,
                                                  hscrollbar_policy=Gtk.PolicyType.NEVER)
        thumbs_adj = self.thumbs_scrolled.get_vadjustment()
        thumbs_adj.connect("value-changed", lambda a: self._render_thumbs())
        thumbs_adj.connect("changed", lambda a: self._render_thumbs())  # first allocation, resizes
        self.thumbs_revealer = Gtk.Revealer(child=self.thumbs_scrolled, reveal_child=False,
                                            transition_type=Gtk.RevealerTransitionType.SLIDE_RIGHT)
        body = Gtk.Box(vexpand=True)
        body.append(self.thumbs_revealer)
        body.append(self.scrolled)
        pages = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        pages.append(body)
        pages.append(Gtk.Separator())
        pages.append(self._bar())
        self.stack.add_named(pages, "pages")

        drag = Gtk.GestureDrag(button=Gdk.BUTTON_PRIMARY)
        drag.connect("drag-begin", self._on_drag_begin)
        drag.connect("drag-update", self._on_drag_update)
        drag.connect("drag-end", self._on_drag_end)
        self.pages_box.add_controller(drag)
        pinch = Gtk.GestureZoom()
        pinch.connect("begin", lambda g, s: setattr(self, "_pinch_base", self.z))
        pinch.connect("scale-changed", lambda g, scale: self.set_zoom(self._pinch_base * scale, fit=None))
        self.scrolled.add_controller(pinch)
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.add_controller(keys)

        self._open(password)

    # -- widgets ---------------------------------------------------------------------------
    def _bar(self):
        bar = Gtk.Box(spacing=6, margin_start=8, margin_end=8, margin_top=4, margin_bottom=4)
        self.thumbs_button = Gtk.ToggleButton(icon_name="view-grid-symbolic", tooltip_text=_("Thumbnails"))
        self.thumbs_button.connect("toggled", self._on_thumbs_toggled)
        prev = Gtk.Button(icon_name="go-up-symbolic", tooltip_text=_("Previous page"))
        prev.connect("clicked", lambda b: self.go(self.current_page() - 1))
        nxt = Gtk.Button(icon_name="go-down-symbolic", tooltip_text=_("Next page"))
        nxt.connect("clicked", lambda b: self.go(self.current_page() + 1))
        self.page_entry = Gtk.Entry(width_chars=4, max_width_chars=6, xalign=1.0,
                                    tooltip_text=_("Go to page"), input_purpose=Gtk.InputPurpose.DIGITS)
        self.page_entry.connect("activate", self._on_page_entry)
        self.page_total = Gtk.Label()
        zoom_out = Gtk.Button(icon_name="zoom-out-symbolic", tooltip_text=_("Zoom out (Ctrl+−)"))
        zoom_out.connect("clicked", lambda b: self.zoom_step(-1))
        zoom_in = Gtk.Button(icon_name="zoom-in-symbolic", tooltip_text=_("Zoom in (Ctrl++)"))
        zoom_in.connect("clicked", lambda b: self.zoom_step(1))
        self.zoom_label = Gtk.Label(width_chars=5)
        fit_width = Gtk.Button(icon_name="zoom-fit-best-symbolic", tooltip_text=_("Fit width"))
        fit_width.connect("clicked", lambda b: self.fit("width"))
        fit_page = Gtk.Button(icon_name="view-fullscreen-symbolic", tooltip_text=_("Fit page"))
        fit_page.connect("clicked", lambda b: self.fit("page"))
        for widget in (self.thumbs_button, prev, self.page_entry, self.page_total, nxt):
            bar.append(widget)
        bar.append(Gtk.Box(hexpand=True))
        for widget in (zoom_out, self.zoom_label, zoom_in, fit_width, fit_page):
            bar.append(widget)
        return bar

    def _password_page(self, error: str | None):
        old = self.stack.get_child_by_name("password")
        if old is not None:
            self.stack.remove(old)
        entry = Adw.PasswordEntryRow(title=_("Password"))
        group = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, css_classes=["boxed-list"])
        group.append(entry)
        label = Gtk.Label(label=error or "", visible=bool(error), wrap=True, css_classes=["error"])
        button = Gtk.Button(label=_("Open"), halign=Gtk.Align.CENTER, css_classes=["pill", "suggested-action"])
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, width_request=320, halign=Gtk.Align.CENTER)
        for widget in (group, label, button):
            box.append(widget)
        page = Adw.StatusPage(title=_("This file is password-protected"),
                              description=_("The password is only used to read the file here, in memory."),
                              icon_name="dialog-password-symbolic", child=box)

        def submit(*_a):
            if entry.get_text():
                button.set_sensitive(False)
                self._open(entry.get_text())

        entry.connect("entry-activated", submit)
        button.connect("clicked", submit)
        self.password_entry = entry
        self.stack.add_named(page, "password")
        self.stack.set_visible_child(page)
        GLib.idle_add(lambda: (entry.grab_focus(), False)[1])

    # -- loading -------------------------------------------------------------------------
    def _open(self, password):
        from odv.formats.pdf import PasswordNeeded

        def done(info):
            self.password = password
            self._loaded(info)

        def failed(err):
            if isinstance(err, PasswordNeeded):
                self._password_page(_("Wrong password.") if password else None)
                self.emit("loaded")  # the page is ready: it asks for the password
            elif isinstance(err, ImportError):
                self.emit("failed", _("Showing PDF files needs pypdfium2, which is missing from this installation."))
            else:
                self.emit("failed", str(err))

        self.engine.submit(lambda: self.engine.open(password), done, failed)

    def _loaded(self, info):
        self.info = info
        self.sizes = list(info.sizes)
        for i in range(info.pages):
            page = PageWidget(self, i)
            self.pages.append(page)
            self.pages_box.append(page)
        self.page_total.set_label(_("of {n}").format(n=info.pages))
        self.stack.set_visible_child_name("pages")
        if not info.pages:
            self.emit("failed", _("This PDF has no pages."))
            return
        self.fit_mode = "width"
        GLib.idle_add(self._first_layout)
        self.emit("loaded")

    def _first_layout(self):
        width = self.scrolled.get_width()
        if width <= 1:
            return True  # not allocated yet
        self._apply_fit()
        self._schedule_update()
        return False

    def outline(self):
        from odv.mdrender import Heading

        if self.info is None:
            return []
        return [Heading(level, title, page, 1) for level, title, page in self.info.toc]

    # -- geometry ------------------------------------------------------------------------
    def _page_top(self, index):
        return SPACING + sum(int(round(h * self.z)) + SPACING for _w, h in self.sizes[:index])

    def _tops(self):
        tops, y = [], SPACING
        for _w, h in self.sizes:
            tops.append(y)
            y += int(round(h * self.z)) + SPACING
        return tops

    def current_page(self) -> int:
        if not self.pages:
            return 0
        adj = self.scrolled.get_vadjustment()
        probe = adj.get_value() + adj.get_page_size() / 3
        tops = self._tops()
        index = 0
        for i, top in enumerate(tops):
            if top <= probe:
                index = i
            else:
                break
        return index

    def get_vadjustment(self):
        return self.scrolled.get_vadjustment()

    def top_offset(self) -> int:
        return self.current_page()

    def scroll_to_offset(self, offset: int) -> None:
        self.go(offset)

    def go(self, index: int, y_points: float = 0.0, align: float = 0.0) -> None:
        if not self.pages:
            return
        index = max(0, min(len(self.pages) - 1, index))
        adj = self.scrolled.get_vadjustment()
        target = self._page_top(index) + y_points * self.z - adj.get_page_size() * align
        if align == 0.0:
            target -= SPACING / 2
        adj.set_value(max(0.0, min(target, adj.get_upper() - adj.get_page_size())))
        self._on_scroll(adj)

    def _on_page_entry(self, entry):
        text = entry.get_text().strip()
        if text.isdigit():
            self.go(int(text) - 1)
        self.scrolled.grab_focus()

    # -- zoom ---------------------------------------------------------------------------------
    def _fit_zoom(self, mode):
        if not self.sizes:
            return self.z
        width = max(50, self.scrolled.get_width() - 2 * SPACING - 16)
        height = max(50, self.scrolled.get_height() - 2 * SPACING)
        page = self.sizes[self.current_page()]
        widest = max(w for w, _h in self.sizes[:500])
        if mode == "page":
            return min(width / page[0], height / page[1])
        return width / widest

    def _apply_fit(self):
        if self.fit_mode:
            self.set_zoom(self._fit_zoom(self.fit_mode), fit=self.fit_mode)

    def set_zoom(self, zoom: float, fit=None) -> None:
        zoom = max(ZOOM_STEPS[0], min(ZOOM_STEPS[-1], zoom))
        self.fit_mode = fit
        if abs(zoom - self.z) < 1e-4 and self.pages and self.pages[0].get_width() > 0:
            self._show_zoom()
            return
        adj = self.scrolled.get_vadjustment()
        index = self.current_page()
        offset = (adj.get_value() - self._page_top(index)) / self.z if self.pages else 0
        self.z = zoom
        for page in self.pages:
            page.queue_resize()
            page.search_rects = page.search_rects  # redrawn at the new zoom
        self._show_zoom()

        def restore():
            adj.set_value(max(0.0, self._page_top(index) + offset * self.z))
            self._schedule_update()
            return False

        GLib.idle_add(restore, priority=GLib.PRIORITY_LOW)

    def _show_zoom(self):
        self.zoom_label.set_label(f"{round(self.z * 100)}%")

    def zoom_step(self, direction: int) -> int:
        if direction == 0:
            self.set_zoom(1.0)
        elif direction > 0:
            self.set_zoom(next((z for z in ZOOM_STEPS if z > self.z + 1e-3), ZOOM_STEPS[-1]))
        else:
            self.set_zoom(next((z for z in reversed(ZOOM_STEPS) if z < self.z - 1e-3), ZOOM_STEPS[0]))
        return round(self.z * 100)

    # DocumentPage zooms views that have zoom() and fit() (as images do).
    def zoom(self, direction: int) -> int:
        return self.zoom_step(direction)

    def fit(self, mode="width") -> None:
        self.set_zoom(self._fit_zoom(mode), fit=mode)

    def _on_width(self, adj):
        width = self.scrolled.get_width()
        if self.fit_mode and self.pages and abs(width - self._last_width) > 2:
            self._last_width = width
            GLib.idle_add(lambda: (self._apply_fit(), False)[1])

    # -- rendering ------------------------------------------------------------------------
    def _on_scroll(self, _adj):
        self._schedule_update()
        page = self.current_page()
        if not self.page_entry.has_focus():
            self.page_entry.set_text(str(page + 1))
        row = self.thumbs.get_row_at_index(page)
        if row is not None and self.thumbs.get_selected_row() is not row:
            self.thumbs.select_row(row)
        self.emit("position-changed")

    def _schedule_update(self):
        if not self._update_id:
            self._update_id = GLib.idle_add(self._update)

    def _visible(self):
        adj = self.scrolled.get_vadjustment()
        top, bottom = adj.get_value(), adj.get_value() + adj.get_page_size()
        out = []
        for i, y in enumerate(self._tops()):
            h = self.sizes[i][1] * self.z
            if y + h >= top - 200 and y <= bottom + 200:
                out.append(i)
            elif y > bottom + 200:
                break
        return out

    def _update(self):
        self._update_id = 0
        if not self.pages:
            return False
        visible = self._visible()
        scale_factor = self.get_scale_factor() or 1
        for i in visible:
            page = self.pages[i]
            w, h = self.sizes[i]
            scale = self.z * scale_factor
            if w * h * scale * scale > MAX_RENDER_PIXELS:
                scale = (MAX_RENDER_PIXELS / (w * h)) ** 0.5
            if abs(page.texture_scale - scale) > 1e-3 and abs(page.pending - scale) > 1e-3:
                page.pending = scale
                self.engine.submit(lambda i=i, s=scale: (i, s, self.engine.render(i, s)), self._rendered,
                                   lambda e: None)
        kept = [p for p in self.pages if p.texture is not None]
        if len(kept) > MAX_TEXTURES:
            near = set(range(max(0, visible[0] - 3), visible[-1] + 4)) if visible else set()
            for page in kept:
                if page.index not in near:
                    page.texture = None
                    page.texture_scale = 0.0
                    page.queue_draw()
        return False

    def _texture(self, rendered):
        w, h, stride, data, fmt, channels = rendered
        memory = {("BGR", 3): Gdk.MemoryFormat.B8G8R8, ("BGRA", 4): Gdk.MemoryFormat.B8G8R8A8,
                  ("BGRX", 4): Gdk.MemoryFormat.B8G8R8A8, ("L", 1): None}.get((fmt, channels))
        if memory is None:
            return None
        return Gdk.MemoryTexture.new(w, h, memory, GLib.Bytes.new(data), stride)

    def _rendered(self, result):
        index, scale, rendered = result
        page = self.pages[index]
        if abs(page.pending - scale) > 1e-3:
            return  # a newer render is on its way
        page.pending = 0.0
        texture = self._texture(rendered)
        if texture is None:
            return
        w, h = self.sizes[index]
        if (rendered[0] > rendered[1]) != (w > h) and abs(w - h) > 1:  # a rotated page beyond the first ones
            self.sizes[index] = (h, w)
            page.queue_resize()
        page.texture = texture
        page.texture_scale = scale
        page.queue_draw()

    # -- thumbnails -----------------------------------------------------------------------
    def _on_thumbs_toggled(self, button):
        self.thumbs_revealer.set_reveal_child(button.get_active())
        if button.get_active() and self.thumbs.get_row_at_index(0) is None and self.pages:
            for i, (w, h) in enumerate(self.sizes):
                picture = Gtk.Picture(content_fit=Gtk.ContentFit.CONTAIN, can_shrink=True,
                                      width_request=120, height_request=int(120 * h / max(1, w)))
                box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=4, margin_bottom=4)
                box.append(picture)
                number = Gtk.Label(label=str(i + 1))
                number.add_css_class("caption")
                box.append(number)
                row = Gtk.ListBoxRow(child=box)
                row._picture = picture
                row._rendering = False
                self.thumbs.append(row)
            GLib.idle_add(lambda: (self._render_thumbs(), False)[1])

    def _render_thumbs(self):
        if not self.thumbs_revealer.get_reveal_child():
            return
        adj = self.thumbs_scrolled.get_vadjustment()
        top, bottom = adj.get_value() - 300, adj.get_value() + adj.get_page_size() + 300
        y = 0
        index = 0
        while True:
            row = self.thumbs.get_row_at_index(index)
            if row is None:
                break
            w, h = self.sizes[index]
            row_h = int(120 * h / max(1, w)) + 30
            if y + row_h >= top and y <= bottom and not row._rendering:
                row._rendering = True
                scale = 120 * (self.get_scale_factor() or 1) / max(1, w)
                self.engine.submit(lambda i=index, s=scale: (i, self.engine.render(i, s)),
                                   lambda r: self._thumb_done(*r), lambda e: None)
            if y > bottom:
                break
            y += row_h
            index += 1

    def _thumb_done(self, index, rendered):
        row = self.thumbs.get_row_at_index(index)
        texture = self._texture(rendered)
        if row is not None and texture is not None:
            row._picture.set_paintable(texture)

    # -- find -------------------------------------------------------------------------------
    def find(self, needle: str, forward: bool, on_result) -> None:
        if not self.pages:
            on_result(False)
            return
        same = self._search is not None and self._search[0] == needle
        start = self._search[1] if same else self.current_page()
        after = self._search[2] if same else -1

        def done(hit):
            for page in self.pages:
                if page.search_rects:
                    page.search_rects = []
                    page.queue_draw()
            if hit is None:
                self._search = (needle, start, after)
                on_result(False)
                return
            index, char, rects = hit
            self._search = (needle, index, char)
            page = self.pages[index]
            page.search_rects = rects
            page.queue_draw()
            if rects:
                self.go(index, rects[0][1], align=0.3)
            on_result(True)

        self.engine.submit(lambda: self.engine.search(needle, start, forward, after), done, lambda e: on_result(False))

    # -- selection and links -------------------------------------------------------------
    def _page_at(self, x, y):
        """(page index, x, y in points) under a point of pages_box."""
        for page in self.pages:
            ok, bounds = page.compute_bounds(self.pages_box)
            if not ok:
                continue
            ox, oy = bounds.get_x(), bounds.get_y()
            if ox <= x <= ox + bounds.get_width() and oy <= y <= oy + bounds.get_height():
                return page.index, (x - ox) / self.z, (y - oy) / self.z
        return None

    def _clear_selection(self):
        self._selection_text = ""
        for page in self.pages:
            if page.select_rects:
                page.select_rects = []
                page.queue_draw()

    def _on_drag_begin(self, gesture, x, y):
        self._drag_start = (x, y)
        self._selecting = False
        self.scrolled.grab_focus()

    def _on_drag_update(self, gesture, dx, dy):
        if abs(dx) + abs(dy) < 4:
            return
        self._selecting = True
        x0, y0 = self._drag_start
        start = self._page_at(x0, y0)
        end = self._page_at(x0 + dx, y0 + dy)
        if start is None or end is None or start[0] != end[0]:
            return
        self._request_selection(start[0], start[1], start[2], end[1], end[2])

    def _request_selection(self, index, x0, y0, x1, y1):
        if self._select_busy:
            self._select_next = (index, x0, y0, x1, y1)
            return
        self._select_busy = True

        def done(result):
            self._select_busy = False
            text, rects = result
            self._clear_selection()
            self._selection_text = text
            self.pages[index].select_rects = rects
            self.pages[index].queue_draw()
            if self._select_next is not None:
                args, self._select_next = self._select_next, None
                self._request_selection(*args)

        def failed(_e):
            self._select_busy = False

        self.engine.submit(lambda: self.engine.select(index, x0, y0, x1, y1), done, failed)

    def _on_drag_end(self, gesture, dx, dy):
        if self._selecting:
            return
        self._clear_selection()
        x0, y0 = self._drag_start
        hit = self._page_at(x0, y0)
        if hit is None:
            return
        self.engine.submit(lambda: self.engine.link_at(*hit), self._follow, lambda e: None)

    def _follow(self, link):
        if link is None:
            return
        kind, target = link
        if kind == "page":
            self.go(target)
        else:
            from odv.views.markdown import confirm_external

            confirm_external(self.get_root(), target)

    def copy_selection(self) -> str:
        if self._selection_text:
            self.get_clipboard().set(self._selection_text)
        return self._selection_text

    def _on_key(self, _ctrl, keyval, _code, state):
        ctrl = state & Gdk.ModifierType.CONTROL_MASK
        if ctrl and keyval in (Gdk.KEY_c, Gdk.KEY_C, Gdk.KEY_Insert) and self._selection_text:
            self.copy_selection()
            return True
        if not ctrl and keyval in (Gdk.KEY_Home, Gdk.KEY_End) and not self.page_entry.has_focus():
            self.go(0 if keyval == Gdk.KEY_Home else len(self.pages) - 1)
            return True
        return False

    # -- printing ----------------------------------------------------------------------------
    def page_count(self) -> int:
        return len(self.pages)

    def shutdown(self):
        self.engine.close()
