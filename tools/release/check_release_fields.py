#!/usr/bin/env python3
"""Lightweight PR gate for release identity fields.

This does not build an APK. It rejects a defaultConfig that carries an internal
channel suffix, a release versionName, a mismatched versionCode, or a release
workflow that could run on push instead of an explicit dispatch.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

RELEASE_APPLICATION_ID = "io.github.nyzeep.orchestella"
RELEASE_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-(?:preview|beta|rc)\.[0-9]+)?$")


def block_at(text: str, marker: str) -> str:
    start = text.find(marker)
    if start < 0:
        raise ValueError("missing block: " + marker)
    brace = text.find("{", start)
    if brace < 0:
        raise ValueError("missing opening brace for: " + marker)
    depth = 0
    for index in range(brace, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1 : index]
    raise ValueError("unterminated block: " + marker)


def field(block: str, name: str) -> str:
    match = re.search(r"^\s*" + re.escape(name) + r"\s*=\s*\"([^\"]*)\"", block, re.MULTILINE)
    if not match:
        raise ValueError("missing " + name)
    return match.group(1)


def check(root: Path) -> list[str]:
    errors: list[str] = []
    build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    try:
        default_config = block_at(build, "defaultConfig")
        application_id = field(default_config, "applicationId")
        version_name = field(default_config, "versionName")
        version_code_match = re.search(r"^\s*versionCode\s*=\s*([0-9]+)", default_config, re.MULTILINE)
        if not version_code_match:
            raise ValueError("missing versionCode")
        version_code = int(version_code_match.group(1))
    except ValueError as failure:
        return [str(failure)]

    if application_id != RELEASE_APPLICATION_ID:
        errors.append("defaultConfig applicationId is '%s', expected '%s'" % (application_id, RELEASE_APPLICATION_ID))
    if not RELEASE_VERSION_PATTERN.match(version_name):
        errors.append("defaultConfig versionName '%s' is not a release version" % version_name)
    lowered = version_name.lower()
    for marker in ("dev", "qa", "dirty"):
        if marker in lowered:
            errors.append("defaultConfig versionName '%s' contains internal marker '%s'" % (version_name, marker))
    if re.search(r"(?:^|[.-])[0-9a-f]{7,40}(?:[.-]|$)", lowered):
        errors.append("defaultConfig versionName '%s' contains a commit SHA" % version_name)
    parts = re.match(r"^(\d+)\.(\d+)\.(\d+)", version_name)
    if parts:
        expected_code = int(parts.group(1)) * 10000 + int(parts.group(2)) * 100 + int(parts.group(3))
        if version_code != expected_code:
            errors.append(
                "versionCode %d does not equal MAJOR*10000+MINOR*100+PATCH (%d)" % (version_code, expected_code)
            )
    if "applicationIdSuffix" in default_config:
        errors.append("defaultConfig must not carry an applicationIdSuffix; use the build channel")
    if '?: "release"' not in build or "orchestella.buildChannel" not in build:
        errors.append("orchestella.buildChannel must default to release")
    if "orchestella2026" in build:
        errors.append("app/build.gradle.kts still contains the historical fallback signing password")

    workflow = root / ".github/workflows/release.yml"
    if not workflow.is_file():
        errors.append("missing .github/workflows/release.yml")
    else:
        workflow_text = workflow.read_text(encoding="utf-8")
        if "workflow_dispatch" not in workflow_text:
            errors.append("release workflow has no workflow_dispatch trigger")
        if re.search(r"^\s*push\s*:", workflow_text, re.MULTILINE):
            errors.append("release workflow must not trigger on push")
        if "tools/release/verify_apk.py" not in workflow_text:
            errors.append("release workflow does not run the APK gate")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    arguments = parser.parse_args(argv)
    errors = check(arguments.root)
    if errors:
        for error in errors:
            print("FAIL: " + error)
        print("RESULT=FAIL")
        return 1
    print("defaultConfig release identity: PASS")
    print("release workflow dispatch-only gate: PASS")
    print("RESULT=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
