"""PowerPoint (.pptx) into the presentation model. Pure Python (zipfile +
ElementTree), for a thread; no dependency.

Placeholders without their own position take it from the slide layout,
then the master; theme colours go through the master's colour map; text
sizes default to the master's text styles. Groups are flattened with their
coordinate transform. Macros are never read."""

import posixpath
import re
import zipfile
import xml.etree.ElementTree as ET

from odv.formats import protect
from odv.formats.slides import Paragraph, Presentation, Run, Shape, Slide

P = "{http://schemas.openxmlformats.org/presentationml/2006/main}"
A = "{http://schemas.openxmlformats.org/drawingml/2006/main}"
R = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"
MAX_PART = 64 * 1024 * 1024
PRESETS = {"rect": "rect", "roundRect": "roundRect", "ellipse": "ellipse", "line": "line",
           "straightConnector1": "line", "snip1Rect": "rect", "flowChartProcess": "rect",
           "flowChartAlternateProcess": "roundRect", "flowChartConnector": "ellipse"}


class PptxError(Exception):
    pass


def _lum_mod(hex_color, mod=None, off=None, tint=None, shade=None):
    r, g, b = (int(hex_color[i:i + 2], 16) / 255 for i in (0, 2, 4))
    import colorsys

    h, l, s = colorsys.rgb_to_hls(r, g, b)
    if mod is not None:
        l *= mod
    if off is not None:
        l += off
    l = max(0.0, min(1.0, l))
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    if tint is not None:
        r, g, b = (c + (1 - c) * (1 - tint) for c in (r, g, b))
    if shade is not None:
        r, g, b = (c * shade for c in (r, g, b))
    return "%02X%02X%02X" % tuple(round(max(0, min(1, c)) * 255) for c in (r, g, b))


