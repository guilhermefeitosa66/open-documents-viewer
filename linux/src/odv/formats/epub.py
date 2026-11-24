"""EPUB 2/3 (without DRM) and FictionBook 2 → rich-text operations.

An EPUB's chapters (the OPF spine, in reading order) go through the HTML
renderer one after another; anchors become "chapter.xhtml#id" so links
between chapters work, and images are read from inside the archive. The
outline is the book's own table of contents (EPUB 3 nav or EPUB 2 NCX),
falling back to the chapters' headings. Pure Python, for a thread.
"""

import posixpath
import xml.etree.ElementTree as ET
import zipfile
from urllib.parse import unquote, urldefrag

from odv.formats import htmlrender
from odv.mdrender import Doc, Heading, Link

MAX_MEMBER = 64 * 1024 * 1024


class EpubError(Exception):
    pass


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _find_all(root, name):
    return [el for el in root.iter() if _local(el.tag) == name]


def _merge(out: Doc, part: Doc, chapter: str) -> None:
    base = out.length
    out.ops.extend(part.ops)
    out.headings.setdefault(chapter.lower(), base)
    for key, offset in part.headings.items():
        out.headings.setdefault(f"{chapter}#{key}".lower(), offset + base)
    for link in part.links:
        out.links.append(Link(link.start + base, link.end + base, _rewrite(link.href, chapter)))
    out.outline.extend(Heading(h.level, h.title, h.offset + base, 1) for h in part.outline)
    out.length += part.length


def _rewrite(href: str, chapter: str) -> str:
    """Links inside the book become anchors of the combined document."""
    if not href or "://" in href or href.startswith("mailto:"):
        return href
    path, fragment = urldefrag(href)
    target = posixpath.normpath(posixpath.join(posixpath.dirname(chapter), unquote(path))) if path else chapter
    return "#" + target + ("#" + fragment if fragment else "")


def build(text, path: str = "") -> Doc:
    if path.lower().endswith(".fb2"):
        return build_fb2(path)
    try:
        book = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        raise EpubError(str(e)) from e
    with book:
        def read(name):
            info = book.getinfo(name)
            if info.file_size > MAX_MEMBER:
                raise EpubError(f"{name} is too large")
            return book.read(info)

        names = set(book.namelist())
        if "META-INF/encryption.xml" in names:
            enc = read("META-INF/encryption.xml").decode("utf-8", "replace")
            if "EncryptedData" in enc and "font" not in enc.lower():
                raise EpubError("this book is protected by DRM")
        container = ET.fromstring(read("META-INF/container.xml"))
        opf_path = next(el.get("full-path") for el in _find_all(container, "rootfile"))
        opf_dir = posixpath.dirname(opf_path)
        opf = ET.fromstring(read(opf_path))
        manifest = {}
        nav_href = None
        for item in _find_all(opf, "item"):
            href = posixpath.normpath(posixpath.join(opf_dir, unquote(item.get("href", ""))))
            manifest[item.get("id")] = (href, item.get("media-type", ""))
            if "nav" in (item.get("properties") or "").split():
                nav_href = href
        spine_el = next(iter(_find_all(opf, "spine")), None)
        spine = [manifest[ref.get("idref")][0] for ref in _find_all(opf, "itemref")
                 if ref.get("idref") in manifest and ref.get("linear") != "no"]
        ncx_id = spine_el.get("toc") if spine_el is not None else None

        out = Doc()
        for chapter in spine:
            if chapter not in names:
                continue

            def resolve(src, chapter=chapter):
                target = posixpath.normpath(posixpath.join(posixpath.dirname(chapter), unquote(src)))
                try:
                    return read(target) if target in names else None
                except EpubError:
                    return None

            html = read(chapter).decode("utf-8", "replace")
            _merge(out, htmlrender.build(html, resolve_image=resolve), chapter)

        toc = _toc(read, names, nav_href, manifest.get(ncx_id, (None,))[0])
        if toc:
            outline = []
            for level, title, href in toc:
                offset = out.headings.get(href.lower(), out.headings.get(href.split("#")[0].lower()))
                if offset is not None:
                    outline.append(Heading(level, title, offset, 1))
            if outline:
                out.outline = outline
        return out


