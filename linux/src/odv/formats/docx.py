"""DOCX (Office Open XML) → rich-text operations. Pure Python (zipfile +
ElementTree), for a thread; no dependency.

A fluid, unpaginated rendering: headings (by style or outline level) feed
the outline; bold, italic, underline, strike, colour, size, alignment;
bulleted and numbered lists from numbering.xml; tables (merged cells keep
their text in the first cell); images from word/media; external and
internal links; header and footer, dimmed; footnotes and endnotes listed
at the end; page breaks as rules. Field instructions are dropped, their
shown result kept. Macros are never looked at.
"""

import posixpath
import re
import zipfile
import xml.etree.ElementTree as ET

from odv.formats import protect
from odv.mdrender import Doc, Heading, Link, Table, slugify

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
V = "{urn:schemas-microsoft-com:vml}"
REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"
MAX_PART = 64 * 1024 * 1024

BULLET_FORMATS = {"bullet", "none"}


class DocxError(Exception):
    pass


def _val(el, name="val"):
    return el.get(W + name) if el is not None else None


def _on(el) -> bool:
    """A toggle property (<w:b/>, <w:b w:val="0"/>…)."""
    if el is None:
        return False
    return _val(el) not in ("0", "false", "off", "none")


def _roman(n: int) -> str:
    out = ""
    for value, sym in ((1000, "m"), (900, "cm"), (500, "d"), (400, "cd"), (100, "c"), (90, "xc"), (50, "l"),
                       (40, "xl"), (10, "x"), (9, "ix"), (5, "v"), (4, "iv"), (1, "i")):
        while n >= value:
            out += sym
            n -= value
    return out


def _format_number(n: int, fmt: str) -> str:
    if fmt in ("lowerLetter", "upperLetter"):
        s = ""
        while n > 0:
            n, rem = divmod(n - 1, 26)
            s = chr(97 + rem) + s
        return s.upper() if fmt == "upperLetter" else s
    if fmt == "lowerRoman":
        return _roman(n)
    if fmt == "upperRoman":
        return _roman(n).upper()
    return str(n)


