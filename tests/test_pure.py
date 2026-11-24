"""Tests that need no display: classification, decoding, Markdown, CSV."""

import os
import time

import pytest

from odv import filetypes, mdrender, recents, textio
from odv.csvindex import CsvIndex, detect_numeric

FIX = os.path.join(os.path.dirname(__file__), "fixtures")


@pytest.mark.parametrize("name,kind,lang", [
    ("README.md", "markdown", "markdown"),
    ("data.csv", "delimited", None),
    ("data.tsv", "delimited", None),
    (".env", "text", "sh"),
    (".env.local", "text", "sh"),
    ("prod.env", "text", "sh"),
    ("Dockerfile", "text", "docker"),
    ("main.py", "text", None),
    ("app.conf", "text", "ini"),
])
def test_classify(name, kind, lang):
    k = filetypes.classify(name)
    assert (k.kind, k.lang) == (kind, lang)
    assert k.has_formatted == (kind in ("markdown", "delimited"))


def test_tsv_delimiter_and_labels():
    assert filetypes.classify("a.tsv").delimiter == "\t"
    assert filetypes.classify("a.csv").formatted_label == "Table"
    assert filetypes.classify("a.md").formatted_label == "Formatted"


def test_sidebar_classes():
    assert filetypes.sidebar_class("a.md") == "markdown"
    assert filetypes.sidebar_class("a.py") == "code"
    assert filetypes.sidebar_class("a.yaml") == "config"
    assert filetypes.sidebar_class(".env") == "config"
    assert filetypes.sidebar_class("a.csv") == "data"
    assert filetypes.sidebar_class("LICENSE") == "text"


def test_decode():
    assert textio.decode("olá".encode()) == ("olá", "utf-8")
    assert textio.decode("olá".encode("cp1252")) == ("olá", "windows-1252")
    assert textio.decode("﻿hi".encode("utf-8")) == ("hi", "utf-8")
    assert textio.decode("hi".encode("utf-16")) == ("hi", "utf-16")
    # A read cut inside a multi-byte character is still UTF-8.
    assert textio.decode("aé".encode()[:-1], truncated=True) == ("a", "utf-8")


def test_binary_detection(tmp_path):
    assert textio.looks_binary(b"abc\x00def")
    assert not textio.looks_binary("hi".encode("utf-16"))
    with pytest.raises(textio.BinaryFile):
        textio.read_text(os.path.join(FIX, "binary.png"))


def test_read_text_truncates(tmp_path):
    p = tmp_path / "big.txt"
    p.write_text("line\n" * 1000)
    tf = textio.read_text(str(p), limit=100)
    assert tf.truncated and tf.size == 5000 and len(tf.text) == 100


def test_split_chunks_keeps_text():
    text = "".join(f"line {i}\n" for i in range(100000))
    chunks = textio.split_chunks(text, 4096)
    assert "".join(chunks) == text
    assert all(c.endswith("\n") for c in chunks)


def test_human_size():
    assert textio.human_size(10) == "10 bytes"
    assert textio.human_size(1536) == "1.5 KB"
    assert textio.human_size(184 * 1024 * 1024) == "184 MB"


def test_markdown_ops():
    doc = mdrender.build(open(os.path.join(FIX, "sample.md")).read())
    text = "".join(op[1] for op in doc.ops if op[0] == "text")
    assert "Sample document\n" in text
    assert "**" not in text and "`inline code`" not in text
    assert "☑ done task" in text
    assert "def hello():" in text
    kinds = [op[0] for op in doc.ops]
    assert "table" in kinds and "hr" in kinds
    table = next(op[1] for op in doc.ops if op[0] == "table")
    assert table.header == ["Name", "Size"] and table.align == ["left", "right"]
    assert table.rows[1] == ["b.py", "3,400"]
    assert set(doc.headings) == {"sample-document", "second-section"}
    hrefs = {link.href for link in doc.links}
    assert hrefs == {"#second-section", "sample.py"}


