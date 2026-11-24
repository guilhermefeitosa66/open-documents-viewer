"""The presentation view: the current slide drawn with Cairo and Pango,
slide thumbnails, navigation, speaker notes and a full-screen
presentation mode (F5)."""

import threading

from gi.repository import Gdk, GdkPixbuf, GLib, GObject, Gtk, Pango, PangoCairo

from odv.i18n import _

BULLET_INDENT = 342900  # 0.375 inch per level


def _rgb(hex_color):
    return tuple(int(hex_color[i:i + 2], 16) / 255 for i in (0, 2, 4))


def _dark(hex_color) -> bool:
    r, g, b = _rgb(hex_color or "FFFFFF")
    return 0.2126 * r + 0.7152 * g + 0.0722 * b < 0.45


class Painter:
    """Draws slides; keeps decoded images between draws."""

    def __init__(self, pres):
        self.pres = pres
        self._pixbufs = {}

    def pixbuf(self, data):
        key = id(data)
        if key not in self._pixbufs:
            try:
                loader = GdkPixbuf.PixbufLoader()
                loader.write(data)
                loader.close()
                self._pixbufs[key] = loader.get_pixbuf()
            except GLib.Error:
                self._pixbufs[key] = None
        return self._pixbufs[key]

    def draw(self, cr, slide, width, height, backdrop=None):
        pres = self.pres
        scale = min(width / pres.width, height / pres.height)
        sw, sh = pres.width * scale, pres.height * scale
        ox, oy = (width - sw) / 2, (height - sh) / 2
        if backdrop:
            cr.set_source_rgb(*backdrop)
            cr.paint()
        cr.save()
        cr.translate(ox, oy)
        cr.rectangle(0, 0, sw, sh)
        cr.clip()
        cr.set_source_rgb(*_rgb(slide.background or "FFFFFF"))
        cr.paint()
        if slide.background_image:
            self._image(cr, slide.background_image, 0, 0, sw, sh)
        default_text = "FFFFFF" if _dark(slide.background) and not slide.background_image else "000000"
        for shape in slide.shapes:
            self._shape(cr, shape, scale, default_text)
        cr.restore()

    def _image(self, cr, data, x, y, w, h):
        pb = self.pixbuf(data)
        if pb is None or w <= 0 or h <= 0:
            return
        cr.save()
        cr.translate(x, y)
        cr.scale(w / pb.get_width(), h / pb.get_height())
        Gdk.cairo_set_source_pixbuf(cr, pb, 0, 0)
        cr.paint()
        cr.restore()

    def _shape(self, cr, s, k, default_text):
        x, y, w, h = s.x * k, s.y * k, s.w * k, s.h * k
        cr.save()
        if s.rotation:
            cr.translate(x + w / 2, y + h / 2)
            cr.rotate(s.rotation * 3.14159265 / 180)
            cr.translate(-(x + w / 2), -(y + h / 2))
        if s.kind == "image":
            if s.image:
                self._image(cr, s.image, x, y, w, h)
        elif s.kind == "line":
            cr.move_to(x, y)
            cr.line_to(x + w, y + h)
            cr.set_source_rgb(*_rgb(s.line or "404040"))
            cr.set_line_width(max(0.5, s.line_width * 12700 * k))
            cr.stroke()
        elif s.kind == "table":
            self._table(cr, s, k, default_text)
        else:
            self._path(cr, s.kind, x, y, w, h)
            if s.fill:
                cr.set_source_rgb(*_rgb(s.fill))
                cr.fill_preserve()
            if s.line:
                cr.set_source_rgb(*_rgb(s.line))
                cr.set_line_width(max(0.5, s.line_width * 12700 * k))
                cr.stroke_preserve()
            cr.new_path()
            if s.paragraphs:
                text_color = default_text if not s.fill else ("FFFFFF" if _dark(s.fill) else "000000")
                self._text(cr, s.paragraphs, x, y, w, h, s.insets, s.anchor, k, text_color)
        cr.restore()

    @staticmethod
    def _path(cr, kind, x, y, w, h):
        if kind == "ellipse" and w > 0 and h > 0:
            cr.save()
            cr.translate(x + w / 2, y + h / 2)
            cr.scale(w / 2, h / 2)
            cr.arc(0, 0, 1, 0, 6.2831853)
            cr.restore()
        elif kind == "roundRect":
            r = min(w, h) * 0.16
            cr.new_sub_path()
            cr.arc(x + w - r, y + r, r, -1.5708, 0)
            cr.arc(x + w - r, y + h - r, r, 0, 1.5708)
            cr.arc(x + r, y + h - r, r, 1.5708, 3.14159)
            cr.arc(x + r, y + r, r, 3.14159, 4.71239)
            cr.close_path()
        else:
            cr.rectangle(x, y, w, h)

    def _layouts(self, cr, paragraphs, width, k, text_color):
        out = []
        for p in paragraphs:
            layout = PangoCairo.create_layout(cr)
            PangoCairo.context_set_resolution(layout.get_context(), 72)  # 1 pt == 1 px at this scale
            indent = p.level * BULLET_INDENT * k
            layout.set_width(max(1, int((width - indent) * Pango.SCALE)))
            layout.set_wrap(Pango.WrapMode.WORD_CHAR)
            layout.set_alignment({"center": Pango.Alignment.CENTER, "right": Pango.Alignment.RIGHT}
                                 .get(p.align, Pango.Alignment.LEFT))
            if p.align == "justify":
                layout.set_justify(True)
            parts = []
            size = p.runs[0].size if p.runs else 18
            if p.bullet:
                first = p.runs[0] if p.runs else None
                color = (first.color if first else None) or text_color
                family = f' font_family="{GLib.markup_escape_text(first.font)}"' if first and first.font else ""
                parts.append(f'<span size="{int(size * 12700 * k * 1024)}" foreground="#{color}"{family}>'
                             f'{GLib.markup_escape_text(p.bullet)} </span>')
            for r in p.runs:
                attrs = [f'size="{max(1, int(r.size * 12700 * k * 1024))}"',
                         f'foreground="#{r.color or text_color}"']
                if r.bold:
                    attrs.append('weight="bold"')
                if r.italic:
                    attrs.append('style="italic"')
                if r.underline:
                    attrs.append('underline="single"')
                if r.strike:
                    attrs.append('strikethrough="true"')
                attrs.append(f'font_family="{GLib.markup_escape_text(r.font or "Sans")}"')
                parts.append(f"<span {' '.join(attrs)}>{GLib.markup_escape_text(r.text)}</span>")
            layout.set_markup("".join(parts) or " ", -1)
            out.append((layout, indent))
        return out

    def _text(self, cr, paragraphs, x, y, w, h, insets, anchor, k, text_color):
        li, ti, ri, bi = (v * k for v in insets)
        width = w - li - ri
        layouts = self._layouts(cr, paragraphs, width, k, text_color)
        total = sum(layout.get_pixel_size()[1] for layout, _i in layouts)
        top = y + ti
        if anchor == "middle":
            top = y + (h - total) / 2
        elif anchor == "bottom":
            top = y + h - bi - total
        for layout, indent in layouts:
            cr.move_to(x + li + indent, top)
            PangoCairo.show_layout(cr, layout)
            top += layout.get_pixel_size()[1]

    def _table(self, cr, s, k, default_text):
        rows = s.rows
        if not rows:
            return
        x, y, w, h = s.x * k, s.y * k, s.w * k, s.h * k
        ncols = max(len(r) for r in rows)
        widths = [cw * k for cw in s.col_widths[:ncols]] or [w / ncols] * ncols
        while len(widths) < ncols:
            widths.append(w / ncols)
        row_h = h / len(rows)
        cr.set_line_width(max(0.5, 9525 * k))
        for ri, row in enumerate(rows):
            cx = x
            for ci in range(ncols):
                cw = widths[ci]
                if ri == 0:
                    cr.set_source_rgb(*_rgb("D9D9D9"))
                    cr.rectangle(cx, y, cw, row_h)
                    cr.fill()
                cr.set_source_rgb(*_rgb("A6A6A6"))
                cr.rectangle(cx, y + ri * row_h, cw, row_h)
                cr.stroke()
                if ci < len(row) and row[ci]:
                    self._text(cr, row[ci], cx, y + ri * row_h, cw, row_h, (45720, 22860, 45720, 22860),
                               "top", k, default_text)
                cx += cw


