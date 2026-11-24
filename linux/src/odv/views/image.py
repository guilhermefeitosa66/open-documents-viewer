"""The image view: fit or zoom, drag to pan, animated GIF/WebP, an info
strip, and Left/Right to the previous/next image of the folder.

Decoding happens in a thread with GdkPixbuf, so every loader installed on
the system works (WebP, HEIF, AVIF… when present); SVG is rendered again
at each zoom level so it stays sharp.
"""

import os
import threading

from gi.repository import Gdk, GdkPixbuf, GLib, GObject, Gtk

from odv.i18n import _
from odv.textio import human_size

ZOOMS = (0.1, 0.15, 0.25, 0.33, 0.5, 0.67, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0)


def supported_extensions() -> set[str]:
    return {"." + ext for fmt in GdkPixbuf.Pixbuf.get_formats() for ext in fmt.get_extensions()}


def is_svg(path: str) -> bool:
    return path.lower().endswith((".svg", ".svgz"))


def siblings(path: str) -> list[str]:
    folder = os.path.dirname(path)
    exts = supported_extensions()
    try:
        names = sorted(os.listdir(folder), key=str.casefold)
    except OSError:
        return [path]
    return [os.path.join(folder, n) for n in names if os.path.splitext(n)[1].lower() in exts]


