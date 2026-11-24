"""OpenDocument spreadsheets (.ods, flat .fods) into the workbook model.

Each cell keeps the text the file shows (text:p) and its typed value
(numbers right-aligned, formulas kept). Sheet names come from a quick scan;
a sheet is streamed with iterparse when it is opened, rows arriving in
batches. Repeated cells/rows are expanded, but the empty tail spreadsheets
pad with (up to a million rows) is cut. Pure Python, for a thread."""

import re
import zipfile
import xml.etree.ElementTree as ET

from odv.formats.sheet import MAX_COLS, Cell, Sheet, SheetError, Style, Workbook

O = "{urn:oasis:names:tc:opendocument:xmlns:office:1.0}"
S = "{urn:oasis:names:tc:opendocument:xmlns:style:1.0}"
T = "{urn:oasis:names:tc:opendocument:xmlns:text:1.0}"
TB = "{urn:oasis:names:tc:opendocument:xmlns:table:1.0}"
FO = "{urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0}"
MAX_REPEAT = 10000


def _width_chars(value: str | None) -> float | None:
    m = re.match(r"([\d.]+)\s*(cm|mm|in|pt)?", value or "")
    if not m:
        return None
    n = float(m.group(1))
    pt = {"cm": n * 28.35, "mm": n * 2.835, "in": n * 72}.get(m.group(2), n)
    return max(1.0, (pt / 0.75 - 10) / 7)  # back to "characters" like XLSX widths


