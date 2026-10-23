#!/usr/bin/env python3
"""Offline Microdroid execute_no_trans gate checker.

Input is one or more minimal SELinux .te fixtures (plus a global_macros file
when needed). The checker answers one question: does the protected payload
domain microdroid_app, directly or through its coredomain/domain inheritance,
hold execute_no_trans on the payload file type?

It never reads the network, never invokes selinux tooling and never prints a
raw policy line. Output is GATE_OPEN / GATE_CLOSED / GATE_UNCLEAR plus the
input line numbers used as evidence.

Standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence

GATE_OPEN = "GATE_OPEN"
GATE_CLOSED = "GATE_CLOSED"
GATE_UNCLEAR = "GATE_UNCLEAR"

PAYLOAD_DOMAIN = "microdroid_app"
DEFAULT_PAYLOAD_TYPE = "system_file"
FILE_CLASS = "file"

MACRO_RE = re.compile(r"define\(\`([^']+)',\s*\`(.*?)'\)", re.DOTALL)
COMMENT_SAFE = re.compile(r"[^#\n]*")
TOKEN_RE = re.compile(r"[A-Za-z0-9_.-]+|\{|\}|:|,")

KNOWN_PERMS = frozenset(
    {
        "append",
        "audit_access",
        "create",
        "entrypoint",
        "execute",
        "execute_no_trans",
        "getattr",
        "ioctl",
        "link",
        "lock",
        "map",
        "mounton",
        "open",
        "quotaon",
        "read",
        "relabelfrom",
        "relabelto",
        "rename",
        "search",
        "setattr",
        "swapon",
        "unlink",
        "watch",
        "write",
        "add_name",
        "remove_name",
        "reparent",
        "rmdir",
        "fork",
        "transition",
        "sigchld",
        "signull",
        "signal",
        "ptrace",
        "getsched",
        "setsched",
        "share",
        "execheap",
        "execstack",
        "setcurrent",
        "setexec",
        "nosuid_transition",
    }
)


class PolicyError(Exception):
    """A fixed, path-free error code."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class Evidence:
    path: str
    line: int
    role: str


@dataclass(frozen=True)
class Evaluation:
    gate: str
    evidence: tuple[Evidence, ...]
    reason: str


@dataclass(frozen=True)
class FixtureResult:
    cases: dict[str, Evaluation]
    mismatches: dict[str, tuple[str, str]]


@dataclass
class Rule:
    kind: str
    source: str
    target: str
    tclass: str
    perms: frozenset[str]
    unresolved: bool
    evidence: Evidence


@dataclass
class ParsedPolicy:
    type_attrs: dict[str, set[str]]
    macros: dict[str, str]
    rules: list[Rule]
    declaration_evidence: dict[str, Evidence]


def _strip_comments(text: str) -> str:
    lines: list[str] = []
    for line in text.splitlines():
        output: list[str] = []
        in_quote = False
        in_backtick = False
        for char in line:
            if char == '"' and not in_backtick:
                in_quote = not in_quote
            elif char == "`" and not in_quote:
                in_backtick = not in_backtick
            elif char == "#" and not in_quote and not in_backtick:
                break
            output.append(char)
        lines.append("".join(output))
    return "\n".join(lines)


def _statements(text: str) -> list[tuple[int, str]]:
    """Split on semicolons across physical lines; keep the first line number."""
    result: list[tuple[int, str]] = []
    buffer: list[str] = []
    line = 1
    start = 1
    for char in text:
        if char == ";":
            if "".join(buffer).strip():
                result.append((start, "".join(buffer)))
            buffer = []
            start = line
            continue
        if not buffer and char.isspace():
            if char == "\n":
                line += 1
            continue
        if not buffer:
            start = line
        buffer.append(char)
        if char == "\n":
            line += 1
    if "".join(buffer).strip():
        result.append((start, "".join(buffer)))
    return result


def _tokenize(statement: str) -> list[str]:
    return TOKEN_RE.findall(statement)


def _macro_tokens(body: str) -> list[str]:
    return [token for token in _tokenize(body) if token not in ("{", "}", ":", ",")]


def _expand_perms(
    tokens: Sequence[str], macros: dict[str, str], seen: frozenset[str] = frozenset()
) -> tuple[frozenset[str], bool]:
    perms: set[str] = set()
    unresolved = False
    for token in tokens:
        if token in ("{", "}", ":", ","):
            continue
        if token in macros:
            if token in seen:
                unresolved = True
                continue
            expanded, failed = _expand_perms(
                _macro_tokens(macros[token]), macros, seen | {token}
            )
            perms.update(expanded)
            unresolved = unresolved or failed
            continue
        if token.endswith("_perms"):
            unresolved = True
            continue
        if token in KNOWN_PERMS:
            perms.add(token)
    return frozenset(perms), unresolved