class SlideCanvas(Gtk.DrawingArea):
    def __init__(self, painter, backdrop=(0.15, 0.15, 0.15)):
        super().__init__(hexpand=True, vexpand=True)
        self.painter = painter
        self.slide = None
        self.backdrop = backdrop
        self.set_draw_func(self._draw)

    def show(self, slide):
        self.slide = slide
        self.queue_draw()

    def _draw(self, _area, cr, w, h):
        if self.slide is not None:
            self.painter.draw(cr, self.slide, w, h, self.backdrop)


class SlidesView(Gtk.Box):
    __gsignals__ = {
        "loaded": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "failed": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
        "position-changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self, path, opener):
        super().__init__(vexpand=True, hexpand=True)
        self.path = path
        self.pres = None
        self.painter = None
        self.position = 0
        self.visible = []  # indices of the slides shown (hidden slides are skipped)
        self.thumbs = Gtk.ListBox(selection_mode=Gtk.SelectionMode.SINGLE)
        self.thumbs.add_css_class("navigation-sidebar")
        self.thumbs.connect("row-selected", self._on_thumb)
        side = Gtk.ScrolledWindow(child=self.thumbs, width_request=190, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.append(side)
        self.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL))
        main = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        self.canvas = SlideCanvas(None)
        main.append(self.canvas)
        bar = Gtk.Box(spacing=6, margin_start=8, margin_end=8, margin_top=4, margin_bottom=4)
        prev = Gtk.Button(icon_name="go-previous-symbolic", tooltip_text=_("Previous slide"))
        prev.connect("clicked", lambda b: self.step(-1))
        nxt = Gtk.Button(icon_name="go-next-symbolic", tooltip_text=_("Next slide"))
        nxt.connect("clicked", lambda b: self.step(1))
        self.counter = Gtk.Label(width_chars=9)
        self.notes_button = Gtk.ToggleButton(label=_("Notes"))
        present = Gtk.Button(icon_name="media-playback-start-symbolic", tooltip_text=_("Present (F5)"))
        present.connect("clicked", lambda b: self.present())
        for widget in (prev, self.counter, nxt):
            bar.append(widget)
        bar.append(Gtk.Box(hexpand=True))
        bar.append(self.notes_button)
        bar.append(present)
        main.append(Gtk.Separator())
        main.append(bar)
        self.notes = Gtk.Label(xalign=0.0, yalign=0.0, wrap=True, selectable=True, margin_start=12, margin_end=12,
                               margin_top=8, margin_bottom=8)
        notes_scroll = Gtk.ScrolledWindow(child=self.notes, height_request=120)
        self.notes_revealer = Gtk.Revealer(child=notes_scroll)
        self.notes_button.bind_property("active", self.notes_revealer, "reveal-child",
                                        GObject.BindingFlags.SYNC_CREATE)
        main.append(self.notes_revealer)
        self.append(main)
        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.add_controller(keys)
        self.set_focusable(True)

        def work():
            try:
                pres = opener(path)
            except Exception as e:  # noqa: BLE001 - shown in the UI
                message = str(e)
                GLib.idle_add(lambda: (self.emit("failed", message), False)[1])
                return
            GLib.idle_add(lambda: (self._loaded(pres), False)[1])

        threading.Thread(target=work, daemon=True).start()

    def _loaded(self, pres):
        self.pres = pres
        self.painter = Painter(pres)
        self.canvas.painter = self.painter
        self.visible = [i for i, s in enumerate(pres.slides) if not s.hidden] or list(range(len(pres.slides)))
        ratio = pres.height / pres.width if pres.width else 0.75
        for n, i in enumerate(self.visible, 1):
            area = SlideCanvas(self.painter, backdrop=None)
            area.set_content_width(150)
            area.set_content_height(int(150 * ratio))
            area.show(pres.slides[i])
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=4, margin_bottom=4)
            number = Gtk.Label(label=str(n), xalign=0.0)
            number.add_css_class("caption")
            box.append(number)
            box.append(area)
            row = Gtk.ListBoxRow(child=box)
            row._index = i
            self.thumbs.append(row)
        if not self.visible:
            self.emit("failed", _("This presentation has no slides."))
            return
        self.go(0)
        self.emit("loaded")

    # -- navigation -------------------------------------------------------------------
    def go(self, position: int) -> None:
        if not self.visible:
            return
        position = max(0, min(len(self.visible) - 1, position))
        self.position = position
        slide = self.pres.slides[self.visible[position]]
        self.canvas.show(slide)
        self.counter.set_label(f"{position + 1} / {len(self.visible)}")
        self.notes.set_label(slide.notes or _("No notes for this slide."))
        row = self.thumbs.get_row_at_index(position)
        if row is not None and self.thumbs.get_selected_row() is not row:
            self.thumbs.select_row(row)
        if getattr(self, "_presenter", None) is not None:
            self._presenter.show(slide)
        self.emit("position-changed")

    def step(self, delta: int) -> None:
        self.go(self.position + delta)

    def _on_thumb(self, _lb, row):
        if row is not None and self.visible and self.visible.index(row._index) != self.position:
            self.go(self.visible.index(row._index))

    def _on_key(self, _ctrl, keyval, _code, state):
        if state & (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.ALT_MASK):
            return False
        moves = {Gdk.KEY_Right: 1, Gdk.KEY_Down: 1, Gdk.KEY_Page_Down: 1, Gdk.KEY_space: 1,
                 Gdk.KEY_Left: -1, Gdk.KEY_Up: -1, Gdk.KEY_Page_Up: -1, Gdk.KEY_BackSpace: -1}
        if keyval in moves:
            self.step(moves[keyval])
        elif keyval == Gdk.KEY_Home:
            self.go(0)
        elif keyval == Gdk.KEY_End:
            self.go(len(self.visible) - 1)
        else:
            return False
        return True

    # -- outline, find ----------------------------------------------------------------------
    def outline(self):
        from odv.mdrender import Heading

        return [Heading(1, self.pres.slides[i].title or _("Slide {n}").format(n=n), n - 1, 1)
                for n, i in enumerate(self.visible, 1)]

    def scroll_to_offset(self, offset: int) -> None:
        self.go(offset)

    def top_offset(self) -> int:
        return self.position

    def find(self, needle: str, forward: bool, on_result) -> None:
        needle = needle.casefold()
        if not self.visible or not needle:
            on_result(False)
            return
        n = len(self.visible)
        for k in range(1, n + 1):
            position = (self.position + (k if forward else -k)) % n
            if needle in self.pres.slides[self.visible[position]].text().casefold():
                self.go(position)
                on_result(True)
                return
        on_result(needle in self.pres.slides[self.visible[self.position]].text().casefold())

    # -- presenting --------------------------------------------------------------------------
    def present(self) -> None:
        if self.pres is None or getattr(self, "_presenter", None) is not None:
            return
        window = Gtk.Window(title=_("Presentation"), decorated=False)
        window.add_css_class("presentation")
        canvas = SlideCanvas(self.painter, backdrop=(0, 0, 0))
        window.set_child(canvas)
        canvas.show(self.pres.slides[self.visible[self.position]])
        self._presenter = canvas
        keys = Gtk.EventControllerKey()

        def on_key(_c, keyval, _code, state):
            if keyval in (Gdk.KEY_Escape, Gdk.KEY_q, Gdk.KEY_F5):
                window.close()
                return True
            return self._on_key(_c, keyval, _code, state)

        keys.connect("key-pressed", on_key)
        window.add_controller(keys)
        click = Gtk.GestureClick(button=0)
        click.connect("pressed", lambda g, n, x, y: self.step(-1 if g.get_current_button() == 3 else 1))
        canvas.add_controller(click)

        def closed(_w):
            self._presenter = None
            return False

        window.connect("close-request", closed)
        root = self.get_root()
        if isinstance(root, Gtk.Window):
            window.set_transient_for(root)
        window.fullscreen()
        window.present()
        self._presenter_window = window

    def shutdown(self):
        window = getattr(self, "_presenter_window", None)
        if window is not None:
            window.close()
