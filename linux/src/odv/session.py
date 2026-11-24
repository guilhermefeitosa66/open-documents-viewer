"""The last session: folder, panes, tabs, views and scroll positions.
Kept in $XDG_STATE_HOME/open-documents-viewer/session.json. Pure Python."""

import json
import os


def _path() -> str:
    base = os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state")
    return os.path.join(base, "open-documents-viewer", "session.json")


def save(state: dict) -> None:
    path = _path()
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(state, f)
        os.replace(tmp, path)
    except OSError:
        pass


def load() -> dict | None:
    """The saved session, with files and the folder that no longer exist
    dropped. None when there is nothing to restore."""
    try:
        with open(_path()) as f:
            state = json.load(f)
    except (OSError, ValueError):
        return None
    if not isinstance(state, dict):
        return None
    folder = state.get("folder")
    if folder and not os.path.isdir(folder):
        state["folder"] = None
    panes = []
    for pane in state.get("panes") or []:
        tabs = [t for t in pane.get("tabs") or [] if isinstance(t, dict) and os.path.isfile(t.get("path", ""))]
        if tabs:
            selected = min(max(0, int(pane.get("selected", 0))), len(tabs) - 1)
            panes.append({"tabs": tabs, "selected": selected})
    state["panes"] = panes[:2]
    if not state["panes"] and not state.get("folder"):
        return None
    return state


def clear() -> None:
    try:
        os.remove(_path())
    except OSError:
        pass