def _read_policy(path: Path, parsed: ParsedPolicy) -> tuple[str, str]:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise PolicyError("POLICY_FILE_UNREADABLE") from error

    # Macro definitions are removed before statement splitting because their
    # bodies contain braces, commas and semicolons.
    def take_macro(match: re.Match[str]) -> str:
        parsed.macros[match.group(1)] = match.group(2)
        return " "

    return path.name, _strip_comments(MACRO_RE.sub(take_macro, text))


def _parse_text(display: str, text: str, parsed: ParsedPolicy) -> None:
    for number, statement in _statements(text):
        tokens = _tokenize(statement)
        if not tokens:
            continue
        evidence = Evidence(display, number, "rule")
        head = tokens[0]
        if head == "type" and len(tokens) >= 2:
            name = tokens[1]
            attrs = {token for token in tokens[2:] if token not in (",", "{", "}")}
            parsed.type_attrs.setdefault(name, set()).update(attrs)
            parsed.declaration_evidence.setdefault(name, evidence)
        elif head == "typeattribute" and len(tokens) >= 3:
            parsed.type_attrs.setdefault(tokens[1], set()).update(tokens[2:])
        elif head in ("allow", "neverallow") and ":" in tokens:
            colon = tokens.index(":")
            if colon < 3 or colon + 1 >= len(tokens):
                continue
            source = tokens[1]
            target = tokens[2]
            tclass = tokens[colon + 1]
            perms, unresolved = _expand_perms(tokens[colon + 2 :], parsed.macros)
            parsed.rules.append(
                Rule(
                    kind=head,
                    source=source,
                    target=target,
                    tclass=tclass,
                    perms=perms,
                    unresolved=unresolved,
                    evidence=evidence,
                )
            )


def _parse(paths: Sequence[Path]) -> ParsedPolicy:
    parsed = ParsedPolicy({}, {}, [], {})
    texts: list[tuple[str, str]] = []
    for path in paths:
        if not path.is_file():
            raise PolicyError("POLICY_FILE_MISSING")
        texts.append(_read_policy(path, parsed))
    for display, text in texts:
        _parse_text(display, text, parsed)
    return parsed


def _expand_type(name: str, type_attrs: dict[str, set[str]]) -> set[str]:
    expanded = {name}
    expanded.update(type_attrs.get(name, set()))
    return expanded


def _relevant_evidence(parsed: ParsedPolicy, payload_type: str) -> list[Evidence]:
    evidence: list[Evidence] = []
    for rule in parsed.rules:
        if rule.kind != "allow" or rule.tclass != FILE_CLASS:
            continue
        if rule.source not in _expand_type(PAYLOAD_DOMAIN, parsed.type_attrs):
            continue
        if rule.target not in _expand_type(payload_type, parsed.type_attrs):
            continue
        if "execute" in rule.perms and "execute_no_trans" not in rule.perms:
            evidence.append(rule.evidence)
    for name in (PAYLOAD_DOMAIN, payload_type):
        declaration = parsed.declaration_evidence.get(name)
        if declaration is not None:
            evidence.append(declaration)
    return _dedupe(evidence)


def _dedupe(items: Iterable[Evidence]) -> list[Evidence]:
    seen: set[tuple[str, int]] = set()
    result: list[Evidence] = []
    for item in sorted(items, key=lambda entry: (entry.path, entry.line)):
        key = (item.path, item.line)
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


