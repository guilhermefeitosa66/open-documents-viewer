"""OpenDocument Text (.odt, and flat .fodt) → rich-text operations. Pure
Python (zipfile + ElementTree), for a thread; no dependency.

Same scope as DOCX: headings (text:h with its outline level) feed the
outline; bold, italic, underline, strike, colour, size, alignment from
automatic and named styles (with inheritance); numbered and bulleted
lists; tables (spanned cells keep their text in the first one); images
from the package; links; header and footer of the default master page,
dimmed; notes listed at the end; page breaks (fo:break-before/after) as
rules.
"""

import re
import zipfile
import xml.etree.ElementTree as ET

from odv.mdrender import Doc, Heading, Link, Table, slugify

NS = {
    "office": "urn:oasis:names:tc:opendocument:xmlns:office:1.0",
    "style": "urn:oasis:names:tc:opendocument:xmlns:style:1.0",
    "text": "urn:oasis:names:tc:opendocument:xmlns:text:1.0",
    "table": "urn:oasis:names:tc:opendocument:xmlns:table:1.0",
    "draw": "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0",
    "fo": "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0",
    "xlink": "http://www.w3.org/1999/xlink",
}
O, S, T, TB, D, FO, XL = (f"{{{NS[k]}}}" for k in ("office", "style", "text", "table", "draw", "fo", "xlink"))
MAX_PART = 64 * 1024 * 1024


class OdtError(Exception):
    pass


def _size_pt(value: str | None) -> float | None:
    m = re.match(r"([\d.]+)\s*(pt|cm|mm|in|px)?", value or "")
    if not m:
        return None
    n = float(m.group(1))
    return {"cm": n * 28.35, "mm": n * 2.835, "in": n * 72, "px": n * 0.75}.get(m.group(2), n)


