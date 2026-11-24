"""Reading positions of books, by path, in $XDG_STATE_HOME. Pure Python."""

import json
import os

MAX = 200


def _path() -> str:
    base = os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state")
    return os.path.join(base, "open-documents-viewer", "positions.json")


def _load() -> dict:
    try:
        with open(_path()) as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def get(path: str) -> int | None:
    value = _load().get(os.path.abspath(path))
    return value if isinstance(value, int) else None


def put(path: str, offset: int) -> None:
    data = _load()
    key = os.path.abspath(path)
    data.pop(key, None)
    data[key] = int(offset)
    while len(data) > MAX:
        data.pop(next(iter(data)))
    target = _path()
    try:
        os.makedirs(os.path.dirname(target), exist_ok=True)
        tmp = target + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f)
        os.replace(tmp, target)
    except OSError:
        pass
