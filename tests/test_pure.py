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
    ("page.html", "html", "html"),
    ("old.HTM", "html", "html"),
])
def test_classify(name, kind, lang):
    k = filetypes.classify(name)
    assert (k.kind, k.lang) == (kind, lang)
    assert k.has_formatted == (kind in ("markdown", "delimited", "html"))


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
    d = settings.DEFAULTS
    assert settings.load() == d
    settings.save({"theme": "kitty", "background": False})
    assert settings.load() == {**d, "theme": "kitty", "background": False}
    (tmp_path / "open-documents-viewer" / "settings.json").write_text('{"theme": 3, "x": 1}')
    assert settings.load() == d
    # set() keeps every other key.
    settings.reset_cache()
    settings.set("watch", False)
    settings.set("theme", "neon")
    assert settings.load() == {**d, "theme": "neon", "watch": False}
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


# -- links --------------------------------------------------------------------

from odv import links  # noqa: E402


def test_link_resolution(tmp_path):
    doc = tmp_path / "docs" / "a.md"
    doc.parent.mkdir()
    doc.write_text("x")
    (tmp_path / "docs" / "b.md").write_text("y")
    (tmp_path / "img").mkdir()
    r = lambda href: links.resolve(href, str(doc))  # noqa: E731
    assert r("#usage") == ("anchor", None, "usage")
    assert r("b.md") == ("local", str(tmp_path / "docs" / "b.md"), None)
    assert r("b.md#install%20notes") == ("local", str(tmp_path / "docs" / "b.md"), "install notes")
    assert r("../img") == ("local", str(tmp_path / "img"), None)
    assert r("nope.md") == ("missing", "nope.md", None)
    assert r("https://libre-apps.org") == ("external", "https://libre-apps.org", None)
    assert r("mailto:a@b.c") == ("external", "mailto:a@b.c", None)
    assert r("//example.org/x") == ("external", "https://example.org/x", None)
    assert r("javascript:alert(1)") == ("ignored", None, None)
    assert r("") == ("ignored", None, None)
    assert r(f"file://{tmp_path}/docs/b.md") == ("local", str(tmp_path / "docs" / "b.md"), None)


def test_session_drops_what_is_gone(tmp_path, monkeypatch):
    from odv import session

    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    a = tmp_path / "a.md"
    a.write_text("x")
    assert session.load() is None
    session.save({"folder": str(tmp_path / "gone"), "panes": [
        {"tabs": [{"path": str(a), "mode": "source", "scroll": 10}, {"path": str(tmp_path / "b.md")}], "selected": 1},
        {"tabs": [{"path": str(tmp_path / "c.md")}], "selected": 0}]})
    state = session.load()
    assert state["folder"] is None
    assert state["panes"] == [{"tabs": [{"path": str(a), "mode": "source", "scroll": 10}], "selected": 0}]
    session.save({"folder": None, "panes": []})
    assert session.load() is None


def test_zoom_steps(tmp_path, monkeypatch):
    from odv import comfort

    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    settings.reset_cache()
    assert comfort.zoom_of("code") == 100
    assert comfort.step_zoom("code", 1) == 110
    assert comfort.step_zoom("code", 1) == 120
    assert comfort.zoom_of("prose") == 100  # each kind keeps its own
    for _ in range(30):
        comfort.step_zoom("code", -1)
    assert comfort.zoom_of("code") == comfort.ZOOM_STEPS[0]
    assert comfort.step_zoom("code", 0) == 100
    assert ".zoom-150 { font-size: 1.50em; }" in comfort.zoom_css()
    settings.reset_cache()


def test_file_properties():
    import gi

    gi.require_version("Gtk", "4.0")
    from odv import fileactions

    rows = dict(fileactions.properties(os.path.join(FIX, "sample.py"), "utf-8", 7))
    assert rows["Name"] == "sample.py" and rows["Encoding"] == "utf-8" and rows["Lines"] == "7"
    assert rows["Permissions"].startswith("-r")
    assert "text/x-python" in rows["Type"]


