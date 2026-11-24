"""The formatted Markdown view: native widgets, no web engine.

The document is parsed off the main thread (odv.mdrender) and arrives as a
list of insert operations, applied here under a time budget per idle step.
"""

import bisect
import os
from urllib.parse import unquote, urlparse

from gi.repository import Adw, Gdk, GLib, Gtk, Pango

from odv import theme

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
        clamp = Adw.ClampScrollable(maximum_size=MAX_WIDTH, tightening_threshold=MAX_WIDTH, child=self.view)
        self.set_child(clamp)

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
                    b.insert_with_tags_by_name(end, op[1], *op[2])
                elif op[0] == "hr":
                    self._add_child(end, self._hr())
                elif op[0] == "table":
                    self._add_child(end, self._table(op[1]))
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

    def _add_child(self, it, widget):
        anchor = self.buffer.create_child_anchor(it)
        self.view.add_child_at_anchor(widget, anchor)

    def _hr(self):
        sep = Gtk.Box(width_request=MAX_WIDTH - 96, height_request=1)
        sep.add_css_class("md-hr")
        return sep

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
        hand = self._link_at(x, y) is not None
        if hand != self._hand:
            self._hand = hand
            self.view.set_cursor(Gdk.Cursor.new_from_name("pointer" if hand else "text"))

    def _on_click(self, gesture, n_press, x, y):
        if n_press != 1 or self.buffer.get_has_selection():
            return
        link = self._link_at(x, y)
        if link:
            self.follow(link.href)

    def follow(self, href: str) -> None:
        if not href:
            return
        if href.startswith("#"):
            self.scroll_to_heading(unquote(href[1:]))
            return
        parsed = urlparse(href)
        if parsed.scheme in ("", "file"):
            target = unquote(parsed.path)
            if not os.path.isabs(target):
                target = os.path.join(os.path.dirname(self.path), target)
            if os.path.exists(target):
                self._open_path(os.path.normpath(target))
                return
        if parsed.scheme:
            Gtk.UriLauncher.new(href).launch(self.get_root(), None, None, None)

    def scroll_to_heading(self, slug: str) -> None:
        if not self._doc:
            return
        off = self._doc.headings.get(slug.lower())
        if off is None:
            return
        it = self.buffer.get_iter_at_offset(off)
        self.view.scroll_to_iter(it, 0.0, True, 0.0, 0.0)

    def shutdown(self):
        self._cancelled = True
        theme.remove_listener(self._on_theme)
