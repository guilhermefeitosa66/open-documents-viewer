"""Opening time per file family, on generated samples (big Markdown, big
code, DOCX, a large XLSX, PPTX, a 200-page PDF).

    python3 tools/bench_families.py [-n 5] [--keep DIR]

Each sample is opened alone, n times; the medians of the first content
and of the fully loaded document are printed, plus the start-up time.
Runs under Xvfb in the test container (make bench-files)."""

import argparse
import os
import shlex
import statistics
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
sys.path[:0] = [os.path.join(ROOT, "tests"), os.path.join(ROOT, "src")]


def make_samples(folder):
    import office
    from pdfgen import make_pdf

    samples = {}
    md = os.path.join(folder, "big.md")
    with open(md, "w") as f:
        for i in range(4000):
            f.write(f"## Section {i}\n\nSome **bold** text, `code`, a [link](#x) and a list:\n\n- one\n- two\n\n")
            if i % 20 == 0:  # tables are child widgets: a table in every section is a stress test, not a document
                f.write("| a | b |\n|---|---|\n| 1 | 2 |\n\n")
    samples["markdown"] = md
    code = os.path.join(folder, "big.py")
    with open(code, "w") as f:
        for i in range(30000):
            f.write(f"def function_{i}(value: int) -> int:\n    return value * {i}  # comment\n\n")
    samples["code"] = code
    docx = os.path.join(folder, "long.docx")
    office.make_docx(docx)
    with zipfile.ZipFile(docx) as z:
        parts = {n: z.read(n) for n in z.namelist()}
    body = parts["word/document.xml"].decode()
    para = "".join(f"<w:p><w:r><w:t>Paragraph {i} of a long report, with enough words to wrap.</w:t></w:r></w:p>"
                   for i in range(20000))
    parts["word/document.xml"] = body.replace("<w:body>", "<w:body>" + para, 1).encode()
    with zipfile.ZipFile(docx, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in parts.items():
            z.writestr(name, data)
    samples["docx"] = docx
    samples["xlsx"] = str(office.make_xlsx(os.path.join(folder, "big.xlsx"), extra_rows=100000))
    samples["pptx"] = str(office.make_pptx(os.path.join(folder, "deck.pptx")))
    page = [(72, 720, 20, "Chapter"), (72, 690, 11, "Lorem ipsum dolor sit amet, consectetur adipiscing elit.")]
    samples["pdf"] = str(make_pdf(os.path.join(folder, "long.pdf"), pages=[page] * 200))
    return samples


def run(cmd, files, n, env):
    values = {}
    for _ in range(n):
        out = subprocess.run(cmd + ["--bench", *files], env=env, capture_output=True, text=True, timeout=180).stdout
        for line in out.splitlines():
            parts = line.split()
            if parts and parts[0] == "odv-bench":
                fields = dict(p.split("=", 1) for p in parts[2:])
                for key, value in fields.items():
                    if key.endswith("_ms"):
                        values.setdefault(f"{parts[1]} {key}", []).append(float(value))
    return {k: statistics.median(v) for k, v in values.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-n", type=int, default=5)
    ap.add_argument("--keep")
    args = ap.parse_args()
    env = dict(os.environ, PYTHONPATH=os.path.join(ROOT, "src"), GTK_A11Y="none")
    cmd = shlex.split(f"{sys.executable} -m odv")
    folder = args.keep or tempfile.mkdtemp(prefix="odv-bench-")
    os.makedirs(folder, exist_ok=True)
    samples = make_samples(folder)
    start = run(cmd, [], args.n, env)
    print(f"{'start-up (no file)':28} first frame {start.get('startup main_to_frame_ms', 0):7.1f} ms")
    for family, path in samples.items():
        r = run(cmd, [path], args.n, env)
        size = os.path.getsize(path) / 1e6
        print(f"{family + f' ({size:.1f} MB)':28} first content {r.get('open first_content_ms', 0):7.1f} ms"
              f"   loaded {r.get('loaded loaded_ms', 0):7.1f} ms")


if __name__ == "__main__":
    main()