class OdsReader:
    def __init__(self, path: str):
        self.path = path
        self.flat = path.lower().endswith(".fods")
        if not self.flat:
            try:
                self.zf = zipfile.ZipFile(path)
            except (OSError, zipfile.BadZipFile) as e:
                raise SheetError(str(e)) from e
            from odv.formats import protect
            from odv.i18n import _

            if protect.odf_encrypted(self.zf):
                raise SheetError(_(protect.ODF_ENCRYPTED))
            if "content.xml" not in self.zf.namelist():
                raise SheetError("content.xml is missing")
        self.workbook = Workbook()
        self.col_styles = {}  # style name -> (width in chars or None)
        self.cell_styles = {}  # style name -> index in workbook.styles
        data = self._content_bytes()
        for m in re.finditer(rb"<table:table\s[^>]*>", data):
            tag = m.group(0).decode("utf-8", "replace")
            name = re.search(r'table:name="([^"]*)"', tag)
            style = re.search(r'table:style-name="([^"]*)"', tag)
            sheet = Sheet(name=_unescape(name.group(1)) if name else "?")
            sheet._style = style.group(1) if style else None
            self.workbook.sheets.append(sheet)
        self._read_styles(data)
        for sheet in self.workbook.sheets:
            sheet.hidden = sheet._style in self.hidden_tables

    def _content_bytes(self) -> bytes:
        if self.flat:
            with open(self.path, "rb") as f:
                return f.read()
        return self.zf.read("content.xml")

    def _open_content(self):
        return open(self.path, "rb") if self.flat else self.zf.open("content.xml")

    def _read_styles(self, data: bytes):
        """Automatic styles sit before the body: parse just that part."""
        self.hidden_tables = set()
        end = data.find(b"<office:body")
        head = data[:end] if end > 0 else data
        m = re.search(rb"<office:automatic-styles.*?</office:automatic-styles>", head, re.DOTALL)
        if not m:
            return
        wrapper = (b'<r xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
                   b'xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" '
                   b'xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" '
                   b'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" '
                   b'xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" '
                   b'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
                   b'xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" '
                   b'xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0">'
                   + m.group(0) + b"</r>")
        try:
            root = ET.fromstring(wrapper)
        except ET.ParseError:
            return
        for st in root.iter(S + "style"):
            name, family = st.get(S + "name"), st.get(S + "family")
            if family == "table-column":
                cp = st.find(S + "table-column-properties")
                self.col_styles[name] = _width_chars(cp.get(S + "column-width")) if cp is not None else None
            elif family == "table":
                tp = st.find(S + "table-properties")
                if tp is not None and tp.get(TB + "display") == "false":
                    self.hidden_tables.add(name)
            elif family == "table-cell":
                text = st.find(S + "text-properties")
                cell = st.find(S + "table-cell-properties")
                para = st.find(S + "paragraph-properties")
                color = (text.get(FO + "color") or "").lstrip("#").upper() if text is not None else ""
                fill = (cell.get(FO + "background-color") or "").lstrip("#").upper() if cell is not None else ""
                align = para.get(FO + "text-align") if para is not None else None
                style = Style(
                    bold=text is not None and text.get(FO + "font-weight") in ("bold", "600", "700", "800", "900"),
                    italic=text is not None and text.get(FO + "font-style") == "italic",
                    underline=text is not None and text.get(S + "text-underline-style") not in (None, "none"),
                    strike=text is not None and text.get(S + "text-line-through-style") not in (None, "none"),
                    color=color if re.fullmatch(r"[0-9A-F]{6}", color) and color != "000000" else None,
                    fill=fill if re.fullmatch(r"[0-9A-F]{6}", fill) and fill != "FFFFFF" else None,
                    align={"start": "left", "end": "right", "center": "center", "left": "left",
                           "right": "right"}.get(align))
                self.cell_styles[name] = len(self.workbook.styles)
                self.workbook.styles.append(style)

    def read_sheet(self, index: int, on_rows=None, batch: int = 2000, cancelled=lambda: False) -> Sheet:
        sheet = self.workbook.sheets[index]
        if sheet.complete:
            return sheet
        table_no = -1
        inside = False
        row = 0
        pending = 0
        with self._open_content() as f:
            for event, el in ET.iterparse(f, events=("start", "end")):
                tag = el.tag
                if event == "start":
                    if tag == TB + "table":
                        table_no += 1
                        inside = table_no == index
                    continue
                if not inside:
                    if tag == TB + "table-row":
                        el.clear()
                    continue
                if tag == TB + "table-column":
                    pass  # handled at the end of table-columns below
                elif tag == TB + "table-columns" or tag == TB + "table-header-columns":
                    pass
                elif tag == TB + "table-row":
                    repeat = int(el.get(TB + "number-rows-repeated", "1"))
                    hidden = el.get(TB + "visibility") in ("collapse", "filter")
                    cells = self._row_cells(el, row, sheet)
                    if not cells and repeat > 1:
                        if hidden:
                            sheet.hidden_rows.update(range(row, row + min(repeat, MAX_REPEAT)))
                        row += repeat  # empty padding: no rows stored
                        el.clear()
                        continue
                    for k in range(min(repeat, MAX_REPEAT)):
                        if cells:
                            sheet.rows[row + k] = cells if k == 0 else dict(cells)
                        if hidden:
                            sheet.hidden_rows.add(row + k)
                    if cells:
                        sheet.max_row = row + min(repeat, MAX_REPEAT)
                        sheet.max_col = max(sheet.max_col, max(cells) + 1)
                    row += repeat
                    el.clear()
                    pending += 1
                    if pending >= batch:
                        pending = 0
                        if cancelled():
                            return sheet
                        if on_rows:
                            on_rows(sheet)
                elif tag == TB + "table":
                    col = 0
                    for column in el.iter(TB + "table-column"):
                        repeat = min(int(column.get(TB + "number-columns-repeated", "1")), MAX_COLS)
                        width = self.col_styles.get(column.get(TB + "style-name"))
                        for c in range(col, min(col + repeat, MAX_COLS)):
                            if width:
                                sheet.col_widths[c] = width
                            if column.get(TB + "visibility") in ("collapse", "filter"):
                                sheet.hidden_cols.add(c)
                        col += repeat
                    break
        sheet.hidden_rows = {r for r in sheet.hidden_rows if r < sheet.max_row}
        sheet.hidden_cols = {c for c in sheet.hidden_cols if c < max(sheet.max_col, 1)}
        sheet.complete = True
        if on_rows:
            on_rows(sheet)
        return sheet

    def _row_cells(self, row_el, row, sheet) -> dict:
        cells = {}
        col = 0
        for c in row_el:
            if c.tag not in (TB + "table-cell", TB + "covered-table-cell"):
                continue
            repeat = int(c.get(TB + "number-columns-repeated", "1"))
            cell = None if c.tag == TB + "covered-table-cell" else self._cell(c)
            if cell is not None:
                for k in range(min(repeat, MAX_COLS - col)):
                    cells[col + k] = cell
                span_c = int(c.get(TB + "number-columns-spanned", "1"))
                span_r = int(c.get(TB + "number-rows-spanned", "1"))
                if span_c > 1 or span_r > 1:
                    sheet.merges.append((row, col, row + span_r - 1, col + span_c - 1))
            col += repeat
            if col >= MAX_COLS:
                break
        return cells

    def _cell(self, c):
        kind = c.get(O + "value-type")
        text = "\n".join("".join(p.itertext()) for p in c if p.tag == T + "p")
        formula = c.get(TB + "formula")
        if formula:
            formula = "=" + re.sub(r"^of:=", "", formula).replace("[.", "").replace("]", "").replace(":.", ":")
        style = self.cell_styles.get(c.get(TB + "style-name"), 0)
        if kind is None and not text:
            return None
        value = text
        if kind in ("float", "percentage", "currency"):
            try:
                value = float(c.get(O + "value"))
            except (TypeError, ValueError):
                value = text
        elif kind == "boolean":
            value = c.get(O + "boolean-value") == "true"
        elif kind == "date":
            value = c.get(O + "date-value") or text
        error = text if text.startswith("#") and text.endswith(("!", "?", "A")) and kind in (None, "string") \
            and formula else None
        return Cell(value=value, formula=formula, style=style, text=text, error=error)

    def close(self):
        if not self.flat:
            self.zf.close()


def _unescape(s: str) -> str:
    return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'")
