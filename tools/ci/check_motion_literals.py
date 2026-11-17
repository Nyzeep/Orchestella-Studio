#!/usr/bin/env python3
"""Motion-literal gate for the Compose UI tree (ticket UI-07).

`OeMotion` / `Oe3Motion` is the single source of animation numbers. A page
that writes `tween(220)` or `delay(300)` re-introduces a second timing table
that no token test can see, so this checker fails the build instead.

Rules (scanned in `ui/**/*.kt`, comments stripped):

  * `tween(<number>)`            -> violation, except the reduced-motion
                                     whitelist `tween(0)`.
  * `delay(<number>)`            -> violation (a fixed stagger/settling wait).
  * `spring(...)` with a numeric  -> violation; spring parameters must come
    literal argument                from a token (`OeMotion.*`).

Anything that is not a bare numeric literal (a `const val`, a
`OeMotion.XXX`, a function parameter) passes untouched.

Usage:
    python tools/ci/check_motion_literals.py
    python tools/ci/check_motion_literals.py --root <dir> [<dir> ...]
    python tools/ci/check_motion_literals.py --self-test

Standard library only; no network, no Android tooling.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path
from typing import Iterable, Sequence

OK = "MOTION_LITERALS_OK"
FOUND = "MOTION_LITERALS_FOUND"

# `tween(0)` is the documented reduced-motion value (Compose surfaces snap to
# their final state); every other literal is a second duration table.
TWEEN_WHITELIST = frozenset({0.0})

# A zero delay is a coroutine yield, not a hard-coded animation wait.
DELAY_WHITELIST = frozenset({0.0})

NUMBER = r"-?\d+(?:\.\d+)?"
TWEEN_RE = re.compile(rf"\btween\s*\(\s*({NUMBER})\s*[fFlL]?\s*[,)]")
DELAY_RE = re.compile(rf"\bdelay\s*\(\s*({NUMBER})\s*[fFlL]?\s*[,)]")
SPRING_RE = re.compile(r"\bspring\s*\(([^()]*)\)", re.DOTALL)
SPRING_ARG_RE = re.compile(rf"(?:^|[,=]\s*)({NUMBER})\s*[fFlL]?\b")


def strip_comments(text: str) -> str:
    """Blank out // and /* */ comments while preserving line structure."""
    out: list[str] = []
    i = 0
    n = len(text)
    in_line = False
    in_block = False
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if ch == "\n":
                in_line = False
                out.append(ch)
            else:
                out.append(" ")
            i += 1
            continue
        if in_block:
            if ch == "*" and nxt == "/":
                in_block = False
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
            continue
        if ch == "/" and nxt == "/":
            in_line = True
            out.append("  ")
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_block = True
            out.append("  ")
            i += 2
            continue
        if ch == '"':
            # Copy string literals verbatim: a URL's "//" is not a comment.
            out.append(ch)
            i += 1
            while i < n:
                if text[i] == "\\" and i + 1 < n:
                    out.append(text[i : i + 2])
                    i += 2
                    continue
                out.append(text[i])
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def scan_text(text: str, origin: str = "<text>") -> list[str]:
    """Return `origin:line: rule: snippet` violations for one Kotlin source."""
    stripped = strip_comments(text)
    lines = stripped.splitlines()
    violations: list[str] = []

    for lineno, line in enumerate(lines, start=1):
        for match in TWEEN_RE.finditer(line):
            value = float(match.group(1))
            if value in TWEEN_WHITELIST:
                continue
            violations.append(
                f"{origin}:{lineno}: tween-literal: tween({match.group(1)})"
            )
        for match in DELAY_RE.finditer(line):
            value = float(match.group(1))
            if value in DELAY_WHITELIST:
                continue
            violations.append(
                f"{origin}:{lineno}: delay-literal: delay({match.group(1)})"
            )

    for match in SPRING_RE.finditer(stripped):
        args = match.group(1)
        literal = SPRING_ARG_RE.search(args)
        if literal is None:
            continue
        lineno = stripped.count("\n", 0, match.start()) + 1
        violations.append(
            f"{origin}:{lineno}: spring-literal: spring({args.strip()})"
        )
    return violations


def iter_sources(roots: Sequence[Path]) -> Iterable[Path]:
    for root in roots:
        if root.is_file() and root.suffix == ".kt":
            yield root
        elif root.is_dir():
            for path in sorted(root.rglob("*.kt")):
                yield path


def resolve_default_roots() -> list[Path]:
    candidates = [
        Path("app/src/main/java/io/github/nyzeep/orchestella/ui"),
        Path("src/main/java/io/github/nyzeep/orchestella/ui"),
    ]
    for candidate in candidates:
        if candidate.is_dir():
            return [candidate]
    return [candidates[0]]


def run(roots: Sequence[Path]) -> list[str]:
    violations: list[str] = []
    for path in iter_sources(roots):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:  # pragma: no cover - defensive
            violations.append(f"{path}: unreadable: {exc}")
            continue
        violations.extend(scan_text(text, origin=str(path)))
    return violations


def _self_test() -> int:
    cases = [
        # source, expected violation count
        ("val a = tween(220)", 1),
        ("val a = tween(0)", 0),
        ("val a = tween(durationMillis = 0)", 0),
        ("val a = tween(OeMotion.PAGE_FADE_MS)", 0),
        ("val a = tween(ThemeStudioSpec.previewTweenMillis)", 0),
        ("val a = tween(220f)", 1),
        ("delay(300) { work() }", 1),
        ("delay(0)", 0),
        ("delay(metadata.pollMs)", 0),
        ("val a = spring(dampingRatio = 0.6f)", 1),
        ("val a = spring(stiffness = OeMotion.NAV_SELECTION_STIFFNESS)", 0),
        ("val a = spring(dampingRatio = motion.press.dampingRatio)", 0),
        ("val a = spring(0.85f, 400f)", 1),
        ("// tween(220) in a comment is documentation, not code", 0),
        ("/* delay(300) */ val a = 1", 0),
        ('val url = "https://example.com" // tween(9)', 0),
        ("val a = tween(220) // literal on a real call", 1),
    ]

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="motion-literals-self-test-") as raw:
        tmp = Path(raw)
        for index, (source, expected) in enumerate(cases):
            path = tmp / f"case{index}.kt"
            path.write_text(source + "\n", encoding="utf-8")
            found = scan_text(path.read_text(encoding="utf-8"), origin=path.name)
            if len(found) != expected:
                failures.append(
                    f"case {index} {source!r}: expected {expected}, got {len(found)} -> {found}"
                )

    if failures:
        print("MOTION_LITERALS_SELF_TEST_FAILED")
        for failure in failures:
            print(f"  {failure}")
        return 1
    print("MOTION_LITERALS_SELF_TEST_OK")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "roots",
        nargs="*",
        help="Files or directories to scan (default: the app's ui/ tree)",
    )
    parser.add_argument(
        "--root",
        action="append",
        default=[],
        help="Additional scan root (repeatable)",
    )
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()

    roots = [Path(item) for item in list(args.roots) + list(args.root)]
    if not roots:
        roots = resolve_default_roots()

    violations = run(roots)
    if violations:
        print(FOUND)
        for violation in violations:
            print(f"  {violation}")
        print(
            f"  {len(violations)} violation(s): use OeMotion / Oe3Motion tokens "
            "(or LocalOe3Motion) instead of literal durations."
        )
        return 1

    scanned = sum(1 for _ in iter_sources(roots))
    print(f"{OK} ({scanned} Kotlin file(s) scanned)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
