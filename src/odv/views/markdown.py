"""The formatted Markdown view: native widgets, no web engine.

The document is parsed off the main thread (odv.mdrender) and arrives as a
list of insert operations, applied here under a time budget per idle step.
"""

import bisect

from gi.repository import Adw, Gdk, GLib, Gtk, Pango

from odv import comfort, settings, theme
from odv.i18n import _

MAX_WIDTH = 820


class MarkdownView(Gtk.ScrolledWindow):
    def __init__(self, path: str, open_path):
        super().__init__(vexpand=True, hexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.path = path
        self._open_path = open_path
        self._doc = None
        self._link_starts: list[int] = []
        self._cancelled = False
        self.buffer = Gtk.TextBuffer(enable_undo=False)
        self.view = Gtk.TextView(
            buffer=self.buffer,
            editable=False,
            cursor_visible=False,
            wrap_mode=Gtk.WrapMode.WORD_CHAR,
            top_margin=36,
            bottom_margin=64,
            left_margin=40,
            right_margin=40,
            pixels_inside_wrap=2,
        )
        self.view.add_css_class("markdown")
        self._make_tags()
        theme.on_change(self._on_theme)
        self.clamp = Adw.ClampScrollable(child=self.view)
        self.set_child(self.clamp)
        self._apply_settings()
        settings.on_change(self._on_setting)

        click = Gtk.GestureClick(button=1)
        click.connect("released", self._on_click)
        self.view.add_controller(click)
        motion = Gtk.EventControllerMotion()
        motion.connect("motion", self._on_motion)
        self.view.add_controller(motion)
        self._hand = False

    # -- tags -------------------------------------------------------------
    def _make_tags(self):
        b = self.buffer
        b.create_tag("p", pixels_below_lines=12)
        b.create_tag("li", pixels_below_lines=4)
        b.create_tag("gap", scale=0.5)
        for level, (scale, above) in enumerate(((2.0, 8), (1.55, 22), (1.3, 18), (1.15, 14), (1.0, 12), (0.92, 12)), 1):
            b.create_tag(f"h{level}", scale=scale, weight=800 if level == 1 else 700,
                         pixels_above_lines=above, pixels_below_lines=10)
        for n in range(1, 9):
            b.create_tag(f"ind{n}", left_margin=40 + 26 * n, indent=-18)
        b.create_tag("quote", left_margin=64, style=Pango.Style.ITALIC)
        b.create_tag("codeblock", family="monospace", scale=0.92, left_margin=56, right_margin=16,
                     pixels_above_lines=0, pixels_below_lines=0, wrap_mode=Gtk.WrapMode.CHAR)
        b.create_tag("code", family="monospace", scale=0.92)
        b.create_tag("bold", weight=700)
        b.create_tag("italic", style=Pango.Style.ITALIC)
        b.create_tag("strike", strikethrough=True)
        b.create_tag("underline", underline=Pango.Underline.SINGLE)
        b.create_tag("link", underline=Pango.Underline.SINGLE)
        b.create_tag("marker", weight=700)
        b.create_tag("quotebar", weight=900)
        b.create_tag("dim")
        self._on_theme(theme.is_dark())

    def _on_theme(self, _dark: bool):
        t = theme.current()
        ui, md = t["ui"], t["md"]
        table = self.buffer.get_tag_table()
        table.lookup("link").set_property("foreground", ui["link"])
        table.lookup("dim").set_property("foreground", t["small_dim"])
        table.lookup("quote").set_property("foreground", t["small_dim"])
        table.lookup("quotebar").set_property("foreground", md["quoteBar"])
        table.lookup("marker").set_property("foreground", md["marker"])
        table.lookup("code").set_property("background", md["codeBg"])
        table.lookup("codeblock").set_property("paragraph-background", md["blockBg"])
        for level in range(1, 7):
            table.lookup(f"h{level}").set_property("foreground", md["heading"])

    # -- content ----------------------------------------------------------
    def set_doc(self, doc, on_first, on_done, budget_ms: float = 8.0) -> None:
        self._doc = doc
        self._link_starts = [link.start for link in doc.links]
        ops = doc.ops
        state = {"i": 0}

        def apply_some(deadline_us):
            b = self.buffer
            while state["i"] < len(ops):
                op = ops[state["i"]]
                state["i"] += 1
                end = b.get_end_iter()
                if op[0] == "text":
                    for tag in op[2]:
                        self._ensure_tag(tag)
                    b.insert_with_tags_by_name(end, op[1], *op[2])
                elif op[0] == "hr":
                    self._add_child(end, self._hr())
                elif op[0] == "table":
                    self._add_child(end, self._table(op[1]))
                elif op[0] == "image":
                    self._add_child(end, self._image(op[1]))
                if GLib.get_monotonic_time() > deadline_us:
                    break
            return state["i"] >= len(ops)

        def step():
            if self._cancelled:
                return False
            if apply_some(GLib.get_monotonic_time() + budget_ms * 1000):
                on_done()
                return False
            return True

        done = apply_some(GLib.get_monotonic_time() + 15 * 1000)
        on_first()
        if done:
            on_done()
        else:
            GLib.idle_add(step, priority=GLib.PRIORITY_LOW)

    def _ensure_tag(self, name: str) -> None:
        """Tags made on demand by richer formats: fg-RRGGBB, bg-RRGGBB, scale-N."""
        table = self.buffer.get_tag_table()
        if table.lookup(name) is not None:
            return
        if name.startswith("fg-"):
            self.buffer.create_tag(name, foreground="#" + name[3:])
        elif name.startswith("bg-"):
            self.buffer.create_tag(name, background="#" + name[3:])
        elif name.startswith("scale-"):
            self.buffer.create_tag(name, scale=int(name[6:]) / 100)
        else:
            self.buffer.create_tag(name)

    def _add_child(self, it, widget):
        anchor = self.buffer.create_child_anchor(it)
        self.view.add_child_at_anchor(widget, anchor)

    def _hr(self):
        sep = Gtk.Box(width_request=MAX_WIDTH - 96, height_request=1)
        sep.add_css_class("md-hr")
        return sep

    def _image(self, source):
        """An image from bytes (notebook outputs) or a local path."""
        from gi.repository import GdkPixbuf

        try:
            if isinstance(source, (bytes, bytearray)):
                loader = GdkPixbuf.PixbufLoader()
                loader.write(bytes(source))
                loader.close()
                pixbuf = loader.get_pixbuf()
            else:
                pixbuf = GdkPixbuf.Pixbuf.new_from_file(source)
        except GLib.Error:
            label = Gtk.Label(label="[image]")
            label.add_css_class("dim-label")
            return label
        width, height = pixbuf.get_width(), pixbuf.get_height()
        limit = MAX_WIDTH - 96
        if width > limit:
            width, height = limit, round(height * limit / width)
        picture = Gtk.Picture(paintable=Gdk.Texture.new_for_pixbuf(pixbuf), can_shrink=True,
                              width_request=width, height_request=height, margin_top=4, margin_bottom=4)
        picture.add_css_class("md-image")
        return picture

    def _table(self, table):
        grid = Gtk.Grid(column_spacing=0, row_spacing=0, margin_top=4, margin_bottom=12)
        grid.add_css_class("md-table")
        ncols = max([len(table.header)] + [len(r) for r in table.rows]) if (table.header or table.rows) else 0
        rows = ([table.header] if table.header else []) + table.rows
        for r, cells in enumerate(rows):
            for c in range(ncols):
                text = cells[c] if c < len(cells) else ""
                align = table.align[c] if c < len(table.align) else "left"
                # Short cells keep one line; only long ones wrap.
                label = Gtk.Label(label=text, selectable=True, wrap=len(text) > 48, max_width_chars=48,
                                  xalign={"right": 1.0, "center": 0.5}.get(align, 0.0), hexpand=True)
                label.set_halign(Gtk.Align.FILL)
                if r == 0 and table.header:
                    label.add_css_class("md-th")
                elif r % 2 == 0:
                    label.add_css_class("md-row-odd")
                grid.attach(label, c, r, 1, 1)
        return grid

    # -- links ------------------------------------------------------------
    def _link_at(self, x, y):
        if not self._doc or not self._doc.links:
            return None
        bx, by = self.view.window_to_buffer_coords(Gtk.TextWindowType.WIDGET, int(x), int(y))
        ok, it = self.view.get_iter_at_location(bx, by)
        if not ok:
            return None
        off = it.get_offset()
        i = bisect.bisect_right(self._link_starts, off) - 1
        if i >= 0:
            link = self._doc.links[i]
            if link.start <= off < link.end:
                return link
        return None

    def _on_motion(self, _ctrl, x, y):
        link = self._link_at(x, y)
        hand = link is not None
        if hand != self._hand:
            self._hand = hand
            self.view.set_cursor(Gdk.Cursor.new_from_name("pointer" if hand else "text"))
        tip = link.href if link else None
        if tip != self.view.get_tooltip_text():
            self.view.set_tooltip_text(tip)

    def _on_click(self, gesture, n_press, x, y):
        if n_press != 1 or self.buffer.get_has_selection():
            return
        link = self._link_at(x, y)
        if link:
            self.follow(link.href)

    def follow(self, href: str) -> None:
        """Follow a link: ``#anchor`` scrolls, a local file opens in a tab (at
        its anchor, if any), anything else asks before opening the browser."""
        from odv.links import resolve

        kind, target, anchor = resolve(href, self.path)
        if kind == "anchor":
            self.scroll_to_heading(anchor)
        elif kind == "local":
            self._open_path(target, anchor=anchor)
        elif kind == "missing":
            root = self.get_root()
            if hasattr(root, "toast"):
                root.toast(_("Link target not found: {target}").format(target=target))
        elif kind == "external":
            confirm_external(self.get_root(), target)

    def scroll_to_heading(self, slug: str) -> bool:
        if not self._doc:
            return False
        off = self._doc.headings.get(slug.lower())
        if off is None:
            return False
        self.scroll_to_offset(off)
        return True

    def scroll_to_offset(self, offset: int) -> None:
        """Scroll so ``offset`` is at the top. Line heights are measured
        lazily, so the scroll is repeated while the layout settles."""
        it = self.buffer.get_iter_at_offset(offset)
        mark = self.buffer.create_mark(None, it, True)
        self.view.scroll_to_mark(mark, 0.0, True, 0.0, 0.0)
        steps = [150, 350, 700]

        def again():
            if self._cancelled or mark.get_deleted():
                return False
            self.view.scroll_to_mark(mark, 0.0, True, 0.0, 0.0)
            if steps:
                GLib.timeout_add(steps.pop(0), again)
            else:
                self.buffer.delete_mark(mark)
            return False

        GLib.timeout_add(steps.pop(0), again)

    def top_offset(self) -> int:
        """Buffer offset at the top of the visible area."""
        rect = self.view.get_visible_rect()
        found = self.view.get_iter_at_location(rect.x, rect.y + 4)
        it = found[1] if isinstance(found, tuple) else found
        return it.get_offset()

    def _on_setting(self, key, _value):
        if key in ("zoom", "prose_width"):
            self._apply_settings()

    def _apply_settings(self):
        comfort.apply_zoom(self.view, "prose")
        width = comfort.prose_width() or 100000
        self.clamp.set_maximum_size(width)
        self.clamp.set_tightening_threshold(min(width, 820))

    def shutdown(self):
        self._cancelled = True
        theme.remove_listener(self._on_theme)
        settings.remove_listener(self._on_setting)


def confirm_external(window, uri: str) -> None:
    """Ask before handing a link to the browser (or mail client)."""
    dialog = Adw.AlertDialog(heading=_("Open this link?"),
                             body=_("It opens outside the app:\n{uri}").format(uri=uri))
    dialog.add_response("cancel", _("Cancel"))
    dialog.add_response("open", _("Open Link"))
    dialog.set_response_appearance("open", Adw.ResponseAppearance.SUGGESTED)
    dialog.set_default_response("open")
    dialog.set_close_response("cancel")

    def done(dlg, result):
        if dlg.choose_finish(result) == "open":
            Gtk.UriLauncher.new(uri).launch(window, None, None, None)

    dialog.choose(window, None, done)
    return dialog
