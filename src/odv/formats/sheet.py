"""Spreadsheets: the shared workbook model, number formatting and the XLSX
reader. Pure Python (zipfile + ElementTree iterparse), for a thread; no
dependency. Values are the ones saved in the file (formulas are shown,
never recalculated)."""

import datetime
import math
import posixpath
import re
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

MAX_COLS = 256


@dataclass
class Style:
    fmt: str = "General"
    bold: bool = False
    italic: bool = False
    underline: bool = False
    strike: bool = False
    color: str | None = None  # RRGGBB
    fill: str | None = None  # RRGGBB
    align: str | None = None  # left, center, right
    border: bool = False


@dataclass
class Cell:
    value: object = None  # str, float, bool or None (as saved)
    formula: str | None = None
    style: int = 0
    error: str | None = None


@dataclass
class Sheet:
    name: str
    hidden: bool = False
    rows: dict = field(default_factory=dict)  # row (0-based) -> {col: Cell}
    max_row: int = 0  # number of rows
    max_col: int = 0  # number of columns
    col_widths: dict = field(default_factory=dict)  # col -> width in characters
    hidden_cols: set = field(default_factory=set)
    hidden_rows: set = field(default_factory=set)
    merges: list = field(default_factory=list)  # (r1, c1, r2, c2), inclusive, 0-based
    freeze: tuple = (0, 0)  # rows, cols frozen at the top-left
    complete: bool = False


@dataclass
class Workbook:
    sheets: list = field(default_factory=list)
    styles: list = field(default_factory=lambda: [Style()])
    date1904: bool = False


def col_name(c: int) -> str:
    s = ""
    c += 1
    while c:
        c, rem = divmod(c - 1, 26)
        s = chr(65 + rem) + s
    return s


def parse_ref(ref: str) -> tuple[int, int]:
    m = re.match(r"\$?([A-Za-z]+)\$?(\d+)", ref)
    if not m:
        return 0, 0
    col = 0
    for ch in m.group(1).upper():
        col = col * 26 + ord(ch) - 64
    return int(m.group(2)) - 1, col - 1


# -- number formats -------------------------------------------------------------------

BUILTIN_FORMATS = {
    0: "General", 1: "0", 2: "0.00", 3: "#,##0", 4: "#,##0.00", 9: "0%", 10: "0.00%", 11: "0.00E+00",
    12: "# ?/?", 13: "# ??/??", 14: "yyyy-mm-dd", 15: "d-mmm-yy", 16: "d-mmm", 17: "mmm-yy",
    18: "h:mm AM/PM", 19: "h:mm:ss AM/PM", 20: "h:mm", 21: "h:mm:ss", 22: "yyyy-mm-dd h:mm",
    37: "#,##0 ;(#,##0)", 38: "#,##0 ;(#,##0)", 39: "#,##0.00;(#,##0.00)", 40: "#,##0.00;(#,##0.00)",
    45: "mm:ss", 46: "[h]:mm:ss", 47: "mm:ss.0", 48: "##0.0E+0", 49: "@",
}
_MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
           "November", "December"]
_DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def serial_to_datetime(serial: float, date1904: bool = False) -> datetime.datetime:
    base = datetime.datetime(1904, 1, 1) if date1904 else datetime.datetime(1899, 12, 30)
    if not date1904 and serial < 61:  # Excel's phantom 1900-02-29
        serial += 1
    return base + datetime.timedelta(days=serial)


def _general(n: float) -> str:
    if isinstance(n, bool):
        return "TRUE" if n else "FALSE"
    if n == int(n) and abs(n) < 1e15:
        return str(int(n))
    if abs(n) >= 1e11 or abs(n) < 1e-9:
        return f"{n:.5E}".replace("E+0", "E+").replace("E-0", "E-")
    return f"{n:.10g}"


def _split_sections(code: str) -> list[str]:
    parts, buf, quote, bracket = [], "", False, False
    for ch in code:
        if ch == '"':
            quote = not quote
        elif ch == "[" and not quote:
            bracket = True
        elif ch == "]" and not quote:
            bracket = False
        if ch == ";" and not quote and not bracket:
            parts.append(buf)
            buf = ""
        else:
            buf += ch
    parts.append(buf)
    return parts


