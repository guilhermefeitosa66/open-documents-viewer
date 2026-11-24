"""RTF → rich-text operations (odv.mdrender.Doc). Pure Python, for a thread.

Covers what documents use day to day: paragraphs and line breaks, bold,
italic, underline, strike, font size (relative to the body), colours,
bullet and numbered lists (their \\listtext), simple tables, Unicode
(\\uN with \\ucN) and code-page characters (\\'hh with \\ansicpgN).
Headers, footers, pictures, fields' instructions and metadata are skipped.
"""

import re

from odv.mdrender import Doc, Table

_TOKEN = re.compile(rb"\\([a-zA-Z]+)(-?\d+)? ?|\\'([0-9a-fA-F]{2})|\\(.)|([{}])|([^\\{}\r\n]+)|[\r\n]+",
                    re.DOTALL)
# Destinations whose text is not part of the body.
SKIP_DEST = {"fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl",
             "headerr", "headerf", "footerl", "footerr", "footerf", "object", "fldinst", "xmlnstbl",
             "listtable", "listoverridetable", "rsidtbl", "generator", "themedata", "colorschememapping",
             "datastore", "latentstyles", "pgdsctbl", "filetbl", "revtbl", "mmathPr", "wgrffmtfilter",
             "nonshppict", "bkmkstart", "bkmkend", "footnote", "annotation", "atnid", "atnauthor"}
DEFAULT_SIZE = 24  # half-points: 12 pt


class _State:
    __slots__ = ("bold", "italic", "underline", "strike", "size", "color", "skip", "uc", "hidden")

    def __init__(self):
        self.bold = self.italic = self.underline = self.strike = self.hidden = False
        self.size = DEFAULT_SIZE
        self.color = 0
        self.skip = False
        self.uc = 1

    def copy(self):
        s = _State()
        for name in self.__slots__:
            setattr(s, name, getattr(self, name))
        return s


