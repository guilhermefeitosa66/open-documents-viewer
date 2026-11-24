"""OpenDocument presentations (.odp, and flat .fodp) into the presentation
model (odv.formats.slides). Pure Python (zipfile + ElementTree), for a
thread; no dependency.

Each draw:page becomes a slide: frames (text boxes, pictures, tables),
custom shapes, rectangles, ellipses, lines and groups, with fill, stroke
and text properties from graphic/presentation/paragraph/text styles
(with inheritance). Placeholders without a position take it from their
master page; the master's own drawings (not its placeholders) are drawn
behind. Backgrounds come from the page's drawing-page style, then the
master's. Speaker notes come from presentation:notes."""

import base64
import math
import re
import zipfile
import xml.etree.ElementTree as ET

from odv.formats.odt import NS, _size_pt
from odv.formats.slides import EMU_PER_PT, Paragraph, Presentation, Run, Shape, Slide

O, S, T, TB, D, FO, XL = (f"{{{NS[k]}}}" for k in ("office", "style", "text", "table", "draw", "fo", "xlink"))
PR = "{urn:oasis:names:tc:opendocument:xmlns:presentation:1.0}"
SVG = "{urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0}"
MAX_PART = 64 * 1024 * 1024
SKIPPED_CLASSES = {"header", "footer", "date-time", "page-number", "handout"}
SHAPE_TYPES = {"rectangle": "rect", "round-rectangle": "roundRect", "ellipse": "ellipse", "circle": "ellipse",
               "flowchart-process": "rect", "flowchart-alternate-process": "roundRect",
               "flowchart-connector": "ellipse"}


class OdpError(Exception):
    pass


def _emu(value) -> int:
    pt = _size_pt(value)
    return int(pt * EMU_PER_PT) if pt is not None else 0


def _color(value):
    if value and re.fullmatch(r"#[0-9a-fA-F]{6}", value):
        return value[1:].upper()
    return None


