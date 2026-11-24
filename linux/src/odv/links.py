"""What a link in a document points to. Pure Python."""

import os
from urllib.parse import unquote, urlparse

EXTERNAL_SCHEMES = {"http", "https", "mailto", "ftp", "ftps", "tel", "irc", "ircs", "news", "xmpp"}


def resolve(href: str, doc_path: str) -> tuple[str, str | None, str | None]:
    """Classify ``href`` found in the document at ``doc_path``.

    Returns (kind, target, anchor) with kind one of:
      "anchor"   a heading in this document (anchor set)
      "local"    an existing file or folder (target: absolute path; anchor may be set)
      "missing"  a local path that does not exist (target: as written)
      "external" a web, mail… address (target: the URI)
      "ignored"  empty, or a scheme we do not follow (javascript:, data:…)
    """
    href = (href or "").strip()
    if not href:
        return "ignored", None, None
    if href.startswith("#"):
        return "anchor", None, unquote(href[1:])
    parsed = urlparse(href)
    scheme = parsed.scheme.lower()
    if scheme in ("", "file") and not (scheme == "" and parsed.netloc):
        path = unquote(parsed.path)
        anchor = unquote(parsed.fragment) or None
        if not path:
            return ("anchor", None, anchor) if anchor else ("ignored", None, None)
        if not os.path.isabs(path):
            path = os.path.join(os.path.dirname(os.path.abspath(doc_path)), path)
        path = os.path.normpath(path)
        if os.path.exists(path):
            return "local", path, anchor
        return "missing", unquote(parsed.path), anchor
    if scheme in EXTERNAL_SCHEMES or (scheme == "" and parsed.netloc):
        return "external", href if scheme else "https:" + href, None
    return "ignored", None, None
