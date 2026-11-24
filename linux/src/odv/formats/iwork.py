"""Apple iWork files (.pages, .numbers, .key): their content is in a
private format, so only the preview picture saved inside the package is
shown, with a note saying so."""

import zipfile

from odv.i18n import _
from odv.mdrender import Doc

PREVIEWS = ("preview.jpg", "QuickLook/Preview.jpg", "QuickLook/Thumbnail.jpg", "preview-web.jpg",
            "QuickLook/Thumbnail.png", "preview-micro.jpg")
MAX_PREVIEW = 32 * 1024 * 1024


class IworkError(Exception):
    pass


def build(text, path: str = "") -> Doc:
    try:
        zf = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as e:
        raise IworkError(str(e)) from e
    doc = Doc()
    with zf:
        names = set(zf.namelist())
        picture = None
        for name in PREVIEWS:
            if name in names and zf.getinfo(name).file_size <= MAX_PREVIEW:
                picture = zf.read(name)
                break
        has_pdf = "QuickLook/Preview.pdf" in names
    if picture is None:
        message = _("This iWork file has no preview picture inside it.")
        if has_pdf:
            message = _("This iWork file only has a PDF preview inside it.")
        doc.ops.append(("text", message + "\n", ("p", "dim")))
        doc.length += len(message) + 1
    else:
        doc.ops.append(("image", picture))
        doc.ops.append(("text", "\n", ()))
        doc.length += 2
    doc.note = _("Apple iWork content cannot be read: this is the preview picture saved inside the file.")
    return doc
