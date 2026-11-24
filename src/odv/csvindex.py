"""Row index over a delimited file, for tables of any size.

The file is memory-mapped and only the byte offset of each row is kept
(8 bytes per row); a row is parsed when the table asks for it. Building the
index runs in a worker thread and can be cancelled. Pure Python: no GTK.
"""

import bisect
import csv
import io
import mmap
import os
import re
from array import array
from collections import OrderedDict

_NL = re.compile(rb"\n")
BLOCK = 4 * 1024 * 1024


class CsvIndex:
    def __init__(self, path: str, delimiter: str | None = None):
        self.path = path
        self._f = open(path, "rb")
        self.size = os.fstat(self._f.fileno()).st_size
        self._mm = mmap.mmap(self._f.fileno(), 0, access=mmap.ACCESS_READ) if self.size else b""
        self.offsets = array("Q", [0])  # start of each row; one past the end for the last
        self.complete = False
        self.cancelled = False
        self.progress = 0.0
        self._cache: OrderedDict[int, list[str]] = OrderedDict()
        head = bytes(self._mm[:65536])
        self._bom = 3 if head.startswith(b"\xef\xbb\xbf") else 0
        self.dialect = self._dialect(head.decode("utf-8", "replace"), delimiter)

    @staticmethod
    def _dialect(sample: str, delimiter: str | None):
        class D(csv.excel):
            pass

        if delimiter:
            D.delimiter = delimiter
            return D
        lines = sample.splitlines()[:50]
        try:
            D.delimiter = csv.Sniffer().sniff("\n".join(lines), delimiters=",;\t|").delimiter
        except csv.Error:
            D.delimiter = ","
        return D

    @property
    def delimiter(self) -> str:
        return self.dialect.delimiter

    # -- building -------------------------------------------------------
    def build(self) -> None:
        mm, n = self._mm, self.size
        pos, in_quote = 0, False
        offsets = self.offsets
        while pos < n and not self.cancelled:
            end = min(n, pos + BLOCK)
            block = mm[pos:end]
            if not in_quote and b'"' not in block:
                offsets.extend(m.end() + pos for m in _NL.finditer(block))
            else:
                start = 0
                while True:
                    nl = block.find(b"\n", start)
                    if nl == -1:
                        if block.count(b'"', start) & 1:
                            in_quote = not in_quote
                        break
                    if block.count(b'"', start, nl) & 1:
                        in_quote = not in_quote
                    if not in_quote:
                        offsets.append(pos + nl + 1)
                    start = nl + 1
            pos = end
            self.progress = pos / n
        if not self.cancelled:
            if offsets[-1] != n:
                offsets.append(n)
            self.progress = 1.0
            self.complete = True

    @property
    def rows_indexed(self) -> int:
        """Rows whose end is known, the header included."""
        return len(self.offsets) - 1

    # -- reading --------------------------------------------------------
    def row(self, i: int) -> list[str]:
        cached = self._cache.get(i)
        if cached is not None:
            self._cache.move_to_end(i)
            return cached
        if i + 1 >= len(self.offsets):
            return []
        start = self.offsets[i] + (self._bom if i == 0 else 0)
        text = self._mm[start:self.offsets[i + 1]].decode("utf-8", "replace").rstrip("\r\n")
        try:
            cells = next(csv.reader(io.StringIO(text), self.dialect))
        except (StopIteration, csv.Error):
            cells = [text]
        self._cache[i] = cells
        if len(self._cache) > 4096:
            self._cache.popitem(last=False)
        return cells

    def row_of_offset(self, offset: int) -> int:
        """The row containing byte ``offset`` (0 = the header)."""
        return max(0, bisect.bisect_right(self.offsets, offset) - 1)

    def find(self, needle: str, start_row: int = 1, forward: bool = True) -> int | None:
        """The first data row after (or before) ``start_row`` whose raw text
        contains ``needle``, ignoring case; wraps around. None if absent.
        Scans the mapped bytes with a regex, so it is fast on huge files."""
        if not needle or not self.size:
            return None
        pattern = re.compile(re.escape(needle.encode("utf-8")), re.IGNORECASE)
        mm, n_rows = self._mm, self.rows_indexed
        if n_rows < 2:
            return None
        first_data = self.offsets[1]
        if forward:
            begin = self.offsets[min(start_row + 1, n_rows)] if start_row + 1 < n_rows else first_data
            m = pattern.search(mm, begin) or pattern.search(mm, first_data, begin)
            return self.row_of_offset(m.start()) if m else None
        end = self.offsets[max(start_row, 1)]
        last = None
        for m in pattern.finditer(mm, first_data, end):
            last = m
        if last is None:
            for m in pattern.finditer(mm, end):
                last = m
        return self.row_of_offset(last.start()) if last else None

    def close(self) -> None:
        self.cancelled = True
        try:
            if isinstance(self._mm, mmap.mmap):
                self._mm.close()
        except BufferError:
            pass
        self._f.close()


def _is_number(text: str) -> bool:
    t = text.strip().replace(",", "").replace("_", "")
    if not t:
        return True
    try:
        float(t)
        return True
    except ValueError:
        return False


def detect_numeric(rows: list[list[str]]) -> list[bool]:
    """Columns whose sampled values are all numbers (or empty), right-aligned."""
    width = max((len(r) for r in rows), default=0)
    out = []
    for j in range(width):
        values = [r[j] for r in rows if j < len(r)]
        out.append(any(v.strip() for v in values) and all(_is_number(v) for v in values))
    return out
