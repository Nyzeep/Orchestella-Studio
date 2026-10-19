#!/usr/bin/env python3
"""Check release licence, NOTICE and corresponding-source consistency.

Standard library only. Run from the repository root:

    python3 tools/release/check_licenses.py
    python3 tools/release/check_licenses.py --root /path/to/checkout

Exit status is 0 only when every check passes.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

PROJECT_LICENSE_MARKERS = (
    "GNU GENERAL PUBLIC LICENSE",
    "Version 3, 29 June 2007",
)

REQUIRED_FILES = (
    "LICENSE",
    "guest-assets/SOURCE-OFFER.txt",
    "tools/qemu-android/gpl/QEMU-GPL-SOURCES.md",
    "app/src/main/assets/NOTICE.GUEST-RUNTIME",
    "app/src/main/assets/NOTICE.QEMU",
)

REFERENCE_DOCS = (
    "app/src/main/assets/NOTICE.QEMU",
    "app/src/main/assets/NOTICE.GUEST-RUNTIME",
    "app/src/main/assets/NOTICE.PHASE5",
    "app/src/main/assets/QEMU-GPL-SOURCES.md",
    "guest-assets/GPL-SOURCES.md",
    "guest-assets/SOURCE-OFFER.txt",
    "tools/qemu-android/gpl/QEMU-GPL-SOURCES.md",
    "tools/qemu-android/gpl/NOTICE.QEMU",
)

REFERENCE_RE = re.compile(
    r"(?<![\w/.\-])((?:app/src/main/assets|guest-assets|tools|docs)/[A-Za-z0-9._/\-]+)"
)
MUSL_MENTION_RE = re.compile(r"\bmusl\s+(\d+\.\d+\.\d+)(?:-r(\d+))?", re.IGNORECASE)
VERSION_RE = re.compile(r"^(\d+\.\d+\.\d+)(?:-r(\d+))?$")

MANIFEST_REL = "tools/release/release_bundle_manifest.json"
MANIFEST_REQUIRED_BASENAMES = (
    "LICENSE",
    "SOURCE-OFFER.txt",
    "QEMU-GPL-SOURCES.md",
    "LICENSE.GPL-2.0",
    "NOTICE.QEMU",
    "NOTICE.GUEST-RUNTIME",
)


class Checker:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.passed = 0
        self.failed = 0

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        if ok:
            self.passed += 1
            print(f"PASS  {name}")
        else:
            self.failed += 1
            suffix = f": {detail}" if detail else ""
            print(f"FAIL  {name}{suffix}")
        return ok

    def path(self, rel: str) -> Path:
        return self.root / rel

    def exists(self, rel: str) -> bool:
        return self.path(rel).exists()

    def read(self, rel: str) -> str:
        return self.path(rel).read_text(encoding="utf-8", errors="replace")


def split_version(value: str) -> tuple[str, str | None] | None:
    match = VERSION_RE.match(value.strip())
    if not match:
        return None
    return match.group(1), match.group(2)


def check_root_license(checker: Checker) -> None:
    rel = "LICENSE"
    if not checker.check("root LICENSE exists", checker.exists(rel)):
        return
    text = checker.read(rel)
    missing = [marker for marker in PROJECT_LICENSE_MARKERS if marker not in text]
    checker.check(
        "root LICENSE is the GNU GPL version 3 text",
        not missing,
        f"missing {missing}",
    )


def check_required_files(checker: Checker) -> None:
    for rel in REQUIRED_FILES:
        checker.check(f"required file exists: {rel}", checker.exists(rel))


def load_agent_musl_version(checker: Checker, rel: str) -> tuple[str, str | None] | None:
    if not checker.exists(rel):
        checker.check("guest-agent input lock is readable", False, f"missing {rel}")
        return None
    try:
        data = json.loads(checker.read(rel))
    except (json.JSONDecodeError, OSError) as exc:
        checker.check("guest-agent input lock is valid JSON", False, str(exc))
        return None
    packages = {
        package.get("name"): package.get("version")
        for package in data.get("provenance", {}).get("packages", [])
    }
    raw = packages.get("musl")
    if not raw:
        checker.check(
            "guest-agent input lock pins musl",
            False,
            "provenance.packages has no musl entry",
        )
        return None
    parsed = split_version(raw)
    if parsed is None:
        checker.check(
            "guest-agent input lock musl version is parseable",
            False,
            f"unexpected version {raw!r}",
        )
        return None
    return parsed


def check_notice_musl(checker: Checker) -> None:
    lock_rel = "tools/phase5/guest-agent-inputs.lock.json"
    notice_rel = "app/src/main/assets/NOTICE.GUEST-RUNTIME"
    expected = load_agent_musl_version(checker, lock_rel)
    if expected is None or not checker.exists(notice_rel):
        if not checker.exists(notice_rel):
            checker.check("NOTICE.GUEST-RUNTIME exists", False, f"missing {notice_rel}")
        return

    text = checker.read(notice_rel)
    expected_base, expected_revision = expected
    mentions = MUSL_MENTION_RE.findall(text)
    if not checker.check(
        "NOTICE.GUEST-RUNTIME names a musl version",
        bool(mentions),
        "no 'musl X.Y.Z' mention found",
    ):
        return

    exact = any(
        base == expected_base and revision == (expected_revision or "")
        for base, revision in mentions
    )
    checker.check(
        "NOTICE.GUEST-RUNTIME musl package matches the guest-agent lock",
        exact,
        f"expected musl {expected_base}"
        + (f"-r{expected_revision}" if expected_revision else "")
        + f", found {mentions}",
    )
    conflicting = [
        (base, revision)
        for base, revision in mentions
        if base != expected_base or (revision and revision != expected_revision)
    ]
    checker.check(
        "NOTICE.GUEST-RUNTIME has no conflicting musl version",
        not conflicting,
        f"conflicting mentions {conflicting}",
    )
    checker.check(
        "NOTICE.GUEST-RUNTIME cites the agent build-input lock",
        lock_rel in text,
        f"{lock_rel} is not referenced",
    )
    checker.check(
        "NOTICE.GUEST-RUNTIME separates the initramfs musl pin",
        "guest-assets/inputs/inputs.lock" in text
        and "MUSL_VERSION=1.2.5" in text,
        "agent and initramfs musl pins are not distinguished",
    )


def check_gpl_sources_musl(checker: Checker) -> None:
    lock_rel = "guest-assets/inputs/inputs.lock"
    manifest_rel = "guest-assets/GPL-SOURCES.md"
    if not checker.check(
        "guest-assets inputs.lock exists", checker.exists(lock_rel)
    ):
        return
    lock_text = checker.read(lock_rel)
    match = re.search(r"^MUSL_VERSION=(\S+)\s*$", lock_text, re.MULTILINE)
    if not match:
        checker.check(
            "inputs.lock defines MUSL_VERSION", False, "MUSL_VERSION not found"
        )
        return
    lock_version = match.group(1)
    if not checker.check("GPL-SOURCES.md exists", checker.exists(manifest_rel)):
        return
    text = checker.read(manifest_rel)
    section_match = re.search(
        r"^##\s+musl[^\n]*\n(.*?)(?=^##\s|\Z)", text, re.MULTILINE | re.DOTALL
    )
    if not section_match:
        checker.check(
            "GPL-SOURCES.md has a musl section", False, "no '## musl' heading"
        )
        return
    section = section_match.group(1)
    listed_versions = re.findall(r"Version:\s*`?([^`\s]+)`?", section)
    archive_versions = re.findall(r"musl-(\d+\.\d+\.\d+)\.tar", section)
    ok = (
        lock_version in listed_versions
        and listed_versions
        and all(version == lock_version for version in listed_versions)
        and all(version == lock_version for version in archive_versions)
    )
    checker.check(
        "GPL-SOURCES.md initramfs musl matches inputs.lock",
        bool(ok),
        f"inputs.lock MUSL_VERSION={lock_version}, "
        f"manifest versions={listed_versions}, archive versions={archive_versions}",
    )
    checker.check(
        "GPL-SOURCES.md cites the initramfs input lock",
        lock_rel in text,
        f"{lock_rel} is not referenced",
    )
    checker.check(
        "GPL-SOURCES.md states the project GPL-3.0-or-later licence",
        "GPL-3.0-or-later" in text
        and "repository's Apache-2.0 license" not in text,
        "project code licence statement is missing or still Apache-2.0",
    )


PROJECT_SPDX_FILES = (
    "guest-assets/display/display-relay.py",
    "guest-assets/initramfs/init",
)


def check_phase5_notice(checker: Checker) -> None:
    rel = "app/src/main/assets/NOTICE.PHASE5"
    if not checker.check(f"Phase 5 notice exists: {rel}", checker.exists(rel)):
        return
    text = checker.read(rel)
    checker.check(
        "NOTICE.PHASE5 states the project GPL-3.0-or-later licence",
        "GPL-3.0-or-later" in text and "under Apache License 2.0" not in text,
        "project code is still labelled Apache-2.0",
    )


def check_project_spdx(checker: Checker) -> None:
    for rel in PROJECT_SPDX_FILES:
        if not checker.check(f"project source exists: {rel}", checker.exists(rel)):
            continue
        text = checker.read(rel)
        checker.check(
            f"project source carries GPL-3.0-or-later SPDX: {rel}",
            "SPDX-License-Identifier: GPL-3.0-or-later" in text
            and "SPDX-License-Identifier: Apache-2.0" not in text,
            "expected GPL-3.0-or-later SPDX and no Apache-2.0 SPDX",
        )


def extract_references(text: str) -> set[str]:
    references: set[str] = set()
    for match in REFERENCE_RE.finditer(text):
        reference = match.group(1).rstrip(".,;:)")
        if reference and not reference.endswith("//"):
            references.add(reference)
    return references


def check_references(checker: Checker) -> None:
    missing: dict[str, list[str]] = {}
    for rel in REFERENCE_DOCS:
        if not checker.exists(rel):
            continue
        for reference in sorted(extract_references(checker.read(rel))):
            if not checker.exists(reference):
                missing.setdefault(rel, []).append(reference)
    detail = "; ".join(
        f"{doc}: {', '.join(refs)}" for doc, refs in sorted(missing.items())
    )
    checker.check(
        "all repository-relative paths cited by NOTICE/GPL records exist",
        not missing,
        detail,
    )


def check_release_manifest(checker: Checker) -> None:
    if not checker.check(
        "release bundle manifest exists", checker.exists(MANIFEST_REL)
    ):
        return
    try:
        data = json.loads(checker.read(MANIFEST_REL))
    except json.JSONDecodeError as exc:
        checker.check("release bundle manifest is valid JSON", False, str(exc))
        return
    files = data.get("files")
    if not isinstance(files, list) or not all(isinstance(item, str) for item in files):
        checker.check(
            "release bundle manifest lists file paths",
            False,
            "'files' must be a list of repository-relative strings",
        )
        return
    checker.check(
        "release bundle manifest lists at least one file", bool(files), "empty list"
    )
    missing = [rel for rel in files if not checker.exists(rel)]
    checker.check(
        "every release bundle manifest file exists",
        not missing,
        f"missing {missing}",
    )
    basenames = {Path(rel).name for rel in files}
    for name in MANIFEST_REQUIRED_BASENAMES:
        checker.check(
            f"release bundle manifest includes {name}",
            name in basenames,
            f"{name} not listed",
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        default=str(Path(__file__).resolve().parents[2]),
        help="repository root to check (default: the checkout containing this script)",
    )
    args = parser.parse_args(argv)

    checker = Checker(Path(args.root).resolve())
    print(f"License consistency check: {checker.root}")
    check_root_license(checker)
    check_required_files(checker)
    check_notice_musl(checker)
    check_gpl_sources_musl(checker)
    check_project_spdx(checker)
    check_phase5_notice(checker)
    check_references(checker)
    check_release_manifest(checker)

    print(f"SUMMARY: {checker.passed} passed, {checker.failed} failed")
    return 0 if checker.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
