"""Office word-processing documents: dispatch by extension."""

import os


def build(text, path: str = ""):
    ext = os.path.splitext(path)[1].lower()
    if ext in (".docx", ".docm", ".dotx", ".dotm"):
        from odv.formats import docx

        return docx.build(text, path)
    raise ValueError(f"unsupported document: {ext}")
