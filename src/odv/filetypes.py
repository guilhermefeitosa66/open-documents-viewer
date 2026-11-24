"""File classification. Pure Python: no GTK, cheap to import."""

import os

MARKDOWN_EXT = {".md", ".markdown", ".mdown", ".mkd", ".mkdn", ".mdtext"}
DELIMITED_EXT = {".csv": None, ".tsv": "\t", ".tab": "\t"}
HTML_EXT = {".html", ".htm", ".xhtml", ".shtml"}
# Word-processing documents read from their package (odv.formats.office).
DOCUMENT_EXT = {".docx", ".odt", ".doc"}
# Structured data with a tree view: extension -> (parser kind, source language).
TREE_EXT = {".json": ("json", "json"), ".geojson": ("json", "json"), ".webmanifest": ("json", "json"),
            ".ndjson": ("ndjson", "json"), ".jsonl": ("ndjson", "json"), ".yaml": ("yaml", "yaml"),
            ".yml": ("yaml", "yaml"), ".xml": ("xml", "xml"), ".plist": ("xml", "xml"),
            ".xsd": ("xml", "xml"), ".gpx": ("xml", "xml"), ".kml": ("xml", "xml")}
# What GdkPixbuf can usually decode; the image view reports a missing loader.
IMAGE_EXT = {".png", ".jpg", ".jpeg", ".jpe", ".gif", ".webp", ".bmp", ".ico", ".cur", ".tif", ".tiff",
             ".avif", ".heic", ".heif", ".jxl", ".svg", ".svgz", ".tga", ".pnm", ".pbm", ".pgm", ".ppm",
             ".xpm", ".xbm", ".icns", ".qoi", ".ani"}

# Language ids understood by GtkSourceView 5, for names its own guess misses.
LANG_BY_EXT = {
    ".env": "sh",
    ".conf": "ini",
    ".cfg": "ini",
    ".properties": "ini",
    ".editorconfig": "ini",
    ".jsonc": "json",
    ".json5": "json",
    ".mjs": "js",
    ".cjs": "js",
    ".tsx": "typescript-jsx",
    ".jsx": "jsx",
    ".kts": "kotlin",
    ".gradle": "groovy",
    ".lock": "toml",
    ".vue": "html",
    ".svelte": "html",
    ".astro": "html",
    ".graphql": "odv-graphql",
    ".gql": "odv-graphql",
    ".graphqls": "odv-graphql",
    ".srt": "odv-subtitles",
    ".vtt": "odv-subtitles",
    ".log": "odv-log",
    ".plist": "xml",
    ".svgz": "xml",
    ".xaml": "xml",
    ".csproj": "xml",
    ".hcl": "terraform",
    ".tfvars": "terraform",
    ".po": "gettext-translation",
    ".pot": "gettext-translation",
    ".jsonl": "json",
    ".ndjson": "json",
    ".geojson": "json",
    ".webmanifest": "json",
    ".tsv-schema": "json",
    ".zsh": "sh",
    ".bash": "sh",
    ".ksh": "sh",
    ".service": "ini",
    ".socket": "ini",
    ".timer": "ini",
    ".container": "ini",
}
LANG_BY_NAME = {
    "dockerfile": "docker",
    "containerfile": "docker",
    "makefile": "makefile",
    "gnumakefile": "makefile",
    "cmakelists.txt": "cmake",
    ".gitignore": "sh",
    ".dockerignore": "sh",
    ".bashrc": "sh",
    ".zshrc": "sh",
    ".profile": "sh",
    "pkgbuild": "sh",
    "jenkinsfile": "groovy",
    "procfile": "yaml",
    ".editorconfig": "ini",
    ".gitattributes": "sh",
    ".gitmodules": "ini",
    ".gitconfig": "ini",
    ".npmrc": "ini",
    ".prettierrc": "json",
    ".eslintrc": "json",
    ".babelrc": "json",
    "gemfile": "ruby",
    "rakefile": "ruby",
    "vagrantfile": "ruby",
    "podfile": "ruby",
    "brewfile": "ruby",
    "justfile": "makefile",
    "meson.build": "meson",
    "meson_options.txt": "meson",
    "pipfile": "toml",
    "pyproject.toml": "toml",
    "go.mod": "go",
    ".bash_profile": "sh",
    ".bash_aliases": "sh",
    ".zprofile": "sh",
    ".zshenv": "sh",
    ".xinitrc": "sh",
    "apkbuild": "sh",
}

# Interpreters named in a "#!" first line -> language id.
SHEBANG = {
    "python": "python3", "python3": "python3", "python2": "python", "sh": "sh", "bash": "sh", "zsh": "sh",
    "dash": "sh", "ksh": "sh", "ash": "sh", "fish": "fish", "node": "js", "nodejs": "js", "deno": "typescript",
    "bun": "js", "perl": "perl", "ruby": "ruby", "php": "php", "lua": "lua", "Rscript": "r", "tclsh": "tcl",
    "wish": "tcl", "awk": "awk", "gawk": "awk", "make": "makefile", "julia": "julia", "groovy": "groovy",
    "scala": "scala", "pwsh": "powershell", "elixir": "elixir", "guile": "scheme", "racket": "scheme",
    "sbcl": "commonlisp", "ocaml": "ocaml", "runghc": "haskell", "swift": "swift", "kotlin": "kotlin",
}