# -- HTML -----------------------------------------------------------------------

from odv.formats import htmlrender  # noqa: E402


def _flat(doc):
    return "".join(op[1] if op[0] == "text" else "￼" for op in doc.ops)


def test_html_render():
    doc = htmlrender.build(open(os.path.join(FIX, "sample.html")).read())
    text = _flat(doc)
    assert "never shown" not in text and "color: red" not in text and "Sample page" not in text
    assert text.startswith("Sample & page\n")
    assert "A paragraph with bold, italic, underline, code and a jump." in text
    assert "[image: remote picture]" in text
    assert "• one\n• two\n" in text
    assert "  indented\n    code" in text
    table = next(op[1] for op in doc.ops if op[0] == "table")
    assert table.header == ["Name", "Size"] and table.rows == [["a.md", "12"]] and table.align[1] == "right"
    assert [(h.level, h.title) for h in doc.outline] == [(1, "Sample & page"), (2, "Details"), (2, "Last part")]
    assert {"top", "details", "custom-id", "last-part"} <= set(doc.headings)
    assert [link.href for link in doc.links] == ["#details", "sample.md"]
    assert len(text) == doc.length
    for link in doc.links:
        assert text[link.start:link.end] in ("jump", "the Markdown sample")


def test_html_styles_and_broken_markup():
    doc = htmlrender.build("<p>x <b>b <i>bi</p><p>after</p></div></span>")
    tags = {op[1]: op[2] for op in doc.ops if op[0] == "text"}
    assert "bold" in tags["b "] and {"bold", "italic"} <= set(tags["bi"])
    assert "bold" not in tags.get("after", ()) and "italic" not in tags.get("after", ())
    assert htmlrender.build("").ops == []
    assert htmlrender.build("plain &lt;text&gt;").ops[0][1] == "plain <text>"


def test_image_kinds():
    png, svg = filetypes.classify("photo.JPG"), filetypes.classify("logo.svg")
    assert png.kind == "image" and png.has_formatted and not png.has_source
    assert svg.kind == "image" and svg.has_source and svg.lang == "xml"
    assert filetypes.classify("a.md").has_source


# -- RTF --------------------------------------------------------------------------

from odv.formats import rtfrender  # noqa: E402


def test_rtf_render():
    doc = rtfrender.build(open(os.path.join(FIX, "sample.rtf"), "rb").read())
    text = _flat(doc)
    assert "Hidden title" not in text and "Some Writer" not in text and "Arial" not in text
    assert text.startswith("Sample RTF\nPlain, bold, italic, underline, gone and terracotta.\n")
    assert "Accents: olá, coração, € euro." in text
    assert "•\tOne item\n•\tTwo items\n" in text
    styles = {op[1]: op[2] for op in doc.ops if op[0] == "text"}
    assert {"bold", "scale-150"} <= set(styles["Sample RTF"])
    assert "fg-B5502B" in styles["terracotta"] and "strike" in styles["gone"]
    table = next(op[1] for op in doc.ops if op[0] == "table")
    assert table.header == ["Name", "Size"] and table.rows == [["a.md", "12"]]
    assert text.rstrip().endswith("After the table.")
    assert len(text) == doc.length


def test_rtf_edge_cases():
    assert _flat(rtfrender.build(b"{\\rtf1 a\\{b\\}c\\\\d}")) == "a{b}c\\d"
    assert _flat(rtfrender.build(b"{\\rtf1\\uc2 x\\u233\\'e9\\'e9y}")) == "xéy"
    assert _flat(rtfrender.build(b"{\\rtf1 {\\*\\unknowndest secret}shown}")) == "shown"
    assert rtfrender.build(b"").ops == []


# -- notebooks --------------------------------------------------------------------

from odv.formats import ipynb  # noqa: E402