class ImageView(Gtk.Overlay):
    __gsignals__ = {
        "loaded": (GObject.SignalFlags.RUN_FIRST, None, ()),
        "failed": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
        "navigate": (GObject.SignalFlags.RUN_FIRST, None, (str,)),
    }

    def __init__(self, path: str):
        super().__init__(vexpand=True, hexpand=True, focusable=True)
        self.path = path
        self.scale = None  # None = fit to the window
        self.width = self.height = 0
        self.format_name = ""
        self._anim_iter = None
        self._anim_timer = 0
        self._closed = False
        self._svg_job = 0

        self.picture = Gtk.Picture(can_shrink=True, content_fit=Gtk.ContentFit.SCALE_DOWN,
                                   halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
        self.scroller = Gtk.ScrolledWindow(child=self.picture, vexpand=True, hexpand=True)
        self.scroller.add_css_class("image-canvas")
        self.set_child(self.scroller)

        self.info = Gtk.Label(halign=Gtk.Align.START, valign=Gtk.Align.END, margin_start=12, margin_bottom=12)
        self.info.add_css_class("osd")
        self.info.add_css_class("image-info")
        self.add_overlay(self.info)

        bar = Gtk.Box(halign=Gtk.Align.END, valign=Gtk.Align.END, margin_end=12, margin_bottom=12, spacing=4)
        bar.add_css_class("osd")
        bar.add_css_class("image-toolbar")
        for icon, tip, cb in (("go-previous-symbolic", _("Previous image (Left)"), lambda b: self.step(-1)),
                              ("zoom-out-symbolic", _("Zoom out"), lambda b: self.zoom(-1)),
                              ("zoom-fit-best-symbolic", _("Fit to window (F)"), lambda b: self.fit()),
                              ("zoom-original-symbolic", _("Actual size (1)"), lambda b: self.set_scale(1.0)),
                              ("zoom-in-symbolic", _("Zoom in"), lambda b: self.zoom(1)),
                              ("go-next-symbolic", _("Next image (Right)"), lambda b: self.step(1))):
            button = Gtk.Button(icon_name=icon, tooltip_text=tip)
            button.add_css_class("flat")
            button.connect("clicked", cb)
            bar.append(button)
        self.add_overlay(bar)

        keys = Gtk.EventControllerKey()
        keys.connect("key-pressed", self._on_key)
        self.add_controller(keys)
        drag = Gtk.GestureDrag()
        drag.connect("drag-begin", self._drag_begin)
        drag.connect("drag-update", self._drag_update)
        self.scroller.add_controller(drag)
        click = Gtk.GestureClick()
        click.connect("pressed", lambda *a: self.grab_focus())
        self.add_controller(click)
        self._drag_origin = (0.0, 0.0)
        self._load()

    # -- loading ----------------------------------------------------------------
    def _load(self):
        path = self.path

        def work():
            try:
                info = GdkPixbuf.Pixbuf.get_file_info(path)
                anim = GdkPixbuf.PixbufAnimation.new_from_file(path)
            except GLib.Error as e:
                message = e.message  # `e` is unbound once the except block ends
                GLib.idle_add(lambda: (self.emit("failed", message), False)[1])
                return
            GLib.idle_add(lambda: (self._show(anim, info), False)[1])

        threading.Thread(target=work, daemon=True).start()

    def _show(self, anim, info):
        if self._closed:
            return
        fmt = info[0] if info and info[0] else None
        self.format_name = fmt.get_name().upper() if fmt else ""
        self.width, self.height = anim.get_width(), anim.get_height()
        if anim.is_static_image():
            self.picture.set_paintable(Gdk.Texture.new_for_pixbuf(anim.get_static_image()))
        else:
            self._anim_iter = anim.get_iter(None)
            self._next_frame()
        self._update_info()
        self.emit("loaded")

    def _next_frame(self):
        self._anim_timer = 0
        if self._closed or self._anim_iter is None:
            return False
        self._anim_iter.advance(None)
        self.picture.set_paintable(Gdk.Texture.new_for_pixbuf(self._anim_iter.get_pixbuf()))
        delay = self._anim_iter.get_delay_time()
        if delay >= 0:
            self._anim_timer = GLib.timeout_add(max(delay, 20), self._next_frame)
        return False

    # -- zoom -------------------------------------------------------------------
    def current_scale(self) -> float:
        if self.scale is not None:
            return self.scale
        if not self.width:
            return 1.0
        w, h = self.scroller.get_width(), self.scroller.get_height()
        if w <= 0 or h <= 0:
            return 1.0
        return min(1.0, w / self.width, h / self.height)

    def fit(self):
        self.scale = None
        self.picture.set_size_request(-1, -1)
        self.picture.set_can_shrink(True)
        self.picture.set_content_fit(Gtk.ContentFit.SCALE_DOWN)
        self.scroller.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.NEVER)
        self._update_info()

    def set_scale(self, scale: float):
        if not self.width:
            return
        self.scale = max(ZOOMS[0], min(ZOOMS[-1], scale))
        self.picture.set_can_shrink(False)
        self.picture.set_content_fit(Gtk.ContentFit.FILL)
        self.picture.set_size_request(round(self.width * self.scale), round(self.height * self.scale))
        self.scroller.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.AUTOMATIC)
        if is_svg(self.path):
            self._render_svg(self.scale)
        self._update_info()

    def zoom(self, direction: int) -> int:
        if direction == 0:
            self.fit()
        else:
            current = self.current_scale()
            if direction > 0:
                target = next((z for z in ZOOMS if z > current + 1e-6), ZOOMS[-1])
            else:
                target = next((z for z in reversed(ZOOMS) if z < current - 1e-6), ZOOMS[0])
            self.set_scale(target)
        return round(self.current_scale() * 100)

    def _render_svg(self, scale):
        self._svg_job += 1
        job, path = self._svg_job, self.path
        w, h = round(self.width * scale), round(self.height * scale)
        if w * h > 64_000_000:
            return

        def work():
            try:
                pixbuf = GdkPixbuf.Pixbuf.new_from_file_at_scale(path, w, h, True)
            except GLib.Error:
                return
            GLib.idle_add(lambda: (job == self._svg_job and not self._closed
                                   and self.picture.set_paintable(Gdk.Texture.new_for_pixbuf(pixbuf)), False)[1])

        threading.Thread(target=work, daemon=True).start()

    # -- info, keys, panning ------------------------------------------------------
    def _update_info(self):
        try:
            size = human_size(os.path.getsize(self.path))
        except OSError:
            size = ""
        zoom = _("Fit") if self.scale is None else f"{round(self.scale * 100)}%"
        parts = [f"{self.width} × {self.height}", self.format_name, size, zoom]
        self.info.set_label("  ·  ".join(p for p in parts if p))

    def step(self, direction: int):
        files = siblings(self.path)
        if self.path not in files or len(files) < 2:
            return
        target = files[(files.index(self.path) + direction) % len(files)]
        self.emit("navigate", target)

    def _on_key(self, _ctrl, keyval, _code, state):
        if state & (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.ALT_MASK):
            return False
        if keyval in (Gdk.KEY_Left, Gdk.KEY_Page_Up):
            self.step(-1)
        elif keyval in (Gdk.KEY_Right, Gdk.KEY_Page_Down, Gdk.KEY_space):
            self.step(1)
        elif keyval in (Gdk.KEY_f, Gdk.KEY_F):
            self.fit()
        elif keyval == Gdk.KEY_1:
            self.set_scale(1.0)
        elif keyval in (Gdk.KEY_plus, Gdk.KEY_equal, Gdk.KEY_KP_Add):
            self.zoom(1)
        elif keyval in (Gdk.KEY_minus, Gdk.KEY_KP_Subtract):
            self.zoom(-1)
        else:
            return False
        return True

    def _drag_begin(self, _gesture, _x, _y):
        self._drag_origin = (self.scroller.get_hadjustment().get_value(),
                             self.scroller.get_vadjustment().get_value())

    def _drag_update(self, _gesture, dx, dy):
        if self.scale is None:
            return
        self.scroller.get_hadjustment().set_value(self._drag_origin[0] - dx)
        self.scroller.get_vadjustment().set_value(self._drag_origin[1] - dy)

    def shutdown(self):
        self._closed = True
        if self._anim_timer:
            GLib.source_remove(self._anim_timer)
            self._anim_timer = 0
