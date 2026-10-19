#!/usr/bin/env python3
"""Verify a release APK or a release metadata fixture.

The APK mode is the release gate: it reads applicationId/versionName with
aapt2, requires a signer whose certificate is not the Android debug key, and
prints the APK SHA-256. The metadata mode exists only for deterministic tests
and fixtures; it never replaces the aapt2/apksigner checks in CI.

Exit status is 0 only when every check passes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Iterable, Optional

RELEASE_APPLICATION_ID = "io.github.nyzeep.orchestella"
RELEASE_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-(?:preview|beta|rc)\.[0-9]+)?$")
INTERNAL_MARKERS = ("dev", "qa", "dirty")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
DEBUG_CERT_MARKER = "CN=Android Debug"
PACKAGE_LINE = re.compile(
    r"^package:\s+name='([^']+)'\s+versionCode='([^']*)'\s+versionName='([^']*)'",
    re.MULTILINE,
)


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def validate_identity(
    application_id: str,
    version_name: str,
    expected_application_id: str,
    expected_version_name: Optional[str],
) -> list[str]:
    errors: list[str] = []
    if application_id != expected_application_id:
        fail(errors, "applicationId is '%s', expected '%s'" % (application_id, expected_application_id))
    if not RELEASE_VERSION_PATTERN.match(version_name or ""):
        fail(
            errors,
            "versionName '%s' is not a public release version (expected MAJOR.MINOR.PATCH or -preview.N/-beta.N/-rc.N)"
            % (version_name or ""),
        )
    lowered = (version_name or "").lower()
    for marker in INTERNAL_MARKERS:
        if marker in lowered:
            fail(errors, "versionName '%s' contains internal marker '%s'" % (version_name, marker))
    if re.search(r"(?:^|[.-])[0-9a-f]{7,40}(?:[.-]|$)", lowered):
        fail(errors, "versionName '%s' contains a commit SHA" % version_name)
    if expected_version_name is not None and version_name != expected_version_name:
        fail(errors, "versionName is '%s', expected '%s'" % (version_name, expected_version_name))
    return errors


def validate_version_code(version_name: str, version_code: str) -> list[str]:
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)", version_name or "")
    if not match:
        return ["cannot derive versionCode from versionName %r" % version_name]
    expected = int(match.group(1)) * 10000 + int(match.group(2)) * 100 + int(match.group(3))
    if not (version_code or "").isdigit():
        return ["versionCode is not numeric: %r" % version_code]
    actual = int(version_code)
    if actual != expected:
        return ["versionCode %d does not equal MAJOR*10000+MINOR*100+PATCH (%d)" % (actual, expected)]
    return []


def validate_certificate(certificate_dn: str) -> list[str]:
    errors: list[str] = []
    if not certificate_dn or not certificate_dn.strip():
        fail(errors, "APK has no signing certificate")
    elif DEBUG_CERT_MARKER.lower() in certificate_dn.lower():
        fail(errors, "APK is signed with the Android debug certificate: %s" % certificate_dn)
    return errors


def validate_sha256(value: str) -> list[str]:
    if SHA256_PATTERN.match(value or ""):
        return []
    return ["SHA-256 value is not 64 lowercase hex characters: %r" % value]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_android_tool(name: str, explicit: Optional[str]) -> str:
    if explicit:
        return explicit
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if build_tools.is_dir():
            def version_key(path: Path) -> list[int]:
                return [int(part) for part in re.findall(r"\d+", path.parent.name)]

            candidates = sorted(
                (path / name for path in build_tools.iterdir() if path.is_dir()),
                key=version_key,
                reverse=True,
            )
            for candidate in candidates:
                if candidate.is_file() and os.access(candidate, os.X_OK):
                    return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    raise FileNotFoundError(
        "cannot find %s; pass --%s or set ANDROID_HOME/ANDROID_SDK_ROOT" % (name, name)
    )


def run(command: list[str]) -> str:
    completed = subprocess.run(command, check=False, text=True, capture_output=True)
    if completed.returncode != 0:
        raise RuntimeError(
            "command failed (%d): %s\n%s" % (completed.returncode, " ".join(command), completed.stderr.strip())
        )
    return completed.stdout


def parse_badging(output: str) -> tuple[str, str, str]:
    match = PACKAGE_LINE.search(output)
    if not match:
        raise RuntimeError("aapt2 dump badging did not contain a package line")
    return match.group(1), match.group(2), match.group(3)


def parse_certificate(output: str) -> str:
    for line in output.splitlines():
        marker = "certificate DN:"
        if marker in line:
            return line.split(marker, 1)[1].strip()
    return ""


def verify_apk(
    apk: Path,
    expected_application_id: str,
    expected_version_name: Optional[str],
    aapt2: Optional[str],
    apksigner: Optional[str],
) -> tuple[list[str], dict[str, str]]:
    errors: list[str] = []
    details: dict[str, str] = {}
    if not apk.is_file():
        return ["APK does not exist: %s" % apk], details
    sha256 = sha256_file(apk)
    details["apkSha256"] = sha256
    errors.extend(validate_sha256(sha256))
    try:
        badging = run([find_android_tool("aapt2", aapt2), "dump", "badging", str(apk)])
        application_id, version_code, version_name = parse_badging(badging)
        details["applicationId"] = application_id
        details["versionName"] = version_name
        details["versionCode"] = version_code
        errors.extend(
            validate_identity(application_id, version_name, expected_application_id, expected_version_name)
        )
        errors.extend(validate_version_code(version_name, version_code))
    except (OSError, RuntimeError) as failure:
        errors.append(str(failure))
    try:
        certificates = run([find_android_tool("apksigner", apksigner), "verify", "--print-certs", str(apk)])
        certificate = parse_certificate(certificates)
        details["certificateDn"] = certificate
        errors.extend(validate_certificate(certificate))
    except (OSError, RuntimeError) as failure:
        errors.append(str(failure))
    return errors, details


def load_metadata(path: Path) -> dict[str, object]:
    with path.open("r", encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError("metadata fixture must be a JSON object")
    return value


def verify_metadata(
    metadata: dict[str, object],
    expected_application_id: str,
    expected_version_name: Optional[str],
) -> tuple[list[str], dict[str, str]]:
    errors: list[str] = []
    details: dict[str, str] = {}
    application_id = str(metadata.get("applicationId", ""))
    version_name = str(metadata.get("versionName", ""))
    certificate_dn = str(metadata.get("certificateDn", ""))
    apk_sha256 = str(metadata.get("apkSha256", ""))
    details.update(
        applicationId=application_id,
        versionName=version_name,
        certificateDn=certificate_dn,
        apkSha256=apk_sha256,
    )
    errors.extend(validate_identity(application_id, version_name, expected_application_id, expected_version_name))
    errors.extend(validate_certificate(certificate_dn))
    errors.extend(validate_sha256(apk_sha256))
    return errors, details


def emit(details: dict[str, str], errors: Iterable[str], json_path: Optional[Path]) -> None:
    errors = list(errors)
    for key in ("applicationId", "versionName", "versionCode", "certificateDn", "apkSha256"):
        if details.get(key):
            print("%s=%s" % (key, details[key]))
    if errors:
        for error in errors:
            print("FAIL: %s" % error)
        print("RESULT=FAIL")
    else:
        print("RESULT=PASS")
    if json_path is not None:
        payload = dict(details)
        payload["result"] = "FAIL" if errors else "PASS"
        payload["errors"] = errors
        json_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path, help="APK to inspect")
    source.add_argument("--metadata", type=Path, help="JSON fixture with applicationId/versionName/certificateDn/apkSha256")
    parser.add_argument("--expected-application-id", default=RELEASE_APPLICATION_ID)
    parser.add_argument("--expected-version-name")
    parser.add_argument("--aapt2")
    parser.add_argument("--apksigner")
    parser.add_argument("--json", type=Path, help="write a machine-readable result")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    arguments = build_parser().parse_args(argv)
    if arguments.apk is not None:
        errors, details = verify_apk(
            arguments.apk,
            arguments.expected_application_id,
            arguments.expected_version_name,
            arguments.aapt2,
            arguments.apksigner,
        )
    else:
        try:
            metadata = load_metadata(arguments.metadata)
        except (OSError, ValueError, json.JSONDecodeError) as failure:
            errors, details = [str(failure)], {}
        else:
            errors, details = verify_metadata(
                metadata, arguments.expected_application_id, arguments.expected_version_name
            )
    emit(details, errors, arguments.json)
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