class _Odt:
    def __init__(self, content, styles, read_file):
        self.read_file = read_file
        self.doc = Doc()
        self.styles = {}  # name -> {"family", "parent", props...}
        self.list_styles = {}  # name -> {level: "bullet"|"number", "char"/"format"}
        self.default_size = 12.0
        for root in (styles, content):
            if root is not None:
                self._load_styles(root)
        self.content = content
        self.styles_root = styles
        self.notes = []
        self.slugs = {}
        self.counters = {}

    # -- styles -----------------------------------------------------------------------
    def _load_styles(self, root):
        for st in root.iter(S + "default-style"):
            if st.get(S + "family") == "paragraph":
                tp = st.find(S + "text-properties")
                size = _size_pt(tp.get(FO + "font-size")) if tp is not None else None
                if size:
                    self.default_size = size
        for st in root.iter(S + "style"):
            props = {"family": st.get(S + "family"), "parent": st.get(S + "parent-style-name")}
            tp = st.find(S + "text-properties")
            if tp is not None:
                if tp.get(FO + "font-weight") is not None:
                    props["bold"] = tp.get(FO + "font-weight") in ("bold", "600", "700", "800", "900")
                if tp.get(FO + "font-style") is not None:
                    props["italic"] = tp.get(FO + "font-style") in ("italic", "oblique")
                if tp.get(S + "text-underline-style") is not None:
                    props["underline"] = tp.get(S + "text-underline-style") != "none"
                if tp.get(S + "text-line-through-style") is not None:
                    props["strike"] = tp.get(S + "text-line-through-style") != "none"
                if tp.get(FO + "color"):
                    props["color"] = tp.get(FO + "color").lstrip("#").upper()
                size = tp.get(FO + "font-size")
                if size:
                    props["size"] = size
                if tp.get(S + "text-position", "").startswith(("super", "sub")):
                    props["position"] = True
                if tp.get(TB + "display") == "none" or tp.get(T + "display") == "none":
                    props["hidden"] = True
            pp = st.find(S + "paragraph-properties")
            if pp is not None:
                if pp.get(FO + "text-align"):
                    props["align"] = pp.get(FO + "text-align")
                if pp.get(FO + "break-before") == "page":
                    props["break_before"] = True
                if pp.get(FO + "break-after") == "page":
                    props["break_after"] = True
            level = st.get(S + "default-outline-level")
            if level and level.isdigit():
                props["outline"] = int(level)
            self.styles[st.get(S + "name")] = props
        for ls in root.iter(T + "list-style"):
            levels = {}
            for lvl in ls:
                level = int(lvl.get(T + "level", "1"))
                if lvl.tag == T + "list-level-style-number":
                    levels[level] = ("number", lvl.get(S + "num-format") or "1", lvl.get(S + "num-suffix") or ".")
                else:
                    levels[level] = ("bullet", lvl.get(T + "bullet-char") or "•", "")
            self.list_styles[ls.get(S + "name")] = levels

    def prop(self, name, key):
        seen = 0
        while name and name in self.styles and seen < 12:
            if key in self.styles[name]:
                return self.styles[name][key]
            name = self.styles[name].get("parent")
            seen += 1
        return None

    def run_tags(self, style_names) -> list[str]:
        tags = []
        values = {}
        for name in style_names:
            for key in ("bold", "italic", "underline", "strike", "color", "size", "position", "hidden"):
                value = self.prop(name, key)
                if value is not None:
                    values[key] = value
        if values.get("hidden"):
            return ["__hidden__"]
        for key, tag in (("bold", "bold"), ("italic", "italic"), ("underline", "underline"), ("strike", "strike")):
            if values.get(key):
                tags.append(tag)
        color = values.get("color")
        if color and re.fullmatch(r"[0-9A-F]{6}", color) and color not in ("000000", "FFFFFF"):
            tags.append("fg-" + color)
        size = values.get("size")
        if size:
            pt = _size_pt(size) if not size.endswith("%") else self.default_size * float(size[:-1]) / 100
            if pt:
                ratio = pt / self.default_size
                if abs(ratio - 1) > 0.08:
                    tags.append(f"scale-{round(max(0.5, min(3.0, ratio)) * 100)}")
        if values.get("position"):
            tags.append("dim")
        return tags

    # -- output ---------------------------------------------------------------------------
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

    # -- body ---------------------------------------------------------------------------------
    def blocks(self, parent, list_ctx=None):
        for el in parent:
            tag = el.tag
            if tag in (T + "p", T + "h"):
                self.paragraph(el, list_ctx)
            elif tag == T + "list":
                self.list(el, list_ctx)
            elif tag == TB + "table":
                self.table(el)
            elif tag in (T + "section", TB + "table-of-content", T + "index-body", T + "table-index"):
                body = el.find(T + "index-body") if el.tag != T + "section" else el
                self.blocks(body if body is not None else el, list_ctx)
            elif tag == D + "frame":
                self.frame(el)

    def list(self, el, ctx):
        style = el.get(T + "style-name") or (ctx or {}).get("style")
        level = (ctx or {}).get("level", 0) + 1
        key = (style, level)
        if el.get(T + "continue-numbering") != "true" and (ctx is None or ctx.get("style") != style):
            self.counters[key] = 0
        for item in el:
            if item.tag not in (T + "list-item", T + "list-header"):
                continue
            kind = self.list_styles.get(style, {}).get(level, ("bullet", "•", ""))
            if item.tag == T + "list-item":
                if kind[0] == "number":
                    self.counters[key] = self.counters.get(key, 0) + 1
                    n = self.counters[key]
                    label = {"a": chr(96 + n) if n <= 26 else str(n), "A": chr(64 + n) if n <= 26 else str(n),
                             "i": None, "I": None}.get(kind[1], str(n)) or str(n)
                    marker = label + kind[2] + " "
                else:
                    marker = (kind[1] if kind[1].strip() else "•") + " "
            else:
                marker = ""
            for k in list(self.counters):
                if k[0] == style and k[1] > level:
                    del self.counters[k]
            sub = {"style": style, "level": level, "marker": marker}
            for child in item:
                if child.tag == T + "list":
                    self.list(child, {"style": style, "level": level})
                else:
                    self.blocks([child], sub)
                    sub = dict(sub, marker="")

    def paragraph(self, el, list_ctx=None):
        style = el.get(T + "style-name")
        if self.prop(style, "break_before"):
            self.widget(("hr",))
            self.text("\n")
        heading = el.tag == T + "h"
        level = int(el.get(T + "outline-level", "1")) if heading else self.prop(style, "outline")
        block = []
        if level:
            block.append(f"h{min(int(level), 6)}")
        else:
            block.append("p")
        align = self.prop(style, "align")
        if align in ("center", "end", "right", "justify"):
            block.append({"center": "align-center", "end": "align-right", "right": "align-right"}
                         .get(align, "align-fill"))
        if list_ctx:
            block = [t for t in block if t != "p"] + [f"ind{min(list_ctx['level'], 8)}", "li"]
            if list_ctx.get("marker"):
                self.text(list_ctx["marker"], tuple(block) + ("marker",))
        start = self.doc.length
        parts = []
        self.inline(el, block, [style] if style else [], parts, None)
        self.text("\n", block)
        if level:
            title = "".join(parts).strip()
            if title:
                self.doc.outline.append(Heading(int(level), title, start, 1))
                self.doc.headings.setdefault(slugify(title, self.slugs), start)
        if self.prop(style, "break_after"):
            self.widget(("hr",))
            self.text("\n")

    def inline(self, el, block, styles, parts, href):
        if el.text:
            self.emit(el.text, block, styles, parts, href)
        for child in el:
            tag = child.tag
            if tag == T + "span":
                name = child.get(T + "style-name")
                self.inline(child, block, styles + ([name] if name else []), parts, href)
            elif tag == T + "a":
                target = child.get(XL + "href", "")
                start = self.doc.length
                self.inline(child, block, styles, parts, target)
                if target:
                    self.doc.links.append(Link(start, self.doc.length, target))
            elif tag == T + "s":
                self.emit(" " * int(child.get(T + "c", "1")), block, styles, parts, href)
            elif tag == T + "tab":
                self.emit("\t", block, styles, parts, href)
            elif tag == T + "line-break":
                self.emit("\n", block, styles, parts, href)
            elif tag == T + "note":
                body = child.find(T + "note-body")
                note = " ".join("".join(p.itertext()).strip() for p in body.iter() if p.tag == T + "p") if body is not None else ""
                self.notes.append(note)
                self.text(f"[{len(self.notes)}]", tuple(block) + ("dim",))
            elif tag in (T + "bookmark", T + "bookmark-start"):
                name = child.get(T + "name")
                if name:
                    self.doc.headings.setdefault(name.lower(), self.doc.length)
            elif tag == D + "frame":
                self.frame(child)
            elif tag in (T + "tracked-changes", O + "annotation", T + "soft-page-break"):
                pass
            else:
                self.inline(child, block, styles, parts, href)
            if child.tail:
                self.emit(child.tail, block, styles, parts, href)

    def emit(self, s, block, styles, parts, href):
        s = re.sub(r"[ \t\n]+", " ", s) if s.strip() or s == " " else s.replace("\n", " ")
        tags = self.run_tags(styles)
        if tags == ["__hidden__"]:
            return
        if href:
            tags.append("link")
        self.text(s, tuple(block) + tuple(tags))
        parts.append(s)

    def frame(self, frame):
        image = frame.find(D + "image")
        if image is not None:
            data = self.read_file(image.get(XL + "href", ""))
            if data:
                self.widget(("image", data))
                self.text("\n")
                return
            binary = image.find(O + "binary-data")  # flat ODF embeds images
            if binary is not None and binary.text:
                import base64

                try:
                    self.widget(("image", base64.b64decode(binary.text)))
                    self.text("\n")
                    return
                except ValueError:
                    pass
            self.text("[image]\n", ("dim",))
            return
        box = frame.find(D + "text-box")
        if box is not None:
            self.blocks(box)

    def table(self, tbl):
        rows = []
        for row in tbl.iter(TB + "table-row"):
            cells = []
            for cell in row:
                if cell.tag not in (TB + "table-cell", TB + "covered-table-cell"):
                    continue
                repeat = min(int(cell.get(TB + "number-columns-repeated", "1")), 64)
                text = "" if cell.tag == TB + "covered-table-cell" else " ".join(
                    "".join(p.itertext()).strip() for p in cell if p.tag in (T + "p", T + "h")).strip()
                cells.extend([text] * repeat)
            while cells and cells[-1] == "":
                cells.pop()
            rows.append(cells)
        while rows and not rows[-1]:
            rows.pop()
        if not rows:
            return
        width = max(len(r) for r in rows)
        rows = [r + [""] * (width - len(r)) for r in rows]
        self.widget(("table", Table(rows[0], rows[1:], ["left"] * width)))
        self.text("\n")

    def master_text(self, kind) -> str:
        if self.styles_root is None:
            return ""
        for master in self.styles_root.iter(S + "master-page"):
            part = master.find(S + kind)
            if part is not None:
                texts = ["".join(p.itertext()).strip() for p in part.iter() if p.tag in (T + "p", T + "h")]
                return "\n".join(t for t in texts if t)
        return ""


