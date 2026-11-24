"""PowerPoint 97–2003 (.ppt, .pps, .pot): the slides' text, placed where
the file puts it. olefile (BSD) opens the compound file; the records of
the "PowerPoint Document" stream are read here, in a thread.

The current edit (Current User → UserEditAtom) gives the persist
directory, which maps the document and each slide to their offsets. Each
slide's drawing gives the text boxes and their anchors; placeholder text
lives in the document's SlideListWithText and is referenced from the
boxes. Pictures, colours and fonts are not read: a banner says only the
text is shown (a faithful view comes from LibreOffice, task D2)."""

import struct

from odv.formats.slides import Paragraph, Presentation, Run, Shape, Slide
from odv.i18n import _

EMU_PER_MASTER_UNIT = 914400 / 576
ENCRYPTED_TOKEN = 0xF3D1C4DF
# TextHeaderAtom types
TITLE_TYPES = {0, 6}
BODY_TYPES = {1, 5, 7, 8}
CENTRED_TYPES = {5, 6}
NOTES_TYPE = 2


class PptError(Exception):
    pass


def _records(data, start, end):
    pos = start
    end = min(end, len(data))
    while pos + 8 <= end:
        verinst, rtype, rlen = struct.unpack_from("<HHI", data, pos)
        body = pos + 8
        if body + rlen > end:
            rlen = end - body
        yield verinst & 0xF, verinst >> 4, rtype, body, rlen
        pos = body + rlen


def _text(data, rtype, body, rlen):
    raw = data[body:body + rlen]
    if rtype == 0x0FA0:
        return raw.decode("utf-16-le", "replace")
    return raw.decode("cp1252", "replace")