def test_markdown_offsets_match_text():
    doc = mdrender.build("# A\n\nsee [x](y) and ---\n\n---\n\n[z](w)")
    flat = []
    for op in doc.ops:
        flat.append(op[1] if op[0] == "text" else "￼")
    flat = "".join(flat)
    assert len(flat) == doc.length
    for link in doc.links:
        assert flat[link.start:link.end] in ("x", "z")


def test_slugify_duplicates():
    seen = {}
    assert mdrender.slugify("Hello, World!", seen) == "hello-world"
    assert mdrender.slugify("Hello World", seen) == "hello-world-1"
    assert mdrender.slugify("Técnica", seen) == "técnica"


def test_markdown_speed():
    text = open(os.path.join(FIX, "sample.md")).read() * 500
    t = time.perf_counter()
    mdrender.build(text)
    assert time.perf_counter() - t < 5.0


def test_csv_index(tmp_path):
    p = tmp_path / "a.csv"
    p.write_text('id,name,qty\n1,"Recife, PE",3\n2,"multi\nline",4\n3,plain,5')
    ix = CsvIndex(str(p))
    ix.build()
    assert ix.complete and ix.rows_indexed == 4
    assert ix.row(0) == ["id", "name", "qty"]
    assert ix.row(1) == ["1", "Recife, PE", "3"]
    assert ix.row(2) == ["2", "multi\nline", "4"]
    assert ix.row(3) == ["3", "plain", "5"]
    ix.close()


def test_csv_sniffs_semicolons_and_bom(tmp_path):
    p = tmp_path / "b.csv"
    p.write_bytes("﻿a;b\n1;2\n".encode())
    ix = CsvIndex(str(p))
    ix.build()
    assert ix.delimiter == ";"
    assert ix.row(0) == ["a", "b"] and ix.row(1) == ["1", "2"]
    ix.close()


def test_csv_empty_file(tmp_path):
    p = tmp_path / "c.csv"
    p.write_text("")
    ix = CsvIndex(str(p))
    ix.build()
    assert ix.complete and ix.rows_indexed == 0
    ix.close()


def test_csv_large_index_speed(tmp_path):
    p = tmp_path / "big.csv"
    with open(p, "w") as f:
        f.write("a,b,c\n")
        f.write("1,2,hello\n" * 500000)
    ix = CsvIndex(str(p))
    t = time.perf_counter()
    ix.build()
    assert ix.rows_indexed == 500001
    assert time.perf_counter() - t < 3.0
    ix.close()


def test_detect_numeric():
    rows = [["1", "a", "2,000.5", ""], ["2", "b", "3", ""]]
    assert detect_numeric(rows) == [True, False, True, False]


def test_recents(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path))
    recents.add("/tmp")
    recents.add(FIX)
    recents.add("/tmp")
    items = recents.load()
    assert [i["path"] for i in items] == ["/tmp", FIX]
    recents.clear()
    assert recents.load() == []
    assert recents.when(time.time()) == "Today"


# -- themes -------------------------------------------------------------------

from odv import settings, themes  # noqa: E402


def test_theme_catalogue_is_complete():
    ids = [t["id"] for t in themes.catalogue()]
    assert ids == ["pastel", "dracula", "kitty", "neon"]
    styles = os.path.join(themes.DATA, "styles")
    for t in themes.catalogue():
        assert themes.background_path(t) and themes.thumbnail_path(t)
        assert os.path.exists(os.path.join(styles, f"odv-{t['id']}.xml"))


def test_resolve_default_follows_system():
    light, dark = themes.resolve("libre-apps", False), themes.resolve("libre-apps", True)
    assert (light["dark"], dark["dark"]) == (False, True)
    assert light["scheme"] == "odv-light" and dark["scheme"] == "odv-dark"
    assert light["background"] is None
    # An imported theme decides light/dark itself, whatever the system says.
    assert themes.resolve("dracula", False)["dark"] is True
    assert themes.resolve("pastel", True)["dark"] is False
    assert themes.resolve("nonexistent", False)["id"] == "libre-apps"


