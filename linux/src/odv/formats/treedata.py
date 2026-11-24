"""Structured data as a tree: JSON, NDJSON, YAML and XML. Pure Python.

Every format becomes plain Python values (dict, list, scalars) so the tree
view, node paths and search work the same way. XML elements become dicts:
"@name" for attributes, "#text" for text, child tags as keys (a list when
a tag repeats).
"""

import json


class ParseError(Exception):
    def __init__(self, message: str, line: int | None = None):
        super().__init__(message)
        self.line = line


def parse(kind: str, text: str):
    if kind == "json":
        try:
            return json.loads(text)
        except json.JSONDecodeError as e:
            raise ParseError(e.msg, e.lineno) from e
    if kind == "ndjson":
        records = []
        for number, line in enumerate(text.splitlines(), 1):
            if line.strip():
                try:
                    records.append(json.loads(line))
                except json.JSONDecodeError as e:
                    raise ParseError(e.msg, number) from e
        return records
    if kind == "yaml":
        try:
            import yaml
        except ImportError as e:
            raise ParseError("PyYAML is not installed") from e
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError as e:
            mark = getattr(e, "problem_mark", None)
            raise ParseError(str(getattr(e, "problem", None) or e), mark.line + 1 if mark else None) from e
        return docs[0] if len(docs) == 1 else docs
    if kind == "xml":
        import xml.etree.ElementTree as ET

        try:
            root = ET.fromstring(text.encode("utf-8"))
        except ET.ParseError as e:
            raise ParseError(str(e), e.position[0] if e.position else None) from e
        return {_tag(root.tag): _element(root)}
    raise ParseError(f"unknown kind {kind}")


def _tag(tag: str) -> str:
    return tag.split("}", 1)[1] if tag.startswith("{") else tag


def _element(el):
    node = {f"@{_tag(k)}": v for k, v in el.attrib.items()}
    for child in el:
        key = _tag(child.tag) if isinstance(child.tag, str) else "#comment"
        value = _element(child)
        if key in node:
            if not isinstance(node[key], list) or not getattr(node[key], "_repeated", False):
                node[key] = _Repeated([node[key]])
            node[key].append(value)
        else:
            node[key] = value
    text = (el.text or "").strip()
    if text:
        if not node:
            return text
        node["#text"] = text
    return node or ""


class _Repeated(list):
    _repeated = True


def children(value) -> list[tuple[object, object]]:
    if isinstance(value, dict):
        return list(value.items())
    if isinstance(value, list):
        return list(enumerate(value))
    return []


def is_container(value) -> bool:
    return isinstance(value, (dict, list)) and len(value) > 0


def preview(value, limit: int = 120) -> str:
    if isinstance(value, dict):
        return "{" + str(len(value)) + "}"
    if isinstance(value, list):
        return "[" + str(len(value)) + "]"
    if isinstance(value, str):
        s = value.replace("\n", "⏎")
        return json.dumps(s[:limit] + ("…" if len(s) > limit else ""), ensure_ascii=False)
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def type_class(value) -> str:
    if isinstance(value, bool) or value is None:
        return "constant"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    return "container"


def path_string(keys: list, kind: str) -> str:
    """$.a.b[2] for JSON/YAML, /a/b[3] (1-based) for XML."""
    if kind == "xml":
        out = ""
        for key in keys:
            out += f"[{key + 1}]" if isinstance(key, int) else "/" + str(key)
        return out or "/"
    out = "$"
    for key in keys:
        if isinstance(key, int):
            out += f"[{key}]"
        elif isinstance(key, str) and key.isidentifier():
            out += "." + key
        else:
            out += "[" + json.dumps(key, ensure_ascii=False) + "]"
    return out


def search(value, needle: str, limit: int = 1000) -> list[list]:
    """Paths (lists of keys) of the nodes whose key or scalar value contains
    ``needle`` (case-insensitive), in document order."""
    needle = needle.casefold()
    found: list[list] = []
    stack = [([], value)]
    while stack and len(found) < limit:
        keys, node = stack.pop()
        items = children(node)
        for key, child in reversed(items):
            stack.append((keys + [key], child))
        if keys:
            key = keys[-1]
            text = "" if isinstance(node, (dict, list)) else preview(node, 10**6)
            if (isinstance(key, str) and needle in key.casefold()) or needle in text.casefold():
                found.append(keys)
    return found
