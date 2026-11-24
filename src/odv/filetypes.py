"""File classification. Pure Python: no GTK, cheap to import."""

import os

MARKDOWN_EXT = {".md", ".markdown", ".mdown", ".mkd", ".mkdn", ".mdtext"}
DELIMITED_EXT = {".csv": None, ".tsv": "\t", ".tab": "\t"}
HTML_EXT = {".html", ".htm", ".xhtml", ".shtml"}

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
}

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
        return self.kind in ("markdown", "html")

    @property
    def has_formatted(self) -> bool:
        return self.rich or self.kind == "delimited"

    @property
    def formatted_label(self) -> str:
        return "Table" if self.kind == "delimited" else "Formatted"


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
    if is_env_file(name):
        return Kind("text", "sh")
    if lower in LANG_BY_NAME:
        return Kind("text", LANG_BY_NAME[lower])
    if ext in LANG_BY_EXT:
        return Kind("text", LANG_BY_EXT[ext])
    wrap = ext in WRAP_EXT or (ext == "" and lower in {"license", "copying", "readme", "authors", "notice"})
    return Kind("text", None, wrap=wrap)


def sidebar_class(name: str) -> str:
    """The colour class of a file's dot in the folder tree."""
    ext = _ext(name)
    if ext in MARKDOWN_EXT or ext in HTML_EXT:
        return "markdown"
    if ext in DELIMITED_EXT:
        return "data"
    if is_env_file(name) or ext in CONFIG_EXT:
        return "config"
    if ext in CODE_EXT or name.lower() in LANG_BY_NAME:
        return "code"
    return "text"
