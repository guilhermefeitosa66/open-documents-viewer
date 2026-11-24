"""PDF through pypdfium2 (Apache-2.0 OR BSD-3-Clause; PDFium is BSD-3).
Imported only when a PDF opens.

PDFium is not thread-safe, so every call into it holds PDFIUM_LOCK, and a
document's work runs on its own worker thread (Engine); results come back
on the main loop. Coordinates handed to the view are in PDF points with
the origin at the page's top-left corner (y grows downwards), already
rotated like the rendering."""

import ctypes
import os
import queue
import sys
import threading
from dataclasses import dataclass, field

PDFIUM_LOCK = threading.RLock()
FPDF_ERR_PASSWORD = 4


def _vendor_path():
    """The .deb and the AppImage carry pypdfium2 next to the odv package."""
    here = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    vendor = os.path.join(here, "vendor")
    if os.path.isdir(vendor) and vendor not in sys.path:
        sys.path.append(vendor)


def load():
    """Import pypdfium2 or raise ImportError with a readable message."""
    _vendor_path()
    import pypdfium2

    return pypdfium2


class PasswordNeeded(Exception):
    pass


@dataclass
class Info:
    pages: int
    sizes: list  # [(width, height)] in points, as displayed (rotation applied)
    toc: list = field(default_factory=list)  # [(level, title, page index)]
    title: str = ""


