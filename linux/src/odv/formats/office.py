"""Office word-processing documents: dispatch by extension."""

import os


def build(text, path: str = ""):
    ext = os.path.splitext(path)[1].lower()
    if ext in (".docx", ".docm", ".dotx", ".dotm"):
        from odv.formats import docx

        return docx.build(text, path)
    if ext in (".doc", ".dot"):
        from odv.formats import doc

        return doc.build(text, path)
    if ext in (".odt", ".ott", ".fodt"):
        from odv.formats import odt

        return odt.build(text, path)
    if ext in (".pages", ".numbers", ".key"):
        from odv.formats import iwork

        return iwork.build(text, path)
    raise ValueError(f"unsupported document: {ext}")