_DATE_TOKENS = re.compile(r'"[^"]*"|\\.|\[[^\]]*\]|yyyy|yy|mmmmm|mmmm|mmm|mm|m|dddd|ddd|dd|d|hh|h|ss|s|AM/PM|A/P|\.0+',
                          re.IGNORECASE)


def _is_date(code: str) -> bool:
    stripped = re.sub(r'"[^"]*"|\\.|\[(?![hms]+\])[^\]]*\]', "", code)
    return bool(re.search(r"[ymdhs]", stripped, re.IGNORECASE))


def _format_date(code: str, dt: datetime.datetime, serial: float) -> str:
    out = []
    tokens = list(_DATE_TOKENS.finditer(code))
    ampm = re.search(r"AM/PM|A/P", code, re.IGNORECASE) is not None
    last = 0
    prev_kind = None
    for i, m in enumerate(tokens):
        out.append(code[last:m.start()].replace("\\", ""))
        tok = m.group(0)
        low = tok.lower()
        if tok.startswith('"'):
            out.append(tok[1:-1])
        elif tok.startswith("\\"):
            out.append(tok[1:])
        elif tok.startswith("["):
            inner = tok[1:-1].lower()
            if inner in ("h", "hh"):
                out.append(str(int(serial * 24)))
            elif inner in ("m", "mm"):
                out.append(str(int(serial * 1440)))
            elif inner in ("s", "ss"):
                out.append(str(int(round(serial * 86400))))
        elif low in ("yyyy",):
            out.append(f"{dt.year:04d}")
        elif low == "yy":
            out.append(f"{dt.year % 100:02d}")
        elif low.startswith("m") and low not in ("mm", "m") or low in ("mmm", "mmmm", "mmmmm"):
            name = _MONTHS[dt.month - 1]
            out.append(name if low == "mmmm" else name[0] if low == "mmmmm" else name[:3])
        elif low in ("mm", "m"):
            nxt = next((t.group(0).lower() for t in tokens[i + 1:] if t.group(0).lower()[0] in "ymdhs"), "")
            is_minute = prev_kind == "h" or nxt.startswith("s")
            value = dt.minute if is_minute else dt.month
            out.append(f"{value:02d}" if low == "mm" else str(value))
        elif low in ("dddd", "ddd"):
            name = _DAYS[dt.weekday()]
            out.append(name if low == "dddd" else name[:3])
        elif low in ("dd", "d"):
            out.append(f"{dt.day:02d}" if low == "dd" else str(dt.day))
        elif low in ("hh", "h"):
            hour = dt.hour % 12 or 12 if ampm else dt.hour
            out.append(f"{hour:02d}" if low == "hh" else str(hour))
        elif low in ("ss", "s"):
            out.append(f"{dt.second:02d}" if low == "ss" else str(dt.second))
        elif low in ("am/pm", "a/p"):
            pm = dt.hour >= 12
            out.append(("PM" if pm else "AM") if low == "am/pm" else ("P" if pm else "A"))
        elif low.startswith("."):
            digits = len(low) - 1
            out.append("." + f"{dt.microsecond / 1e6:.{digits}f}"[2:])
        if low and low[0] in "ymdhs":
            prev_kind = low[0]
        elif low.startswith("[") and low[1:2] in ("h", "m", "s"):
            prev_kind = low[1]
        last = m.end()
    out.append(code[last:].replace("\\", ""))
    return "".join(out)


