"""Reading and decoding files. Pure Python: no GTK."""

import codecs
import os

MAX_TEXT_BYTES = 64 * 1024 * 1024  # what a text view will hold; the rest is cut
HIGHLIGHT_LIMIT = 2 * 1024 * 1024  # above this, syntax highlighting is off
BINARY_SNIFF = 8192

_BOMS = (
    (codecs.BOM_UTF8, "utf-8-sig"),
    (codecs.BOM_UTF32_LE, "utf-32"),
    (codecs.BOM_UTF32_BE, "utf-32"),
    (codecs.BOM_UTF16_LE, "utf-16"),
    (codecs.BOM_UTF16_BE, "utf-16"),
)


class BinaryFile(Exception):
    pass


def looks_binary(head: bytes) -> bool:
    for bom, _ in _BOMS:
        if head.startswith(bom):
            return False
    return b"\x00" in head[:BINARY_SNIFF]


def decode(data: bytes, truncated: bool = False) -> tuple[str, str]:
    """Decode bytes to text, returning (text, encoding name). ``truncated``
    says the read stopped before the end of the file."""
    for bom, enc in _BOMS:
        if data.startswith(bom):
            return data.decode(enc, errors="replace"), enc.replace("-sig", "")
    try:
        return data.decode("utf-8"), "utf-8"
    except UnicodeDecodeError as e:
        # A read cut in the middle of a multi-byte character is still UTF-8.
        if truncated and e.start >= len(data) - 3 and e.reason == "unexpected end of data":
            return data[: e.start].decode("utf-8", errors="replace"), "utf-8"
        return data.decode("cp1252", errors="replace"), "windows-1252"


class TextFile:
    __slots__ = ("path", "text", "encoding", "size", "truncated")

    def __init__(self, path, text, encoding, size, truncated):
        self.path = path
        self.text = text
        self.encoding = encoding
        self.size = size
        self.truncated = truncated


def read_text(path: str, limit: int = MAX_TEXT_BYTES) -> TextFile:
    """Read up to ``limit`` bytes. Raises BinaryFile or OSError."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        data = f.read(limit)
    if looks_binary(data):
        raise BinaryFile(path)
    truncated = size > len(data)
    text, enc = decode(data, truncated)
    return TextFile(path, text, enc, size, truncated)


def human_size(n: float) -> str:
    if n < 1024:
        return f"{int(n)} bytes"
    for unit in ("KB", "MB", "GB"):
        n /= 1024
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if n >= 100 else f"{n:.1f} {unit}"
    return ""


def split_chunks(text: str, size: int = 256 * 1024) -> list[str]:
    """Split text into chunks of about ``size`` characters, at line ends."""
    if len(text) <= size:
        return [text]
    out, start, n = [], 0, len(text)
    while start < n:
        end = min(n, start + size)
        if end < n:
            nl = text.find("\n", end)
            end = n if nl == -1 or nl - end > size else nl + 1
        out.append(text[start:end])
        start = end
    return out
