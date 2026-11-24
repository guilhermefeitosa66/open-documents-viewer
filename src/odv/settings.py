"""User settings, kept locally in $XDG_CONFIG_HOME/open-documents-viewer."""

import json
import os

DEFAULTS = {"theme": "libre-apps", "background": True}


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
