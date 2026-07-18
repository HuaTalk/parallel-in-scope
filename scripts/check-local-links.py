#!/usr/bin/env python3
"""Check that relative Markdown links point to files or directories in the repo."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[1]
SCAN_ROOTS = (ROOT / "README.md", ROOT / "README.en.md", ROOT / "CHANGELOG.md", ROOT / "docs", ROOT / "demo")
LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+['\"][^)]*['\"])?\)")


def markdown_files(path: Path):
    if path.is_file():
        yield path
    elif path.is_dir():
        yield from path.rglob("*.md")


def main() -> int:
    errors: list[str] = []
    for scan_root in SCAN_ROOTS:
        for source in markdown_files(scan_root):
            for raw_target in LINK_RE.findall(source.read_text(encoding="utf-8")):
                target = unquote(raw_target)
                parsed = urlparse(target)
                if parsed.scheme or parsed.netloc or target.startswith("#"):
                    continue
                path = (source.parent / parsed.path).resolve()
                try:
                    path.relative_to(ROOT)
                except ValueError:
                    errors.append(f"{source.relative_to(ROOT)}: link escapes repository: {raw_target}")
                    continue
                if not path.exists():
                    errors.append(f"{source.relative_to(ROOT)}: missing target: {raw_target}")

    if errors:
        print("Local documentation links failed:")
        print("\n".join(f"- {error}" for error in errors))
        return 1
    print("Local documentation links: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