def test_notebook_render():
    doc = ipynb.build(open(os.path.join(FIX, "sample.ipynb")).read())
    text = _flat(doc)
    assert text.startswith("Notebook title\nSome bold text and a link.\nIn [1]:\nx = 2 + 2\nprint(x)\n")
    assert "\n4\n" in text and "\n40\n" in text
    assert "ZeroDivisionError: division by zero" in text and "\x1b" not in text
    assert "<Figure>" not in text  # the image replaces its text alternative
    assert [op[0] for op in doc.ops].count("image") == 1
    assert [(h.level, h.title) for h in doc.outline] == [(1, "Notebook title"), (2, "Results")]
    link = doc.links[0]
    assert text[link.start:link.end] == "link" and doc.headings["results"] == doc.outline[1].offset
    assert len(text) == doc.length


def test_notebook_v3_and_empty():
    v3 = {"nbformat": 3, "worksheets": [{"cells": [{"cell_type": "code", "input": "1+1", "prompt_number": 4,
                                                     "outputs": []}]}]}
    import json

    assert _flat(ipynb.build(json.dumps(v3))).startswith("In [4]:\n1+1\n")
    assert ipynb.build('{"cells": []}').ops == []


@pytest.mark.parametrize("name,lang", [
    ("Jenkinsfile", "groovy"), ("Procfile", "yaml"), (".editorconfig", "ini"), ("App.vue", "html"),
    ("Page.svelte", "html"), ("schema.graphql", "odv-graphql"), ("movie.srt", "odv-subtitles"),
    ("talk.vtt", "odv-subtitles"), ("app.log", "odv-log"), ("Info.plist", "xml"), ("vars.hcl", "terraform"),
    ("pt_BR.po", "gettext-translation"), ("events.ndjson", "json"), ("Gemfile", "ruby"), ("go.mod", "go"),
    ("unit.service", "ini"), (".env.production", "sh"),
])
def test_more_languages(name, lang):
    assert filetypes.classify(name).lang == lang


@pytest.mark.parametrize("line,lang", [
    ("#!/usr/bin/env python3", "python3"), ("#!/bin/bash", "sh"), ("#!/usr/bin/env -S node --no-warnings", "js"),
    ("#!/usr/bin/python3.12", "python3"), ("#!/usr/bin/env ruby", "ruby"), ("#!/usr/bin/env FOO=1 perl", "perl"),
    ("# not a shebang", None), ("#!/usr/bin/unknown", None), ("#!", None),
])
def test_shebang(line, lang):
    assert filetypes.lang_from_shebang(line) == lang


# -- data trees ---------------------------------------------------------------------

from odv.formats import treedata  # noqa: E402


def test_tree_parsing():
    data = treedata.parse("json", open(os.path.join(FIX, "sample.json")).read())
    assert data["list"] == [1, 2, 3]
    yaml_data = treedata.parse("yaml", open(os.path.join(FIX, "sample.yaml")).read())
    assert yaml_data["items"][1]["note"] == "find me here"
    xml = treedata.parse("xml", open(os.path.join(FIX, "sample.xml")).read())
    lib = xml["library"]
    assert lib["@name"] == "home" and lib["book"][1]["title"] == "Iracema" and lib["book"][0]["@id"] == "1"
    assert treedata.parse("ndjson", open(os.path.join(FIX, "sample.ndjson")).read())[1] == {"id": 2, "ok": False}
    with pytest.raises(treedata.ParseError) as err:
        treedata.parse("json", open(os.path.join(FIX, "broken.json")).read())
    assert err.value.line == 4


