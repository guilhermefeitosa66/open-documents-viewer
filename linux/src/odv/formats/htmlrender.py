"""HTML → the rich-text operations of odv.mdrender, rendered natively.

Safe by construction: nothing is fetched, nothing runs. Scripts, styles,
forms and embedded frames are dropped; links go through the same
confirmation as Markdown; images show their alt text (local images are
handled with Markdown images). Pure Python (html.parser), for a thread.
"""

import re
from html.parser import HTMLParser

from odv.mdrender import BULLETS, Doc, Heading, Link, Table, slugify

SKIP = {"script", "style", "head", "noscript", "template", "iframe", "object", "embed", "math",
        "canvas", "select", "button", "textarea", "video", "audio", "map", "form"}
BLOCK = {"p", "div", "section", "article", "main", "header", "footer", "nav", "aside", "figure",
         "figcaption", "address", "details", "summary", "dl", "dt", "dd", "fieldset", "legend",
         "center", "body", "html", "caption"}
VOID = {"br", "hr", "img", "input", "meta", "link", "area", "base", "col", "embed", "param", "source",
        "track", "wbr"}
INLINE_STYLE = {"b": "bold", "strong": "bold", "i": "italic", "em": "italic", "cite": "italic",
                "var": "italic", "dfn": "italic", "u": "underline", "ins": "underline", "s": "strike",
                "strike": "strike", "del": "strike", "code": "code", "kbd": "code", "samp": "code",
                "tt": "code", "small": "dim", "sub": "dim", "sup": "dim", "mark": "bold"}
_WS = re.compile(r"[ \t\r\n\f]+")


