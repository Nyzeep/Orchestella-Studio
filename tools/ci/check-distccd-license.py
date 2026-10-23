#!/usr/bin/env python3
"""Audit the distccd ARM64 artifact's license and NOTICE surface.

Checks that:
  * the SBOM declares distcc as GPL-2.0-or-later and pins both checksums;
  * NOTICE-fastlane.md has a distcc 3.4 section with the pinned source,
    corresponding-source delivery and a three-year written-offer fallback;
  * the build output is git-ignored and no binary is tracked.

Standard library only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

SOURCE_SHA256 = "2b99edda9dad9dbf283933a02eace6de7423fe5650daa4a728c950e5cd37bd7d"
REQUIRED_NOTICE_TEXT = (
    "### distcc 3.4",
    "GPL-2.0-or-later",
    SOURCE_SHA256,
    "corresponding source",
    "three years",
    "not-tested",
)


def fail(problems: list[str], message: str) -> None:
    problems.append(message)


def check_sbom(artifact: Path, problems: list[str]) -> None:
    path = artifact / "SBOM.spdx.json"
    if not path.is_file():
        fail(problems, f"missing SBOM: {path}")
        return
    document = json.loads(path.read_text(encoding="utf-8"))
    packages = {package.get("name"): package for package in document.get("packages", [])}
    source = packages.get("distcc-source")
    binary = packages.get("distccd-android-arm64")
    if source is None:
        fail(problems, "SBOM has no distcc-source package")
    else:
        if source.get("licenseConcluded") != "GPL-2.0-or-later":
            fail(problems, "distcc-source license is not GPL-2.0-or-later")
        if SOURCE_SHA256 not in {
            checksum.get("checksumValue") for checksum in source.get("checksums", [])
        }:
            fail(problems, "distcc-source checksum does not match the pinned tarball")
    if binary is None:
        fail(problems, "SBOM has no distccd-android-arm64 package")
        return
    if binary.get("licenseConcluded") != "GPL-2.0-or-later":
        fail(problems, "distccd binary license is not GPL-2.0-or-later")
    binary_path = artifact / "bin" / "distccd"
    if not binary_path.is_file():
        fail(problems, f"missing binary: {binary_path}")
        return
    digest = hashlib.sha256(binary_path.read_bytes()).hexdigest()
    if digest not in {c.get("checksumValue") for c in binary.get("checksums", [])}:
        fail(problems, "SBOM binary checksum does not match bin/distccd")


def check_notice(notice: Path, problems: list[str]) -> None:
    if not notice.is_file():
        fail(problems, f"missing NOTICE: {notice}")
        return
    text = notice.read_text(encoding="utf-8")
    for required in REQUIRED_NOTICE_TEXT:
        if required not in text:
            fail(problems, f"NOTICE-fastlane.md is missing: {required}")


def check_git(root: Path, problems: list[str]) -> None:
    tracked = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--", "build/fastlane-distccd"],
        check=False,
        capture_output=True,
        text=True,
    )
    if tracked.returncode != 0:
        fail(problems, "git ls-files failed")
    elif tracked.stdout.strip():
        fail(problems, "build/fastlane-distccd contains tracked files")
    ignored = subprocess.run(
        ["git", "-C", str(root), "check-ignore", "-q", "build/fastlane-distccd/bin/distccd"],
        check=False,
    )
    if ignored.returncode != 0:
        fail(problems, "build/fastlane-distccd/bin/distccd is not git-ignored")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", default="build/fastlane-distccd")
    parser.add_argument("--notice", default="NOTICE-fastlane.md")
    parser.add_argument("--root", default=".")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    problems: list[str] = []
    check_sbom(Path(args.artifact), problems)
    check_notice(root / args.notice, problems)
    check_git(root, problems)
    if problems:
        for problem in problems:
            print(f"FAIL {problem}", file=sys.stderr)
        return 1
    print("DISTCCD_LICENSE_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