def test_tree_paths_preview_search():
    assert treedata.path_string(["items", 1, "note"], "json") == "$.items[1].note"
    assert treedata.path_string(["a b", 0], "yaml") == '$["a b"][0]'
    assert treedata.path_string(["library", "book", 1, "title"], "xml") == "/library/book[2]/title"
    assert treedata.preview({"a": 1}) == "{1}" and treedata.preview([1, 2]) == "[2]"
    assert treedata.preview(None) == "null" and treedata.preview(True) == "true"
    assert treedata.preview("x" * 300).endswith('…"')
    data = {"name": "odv", "items": [{"id": 1}, {"note": "find ME here", "id": 2}]}
    assert treedata.search(data, "find me") == [["items", 1, "note"]]
    assert treedata.search(data, "id") == [["items", 0, "id"], ["items", 1, "id"]]


def test_xml_entity_bomb_is_refused():
    bomb = ('<?xml version="1.0"?><!DOCTYPE l [<!ENTITY a "aaaaaaaaaa">'
            + "".join(f'<!ENTITY {chr(98 + i)} "{("&" + chr(97 + i) + ";") * 10}">' for i in range(8))
            + ']><l>&i;</l>')
    with pytest.raises(treedata.ParseError):
        treedata.parse("xml", bomb)


def test_csv_sort_order(tmp_path):
    p = tmp_path / "s.csv"
    p.write_text("name,qty\nbanana,10\nApple,2\ncherry,\n,7\napple pie,1000\n")
    ix = CsvIndex(str(p))
    ix.build()
    assert list(ix.sort_order(0)) == [2, 5, 1, 3, 4]  # case-insensitive, the empty name last
    assert list(ix.sort_order(1, numeric=True)) == [2, 4, 1, 5, 3]  # numbers, the empty qty last
    assert list(ix.sort_order(1, descending=True, numeric=True)) == [5, 1, 4, 2, 3]
    assert ix.sort_order(0, cancelled=lambda: True) is not None  # small files finish before checking
    ix.close()


def test_csv_encoding_detection(tmp_path):
    p = tmp_path / "latin.csv"
    p.write_bytes("cidade;estado\nSão Luís;MA\n".encode("cp1252"))
    ix = CsvIndex(str(p))
    ix.build()
    assert ix.encoding == "cp1252" and ix.delimiter == ";"
    assert ix.row(1) == ["São Luís", "MA"]
    ix.close()


# -- encodings ------------------------------------------------------------------------


def test_utf16_without_bom(tmp_path):
    for codec in ("utf-16-le", "utf-16-be"):
        p = tmp_path / f"{codec}.txt"
        p.write_bytes("Olá, mundo\nsegunda linha\n".encode(codec))
        tf = textio.read_text(str(p))
        assert tf.text.startswith("Olá, mundo") and tf.encoding == codec
    assert textio.looks_binary(b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR")


def test_forced_encoding_and_line_endings(tmp_path):
    p = tmp_path / "f.txt"
    p.write_bytes("ação\r\nlinha\r\n".encode("utf-8"))
    tf = textio.read_text(str(p))
    assert tf.text.startswith("ação") and tf.line_endings == "CRLF"
    assert textio.read_text(str(p), encoding="cp1252").text.startswith("aÃ§Ã£o")
    assert textio.line_endings("a\nb\n") == "LF" and textio.line_endings("a\rb") == "CR"
    assert textio.line_endings("a\nb\r\n") == "Mixed" and textio.line_endings("one") == "None"
    import codecs

    assert all(codecs.lookup(codec) for codec, _ in textio.ENCODINGS)


# -- e-books --------------------------------------------------------------------------

from odv.formats import epub  # noqa: E402
from books import make_epub, make_fb2  # noqa: E402


def test_epub3(tmp_path):
    doc = epub.build(None, str(make_epub(tmp_path / "b.epub")))
    text = _flat(doc)
    assert text.index("Chapter One") < text.index("Chapter Two") < text.index("The end.")
    assert [op[0] for op in doc.ops].count("image") == 1
    assert [(h.level, h.title) for h in doc.outline] == [(1, "One (from the nav)"), (1, "Two (from the nav)"),
                                                         (2, "Scene")]
    link = doc.links[0]
    assert link.href == "#OEBPS/text/c2.xhtml#scene"
    assert doc.headings["oebps/text/c2.xhtml#scene"] == doc.outline[2].offset


def test_epub2_ncx_drm_and_fb2(tmp_path):
    doc = epub.build(None, str(make_epub(tmp_path / "b2.epub", version=2)))
    assert [h.title for h in doc.outline] == ["One (from the NCX)", "Two (from the NCX)"]
    with pytest.raises(epub.EpubError, match="DRM"):
        epub.build(None, str(make_epub(tmp_path / "drm.epub", drm=True)))
    with pytest.raises(epub.EpubError):
        epub.build(None, str(tmp_path / "missing.epub"))
    fb = epub.build(None, str(make_fb2(tmp_path / "b.fb2")))
    text = _flat(fb)
    assert "Hidden metadata" not in text and "Some words and more." in text
    assert [(h.level, h.title) for h in fb.outline] == [(2, "Part One"), (3, "Inner")]
    assert [op[0] for op in fb.ops].count("image") == 1


def test_positions(tmp_path, monkeypatch):
    from odv import positions

    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path))
    assert positions.get("/x/book.epub") is None
    positions.put("/x/book.epub", 1234)
    assert positions.get("/x/book.epub") == 1234