class _Pptx:
    def __init__(self, zf):
        self.zf = zf
        self.names = set(zf.namelist())
        self.theme = {}
        self.fonts = {}  # "major"/"minor" -> the theme's latin typeface
        self.clr_map = {"bg1": "lt1", "tx1": "dk1", "bg2": "lt2", "tx2": "dk2"}
        self._cache = {}

    def read(self, name):
        if name not in self.names or self.zf.getinfo(name).file_size > MAX_PART:
            return None
        return self.zf.read(name)

    def xml(self, name):
        if name in self._cache:
            return self._cache[name]
        data = self.read(name)
        try:
            root = ET.fromstring(data) if data else None
        except ET.ParseError:
            root = None
        self._cache[name] = root
        return root

    def rels(self, part):
        name = posixpath.join(posixpath.dirname(part), "_rels", posixpath.basename(part) + ".rels")
        root = self.xml(name)
        out = {}
        if root is not None:
            for rel in root.iter(REL + "Relationship"):
                target = rel.get("Target", "")
                if rel.get("TargetMode") != "External":
                    target = posixpath.normpath(posixpath.join(posixpath.dirname(part), target))
                out[rel.get("Id")] = (target, rel.get("Type", "").rsplit("/", 1)[-1])
        return out

    # -- colours ------------------------------------------------------------------------
    def load_theme(self, master):
        for target, kind in self.rels(master).values():
            if kind == "theme":
                root = self.xml(target)
                if root is None:
                    continue
                for which in ("major", "minor"):
                    latin = root.find(f".//{A}{which}Font/{A}latin")
                    if latin is not None and latin.get("typeface"):
                        self.fonts[which] = latin.get("typeface")
                scheme = next(root.iter(A + "clrScheme"), None)
                if scheme is not None:
                    for child in scheme:
                        name = child.tag.split("}")[1]
                        srgb = child.find(A + "srgbClr")
                        sys_clr = child.find(A + "sysClr")
                        if srgb is not None:
                            self.theme[name] = srgb.get("val").upper()
                        elif sys_clr is not None:
                            self.theme[name] = (sys_clr.get("lastClr") or "000000").upper()
        m = self.xml(master)
        cm = m.find(P + "clrMap") if m is not None else None
        if cm is not None:
            self.clr_map.update({k: v for k, v in cm.attrib.items()})

    def color(self, parent) -> str | None:
        """A colour from a fill-like element (solidFill, fgClr…)."""
        if parent is None:
            return None
        for el in parent:
            tag = el.tag.split("}")[1]
            base = None
            if tag == "srgbClr":
                base = el.get("val", "000000").upper()
            elif tag == "schemeClr":
                name = el.get("val")
                name = self.clr_map.get(name, name)
                base = self.theme.get(name)
            elif tag == "sysClr":
                base = (el.get("lastClr") or "000000").upper()
            elif tag == "prstClr":
                base = {"black": "000000", "white": "FFFFFF", "red": "FF0000", "blue": "0000FF",
                        "green": "008000", "yellow": "FFFF00", "gray": "808080"}.get(el.get("val"), "000000")
            if base:
                mods = {c.tag.split("}")[1]: int(c.get("val", "100000")) / 100000 for c in el}
                if mods:
                    try:
                        base = _lum_mod(base, mods.get("lumMod"), mods.get("lumOff"), mods.get("tint"),
                                        mods.get("shade"))
                    except ValueError:
                        pass
                return base
        return None

    def fill(self, sppr):
        if sppr is None:
            return None, False
        if sppr.find(A + "noFill") is not None:
            return None, True
        solid = sppr.find(A + "solidFill")
        if solid is not None:
            return self.color(solid), True
        grad = sppr.find(A + "gradFill")
        if grad is not None:
            stop = next(grad.iter(A + "gs"), None)
            return self.color(stop), True
        return None, False

    # -- placeholders ---------------------------------------------------------------------
    @staticmethod
    def ph_of(sp):
        nv = next((c for c in sp if c.tag.startswith(P + "nv")), None)
        ph = nv.find(f"{P}nvPr/{P}ph") if nv is not None else None
        if ph is None:
            return None
        return ph.get("type", "body"), ph.get("idx")

    def find_ph(self, part, ph):
        root = self.xml(part)
        if root is None:
            return None
        kind, idx = ph
        exact = alias = None
        for sp in root.iter(P + "sp"):
            other = self.ph_of(sp)
            if other is None:
                continue
            if idx is not None and other[1] == idx:
                return sp
            if other[0] == kind:
                exact = sp if exact is None else exact
            elif kind in ("ctrTitle", "title") and other[0] in ("ctrTitle", "title"):
                alias = sp if alias is None else alias
        return exact if exact is not None else alias


def _xfrm(el):
    x = el.find(A + "xfrm") if el is not None else None
    if x is None:
        return None
    off, ext = x.find(A + "off"), x.find(A + "ext")
    if off is None or ext is None:
        return None
    return (int(off.get("x", 0)), int(off.get("y", 0)), int(ext.get("cx", 0)), int(ext.get("cy", 0)),
            int(x.get("rot", 0)) / 60000)


