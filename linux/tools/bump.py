"""Set the version everywhere it is written and add a CHANGELOG entry.

    python3 tools/bump.py 0.2.0 "Added: ..." ["Fixed: ..."]

src/odv/__init__.py, the AppImage recipe, the AppStream metainfo <releases>
and CHANGELOG.md (newest first).
"""

import datetime
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
META = os.path.join(ROOT, "packaging", "common", "io.github.guilhermefeitosa66.OpenDocumentsViewer.metainfo.xml")


def sub(path, pattern, repl, count=1):
    with open(path) as f:
        text = f.read()
    new, n = re.subn(pattern, repl, text, count=count, flags=re.M)
    if n == 0:
        sys.exit(f"bump: pattern not found in {path}: {pattern}")
    with open(path, "w") as f:
        f.write(new)


def main():
    version, notes = sys.argv[1], sys.argv[2:]
    today = datetime.date.today().isoformat()
    sub(os.path.join(ROOT, "src", "odv", "__init__.py"), r'^__version__ = ".*"', f'__version__ = "{version}"')
    sub(os.path.join(ROOT, "packaging", "appimage", "AppImageBuilder.yml"), r'^    version: ".*"',
        f'    version: "{version}"')
    items = "".join(f"        <li>{n.replace('&', '&amp;').replace('<', '&lt;')}</li>\n" for n in notes)
    sub(META, r"^  <releases>\n", f'  <releases>\n    <release version="{version}" date="{today}">\n'
        f"      <description>\n        <ul>\n{items}        </ul>\n      </description>\n    </release>\n")
    changelog = os.path.join(ROOT, "CHANGELOG.md")
    head = "# Changelog\n\nAll notable changes to this project. Versions follow semantic versioning.\n\n"
    body = ""
    if os.path.exists(changelog):
        with open(changelog) as f:
            body = f.read()
        body = body[len(head):] if body.startswith(head) else body
    entry = f"## {version} ({today})\n\n" + "".join(f"- {n}\n" for n in notes) + "\n"
    with open(changelog, "w") as f:
        f.write(head + entry + body)
    print(f"version {version}")


if __name__ == "__main__":
    main()
