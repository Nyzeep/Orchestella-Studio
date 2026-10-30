#!/usr/bin/env python3
"""README asset gate: every local image a README shows must exist AND be tracked.

Why this exists
---------------
Session 25 curated six screenshots for the README showcase and added two
`.gitignore` negations (`!/docs/assets/screenshots/*.png|jpg`) for them. The
negations were inert: `/docs/assets/*` excludes the *directory*, git never
descends into an excluded directory, and a file-level negation cannot lift a
parent exclusion. The files stayed untracked, the release page rendered four
broken images, and nothing in CI noticed.

So the gate answers two questions per reference:

1. does the file exist in the working tree?
2. does `git ls-files` know it (i.e. is it really going to be in the pushed tree)?

A README that looks fine locally but ships 404s is exactly the failure this
catches. Standard library only; no network.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable, Sequence

DEFAULT_READMES: tuple[str, ...] = ("README.md", "README.en.md")

# Markdown inline image `![alt](url "title")`, HTML `<img src="...">`, and the
# plain markdown link form used for image files by some editors.
MD_IMAGE_RE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
HTML_IMAGE_RE = re.compile(r"<img\b[^>]*?\bsrc\s*=\s*['\"]([^'\"]+)['\"]", re.IGNORECASE)

REMOTE_PREFIXES = ("http://", "https://", "//", "data:", "mailto:")


def collect_local_images(text: str) -> list[str]:
    """Relative image targets in scan order (markdown first, then HTML), de-duplicated."""
    seen: dict[str, None] = {}
    for match in (*MD_IMAGE_RE.finditer(text), *HTML_IMAGE_RE.finditer(text)):
        target = match.group(1).strip()
        if not target or target.startswith(REMOTE_PREFIXES):
            continue
        target = target.split("#", 1)[0].split("?", 1)[0]
        if not target:
            continue
        seen.setdefault(target.lstrip("./"), None)
    return list(seen)


def resolve_missing(
    refs: Iterable[str], root: Path, tracked: set[str]
) -> list[tuple[str, str]]:
    """(reference, reason) pairs for references that would 404 on GitHub."""
    problems: list[tuple[str, str]] = []
    for ref in refs:
        if not (root / ref).is_file():
            problems.append((ref, "missing from the working tree"))
        elif ref not in tracked:
            problems.append((ref, "not tracked by git (check .gitignore negations)"))
    return problems


def git_tracked_files(root: Path) -> set[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=str(root),
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return {entry for entry in result.stdout.decode("utf-8").split("\0") if entry}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".", help="repository root (default: .)")
    parser.add_argument(
        "--readmes",
        nargs="+",
        default=list(DEFAULT_READMES),
        help="README files to scan, relative to the repo root",
    )
    parser.add_argument("--json", action="store_true", help="machine-readable summary")
    args = parser.parse_args(argv)

    root = Path(args.repo_root).resolve()
    try:
        tracked = git_tracked_files(root)
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        print(f"README_ASSETS_ERROR cannot list git files: {exc}", file=sys.stderr)
        return 2

    scanned = 0
    problems: list[tuple[str, str, str]] = []
    for name in args.readmes:
        readme = root / name
        if not readme.is_file():
            problems.append((name, name, "README file is missing"))
            continue
        scanned += 1
        refs = collect_local_images(readme.read_text(encoding="utf-8"))
        problems.extend((name, ref, reason) for ref, reason in resolve_missing(refs, root, tracked))

    if args.json:
        import json

        print(
            json.dumps(
                {
                    "readmes": args.readmes,
                    "scanned": scanned,
                    "problems": [
                        {"readme": r, "asset": a, "reason": why} for r, a, why in problems
                    ],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        for readme, asset, reason in problems:
            print(f"README_ASSETS_FAIL {readme}: {asset} -> {reason}")
        if not problems:
            print(f"README_ASSETS_OK {scanned} README file(s), every local image exists and is tracked")

    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
