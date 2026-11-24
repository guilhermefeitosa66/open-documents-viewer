"""Password-protected and macro-enabled office files. Pure Python and cheap
to import; msoffcrypto-tool (MIT) is imported only to unlock a file.

An encrypted OOXML file (.docx, .xlsx, .pptx…) is a compound file holding
the real package encrypted. Once the right password is given it is
decrypted into memory (never written to disk) and the readers open it
through ``source(path)``. Encrypted OpenDocument files are explained, not
opened. Macros are never read or run; macro-enabled files get a note."""

import io
import os

from odv.i18n import _

OOXML_EXT = {".docx", ".docm", ".dotx", ".dotm", ".xlsx", ".xlsm", ".xltx", ".xltm",
             ".pptx", ".pptm", ".ppsx", ".ppsm", ".potx", ".potm"}
MACRO_EXT = {".docm", ".dotm", ".xlsm", ".xltm", ".pptm", ".ppsm", ".potm"}
OLE_MAGIC = bytes.fromhex("D0CF11E0A1B11AE1")
MAX_DECRYPTED = 512 * 1024 * 1024

_unlocked: dict[str, tuple[tuple[int, int], bytes]] = {}  # path -> ((mtime_ns, size), package)


class WrongPassword(Exception):
    pass


def _stamp(path):
    st = os.stat(path)
    return st.st_mtime_ns, st.st_size


def macro_note(path: str) -> str | None:
    if os.path.splitext(path)[1].lower() in MACRO_EXT:
        return _("This file contains macros. They are never run.")
    return None


def is_encrypted_ooxml(path: str) -> bool:
    if os.path.splitext(path)[1].lower() not in OOXML_EXT:
        return False
    try:
        with open(path, "rb") as f:
            return f.read(8) == OLE_MAGIC
    except OSError:
        return False


def needs_password(path: str) -> bool:
    if not is_encrypted_ooxml(path):
        return False
    entry = _unlocked.get(path)
    try:
        return entry is None or entry[0] != _stamp(path)
    except OSError:
        return True


def source(path: str):
    """What zipfile should open: the decrypted package, or the path."""
    entry = _unlocked.get(path)
    if entry is not None:
        try:
            if entry[0] == _stamp(path):
                return io.BytesIO(entry[1])
        except OSError:
            pass
        del _unlocked[path]
    return path


def unlock(path: str, password: str) -> None:
    """Decrypt ``path`` into memory. Raises WrongPassword, or RuntimeError
    when msoffcrypto-tool is missing or the encryption is not supported."""
    try:
        import msoffcrypto
        from msoffcrypto import exceptions
    except ImportError as e:
        raise RuntimeError(_("Opening password-protected files needs msoffcrypto-tool "
                             "(the python3-msoffcrypto-tool package).")) from e
    stamp = _stamp(path)
    out = io.BytesIO()
    with open(path, "rb") as f:
        try:
            office = msoffcrypto.OfficeFile(f)
            office.load_key(password=password, verify_password=True)
            office.decrypt(out)
        except exceptions.InvalidKeyError as e:
            raise WrongPassword(_("Wrong password.")) from e
        except (exceptions.DecryptionError, exceptions.FileFormatError) as e:
            if "password" in str(e).lower() or "key" in str(e).lower():
                raise WrongPassword(_("Wrong password.")) from e
            raise RuntimeError(str(e)) from e
    data = out.getvalue()
    if len(data) > MAX_DECRYPTED:
        raise RuntimeError(_("This file is too big to unlock in memory."))
    _unlocked[path] = (stamp, data)


def odf_encrypted(zf) -> bool:
    """True when an OpenDocument package's parts are encrypted."""
    try:
        manifest = zf.read("META-INF/manifest.xml")
    except KeyError:
        return False
    return b"encryption-data" in manifest


ODF_ENCRYPTED = "This file is password-protected. Encrypted OpenDocument files cannot be opened yet."
