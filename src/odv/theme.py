"""Applies the chosen theme to libadwaita: colours, dark/light, the source
view scheme and the Markdown palette. Listeners hear about every change."""

from gi.repository import Adw, Gdk, Gtk

from odv import settings, themes

_COMMON = """
.kind-dot { min-width: 8px; min-height: 8px; border-radius: 99px; margin: 0 4px; }
.kind-markdown { background: @odv_markdown; }
.kind-code { background: @odv_code; }
.kind-config { background: @odv_config; }
.kind-data { background: @odv_data; }
.kind-text { background: @odv_text; }
.folder-icon { color: @odv_folder; }
.pane-split.active-pane tabbar .box { box-shadow: inset 0 2px @accent_bg_color; }
.mode-switch button { padding: 2px 10px; min-height: 24px; }
.md-table { border: 1px solid @odv_line; border-radius: 6px; }
.md-table label { padding: 6px 12px; }
.md-table .md-th { font-weight: bold; background: alpha(@odv_line, 0.5); }
.md-table .md-row-odd { background: alpha(@odv_line, 0.25); }
.md-hr { background: @odv_hr; min-height: 1px; margin: 8px 0; }
.data-table { font-feature-settings: "tnum"; }
.row-number { color: @odv_dim; }
.welcome-hint { color: @odv_small_dim; }
.document-area { background-color: @view_bg_color; }
tabbar .box { background: @odv_tabbar; }
.theme-card { padding: 6px; border-radius: 12px; }
.theme-card.selected { box-shadow: 0 0 0 3px @accent_bg_color; }
.theme-thumb { border-radius: 8px; }
"""

# With a background picture: the window paints nothing, the image shows
# through translucent bars, and documents stay on solid colour.
_WITH_BACKGROUND = """
window.odv-has-background { background-color: @odv_bg; }
window.odv-has-background .welcome-panel { background-color: alpha(@odv_surface, 0.85); border-radius: 18px; padding: 24px 32px; }
window.odv-has-background .empty-panel { background-color: alpha(@odv_surface, 0.85); border-radius: 18px; }
"""

_provider = None
_listeners: list = []
_state = {"theme": themes.DEFAULT_ID, "background": True, "applying": False}
_current: dict = {}


def current() -> dict:
    """The resolved theme in effect (see odv.themes.resolve)."""
    return _current


def is_dark() -> bool:
    return _current.get("dark", Adw.StyleManager.get_default().get_dark())


def palette() -> dict:
    return _current


def theme_id() -> str:
    return _state["theme"]


def background_enabled() -> bool:
    return _state["background"]


def background_path() -> str | None:
    return _current.get("background") if _state["background"] else None


def on_change(callback) -> None:
    """Call ``callback(dark: bool)`` whenever the theme or style changes."""
    _listeners.append(callback)


def remove_listener(callback) -> None:
    if callback in _listeners:
        _listeners.remove(callback)


def css_for(t: dict, with_background: bool) -> str:
    ui, dots, md = t["ui"], t["dots"], t["md"]
    translucent = with_background
    bar = (lambda c: f"alpha({c}, 0.85)") if translucent else (lambda c: c)
    colors = {
        "accent_bg_color": ui["accent"], "accent_fg_color": ui["onAccent"], "accent_color": ui["link"],
        "window_bg_color": ui["bg"], "window_fg_color": ui["text"],
        "view_bg_color": ui["bg"], "view_fg_color": ui["text"],
        "headerbar_bg_color": bar(ui["headerbar"]), "headerbar_fg_color": ui["text"],
        "headerbar_backdrop_color": bar(ui["headerbar"]),
        "sidebar_bg_color": bar(ui["surface"]), "sidebar_fg_color": ui["text"],
        "card_bg_color": ui["surfaceAlt"], "card_fg_color": ui["text"],
        "popover_bg_color": ui["surfaceAlt"], "popover_fg_color": ui["text"],
        "dialog_bg_color": ui["surfaceAlt"], "dialog_fg_color": ui["text"],
        "odv_line": ui["border"], "odv_dim": ui["textMuted"], "odv_small_dim": t["small_dim"],
        "odv_hr": md["hr"], "odv_bg": ui["bg"], "odv_surface": ui["surface"],
        "odv_tabbar": bar(ui["tabbar"]),
        "odv_folder": dots["folder"], "odv_markdown": dots["markdown"], "odv_code": dots["code"],
        "odv_config": dots["config"], "odv_data": dots["data"], "odv_text": dots["text"],
    }
    defs = "".join(f"@define-color {k} {v};\n" for k, v in colors.items())
    from odv.comfort import zoom_css

    return defs + _COMMON + zoom_css() + (_WITH_BACKGROUND if translucent else "")


def _apply(*_args) -> None:
    if _state["applying"]:
        return
    _state["applying"] = True
    try:
        sm = Adw.StyleManager.get_default()
        chosen = themes.get(_state["theme"])
        if chosen is None:
            sm.set_color_scheme(Adw.ColorScheme.DEFAULT)
        else:
            sm.set_color_scheme(Adw.ColorScheme.FORCE_DARK if chosen["scheme"] == "dark"
                                else Adw.ColorScheme.FORCE_LIGHT)
        _current.clear()
        _current.update(themes.resolve(_state["theme"], sm.get_dark()))
        _provider.load_from_string(css_for(_current, background_path() is not None))
    finally:
        _state["applying"] = False
    for cb in list(_listeners):
        cb(_current["dark"])


def set_theme(theme_id: str) -> None:
    _state["theme"] = theme_id if themes.get(theme_id) else themes.DEFAULT_ID
    settings.set("theme", _state["theme"])
    _apply()


def set_background(enabled: bool) -> None:
    _state["background"] = bool(enabled)
    settings.set("background", _state["background"])
    _apply()


def install() -> None:
    global _provider
    if _provider is not None:
        return
    _state["theme"] = settings.get("theme") if themes.get(settings.get("theme")) else themes.DEFAULT_ID
    _state["background"] = settings.get("background")
    _provider = Gtk.CssProvider()
    Gtk.StyleContext.add_provider_for_display(
        Gdk.Display.get_default(), _provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
    )
    Adw.StyleManager.get_default().connect("notify::dark", _apply)
    _apply()
