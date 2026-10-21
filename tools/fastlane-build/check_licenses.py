#!/usr/bin/env python3
"""License, notice and SBOM completeness gate for the sealed toolbox.

This is a policy gate, not legal advice. It fails closed when an artifact has
no license, an unapproved license, no pinned SHA256, or when a GPL-family
artifact has no corresponding-source pointer. It also cross-checks the SBOM
and NOTICE against the manifest instead of treating them as free-form prose:
a stale checksum, version or package entry fails the gate.
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
HTTPS_RE = re.compile(r"^https://[^\s]+$")


def _load(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _sbom_packages(sbom: dict) -> dict[str, dict]:
    packages = sbom.get("packages")
    if not isinstance(packages, list):
        return {}
    indexed: dict[str, dict] = {}
    for package in packages:
        if not isinstance(package, dict):
            continue
        name = str(package.get("name", "")).lower()
        if name:
            indexed[name] = package
    return indexed


def _checksums(package: dict) -> set[tuple[str, str]]:
    values: set[tuple[str, str]] = set()
    for checksum in package.get("checksums") or []:
        if isinstance(checksum, dict):
            values.add(
                (
                    str(checksum.get("algorithm", "")).upper(),
                    str(checksum.get("checksumValue", "")).lower(),
                )
            )
    return values


def _external_refs(package: dict) -> dict[str, str]:
    refs: dict[str, str] = {}
    for reference in package.get("externalRefs") or []:
        if not isinstance(reference, dict):
            continue
        kind = str(reference.get("referenceType", ""))
        if kind:
            refs[kind] = str(reference.get("referenceLocator", ""))
    return refs


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

    if not notice.strip():
        problems.append(f"NOTICE is empty: {notice_path}")
    if sbom.get("spdxVersion") != "SPDX-2.3":
        problems.append("SBOM must declare spdxVersion SPDX-2.3")
    if not str(sbom.get("documentNamespace", "")).startswith("https://"):
        problems.append("SBOM documentNamespace must be an https URL")
    if not isinstance(sbom.get("creationInfo"), dict):
        problems.append("SBOM has no creationInfo")
    packages = _sbom_packages(sbom)
    described = {
        str(relation.get("relatedSpdxElement"))
        for relation in sbom.get("relationships") or []
        if isinstance(relation, dict) and relation.get("relationshipType") == "DESCRIBES"
    }
    artifact_names = {str(artifact.get("name", "")).lower() for artifact in artifacts}

    for artifact in artifacts:
        name = str(artifact.get("name", "")).lower()
        version = str(artifact.get("version", ""))
        if not name:
            problems.append("artifact has no name")
            continue
        license_name = artifact.get("license")
        if not license_name:
            problems.append(f"{name}: missing license")
        elif license_name not in ALLOWED_LICENSES:
            problems.append(f"{name}: license not on the allowlist: {license_name}")
        source = str(artifact.get("source", ""))
        if not HTTPS_RE.match(source):
            problems.append(f"{name}: missing or non-https source URL")
        sha = str(artifact.get("sha256", "")).lower()
        if not SHA256_RE.match(sha):
            problems.append(f"{name}: missing or malformed sha256")
        binary_sha = str(artifact.get("binarySha256", "")).lower()
        if artifact.get("member") and not SHA256_RE.match(binary_sha):
            problems.append(f"{name}: missing or malformed binarySha256")
        if str(license_name or "").startswith(("GPL", "LGPL")):
            corresponding = artifact.get("correspondingSource")
            if not corresponding:
                problems.append(f"{name}: GPL-family artifact has no correspondingSource")
            else:
                if not HTTPS_RE.match(str(corresponding.get("upstream", ""))):
                    problems.append(f"{name}: correspondingSource lacks an https upstream")
                if not SHA256_RE.match(str(corresponding.get("upstreamSha256", ""))):
                    problems.append(f"{name}: correspondingSource lacks upstreamSha256")
                if corresponding.get("packaging") and not HTTPS_RE.match(
                    str(corresponding["packaging"])
                ):
                    problems.append(f"{name}: correspondingSource packaging is not https")
        if name not in notice:
            problems.append(f"{name}: not named in NOTICE ({notice_path})")
        if version and version.lower() not in notice:
            problems.append(f"{name}: version {version} is not named in NOTICE")
        if license_name and str(license_name).lower() not in notice:
            problems.append(f"{name}: license {license_name} is not named in NOTICE")
        if SHA256_RE.match(sha) and sha not in notice:
            problems.append(f"{name}: package sha256 is not named in NOTICE")
        if SHA256_RE.match(binary_sha) and binary_sha not in notice:
            problems.append(f"{name}: binary sha256 is not named in NOTICE")

        package = packages.get(name)
        if package is None:
            problems.append(f"{name}: not listed in SBOM ({sbom_path})")
        else:
            if str(package.get("versionInfo", "")) != version:
                problems.append(f"{name}: SBOM version does not match the manifest")
            if package.get("licenseDeclared") != license_name:
                problems.append(f"{name}: SBOM licenseDeclared does not match the manifest")
            if package.get("licenseConcluded") != license_name:
                problems.append(f"{name}: SBOM licenseConcluded does not match the manifest")
            if str(package.get("downloadLocation", "")) != source:
                problems.append(f"{name}: SBOM downloadLocation does not match the manifest")
            checksums = _checksums(package)
            if ("SHA256", sha) not in checksums:
                problems.append(f"{name}: SBOM is missing the package SHA256")
            if artifact.get("member") and ("SHA256", binary_sha) not in checksums:
                problems.append(f"{name}: SBOM is missing the binary SHA256")
            if not str(package.get("copyrightText", "")).strip():
                problems.append(f"{name}: SBOM has no copyrightText")
            if str(package.get("SPDXID", "")) not in described:
                problems.append(f"{name}: SBOM package is not DESCRIBES-linked")
            refs = _external_refs(package)
            corresponding = artifact.get("correspondingSource") or {}
            if corresponding:
                if refs.get("corresponding-source") != corresponding.get("upstream"):
                    problems.append(f"{name}: SBOM corresponding-source does not match")
                if corresponding.get("packaging") and refs.get("packaging-source") != corresponding.get(
                    "packaging"
                ):
                    problems.append(f"{name}: SBOM packaging-source does not match")

    for package_name in packages:
        if package_name not in artifact_names:
            problems.append(f"{package_name}: SBOM lists a package that is not in the manifest")

    pending = document.get("pendingProfiles") or {}
    if not isinstance(pending, dict):
        problems.append("pendingProfiles must be an object")
        pending = {}
    for profile_name, profile in pending.items():
        status = profile.get("status") if isinstance(profile, dict) else None
        if status not in {"not-tested", "blocked"}:
            problems.append(f"pending profile {profile_name} claims status {status!r}; v1 must not ship it")
            continue
        for required in profile.get("requiredArtifacts") or []:
            if not isinstance(required, dict):
                continue
            required_name = str(required.get("name", ""))
            if not required_name:
                problems.append(f"pending profile {profile_name} has an unnamed required artifact")
                continue
            if required_name.lower() in artifact_names:
                problems.append(
                    f"pending profile {profile_name} lists shipped artifact {required_name}"
                )
            if required.get("sha256") is not None:
                problems.append(
                    f"pending profile {profile_name} must not claim a pinned {required_name}"
                )
            if required.get("license") not in ALLOWED_LICENSES:
                problems.append(
                    f"pending profile {profile_name} has no approved license for {required_name}"
                )
            if not HTTPS_RE.match(str(required.get("source", ""))):
                problems.append(
                    f"pending profile {profile_name} has no https source for {required_name}"
                )
            if required_name.lower() not in notice:
                problems.append(
                    f"pending profile {profile_name} component {required_name} is not named in NOTICE"
                )
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