def _format_number_code(code: str, n: float) -> str:
    literal_prefix, literal_suffix = "", ""
    # Currency like [$R$-416] or [$€-2]: keep the symbol.
    code = re.sub(r"\[\$([^\]-]*)(-[^\]]*)?\]", lambda m: f'"{m.group(1)}"', code)
    code = re.sub(r"\[[^\]]*\]", "", code)  # colours, conditions
    code = re.sub(r"_.|\*.", "", code)  # padding
    parts = re.split(r'("[^"]*"|\\.)', code)
    number_part = ""
    seen_digit = False
    for part in parts:
        if part.startswith('"') or part.startswith("\\"):
            text = part[1:-1] if part.startswith('"') else part[1:]
            if seen_digit:
                literal_suffix += text
            else:
                literal_prefix += text
            continue
        m = re.search(r"[0#?.,%Ee+-]+(?:[0#?.,%]*)", part)
        if m and re.search(r"[0#?]", part):
            literal_prefix += part[:m.start()] if not seen_digit else ""
            number_part += m.group(0)
            literal_suffix += part[m.end():]
            seen_digit = True
        elif seen_digit:
            literal_suffix += part
        else:
            literal_prefix += part
    if not number_part:
        return literal_prefix + literal_suffix if (literal_prefix or literal_suffix) else _general(n)
    percent = "%" in number_part or "%" in literal_suffix
    if percent:
        n *= 100
        number_part = number_part.replace("%", "")
    if re.search(r"E[+-]", number_part, re.IGNORECASE):
        mant = number_part.split("E")[0].split("e")[0]
        decimals = len(mant.split(".")[1]) if "." in mant else 0
        text = f"{n:.{decimals}E}"
        mantissa, exp = text.split("E")
        text = f"{mantissa}E{'+' if int(exp) >= 0 else '-'}{abs(int(exp)):02d}"
    else:
        int_part, _, dec_part = number_part.partition(".")
        decimals = len(re.sub(r"[^0#?]", "", dec_part))
        thousands = "," in int_part
        scale_commas = len(int_part) - len(int_part.rstrip(","))
        n /= 1000 ** scale_commas
        min_int = len(re.sub(r"[^0]", "", int_part))
        text = f"{abs(n):,.{decimals}f}" if thousands else f"{abs(n):.{decimals}f}"
        whole, dot, frac = text.partition(".")
        if dec_part and "#" in dec_part and frac:
            frac = frac.rstrip("0") if set(dec_part) <= {"#"} else frac
        digits = whole.replace(",", "")
        if digits == "0" and min_int == 0:
            whole = ""
        elif len(digits) < min_int:  # "000" shows 7 as 007
            padded = digits.rjust(min_int, "0")
            whole = f"{int(padded):,}".rjust(len(padded), "0") if thousands else padded
        text = whole + (dot + frac if frac else "")
        if n < 0:
            text = "-" + text
    if percent and "%" not in literal_suffix:
        literal_suffix = "%" + literal_suffix
    return literal_prefix + text + literal_suffix


