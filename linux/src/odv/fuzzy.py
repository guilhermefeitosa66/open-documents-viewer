"""Fuzzy matching for quick open and the command palette. Pure Python.

The query's characters must appear in order (spaces are ignored). Matches
score higher when they are consecutive, start a word or path segment, or
fall in the file name rather than the folders; shorter candidates win ties.
"""

BOUNDARY = "/\\_-. :"


def score(query: str, candidate: str) -> float | None:
    q = query.replace(" ", "").lower()
    if not q:
        return 0.0
    low = candidate.lower()
    name_start = max(candidate.rfind("/"), candidate.rfind("\\")) + 1
    total, pos, prev = 0.0, 0, -2
    for ch in q:
        idx = low.find(ch, pos)
        if idx < 0:
            return None
        # Prefer the same letter where it starts a word, if it comes soon.
        for j in range(idx, min(len(low), idx + 12)):
            if low[j] == ch and (j == 0 or candidate[j - 1] in BOUNDARY
                                 or (candidate[j].isupper() and candidate[j - 1].islower())):
                if j != idx and prev + 1 != idx:
                    idx = j
                break
        bonus = 1.0
        if idx == prev + 1:
            bonus += 5
        if idx == 0 or candidate[idx - 1] in BOUNDARY:
            bonus += 4
        elif candidate[idx].isupper() and candidate[idx - 1].islower():
            bonus += 3
        if idx >= name_start:
            bonus += 1.5
        total += bonus
        prev, pos = idx, idx + 1
    name = low[name_start:]
    if q in name:
        total += 8 + (6 if name.startswith(q) else 0)
    return total - len(candidate) * 0.02


def rank(query: str, items: list, key=lambda x: x, limit: int = 50) -> list:
    """The ``limit`` best items for ``query``, best first."""
    scored = []
    for item in items:
        s = score(query, key(item))
        if s is not None:
            scored.append((s, item))
    scored.sort(key=lambda t: -t[0])
    return [item for _, item in scored[:limit]]
