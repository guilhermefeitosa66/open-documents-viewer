"""Opens a presentation by extension into odv.formats.slides.Presentation."""

import os


def open_presentation(path: str):
    ext = os.path.splitext(path)[1].lower()
    if ext in (".pptx", ".ppsx", ".potx", ".pptm", ".ppsm", ".potm"):
        from odv.formats import pptx

        return pptx.build(path)
    if ext in (".odp", ".otp", ".fodp"):
        from odv.formats import odp

        return odp.build(path)
    if ext in (".ppt", ".pps", ".pot"):
        from odv.formats import ppt

        return ppt.build(path)
    raise ValueError(f"unsupported presentation: {ext}")