def lang_from_shebang(first_line: str) -> str | None:
    """The language of a script from its "#!" line, if we know the interpreter."""
    if not first_line.startswith("#!"):
        return None
    parts = first_line[2:].strip().split()
    if not parts:
        return None
    prog = parts[0].rsplit("/", 1)[-1]
    if prog == "env":
        args = [p for p in parts[1:] if not p.startswith("-") and "=" not in p]
        if not args:
            return None
        prog = args[0].rsplit("/", 1)[-1]
    if prog in SHEBANG:
        return SHEBANG[prog]
    base = prog.rstrip("0123456789.")  # python3.12, ruby3.2…
    return SHEBANG.get(base)

CODE_EXT = {
    ".py", ".pyi", ".dart", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".kt", ".kts",
    ".java", ".c", ".h", ".cc", ".cpp", ".cxx", ".hpp", ".hh", ".rs", ".go", ".rb", ".php",
    ".sh", ".bash", ".zsh", ".fish", ".cs", ".swift", ".lua", ".pl", ".r", ".scala",
    ".sql", ".html", ".htm", ".css", ".scss", ".sass", ".less", ".vue", ".svelte",
    ".m", ".mm", ".hs", ".ex", ".exs", ".erl", ".clj", ".groovy", ".gradle", ".vala",
    ".zig", ".nim", ".jl", ".ps1", ".bat", ".cmake", ".mk", ".el", ".lisp", ".ml",
}
CONFIG_EXT = {
    ".json", ".jsonc", ".json5", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".conf",
    ".xml", ".plist", ".properties", ".env", ".lock", ".desktop", ".service", ".editorconfig",
}
WRAP_EXT = {".txt", ".text", ".log", ".rst", ".adoc", ".asciidoc", ".org", ".tex"}


class Kind:
    """kind: "markdown" | "delimited" | "text"; lang: a GtkSourceView language
    id hint (None = let it guess); delimiter: for "delimited" (None = sniff)."""

    __slots__ = ("kind", "lang", "delimiter", "wrap")

    def __init__(self, kind: str, lang: str | None, delimiter: str | None = None, wrap: bool = False):
        self.kind = kind
        self.lang = lang
        self.delimiter = delimiter
        self.wrap = wrap

    def __eq__(self, other):
        return isinstance(other, Kind) and all(getattr(self, a) == getattr(other, a) for a in self.__slots__)

    def __repr__(self):
        return f"Kind({self.kind!r}, {self.lang!r}, {self.delimiter!r}, {self.wrap!r})"

    @property
    def rich(self) -> bool:
        """Shown formatted by the rich-text view (odv.formats.RICH)."""
        return self.kind in ("markdown", "html", "rtf", "notebook", "ebook", "message", "document")

    @property
    def has_formatted(self) -> bool:
        return self.rich or self.kind in ("delimited", "image", "tree")

    @property
    def has_source(self) -> bool:
        """Raster images have no text to show; SVG does (its XML)."""
        return (self.kind != "image" or self.lang == "xml") and not self.binary

    @property
    def binary(self) -> bool:
        """Read from the file itself, not as text (EPUB is a zip)."""
        return self.kind in ("ebook", "document") and self.lang is None

    @property
    def formatted_label(self) -> str:
        return {"delimited": "Table", "image": "Image", "tree": "Tree"}.get(self.kind, "Formatted")


def is_env_file(name: str) -> bool:
    n = name.lower()
    return n == ".env" or n.startswith(".env.") or n.endswith(".env")


def _ext(name: str) -> str:
    return os.path.splitext(name)[1].lower()


def classify(path: str) -> Kind:
    name = os.path.basename(path)
    lower = name.lower()
    ext = _ext(name)
    if ext in MARKDOWN_EXT:
        return Kind("markdown", "markdown", wrap=True)
    if ext in DELIMITED_EXT:
        return Kind("delimited", None, DELIMITED_EXT[ext])
    if ext in HTML_EXT:
        return Kind("html", "html", wrap=True)
    if ext == ".rtf":
        return Kind("rtf", None, wrap=True)
    if ext == ".ipynb":
        return Kind("notebook", "json")
    if ext in (".eml", ".vcf", ".vcard", ".ics", ".ical", ".ifb"):
        return Kind("message", None, wrap=True)
    if ext in DOCUMENT_EXT:
        return Kind("document", None)
    if ext == ".epub":
        return Kind("ebook", None)
    if ext == ".fb2":
        return Kind("ebook", "xml")
    if ext in TREE_EXT:
        return Kind("tree", TREE_EXT[ext][1], delimiter=TREE_EXT[ext][0])
    if ext in IMAGE_EXT:
        return Kind("image", "xml" if ext in (".svg", ".svgz") else None)
    if is_env_file(name):
        return Kind("text", "sh")
    if lower in LANG_BY_NAME:
        return Kind("text", LANG_BY_NAME[lower])
    if ext in LANG_BY_EXT:
        return Kind("text", LANG_BY_EXT[ext], wrap=ext in WRAP_EXT or ext in (".srt", ".vtt"))
    wrap = ext in WRAP_EXT or (ext == "" and lower in {"license", "copying", "readme", "authors", "notice"})
    return Kind("text", None, wrap=wrap)


def sidebar_class(name: str) -> str:
    """The colour class of a file's dot in the folder tree."""
    ext = _ext(name)
    if ext in DOCUMENT_EXT:
        return "markdown"
    if ext in MARKDOWN_EXT or ext in HTML_EXT or ext in (".rtf", ".ipynb", ".epub", ".fb2", ".eml", ".vcf", ".ics"):
        return "markdown"
    if ext in DELIMITED_EXT or ext in IMAGE_EXT:
        return "data"
    if is_env_file(name) or ext in CONFIG_EXT:
        return "config"
    if ext in CODE_EXT or name.lower() in LANG_BY_NAME:
        return "code"
    return "text"
