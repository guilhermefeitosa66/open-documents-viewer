"""Markdown → a flat list of insert operations for a Gtk.TextBuffer.

Pure Python (markdown-it-py, no GTK), so it runs in a worker thread and is
testable without a display. The widget side (views/markdown.py) only applies
the operations.

Operations:
  ("text", str, tags)      insert text carrying the named tags
  ("hr",)                  a horizontal rule (a child widget)
  ("table", Table)         a table (a child widget)
Offsets count Unicode characters, like GtkTextBuffer; every child widget
takes one character.
"""

import re
import unicodedata
from dataclasses import dataclass, field

BULLETS = ("•", "◦", "▪")


@dataclass
class Table:
    header: list[str]
    rows: list[list[str]]
    align: list[str]  # "left" | "center" | "right" per column


@dataclass
class Link:
    start: int
    end: int
    href: str


@dataclass
class Heading:
    level: int
    title: str
    offset: int  # in the formatted buffer
    line: int  # 1-based, in the source


@dataclass
class Doc:
    ops: list = field(default_factory=list)
    links: list[Link] = field(default_factory=list)
    headings: dict[str, int] = field(default_factory=dict)  # slug -> offset
    outline: list[Heading] = field(default_factory=list)
    length: int = 0


def slugify(text: str, seen: dict[str, int]) -> str:
    """GitHub-style heading anchor."""
    s = unicodedata.normalize("NFKC", text).strip().lower()
    s = re.sub(r"[^\w\- ]", "", s).replace(" ", "-")
    n = seen.get(s, 0)
    seen[s] = n + 1
    return s if n == 0 else f"{s}-{n}"


_TASK = re.compile(r"^\[([ xX])\] ")

_md = None


def _parser():
    global _md
    if _md is None:
        from markdown_it import MarkdownIt

        _md = MarkdownIt("commonmark", {"html": True}).enable(["table", "strikethrough"])
    return _md


