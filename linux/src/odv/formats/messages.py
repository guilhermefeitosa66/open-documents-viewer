"""E-mail messages (.eml), contacts (.vcf) and calendars (.ics) as readable
documents. Pure Python (stdlib email), for a thread.

E-mail HTML goes through the safe HTML renderer: no scripts, nothing
remote; inline images (cid:) come from the message itself. Attachments
are listed as links the viewer offers to save.
"""

import base64
import datetime
import email
import email.policy
import html
import re

from odv.formats import htmlrender
from odv.i18n import _
from odv.mdrender import Doc, Heading, Link, Table


class _Out:
    def __init__(self):
        self.doc = Doc()

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

    def heading(self, title, level=1):
        self.doc.outline.append(Heading(level, title, self.doc.length, 1))
        self.text(title + "\n", (f"h{level}",))

    def widget(self, op):
        self.doc.ops.append(op)
        self.doc.length += 1
        self.text("\n")

    def link(self, label, href, tags=()):
        start = self.doc.length
        self.text(label, tuple(tags) + ("link",))
        self.doc.links.append(Link(start, self.doc.length, href))

    def merge(self, part: Doc):
        base = self.doc.length
        self.doc.ops.extend(part.ops)
        self.doc.links.extend(Link(link.start + base, link.end + base, link.href) for link in part.links)
        for key, offset in part.headings.items():
            self.doc.headings.setdefault(key, offset + base)
        self.doc.outline.extend(Heading(h.level, h.title, h.offset + base, 1) for h in part.outline)
        self.doc.length += part.length


# -- e-mail -----------------------------------------------------------------------

def build_eml(data: bytes) -> Doc:
    msg = email.message_from_bytes(data, policy=email.policy.default)
    out = _Out()
    out.heading(str(msg.get("Subject") or _("(no subject)")))
    labels = {"From": _("From"), "To": _("To"), "Cc": _("Cc"), "Date": _("Date"), "Reply-To": _("Reply to")}
    rows = [(labels[name], str(msg.get(name))) for name in labels if msg.get(name)]
    if rows:
        out.widget(("table", Table([], [[k, v] for k, v in rows], ["left", "left"])))
    inline = {}
    for part in msg.walk():
        cid = part.get("Content-ID")
        if cid and part.get_content_maintype() == "image":
            inline[cid.strip("<>")] = part.get_payload(decode=True)
    body = msg.get_body(preferencelist=("html", "plain"))
    if body is not None:
        content = body.get_content()
        if body.get_content_subtype() == "html":
            def resolve(src):
                return inline.get(src[4:]) if src.startswith("cid:") else None

            out.merge(htmlrender.build(content, resolve_image=resolve))
        else:
            for paragraph in re.split(r"\n\s*\n", content.strip()):
                out.text(paragraph.strip() + "\n", ("p",))
    attachments = [part for part in msg.iter_attachments()]
    if attachments:
        out.heading(_("Attachments"), 2)
        for i, part in enumerate(attachments):
            name = part.get_filename() or f"attachment-{i + 1}"
            payload = part.get_payload(decode=True) or b""
            href = f"odv-attachment:{i}"
            out.doc.attachments[href] = (name, payload)
            out.text("• ", ("marker",))
            out.link(name, href, ("p",))
            out.text(f"  ({len(payload):,} bytes)\n".replace(",", "."), ("p", "dim"))
    return out.doc


# -- vCard / iCalendar -----------------------------------------------------------------

def _unfold(text: str) -> list[tuple[str, dict, str]]:
    """Content lines: (NAME, params, value), continuation lines joined."""
    lines = []
    for raw in re.split(r"\r?\n", text):
        if raw[:1] in (" ", "\t") and lines:
            lines[-1] += raw[1:]
        elif raw:
            lines.append(raw)
    out = []
    for line in lines:
        head, _, value = line.partition(":")
        parts = head.split(";")
        name = parts[0].split(".")[-1].upper()
        params = {}
        for p in parts[1:]:
            k, _, v = p.partition("=")
            params[k.upper()] = v
        out.append((name, params, value))
    return out


BS = chr(92)  # a backslash


def _unescape(value: str) -> str:
    return (value.replace(BS + "n", "\n").replace(BS + "N", "\n").replace(BS + ",", ",")
            .replace(BS + ";", ";").replace(BS + BS, BS))


VCARD_FIELDS = [("ORG", "Organization"), ("TITLE", "Title"), ("TEL", "Phone"), ("EMAIL", "E-mail"),
                ("ADR", "Address"), ("URL", "Web"), ("BDAY", "Birthday"), ("NOTE", "Note")]


