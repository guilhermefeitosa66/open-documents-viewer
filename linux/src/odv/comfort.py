"""Reading comfort: zoom levels per kind of view, prose width. The values
live in settings; views apply them and follow changes."""

from odv import settings

ZOOM_STEPS = (50, 60, 70, 80, 90, 100, 110, 120, 135, 150, 175, 200, 250, 300)
KINDS = ("prose", "code", "table")
PROSE_WIDTHS = {"narrow": 680, "medium": 820, "wide": 1040, "full": 0}


def zoom_css() -> str:
    return "".join(f".zoom-{z} {{ font-size: {z / 100:.2f}em; }}\n" for z in ZOOM_STEPS)


def zoom_of(kind: str) -> int:
    value = settings.get("zoom").get(kind, 100)
    return value if value in ZOOM_STEPS else 100


def step_zoom(kind: str, direction: int) -> int:
    """direction: +1, -1, or 0 to reset. Returns the new level."""
    current = zoom_of(kind)
    if direction == 0:
        new = 100
    else:
        i = ZOOM_STEPS.index(current) + (1 if direction > 0 else -1)
        new = ZOOM_STEPS[max(0, min(len(ZOOM_STEPS) - 1, i))]
    zoom = dict(settings.get("zoom"))
    zoom[kind] = new
    settings.set("zoom", zoom)
    return new


def apply_zoom(widget, kind: str) -> None:
    for cls in list(widget.get_css_classes()):
        if cls.startswith("zoom-"):
            widget.remove_css_class(cls)
    level = zoom_of(kind)
    if level != 100:
        widget.add_css_class(f"zoom-{level}")


def prose_width() -> int:
    return PROSE_WIDTHS.get(settings.get("prose_width"), 820)
