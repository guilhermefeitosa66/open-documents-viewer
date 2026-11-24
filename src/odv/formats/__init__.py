"""Document formats. Each module is imported only when a file of its kind
opens, never on the start-up path."""

import importlib

# kind -> module with build(text: str, path: str) -> odv.mdrender.Doc
RICH = {
    "markdown": "odv.mdrender",
    "html": "odv.formats.htmlrender",
    "rtf": "odv.formats.rtfrender",
    "notebook": "odv.formats.ipynb",
}


def build_rich(kind: str, text: str, path: str):
    module = importlib.import_module(RICH[kind])
    return module.build(text, path)