class _SlideReader:
    def __init__(self, pkg: _Pptx, part: str, layout: str | None, master: str | None):
        self.pkg, self.part, self.layout, self.master = pkg, part, layout, master
        self.rels = pkg.rels(part)

    def default_size(self, ph, level):
        kind = ph[0] if ph else "other"
        master = self.pkg.xml(self.master) if self.master else None
        style_name = "titleStyle" if kind in ("title", "ctrTitle") else ("bodyStyle" if ph else "otherStyle")
        if master is not None:
            style = master.find(f"{P}txStyles/{P}{style_name}")
            if style is not None:
                lvl = style.find(f"{A}lvl{level + 1}pPr/{A}defRPr")
                if lvl is not None and lvl.get("sz"):
                    return int(lvl.get("sz")) / 100
        return {"title": 44, "ctrTitle": 44, "subTitle": 32}.get(kind, 28 if ph else 18) - (4 * level if ph else 0)

    def shapes(self, tree, transform=None):
        out = []
        for el in tree:
            tag = el.tag
            if tag == P + "grpSp":
                gsp = el.find(P + "grpSpPr")
                x = gsp.find(A + "xfrm") if gsp is not None else None
                if x is not None and x.find(A + "chOff") is not None:
                    off, ext = x.find(A + "off"), x.find(A + "ext")
                    choff, chext = x.find(A + "chOff"), x.find(A + "chExt")
                    sx = int(ext.get("cx", 1)) / max(1, int(chext.get("cx", 1)))
                    sy = int(ext.get("cy", 1)) / max(1, int(chext.get("cy", 1)))
                    inner = (int(off.get("x", 0)) - int(choff.get("x", 0)) * sx,
                             int(off.get("y", 0)) - int(choff.get("y", 0)) * sy, sx, sy)
                    out.extend(self.shapes(el, self._compose(transform, inner)))
                else:
                    out.extend(self.shapes(el, transform))
            elif tag in (P + "sp", P + "cxnSp", P + "pic", P + "graphicFrame"):
                shape = self.shape(el)
                if shape is not None:
                    if transform:
                        ox, oy, sx, sy = transform
                        shape.x, shape.y = int(ox + shape.x * sx), int(oy + shape.y * sy)
                        shape.w, shape.h = int(shape.w * sx), int(shape.h * sy)
                    out.append(shape)
        return out

    @staticmethod
    def _compose(outer, inner):
        if outer is None:
            return inner
        ox, oy, sx, sy = outer
        ix, iy, isx, isy = inner
        return (ox + ix * sx, oy + iy * sy, sx * isx, sy * isy)

    def shape(self, el):
        pkg = self.pkg
        ph = pkg.ph_of(el)
        sppr = el.find(P + "spPr")
        geom = _xfrm(sppr) if sppr is not None else None
        if el.tag == P + "graphicFrame":
            x = el.find(P + "xfrm")
            if x is not None:
                off, ext = x.find(A + "off"), x.find(A + "ext")
                geom = (int(off.get("x", 0)), int(off.get("y", 0)), int(ext.get("cx", 0)), int(ext.get("cy", 0)), 0)
        inherited = []
        if ph is not None:
            found = [pkg.find_ph(part, ph) for part in (self.layout, self.master) if part]
            # a layout's centred title only stands in for a plain title the master lacks
            found = [sp for sp in found if sp is not None]
            exact = [sp for sp in found if pkg.ph_of(sp)[0] == ph[0]
                     or (ph[1] is not None and pkg.ph_of(sp)[1] == ph[1])]
            found = exact or found
            for sp in found:
                inherited.append(sp)
                if geom is None:
                    geom = _xfrm(sp.find(P + "spPr"))
        if geom is None:
            return None
        x, y, w, h, rot = geom
        if el.tag == P + "pic":
            blip = next(el.iter(A + "blip"), None)
            rid = blip.get(R + "embed") if blip is not None else None
            target = self.rels.get(rid, (None, None))[0] if rid else None
            data = pkg.read(target) if target else None
            return Shape("image", x, y, w, h, image=data, rotation=rot)
        if el.tag == P + "graphicFrame":
            tbl = next(el.iter(A + "tbl"), None)
            if tbl is None:
                return None
            shape = Shape("table", x, y, w, h)
            shape.col_widths = [int(g.get("w", 0)) for g in tbl.iter(A + "gridCol")]
            for tr in tbl.iter(A + "tr"):
                shape.rows.append([self.paragraphs(tc.find(A + "txBody"), None, []) for tc in tr.iter(A + "tc")])
            return shape
        prst = sppr.find(A + "prstGeom") if sppr is not None else None
        kind = PRESETS.get(prst.get("prst"), "rect") if prst is not None else "rect"
        if el.tag == P + "cxnSp":
            kind = "line"
        fill, has_fill = pkg.fill(sppr)
        if not has_fill:
            style = el.find(P + "style")
            fill_ref = style.find(A + "fillRef") if style is not None else None
            if fill_ref is not None and fill_ref.get("idx") not in (None, "0"):
                fill = pkg.color(fill_ref)
        ln = sppr.find(A + "ln") if sppr is not None else None
        line = None
        width = 1.0
        if ln is not None and ln.find(A + "noFill") is None:
            line = pkg.color(ln.find(A + "solidFill"))
            width = int(ln.get("w", 12700)) / 12700
        elif ln is None or ln.find(A + "noFill") is None:
            style = el.find(P + "style")
            ln_ref = style.find(A + "lnRef") if style is not None else None
            if ln_ref is not None and ln_ref.get("idx") not in (None, "0"):
                line = pkg.color(ln_ref)
        shape = Shape(kind, x, y, w, h, fill=fill, line=line, line_width=width, rotation=rot)
        body = el.find(P + "txBody")
        if body is not None:
            shape.paragraphs = self.paragraphs(body, ph, inherited)
            bpr = body.find(A + "bodyPr")
            anchor = bpr.get("anchor") if bpr is not None else None
            if anchor is None:
                for sp in inherited:
                    ib = sp.find(f"{P}txBody/{A}bodyPr")
                    if ib is not None and ib.get("anchor"):
                        anchor = ib.get("anchor")
                        break
            shape.anchor = {"ctr": "middle", "b": "bottom"}.get(anchor, "top")
            if bpr is not None:
                shape.insets = tuple(int(bpr.get(k, d)) for k, d in
                                     (("lIns", 91440), ("tIns", 45720), ("rIns", 91440), ("bIns", 45720)))
        return shape

    def paragraphs(self, body, ph, inherited):
        out = []
        if body is None:
            return out
        counters = {}
        for p in body.findall(A + "p"):
            ppr = p.find(A + "pPr")
            level = int(ppr.get("lvl", 0)) if ppr is not None else 0
            align = ppr.get("algn") if ppr is not None else None
            if align is None:
                for sp in inherited:
                    lp = sp.find(f"{P}txBody/{A}lstStyle/{A}lvl{level + 1}pPr")
                    if lp is not None and lp.get("algn"):
                        align = lp.get("algn")
                        break
            para = Paragraph(align={"ctr": "center", "r": "right", "just": "justify"}.get(align, "left"), level=level)
            size = self.default_size(ph, level)
            font = self.pkg.fonts.get("major" if ph and ph[0] in ("title", "ctrTitle") else "minor")
            for sp in inherited:
                d = sp.find(f"{P}txBody/{A}lstStyle/{A}lvl{level + 1}pPr/{A}defRPr")
                if d is not None and d.get("sz"):
                    size = int(d.get("sz")) / 100
                    break
            if ppr is not None:
                if ppr.find(A + "buChar") is not None:
                    para.bullet = ppr.find(A + "buChar").get("char", "•")
                elif ppr.find(A + "buAutoNum") is not None:
                    counters[level] = counters.get(level, 0) + 1
                    para.bullet = f"{counters[level]}."
            if para.bullet is None and ph is not None and ph[0] == "body" and (ppr is None or ppr.find(A + "buNone") is None):
                para.bullet = "•"
            for r in p:
                if r.tag not in (A + "r", A + "fld", A + "br"):
                    continue
                if r.tag == A + "br":
                    para.runs.append(Run("\n", size=size))
                    continue
                rpr = r.find(A + "rPr")
                t = r.find(A + "t")
                run = Run(t.text if t is not None and t.text else "", size=size, font=font)
                if rpr is not None:
                    run.bold = rpr.get("b") in ("1", "true")
                    run.italic = rpr.get("i") in ("1", "true")
                    run.underline = rpr.get("u") not in (None, "none")
                    run.strike = rpr.get("strike") not in (None, "noStrike")
                    if rpr.get("sz"):
                        run.size = int(rpr.get("sz")) / 100
                    run.color = self.pkg.color(rpr.find(A + "solidFill"))
                    latin = rpr.find(A + "latin")
                    face = latin.get("typeface") if latin is not None else None
                    if face and face.startswith("+"):
                        face = self.pkg.fonts.get("major" if face.startswith("+mj") else "minor")
                    if face:
                        run.font = face
                para.runs.append(run)
            if not para.runs:
                para.runs.append(Run("", size=size))
                para.bullet = None
            out.append(para)
        return out

    def background(self, slide):
        for part in (self.part, self.layout, self.master):
            root = self.pkg.xml(part) if part else None
            bg = root.find(f"{P}cSld/{P}bg") if root is not None else None
            if bg is None:
                continue
            bgpr = bg.find(P + "bgPr")
            if bgpr is not None:
                blip = next(bgpr.iter(A + "blip"), None)
                if blip is not None:
                    rels = self.pkg.rels(part)
                    target = rels.get(blip.get(R + "embed"), (None,))[0]
                    slide.background_image = self.pkg.read(target) if target else None
                    return
                fill, has = self.pkg.fill(bgpr)
                if has:
                    slide.background = fill
                    return
            ref = bg.find(P + "bgRef")
            if ref is not None:
                slide.background = self.pkg.color(ref) or slide.background
                return
        slide.background = self.pkg.theme.get(self.pkg.clr_map.get("bg1", "lt1"), "FFFFFF")