class _Builder:
    def __init__(self):
        self.doc = Doc()
        self.block: list[str] = []  # block-level tags in effect
        self.lists: list[dict] = []
        self.marker: str | None = None  # list marker waiting for its item's text
        self.quote_bar = False  # a quote paragraph waiting for its bar
        self.slugs: dict[str, int] = {}

    # -- output ---------------------------------------------------------
    def text(self, s: str, extra: tuple = ()) -> None:
        if not s:
            return
        tags = tuple(self.block) + tuple(extra)
        ops = self.doc.ops
        if ops and ops[-1][0] == "text" and ops[-1][2] == tags:
            ops[-1] = ("text", ops[-1][1] + s, tags)
        else:
            ops.append(("text", s, tags))
        self.doc.length += len(s)

    def widget(self, op: tuple) -> None:
        self.doc.ops.append(op)
        self.doc.length += 1
        self.text("\n")

    def flush_marker(self) -> None:
        if self.marker is not None:
            m, self.marker = self.marker, None
            self.text(m, ("marker",))

    # -- inline ---------------------------------------------------------
    def inline(self, children, first_in_item: bool) -> None:
        style: list[str] = []
        link_start: list[tuple[int, str]] = []
        for i, tok in enumerate(children):
            t = tok.type
            if t == "text":
                content = tok.content
                if first_in_item and i == 0:
                    m = _TASK.match(content)
                    if m:
                        self.text("☑ " if m.group(1) != " " else "☐ ", ("marker",))
                        content = content[m.end():]
                self.text(content, tuple(style))
            elif t == "code_inline":
                self.text(tok.content, tuple(style) + ("code",))
            elif t == "softbreak":
                self.text(" ", tuple(style))
            elif t == "hardbreak":
                self.text("\n", tuple(style))
            elif t in ("strong_open", "em_open", "s_open"):
                style.append({"strong_open": "bold", "em_open": "italic", "s_open": "strike"}[t])
            elif t in ("strong_close", "em_close", "s_close"):
                tag = {"strong_close": "bold", "em_close": "italic", "s_close": "strike"}[t]
                if tag in style:
                    style.remove(tag)
            elif t == "link_open":
                link_start.append((self.doc.length, tok.attrGet("href") or ""))
                style.append("link")
            elif t == "link_close":
                if "link" in style:
                    style.remove("link")
                if link_start:
                    start, href = link_start.pop()
                    self.doc.links.append(Link(start, self.doc.length, href))
            elif t == "image":
                alt = "".join(c.content for c in (tok.children or [])) or tok.content
                self.text(f"[image: {alt}]" if alt else "[image]", tuple(style) + ("dim",))
            elif t == "html_inline":
                self.text(tok.content, tuple(style) + ("dim",))
            else:
                self.text(tok.content, tuple(style))

    @staticmethod
    def plain(tok) -> str:
        if not tok.children:
            return tok.content
        out = []
        for c in tok.children:
            if c.type in ("text", "code_inline", "html_inline"):
                out.append(c.content)
            elif c.type in ("softbreak", "hardbreak"):
                out.append(" ")
            elif c.type == "image":
                out.append("".join(x.content for x in (c.children or [])))
        return "".join(out)

    # -- blocks ---------------------------------------------------------
    def build(self, tokens) -> Doc:
        i, n = 0, len(tokens)
        item_fresh = False
        while i < n:
            tok = tokens[i]
            t = tok.type
            if t == "heading_open":
                level = int(tok.tag[1])
                inline = tokens[i + 1]
                title = self.plain(inline)
                self.doc.headings[slugify(title, self.slugs)] = self.doc.length
                line = (tok.map[0] + 1) if tok.map else 1
                self.doc.outline.append(Heading(level, title, self.doc.length, line))
                self.block.append(f"h{level}")
            elif t == "heading_close":
                self.text("\n")
                self.block.pop()
            elif t == "paragraph_open":
                self.block.append("li" if tok.hidden else "p")
                self.quote_bar = "quote" in self.block
            elif t == "paragraph_close":
                self.text("\n")
                self.block.pop()
            elif t == "inline":
                self.flush_marker()
                if self.quote_bar:
                    self.quote_bar = False
                    self.text("▎ ", ("quotebar",))
                self.inline(tok.children or [], item_fresh)
                item_fresh = False
            elif t in ("bullet_list_open", "ordered_list_open"):
                start = int(tok.attrGet("start") or 1) if t == "ordered_list_open" else 0
                self.lists.append({"ordered": t == "ordered_list_open", "n": start})
            elif t in ("bullet_list_close", "ordered_list_close"):
                self.lists.pop()
                if not self.lists:
                    self.text("\n", ("gap",))
            elif t == "list_item_open":
                lst = self.lists[-1]
                depth = len(self.lists)
                if lst["ordered"]:
                    self.marker = f"{lst['n']}. "
                    lst["n"] += 1
                else:
                    self.marker = BULLETS[(depth - 1) % len(BULLETS)] + " "
                self.block.append(f"ind{min(depth, 8)}")
                item_fresh = True
            elif t == "list_item_close":
                if self.marker is not None:  # an empty item
                    self.flush_marker()
                    self.text("\n")
                self.block.pop()
            elif t == "blockquote_open":
                self.block.append("quote")
            elif t == "blockquote_close":
                self.block.pop()
            elif t in ("fence", "code_block"):
                self.flush_marker()
                code = tok.content[:-1] if tok.content.endswith("\n") else tok.content
                self.text(code + "\n", ("codeblock",))
                self.text("\n", ("gap",))
            elif t == "html_block":
                self.flush_marker()
                self.text(tok.content.rstrip("\n") + "\n", ("dim", "codeblock"))
                self.text("\n", ("gap",))
            elif t == "hr":
                self.widget(("hr",))
            elif t == "table_open":
                i = self.table(tokens, i)
                continue
            i += 1
        return self.doc

    def table(self, tokens, i: int) -> int:
        header: list[str] = []
        rows: list[list[str]] = []
        align: list[str] = []
        row: list[str] | None = None
        in_head = False
        while tokens[i].type != "table_close":
            tok = tokens[i]
            t = tok.type
            if t == "thead_open":
                in_head = True
            elif t == "thead_close":
                in_head = False
            elif t == "tr_open":
                row = []
            elif t == "tr_close":
                (header.extend(row) if in_head else rows.append(row)) if row is not None else None
                row = None
            elif t in ("th_open", "td_open"):
                if in_head:
                    style = tok.attrGet("style") or ""
                    m = re.search(r"text-align:\s*(\w+)", style)
                    align.append(m.group(1) if m else "left")
            elif t == "inline" and row is not None:
                row.append(self.plain(tok))
            i += 1
        self.flush_marker()
        self.widget(("table", Table(header, rows, align)))
        return i + 1


def build(text: str, path: str = "") -> Doc:
    return _Builder().build(_parser().parse(text))