def render(content, styles, read_file) -> Doc:
    """``content``/``styles``: parsed roots (a flat document passes itself twice)."""
    odt = _Odt(content, styles, read_file)
    header = odt.master_text("header")
    if header:
        odt.text(header + "\n", ("dim", "align-center"))
        odt.widget(("hr",))
        odt.text("\n")
    body = content.find(f"{O}body/{O}text")
    if body is not None:
        odt.blocks(body)
    if odt.notes:
        odt.widget(("hr",))
        odt.text("\n")
        for i, note in enumerate(odt.notes, 1):
            odt.text(f"[{i}] ", ("p", "dim", "bold"))
            odt.text(note + "\n", ("p", "dim"))
    footer = odt.master_text("footer")
    if footer:
        odt.widget(("hr",))
        odt.text("\n")
        odt.text(footer + "\n", ("dim", "align-center"))
    return odt.doc


def build(text, path: str = "") -> Doc:
    if path.lower().endswith((".fodt",)):
        root = ET.parse(path).getroot()
        return render(root, root, lambda href: None)
    try:
        zf = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        raise OdtError(str(e)) from e
    with zf:
        from odv.formats import protect
        from odv.i18n import _

        if protect.odf_encrypted(zf):
            raise OdtError(_(protect.ODF_ENCRYPTED))
        names = set(zf.namelist())

        def read(name):
            if name not in names or zf.getinfo(name).file_size > MAX_PART:
                return None
            return zf.read(name)

        def parse(name):
            data = read(name)
            try:
                return ET.fromstring(data) if data else None
            except ET.ParseError:
                return None

        content = parse("content.xml")
        if content is None:
            raise OdtError("content.xml is missing or damaged")
        return render(content, parse("styles.xml"), lambda href: read(href.lstrip("./")) if href else None)
