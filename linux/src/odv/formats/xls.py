"""Excel 97–2003 (.xls) into the workbook model, through xlrd (BSD).

Values, number formats (dates included), bold/italic/underline/strike,
font and fill colours, alignment, merged cells, column widths and hidden
rows, columns and sheets are read when the file carries its formatting.
xlrd gives the saved values only: formulas are not available as text."""

from odv.formats.sheet import MAX_COLS, Cell, Sheet, SheetError, Style, Workbook


def _hex(colour_map, index):
    rgb = colour_map.get(index) if colour_map and index is not None else None
    return "%02X%02X%02X" % rgb if rgb else None


class XlsReader:
    def __init__(self, path: str):
        try:
            import xlrd
        except ImportError as e:
            raise SheetError("xlrd is not installed") from e
        self.xlrd = xlrd
        try:
            try:
                self.book = xlrd.open_workbook(path, formatting_info=True, on_demand=True)
            except NotImplementedError:
                self.book = xlrd.open_workbook(path, on_demand=True)
        except xlrd.biffh.XLRDError as e:
            raise SheetError(str(e)) from e
        self.workbook = Workbook(date1904=self.book.datemode == 1)
        self._xf_style = {}
        self._styles()
        for i in range(self.book.nsheets):
            name = self.book.sheet_names()[i]
            hidden = False
            try:
                hidden = self.book.sheet_by_index(i).visibility != 0
            except Exception:  # noqa: BLE001 - visibility is best effort
                pass
            self.workbook.sheets.append(Sheet(name=name, hidden=hidden))

    def _styles(self):
        book = self.book
        xf_list = getattr(book, "xf_list", None) or []
        colours = getattr(book, "colour_map", {})
        for i, xf in enumerate(xf_list):
            fmt = book.format_map.get(xf.format_key)
            font = book.font_list[xf.font_index] if xf.font_index < len(book.font_list) else None
            colour = _hex(colours, font.colour_index) if font else None
            fill = None
            if xf.background and xf.background.fill_pattern:
                fill = _hex(colours, xf.background.pattern_colour_index)
            align = {1: "left", 2: "center", 3: "right", 6: "center"}.get(xf.alignment.hor_align) if xf.alignment else None
            style = Style(fmt=fmt.format_str if fmt else "General",
                          bold=bool(font and font.bold), italic=bool(font and font.italic),
                          underline=bool(font and font.underline_type), strike=bool(font and font.struck_out),
                          color=colour if colour not in (None, "000000") else None,
                          fill=fill if fill not in (None, "FFFFFF") else None, align=align)
            self._xf_style[i] = len(self.workbook.styles)
            self.workbook.styles.append(style)

    def read_sheet(self, index: int, on_rows=None, batch: int = 2000, cancelled=lambda: False) -> Sheet:
        sheet = self.workbook.sheets[index]
        if sheet.complete:
            return sheet
        x = self.xlrd
        ws = self.book.sheet_by_index(index)
        sheet.max_col = min(ws.ncols, MAX_COLS)
        for r in range(ws.nrows):
            cells = {}
            for c in range(sheet.max_col):
                cell = ws.cell(r, c)
                kind = cell.ctype
                if kind in (x.XL_CELL_EMPTY, x.XL_CELL_BLANK):
                    if kind == x.XL_CELL_BLANK and self._xf_style.get(cell.xf_index):
                        cells[c] = Cell(value=None, style=self._xf_style[cell.xf_index])
                    continue
                style = self._xf_style.get(cell.xf_index, 0)
                if kind == x.XL_CELL_ERROR:
                    cells[c] = Cell(value=None, error=x.error_text_from_code.get(cell.value, "#ERR"), style=style)
                elif kind == x.XL_CELL_BOOLEAN:
                    cells[c] = Cell(value=bool(cell.value), style=style)
                elif kind in (x.XL_CELL_NUMBER, x.XL_CELL_DATE):
                    cells[c] = Cell(value=float(cell.value), style=style)
                else:
                    cells[c] = Cell(value=str(cell.value), style=style)
            if cells:
                sheet.rows[r] = cells
            if r % batch == batch - 1:
                sheet.max_row = r + 1
                if cancelled():
                    return sheet
                if on_rows:
                    on_rows(sheet)
        sheet.max_row = ws.nrows
        for rlo, rhi, clo, chi in getattr(ws, "merged_cells", []) or []:
            sheet.merges.append((rlo, clo, rhi - 1, chi - 1))
        for c, info in (getattr(ws, "colinfo_map", {}) or {}).items():
            if c < MAX_COLS:
                sheet.col_widths[c] = info.width / 256
                if info.hidden:
                    sheet.hidden_cols.add(c)
        for r, info in (getattr(ws, "rowinfo_map", {}) or {}).items():
            if info.hidden and r < ws.nrows:
                sheet.hidden_rows.add(r)
        sheet.freeze = (getattr(ws, "horz_split_pos", 0) or 0, getattr(ws, "vert_split_pos", 0) or 0)
        sheet.complete = True
        if on_rows:
            on_rows(sheet)
        return sheet

    def close(self):
        self.book.release_resources()