class _RtfBuilder:
    def __init__(self):
        self.doc = Doc()
        self.colors: list[str | None] = [None]
        self.codepage = "cp1252"
        self.state = _State()
        self.stack: list[_State] = []
        self.skip_chars = 0
        self.in_table = False  # collecting table rows
        self.para_in_table = False  # the current paragraph carries \\intbl
        self.row: list[str] = []
        self.cell: list[str] = []
        self.rows: list[list[str]] = []
        self.body_size = DEFAULT_SIZE
        self.last = "\n"
        self._color_parts: dict | None = None
        self._in_colortbl = False

    # -- output ---------------------------------------------------------------
    def tags(self) -> tuple:
        s = self.state
        out = []
        if s.bold:
            out.append("bold")
        if s.italic:
            out.append("italic")
        if s.underline:
            out.append("underline")
        if s.strike:
            out.append("strike")
        if s.size != self.body_size:
            ratio = max(0.5, min(3.0, s.size / self.body_size))
            if abs(ratio - 1) > 0.05:
                out.append(f"scale-{round(ratio * 100)}")
        color = self.colors[s.color] if 0 <= s.color < len(self.colors) else None
        if color:
            out.append(f"fg-{color}")
        return tuple(out)

    def emit(self, text: str) -> None:
        if not text or self.state.skip or self.state.hidden:
            return
        if self.in_table and not self.para_in_table:
            self.flush_table()  # a paragraph outside the rows ends the table
        if self.in_table:
            self.cell.append(text)
            return
        tags = ("p",) + self.tags()
        ops = self.doc.ops
        if ops and ops[-1][0] == "text" and ops[-1][2] == tags:
            ops[-1] = ("text", ops[-1][1] + text, tags)
        else:
            ops.append(("text", text, tags))
        self.doc.length += len(text)
        self.last = text[-1]

    def paragraph(self) -> None:
        if self.in_table and self.para_in_table:
            self.cell.append(" ")
            return
        self.emit("\n")

    def flush_table(self) -> None:
        self.in_table = False  # first: emit() below must not end the table again
        if self.rows:
            header, rows = self.rows[0], self.rows[1:]
            if self.last != "\n":
                self.emit("\n")
            self.doc.ops.append(("table", Table(header, rows, ["left"] * len(header))))
            self.doc.length += 1
            self.last = "x"
            self.emit("\n")
        self.rows = []

    # -- parsing ----------------------------------------------------------------
    def control(self, word: str, param: int | None) -> None:
        s = self.state
        if word in SKIP_DEST:
            s.skip = True
            if word == "colortbl":
                s.skip = False
                self._in_colortbl = True
                self._color_parts = {}
            return
        if self._in_colortbl and word in ("red", "green", "blue"):
            self._color_parts[word] = param or 0
            return
        if word == "ansicpg" and param:
            self.codepage = f"cp{param}"
        elif word == "fs" and param:
            s.size = param
        elif word == "b":
            s.bold = param != 0
        elif word == "i":
            s.italic = param != 0
        elif word in ("ul", "uld", "uldb", "ulw"):
            s.underline = param != 0
        elif word == "ulnone":
            s.underline = False
        elif word in ("strike", "striked"):
            s.strike = param != 0
        elif word == "cf":
            s.color = param or 0
        elif word == "v":
            s.hidden = param != 0
        elif word == "plain":
            keep_skip, keep_uc = s.skip, s.uc
            self.state = _State()
            self.state.skip, self.state.uc = keep_skip, keep_uc
        elif word in ("par", "sect"):
            self.paragraph()
        elif word == "line":
            self.emit("\n") if not self.in_table else self.cell.append(" ")
        elif word == "tab":
            self.emit("\t")
        elif word == "page":
            self.paragraph()
        elif word == "uc" and param is not None:
            s.uc = param
        elif word == "u" and param is not None:
            self.emit(chr(param % 65536))
            self.skip_chars = s.uc
        elif word == "emdash":
            self.emit("—")
        elif word == "endash":
            self.emit("–")
        elif word == "bullet":
            self.emit("•")
        elif word in ("lquote", "rquote"):
            self.emit("‘" if word == "lquote" else "’")
        elif word in ("ldblquote", "rdblquote"):
            self.emit("“" if word == "ldblquote" else "”")
        elif word == "trowd":
            if not self.in_table:
                self.in_table = True
                self.rows = []
            self.para_in_table = True
            self.row, self.cell = [], []
        elif word == "cell" and self.in_table:
            self.row.append("".join(self.cell).strip())
            self.cell = []
        elif word == "row" and self.in_table:
            self.rows.append(self.row)
            self.row = []
        elif word == "pard":
            self.para_in_table = False
        elif word == "intbl":
            self.para_in_table = True
            if not self.in_table:
                self.in_table = True
                self.rows, self.row, self.cell = [], [], []

    def feed(self, data: bytes) -> Doc:
        pending_dest = False
        for m in _TOKEN.finditer(data):
            word, param, hexchar, symbol, brace, text = m.groups()
            if brace == b"{":
                self.stack.append(self.state.copy())
                continue
            if brace == b"}":
                closing_colortbl = self._in_colortbl
                if self.stack:
                    self.state = self.stack.pop()
                if closing_colortbl:
                    self._in_colortbl = False
                continue
            if word is not None:
                w = word.decode("ascii")
                if self.skip_chars and w != "u":
                    self.skip_chars -= 1
                    continue
                if pending_dest:
                    pending_dest = False
                    self.state.skip = True
                    continue
                self.control(w, int(param) if param is not None else None)
                continue
            if hexchar is not None:
                if self.skip_chars:
                    self.skip_chars -= 1
                    continue
                self.emit(bytes([int(hexchar, 16)]).decode(self.codepage, "replace"))
                continue
            if symbol is not None:
                sym = symbol.decode("latin-1")
                if sym == "*":
                    pending_dest = True  # an optional destination we do not know: skip it
                elif sym in "\\{}":
                    self.emit(sym)
                elif sym == "~":
                    self.emit("\u00a0")
                elif sym == "_":
                    self.emit("\u2011")
                elif sym in "\n\r":
                    self.paragraph()
                continue
            if text is not None:
                chunk = text.decode(self.codepage, "replace")
                if self._in_colortbl:
                    for _ in range(chunk.count(";")):
                        parts = self._color_parts or {}
                        if parts:
                            rgb = (parts.get("red", 0), parts.get("green", 0), parts.get("blue", 0))
                            # Black and white mean "the text colour": keep the theme's.
                            self.colors.append(None if rgb in ((0, 0, 0), (255, 255, 255))
                                               else "%02X%02X%02X" % rgb)
                        self._color_parts = {}
                    continue
                if self.skip_chars:
                    cut = min(self.skip_chars, len(chunk))
                    chunk = chunk[cut:]
                    self.skip_chars -= cut
                self.emit(chunk)
        if self.in_table:
            self.flush_table()
        return self.doc



def build(text: str | bytes, path: str = "") -> Doc:
    """RTF is 7-bit text; anything else came in through \\'hh and \\uN."""
    data = text.encode("cp1252", "replace") if isinstance(text, str) else text
    return _RtfBuilder().feed(data)
