#!/usr/bin/env python3
"""Motion-literal gate for the Compose UI tree (ticket UI-07).

`OeMotion` / `Oe3Motion` is the single source of animation numbers. A page
that writes `tween(220)` or `delay(300)` re-introduces a second timing table
that no token test can see, so this checker fails the build instead.

Rules (scanned in `ui/**/*.kt`, comments stripped):

  * `tween(<number>)`            -> violation, except the reduced-motion
                                     whitelist `tween(0)`.
  * `delay(<number>)`            -> violation (a fixed stagger/settling wait).
  * `spring(...)` / `SpringSpec(...)` with a number -> violation; parameters
                                    must come from a token (`OeMotion.*`).
  * Spring calls passing          -> violation (W1-02): stiffness must come
    `Spring.Stiffness*`             from a token constant, never the
    framework constant              framework's canned values.
  * retired / token-owned motion  -> violation (W1-02): the second-source
    constant declarations           constants retired or re-homed in W1-02
                                    may not be re-declared with numeric
                                    initializers outside the token files.

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

# W1-02: spring() must not hand the framework's canned stiffness values to the
# animator; the number has to flow from an OeMotion token constant.
SPRING_STIFFNESS_RE = re.compile(r"\bSpring\.Stiffness\w+")

# W1-02: constants retired outright this round — any re-declaration is a
# second timing table growing back.
RETIRED_MOTION_CONSTANTS = frozenset({"previewTweenMillis", "ACCORDION_MILLIS"})

# W1-02: press-scale constants re-homed into OeMotion. Declaring them again
# with a numeric initializer outside the token files re-opens the bypass.
TOKEN_OWNED_MOTION_CONSTANTS = frozenset(
    {"CONTROL_PRESS_SCALE", "NAV_PRESS_SCALE", "SLIDER_PRESS_SCALE"}
)

# The single place these token-owned names may carry numeric values.
MOTION_TOKEN_FILE_SUFFIX = "design/tokens/Motion.kt"

NUMBER = r"-?\d+(?:\.\d+)?"
TWEEN_RE = re.compile(rf"\btween\s*\(\s*({NUMBER})\s*[fFlL]?\s*[,)]")
DELAY_RE = re.compile(rf"\bdelay\s*\(\s*({NUMBER})\s*[fFlL]?\s*[,)]")
SPRING_RE = re.compile(r"\b(spring|SpringSpec)(?:\s*<[^<>()]+>)?\s*\(([^()]*)\)", re.DOTALL)
SPRING_ARG_RE = re.compile(rf"(?:^|[,=]\s*)({NUMBER})\s*[fFlL]?\b")
CONST_DECL_RE = re.compile(r"\b(?:const\s+val|val)\s+(\w+)\s*=\s*(.+?)\s*$")
NUMERIC_INIT_RE = re.compile(rf"^{NUMBER}\s*[fFlL]?$")


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
    is_token_file = origin.replace("\\", "/").endswith(MOTION_TOKEN_FILE_SUFFIX)

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
        decl = CONST_DECL_RE.search(line)
        if decl:
            name, init = decl.group(1), decl.group(2)
            if name in RETIRED_MOTION_CONSTANTS:
                violations.append(
                    f"{origin}:{lineno}: retired-constant: {name}"
                )
            elif (
                name in TOKEN_OWNED_MOTION_CONSTANTS
                and not is_token_file
                and NUMERIC_INIT_RE.match(init)
            ):
                violations.append(
                    f"{origin}:{lineno}: second-source-constant: {name}"
                )

    for match in SPRING_RE.finditer(stripped):
        call, args = match.groups()
        lineno = stripped.count("\n", 0, match.start()) + 1
        if SPRING_STIFFNESS_RE.search(args):
            violations.append(
                f"{origin}:{lineno}: spring-stiffness-literal: {call}({args.strip()})"
            )
            continue
        literal = SPRING_ARG_RE.search(args)
        if literal is None:
            continue
        violations.append(
            f"{origin}:{lineno}: spring-literal: {call}({args.strip()})"
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
        # W1-02: spring stiffness must flow from tokens, not framework cans.
        ("val a = spring(dampingRatio = Spring.StiffnessMedium)", 1),
        ("val a = spring(stiffness = OeMotion.PRESS_STIFFNESS)", 0),
        ("val a = spring(dampingRatio = motion.press.dampingRatio, stiffness = Spring.StiffnessMedium)", 1),
        ("val a = SpringSpec<Float>(stiffness = Spring.StiffnessMedium)", 1),
        ("val a = SpringSpec<Float>(stiffness = 400f)", 1),
        ("val a = SpringSpec<Float>(stiffness = OeMotion.PRESS_STIFFNESS)", 0),
        # W1-02: retired second-source constants may not grow back.
        ("val previewTweenMillis = 300", 1),
        ("const val ACCORDION_MILLIS = 220", 1),
        ("val pressScale = OeMotion.CONTROL_PRESS_SCALE", 0),
        # W1-02: token-owned press scales only carry numbers inside Motion.kt.
        ("val CONTROL_PRESS_SCALE = 0.95f", 1),
        ("val CONTROL_PRESS_SCALE = OeMotion.CONTROL_PRESS_SCALE", 0),
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

        # Token-owned names carrying numeric values are legal in the token
        # file itself — simulate the real origin suffix.
        token_case = "val CONTROL_PRESS_SCALE = 0.95f\n"
        found = scan_text(
            token_case,
            origin="app/src/main/java/x/design/tokens/Motion.kt",
        )
        if found:
            failures.append(f"token-file numeric initializer wrongly flagged: {found}")

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
