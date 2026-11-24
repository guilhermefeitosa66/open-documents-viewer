"""User settings, kept locally in $XDG_CONFIG_HOME/open-documents-viewer.

``get``/``set`` work on one in-memory copy, loaded on first use; every
``set`` writes the whole file, so no module can drop another one's keys."""

import json
import os

DEFAULTS = {"theme": "libre-apps", "background": True, "watch": True}

_values: dict | None = None
_listeners: list = []


def _path() -> str:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "open-documents-viewer", "settings.json")


def load() -> dict:
    out = dict(DEFAULTS)
    try:
        with open(_path()) as f:
            data = json.load(f)
        if isinstance(data, dict):
            out.update({k: v for k, v in data.items() if k in DEFAULTS and isinstance(v, type(DEFAULTS[k]))})
    except (OSError, ValueError):
        pass
    return out


def save(values: dict) -> None:
    path = _path()
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            json.dump({k: values[k] for k in DEFAULTS if k in values}, f)
        os.replace(tmp, path)
    except OSError:
        pass


def _ensure() -> dict:
    global _values
    if _values is None:
        _values = load()
    return _values


def get(key: str):
    return _ensure()[key]


def set(key: str, value) -> None:  # noqa: A001 - mirrors get()
    values = _ensure()
    values[key] = value
    save(values)
    for cb in list(_listeners):
        cb(key, value)


def on_change(callback) -> None:
    _listeners.append(callback)


def reset_cache() -> None:
    """Forget the in-memory copy (tests change XDG_CONFIG_HOME)."""
    global _values
    _values = None