def test_small_text_colour_passes_contrast():
    for tid in ["libre-apps"] + [t["id"] for t in themes.catalogue()]:
        for dark in (False, True):
            t = themes.resolve(tid, dark)
            assert themes.contrast(t["small_dim"], t["ui"]["bg"]) >= 4.5, tid
            assert themes.contrast(t["ui"]["text"], t["ui"]["bg"]) >= 4.5, tid
    # Pastel's textMuted is 3.6:1 on its background, so small text uses text.
    pastel = themes.resolve("pastel", False)
    assert pastel["small_dim"] == pastel["ui"]["text"]


def test_settings_round_trip(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    settings.reset_cache()
    assert settings.load() == {"theme": "libre-apps", "background": True, "watch": True}
    settings.save({"theme": "kitty", "background": False})
    assert settings.load() == {"theme": "kitty", "background": False, "watch": True}
    (tmp_path / "open-documents-viewer" / "settings.json").write_text('{"theme": 3, "x": 1}')
    assert settings.load() == {"theme": "libre-apps", "background": True, "watch": True}
    # set() keeps every other key.
    settings.reset_cache()
    settings.set("watch", False)
    settings.set("theme", "neon")
    assert settings.load() == {"theme": "neon", "background": True, "watch": False}
    settings.reset_cache()


# -- languages ----------------------------------------------------------------

import ast  # noqa: E402

from odv import i18n  # noqa: E402

SRC_DIR = os.path.join(os.path.dirname(__file__), "..", "src", "odv")


def ui_strings():
    found = {}
    for root, _dirs, files in os.walk(SRC_DIR):
        for name in files:
            if not name.endswith(".py"):
                continue
            path = os.path.join(root, name)
            tree = ast.parse(open(path).read())
            for node in ast.walk(tree):
                if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "_"
                        and node.args and isinstance(node.args[0], ast.Constant)
                        and isinstance(node.args[0].value, str)):
                    found[node.args[0].value] = os.path.relpath(path, SRC_DIR)
    return found


def test_every_ui_string_is_translated():
    strings = ui_strings()
    assert len(strings) > 40
    missing = [f"{where}: {text!r}" for text, where in strings.items()
               if not all(lang in i18n.CATALOG.get(text, {}) for lang in ("pt", "es"))]
    assert not missing, "untranslated:\n" + "\n".join(missing)


def test_translations_keep_placeholders():
    import re

    for text, entry in i18n.CATALOG.items():
        want = sorted(re.findall(r"{\w+}", text))
        for lang, translated in entry.items():
            assert sorted(re.findall(r"{\w+}", translated)) == want, (lang, text)


@pytest.mark.parametrize("env,lang", [
    ({"LANG": "pt_BR.UTF-8"}, "pt"),
    ({"LANG": "es_ES.UTF-8"}, "es"),
    ({"LANG": "de_DE.UTF-8"}, "en"),
    ({"LANGUAGE": "pt_BR:en", "LANG": "en_US.UTF-8"}, "pt"),
    ({"LC_ALL": "C"}, "en"),
    ({"ODV_LANG": "es", "LANG": "pt_BR.UTF-8"}, "es"),
])
def test_language_detection(env, lang):
    assert i18n.detect(env) == lang


def test_translate():
    i18n.set_language("pt")
    try:
        assert i18n._("Open File…") == "Abrir arquivo…"
        assert i18n._("not in the catalogue") == "not in the catalogue"
    finally:
        i18n.set_language("en")


# -- search -------------------------------------------------------------------

from odv import foldersearch  # noqa: E402


