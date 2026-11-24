"""Text search across a folder. Pure Python, meant for a worker thread.

Which files: inside a git work tree, ``git ls-files`` (tracked and untracked,
minus what .gitignore excludes: the same set git itself sees). Elsewhere, a
walk that skips VCS folders and applies the patterns of the folder's own
.gitignore with fnmatch (a subset of git's rules, enough for common cases).
Binary files and files above MAX_FILE are skipped.
"""

import fnmatch
import os
import subprocess
from dataclasses import dataclass, field

from odv.textio import BINARY_SNIFF, decode, looks_binary

MAX_FILE = 8 * 1024 * 1024
MAX_PER_FILE = 200
MAX_TOTAL = 5000
SNIPPET = 160
ALWAYS_SKIP = {".git", ".hg", ".svn"}


@dataclass
class Hit:
    line: int  # 1-based
    text: str  # the line, trimmed around the match
    column: int  # 0-based, in ``text``


@dataclass
class FileHits:
    path: str
    hits: list[Hit] = field(default_factory=list)


class Cancelled(Exception):
    pass


def _git_files(root: str) -> list[str] | None:
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             capture_output=True, timeout=20, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    files = []
    for rel in out.decode("utf-8", "replace").split("\0"):
        if rel:
            path = os.path.join(root, rel)
            if os.path.isfile(path):
                files.append(path)
    return files


def _gitignore_patterns(root: str) -> list[str]:
    try:
        with open(os.path.join(root, ".gitignore"), encoding="utf-8", errors="replace") as f:
            lines = f.read().splitlines()
    except OSError:
        return []
    return [ln.strip() for ln in lines if ln.strip() and not ln.startswith("#") and not ln.startswith("!")]


def _ignored(rel: str, is_dir: bool, patterns: list[str]) -> bool:
    name = os.path.basename(rel)
    for p in patterns:
        dir_only = p.endswith("/")
        p = p.rstrip("/")
        if dir_only and not is_dir:
            continue
        anchored = p.startswith("/") or "/" in p
        p = p.lstrip("/")
        if fnmatch.fnmatch(rel if anchored else name, p):
            return True
    return False


def _walk_files(root: str, cancelled) -> list[str]:
    patterns = _gitignore_patterns(root)
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        if cancelled():
            raise Cancelled
        rel_dir = os.path.relpath(dirpath, root)
        rel_dir = "" if rel_dir == "." else rel_dir
        dirnames[:] = sorted(d for d in dirnames if d not in ALWAYS_SKIP
                             and not _ignored(os.path.join(rel_dir, d), True, patterns))
        for name in sorted(filenames):
            if not _ignored(os.path.join(rel_dir, name), False, patterns):
                files.append(os.path.join(dirpath, name))
    return files


def list_files(root: str, cancelled=lambda: False) -> list[str]:
    files = None
    if os.path.isdir(os.path.join(root, ".git")) or os.path.isfile(os.path.join(root, ".git")):
        files = _git_files(root)
    if files is None:
        files = _walk_files(root, cancelled)
    return sorted(files, key=lambda p: os.path.relpath(p, root).casefold())


def _snippet(line: str, col: int, length: int) -> tuple[str, int]:
    line = line.rstrip("\r\n").replace("\t", "    ")
    if len(line) <= SNIPPET:
        stripped = line.lstrip()
        return stripped, max(0, col - (len(line) - len(stripped)))
    start = max(0, col - SNIPPET // 3)
    piece = line[start:start + SNIPPET]
    return ("…" if start else "") + piece, col - start + (1 if start else 0)


def search_file(path: str, needle_folded: str, needle_len: int) -> list[Hit]:
    try:
        if os.path.getsize(path) > MAX_FILE:
            return []
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        return []
    if looks_binary(data[:BINARY_SNIFF]):
        return []
    text, _enc = decode(data)
    folded = text.casefold()
    if needle_folded not in folded:
        return []
    hits = []
    for number, (line, line_folded) in enumerate(zip(text.split("\n"), folded.split("\n")), 1):
        col = line_folded.find(needle_folded)
        if col >= 0:
            snippet, snip_col = _snippet(line, col, needle_len)
            hits.append(Hit(number, snippet, snip_col))
            if len(hits) >= MAX_PER_FILE:
                break
    return hits


def search(root: str, needle: str, on_file=None, cancelled=lambda: False) -> tuple[list[FileHits], bool]:
    """Search ``needle`` (case-insensitive) in the files under ``root``.

    Calls ``on_file(FileHits)`` for each file with hits as it goes. Returns
    (results, truncated)."""
    needle_folded = needle.casefold()
    results: list[FileHits] = []
    total = 0
    if not needle_folded:
        return results, False
    for path in list_files(root, cancelled):
        if cancelled():
            raise Cancelled
        hits = search_file(path, needle_folded, len(needle))
        if hits:
            fh = FileHits(path, hits)
            results.append(fh)
            total += len(hits)
            if on_file:
                on_file(fh)
            if total >= MAX_TOTAL:
                return results, True
    return results, False
