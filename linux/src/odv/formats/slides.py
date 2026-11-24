"""The presentation model shared by the PPTX and ODP readers, drawn by
odv.views.slides. Positions and sizes are in EMU (914400 per inch)."""

from dataclasses import dataclass, field

EMU_PER_PT = 12700


@dataclass
class Run:
    text: str
    bold: bool = False
    italic: bool = False
    underline: bool = False
    strike: bool = False
    size: float = 18.0  # points
    color: str | None = None  # RRGGBB
    font: str | None = None


@dataclass
class Paragraph:
    runs: list = field(default_factory=list)
    align: str = "left"  # left, center, right, justify
    level: int = 0
    bullet: str | None = None  # the bullet or number shown before the text


@dataclass
class Shape:
    kind: str  # rect, roundRect, ellipse, line, image, table, text
    x: int = 0
    y: int = 0
    w: int = 0
    h: int = 0
    fill: str | None = None
    line: str | None = None
    line_width: float = 1.0  # points
    image: bytes | None = None
    paragraphs: list = field(default_factory=list)
    anchor: str = "top"  # top, middle, bottom
    rows: list = field(default_factory=list)  # table: [[cell paragraphs list]]
    col_widths: list = field(default_factory=list)
    rotation: float = 0.0
    insets: tuple = (91440, 45720, 91440, 45720)  # left, top, right, bottom


@dataclass
class Slide:
    shapes: list = field(default_factory=list)
    background: str | None = "FFFFFF"
    background_image: bytes | None = None
    notes: str = ""
    title: str = ""
    hidden: bool = False

    def text(self) -> str:
        parts = []
        for s in self.shapes:
            for p in s.paragraphs:
                parts.append("".join(r.text for r in p.runs))
            for row in s.rows:
                for cell in row:
                    for p in cell:
                        parts.append("".join(r.text for r in p.runs))
        return "\n".join(parts)


@dataclass
class Presentation:
    width: int = 9144000
    height: int = 6858000
    slides: list = field(default_factory=list)