class _Odp:
    def __init__(self, content, styles, read_file):
        self.read_file = read_file
        self.styles = {}  # name -> props (with "parent")
        self.list_styles = {}  # name -> {level: ("bullet", char) | ("number", format, suffix)}
        self.fonts = {}  # font-face name -> family
        self.fill_images = {}  # draw:fill-image name -> href or bytes
        self.layouts = {}  # page-layout name -> (width, height) in EMU
        self.masters = {}  # master-page name -> element
        self.defaults = {}
        for root in (styles, content):
            if root is not None:
                self._load(root)

    # -- styles -----------------------------------------------------------------------------
    def _load(self, root):
        for face in root.iter(S + "font-face"):
            family = (face.get(SVG + "font-family") or "").strip("'\"")
            if family:
                self.fonts[face.get(S + "name")] = family
        for img in root.iter(D + "fill-image"):
            href = img.get(XL + "href")
            if href:
                self.fill_images[img.get(D + "name")] = href
            else:
                binary = img.find(O + "binary-data")
                if binary is not None and binary.text:
                    try:
                        self.fill_images[img.get(D + "name")] = base64.b64decode(binary.text)
                    except ValueError:
                        pass
        for pl in root.iter(S + "page-layout"):
            props = pl.find(S + "page-layout-properties")
            if props is not None and props.get(FO + "page-width"):
                self.layouts[pl.get(S + "name")] = (_emu(props.get(FO + "page-width")),
                                                    _emu(props.get(FO + "page-height")))
        for master in root.iter(S + "master-page"):
            self.masters[master.get(S + "name")] = master
        for st in root.iter(S + "default-style"):
            if st.get(S + "family") == "graphic":
                self.defaults = self._props(st)
        for st in root.iter(S + "style"):
            props = self._props(st)
            props["parent"] = st.get(S + "parent-style-name")
            self.styles[st.get(S + "name")] = props
        for ls in root.iter(T + "list-style"):
            levels = {}
            for lvl in ls:
                level = int(lvl.get(T + "level", "1"))
                if lvl.tag == T + "list-level-style-number":
                    levels[level] = ("number", lvl.get(S + "num-format") or "1", lvl.get(S + "num-suffix") or ".")
                elif lvl.tag == T + "list-level-style-bullet":
                    levels[level] = ("bullet", lvl.get(T + "bullet-char") or "•")
            self.list_styles[ls.get(S + "name")] = levels

    def _props(self, st):
        props = {}
        gp = st.find(S + "graphic-properties")
        if gp is not None:
            if gp.get(D + "fill"):
                props["fill"] = gp.get(D + "fill")
            if gp.get(D + "fill-color"):
                props["fill_color"] = _color(gp.get(D + "fill-color"))
            if gp.get(D + "stroke"):
                props["stroke"] = gp.get(D + "stroke")
            if gp.get(SVG + "stroke-color"):
                props["stroke_color"] = _color(gp.get(SVG + "stroke-color"))
            if gp.get(SVG + "stroke-width"):
                props["stroke_width"] = _size_pt(gp.get(SVG + "stroke-width"))
            if gp.get(D + "textarea-vertical-align"):
                props["valign"] = gp.get(D + "textarea-vertical-align")
            for key, attr in (("pad_l", FO + "padding-left"), ("pad_t", FO + "padding-top"),
                              ("pad_r", FO + "padding-right"), ("pad_b", FO + "padding-bottom")):
                if gp.get(attr):
                    props[key] = _emu(gp.get(attr))
            inner = gp.find(T + "list-style")
            if inner is not None:
                levels = {}
                for lvl in inner:
                    level = int(lvl.get(T + "level", "1"))
                    if lvl.tag == T + "list-level-style-number":
                        levels[level] = ("number", lvl.get(S + "num-format") or "1",
                                         lvl.get(S + "num-suffix") or ".")
                    elif lvl.tag == T + "list-level-style-bullet":
                        levels[level] = ("bullet", lvl.get(T + "bullet-char") or "•")
                props["list"] = levels
        dp = st.find(S + "drawing-page-properties")
        if dp is not None:
            if dp.get(D + "fill"):
                props["fill"] = dp.get(D + "fill")
            if dp.get(D + "fill-color"):
                props["fill_color"] = _color(dp.get(D + "fill-color"))
            if dp.get(D + "fill-image-name"):
                props["fill_image"] = dp.get(D + "fill-image-name")
            if dp.get(PR + "visibility"):
                props["visibility"] = dp.get(PR + "visibility")
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
            if _color(tp.get(FO + "color")):
                props["color"] = _color(tp.get(FO + "color"))
            if tp.get(FO + "font-size") and not tp.get(FO + "font-size").endswith("%"):
                props["size"] = _size_pt(tp.get(FO + "font-size"))
            family = self.fonts.get(tp.get(S + "font-name")) or (tp.get(FO + "font-family") or "").strip("'\"")
            if family:
                props["font"] = family
        pp = st.find(S + "paragraph-properties")
        if pp is not None and pp.get(FO + "text-align"):
            props["align"] = {"center": "center", "end": "right", "right": "right",
                              "justify": "justify"}.get(pp.get(FO + "text-align"), "left")
        return props

    def prop(self, names, key, default=None):
        """The first value of ``key`` along the styles in ``names`` (most
        specific first) and their parents."""
        for name in names:
            seen = 0
            while name and name in self.styles and seen < 16:
                if key in self.styles[name]:
                    return self.styles[name][key]
                name = self.styles[name].get("parent")
                seen += 1
        return self.defaults.get(key, default)

    # -- pages --------------------------------------------------------------------------------
    def build(self, body) -> Presentation:
        out = Presentation()
        pres = body.find(f"{O}body/{O}presentation") if body is not None else None
        if pres is None:
            raise OdpError("this file has no presentation body")
        first_master = None
        for page in pres.findall(D + "page"):
            master = self.masters.get(page.get(D + "master-page-name"))
            if first_master is None and master is not None:
                first_master = master
                size = self.layouts.get(master.get(S + "page-layout-name"))
                if size and size[0] and size[1]:
                    out.width, out.height = size
            out.slides.append(self.page(page, master))
        return out

    def page(self, page, master) -> Slide:
        slide = Slide()
        page_styles = [page.get(D + "style-name"), master.get(D + "style-name") if master is not None else None]
        slide.hidden = self.prop(page_styles[:1], "visibility") == "hidden"
        fill = self.prop(page_styles, "fill")
        if fill == "solid":
            slide.background = self.prop(page_styles, "fill_color") or "FFFFFF"
        elif fill == "bitmap":
            ref = self.fill_images.get(self.prop(page_styles, "fill_image"))
            slide.background_image = ref if isinstance(ref, bytes) else (self.read_file(ref) if ref else None)
        if master is not None:
            for el in master:
                if el.get(PR + "class") or el.get(PR + "placeholder") == "true":
                    continue
                slide.shapes.extend(self.shapes(el, master))
        for el in page:
            if el.tag == PR + "notes":
                slide.notes = "\n".join(
                    "\n".join(" ".join("".join(p.itertext()).split()) for p in frame.iter(T + "p"))
                    for frame in el.findall(D + "frame") if frame.get(PR + "class") == "notes").strip()
                continue
            if el.get(PR + "class") in SKIPPED_CLASSES:
                continue
            if el.get(PR + "class") == "title" and not slide.title:
                slide.title = " ".join("".join(el.itertext()).split())
            slide.shapes.extend(self.shapes(el, master))
        return slide

    def _master_frame(self, master, cls):
        if master is None or not cls:
            return None
        cls = {"subtitle": "subtitle", "outline": "outline"}.get(cls, cls)
        for el in master:
            if el.get(PR + "class") == cls:
                return el
        if cls == "subtitle":
            return self._master_frame(master, "outline")
        return None

    def _geometry(self, el, master):
        x, y = el.get(SVG + "x"), el.get(SVG + "y")
        w, h = _emu(el.get(SVG + "width")), _emu(el.get(SVG + "height"))
        rotation = 0.0
        transform = el.get(D + "transform")
        if transform:
            rot = re.search(r"rotate\s*\(\s*([-\d.eE]+)\s*\)", transform)
            tr = re.search(r"translate\s*\(\s*([-\d.]+\w*)\s*[ ,]\s*([-\d.]+\w*)\s*\)", transform)
            a = float(rot.group(1)) if rot else 0.0
            tx, ty = (_emu(tr.group(1)), _emu(tr.group(2))) if tr else (0, 0)
            # The shape is rotated about its top-left corner, then moved: find its centre.
            cx = w / 2 * math.cos(a) + h / 2 * math.sin(a) + tx
            cy = -w / 2 * math.sin(a) + h / 2 * math.cos(a) + ty
            return int(cx - w / 2), int(cy - h / 2), w, h, -math.degrees(a)
        if x is None and (not w or not h):
            frame = self._master_frame(master, el.get(PR + "class"))
            if frame is not None:
                return self._geometry(frame, None)
            return None
        return _emu(x), _emu(y), w, h, rotation

    def shapes(self, el, master) -> list:
        tag = el.tag
        if tag == D + "g":
            out = []
            for child in el:
                out.extend(self.shapes(child, master))
            return out
        if tag == D + "line" or tag == D + "connector":
            x1, y1 = _emu(el.get(SVG + "x1")), _emu(el.get(SVG + "y1"))
            x2, y2 = _emu(el.get(SVG + "x2")), _emu(el.get(SVG + "y2"))
            names = [el.get(D + "style-name")]
            color = self.prop(names, "stroke_color") or "404040"
            width = self.prop(names, "stroke_width") or 1.0
            return [Shape("line", x1, y1, x2 - x1, y2 - y1, line=color, line_width=width)]
        if tag not in (D + "frame", D + "custom-shape", D + "rect", D + "ellipse", D + "circle"):
            return []
        geom = self._geometry(el, master)
        if geom is None:
            return []
        x, y, w, h, rot = geom
        names = [el.get(PR + "style-name"), el.get(D + "style-name")]
        cls = el.get(PR + "class")
        if master is not None and cls:
            frame = self._master_frame(master, cls)
            if frame is not None:
                names += [frame.get(PR + "style-name"), frame.get(D + "style-name")]
        if tag == D + "frame":
            image = el.find(D + "image")
            if image is not None and el.find(D + "text-box") is None:
                data = self.read_file(image.get(XL + "href", "")) if image.get(XL + "href") else None
                binary = image.find(O + "binary-data")
                if data is None and binary is not None and binary.text:
                    try:
                        data = base64.b64decode(binary.text)
                    except ValueError:
                        data = None
                return [Shape("image", x, y, w, h, image=data, rotation=rot)]
            table = el.find(TB + "table")
            if table is not None:
                shape = Shape("table", x, y, w, h)
                for row in table.iter(TB + "table-row"):
                    shape.rows.append([self.paragraphs(cell, [], cls) for cell in row
                                       if cell.tag in (TB + "table-cell", TB + "covered-table-cell")])
                return [shape]
            if el.get(PR + "placeholder") == "true" and not "".join(el.itertext()).strip():
                return []
            body = el.find(D + "text-box")
            kind = "text"
        else:
            body = el
            kind = {D + "rect": "rect", D + "ellipse": "ellipse", D + "circle": "ellipse"}.get(tag)
            if kind is None:
                geo = el.find(D + "enhanced-geometry")
                kind = SHAPE_TYPES.get(geo.get(D + "type") if geo is not None else None, "rect")
                if geo is not None and kind == "rect" and "ellipse" in (geo.get(D + "type") or ""):
                    kind = "ellipse"
        fill_kind = self.prop(names, "fill", "none" if tag == D + "frame" else "solid")
        fill = self.prop(names, "fill_color", "729FCF") if fill_kind in ("solid", "gradient") else None
        line = None
        if self.prop(names, "stroke", "none" if tag == D + "frame" else "solid") != "none":
            line = self.prop(names, "stroke_color") or "3465A4"
        shape = Shape("rect" if kind == "text" else kind, x, y, w, h, fill=fill, line=line,
                      line_width=self.prop(names, "stroke_width") or 1.0, rotation=rot)
        shape.anchor = {"middle": "middle", "bottom": "bottom"}.get(
            self.prop(names, "valign", "middle" if kind != "text" else "top"), "top")
        shape.insets = tuple(self.prop(names, k) or d for k, d in
                             (("pad_l", 91440), ("pad_t", 45720), ("pad_r", 91440), ("pad_b", 45720)))
        if body is not None:
            shape.paragraphs = self.paragraphs(body, names, cls, kind != "text")
        return [shape]

    # -- text -----------------------------------------------------------------------------------
    def paragraphs(self, body, shape_styles, cls, centred=False):
        out = []
        self._walk(body, shape_styles, cls, centred, out, None, 0, {})
        return out

    def _walk(self, parent, shape_styles, cls, centred, out, list_style, level, counters):
        for el in parent:
            if el.tag in (T + "p", T + "h"):
                self.paragraph(el, shape_styles, cls, centred, out, None, counters, level=level)
            elif el.tag == T + "list":
                style = el.get(T + "style-name") or list_style
                for item in el:
                    if item.tag not in (T + "list-item", T + "list-header"):
                        continue
                    first = True
                    for child in item:
                        if child.tag == T + "list":
                            self._walk([child], shape_styles, cls, centred, out, style, level + 1, counters)
                        elif child.tag in (T + "p", T + "h"):
                            marker = (style, level + 1) if first and item.tag == T + "list-item" else None
                            self.paragraph(child, shape_styles, cls, centred, out, marker, counters,
                                           level=level)
                            first = False

    def paragraph(self, el, shape_styles, cls, centred, out, marker, counters, level=0):
        pstyle = el.get(T + "style-name")
        styles = ([pstyle] if pstyle else []) + list(shape_styles)
        align = self.prop(styles, "align", "center" if centred else "left")
        para = Paragraph(align=align, level=level)
        if marker is not None:
            style, depth = marker
            levels = self.list_styles.get(style) or self.prop(shape_styles, "list") or {}
            kind = levels.get(depth, ("bullet", "•"))
            if kind[0] == "number":
                key = (style, depth)
                counters[key] = counters.get(key, 0) + 1
                for k in list(counters):
                    if k[0] == style and k[1] > depth:
                        del counters[k]
                para.bullet = f"{counters[key]}{kind[2]}"
            else:
                para.bullet = kind[1] if kind[1].strip() else "•"
        base = self.prop(styles, "size") or (44 if cls == "title" else 32 if cls == "subtitle" else
                                              max(16, 32 - 4 * level) if cls == "outline" else 18)
        self._runs(el, styles, base, para.runs)
        if not para.runs or not "".join(r.text for r in para.runs):
            para.runs = [Run("", size=base)]
            para.bullet = None
        out.append(para)

    def _runs(self, el, styles, base, runs):
        def add(text, names):
            if not text:
                return
            text = re.sub(r"[ \t\n]+", " ", text)
            run = Run(text, size=self.prop(names, "size") or base)
            run.bold = bool(self.prop(names, "bold"))
            run.italic = bool(self.prop(names, "italic"))
            run.underline = bool(self.prop(names, "underline"))
            run.strike = bool(self.prop(names, "strike"))
            run.color = self.prop(names, "color")
            run.font = self.prop(names, "font")
            runs.append(run)

        def walk(node, names):
            add(node.text, names)
            for child in node:
                if child.tag == T + "span":
                    name = child.get(T + "style-name")
                    walk(child, ([name] if name else []) + names)
                elif child.tag == T + "s":
                    add(" " * int(child.get(T + "c", "1")), names)
                elif child.tag == T + "tab":
                    add("\t", names)
                elif child.tag == T + "line-break":
                    runs.append(Run("\n", size=self.prop(names, "size") or base))
                elif child.tag == T + "a":
                    walk(child, names)
                add(child.tail, names)

        walk(el, styles)


def build(path: str) -> Presentation:
    if path.lower().endswith(".fodp"):
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as e:
            raise OdpError(str(e)) from e
        return _Odp(root, root, lambda href: None).build(root)
    try:
        zf = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        raise OdpError(str(e)) from e
    with zf:
        names = set(zf.namelist())

        def read(name):
            name = (name or "").lstrip("./")
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
            raise OdpError("content.xml is missing or damaged")
        return _Odp(content, parse("styles.xml"), read).build(content)