def format_value(value, code: str, date1904: bool = False) -> str:
    """How the spreadsheet shows ``value`` with the number format ``code``."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, str):
        sections = _split_sections(code)
        if len(sections) >= 4 and "@" in sections[3]:
            return sections[3].replace('"', "").replace("@", value)
        return value
    if not isinstance(value, (int, float)) or math.isnan(value) or math.isinf(value):
        return str(value)
    if not code or code.lower() == "general":
        return _general(value)
    if code == "@":
        return _general(value)
    sections = _split_sections(code)
    section = sections[0]
    n = value
    if len(sections) >= 2 and value < 0:
        section, n = sections[1], -value
    elif len(sections) >= 3 and value == 0:
        section = sections[2]
    if section.lower() == "general":
        return _general(n)
    try:
        if _is_date(section):
            if n < 0 or n > 2958465:
                return _general(value)
            return _format_date(section, serial_to_datetime(n, date1904), n)
        return _format_number_code(section, n)
    except (ValueError, OverflowError, IndexError):
        return _general(value)


# -- XLSX -----------------------------------------------------------------------------------

M = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
RNS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
PKG = "{http://schemas.openxmlformats.org/package/2006/relationships}"
# The default Office palette for theme colours we cannot read from the theme part.
THEME = ["FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47"]
INDEXED = {8: "000000", 9: "FFFFFF", 10: "FF0000", 11: "00FF00", 12: "0000FF", 13: "FFFF00", 14: "FF00FF",
           15: "00FFFF", 16: "800000", 17: "008000", 18: "000080", 19: "808000", 20: "800080", 21: "008080",
           22: "C0C0C0", 23: "808080"}


class SheetError(Exception):
    pass


def _color(el) -> str | None:
    if el is None:
        return None
    if el.get("rgb"):
        rgb = el.get("rgb")[-6:].upper()
        return rgb if re.fullmatch(r"[0-9A-F]{6}", rgb) else None
    if el.get("theme") is not None:
        i = int(el.get("theme"))
        return THEME[i] if i < len(THEME) else None
    if el.get("indexed") is not None:
        return INDEXED.get(int(el.get("indexed")))
    return None


class XlsxReader:
    """Opens the package and reads the workbook; sheets are read one by one
    (read_sheet), streaming rows to a callback as they parse."""

    def __init__(self, path: str):
        try:
            self.zf = zipfile.ZipFile(path)
        except (OSError, zipfile.BadZipFile) as e:
            raise SheetError(str(e)) from e
        self.names = set(self.zf.namelist())
        if "xl/workbook.xml" not in self.names:
            raise SheetError("xl/workbook.xml is missing")
        self.workbook = Workbook()
        self.shared: list[str] = []
        self._targets: list[str] = []
        self._read_workbook()
        self._read_styles()
        self._read_shared()

    def _xml(self, name):
        if name not in self.names:
            return None
        try:
            return ET.fromstring(self.zf.read(name))
        except ET.ParseError:
            return None

    def _read_workbook(self):
        root = self._xml("xl/workbook.xml")
        rels = {}
        rel_root = self._xml("xl/_rels/workbook.xml.rels")
        if rel_root is not None:
            for rel in rel_root.iter(PKG + "Relationship"):
                target = rel.get("Target", "")
                target = target.lstrip("/") if target.startswith("/") else posixpath.normpath(posixpath.join("xl", target))
                rels[rel.get("Id")] = target
        pr = root.find(M + "workbookPr")
        self.workbook.date1904 = pr is not None and pr.get("date1904") in ("1", "true")
        for sh in root.iter(M + "sheet"):
            name = sh.get("name", "?")
            self.workbook.sheets.append(Sheet(name=name, hidden=sh.get("state") in ("hidden", "veryHidden")))
            self._targets.append(rels.get(sh.get(RNS + "id"), ""))

    def _read_styles(self):
        root = self._xml("xl/styles.xml")
        if root is None:
            return
        fmts = dict(BUILTIN_FORMATS)
        nf = root.find(M + "numFmts")
        if nf is not None:
            for f in nf:
                fmts[int(f.get("numFmtId"))] = f.get("formatCode", "General")
        fonts = []
        fonts_el = root.find(M + "fonts")
        for font in (fonts_el if fonts_el is not None else []):
            u = font.find(M + "u")
            fonts.append({"bold": font.find(M + "b") is not None and font.find(M + "b").get("val") not in ("0", "false"),
                          "italic": font.find(M + "i") is not None and font.find(M + "i").get("val") not in ("0", "false"),
                          "underline": u is not None and u.get("val") != "none",
                          "strike": font.find(M + "strike") is not None,
                          "color": _color(font.find(M + "color"))})
        fills = []
        fills_el = root.find(M + "fills")
        for fill in (fills_el if fills_el is not None else []):
            pat = fill.find(M + "patternFill")
            color = None
            if pat is not None and pat.get("patternType") not in (None, "none"):
                color = _color(pat.find(M + "fgColor"))
            fills.append(color)
        borders = []
        borders_el = root.find(M + "borders")
        for border in (borders_el if borders_el is not None else []):
            borders.append(any(side.get("style") for side in border))
        styles = []
        xfs = root.find(M + "cellXfs")
        for xf in (xfs if xfs is not None else []):
            font = fonts[int(xf.get("fontId", 0))] if int(xf.get("fontId", 0)) < len(fonts) else {}
            fill_id = int(xf.get("fillId", 0))
            align = xf.find(M + "alignment")
            styles.append(Style(
                fmt=fmts.get(int(xf.get("numFmtId", 0)), "General"),
                bold=font.get("bold", False), italic=font.get("italic", False),
                underline=font.get("underline", False), strike=font.get("strike", False),
                color=font.get("color") if font.get("color") not in ("000000",) else None,
                fill=fills[fill_id] if fill_id < len(fills) and fills[fill_id] not in ("FFFFFF",) else None,
                align=align.get("horizontal") if align is not None else None,
                border=borders[int(xf.get("borderId", 0))] if int(xf.get("borderId", 0)) < len(borders) else False))
        if styles:
            self.workbook.styles = styles

    def _read_shared(self):
        if "xl/sharedStrings.xml" not in self.names:
            return
        with self.zf.open("xl/sharedStrings.xml") as f:
            for _event, el in ET.iterparse(f):
                if el.tag == M + "si":
                    self.shared.append("".join(t.text or "" for t in el.iter(M + "t")
                                               if not any(p.tag == M + "rPh" for p in [])))
                    el.clear()

    def read_sheet(self, index: int, on_rows=None, batch: int = 2000, cancelled=lambda: False) -> Sheet:
        sheet = self.workbook.sheets[index]
        target = self._targets[index]
        if target not in self.names:
            sheet.complete = True
            return sheet
        pending = []
        with self.zf.open(target) as f:
            for _event, el in ET.iterparse(f):
                tag = el.tag
                if tag == M + "row":
                    r = int(el.get("r", sheet.max_row + 1)) - 1
                    if el.get("hidden") in ("1", "true"):
                        sheet.hidden_rows.add(r)
                    cells = {}
                    next_col = 0
                    for c in el:
                        if c.tag != M + "c":
                            continue
                        ref = c.get("r")
                        col = parse_ref(ref)[1] if ref else next_col
                        next_col = col + 1
                        if col >= MAX_COLS:
                            continue
                        cell = self._cell(c)
                        if cell is not None:
                            cells[col] = cell
                    if cells:
                        sheet.rows[r] = cells
                        sheet.max_col = max(sheet.max_col, max(cells) + 1)
                    sheet.max_row = max(sheet.max_row, r + 1)
                    pending.append(r)
                    el.clear()
                    if len(pending) >= batch:
                        if cancelled():
                            return sheet
                        if on_rows:
                            on_rows(sheet)
                        pending = []
                elif tag == M + "col":
                    lo, hi = int(el.get("min", 1)) - 1, min(int(el.get("max", 1)), MAX_COLS) - 1
                    for c in range(lo, hi + 1):
                        if el.get("width"):
                            sheet.col_widths[c] = float(el.get("width"))
                        if el.get("hidden") in ("1", "true"):
                            sheet.hidden_cols.add(c)
                elif tag == M + "pane":
                    if el.get("state") in ("frozen", "frozenSplit"):
                        sheet.freeze = (int(float(el.get("ySplit", 0))), int(float(el.get("xSplit", 0))))
                elif tag == M + "mergeCell":
                    ref = el.get("ref", "")
                    if ":" in ref:
                        a, b = ref.split(":")
                        (r1, c1), (r2, c2) = parse_ref(a), parse_ref(b)
                        sheet.merges.append((r1, c1, r2, c2))
                elif tag == M + "dimension":
                    ref = el.get("ref", "")
                    if ":" in ref:
                        r2, c2 = parse_ref(ref.split(":")[1])
                        sheet.max_col = max(sheet.max_col, min(c2 + 1, MAX_COLS))
        sheet.complete = True
        if on_rows:
            on_rows(sheet)
        return sheet

    def _cell(self, c):
        kind = c.get("t", "n")
        v = c.find(M + "v")
        f = c.find(M + "f")
        style = int(c.get("s", 0))
        formula = ("=" + f.text) if f is not None and f.text else None
        text = v.text if v is not None else None
        if kind == "inlineStr":
            is_el = c.find(M + "is")
            value = "".join(t.text or "" for t in is_el.iter(M + "t")) if is_el is not None else ""
        elif text is None:
            if formula is None and style == 0:
                return None
            value = None
        elif kind == "s":
            i = int(text)
            value = self.shared[i] if i < len(self.shared) else ""
        elif kind == "b":
            value = text in ("1", "true")
        elif kind == "e":
            return Cell(value=text, formula=formula, style=style, error=text)
        elif kind in ("str", "d"):
            value = text
        else:
            try:
                value = float(text)
            except ValueError:
                value = text
        return Cell(value=value, formula=formula, style=style)

    def close(self):
        self.zf.close()


def display(workbook: Workbook, cell: Cell) -> str:
    if cell.error:
        return cell.error
    style = workbook.styles[cell.style] if cell.style < len(workbook.styles) else Style()
    return format_value(cell.value, style.fmt, workbook.date1904)
