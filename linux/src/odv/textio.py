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


# Encodings offered in "Reopen with Encoding": (codec, label).
ENCODINGS = [
    ("utf-8", "Unicode (UTF-8)"), ("utf-16-le", "Unicode (UTF-16 LE)"), ("utf-16-be", "Unicode (UTF-16 BE)"),
    ("cp1252", "Western (Windows-1252)"), ("iso-8859-1", "Western (ISO-8859-1)"),
    ("iso-8859-15", "Western (ISO-8859-15)"), ("mac_roman", "Western (Mac Roman)"), ("cp437", "DOS (CP437)"),
    ("cp850", "DOS Latin 1 (CP850)"), ("cp1250", "Central European (Windows-1250)"),
    ("iso-8859-2", "Central European (ISO-8859-2)"), ("cp1251", "Cyrillic (Windows-1251)"),
    ("koi8-r", "Cyrillic (KOI8-R)"), ("cp1253", "Greek (Windows-1253)"), ("cp1254", "Turkish (Windows-1254)"),
    ("cp1255", "Hebrew (Windows-1255)"), ("cp1256", "Arabic (Windows-1256)"), ("shift_jis", "Japanese (Shift_JIS)"),
    ("euc-jp", "Japanese (EUC-JP)"), ("gb18030", "Chinese Simplified (GB18030)"),
    ("big5", "Chinese Traditional (Big5)"), ("euc-kr", "Korean (EUC-KR)"), ("cp874", "Thai (Windows-874)"),
]


def utf16_without_bom(head: bytes) -> str | None:
    """'utf-16-le' or 'utf-16-be' when text looks like UTF-16 with no BOM
    (mostly ASCII text: every other byte is zero)."""
    sample = head[:4096]
    if len(sample) < 4:
        return None
    even, odd = sample[0::2], sample[1::2]
    zeros_even = even.count(0) / len(even)
    zeros_odd = odd.count(0) / len(odd)
    if zeros_odd > 0.4 and zeros_even < 0.05:
        return "utf-16-le"
    if zeros_even > 0.4 and zeros_odd < 0.05:
        return "utf-16-be"
    return None


def looks_binary(head: bytes) -> bool:
    for bom, _ in _BOMS:
        if head.startswith(bom):
            return False
    if utf16_without_bom(head):
        return False
    return b"\x00" in head[:BINARY_SNIFF]


def line_endings(text: str) -> str:
    """LF, CRLF, CR, Mixed or None (a single line)."""
    crlf = text.count("\r\n")
    cr = text.count("\r") - crlf
    lf = text.count("\n") - crlf
    kinds = [name for name, n in (("LF", lf), ("CRLF", crlf), ("CR", cr)) if n]
    if not kinds:
        return "None"
    return kinds[0] if len(kinds) == 1 else "Mixed"


def decode(data: bytes, truncated: bool = False, encoding: str | None = None) -> tuple[str, str]:
    """Decode bytes to text, returning (text, encoding name). ``truncated``
    says the read stopped before the end of the file; ``encoding`` forces one."""
    if encoding:
        if encoding.startswith("utf-16") and data[:2] in (codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE):
            data = data[2:]
        return data.decode(encoding, errors="replace"), encoding
    for bom, enc in _BOMS:
        if data.startswith(bom):
            return data.decode(enc, errors="replace"), enc.replace("-sig", "")
    utf16 = utf16_without_bom(data)
    if utf16:
        return data.decode(utf16, errors="replace"), utf16
    try:
        return data.decode("utf-8"), "utf-8"
    except UnicodeDecodeError as e:
        # A read cut in the middle of a multi-byte character is still UTF-8.
        if truncated and e.start >= len(data) - 3 and e.reason == "unexpected end of data":
            return data[: e.start].decode("utf-8", errors="replace"), "utf-8"
        return data.decode("cp1252", errors="replace"), "windows-1252"


class TextFile:
    __slots__ = ("path", "text", "encoding", "size", "truncated", "line_endings")

    def __init__(self, path, text, encoding, size, truncated):
        self.path = path
        self.text = text
        self.encoding = encoding
        self.size = size
        self.truncated = truncated
        self.line_endings = line_endings(text[:1_000_000])


def read_text(path: str, limit: int = MAX_TEXT_BYTES, encoding: str | None = None) -> TextFile:
    """Read up to ``limit`` bytes. Raises BinaryFile or OSError. ``encoding``
    forces a codec (and skips the binary check: the user asked for text)."""
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        data = f.read(limit)
    if not encoding and looks_binary(data):
        raise BinaryFile(path)
    truncated = size > len(data)
    text, enc = decode(data, truncated, encoding)
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