class _Docx:
    def __init__(self, zf: zipfile.ZipFile):
        self.zf = zf
        self.names = set(zf.namelist())
        self.doc = Doc()
        self.rels = self._rels("word/_rels/document.xml.rels")
        self.styles = {}  # styleId -> {"name", "level", "bold", "italic", "size", "color", "align", "based"}
        self.default_size = 22
        self.numbering = {}  # numId -> {ilvl: (fmt, lvlText, start)}
        self.counters = {}  # (numId, ilvl) -> current number
        self.notes = {}  # ("footnote"|"endnote", id) -> text
        self.note_order = []
        self.bookmarks = []
        self.slugs = {}
        self._load_styles()
        self._load_numbering()
        self._load_notes("word/footnotes.xml", "footnote")
        self._load_notes("word/endnotes.xml", "endnote")

    # -- package ----------------------------------------------------------------
    def read(self, name):
        if name not in self.names:
            return None
        info = self.zf.getinfo(name)
        if info.file_size > MAX_PART:
            return None
        return self.zf.read(info)

    def xml(self, name):
        data = self.read(name)
        if data is None:
            return None
        try:
            return ET.fromstring(data)
        except ET.ParseError:
            return None

    def _rels(self, name):
        root = self.xml(name)
        rels = {}
        if root is not None:
            base = posixpath.dirname(posixpath.dirname(name))
            for rel in root.iter(REL + "Relationship"):
                target = rel.get("Target", "")
                if rel.get("TargetMode") != "External" and not target.startswith("/"):
                    target = posixpath.normpath(posixpath.join(base, target))
                rels[rel.get("Id")] = (target.lstrip("/"), rel.get("TargetMode") == "External")
        return rels

    def _load_styles(self):
        root = self.xml("word/styles.xml")
        if root is None:
            return
        sz = root.find(f"{W}docDefaults/{W}rPrDefault/{W}rPr/{W}sz")
        if sz is not None and (_val(sz) or "").isdigit():
            self.default_size = int(_val(sz))
        for style in root.iter(W + "style"):
            sid = style.get(W + "styleId")
            name = _val(style.find(W + "name")) or ""
            ppr, rpr = style.find(W + "pPr"), style.find(W + "rPr")
            level = None
            m = re.match(r"(?i)heading\s*(\d)", name)
            if m:
                level = int(m.group(1))
            elif name.lower() == "title":
                level = 0
            elif name.lower() == "subtitle":
                level = -1
            outline = ppr.find(W + "outlineLvl") if ppr is not None else None
            if level is None and outline is not None and (_val(outline) or "").isdigit():
                level = int(_val(outline)) + 1
            self.styles[sid] = {
                "name": name, "level": level,
                "bold": _on(rpr.find(W + "b")) if rpr is not None and rpr.find(W + "b") is not None else None,
                "italic": _on(rpr.find(W + "i")) if rpr is not None and rpr.find(W + "i") is not None else None,
                "align": _val(ppr.find(W + "jc")) if ppr is not None else None,
                "based": _val(style.find(W + "basedOn")),
            }

    def _style(self, sid, key):
        seen = 0
        while sid and sid in self.styles and seen < 10:
            value = self.styles[sid].get(key)
            if value is not None:
                return value
            sid = self.styles[sid].get("based")
            seen += 1
        return None

    def _load_numbering(self):
        root = self.xml("word/numbering.xml")
        if root is None:
            return
        abstract = {}
        for an in root.iter(W + "abstractNum"):
            levels = {}
            for lvl in an.iter(W + "lvl"):
                ilvl = int(lvl.get(W + "ilvl", "0"))
                fmt = _val(lvl.find(W + "numFmt")) or "decimal"
                text = _val(lvl.find(W + "lvlText")) or ""
                start = int(_val(lvl.find(W + "start")) or "1")
                levels[ilvl] = (fmt, text, start)
            abstract[an.get(W + "abstractNumId")] = levels
        for num in root.iter(W + "num"):
            aid = _val(num.find(W + "abstractNumId"))
            if aid in abstract:
                self.numbering[num.get(W + "numId")] = abstract[aid]

    def _load_notes(self, name, kind):
        root = self.xml(name)
        if root is None:
            return
        for note in root.iter(W + kind):
            nid = note.get(W + "id")
            if note.get(W + "type") in ("separator", "continuationSeparator", "continuationNotice"):
                continue
            text = " ".join("".join(t.text or "" for t in p.iter(W + "t")) for p in note.iter(W + "p")).strip()
            self.notes[(kind, nid)] = text

    # -- output -------------------------------------------------------------------
    def text(self, s, tags=()):
        if not s:
            return
        ops = self.doc.ops
        tags = tuple(tags)
        if ops and ops[-1][0] == "text" and ops[-1][2] == tags:
            ops[-1] = ("text", ops[-1][1] + s, tags)
        else:
            ops.append(("text", s, tags))
        self.doc.length += len(s)

    def widget(self, op):
        self.doc.ops.append(op)
        self.doc.length += 1

    # -- body -----------------------------------------------------------------------
    def body(self, root):
        for el in root:
            self.block(el)

    def block(self, el):
        tag = el.tag
        if tag == W + "p":
            self.paragraph(el)
        elif tag == W + "tbl":
            self.table(el)
        elif tag == W + "sdt":
            content = el.find(W + "sdtContent")
            if content is not None:
                self.body(content)

    def paragraph(self, p, extra=()):
        ppr = p.find(W + "pPr")
        sid = _val(ppr.find(W + "pStyle")) if ppr is not None else None
        level = self._style(sid, "level")
        if ppr is not None and level is None:
            outline = ppr.find(W + "outlineLvl")
            if outline is not None and (_val(outline) or "").isdigit() and int(_val(outline)) < 9:
                level = int(_val(outline)) + 1
        align = (_val(ppr.find(W + "jc")) if ppr is not None else None) or self._style(sid, "align")
        block = list(extra)
        if level is not None:
            block.append("h1" if level <= 1 else f"h{min(level, 6)}")
            if level == -1:
                block[-1] = "h3"
        else:
            block.append("p")
        if align in ("center", "right", "end", "both", "distribute"):
            block.append({"center": "align-center", "right": "align-right", "end": "align-right"}
                         .get(align, "align-fill"))
        num = ppr.find(W + "numPr") if ppr is not None else None
        marker = None
        if num is not None and level is None:
            num_id = _val(num.find(W + "numId"))
            ilvl = int(_val(num.find(W + "ilvl")) or "0")
            if num_id in self.numbering and num_id != "0":
                fmt, lvl_text, start = self.numbering[num_id].get(ilvl, ("bullet", "•", 1))
                for (nid, deeper) in list(self.counters):
                    if nid == num_id and deeper > ilvl:
                        del self.counters[(nid, deeper)]
                n = self.counters.get((num_id, ilvl), start - 1) + 1
                self.counters[(num_id, ilvl)] = n
                if fmt in BULLET_FORMATS:
                    marker = "•◦▪"[ilvl % 3] + " " if fmt == "bullet" else ""
                else:
                    label = re.sub(r"%(\d)", lambda m: _format_number(
                        self.counters.get((num_id, int(m.group(1)) - 1), 1) if int(m.group(1)) - 1 != ilvl else n,
                        self.numbering[num_id].get(int(m.group(1)) - 1, (fmt,))[0]), lvl_text)
                    marker = label + " "
                block = [t for t in block if t != "p"] + [f"ind{min(ilvl + 1, 8)}", "li"]
        start = self.doc.length
        title_parts = []
        if marker:
            self.text(marker, tuple(block) + ("marker",))
        self.runs(p, tuple(block), title_parts)
        self.text("\n", tuple(block))
        if level is not None and level >= 0:
            title = "".join(title_parts).strip()
            if title:
                self.doc.outline.append(Heading(max(1, level), title, start, 1))
                self.doc.headings.setdefault(slugify(title, self.slugs), start)
        for name in self.bookmarks:
            self.doc.headings.setdefault(name.lower(), start)
        self.bookmarks = []

    def runs(self, parent, block, title_parts, link_href=None):
        in_field = 0  # inside a complex field's instructions
        for el in parent:
            tag = el.tag
            if tag == W + "r":
                rpr = el.find(W + "rPr")
                for child in el:
                    ctag = child.tag
                    if ctag == W + "fldChar":
                        kind = _val(child, "fldCharType")
                        in_field = 1 if kind == "begin" else (0 if kind in ("separate", "end") else in_field)
                    elif in_field:
                        continue
                    elif ctag == W + "t":
                        self.emit(child.text or "", rpr, block, title_parts, link_href)
                    elif ctag == W + "tab":
                        self.emit("\t", rpr, block, title_parts, link_href)
                    elif ctag in (W + "br", W + "cr"):
                        if _val(child, "type") == "page":
                            self.text("\n", block)
                            self.widget(("hr",))
                        else:
                            self.emit("\n", rpr, block, title_parts, link_href)
                    elif ctag == W + "noBreakHyphen":
                        self.emit("‑", rpr, block, title_parts, link_href)
                    elif ctag == W + "softHyphen":
                        self.emit("­", rpr, block, title_parts, link_href)
                    elif ctag in (W + "footnoteReference", W + "endnoteReference"):
                        kind = "footnote" if ctag == W + "footnoteReference" else "endnote"
                        key = (kind, child.get(W + "id"))
                        if key in self.notes:
                            if key not in self.note_order:
                                self.note_order.append(key)
                            self.text(f"[{self.note_order.index(key) + 1}]", tuple(block) + ("dim",))
                    elif ctag == W + "drawing" or ctag == W + "pict":
                        self.image(child)
            elif tag == W + "hyperlink":
                rid = el.get(R + "id")
                anchor = el.get(W + "anchor")
                href = None
                if rid and rid in self.rels:
                    target, external = self.rels[rid]
                    href = target if external else None
                elif anchor:
                    href = "#" + anchor.lower()
                start = self.doc.length
                self.runs(el, block, title_parts, href)
                if href:
                    self.doc.links.append(Link(start, self.doc.length, href))
            elif tag in (W + "smartTag", W + "ins", W + "fldSimple", W + "customXml"):
                self.runs(el, block, title_parts, link_href)
            elif tag == W + "sdt":
                content = el.find(W + "sdtContent")
                if content is not None:
                    self.runs(content, block, title_parts, link_href)
            elif tag == W + "bookmarkStart":
                name = el.get(W + "name")
                if name and not name.startswith("_GoBack"):
                    self.bookmarks.append(name)

    def emit(self, s, rpr, block, title_parts, link_href):
        if not s:
            return
        tags = list(block)
        if rpr is not None:
            if rpr.find(W + "vanish") is not None and _on(rpr.find(W + "vanish")):
                return
            if _on(rpr.find(W + "b")):
                tags.append("bold")
            if _on(rpr.find(W + "i")):
                tags.append("italic")
            u = rpr.find(W + "u")
            if u is not None and _val(u) not in (None, "none", "0"):
                tags.append("underline")
            if _on(rpr.find(W + "strike")) or _on(rpr.find(W + "dstrike")):
                tags.append("strike")
            color = _val(rpr.find(W + "color"))
            if color and re.fullmatch(r"[0-9A-Fa-f]{6}", color) and color.upper() not in ("000000", "FFFFFF"):
                tags.append("fg-" + color.upper())
            sz = _val(rpr.find(W + "sz"))
            if sz and sz.isdigit() and not any(t.startswith("h") and t[1:].isdigit() for t in block):
                ratio = int(sz) / self.default_size
                if abs(ratio - 1) > 0.08:
                    tags.append(f"scale-{round(max(0.5, min(3.0, ratio)) * 100)}")
            va = _val(rpr.find(W + "vertAlign"))
            if va in ("superscript", "subscript"):
                tags.append("dim")
        if link_href:
            tags.append("link")
        self.text(s, tuple(tags))
        title_parts.append(s)

    def image(self, el):
        blip = next(el.iter(A + "blip"), None)
        rid = blip.get(R + "embed") if blip is not None else None
        if rid is None:
            img = next(el.iter(V + "imagedata"), None)
            rid = img.get(R + "id") if img is not None else None
        target = self.rels.get(rid, (None, True)) if rid else (None, True)
        data = self.read(target[0]) if target[0] and not target[1] else None
        if data:
            self.widget(("image", data))
        else:
            self.text("[image]", ("dim",))

    def table(self, tbl):
        rows = []
        header_rows = 0
        for tr in tbl.iter(W + "tr"):
            if tr.find(f"{W}trPr/{W}tblHeader") is not None and len(rows) == header_rows:
                header_rows += 1
            row = []
            for tc in tr.findall(W + "tc"):
                tcpr = tc.find(W + "tcPr")
                span = int(_val(tcpr.find(W + "gridSpan")) or "1") if tcpr is not None else 1
                vmerge = tcpr.find(W + "vMerge") if tcpr is not None else None
                continued = vmerge is not None and _val(vmerge) != "restart"
                text = "" if continued else " ".join(
                    "".join(t.text or "" for t in p.iter(W + "t")) for p in tc.iter(W + "p")).strip()
                row.append(text)
                row.extend([""] * (span - 1))
            rows.append(row)
        if not rows:
            return
        width = max(len(r) for r in rows)
        rows = [r + [""] * (width - len(r)) for r in rows]
        header = rows[0]
        self.widget(("table", Table(header, rows[1:], ["left"] * width)))
        self.text("\n")

    def header_footer_text(self, sect, kind) -> str:
        """The text of the section's default header or footer ("" if none)."""
        if sect is None:
            return ""
        for ref in sect.findall(f"{W}{kind}Reference"):
            if _val(ref, "type") in ("default", None):
                target = self.rels.get(ref.get(R + "id"), (None, True))[0]
                root = self.xml(target) if target else None
                if root is None:
                    continue
                texts = ["".join(t.text or "" for t in p.iter(W + "t")).strip() for p in root.iter(W + "p")]
                return "\n".join(t for t in texts if t)
        return ""


def build(text, path: str = "") -> Doc:
    try:
        zf = zipfile.ZipFile(protect.source(path))
    except (OSError, zipfile.BadZipFile) as e:
        raise DocxError(str(e)) from e
    with zf:
        d = _Docx(zf)
        root = d.xml("word/document.xml")
        if root is None:
            raise DocxError("word/document.xml is missing or damaged")
        body = root.find(W + "body")
        sect = body.find(W + "sectPr") if body is not None else None
        header = d.header_footer_text(sect, "header")
        if header:
            d.text(header + "\n", ("dim", "align-center"))
            d.widget(("hr",))
            d.text("\n")
        if body is not None:
            d.body(body)
        if d.note_order:
            d.widget(("hr",))
            d.text("\n")
            for i, key in enumerate(d.note_order, 1):
                d.text(f"[{i}] ", ("p", "dim", "bold"))
                d.text(d.notes[key] + "\n", ("p", "dim"))
        footer = d.header_footer_text(sect, "footer")
        if footer:
            d.widget(("hr",))
            d.text("\n")
            d.text(footer + "\n", ("dim", "align-center"))
        return d.doc
