"""Recently opened files and folders, kept locally under $XDG_STATE_HOME."""

import json
import os
import time
from odv.i18n import _

MAX = 12


def _path() -> str:
    base = os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state")
    return os.path.join(base, "open-documents-viewer", "recent.json")


def load() -> list[dict]:
    try:
        with open(_path()) as f:
            items = json.load(f)
        return [i for i in items if isinstance(i, dict) and "path" in i][:MAX]
    except (OSError, ValueError):
        return []


def _save(items: list[dict]) -> None:
    path = _path()
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(items[:MAX], f)
        os.replace(tmp, path)
    except OSError:
        pass


def add(path: str) -> None:
    path = os.path.abspath(path)
    items = [i for i in load() if i["path"] != path]
    items.insert(0, {"path": path, "dir": os.path.isdir(path), "at": time.time()})
    _save(items)


def clear() -> None:
    _save([])


def when(ts: float, now: float | None = None) -> str:
    now = time.time() if now is None else now
    days = int((now - ts) // 86400)
    if days <= 0:
        return _("Today")
    if days == 1:
        return _("Yesterday")
    if days < 30:
        return _("{n} days ago").format(n=days)
    return time.strftime("%Y-%m-%d", time.localtime(ts))
