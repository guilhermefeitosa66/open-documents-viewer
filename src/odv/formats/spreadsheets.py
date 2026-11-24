"""Opens a workbook by extension; each reader has ``workbook`` and
``read_sheet(index, on_rows)``."""

import os


def open_workbook(path: str):
    ext = os.path.splitext(path)[1].lower()
    if ext in (".xlsx", ".xlsm", ".xltx", ".xltm"):
        from odv.formats.sheet import XlsxReader

        return XlsxReader(path)
    raise ValueError(f"unsupported spreadsheet: {ext}")
