"""The theme catalogue: libre-apps (the default, following the system) plus
the shared libre-apps themes imported into data/themes. Pure Python."""

import json
import os

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
THEMES_DIR = os.path.join(DATA, "themes")
DEFAULT_ID = "libre-apps"

# The libre-apps brand colours (site-libre-apps-org/brand), in the same shape
# as the imported themes so the rest of the code treats them alike.
LIBRE = {
    False: {"bg": "#FCF7EE", "surface": "#F3E8D7", "surfaceAlt": "#FFFFFF", "border": "#E2D2B9",
            "text": "#2A1A12", "textMuted": "#6B5242", "accent": "#B5502B", "onAccent": "#FFFFFF",
            "link": "#8E3A1E", "selection": "#F5D7A1", "tabbar": "#F7EEDF", "headerbar": "#FCF7EE"},
    True: {"bg": "#1A1310", "surface": "#251B16", "surfaceAlt": "#2A1F19", "border": "#3E2E24",
           "text": "#F3E7D8", "textMuted": "#BBA58F", "accent": "#D9973A", "onAccent": "#1E1612",
           "link": "#E8B062", "selection": "#5A3E2A", "tabbar": "#251B16", "headerbar": "#251B16"},
}
LIBRE_MARKDOWN = {
    False: {"heading": "#2A1A12", "quoteBar": "#B5502B", "codeBg": "#EFE2CC", "blockBg": "#F3E8D7",
            "hr": "#E2D2B9", "marker": "#B5502B"},
    True: {"heading": "#F3E7D8", "quoteBar": "#D9973A", "codeBg": "#2E231C", "blockBg": "#251B16",
           "hr": "#3E2E24", "marker": "#D9973A"},
}
LIBRE_DOTS = {
    False: {"folder": "#B77A22", "markdown": "#B5502B", "code": "#3F6E8C", "config": "#7A6A2E",
            "data": "#5C6B2E", "text": "#8C7A6B"},
    True: {"folder": "#D9973A", "markdown": "#E57A36", "code": "#8DB6CF", "config": "#C9B56A",
           "data": "#A9C27A", "text": "#A89584"},
}

_catalogue = None


def catalogue() -> list[dict]:
    global _catalogue
    if _catalogue is None:
        try:
            with open(os.path.join(THEMES_DIR, "themes.json")) as f:
                _catalogue = json.load(f)["themes"]
        except (OSError, ValueError, KeyError):
            _catalogue = []
    return _catalogue


def get(theme_id: str) -> dict | None:
    """The imported theme with this id; None for libre-apps or an unknown id."""
    return next((t for t in catalogue() if t["id"] == theme_id), None)


def background_path(theme: dict | None) -> str | None:
    if not theme or not theme.get("background"):
        return None
    path = os.path.join(THEMES_DIR, theme["background"])
    return path if os.path.exists(path) else None


def thumbnail_path(theme: dict | None) -> str | None:
    if not theme or not theme.get("thumbnail"):
        return None
    path = os.path.join(THEMES_DIR, theme["thumbnail"])
    return path if os.path.exists(path) else None


def _lum(hex_color: str) -> float:
    def ch(c):
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (int(hex_color[i:i + 2], 16) for i in (1, 3, 5))
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def contrast(a: str, b: str) -> float:
    la, lb = sorted((_lum(a), _lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def resolve(theme_id: str, system_dark: bool) -> dict:
    """Everything the UI needs for one theme, flattened.

    Keys: id, dark, ui (colours by role), md (Markdown colours), dots (file
    kind colours), scheme (GtkSourceView scheme id), small_dim (the colour
    for small secondary text: textMuted only where it passes AA)."""
    theme = get(theme_id)
    if theme is None:
        dark = system_dark
        ui = dict(LIBRE[dark])
        md = dict(LIBRE_MARKDOWN[dark])
        dots = dict(LIBRE_DOTS[dark])
        scheme = "odv-dark" if dark else "odv-light"
        tid = DEFAULT_ID
    else:
        dark = theme["scheme"] == "dark"
        ui = dict(theme["ui"])
        ui.setdefault("tabbar", ui["surface"])
        ui.setdefault("headerbar", ui["surface"])
        syn = theme["syntax"]
        md = dict(theme["markdown"])
        md.setdefault("blockBg", md["codeBg"])
        md.setdefault("marker", ui["accent"])
        dots = {"folder": ui["accent"], "markdown": md["heading"], "code": syn["function"],
                "config": syn["attribute"], "data": syn["string"], "text": ui["textMuted"]}
        scheme = f"odv-{theme['id']}"
        tid = theme["id"]
    small_dim = ui["textMuted"] if contrast(ui["textMuted"], ui["bg"]) >= 4.5 else ui["text"]
    return {"id": tid, "dark": dark, "ui": ui, "md": md, "dots": dots, "scheme": scheme,
            "small_dim": small_dim, "background": background_path(theme)}
