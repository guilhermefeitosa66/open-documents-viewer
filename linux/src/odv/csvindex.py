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
        self.encoding = self._encoding(head)
        self.dialect = self._dialect(head.decode(self.encoding, "replace"), delimiter)

    @staticmethod
    def _encoding(head: bytes) -> str:
        """UTF-8 unless the sample is not valid UTF-8 (then Windows-1252)."""
        try:
            head.decode("utf-8")
            return "utf-8"
        except UnicodeDecodeError as e:
            if e.reason == "unexpected end of data" and e.start >= len(head) - 3:
                return "utf-8"  # the sample was cut inside a character
            return "cp1252"

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
        cells = self._parse(i)
        self._cache[i] = cells
        if len(self._cache) > 4096:
            self._cache.popitem(last=False)
        return cells

    def _parse(self, i: int) -> list[str]:
        start = self.offsets[i] + (self._bom if i == 0 else 0)
        text = self._mm[start:self.offsets[i + 1]].decode(self.encoding, "replace").rstrip("\r\n")
        try:
            return next(csv.reader(io.StringIO(text), self.dialect))
        except (StopIteration, csv.Error):
            return [text]

    def sort_order(self, column: int, descending: bool = False, numeric: bool = False,
                   cancelled=lambda: False, progress=None) -> array | None:
        """Data row numbers (1..n) ordered by ``column``; the file is not
        touched. Empty cells go last either way. None if cancelled."""
        n = self.rows_indexed
        keys = []
        empty = []
        # One streaming csv.reader over the file is far faster than parsing
        # row by row; its rows line up with the index (both honour quotes).
        with open(self.path, encoding=self.encoding, errors="replace", newline="") as f:
            if self._bom:
                f.read(1)
            reader = csv.reader(f, self.dialect)
            next(reader, None)  # the header
            for i, cells in enumerate(reader, 1):
                if i >= n:
                    break
                if i % 100000 == 0:
                    if cancelled():
                        return None
                    if progress:
                        progress(i / n)
                value = cells[column].strip() if column < len(cells) else ""
                if not value:
                    empty.append(i)
                    continue
                if numeric:
                    try:
                        key = float(value.replace(",", "").replace("_", ""))
                    except ValueError:
                        key = float("inf")
                else:
                    key = value.casefold()
                keys.append((key, i))
        keys.sort(key=lambda kv: kv[0], reverse=descending)
        order = array("Q", (i for _k, i in keys))
        order.extend(empty)
        return order

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