def build_vcf(text: str) -> Doc:
    out = _Out()
    card = None
    for name, params, value in _unfold(text):
        if name == "BEGIN" and value.upper() == "VCARD":
            card = {"rows": [], "photo": None, "fn": None}
        elif name == "END" and card is not None:
            out.heading(card["fn"] or _("(no name)"), 2)
            if card["photo"]:
                out.widget(("image", card["photo"]))
            if card["rows"]:
                order = {k: i for i, (k, _l) in enumerate(VCARD_FIELDS)}
                rows = sorted(card["rows"], key=lambda r: order.get(r[0], 99))
                labels = {k: _(v) for k, v in VCARD_FIELDS}
                out.widget(("table", Table([], [[labels[k] + (f" ({t})" if t else ""), v] for k, t, v in rows],
                                           ["left", "left"])))
            card = None
        elif card is not None:
            if name == "FN":
                card["fn"] = _unescape(value)
            elif name == "N" and not card["fn"]:
                parts = [p for p in _unescape(value).split(";") if p]
                card["fn"] = " ".join(reversed(parts[:2])) if parts else None
            elif name == "PHOTO" and (params.get("ENCODING", "").upper() in ("B", "BASE64") or value.startswith("data:")):
                try:
                    card["photo"] = base64.b64decode(value.split(",", 1)[-1])
                except ValueError:
                    pass
            elif name in dict(VCARD_FIELDS):
                kind = params.get("TYPE", "").lower().replace(",", ", ")
                v = _unescape(value)
                if name == "ADR":
                    v = ", ".join(p for p in v.split(";") if p.strip())
                card["rows"].append((name, kind, v))
    return out.doc


def _when(value: str, params: dict) -> tuple[str, str]:
    """(sort key, human text) for an iCalendar date or date-time."""
    v = value.strip()
    try:
        if "T" in v:
            utc = v.endswith("Z")
            dt = datetime.datetime.strptime(v.rstrip("Z")[:15], "%Y%m%dT%H%M%S")
            text = dt.strftime("%Y-%m-%d %H:%M") + (" UTC" if utc else (f" ({params['TZID']})" if "TZID" in params else ""))
            return dt.isoformat(), text
        d = datetime.datetime.strptime(v[:8], "%Y%m%d").date()
        return d.isoformat(), d.isoformat()
    except ValueError:
        return v, v


def build_ics(text: str) -> Doc:
    out = _Out()
    items = []
    current = None
    for name, params, value in _unfold(text):
        if name == "BEGIN" and value.upper() in ("VEVENT", "VTODO", "VJOURNAL"):
            current = {"kind": value.upper(), "props": {}}
        elif name == "END" and current is not None and value.upper() == current["kind"]:
            items.append(current)
            current = None
        elif current is not None and name in ("SUMMARY", "DTSTART", "DTEND", "DUE", "LOCATION", "DESCRIPTION",
                                              "STATUS", "ORGANIZER", "URL"):
            current["props"][name] = (params, value)
    def key(item):
        p = item["props"].get("DTSTART") or item["props"].get("DUE")
        return _when(p[1], p[0])[0] if p else "9999"
    for item in sorted(items, key=key):
        props = item["props"]
        title = _unescape(props.get("SUMMARY", ({}, _("(untitled)")))[1])
        out.heading(title, 2)
        rows = []
        labels = {"DTSTART": _("Starts"), "DTEND": _("Ends"), "DUE": _("Due"), "LOCATION": _("Location"),
                  "STATUS": _("Status"), "ORGANIZER": _("Organizer"), "URL": _("Web")}
        for prop, label in labels.items():
            if prop in props:
                params, value = props[prop]
                shown = _when(value, params)[1] if prop in ("DTSTART", "DTEND", "DUE") else _unescape(value)
                if prop == "ORGANIZER":
                    shown = params.get("CN", shown.replace("mailto:", ""))
                rows.append([label, shown])
        if rows:
            out.widget(("table", Table([], rows, ["left", "left"])))
        if "DESCRIPTION" in props:
            for paragraph in _unescape(props["DESCRIPTION"][1]).split("\n\n"):
                out.text(paragraph.strip() + "\n", ("p",))
    return out.doc


def build(text, path: str = "") -> Doc:
    lower = path.lower()
    if lower.endswith((".eml", ".mbox-message", ".msg822")):
        with open(path, "rb") as f:
            return build_eml(f.read())
    if lower.endswith((".vcf", ".vcard")):
        return build_vcf(text)
    return build_ics(text)
