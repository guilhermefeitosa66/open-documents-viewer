"""Actions on the open file: copy its path, show it in its folder, open it
with another app, save a copy, print, properties. Nothing here edits the file."""

import datetime
import os
import shutil
import stat
import threading

from gi.repository import Adw, Gdk, Gio, GLib, Gtk

from odv.i18n import _
from odv.textio import human_size
from odv.welcome import tilde


def copy_path(window, doc) -> None:
    Gdk.Display.get_default().get_clipboard().set(doc.path)
    window.toast(_("Path copied"), timeout=1)


def show_in_folder(window, doc) -> None:
    Gtk.FileLauncher.new(Gio.File.new_for_path(doc.path)).open_containing_folder(window, None, None, None)


def open_with(window, doc) -> None:
    launcher = Gtk.FileLauncher.new(Gio.File.new_for_path(doc.path))
    launcher.set_always_ask(True)
    launcher.launch(window, None, None, None)


def save_copy(window, doc, on_done=None) -> None:
    dialog = Gtk.FileDialog(title=_("Save a Copy"), modal=True, initial_name=doc.name)
    dialog.set_initial_folder(Gio.File.new_for_path(os.path.dirname(doc.path)))

    def chosen(dlg, result):
        try:
            target = dlg.save_finish(result)
        except GLib.Error:
            return
        if target and target.get_path():
            copy_to(window, doc.path, target.get_path(), on_done)

    dialog.save(window, None, chosen)


def copy_to(window, source: str, target: str, on_done=None) -> None:
    """Copy in a thread; the original is never touched."""
    def work():
        try:
            if os.path.abspath(source) == os.path.abspath(target):
                raise OSError(_("A copy cannot replace the original."))
            shutil.copyfile(source, target)
            message = _("Saved a copy to {path}").format(path=tilde(target))
            ok = True
        except OSError as e:
            message = _("Could not save the copy: {error}").format(error=e.strerror or str(e))
            ok = False
        GLib.idle_add(lambda: (window.toast(message), on_done and on_done(ok), False)[2])

    threading.Thread(target=work, daemon=True).start()


# -- printing -----------------------------------------------------------------

def printable_buffer(doc):
    """A GtkSourceBuffer to print: the source view's own buffer, or a copy
    of the formatted Markdown with its styles. None if nothing printable."""
    from odv.views.source import gtksource

    gs = gtksource()
    if not gs:
        return None, False
    view = doc.views.get(doc.mode)
    if doc.mode == "formatted" and doc.kind.rich and view is not None:
        src = view.buffer
        buf = gs.Buffer(tag_table=src.get_tag_table())
        buf.set_highlight_syntax(False)
        it = src.get_start_iter()
        # Copy text and styles; tables and rules (child widgets) become blank lines.
        buf.insert_range(buf.get_end_iter(), it, src.get_end_iter())
        return buf, False
    source = doc.views.get("source")
    if source is not None and isinstance(source.buffer, gs.Buffer):
        return source.buffer, True
    return None, False


def print_doc(window, doc, export_to: str | None = None):
    """Show the print dialog (or, for tests, export straight to a PDF)."""
    from odv.views.source import gtksource

    if doc.kind.kind == "pdf" or doc.mode == "faithful":
        return print_pdf(window, doc, export_to)
    if doc.mode == "formatted" and doc.kind.kind == "delimited":
        doc.set_mode("source")
    buf, is_code = printable_buffer(doc)
    if buf is None:
        window.toast(_("Open the file once in this view before printing."))
        return None
    gs = gtksource()
    comp = gs.PrintCompositor.new(buf)
    comp.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
    comp.set_print_line_numbers(1 if is_code else 0)
    comp.set_body_font_name("Monospace 9" if is_code else "Sans 10")
    comp.set_header_format(True, doc.name, None, None)
    comp.set_footer_format(True, None, _("Page %N of %Q"), None)
    comp.set_print_header(True)
    comp.set_print_footer(True)
    op = Gtk.PrintOperation(job_name=doc.name)

    def begin(operation, context):
        while not comp.paginate(context):
            pass
        operation.set_n_pages(comp.get_n_pages())

    op.connect("begin-print", begin)
    op.connect("draw-page", lambda operation, context, n: comp.draw_page(context, n))
    if export_to:
        op.set_export_filename(export_to)
        return op.run(Gtk.PrintOperationAction.EXPORT, window)
    return op.run(Gtk.PrintOperationAction.PRINT_DIALOG, window)


