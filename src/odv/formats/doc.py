"""Word 97–2003 (.doc): the text, read from the binary file. olefile opens
the OLE container; the text comes from the piece table (CLX) the FIB
points to, each piece in Windows-1252 or UTF-16. Field instructions are
dropped (their shown result kept), tables are rebuilt from the cell marks.
Formatting is not read: the document says so in a banner; a faithful view
needs LibreOffice. Pure Python, for a thread.
"""

import re
import struct

from odv.i18n import _
from odv.mdrender import Doc, Table


class DocError(Exception):
    pass


def extract_text(word: bytes, table: bytes) -> str:
    if len(word) < 0x200 or struct.unpack_from("<H", word, 0)[0] != 0xA5EC:
        raise DocError("not a Word 97–2003 document")
    flags = struct.unpack_from("<H", word, 0x0A)[0]
    if flags & 0x0100:
        raise DocError("the document is password-protected")
    pos = 32
    csw = struct.unpack_from("<H", word, pos)[0]
    pos += 2 + csw * 2
    cslw = struct.unpack_from("<H", word, pos)[0]
    lw = pos + 2
    ccp_text = struct.unpack_from("<i", word, lw + 3 * 4)[0]
    pos = lw + cslw * 4
    pos += 2  # cbRgFcLcb
    fc_clx, lcb_clx = struct.unpack_from("<II", word, pos + 33 * 8)
    clx = table[fc_clx:fc_clx + lcb_clx]
    i = 0
    while i < len(clx) and clx[i] == 0x01:  # Prc: property modifiers we do not need
        cb = struct.unpack_from("<H", clx, i + 1)[0]
        i += 3 + cb
    if i >= len(clx) or clx[i] != 0x02:
        raise DocError("the piece table is missing")
    lcb = struct.unpack_from("<I", clx, i + 1)[0]
    plc = clx[i + 5:i + 5 + lcb]
    n = (lcb - 4) // 12
    cps = struct.unpack_from(f"<{n + 1}I", plc, 0)
    out = []
    remaining = ccp_text if ccp_text > 0 else cps[-1]
    for k in range(n):
        if remaining <= 0:
            break
        start, end = cps[k], cps[k + 1]
        count = min(end - start, remaining)
        fc_raw = struct.unpack_from("<I", plc, (n + 1) * 4 + k * 8 + 2)[0]
        if fc_raw & 0x40000000:
            offset = (fc_raw & 0x3FFFFFFF) // 2
            out.append(word[offset:offset + count].decode("cp1252", "replace"))
        else:
            offset = fc_raw & 0x3FFFFFFF
            out.append(word[offset:offset + count * 2].decode("utf-16-le", "replace"))
        remaining -= count
    return "".join(out)


def _strip_fields(text: str) -> str:
    """\\x13 instruction \\x14 result \\x15 → result (nested fields too)."""
    out = []
    stack = []  # for each open field: are we past its separator?
    for ch in text:
        if ch == "\x13":
            stack.append(False)
        elif ch == "\x14" and stack:
            stack[-1] = True
        elif ch == "\x15" and stack:
            stack.pop()
        elif not stack or all(stack):
            out.append(ch)
    return "".join(out)


def to_doc(text: str) -> Doc:
    text = _strip_fields(text)
    for ch in "\x01\x02\x05\x08":  # pictures, note references, annotations, drawn objects
        text = text.replace(ch, "")
    text = text.replace("\x0b", "\n").replace("\x1e", "-").replace("\x1f", "­").replace("\xa0", " ")
    doc = Doc()

    def emit(s, tags=("p",)):
        ops = doc.ops
        if ops and ops[-1][0] == "text" and ops[-1][2] == tags:
            ops[-1] = ("text", ops[-1][1] + s, tags)
        else:
            ops.append(("text", s, tags))
        doc.length += len(s)

    rows: list[list[str]] = []
    row: list[str] = []

    def flush_table():
        nonlocal rows
        if rows:
            width = max(len(r) for r in rows)
            rows = [r + [""] * (width - len(r)) for r in rows]
            doc.ops.append(("table", Table(rows[0], rows[1:], ["left"] * width)))
            doc.length += 1
            emit("\n", ())
        rows = []

    # The row-end mark plays the paragraph mark's part: what follows it is a
    # new paragraph. (Without the table formatting an empty cell inside a
    # row reads like a row end; a known limit of text-only reading.)
    text = re.sub("\x07\x07(?=[^\x07\r])", "\x07\x07\r", text)
    for paragraph in text.split("\r"):
        if "\x07" in paragraph:
            # Each \x07 ends a cell; a \x07 right after one, with no text, ends the row.
            buf, after_mark = "", False
            for ch in paragraph:
                if ch == "\x07":
                    if after_mark and not buf:
                        rows.append(row)
                        row = []
                        after_mark = False
                    else:
                        row.append(buf.strip())
                        buf, after_mark = "", True
                else:
                    buf += ch
                    after_mark = after_mark and ch.isspace()
            continue
        if row:
            rows.append(row)
            row = []
        flush_table()
        if "\x0c" in paragraph:
            for j, part in enumerate(paragraph.split("\x0c")):
                if j:
                    doc.ops.append(("hr",))
                    doc.length += 1
                    emit("\n", ())
                if part.strip():
                    emit(part.strip() + "\n")
            continue
        emit(paragraph.rstrip() + "\n")
    if row:
        rows.append(row)
    flush_table()
    doc.note = _("Plain text from a Word 97–2003 document: formatting is not shown.")
    return doc


def build(text, path: str = "") -> Doc:
    try:
        import olefile
    except ImportError as e:
        raise DocError("olefile is not installed") from e
    if not olefile.isOleFile(path):
        raise DocError("not a Word 97–2003 document")
    with olefile.OleFileIO(path) as ole:
        if not ole.exists("WordDocument"):
            raise DocError("not a Word 97–2003 document")
        word = ole.openstream("WordDocument").read()
        flags = struct.unpack_from("<H", word, 0x0A)[0] if len(word) > 12 else 0
        name = "1Table" if flags & 0x0200 else "0Table"
        if not ole.exists(name):
            raise DocError("the table stream is missing")
        table = ole.openstream(name).read()
    return to_doc(extract_text(word, table))