class _HtmlBuilder(HTMLParser):
    def __init__(self, resolve_image=None):
        super().__init__(convert_charrefs=True)
        self.resolve_image = resolve_image  # src -> bytes or None; never fetches from the network
        self.doc = Doc()
        self.block: list[str] = []  # block tags in effect (stack, with markers for pops)
        self.stack: list[tuple[str, list]] = []  # open elements: (tag, what to undo on close)
        self.inline: list[str] = []
        self.lists: list[dict] = []
        self.marker: str | None = None
        self.skip = 0
        self.pre = 0
        self.slugs: dict[str, int] = {}
        self.heading: dict | None = None
        self.link_start: list[tuple[int, str]] = []
        self.table = None  # {"rows": [...], "row": [...], "cell": [...], "header": [...], "head": bool}
        self.last = "\n"  # last character emitted, for whitespace and line breaks
        self.title = None
        self._in_title = False

    # -- output ---------------------------------------------------------------
    def emit(self, s: str, extra: tuple = ()) -> None:
        if not s:
            return
        if self.marker is not None and s.strip():
            m, self.marker = self.marker, None
            self.emit(m, ("marker",))
        tags = tuple(self.block) + tuple(self.inline) + tuple(extra)
        ops = self.doc.ops
        if ops and ops[-1][0] == "text" and ops[-1][2] == tags:
            ops[-1] = ("text", ops[-1][1] + s, tags)
        else:
            ops.append(("text", s, tags))
        self.doc.length += len(s)
        self.last = s[-1]

    def newline(self) -> None:
        if self.last != "\n":
            self.emit("\n")

    def widget(self, op: tuple) -> None:
        self.newline()
        self.doc.ops.append(op)
        self.doc.length += 1
        self.last = "x"
        self.emit("\n")

    # -- parser callbacks -------------------------------------------------------
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "title":
            self._in_title = True
            return
        if tag in SKIP:
            if tag not in VOID:
                self.skip += 1
            return
        if self.skip:
            return
        anchor = a.get("id") or (a.get("name") if tag == "a" else None)
        if anchor:
            self.doc.headings.setdefault(anchor.lower(), self.doc.length)
        if self.table is not None:
            self._table_start(tag, a)
            return
        undo = []
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
            self.newline()
            self.block.append(tag)
            undo.append("block")
            self.heading = {"level": int(tag[1]), "offset": self.doc.length, "text": [],
                            "line": self.getpos()[0], "id": a.get("id")}
        elif tag == "p":
            self.newline()
            self.block.append("p")
            undo.append("block")
        elif tag in BLOCK:
            self.newline()
        elif tag == "br":
            self.emit("\n")
        elif tag == "hr":
            self.widget(("hr",))
        elif tag == "pre":
            self.newline()
            self.pre += 1
            self.block.append("codeblock")
            undo.append("block")
            undo.append("pre")
        elif tag == "blockquote":
            self.newline()
            self.block.append("quote")
            undo.append("block")
        elif tag in ("ul", "ol", "menu"):
            self.newline()
            start = int(a.get("start", "1")) if (a.get("start") or "1").isdigit() else 1
            self.lists.append({"ordered": tag == "ol", "n": start})
            undo.append("list")
        elif tag == "li":
            self.newline()
            depth = max(1, len(self.lists))
            lst = self.lists[-1] if self.lists else {"ordered": False, "n": 1}
            if lst["ordered"]:
                self.marker = f"{lst['n']}. "
                lst["n"] += 1
            else:
                self.marker = BULLETS[(depth - 1) % len(BULLETS)] + " "
            self.block.append(f"ind{min(depth, 8)}")
            self.block.append("li")
            undo += ["block", "block"]
        elif tag == "a" and a.get("href") is not None:
            self.link_start.append((self.doc.length, a.get("href") or ""))
            self.inline.append("link")
            undo += ["inline", "link"]
        elif tag in INLINE_STYLE:
            self.inline.append(INLINE_STYLE[tag])
            undo.append("inline")
        elif tag in ("img", "image"):
            src = a.get("src") or a.get("xlink:href") or a.get("href") or ""
            data = self.resolve_image(src) if self.resolve_image and src else None
            if data:
                self.newline()
                self.doc.ops.append(("image", data))
                self.doc.length += 1
                self.last = "x"
                self.emit("\n")
            else:
                alt = (a.get("alt") or "").strip()
                self.emit(f"[image: {alt}]" if alt else "[image]", ("dim",))
        elif tag == "table":
            self.newline()
            self.table = {"rows": [], "row": None, "cell": None, "header": [], "head": False, "align": [],
                          "depth": 1}
            return
        if tag not in VOID:
            self.stack.append((tag, undo))

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
            return
        if tag in SKIP:
            if tag not in VOID:
                self.skip = max(0, self.skip - 1)
            return
        if self.skip:
            return
        if self.table is not None:
            self._table_end(tag)
            return
        # Close up to the matching element (HTML in the wild is not balanced).
        if not any(t == tag for t, _ in self.stack):
            return
        while self.stack:
            t, undo = self.stack.pop()
            self._undo(t, undo)
            if t == tag:
                break

    def _undo(self, tag, undo):
        for what in reversed(undo):
            if what == "block" and self.block:
                self.block.pop()
            elif what == "inline" and self.inline:
                self.inline.pop()
            elif what == "pre":
                self.pre = max(0, self.pre - 1)
            elif what == "list" and self.lists:
                self.lists.pop()
                if not self.lists:
                    self.newline()
                    self.emit("\n", ("gap",))
            elif what == "link" and self.link_start:
                start, href = self.link_start.pop()
                self.doc.links.append(Link(start, self.doc.length, href))
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6") and self.heading:
            h = self.heading
            self.heading = None
            title = _WS.sub(" ", "".join(h["text"])).strip()
            if title:
                self.doc.headings.setdefault(slugify(title, self.slugs), h["offset"])
                self.doc.outline.append(Heading(h["level"], title, h["offset"], h["line"]))
            self.emit("\n")
        elif tag in ("p", "pre", "blockquote", "li") or tag in BLOCK:
            self.newline()
            if tag == "pre":
                self.emit("\n", ("gap",))

    def handle_data(self, data):
        if self._in_title:
            self.title = (self.title or "") + data
            return
        if self.skip:
            return
        if self.table is not None:
            if self.table["cell"] is not None:
                self.table["cell"].append(data)
            return
        if self.pre:
            text = data
        else:
            text = _WS.sub(" ", data)
            if self.last in ("\n", " "):
                text = text.lstrip(" ")
        if not text:
            return
        if self.heading is not None:
            self.heading["text"].append(text)
        self.emit(text)

    # -- tables ---------------------------------------------------------------
    def _close_cell(self):
        t = self.table
        if t["cell"] is not None:
            t["row"].append(_WS.sub(" ", "".join(t["cell"])).strip())
            if len(t["align"]) < len(t["row"]):
                t["align"].append(t.get("cell_align", "left"))
            t["cell"] = None

    def _close_row(self):
        t = self.table
        self._close_cell()
        if t["row"] is not None:
            if not t["header"] and (t["head"] or (t.get("is_th") and not t["rows"])):
                t["header"] = t["row"]
            else:
                t["rows"].append(t["row"])
            t["row"] = None

    def _table_start(self, tag, a):
        # Cells and rows close implicitly in HTML: a new one ends the last.
        t = self.table
        if tag == "table":
            t["depth"] += 1
        elif tag == "thead":
            t["head"] = True
        elif tag in ("tbody", "tfoot"):
            self._close_row()
            t["head"] = False
        elif tag == "tr":
            self._close_row()
            t["row"] = []
        elif tag in ("td", "th"):
            self._close_cell()
            if t["row"] is None:
                t["row"] = []
            t["cell"] = []
            t["is_th"] = tag == "th"
            align = (a.get("align") or "").lower()
            m = re.search(r"text-align:\s*(\w+)", a.get("style") or "")
            t["cell_align"] = m.group(1) if m else (align or "left")
        elif tag == "br" and t["cell"] is not None:
            t["cell"].append(" ")

    def _table_end(self, tag):
        t = self.table
        if tag in ("td", "th"):
            self._close_cell()
        elif tag == "tr":
            self._close_row()
        elif tag == "thead":
            self._close_row()
            t["head"] = False
        elif tag == "table":
            t["depth"] -= 1
            if t["depth"] <= 1:
                self._close_row()
                self.table = None
                if t["header"] or t["rows"]:
                    self.widget(("table", Table(t["header"], t["rows"], t["align"])))


def local_images(doc_path: str):
    """Resolve <img src> to bytes for files next to the document; remote
    addresses (and anything outside the file system) are never loaded."""
    import os
    from urllib.parse import unquote, urlparse

    base = os.path.dirname(os.path.abspath(doc_path))

    def resolve(src):
        parsed = urlparse(src)
        if parsed.scheme not in ("", "file") or parsed.netloc:
            return None
        target = unquote(parsed.path)
        target = target if os.path.isabs(target) else os.path.join(base, target)
        try:
            if os.path.getsize(target) > 32 * 1024 * 1024:
                return None
            with open(target, "rb") as f:
                return f.read()
        except OSError:
            return None

    return resolve


def build(text: str, path: str = "", resolve_image=None) -> Doc:
    if resolve_image is None and path:
        resolve_image = local_images(path)
    builder = _HtmlBuilder(resolve_image)
    builder.feed(text)
    builder.close()
    while builder.stack:
        tag, undo = builder.stack.pop()
        builder._undo(tag, undo)
    return builder.doc