def evaluate(
    paths: Sequence[Path], payload_type: str = DEFAULT_PAYLOAD_TYPE
) -> Evaluation:
    parsed = _parse(paths)
    if not parsed.rules and not parsed.type_attrs:
        raise PolicyError("POLICY_EMPTY")

    domain_types = _expand_type(PAYLOAD_DOMAIN, parsed.type_attrs)
    payload_types = _expand_type(payload_type, parsed.type_attrs)

    # A policy excerpt must at least identify the domain and payload type;
    # otherwise absence of an allow rule is not evidence of a closed gate.
    domain_declared = PAYLOAD_DOMAIN in parsed.type_attrs
    payload_declared = payload_type in parsed.type_attrs
    domain_mentioned = any(rule.source in domain_types for rule in parsed.rules)
    payload_mentioned = any(rule.target in payload_types for rule in parsed.rules)
    if not domain_declared and not domain_mentioned:
        return Evaluation(GATE_UNCLEAR, (), "domain undeclared")
    if not payload_declared and not payload_mentioned:
        return Evaluation(GATE_UNCLEAR, (), "payload type undeclared")

    allow_hits: list[Evidence] = []
    never_hits: list[Evidence] = []
    unresolved_hits: list[Evidence] = []
    for rule in parsed.rules:
        if rule.tclass != FILE_CLASS:
            continue
        if rule.source not in domain_types or rule.target not in payload_types:
            continue
        if rule.unresolved and ("execute_no_trans" in rule.perms or not rule.perms):
            unresolved_hits.append(rule.evidence)
        if "execute_no_trans" not in rule.perms:
            continue
        if rule.kind == "allow":
            allow_hits.append(rule.evidence)
        elif rule.kind == "neverallow":
            never_hits.append(rule.evidence)

    if never_hits:
        return Evaluation(
            GATE_UNCLEAR, tuple(_dedupe(never_hits + allow_hits)), "allow/neverallow conflict"
        )
    if unresolved_hits and not allow_hits:
        return Evaluation(GATE_UNCLEAR, tuple(_dedupe(unresolved_hits)), "unresolved macro")
    if allow_hits:
        return Evaluation(GATE_OPEN, tuple(_dedupe(allow_hits)), "execute_no_trans granted")
    return Evaluation(
        GATE_CLOSED,
        tuple(_relevant_evidence(parsed, payload_type)),
        "execute_no_trans not granted",
    )


def _case_files(directory: Path) -> list[Path]:
    files = sorted(directory.glob("*.te"))
    macros = directory / "global_macros"
    if macros.is_file():
        files.append(macros)
    return files


def evaluate_fixture_root(root: Path) -> FixtureResult:
    expectations_path = root / "EXPECTATIONS.json"
    if not expectations_path.is_file():
        raise PolicyError("EXPECTATIONS_MISSING")
    try:
        expectations = json.loads(expectations_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PolicyError("EXPECTATIONS_INVALID") from error
    if not isinstance(expectations, dict) or not expectations:
        raise PolicyError("EXPECTATIONS_INVALID")

    cases: dict[str, Evaluation] = {}
    mismatches: dict[str, tuple[str, str]] = {}
    for name in sorted(expectations):
        directory = root / name
        if not directory.is_dir():
            raise PolicyError("FIXTURE_CASE_MISSING")
        files = _case_files(directory)
        if not files:
            raise PolicyError("FIXTURE_CASE_EMPTY")
        evaluation = evaluate(files)
        cases[name] = evaluation
        expected = str(expectations[name])
        if evaluation.gate != expected:
            mismatches[name] = (expected, evaluation.gate)
    return FixtureResult(cases, mismatches)


def _format_evidence(evidence: Sequence[Evidence]) -> str:
    if not evidence:
        return "-"
    return ",".join(f"{item.path}:{item.line}" for item in evidence)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="SELinux .te fixture paths")
    parser.add_argument(
        "--policy-fixtures",
        type=Path,
        help="fixture root with EXPECTATIONS.json and one directory per case",
    )
    parser.add_argument("--payload-type", default=DEFAULT_PAYLOAD_TYPE)
    args = parser.parse_args(argv)

    try:
        if args.policy_fixtures is not None:
            result = evaluate_fixture_root(args.policy_fixtures)
            counts = {GATE_OPEN: 0, GATE_CLOSED: 0, GATE_UNCLEAR: 0}
            for name, evaluation in result.cases.items():
                counts[evaluation.gate] += 1
                print(
                    f"CASE {name} {evaluation.gate} "
                    f"evidence={_format_evidence(evaluation.evidence)}"
                )
            for name, (expected, actual) in result.mismatches.items():
                print(f"MISMATCH {name} expected={expected} actual={actual}")
            print(
                "GATE_SUMMARY "
                f"open={counts[GATE_OPEN]} closed={counts[GATE_CLOSED]} "
                f"unclear={counts[GATE_UNCLEAR]}"
            )
            return 0 if not result.mismatches else 1

        if not args.paths:
            parser.error("provide .te paths or --policy-fixtures")
        evaluation = evaluate(args.paths, payload_type=args.payload_type)
        print(f"{evaluation.gate} evidence={_format_evidence(evaluation.evidence)}")
        return 0 if evaluation.gate != GATE_UNCLEAR else 1
    except PolicyError as error:
        print(f"ERROR={error.code}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