def _toc(read, names, nav_href, ncx_href) -> list[tuple[int, str, str]]:
    """[(level, title, "chapter#id")] from the EPUB 3 nav or the EPUB 2 NCX."""
    entries = []
    if nav_href and nav_href in names:
        try:
            root = ET.fromstring(read(nav_href))
        except ET.ParseError:
            root = None
        if root is not None:
            nav = next((n for n in _find_all(root, "nav")
                        if "toc" in " ".join(v for k, v in n.attrib.items() if k.endswith("type"))), None)
            if nav is not None:
                def walk(ol, level):
                    for li in ol:
                        if _local(li.tag) != "li":
                            continue
                        link = next((a for a in li.iter() if _local(a.tag) == "a"), None)
                        if link is not None and link.get("href"):
                            title = " ".join("".join(link.itertext()).split())
                            entries.append((level, title, _target(nav_href, link.get("href"))))
                        for sub in li:
                            if _local(sub.tag) == "ol":
                                walk(sub, level + 1)

                for ol in nav:
                    if _local(ol.tag) == "ol":
                        walk(ol, 1)
    if not entries and ncx_href and ncx_href in names:
        try:
            root = ET.fromstring(read(ncx_href))
        except ET.ParseError:
            return entries

        def walk_points(parent, level):
            for point in parent:
                if _local(point.tag) != "navPoint":
                    continue
                label = next((t for t in point.iter() if _local(t.tag) == "text"), None)
                content = next((c for c in point.iter() if _local(c.tag) == "content"), None)
                if label is not None and content is not None:
                    entries.append((level, (label.text or "").strip(), _target(ncx_href, content.get("src", ""))))
                walk_points(point, level + 1)

        for nav_map in _find_all(root, "navMap"):
            walk_points(nav_map, 1)
    return entries


def _target(base_file: str, href: str) -> str:
    path, fragment = urldefrag(href)
    target = posixpath.normpath(posixpath.join(posixpath.dirname(base_file), unquote(path)))
    return target + ("#" + fragment if fragment else "")


# -- FictionBook 2 ----------------------------------------------------------------

def build_fb2(path: str) -> Doc:
    import base64
    import html

    tree = ET.parse(path)
    root = tree.getroot()
    binaries = {el.get("id"): el.text or "" for el in root.iter() if _local(el.tag) == "binary"}
    parts = []

    def convert(el, depth):
        tag = _local(el.tag)
        inner = html.escape(el.text or "")
        for child in el:
            inner += convert(child, depth + (1 if tag == "section" else 0))
            inner += html.escape(child.tail or "")
        if tag in ("binary", "description"):
            return ""
        mapping = {"p": "p", "emphasis": "em", "strong": "strong", "strikethrough": "s", "code": "code",
                   "epigraph": "blockquote", "cite": "blockquote", "poem": "blockquote", "v": "p",
                   "subtitle": "h4", "text-author": "p", "table": "table", "tr": "tr", "td": "td", "th": "th"}
        if tag == "title":
            level = min(6, depth + 1)
            return f"<h{level}>{inner}</h{level}>"
        if tag == "empty-line":
            return "<br>"
        if tag == "image":
            ref = next((v for k, v in el.attrib.items() if k.endswith("href")), "")
            return f'<img src="{html.escape(ref)}">'
        if tag == "a":
            ref = next((v for k, v in el.attrib.items() if k.endswith("href")), "")
            return f'<a href="{html.escape(ref)}">{inner}</a>'
        if tag in mapping:
            return f"<{mapping[tag]}>{inner}</{mapping[tag]}>"
        return f"<div>{inner}</div>" if tag in ("section", "body", "stanza") else inner

    for body in (el for el in root if _local(el.tag) == "body"):
        parts.append(convert(body, 0))

    def resolve(src):
        data = binaries.get(src.lstrip("#"))
        try:
            return base64.b64decode(data) if data else None
        except ValueError:
            return None

    return htmlrender.build("".join(parts), resolve_image=resolve)