class _Reader:
    def __init__(self, doc: bytes, current_user: bytes | None):
        self.doc = doc
        self.persist = {}
        self.doc_ref = None
        self._edits(current_user)

    def _edits(self, current_user):
        offset = None
        if current_user and len(current_user) >= 20:
            token, offset = struct.unpack_from("<II", current_user, 12)
            if token == ENCRYPTED_TOKEN:
                raise PptError(_("This presentation is password-protected."))
        seen = set()
        while offset is not None and offset not in seen and offset + 8 <= len(self.doc):
            seen.add(offset)
            rtype, rlen = struct.unpack_from("<HI", self.doc, offset + 2)
            if rtype != 0x0FF5 or rlen < 20:
                break
            last_edit, dir_offset, doc_ref = struct.unpack_from("<III", self.doc, offset + 16)
            if self.doc_ref is None:
                self.doc_ref = doc_ref
            self._directory(dir_offset)
            offset = last_edit or None
        if self.doc_ref is None or self.doc_ref not in self.persist:
            raise PptError("the presentation's edit records are missing or damaged")

    def _directory(self, offset):
        if offset + 8 > len(self.doc):
            return
        rlen = struct.unpack_from("<I", self.doc, offset + 4)[0]
        pos, end = offset + 8, min(len(self.doc), offset + 8 + rlen)
        while pos + 4 <= end:
            entry = struct.unpack_from("<I", self.doc, pos)[0]
            first, count = entry & 0xFFFFF, entry >> 20
            pos += 4
            for k in range(count):
                if pos + 4 > end:
                    break
                self.persist.setdefault(first + k, struct.unpack_from("<I", self.doc, pos)[0])
                pos += 4

    def container(self, ref):
        offset = self.persist.get(ref)
        if offset is None or offset + 8 > len(self.doc):
            return None
        rtype, rlen = struct.unpack_from("<HI", self.doc, offset + 2)
        return rtype, offset + 8, rlen

    # -- the document ----------------------------------------------------------------------
    def build(self) -> Presentation:
        out = Presentation(note=_("Text from a PowerPoint 97–2003 presentation: pictures and formatting are not shown."))
        found = self.container(self.doc_ref)
        if found is None or found[0] != 0x03E8:
            raise PptError("the document record is missing")
        _t, body, rlen = found
        slide_lists = {}
        for ver, inst, rtype, b, n in _records(self.doc, body, body + rlen):
            if rtype == 0x03E9 and n >= 8:
                x, y = struct.unpack_from("<ii", self.doc, b)
                if x > 0 and y > 0:
                    out.width, out.height = int(x * EMU_PER_MASTER_UNIT), int(y * EMU_PER_MASTER_UNIT)
            elif rtype == 0x0FF0:
                slide_lists[inst] = self._slide_list(b, n)
        notes = {}
        for entry in slide_lists.get(2, []):
            found = self.container(entry["ref"])
            if found is not None and found[0] == 0x03F0:
                slide_id, text = self._notes(found[1], found[2])
                if slide_id is not None:
                    notes[slide_id] = text
        for entry in slide_lists.get(0, []):
            out.slides.append(self._slide(entry, out, notes.get(entry["id"], "")))
        return out

    def _slide_list(self, body, rlen):
        entries = []
        current_type = 4
        for _v, _i, rtype, b, n in _records(self.doc, body, body + rlen):
            if rtype == 0x03F3 and n >= 16:
                ref, _flags, _texts, slide_id = struct.unpack_from("<IIiI", self.doc, b)
                entries.append({"ref": ref, "id": slide_id, "texts": []})
            elif rtype == 0x0F9F and n >= 4:
                current_type = struct.unpack_from("<I", self.doc, b)[0]
            elif rtype in (0x0FA0, 0x0FA8) and entries:
                entries[-1]["texts"].append((current_type, _text(self.doc, rtype, b, n)))
        return entries

    def _notes(self, body, rlen):
        slide_id = None
        texts = []
        for _v, _i, rtype, b, n in _records(self.doc, body, body + rlen):
            if rtype == 0x03F1 and n >= 4:
                slide_id = struct.unpack_from("<I", self.doc, b)[0]
        for kind, text, _anchor in self._boxes(body, rlen, []):
            if kind == NOTES_TYPE:
                texts.append(text)
        return slide_id, "\n".join(t.replace("\r", "\n").replace("\x0b", "\n") for t in texts).strip()

    def _slide(self, entry, pres, notes) -> Slide:
        slide = Slide(notes=notes)
        found = self.container(entry["ref"])
        boxes = []
        if found is not None and found[0] == 0x03EE:
            _t, body, rlen = found
            for _v, _i, rtype, b, n in _records(self.doc, body, body + rlen):
                if rtype == 0x03F9 and n >= 12:
                    flags = struct.unpack_from("<H", self.doc, b + 10)[0]
                    slide.hidden = bool(flags & 0x4)
            boxes = self._boxes(body, rlen, entry["texts"])
        placed = [b for b in boxes if b[2] is not None]
        if not placed:  # no drawing: stack the slide list's text
            w, h = pres.width, pres.height
            placed = []
            top = int(h * 0.05)
            for kind, text in entry["texts"]:
                height = int(h * (0.18 if kind in TITLE_TYPES else 0.7))
                placed.append((kind, text, (int(w * 0.06), top, int(w * 0.88), height)))
                top += height
        for kind, text, anchor in placed:
            x, y, w, h = anchor
            shape = Shape("rect", x, y, w, h, anchor="middle" if kind in TITLE_TYPES else "top")
            shape.paragraphs = self._paragraphs(kind, text)
            slide.shapes.append(shape)
            if kind in TITLE_TYPES and not slide.title:
                slide.title = " ".join(text.split())
        return slide

    @staticmethod
    def _paragraphs(kind, text):
        size = 40 if kind in TITLE_TYPES else 28 if kind in BODY_TYPES else 18
        align = "center" if kind in CENTRED_TYPES or kind in TITLE_TYPES else "left"
        out = []
        for line in text.split("\r"):
            para = Paragraph(align=align)
            para.runs.append(Run(line.replace("\x0b", "\n"), size=size))
            if kind in (1, 7, 8) and line.strip():
                para.bullet = "•"
            out.append(para)
        return out

    def _boxes(self, body, rlen, slide_texts):
        """(text type, text, anchor in EMU or None) for each text box of the
        drawing inside the record at ``body``."""
        out = []
        self._walk(body, rlen, slide_texts, out, None)
        return out

    def _walk(self, body, rlen, slide_texts, out, transform):
        for ver, _inst, rtype, b, n in _records(self.doc, body, body + rlen):
            if rtype == 0xF003:  # a group: its first shape gives the child coordinate system
                inner = transform
                first = next(iter(_records(self.doc, b, b + n)), None)
                if first is not None and first[2] == 0xF004:
                    group = self._group(first[3], first[4], transform)
                    if group is not None:
                        inner = group
                self._walk(b, n, slide_texts, out, inner)
            elif rtype == 0xF004:
                self._shape(b, n, slide_texts, out, transform)
            elif ver == 0xF:
                self._walk(b, n, slide_texts, out, transform)

    def _anchor(self, body, rlen, transform):
        for _v, _i, rtype, b, n in _records(self.doc, body, body + rlen):
            if rtype == 0xF010:
                if n == 8:
                    top, left, right, bottom = struct.unpack_from("<hhhh", self.doc, b)
                elif n >= 16:
                    top, left, right, bottom = struct.unpack_from("<iiii", self.doc, b)
                else:
                    continue
                k = EMU_PER_MASTER_UNIT
                return int(left * k), int(top * k), int((right - left) * k), int((bottom - top) * k)
            if rtype == 0xF00F and n >= 16 and transform is not None:
                left, top, right, bottom = struct.unpack_from("<iiii", self.doc, b)
                ox, oy, sx, sy, cx, cy = transform
                return (int(ox + (left - cx) * sx), int(oy + (top - cy) * sy),
                        int((right - left) * sx), int((bottom - top) * sy))
        return None

    def _group(self, body, rlen, transform):
        frame = None
        for _v, _i, rtype, b, n in _records(self.doc, body, body + rlen):
            if rtype == 0xF009 and n >= 16:
                frame = struct.unpack_from("<iiii", self.doc, b)
        anchor = self._anchor(body, rlen, transform)
        if frame is None or anchor is None:
            return None
        left, top, right, bottom = frame
        x, y, w, h = anchor
        return x, y, w / max(1, right - left), h / max(1, bottom - top), left, top

    def _shape(self, body, rlen, slide_texts, out, transform):
        text_box = None
        for _v, _i, rtype, b, n in _records(self.doc, body, body + rlen):
            if rtype == 0xF00D:
                text_box = (b, n)
        if text_box is None:
            return
        anchor = self._anchor(body, rlen, transform)
        kind, chunks = 4, []
        for _v, _i, rtype, b, n in _records(self.doc, text_box[0], text_box[0] + text_box[1]):
            if rtype == 0x0F9E and n >= 4:  # placeholder text kept in the slide list
                index = struct.unpack_from("<i", self.doc, b)[0]
                if 0 <= index < len(slide_texts):
                    kind = slide_texts[index][0]
                    chunks.append(slide_texts[index][1])
            elif rtype == 0x0F9F and n >= 4:
                kind = struct.unpack_from("<I", self.doc, b)[0]
            elif rtype in (0x0FA0, 0x0FA8):
                chunks.append(_text(self.doc, rtype, b, n))
        text = "\r".join(chunks)
        if text.strip():
            out.append((kind, text, anchor))


def build(path: str) -> Presentation:
    try:
        import olefile
    except ImportError as e:
        raise PptError("olefile is not installed") from e
    if not olefile.isOleFile(path):
        raise PptError("not a PowerPoint 97–2003 presentation")
    with olefile.OleFileIO(path) as ole:
        if not ole.exists("PowerPoint Document"):
            raise PptError("not a PowerPoint 97–2003 presentation")
        if ole.exists("EncryptedSummary"):
            raise PptError(_("This presentation is password-protected."))
        doc = ole.openstream("PowerPoint Document").read()
        user = ole.openstream("Current User").read() if ole.exists("Current User") else None
    return _Reader(doc, user).build()