def test_csv_find_wraps_and_goes_back(tmp_path):
    p = tmp_path / "f.csv"
    p.write_text("id,city\n1,Teresina\n2,Recife\n3,Natal\n4,RECIFE\n")
    ix = CsvIndex(str(p))
    ix.build()
    assert ix.find("recife", 0) == 2
    assert ix.find("recife", 2) == 4
    assert ix.find("recife", 4) == 2  # wraps
    assert ix.find("recife", 4, forward=False) == 2
    assert ix.find("recife", 2, forward=False) == 4  # wraps backwards
    assert ix.find("city", 0) is None  # the header is not a data row
    assert ix.find("nowhere", 0) is None
    ix.close()


def _tree(root):
    (root / "src").mkdir()
    (root / "build").mkdir()
    (root / "src" / "a.py").write_text("x = 1\nNeedle here\nnothing\n")
    (root / "src" / "b.txt").write_text("no match\n")
    (root / "build" / "out.txt").write_text("needle in an ignored folder\n")
    (root / "notes.log").write_text("needle in an ignored file\n")
    (root / "image.bin").write_bytes(b"\x00\x01needle")
    (root / ".gitignore").write_text("build/\n*.log\n")


def test_folder_search_respects_gitignore(tmp_path):
    _tree(tmp_path)
    results, truncated = foldersearch.search(str(tmp_path), "NEEDLE")
    assert not truncated
    assert [os.path.relpath(r.path, tmp_path) for r in results] == [os.path.join("src", "a.py")]
    hit = results[0].hits[0]
    assert hit.line == 2 and hit.text[hit.column:hit.column + 6] == "Needle"


def test_folder_search_uses_git_when_present(tmp_path):
    import shutil
    import subprocess

    if not shutil.which("git"):
        pytest.skip("git not installed")
    _tree(tmp_path)
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    files = [os.path.relpath(f, tmp_path) for f in foldersearch.list_files(str(tmp_path))]
    assert os.path.join("build", "out.txt") not in files and "notes.log" not in files
    assert os.path.join("src", "a.py") in files


def test_folder_search_snippet_of_long_line(tmp_path):
    (tmp_path / "long.txt").write_text("x" * 500 + "needle" + "y" * 500 + "\n")
    results, _ = foldersearch.search(str(tmp_path), "needle")
    hit = results[0].hits[0]
    assert len(hit.text) <= foldersearch.SNIPPET + 1
    assert hit.text[hit.column:hit.column + 6] == "needle"


def test_folder_search_cancel(tmp_path):
    _tree(tmp_path)
    with pytest.raises(foldersearch.Cancelled):
        foldersearch.search(str(tmp_path), "needle", cancelled=lambda: True)


# -- fuzzy --------------------------------------------------------------------

from odv import fuzzy  # noqa: E402

FILES = ["src/odv/window.py", "src/odv/views/markdown.py", "README.md", "tests/test_gui.py",
         "packaging/deb/build.sh", "src/odv/mdrender.py", "CHANGELOG.md"]


@pytest.mark.parametrize("query,best", [
    ("win", "src/odv/window.py"),
    ("mdr", "src/odv/mdrender.py"),
    ("readme", "README.md"),
    ("tgui", "tests/test_gui.py"),
    ("deb b", "packaging/deb/build.sh"),
    ("views mark", "src/odv/views/markdown.py"),
])
def test_fuzzy_best_match(query, best):
    assert fuzzy.rank(query, FILES)[0] == best


def test_fuzzy_needs_order_and_limits():
    assert fuzzy.score("xyz", "src/odv/window.py") is None
    assert fuzzy.score("wodniw", "window") is None
    assert fuzzy.rank("", FILES) == FILES[:50]
    assert len(fuzzy.rank("o", FILES * 30, limit=10)) == 10


def test_markdown_outline():
    doc = mdrender.build("# Title\n\ntext\n\n## One\n\nmore\n\n### Deep `code`\n\n## Two\n")
    assert [(h.level, h.title, h.line) for h in doc.outline] == [
        (1, "Title", 1), (2, "One", 5), (3, "Deep code", 9), (2, "Two", 11)]
    offsets = [h.offset for h in doc.outline]
    assert offsets == sorted(offsets) and offsets[0] == 0
