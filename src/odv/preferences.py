"""Preferences: the theme (with a thumbnail of each) and the background picture."""

import threading

from gi.repository import Adw, Gdk, GLib, Gtk

from odv import settings, theme, themes
from odv.i18n import _

THUMB_W, THUMB_H = 176, 99


def _rgb(hex_color):
    return tuple(int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))


class ThemeThumb(Gtk.Overlay):
    """A small picture of the theme: its background (or colour) with a
    miniature document window drawn in the theme's colours."""

    def __init__(self, theme_id: str):
        super().__init__(width_request=THUMB_W, height_request=THUMB_H, overflow=Gtk.Overflow.HIDDEN)
        self.add_css_class("theme-thumb")
        self.theme_id = theme_id
        self.picture = Gtk.Picture(content_fit=Gtk.ContentFit.COVER, can_shrink=True)
        self.set_child(self.picture)
        area = Gtk.DrawingArea(content_width=THUMB_W, content_height=THUMB_H)
        area.set_draw_func(self._draw)
        self.add_overlay(area)
        path = themes.thumbnail_path(themes.get(theme_id))
        if path:
            def load():
                try:
                    tex = Gdk.Texture.new_from_filename(path)
                except GLib.Error:
                    return
                GLib.idle_add(lambda: (self.picture.set_paintable(tex), False)[1])

            threading.Thread(target=load, daemon=True).start()

    def _draw(self, _area, cr, w, h):
        if self.theme_id == themes.DEFAULT_ID:
            # Light on the left, dark on the right: it follows the system.
            for i, dark in enumerate((False, True)):
                cr.save()
                cr.rectangle(i * w / 2, 0, w / 2, h)
                cr.clip()
                self._window(cr, w, h, themes.resolve(themes.DEFAULT_ID, dark), solid=True)
                cr.restore()
            return
        self._window(cr, w, h, themes.resolve(self.theme_id, False),
                     solid=themes.thumbnail_path(themes.get(self.theme_id)) is None)

    @staticmethod
    def _window(cr, w, h, t, solid):
        ui, md = t["ui"], t["md"]
        if solid:
            cr.set_source_rgb(*_rgb(ui["surface"]))
            cr.paint()
        x, y, ww, hh = w * 0.16, h * 0.2, w * 0.68, h * 0.68
        cr.set_source_rgba(*_rgb(ui["surface"]), 0.9)
        cr.rectangle(x, y, ww, hh * 0.16)
        cr.fill()
        cr.set_source_rgb(*_rgb(ui["bg"]))
        cr.rectangle(x, y + hh * 0.16, ww, hh * 0.84)
        cr.fill()
        cr.set_source_rgb(*_rgb(ui["accent"]))
        cr.rectangle(x + ww * 0.72, y + hh * 0.04, ww * 0.22, hh * 0.08)
        cr.fill()
        lines = [(md["heading"], 0.45, 0.10), (ui["text"], 0.80, 0.06), (ui["text"], 0.66, 0.06),
                 (ui["link"], 0.30, 0.06), (ui["text"], 0.72, 0.06)]
        ly = y + hh * 0.28
        for color, frac, thick in lines:
            cr.set_source_rgb(*_rgb(color))
            cr.rectangle(x + ww * 0.08, ly, ww * frac * 0.84, hh * thick)
            cr.fill()
            ly += hh * (thick + 0.07)


class PreferencesDialog(Adw.PreferencesDialog if hasattr(Adw, "PreferencesDialog") else Adw.PreferencesWindow):
    def __init__(self):
        super().__init__(title=_("Preferences"))
        page = Adw.PreferencesPage(title=_("Appearance"), icon_name="applications-graphics-symbolic")
        self.add(page)

        group = Adw.PreferencesGroup(title=_("Theme"))
        flow = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, column_spacing=12, row_spacing=12,
                           min_children_per_line=2, max_children_per_line=3, homogeneous=True)
        self.cards = {}
        entries = [(themes.DEFAULT_ID, "libre-apps", _("Follows the system"))]
        entries += [(t["id"], t["name"], _("Dark") if t["scheme"] == "dark" else _("Light")) for t in themes.catalogue()]
        for tid, name, sub in entries:
            card = Gtk.Button(tooltip_text=name)
            card.add_css_class("flat")
            card.add_css_class("theme-card")
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            box.append(ThemeThumb(tid))
            label = Gtk.Label(label=name, xalign=0.0)
            label.add_css_class("heading")
            box.append(label)
            caption = Gtk.Label(label=sub, xalign=0.0)
            caption.add_css_class("caption")
            box.append(caption)
            card.set_child(box)
            card.connect("clicked", self._pick, tid)
            card.update_property([Gtk.AccessibleProperty.LABEL], [_("{name} theme").format(name=name)])
            flow.append(card)
            self.cards[tid] = card
        group.add(flow)
        page.add(group)

        bg_group = Adw.PreferencesGroup(title=_("Background"))
        self.bg_row = Adw.SwitchRow(
            title=_("Background picture"),
            subtitle=_("Shown on the start screen and behind the sidebar and tabs. "
                       "Documents always sit on a solid colour."))
        self.bg_row.set_active(theme.background_enabled())
        self.bg_row.connect("notify::active", lambda row, _p: theme.set_background(row.get_active()))
        bg_group.add(self.bg_row)
        page.add(bg_group)

        files_group = Adw.PreferencesGroup(title=_("Files and folders"))
        watch_row = Adw.SwitchRow(
            title=_("Reload files when they change"),
            subtitle=_("Keeps the scroll position. Turn off for files that change all the time."))
        watch_row.set_active(settings.get("watch"))
        watch_row.connect("notify::active", lambda row, _p: settings.set("watch", row.get_active()))
        files_group.add(watch_row)
        page.add(files_group)
        self._sync()

    def _pick(self, _btn, tid):
        theme.set_theme(tid)
        self._sync()

    def _sync(self):
        current = theme.theme_id()
        for tid, card in self.cards.items():
            (card.add_css_class if tid == current else card.remove_css_class)("selected")
        has_picture = themes.background_path(themes.get(current)) is not None
        self.bg_row.set_sensitive(has_picture)

    def present_for(self, window):
        if isinstance(self, getattr(Adw, "PreferencesDialog", ())):
            self.present(window)
        else:
            self.set_transient_for(window)
            self.present()