def test_markdown_local_images(tmp_path):
    import shutil

    shutil.copy(os.path.join(FIX, "images", "a-red.png"), tmp_path / "pic.png")
    md = tmp_path / "doc.md"
    md.write_text("![local](pic.png) ![remote](https://example.org/x.png) ![missing](nope.png)\n")
    doc = mdrender.build(md.read_text(), str(md))
    images = [op for op in doc.ops if op[0] == "image"]
    assert len(images) == 1 and images[0][1][:4] == b"\x89PNG"
    text = _flat(doc)
    assert "[image: remote]" in text and "[image: missing]" in text
    assert len(text) == doc.length
    assert all(op[0] != "image" for op in mdrender.build(md.read_text()).ops)  # no path, no files read


# -- e-mail, contacts, calendars ----------------------------------------------------------

from odv.formats import messages  # noqa: E402


def test_eml():
    doc = messages.build(None, os.path.join(FIX, "sample.eml"))
    text = _flat(doc)
    assert text.startswith("Relatório de março\n")
    assert "segue o relatório." in text and "alert(1)" not in text
    header = next(op[1] for op in doc.ops if op[0] == "table")
    assert header.rows[0] == ["From", "Ana <ana@example.org>"]
    assert [op[0] for op in doc.ops].count("image") == 1  # the cid: logo; the remote pixel is not loaded
    assert list(doc.attachments.values()) == [("dados.csv", b"id,valor\n1,10\n")]
    link = next(link for link in doc.links if link.href == "odv-attachment:0")
    assert text[link.start:link.end] == "dados.csv"


def test_vcf_and_ics():
    vcf = messages.build(open(os.path.join(FIX, "contacts.vcf")).read(), "contacts.vcf")
    assert [h.title for h in vcf.outline] == ["Ana Souza", "Bruno Lima"]
    rows = next(op[1] for op in vcf.ops if op[0] == "table").rows
    assert ["Address (home)", "Rua A, 10, Teresina, PI, 64000-000, Brasil"] in rows
    assert ["Note", "Primeira linha\nsegunda linha"] in rows
    ics = messages.build(open(os.path.join(FIX, "events.ics")).read(), "events.ics")
    assert [h.title for h in ics.outline] == ["First meeting", "Second", "Entregar"]  # by date
    first = next(op[1] for op in ics.ops if op[0] == "table").rows
    assert first[0] == ["Starts", "2026-12-01 09:00 (America/Fortaleza)"] and ["Organizer", "Ana"] in first


# -- office -----------------------------------------------------------------------------

from odv.formats import docx  # noqa: E402
from office import make_docx  # noqa: E402


