#!/usr/bin/env python3
"""License and notice completeness gate for the sealed toolbox.

This is a policy gate, not legal advice. It fails closed when an artifact has
no license, an unapproved license, no pinned SHA256, or when a GPL-family
artifact has no corresponding-source pointer.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ALLOWED_LICENSES = {
    "Apache-2.0",
    "Apache-2.0 WITH LLVM-exception",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "CC0-1.0",
    "EPL-2.0",
    "GPL-2.0-only",
    "GPL-2.0-or-later",
    "GPL-3.0-only",
    "GPL-3.0-or-later",
    "LGPL-2.1-only",
    "LGPL-2.1-or-later",
    "LGPL-3.0-only",
    "LGPL-3.0-or-later",
    "MIT",
    "MPL-2.0",
}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def _load(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def check(manifest_path: Path, notice_path: Path, sbom_path: Path) -> list[str]:
    problems: list[str] = []
    try:
        document = _load(manifest_path)
        artifacts = document.get("artifacts") or []
        if not artifacts:
            return [f"manifest has no artifacts: {manifest_path}"]
        notice = Path(notice_path).read_text(encoding="utf-8").lower()
        sbom = _load(sbom_path)
    except (OSError, json.JSONDecodeError) as failure:
        return [f"cannot read license inputs: {failure}"]

    sbom_names = {
        str(package.get("name", "")).lower()
        for package in sbom.get("packages", [])
        if isinstance(package, dict)
    }
    for artifact in artifacts:
        name = str(artifact.get("name", "")).lower()
        if not name:
            problems.append("artifact has no name")
            continue
        license_name = artifact.get("license")
        if not license_name:
            problems.append(f"{name}: missing license")
        elif license_name not in ALLOWED_LICENSES:
            problems.append(f"{name}: license not on the allowlist: {license_name}")
        if not artifact.get("source"):
            problems.append(f"{name}: missing source URL")
        sha = str(artifact.get("sha256", ""))
        if not SHA256_RE.match(sha):
            problems.append(f"{name}: missing or malformed sha256")
        if artifact.get("member") and not SHA256_RE.match(str(artifact.get("binarySha256", ""))):
            problems.append(f"{name}: missing or malformed binarySha256")
        if str(license_name or "").startswith(("GPL", "LGPL")):
            source = artifact.get("correspondingSource")
            if not source:
                problems.append(f"{name}: GPL-family artifact has no correspondingSource")
            elif not source.get("upstream") or not source.get("upstreamSha256"):
                problems.append(f"{name}: correspondingSource lacks upstream pin")
        if name not in notice:
            problems.append(f"{name}: not named in NOTICE ({notice_path})")
        if name not in sbom_names:
            problems.append(f"{name}: not listed in SBOM ({sbom_path})")
    return problems


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default="fastlane-toolbox/manifest.json")
    parser.add_argument("--notice", default="NOTICE-fastlane.md")
    parser.add_argument("--sbom", default="fastlane-toolbox/SBOM.spdx.json")
    args = parser.parse_args(argv)
    problems = check(Path(args.manifest), Path(args.notice), Path(args.sbom))
    if problems:
        for problem in problems:
            print(f"FAIL {problem}", file=sys.stderr)
        return 1
    print("LICENSE_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