class Engine:
    """One open PDF and the thread that works on it."""

    def __init__(self, path: str, post):
        """``post(callable)`` runs a callable on the main loop (GLib.idle_add)."""
        self.path = path
        self.post = post
        self.pdf = None
        self.pdfium = None
        self._pages = {}  # index -> (PdfPage, PdfTextPage | None), a few kept open
        self._order = []
        self._jobs = queue.Queue()
        self._closed = False
        self._render_gen = {}
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    # -- the worker -------------------------------------------------------------------
    def _run(self):
        while True:
            job = self._jobs.get()
            if job is None:
                break
            fn, done, failed = job
            try:
                with PDFIUM_LOCK:
                    result = fn()
            except Exception as e:  # noqa: BLE001 - surfaced in the UI
                if failed is not None:  # bind now: the loop reassigns these names
                    self.post(lambda f=failed, err=e: f(err))
                continue
            if done is not None:
                self.post(lambda d=done, r=result: d(r))
        with PDFIUM_LOCK:
            self._close_pages()
            if self.pdf is not None:
                self.pdf.close()
                self.pdf = None

    def submit(self, fn, done=None, failed=None):
        if not self._closed:
            self._jobs.put((fn, done, failed))

    def close(self):
        if not self._closed:
            self._closed = True
            self._jobs.put(None)

    def _close_pages(self):
        for page, text in self._pages.values():
            if text is not None:
                text.close()
            page.close()
        self._pages.clear()
        self._order.clear()

    def _page(self, index, with_text=False):
        entry = self._pages.get(index)
        if entry is None:
            entry = (self.pdf[index], None)
            self._pages[index] = entry
        if with_text and entry[1] is None:
            entry = (entry[0], entry[0].get_textpage())
            self._pages[index] = entry
        if index in self._order:
            self._order.remove(index)
        self._order.append(index)
        while len(self._order) > 8:
            old = self._order.pop(0)
            page, text = self._pages.pop(old)
            if text is not None:
                text.close()
            page.close()
        return entry

    # -- jobs (run on the worker, under the lock) -------------------------------------------
    def open(self, password: str | None = None) -> Info:
        pdfium = self.pdfium = load()
        try:
            self.pdf = pdfium.PdfDocument(self.path, password=password)
        except pdfium.PdfiumError as e:
            if getattr(e, "err_code", None) == FPDF_ERR_PASSWORD:
                raise PasswordNeeded(str(e)) from e
            raise
        n = len(self.pdf)
        sizes = []
        for i in range(n):
            if i < 300:  # loading the page gives the size as displayed (its /Rotate applied)
                page = self.pdf[i]
                sizes.append(tuple(page.get_size()))
                page.close()
            else:  # the view corrects a rotated page once it is rendered
                sizes.append(tuple(self.pdf.get_page_size(i)))
        toc = []
        try:
            for bm in self.pdf.get_toc(max_depth=8):
                dest = bm.get_dest()
                page = dest.get_index() if dest is not None else None
                title = (bm.get_title() or "").strip()
                if title and page is not None:
                    toc.append((getattr(bm, "level", 0) + 1, title, page))
        except Exception:  # noqa: BLE001 - a broken outline is not fatal
            pass
        title = ""
        try:
            title = (self.pdf.get_metadata_value("Title") or "").strip()
        except Exception:  # noqa: BLE001
            pass
        return Info(n, sizes, toc, title)

    def render(self, index: int, scale: float):
        """(width, height, stride, BGR bytes) of page ``index`` at ``scale`` px/pt."""
        page, _text = self._page(index)
        bitmap = page.render(scale=scale, may_draw_forms=False, draw_annots=True)
        try:
            w, h, stride = bitmap.width, bitmap.height, bitmap.stride
            data = bytes(ctypes.string_at(ctypes.addressof(bitmap.buffer), stride * h))
            n_channels = getattr(bitmap, "n_channels", 3)
            fmt = getattr(bitmap, "mode", "BGR")
        finally:
            bitmap.close()
        return w, h, stride, data, fmt, n_channels

    def text(self, index: int) -> str:
        _page, text = self._page(index, with_text=True)
        return text.get_text_range()

    def _to_page(self, page, x, y):
        """View point (points from the top-left) to PDF page coordinates."""
        raw = self.pdfium.raw
        w, h = page.get_size()
        rot = page.get_rotation() // 90 % 4
        dw, dh = (h, w) if rot in (1, 3) else (w, h)
        px, py = ctypes.c_double(), ctypes.c_double()
        raw.FPDF_DeviceToPage(page.raw, 0, 0, max(1, round(dw * 100)), max(1, round(dh * 100)), 0,
                              round(x * 100), round(y * 100), px, py)
        return px.value, py.value

    def _to_view(self, page, left, bottom, right, top):
        raw = self.pdfium.raw
        w, h = page.get_size()
        rot = page.get_rotation() // 90 % 4
        dw, dh = (h, w) if rot in (1, 3) else (w, h)
        sx, sy = max(1, round(dw * 100)), max(1, round(dh * 100))
        points = []
        for px, py in ((left, bottom), (right, top)):
            dx, dy = ctypes.c_int(), ctypes.c_int()
            raw.FPDF_PageToDevice(page.raw, 0, 0, sx, sy, 0, px, py, dx, dy)
            points.append((dx.value / 100, dy.value / 100))
        (x0, y0), (x1, y1) = points
        return min(x0, x1), min(y0, y1), abs(x1 - x0), abs(y1 - y0)

    def _rects(self, page, text, start, count):
        rects = []
        n = text.count_rects(start, count)
        for i in range(n):
            left, bottom, right, top = text.get_rect(i)
            rects.append(self._to_view(page, left, bottom, right, top))
        return rects

    def search(self, needle: str, start_page: int, forward: bool, after: int = -1):
        """The next match from ``start_page`` (inside it, after char ``after``),
        wrapping around: (page, char index, [rects]) or None."""
        n = len(self.pdf)
        order = [(start_page + (k if forward else -k)) % n for k in range(n + 1)]
        for step, index in enumerate(order):
            page, text = self._page(index, with_text=True)
            searcher = text.search(needle, match_case=False)
            hits = []
            try:
                while True:
                    hit = searcher.get_next()
                    if not hit:
                        break
                    hits.append(hit)
            finally:
                searcher.close()
            if step == 0:
                hits = [h for h in hits if (h[0] > after if forward else h[0] < after or after < 0)]
            if step == n:
                hits = [h for h in hits if (h[0] <= after if forward else True)]
            if hits:
                start, count = hits[0] if forward else hits[-1]
                return index, start, self._rects(page, text, start, count)
        return None

    def select(self, index: int, x0, y0, x1, y1):
        """Text between two view points on a page: (text, [rects])."""
        page, text = self._page(index, with_text=True)
        a = self._index(text, *self._to_page(page, x0, y0))
        b = self._index(text, *self._to_page(page, x1, y1))
        if a is None or b is None or a < 0 or b < 0:
            return "", []
        start, end = min(a, b), max(a, b)
        return text.get_text_range(start, end - start + 1), self._rects(page, text, start, end - start + 1)

    @staticmethod
    def _index(text, x, y):
        """The character nearest a point: widen the tolerance until one is found
        (a drag often ends past the end of a line)."""
        for tol in (6, 24, 72):
            index = text.get_index(x, y, tol, tol / 2)
            if index is not None and index >= 0:
                return index
        return None

    def link_at(self, index: int, x: float, y: float):
        """("page", n) or ("uri", address) for a link under a view point."""
        raw = self.pdfium.raw
        page, _text = self._page(index)
        px, py = self._to_page(page, x, y)
        link = raw.FPDFLink_GetLinkAtPoint(page.raw, px, py)
        if not link:
            return None
        dest = raw.FPDFLink_GetDest(self.pdf.raw, link)
        action = raw.FPDFLink_GetAction(link)
        if not dest and action:
            kind = raw.FPDFAction_GetType(action)
            if kind == raw.PDFACTION_URI:
                size = raw.FPDFAction_GetURIPath(self.pdf.raw, action, None, 0)
                buf = ctypes.create_string_buffer(size)
                raw.FPDFAction_GetURIPath(self.pdf.raw, action, buf, size)
                return "uri", buf.value.decode("utf-8", "replace")
            if kind == raw.PDFACTION_GOTO:
                dest = raw.FPDFAction_GetDest(self.pdf.raw, action)
        if dest:
            target = raw.FPDFDest_GetDestPageIndex(self.pdf.raw, dest)
            if target >= 0:
                return "page", target
        return None


def extract_text(path: str, password: str | None = None, limit: int = 64 * 1024 * 1024) -> str:
    """All the text, page after page (for the source view), in the caller's thread."""
    pdfium = load()
    parts = []
    size = 0
    with PDFIUM_LOCK:
        pdf = pdfium.PdfDocument(path, password=password)
        try:
            for i in range(len(pdf)):
                page = pdf[i]
                text = page.get_textpage()
                chunk = text.get_text_range().replace("\r\n", "\n")
                text.close()
                page.close()
                parts.append(f"── {i + 1} ──\n{chunk.rstrip()}\n")
                size += len(chunk)
                if size > limit:
                    break
        finally:
            pdf.close()
    return "\n".join(parts)
