"""The faithful view: an office file converted to PDF by LibreOffice, when
it is installed, and shown with the PDF view.

The conversion runs headless with its own throw-away profile (so a
LibreOffice the user has open is not disturbed), in a temporary folder
that the caller removes when the tab closes. The process group is killed
if it runs too long. Nothing is sent anywhere: LibreOffice converts
locally."""

import os
import shutil
import signal
import subprocess
import tempfile

from odv.filetypes import FAITHFUL_EXT

TIMEOUT = 180  # seconds


class FaithfulError(Exception):
    pass


def supported(path: str) -> bool:
    return os.path.splitext(path)[1].lower() in FAITHFUL_EXT


def find_soffice() -> str | None:
    for name in ("soffice", "libreoffice"):
        found = shutil.which(name)
        if found:
            return found
    return None


def convert(path: str) -> tuple[str, str]:
    """(pdf path, temporary folder to remove afterwards)."""
    soffice = find_soffice()
    if soffice is None:
        raise FaithfulError("LibreOffice is not installed")
    folder = tempfile.mkdtemp(prefix="odv-faithful-")
    profile = os.path.join(folder, "profile")
    cmd = [soffice, f"-env:UserInstallation=file://{profile}", "--headless", "--invisible", "--nologo",
           "--norestore", "--nodefault", "--nolockcheck", "--convert-to", "pdf", "--outdir", folder, path]
    try:
        proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                start_new_session=True)
        try:
            _out, err = proc.communicate(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.communicate()
            raise FaithfulError("LibreOffice took too long to convert this file") from None
        finally:
            try:  # helpers LibreOffice may have left in its group
                os.killpg(proc.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
        pdf = os.path.join(folder, os.path.splitext(os.path.basename(path))[0] + ".pdf")
        if not os.path.isfile(pdf):
            message = (err or b"").decode("utf-8", "replace").strip().splitlines()
            raise FaithfulError(message[-1] if message else "LibreOffice could not convert this file")
        shutil.rmtree(profile, ignore_errors=True)
        return pdf, folder
    except BaseException:
        shutil.rmtree(folder, ignore_errors=True)
        raise