def print_pdf(window, doc, export_to: str | None = None):
    """Print a PDF's pages as images rendered at the printer's resolution."""
    import cairo

    from odv.formats.pdf import PDFIUM_LOCK, load

    view = doc.views.get("faithful" if doc.mode == "faithful" else "formatted")
    password = getattr(view, "password", None)
    if view is None or getattr(view, "info", None) is None:
        window.toast(_("Open the file once in this view before printing."))
        return None
    pdfium = load()
    with PDFIUM_LOCK:
        pdf = pdfium.PdfDocument(view.path, password=password)
    op = Gtk.PrintOperation(job_name=doc.name, n_pages=view.info.pages, use_full_page=False)

    def draw(_operation, context, n):
        width, height = context.get_width(), context.get_height()
        dpi = min(300.0, max(72.0, context.get_dpi_x() or 150.0))
        with PDFIUM_LOCK:
            page = pdf[n]
            pw, ph = page.get_size()
            fit = min(width / pw, height / ph)
            scale = fit * dpi / 72
            bitmap = page.render(scale=scale, may_draw_forms=False, prefer_bgrx=True)
            w, h, stride = bitmap.width, bitmap.height, bitmap.stride
            import ctypes

            data = bytearray(ctypes.string_at(ctypes.addressof(bitmap.buffer), stride * h))
            bitmap.close()
            page.close()
        surface = cairo.ImageSurface.create_for_data(data, cairo.FORMAT_RGB24, w, h, stride)
        cr = context.get_cairo_context()
        cr.save()
        cr.translate((width - pw * fit) / 2, (height - ph * fit) / 2)
        cr.scale(pw * fit / w, ph * fit / h)
        cr.set_source_surface(surface, 0, 0)
        cr.paint()
        cr.restore()

    def done(_operation, _result):
        with PDFIUM_LOCK:
            pdf.close()

    op.connect("draw-page", draw)
    op.connect("done", done)
    if export_to:
        op.set_export_filename(export_to)
        return op.run(Gtk.PrintOperationAction.EXPORT, window)
    return op.run(Gtk.PrintOperationAction.PRINT_DIALOG, window)


# -- encodings ------------------------------------------------------------------

def choose_encoding(window, doc) -> Adw.Dialog:
    """Reopen the document with an encoding picked from a list."""
    from odv.textio import ENCODINGS

    dialog = Adw.Dialog(title=_("Reopen with Encoding"), content_width=420, content_height=520)
    listbox = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
    listbox.add_css_class("boxed-list")
    current = getattr(getattr(doc, "_text", None), "encoding", None)
    for codec, label in ENCODINGS:
        row = Adw.ActionRow(title=label, activatable=True)
        if codec == current:
            row.add_suffix(Gtk.Image(icon_name="object-select-symbolic"))
        row._codec = codec
        listbox.append(row)

    def picked(_lb, row):
        dialog.close()
        pane = window.pane_of(doc)
        if pane is not None:
            page = pane.find_page(doc.path)
            pane.replace(page, doc.reload(keep_scroll=True, encoding=row._codec))

    listbox.connect("row-activated", picked)
    scroller = Gtk.ScrolledWindow(child=Adw.Clamp(child=listbox, margin_top=12, margin_bottom=12,
                                                  margin_start=12, margin_end=12), vexpand=True)
    toolbar = Adw.ToolbarView(content=scroller)
    toolbar.add_top_bar(Adw.HeaderBar())
    dialog.set_child(toolbar)
    dialog.present(window)
    return dialog


# -- properties -----------------------------------------------------------------

def properties(path: str, encoding: str | None = None, lines: int | None = None,
               endings: str | None = None) -> list[tuple[str, str]]:
    st = os.stat(path)
    ctype, _uncertain = Gio.content_type_guess(path, None)
    rows = [
        (_("Name"), os.path.basename(path)),
        (_("Folder"), tilde(os.path.dirname(path))),
        (_("Size"), f"{human_size(st.st_size)} ({st.st_size:,} bytes)".replace(",", ".")),
        (_("Type"), f"{Gio.content_type_get_description(ctype)} ({ctype})"),
        (_("Modified"), datetime.datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M:%S")),
        (_("Permissions"), stat.filemode(st.st_mode)),
    ]
    if encoding:
        rows.append((_("Encoding"), encoding))
    if lines is not None:
        rows.append((_("Lines"), f"{lines:,}".replace(",", ".")))
    if endings:
        rows.append((_("Line endings"), _("None (one line)") if endings == "None" else
                     _("Mixed") if endings == "Mixed" else endings))
    return rows


def show_properties(window, doc) -> Adw.Dialog:
    tf = getattr(doc, "_text", None)
    source = doc.views.get("source")
    lines = source.buffer.get_line_count() if source is not None and hasattr(source, "buffer") else None
    dialog = Adw.Dialog(title=_("Properties"), content_width=460)
    page = Adw.PreferencesPage()
    group = Adw.PreferencesGroup()
    for title, value in properties(doc.path, tf.encoding if tf else None, lines, tf.line_endings if tf else None):
        row = Adw.ActionRow(title=title, subtitle=GLib.markup_escape_text(value), subtitle_selectable=True)
        row.add_css_class("property")
        group.add(row)
    page.add(group)
    toolbar = Adw.ToolbarView(content=page)
    toolbar.add_top_bar(Adw.HeaderBar())
    dialog.set_child(toolbar)
    dialog.present(window)
    return dialog
