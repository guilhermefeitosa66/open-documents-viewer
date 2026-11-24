"""A small PDF written by hand for the tests: three pages (the third
rotated), text in Helvetica, an outline, an internal and a web link."""


def _stream(text_ops: str) -> bytes:
    data = text_ops.encode("latin-1")
    return b"<< /Length %d >>\nstream\n" % len(data) + data + b"\nendstream"


def make_pdf(path, pages=None):
    pages = pages or [
        [(72, 720, 28, "Quarterly Review"), (72, 680, 14, "Hello and welcome."),
         (72, 640, 14, "See the results page."), (72, 600, 14, "Visit the site.")],
        [(72, 720, 28, "Results"), (72, 680, 14, "North 42"), (72, 660, 14, "South 17")],
        [(72, 500, 20, "Backup slide")],
    ]
    n = len(pages)
    # Object numbers: 1 catalog, 2 pages, 3 font, 4 outlines, 5-7 outline items,
    # then per page: page, content (and the first page's two link annotations).
    objs = {}
    first_page = 8
    page_ids = [first_page + 2 * i for i in range(n)]
    annot_ids = [first_page + 2 * n, first_page + 2 * n + 1]
    objs[1] = b"<< /Type /Catalog /Pages 2 0 R /Outlines 4 0 R /PageMode /UseOutlines >>"
    objs[2] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (b" ".join(b"%d 0 R" % p for p in page_ids), n)
    objs[3] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    objs[4] = b"<< /Type /Outlines /First 5 0 R /Last 6 0 R /Count 3 >>"
    objs[5] = b"<< /Title (Introduction) /Parent 4 0 R /Next 6 0 R /Dest [%d 0 R /XYZ 0 792 0] >>" % page_ids[0]
    objs[6] = (b"<< /Title (Results) /Parent 4 0 R /Prev 5 0 R /First 7 0 R /Last 7 0 R /Count 1 "
               b"/Dest [%d 0 R /XYZ 0 792 0] >>" % page_ids[min(1, n - 1)])
    objs[7] = b"<< /Title (Backup) /Parent 6 0 R /Dest [%d 0 R /XYZ 0 792 0] >>" % page_ids[-1]
    for i, lines in enumerate(pages):
        ops = "BT\n" + "".join(f"/F1 {size} Tf 1 0 0 1 {x} {y} Tm ({text}) Tj\n" for x, y, size, text in lines) + "ET"
        rotate = b" /Rotate 90" if i == 2 else b""
        annots = b" /Annots [%d 0 R %d 0 R]" % tuple(annot_ids) if i == 0 else b""
        objs[page_ids[i]] = (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >>"
                             b" /Contents %d 0 R%s%s >>" % (page_ids[i] + 1, rotate, annots))
        objs[page_ids[i] + 1] = _stream(ops)
    objs[annot_ids[0]] = (b"<< /Type /Annot /Subtype /Link /Rect [72 635 250 655] /Border [0 0 0] "
                          b"/Dest [%d 0 R /XYZ 0 792 0] >>" % page_ids[min(1, n - 1)])
    objs[annot_ids[1]] = (b"<< /Type /Annot /Subtype /Link /Rect [72 595 250 615] /Border [0 0 0] "
                          b"/A << /S /URI /URI (https://libre-apps.org/) >> >>")
    out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    for num in sorted(objs):
        offsets[num] = len(out)
        out += b"%d 0 obj\n" % num + objs[num] + b"\nendobj\n"
    xref = len(out)
    size = max(objs) + 1
    out += b"xref\n0 %d\n0000000000 65535 f \n" % size
    for num in range(1, size):
        out += b"%010d 00000 n \n" % offsets[num] if num in offsets else b"0000000000 65535 f \n"
    out += b"trailer\n<< /Size %d /Root 1 0 R /Info << /Title (Sample report) >> >>\nstartxref\n%d\n%%%%EOF\n" % (size, xref)
    with open(path, "wb") as f:
        f.write(bytes(out))
    return path


def fake_soffice(folder, fail=False):
    """A stand-in for LibreOffice in ``folder`` (put it first in PATH): it
    "converts" any file to the sample PDF, or fails without output."""
    import os
    import sys

    os.makedirs(folder, exist_ok=True)
    script = os.path.join(folder, "soffice")
    tests = os.path.dirname(os.path.abspath(__file__))
    body = ("import sys\nsys.stderr.write('Error: source file could not be loaded\\n')\nsys.exit(0)\n" if fail else
            f"import os, sys\nsys.path.insert(0, {tests!r})\nfrom pdfgen import make_pdf\n"
            "args = sys.argv[1:]\nout = args[args.index('--outdir') + 1]\nsrc = args[-1]\n"
            "assert any(a.startswith('-env:UserInstallation=file://') for a in args)\n"
            "make_pdf(os.path.join(out, os.path.splitext(os.path.basename(src))[0] + '.pdf'))\n")
    with open(script, "w") as f:
        f.write(f"#!{sys.executable}\n{body}")
    os.chmod(script, 0o755)
    return folder