def build(path: str) -> Presentation:
    try:
        zf = zipfile.ZipFile(protect.source(path))
    except (OSError, zipfile.BadZipFile) as e:
        raise PptxError(str(e)) from e
    with zf:
        pkg = _Pptx(zf)
        pres_part = "ppt/presentation.xml"
        root = pkg.xml(pres_part)
        if root is None:
            raise PptxError("ppt/presentation.xml is missing or damaged")
        out = Presentation()
        sz = root.find(P + "sldSz")
        if sz is not None:
            out.width, out.height = int(sz.get("cx", out.width)), int(sz.get("cy", out.height))
        rels = pkg.rels(pres_part)
        theme_loaded = False
        for sid in root.iter(P + "sldId"):
            part = rels.get(sid.get(R + "id"), (None,))[0]
            if not part or part not in pkg.names:
                continue
            srels = pkg.rels(part)
            layout = next((t for t, k in srels.values() if k == "slideLayout"), None)
            master = next((t for t, k in pkg.rels(layout).values() if k == "slideMaster"), None) if layout else None
            if master and not theme_loaded:
                pkg.load_theme(master)
                theme_loaded = True
            reader = _SlideReader(pkg, part, layout, master)
            sroot = pkg.xml(part)
            slide = Slide(hidden=sroot.get("show") in ("0", "false"))
            tree = sroot.find(f"{P}cSld/{P}spTree")
            if tree is not None:
                slide.shapes = reader.shapes(tree)
            reader.background(slide)
            for el in (tree if tree is not None else []):
                ph = pkg.ph_of(el)
                if ph and ph[0] in ("title", "ctrTitle"):
                    slide.title = " ".join("".join(t.text or "" for t in el.iter(A + "t")).split())
                    break
            notes_part = next((t for t, k in srels.values() if k == "notesSlide"), None)
            if notes_part:
                nroot = pkg.xml(notes_part)
                if nroot is not None:
                    for sp in nroot.iter(P + "sp"):
                        ph = pkg.ph_of(sp)
                        if ph and ph[0] == "body":
                            slide.notes = "\n".join("".join(t.text or "" for t in p.iter(A + "t"))
                                                    for p in sp.iter(A + "p")).strip()
            out.slides.append(slide)
        return out
