"""Jupyter notebooks → rich-text operations. Pure Python, for a thread.

Markdown cells go through odv.mdrender; code cells become code blocks with
their execution count; outputs show their text (stream, plain results,
errors without ANSI colours) and their PNG/JPEG images. HTML outputs show
their plain-text alternative when there is one; nothing is executed.
"""

import base64
import json
import re

from odv import mdrender
from odv.mdrender import Doc, Heading, Link

_ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def _text(value) -> str:
    return "".join(value) if isinstance(value, list) else (value or "")


class _Notebook:
    def __init__(self):
        self.doc = Doc()

    def text(self, s: str, tags: tuple) -> None:
        if not s:
            return
        ops = self.doc.ops
        if ops and ops[-1][0] == "text" and ops[-1][2] == tags:
            ops[-1] = ("text", ops[-1][1] + s, tags)
        else:
            ops.append(("text", s, tags))
        self.doc.length += len(s)

    def merge(self, part: Doc) -> None:
        base = self.doc.length
        self.doc.ops.extend(part.ops)
        self.doc.links.extend(Link(link.start + base, link.end + base, link.href) for link in part.links)
        for slug, offset in part.headings.items():
            self.doc.headings.setdefault(slug, offset + base)
        self.doc.outline.extend(Heading(h.level, h.title, h.offset + base, 1) for h in part.outline)
        self.doc.length += part.length

    def image(self, data: bytes) -> None:
        self.doc.ops.append(("image", data))
        self.doc.length += 1
        self.text("\n", ())

    def code(self, source: str, label: str) -> None:
        self.text(label + "\n", ("dim",))
        self.text((source.rstrip("\n") or " ") + "\n", ("codeblock",))
        self.text("\n", ("gap",))

    def output(self, out: dict) -> None:
        kind = out.get("output_type")
        if kind == "stream":
            self.text(_ANSI.sub("", _text(out.get("text"))).rstrip("\n") + "\n", ("codeblock", "dim"))
        elif kind == "error":
            trace = "\n".join(out.get("traceback") or []) or f"{out.get('ename')}: {out.get('evalue')}"
            self.text(_ANSI.sub("", trace).rstrip("\n") + "\n", ("codeblock", "dim"))
        elif kind in ("execute_result", "display_data"):
            data = out.get("data") or {}
            shown = False
            for mime in ("image/png", "image/jpeg"):
                if mime in data:
                    try:
                        self.image(base64.b64decode(_text(data[mime])))
                        shown = True
                    except (ValueError, TypeError):
                        pass
                    break
            if not shown and "text/markdown" in data:
                self.merge(mdrender.build(_text(data["text/markdown"])))
                shown = True
            if not shown and "text/plain" in data:
                self.text(_text(data["text/plain"]).rstrip("\n") + "\n", ("codeblock", "dim"))
        self.text("\n", ("gap",))


def build(text: str, path: str = "") -> Doc:
    nb = json.loads(text)
    out = _Notebook()
    cells = nb.get("cells")
    if cells is None:  # nbformat 3 kept cells inside worksheets
        cells = [c for ws in nb.get("worksheets") or [] for c in ws.get("cells") or []]
    for cell in cells:
        kind = cell.get("cell_type")
        source = _text(cell.get("source", cell.get("input")))
        if kind == "markdown":
            out.merge(mdrender.build(source))
        elif kind == "code":
            count = cell.get("execution_count", cell.get("prompt_number"))
            out.code(source, f"In [{count if count is not None else ' '}]:")
            for o in cell.get("outputs") or []:
                out.output(o)
        elif kind == "raw":
            out.code(source, "Raw:")
    return out.doc