def test_docx(tmp_path):
    doc = docx.build(None, str(make_docx(tmp_path / "r.docx")))
    text = _flat(doc)
    assert text.startswith("Company header\n")
    assert "Plain bold, red italic and a link.[1]7\n" in text  # field result kept, instruction dropped
    assert "PAGE" not in text and "hidden text" not in text
    assert "1. First step\n2. Second step\n• A bullet\n" in text
    assert text.rstrip().endswith("Page footer") and "[1] A footnote." in text
    assert [(h.level, h.title) for h in doc.outline] == [(1, "Report Title"), (1, "Introduction"), (2, "Details")]
    styles = {op[1]: op[2] for op in doc.ops if op[0] == "text"}
    assert "bold" in styles["bold"] and {"italic", "fg-B5502B"} <= set(styles["red italic"])
    assert "align-center" in styles["Centered line\n"]
    table = next(op[1] for op in doc.ops if op[0] == "table")
    assert table.header == ["Name", "Size"] and table.rows == [["merged", ""]]
    kinds = [op[0] for op in doc.ops]
    assert kinds.count("image") == 1 and kinds.count("hr") >= 3  # header, page break, notes, footer
    link = doc.links[0]
    assert link.href == "https://libre-apps.org" and text[link.start:link.end] == "a link"
    assert len(text) == doc.length


def test_docx_errors(tmp_path):
    bad = tmp_path / "bad.docx"
    bad.write_bytes(b"not a zip")
    with pytest.raises(docx.DocxError):
        docx.build(None, str(bad))


def test_odt(tmp_path):
    from odv.formats import odt
    from office import make_odt

    doc = odt.build(None, str(make_odt(tmp_path / "r.odt")))
    text = _flat(doc)
    assert text.startswith("Company header\n")
    assert "Plain bold, red italic and a link.[1]\n" in text
    assert "Centered  line\n" in text and "hidden text" not in text
    assert "1. First step\n2. Second step\n• A bullet\n" in text
    assert "[1] A footnote." in text and text.rstrip().endswith("Page footer")
    assert [(h.level, h.title) for h in doc.outline] == [(1, "Report Title"), (2, "Introduction"), (2, "Details")]
    styles = {op[1]: op[2] for op in doc.ops if op[0] == "text"}
    assert "bold" in styles["bold"] and {"italic", "fg-B5502B"} <= set(styles["red italic"])
    table = next(op[1] for op in doc.ops if op[0] == "table")
    assert table.header == ["Name", "Size"] and table.rows == [["merged", ""]]
    assert [op[0] for op in doc.ops].count("image") == 1
    assert doc.links[0].href == "https://libre-apps.org"
    assert len(text) == doc.length


DOC_TEXT = ("Report Title\rPlain paragraph with ação.\r"
            "Name\x07Size\x07\x07merged\x07x\x07\x07"
            "After \x13 PAGE \\* MERGEFORMAT \x147\x15 field.\rBefore\x0cafter the break\r")


def test_doc_binary(tmp_path):
    olefile = pytest.importorskip("olefile")
    from cfb import make_doc
    from odv.formats import doc as docfmt

    path = make_doc(tmp_path / "old.doc", DOC_TEXT)
    assert olefile.isOleFile(str(path))
    d = docfmt.build(None, str(path))
    text = _flat(d)
    assert text.startswith("Report Title\nPlain paragraph with ação.\n")
    assert "After 7 field." in text and "MERGEFORMAT" not in text
    table = next(op[1] for op in d.ops if op[0] == "table")
    assert table.header == ["Name", "Size"] and table.rows == [["merged", "x"]]
    assert "hr" in [op[0] for op in d.ops] and "after the break" in text
    assert d.note.startswith("Plain text")
    with pytest.raises(docfmt.DocError, match="password"):
        docfmt.build(None, str(make_doc(tmp_path / "locked.doc", "x\r", encrypted=True)))
    with pytest.raises(docfmt.DocError):
        docfmt.build(None, os.path.join(FIX, "sample.md"))
