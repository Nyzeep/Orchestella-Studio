#!/usr/bin/env python3
"""Scoped QA target resolution, host APK verification, and exact-package ADB operations.

Single source of truth for Wave 02+ QA isolation:
1. Only explicit ``io.github.nyzeep.orchestella.qa.<purpose>`` packages (with matching
   ``<pkg>.test`` instrumentation) are ever accepted as targets. Release, dev, and
   other QA packages are rejected fail-closed with actionable error codes.
2. Host preflight inspects actual APK SHA-256, applicationId, versionName purpose,
   ABI, instrumentation targetPackage/class, and apksigner certificate digests before
   any ADB write command is issued.
3. Default installation is incremental (``adb install -r -t``) with zero uninstalls;
   explicit clean installation uninstalls strictly the two verified QA packages
   (``<qa_pkg>.test`` and ``<qa_pkg>``). ``--skip-install`` verifies on-device APK
   hashes, instrumentation registration, and staged input hashes read-only.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
from typing import Any, Callable
import xml.etree.ElementTree as ET
import zipfile

RELEASE_APPLICATION_ID = "io.github.nyzeep.orchestella"
DEV_APPLICATION_ID = "io.github.nyzeep.orchestella.dev"
QA_PACKAGE_PREFIX = "io.github.nyzeep.orchestella.qa."
QA_PURPOSE_RE = re.compile(r"^[a-z0-9]{1,32}$")
_ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_CERT_SHA256_RE = re.compile(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})")
_PUBKEY_SHA256_RE = re.compile(r"Signer #\d+ public key SHA-256 digest:\s*([0-9a-fA-F]{64})")
_CERT_DN_RE = re.compile(r"Signer #\d+ certificate DN:\s*(.+)$")
_VERIFIED_SCHEME_RE = re.compile(r"Verified using (v[0-9.]+) scheme .*?:\s*(true|false)", re.IGNORECASE)
_XMLTREE_NODE_RE = re.compile(r"^(\s*)[E]:\s+(\S+)")
_XMLTREE_ATTR_RE = re.compile(r'^\s*[A]:\s+(?:https?://\S+?:)?([A-Za-z0-9_]+)(?:\([^)]+\))?=(.+)$')
_SIGNATURE_CONFLICT_TOKENS = (
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
    "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
    "signatures do not match",
)


class QaGuardError(ValueError):
    """Fail-closed QA guard error carrying a stable machine code and clear user detail."""

    def __init__(self, code: str, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class QaTargetSpec:
    """Resolved isolated QA application and instrumentation target."""

    purpose: str
    app_package: str
    test_package: str
    instrumentation_class: str
    expected_abi: str = "arm64-v8a"

    @property
    def instrumentation_component(self) -> str:
        return f"{self.test_package}/{self.instrumentation_class}"


def _default_run_cmd(
    cmd: list[str],
    *,
    check: bool = True,
    timeout: int = 120,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        shell=False,
        text=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        check=check,
        timeout=timeout,
    )


def sha256_file(path: Path) -> str:
    """Return lowercase hex SHA-256 digest of ``path``."""
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _classify_non_qa_package(package: str, expected_purpose: str | None = None) -> tuple[str, str]:
    cleaned = (package or "").strip()
    if cleaned == RELEASE_APPLICATION_ID:
        return (
            "RELEASE_PACKAGE_FORBIDDEN",
            f"Refusing release package {RELEASE_APPLICATION_ID!r}; QA tools may operate only on {QA_PACKAGE_PREFIX}<purpose>",
        )
    if cleaned == DEV_APPLICATION_ID or cleaned.startswith(f"{DEV_APPLICATION_ID}."):
        return (
            "DEV_PACKAGE_FORBIDDEN",
            f"Refusing internal dev package {cleaned!r}; QA tools may operate only on {QA_PACKAGE_PREFIX}<purpose>",
        )
    if cleaned.startswith(QA_PACKAGE_PREFIX):
        suffix = cleaned[len(QA_PACKAGE_PREFIX):]
        if not QA_PURPOSE_RE.fullmatch(suffix):
            return (
                "INVALID_QA_PURPOSE",
                f"Invalid QA purpose segment in package {cleaned!r}; expected [a-z0-9]{{1,32}}",
            )
        if expected_purpose is not None and suffix != expected_purpose:
            return (
                "APP_PACKAGE_MISMATCH",
                f"APK applicationId {cleaned!r} targets QA purpose {suffix!r}, expected {expected_purpose!r}",
            )
        return ("QA_PACKAGE_OK", "")
    return (
        "NON_QA_PACKAGE_FORBIDDEN",
        f"Refusing non-QA package {cleaned!r}; expected {QA_PACKAGE_PREFIX}<purpose>",
    )


def resolve_qa_target(
    *,
    purpose: str | None = None,
    package: str | None = None,
    default_purpose: str,
    instrumentation_class: str,
    expected_abi: str = "arm64-v8a",
) -> QaTargetSpec:
    """Resolve and strictly validate an isolated QA package + instrumentation target."""
    if not instrumentation_class or not instrumentation_class.strip():
        raise QaGuardError("INVALID_INSTRUMENTATION_CLASS", "instrumentation_class must not be empty")

    resolved_purpose: str
    if purpose is not None and purpose.strip():
        cand_purpose = purpose.strip()
        if not QA_PURPOSE_RE.fullmatch(cand_purpose):
            raise QaGuardError(
                "INVALID_QA_PURPOSE",
                f"QA purpose {cand_purpose!r} must match [a-z0-9]{{1,32}} (matching app/build.gradle.kts)",
            )
        resolved_purpose = cand_purpose
    elif package is not None and package.strip():
        code, detail = _classify_non_qa_package(package)
        if code != "QA_PACKAGE_OK":
            raise QaGuardError(code, detail)
        resolved_purpose = package.strip()[len(QA_PACKAGE_PREFIX):]
    else:
        cand_purpose = default_purpose.strip()
        if not QA_PURPOSE_RE.fullmatch(cand_purpose):
            raise QaGuardError("INVALID_QA_PURPOSE", f"Default QA purpose {cand_purpose!r} is invalid")
        resolved_purpose = cand_purpose

    expected_pkg = f"{QA_PACKAGE_PREFIX}{resolved_purpose}"
    if package is not None and package.strip():
        pkg_clean = package.strip()
        code, detail = _classify_non_qa_package(pkg_clean)
        if code != "QA_PACKAGE_OK":
            raise QaGuardError(code, detail)
        if pkg_clean != expected_pkg:
            raise QaGuardError(
                "QA_TARGET_CONFLICT",
                f"--package {pkg_clean!r} conflicts with --qa-purpose {resolved_purpose!r} (expected {expected_pkg!r})",
            )

    return QaTargetSpec(
        purpose=resolved_purpose,
        app_package=expected_pkg,
        test_package=f"{expected_pkg}.test",
        instrumentation_class=instrumentation_class.strip(),
        expected_abi=expected_abi,
    )


def _version_sort_key(path: Path) -> list[int]:
    return [int(part) for part in re.findall(r"\d+", path.name)]


def find_sdk_tool(
    explicit: str | None,
    env_var: str,
    candidates: list[str] | tuple[str, ...],
) -> str | None:
    """Locate an Android SDK/NDK tool from explicit arg, env var, PATH, or ANDROID_HOME."""
    if explicit:
        return explicit
    env_val = os.environ.get(env_var)
    if env_val:
        return env_val
    for cand in candidates:
        found = shutil.which(cand)
        if found:
            return found

    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        return None
    root = Path(sdk_root)
    exts = ("", ".exe", ".bat")

    search_dirs: list[Path] = []
    build_tools = root / "build-tools"
    if build_tools.is_dir():
        search_dirs.extend(sorted((d for d in build_tools.iterdir() if d.is_dir()), key=_version_sort_key, reverse=True))
    cmdline_tools = root / "cmdline-tools"
    if cmdline_tools.is_dir():
        for d in sorted((d for d in cmdline_tools.iterdir() if d.is_dir()), key=lambda p: (p.name != "latest", p.name), reverse=False):
            bin_dir = d / "bin"
            if bin_dir.is_dir():
                search_dirs.append(bin_dir)

    for directory in search_dirs:
        for cand in candidates:
            for ext in exts:
                candidate = directory / f"{cand}{ext}"
                if candidate.is_file():
                    return str(candidate)

    ndk_root = root / "ndk"
    if ndk_root.is_dir():
        for ver_dir in sorted((d for d in ndk_root.iterdir() if d.is_dir()), key=_version_sort_key, reverse=True):
            for cand in candidates:
                for match in ver_dir.rglob(f"{cand}*"):
                    if match.is_file() and match.name in (cand, f"{cand}.exe"):
                        return str(match)
    return None


def _parse_int_attr(value: str | None) -> int | None:
    if value is None:
        return None
    text = value.strip()
    if text.startswith("0x"):
        try:
            return int(text, 16)
        except ValueError:
            return None
    try:
        return int(text)
    except ValueError:
        return None


def _parse_bool_attr(value: str | None) -> bool | None:
    if value is None:
        return None
    lowered = value.strip().lower()
    if lowered in ("true", "1", "0xffffffff", "-1"):
        return True
    if lowered in ("false", "0", "0x0"):
        return False
    return None


def parse_apkanalyzer_manifest_xml(xml_text: str) -> dict[str, Any]:
    """Parse ``apkanalyzer manifest print <apk>`` XML output."""
    try:
        root = ET.fromstring(xml_text.strip())
    except ET.ParseError as exc:
        raise QaGuardError("MANIFEST_PARSE_FAILED", f"Failed to parse apkanalyzer manifest XML: {exc}") from exc

    app_id = (root.attrib.get("package") or "").strip()
    if not app_id:
        raise QaGuardError("MANIFEST_PARSE_FAILED", "Manifest XML missing package attribute")

    version_name = (root.attrib.get(f"{_ANDROID_NS}versionName") or "").strip()
    version_code = _parse_int_attr(root.attrib.get(f"{_ANDROID_NS}versionCode"))

    uses_sdk = root.find("uses-sdk")
    min_sdk = _parse_int_attr(uses_sdk.attrib.get(f"{_ANDROID_NS}minSdkVersion")) if uses_sdk is not None else None
    target_sdk = _parse_int_attr(uses_sdk.attrib.get(f"{_ANDROID_NS}targetSdkVersion")) if uses_sdk is not None else None

    app_el = root.find("application")
    debuggable = _parse_bool_attr(app_el.attrib.get(f"{_ANDROID_NS}debuggable")) if app_el is not None else None

    instrumentations: list[dict[str, str]] = []
    for inst in root.findall("instrumentation"):
        name = (inst.attrib.get(f"{_ANDROID_NS}name") or "").strip()
        target_pkg = (inst.attrib.get(f"{_ANDROID_NS}targetPackage") or "").strip()
        instrumentations.append({"name": name, "target_package": target_pkg})

    return {
        "application_id": app_id,
        "version_name": version_name,
        "version_code": version_code,
        "min_sdk": min_sdk,
        "target_sdk": target_sdk,
        "debuggable": debuggable,
        "instrumentations": instrumentations,
    }


def _extract_xmltree_value(raw_val: str) -> str:
    m = re.match(r'^"([^"]*)"(?:\s+\(Raw:\s*"[^"]*"\))?$', raw_val.strip())
    if m:
        return m.group(1)
    m_raw = re.search(r'\(Raw:\s*"([^"]*)"\)\s*$', raw_val.strip())
    if m_raw:
        return m_raw.group(1)
    return raw_val.strip()


def parse_aapt2_xmltree(xmltree_text: str) -> dict[str, Any]:
    """Parse ``aapt2 dump xmltree <apk> --file AndroidManifest.xml`` output."""
    app_id = ""
    version_name = ""
    version_code: int | None = None
    min_sdk: int | None = None
    target_sdk: int | None = None
    debuggable: bool | None = None
    instrumentations: list[dict[str, str]] = []

    stack: list[tuple[int, str]] = []
    current_inst: dict[str, str] | None = None

    for raw_line in xmltree_text.splitlines():
        node_m = _XMLTREE_NODE_RE.match(raw_line)
        if node_m:
            indent = len(node_m.group(1))
            tag = node_m.group(2)
            while stack and stack[-1][0] >= indent:
                _, popped = stack.pop()
                if popped == "instrumentation" and current_inst is not None:
                    instrumentations.append(current_inst)
                    current_inst = None
            stack.append((indent, tag))
            if tag == "instrumentation":
                current_inst = {"name": "", "target_package": ""}
            continue

        attr_m = _XMLTREE_ATTR_RE.match(raw_line)
        if not attr_m or not stack:
            continue
        attr_name = attr_m.group(1)
        val = _extract_xmltree_value(attr_m.group(2))
        cur_tag = stack[-1][1]
        if cur_tag == "manifest":
            if attr_name == "package":
                app_id = val
            elif attr_name == "versionName":
                version_name = val
            elif attr_name == "versionCode":
                version_code = _parse_int_attr(val)
        elif cur_tag == "uses-sdk":
            if attr_name == "minSdkVersion":
                min_sdk = _parse_int_attr(val)
            elif attr_name == "targetSdkVersion":
                target_sdk = _parse_int_attr(val)
        elif cur_tag == "application":
            if attr_name == "debuggable":
                debuggable = _parse_bool_attr(val)
        elif cur_tag == "instrumentation" and current_inst is not None:
            if attr_name == "name":
                current_inst["name"] = val
            elif attr_name == "targetPackage":
                current_inst["target_package"] = val

    if current_inst is not None:
        instrumentations.append(current_inst)

    if not app_id:
        raise QaGuardError("MANIFEST_PARSE_FAILED", "aapt2 xmltree output missing manifest package")

    return {
        "application_id": app_id,
        "version_name": version_name,
        "version_code": version_code,
        "min_sdk": min_sdk,
        "target_sdk": target_sdk,
        "debuggable": debuggable,
        "instrumentations": instrumentations,
    }


def parse_apksigner_output(stdout: str, stderr: str, returncode: int) -> dict[str, Any]:
    """Parse ``apksigner verify --verbose --print-certs`` output."""
    combined = "\n".join(part for part in (stdout, stderr) if part)
    schemes: dict[str, bool] = {}
    cert_sha256s: list[str] = []
    pubkey_sha256s: list[str] = []
    dns: list[str] = []
    lines: list[str] = []

    for raw in combined.splitlines():
        line = raw.strip()
        if not line:
            continue
        sch_m = _VERIFIED_SCHEME_RE.search(line)
        if sch_m:
            schemes[sch_m.group(1).lower()] = sch_m.group(2).lower() == "true"
            lines.append(line)
            continue
        cert_m = _CERT_SHA256_RE.search(line)
        if cert_m:
            cert_sha256s.append(cert_m.group(1).lower())
            lines.append(line)
            continue
        pub_m = _PUBKEY_SHA256_RE.search(line)
        if pub_m:
            pubkey_sha256s.append(pub_m.group(1).lower())
            lines.append(line)
            continue
        dn_m = _CERT_DN_RE.search(line)
        if dn_m:
            dns.append(dn_m.group(1).strip())

    unique_certs = sorted(set(cert_sha256s))
    passed = returncode == 0 and bool(unique_certs)
    return {
        "passed": passed,
        "schemes": schemes,
        "signer_count": len(unique_certs),
        "signer_cert_sha256s": unique_certs,
        "signer_pubkey_sha256s": sorted(set(pubkey_sha256s)),
        "signer_dns": dns,
        "lines": lines,
    }


def inspect_apk_abis(apk_path: Path) -> list[str]:
    """Return sorted ABIs shipped under ``lib/<abi>/`` in the APK ZIP."""
    abis: set[str] = set()
    with zipfile.ZipFile(apk_path, "r") as zf:
        for name in zf.namelist():
            if name.startswith("lib/") and name.endswith(".so"):
                parts = name.split("/")
                if len(parts) >= 3 and parts[1]:
                    abis.add(parts[1])
    return sorted(abis)


def inspect_apk_host(
    apk_path: Path,
    *,
    apkanalyzer_path: str | None = None,
    aapt2_path: str | None = None,
    apksigner_path: str | None = None,
    run_cmd: Callable[..., subprocess.CompletedProcess[str]] = _default_run_cmd,
) -> dict[str, Any]:
    """Inspect a host APK file using actual bytes, Android manifest tools, and apksigner."""
    path = Path(apk_path)
    if not path.is_file():
        raise QaGuardError("APK_MISSING", f"APK does not exist: {path.name}")

    analyzer = find_sdk_tool(apkanalyzer_path, "APKANALYZER", ["apkanalyzer"])
    aapt2 = find_sdk_tool(aapt2_path, "AAPT2", ["aapt2"])
    apksigner = find_sdk_tool(apksigner_path, "APKSIGNER", ["apksigner"])

    manifest_meta: dict[str, Any] | None = None
    if analyzer:
        proc = run_cmd([analyzer, "manifest", "print", str(path)], check=False, timeout=60)
        if proc.returncode == 0 and proc.stdout.strip():
            manifest_meta = parse_apkanalyzer_manifest_xml(proc.stdout)
    if manifest_meta is None and aapt2:
        proc = run_cmd([aapt2, "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"], check=False, timeout=60)
        if proc.returncode == 0 and proc.stdout.strip():
            manifest_meta = parse_aapt2_xmltree(proc.stdout)
    if manifest_meta is None:
        raise QaGuardError(
            "ANDROID_MANIFEST_TOOL_MISSING",
            f"Unable to inspect AndroidManifest.xml for {path.name}; install apkanalyzer or aapt2 or pass --apkanalyzer/--aapt2",
        )

    if not apksigner:
        raise QaGuardError(
            "APKSIGNER_MISSING",
            f"Unable to verify signature for {path.name}; install apksigner or pass --apksigner",
        )
    sig_proc = run_cmd([apksigner, "verify", "--verbose", "--print-certs", str(path)], check=False, timeout=60)
    sig_info = parse_apksigner_output(sig_proc.stdout, sig_proc.stderr, sig_proc.returncode)

    return {
        "name": path.name,
        "size_bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        **manifest_meta,
        "abis": inspect_apk_abis(path),
        "apksigner": sig_info,
    }


def _validate_single_apk_signature(label: str, apk_info: dict[str, Any]) -> list[str]:
    sig = apk_info.get("apksigner")
    if not isinstance(sig, dict) or not sig.get("passed"):
        raise QaGuardError(
            "APK_SIGNATURE_INVALID",
            f"{label} APK {apk_info.get('name', '')!r} failed apksigner verification",
        )
    certs = [str(c).lower() for c in sig.get("signer_cert_sha256s") or [] if _SHA256_RE.fullmatch(str(c))]
    if not certs:
        raise QaGuardError(
            "APK_SIGNATURE_INVALID",
            f"{label} APK {apk_info.get('name', '')!r} has no verifiable signer certificate SHA-256",
        )
    return sorted(set(certs))


def verify_qa_apk_pair(
    app_apk: Path,
    test_apk: Path,
    target: QaTargetSpec,
    *,
    app_info: dict[str, Any] | None = None,
    test_info: dict[str, Any] | None = None,
    apkanalyzer_path: str | None = None,
    aapt2_path: str | None = None,
    apksigner_path: str | None = None,
    run_cmd: Callable[..., subprocess.CompletedProcess[str]] = _default_run_cmd,
) -> dict[str, Any]:
    """Verify app + instrumentation APK identity, purpose, ABI, instrumentation target, and signatures."""
    app_meta = app_info if app_info is not None else inspect_apk_host(
        app_apk,
        apkanalyzer_path=apkanalyzer_path,
        aapt2_path=aapt2_path,
        apksigner_path=apksigner_path,
        run_cmd=run_cmd,
    )
    test_meta = test_info if test_info is not None else inspect_apk_host(
        test_apk,
        apkanalyzer_path=apkanalyzer_path,
        aapt2_path=aapt2_path,
        apksigner_path=apksigner_path,
        run_cmd=run_cmd,
    )

    app_id = str(app_meta.get("application_id") or "")
    pkg_code, pkg_detail = _classify_non_qa_package(app_id, expected_purpose=target.purpose)
    if pkg_code != "QA_PACKAGE_OK":
        raise QaGuardError(pkg_code, pkg_detail)

    version_name = str(app_meta.get("version_name") or "")
    expected_marker = f"-qa.{target.purpose}."
    if expected_marker not in version_name:
        raise QaGuardError(
            "APK_VERSION_PURPOSE_MISMATCH",
            f"App APK versionName {version_name!r} does not declare {expected_marker!r}",
        )

    abis = list(app_meta.get("abis") or [])
    if target.expected_abi not in abis:
        raise QaGuardError(
            "APK_ABI_MISMATCH",
            f"App APK missing expected ABI {target.expected_abi!r}; found {abis}",
        )

    test_id = str(test_meta.get("application_id") or "")
    if test_id != target.test_package:
        raise QaGuardError(
            "TEST_PACKAGE_MISMATCH",
            f"Test APK applicationId is {test_id!r}, expected {target.test_package!r}",
        )

    instrumentations = list(test_meta.get("instrumentations") or [])
    if not instrumentations:
        raise QaGuardError(
            "INSTRUMENTATION_MISSING",
            f"Test APK {test_meta.get('name', '')!r} declares no <instrumentation> component",
        )
    if len(instrumentations) != 1:
        raise QaGuardError(
            "INSTRUMENTATION_AMBIGUOUS",
            f"Test APK declares {len(instrumentations)} instrumentation targets; expected exactly 1",
        )
    inst = instrumentations[0]
    if inst.get("target_package") != target.app_package:
        raise QaGuardError(
            "INSTRUMENTATION_TARGET_MISMATCH",
            f"Instrumentation targetPackage is {inst.get('target_package')!r}, expected {target.app_package!r}",
        )
    if inst.get("name") != target.instrumentation_class:
        raise QaGuardError(
            "INSTRUMENTATION_CLASS_MISMATCH",
            f"Instrumentation class is {inst.get('name')!r}, expected {target.instrumentation_class!r}",
        )

    app_certs = _validate_single_apk_signature("App", app_meta)
    test_certs = _validate_single_apk_signature("Test", test_meta)
    if app_certs != test_certs:
        raise QaGuardError(
            "APK_SIGNATURE_MISMATCH",
            f"App APK signing cert {app_certs} differs from Test APK signing cert {test_certs}",
        )

    return {
        "qa_target": {
            "purpose": target.purpose,
            "app_package": target.app_package,
            "test_package": target.test_package,
            "instrumentation_component": target.instrumentation_component,
            "expected_abi": target.expected_abi,
        },
        "app_apk": app_meta,
        "test_apk": test_meta,
        "signing_cert_sha256s": app_certs,
    }


def resolve_unique_device(
    adb_path: str,
    requested_serial: str | None,
    *,
    run_cmd: Callable[..., subprocess.CompletedProcess[str]] = _default_run_cmd,
) -> str:
    """Resolve exactly one connected authorized ADB device or verify explicit serial."""
    proc = run_cmd([adb_path, "devices", "-l"], check=True, timeout=30)
    authorized: list[str] = []
    other_states: dict[str, str] = {}
    for line in proc.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        if state == "device":
            authorized.append(serial)
        else:
            other_states[serial] = state

    if requested_serial:
        req = requested_serial.strip()
        if req in authorized:
            return req
        if req in other_states:
            raise QaGuardError(
                "DEVICE_UNAVAILABLE",
                f"Requested device is in state {other_states[req]!r}, expected 'device'",
            )
        raise QaGuardError("DEVICE_UNAVAILABLE", "Requested device serial is not connected")

    if not authorized:
        raise QaGuardError(
            "DEVICE_UNAVAILABLE",
            f"Expected 1 authorized ADB device, found 0 (other states: {len(other_states)})",
        )
    if len(authorized) > 1:
        raise QaGuardError(
            "DEVICE_NOT_UNIQUE",
            f"Expected exactly 1 authorized ADB device, found {len(authorized)}; pass --serial explicitly",
        )
    return authorized[0]


def list_installed_orchestella_packages_readonly(adb: Any) -> list[str]:
    """Read-only observation of installed ``io.github.nyzeep.orchestella*`` packages."""
    proc = adb.run("shell", "pm", "list", "packages", RELEASE_APPLICATION_ID, check=False, timeout=30)
    pkgs: list[str] = []
    for raw in proc.stdout.splitlines():
        line = raw.strip()
        if line.startswith("package:"):
            pkg = line[len("package:"):].strip()
            if pkg:
                pkgs.append(pkg)
    return sorted(set(pkgs))


def _parse_pm_path_lines(stdout: str) -> list[str]:
    paths: list[str] = []
    for raw in stdout.splitlines():
        line = raw.strip()
        if line.startswith("package:"):
            p = line[len("package:"):].strip()
            if p:
                paths.append(p)
    return paths


def _parse_sha256sum_output(stdout: str) -> str | None:
    for raw in stdout.splitlines():
        parts = raw.strip().split()
        if parts and _SHA256_RE.fullmatch(parts[0]):
            return parts[0].lower()
    return None


def _parse_dumpsys_version(dumpsys_text: str) -> tuple[str | None, int | None]:
    vname_m = re.search(r"\bversionName=([^\s]+)", dumpsys_text)
    vcode_m = re.search(r"\bversionCode=(\d+)", dumpsys_text)
    return (
        vname_m.group(1) if vname_m else None,
        int(vcode_m.group(1)) if vcode_m else None,
    )


def inspect_installed_qa_package(adb: Any, package: str) -> dict[str, Any]:
    """Read-only inspection of an installed package's paths, version, and on-device base.apk SHA-256."""
    pm_proc = adb.run("shell", "pm", "path", package, check=False, timeout=30)
    paths = _parse_pm_path_lines(pm_proc.stdout)
    if pm_proc.returncode != 0 or not paths:
        return {
            "package": package,
            "installed": False,
            "apk_paths": [],
            "sha256": None,
            "version_name": None,
            "version_code": None,
        }

    base_path = next((p for p in paths if p.endswith("/base.apk")), paths[0])
    sha_proc = adb.run("shell", "sha256sum", base_path, check=False, timeout=60)
    sha_val = _parse_sha256sum_output(sha_proc.stdout) if sha_proc.returncode == 0 else None

    dump_proc = adb.run("shell", "dumpsys", "package", package, check=False, timeout=30)
    vname, vcode = _parse_dumpsys_version(dump_proc.stdout)

    return {
        "package": package,
        "installed": True,
        "apk_paths": paths,
        "sha256": sha_val,
        "version_name": vname,
        "version_code": vcode,
    }


def clean_uninstall_qa_target(
    adb: Any,
    qa_package: str,
    *,
    test_package: str | None = None,
) -> dict[str, Any]:
    """Uninstall ONLY the exact verified QA package and its exact ``.test`` package."""
    pkg = (qa_package or "").strip()
    code, detail = _classify_non_qa_package(pkg)
    if code != "QA_PACKAGE_OK":
        raise QaGuardError(code, detail)

    resolved_test = (test_package or f"{pkg}.test").strip()
    if resolved_test != f"{pkg}.test":
        raise QaGuardError(
            "TEST_PACKAGE_MISMATCH",
            f"Refusing uninstall of mismatched test package {resolved_test!r} for QA package {pkg!r}",
        )

    exact_targets = [resolved_test, pkg]
    removed: list[str] = []
    not_installed: list[str] = []
    results: list[dict[str, Any]] = []

    for target_pkg in exact_targets:
        pm_proc = adb.run("shell", "pm", "path", target_pkg, check=False, timeout=30)
        if pm_proc.returncode != 0 or not _parse_pm_path_lines(pm_proc.stdout):
            not_installed.append(target_pkg)
            results.append({"package": target_pkg, "action": "not_installed"})
            continue
        un_proc = adb.run("uninstall", target_pkg, timeout=60, check=False)
        out_text = (un_proc.stdout + "\n" + un_proc.stderr).strip()
        if un_proc.returncode != 0:
            raise QaGuardError(
                "CLEAN_UNINSTALL_FAILED",
                f"Failed to uninstall exact QA package {target_pkg!r}: {out_text[:200]}",
            )
        removed.append(target_pkg)
        results.append({"package": target_pkg, "action": "uninstalled", "output": out_text[:120]})

    return {
        "target_packages": exact_targets,
        "removed": removed,
        "not_installed": not_installed,
        "results": results,
    }


def is_signature_conflict_error(output: str) -> bool:
    """Return True when ADB install output indicates an incompatible signing key conflict."""
    lowered = (output or "").lower()
    return any(tok.lower() in lowered for tok in _SIGNATURE_CONFLICT_TOKENS)


def _run_adb_install(adb: Any, apk_path: Path, *, package: str, timeout: int) -> None:
    proc = adb.run("install", "-r", "-t", str(apk_path), check=False, timeout=timeout)
    combined = "\n".join(p for p in (proc.stdout, proc.stderr) if p).strip()
    if proc.returncode == 0:
        return
    if is_signature_conflict_error(combined):
        raise QaGuardError(
            "INSTALL_SIGNATURE_CONFLICT",
            f"Signature conflict installing {package!r} incrementally (existing app data preserved, zero uninstalls performed). "
            f"Either re-sign the candidate with the installed key, or re-run with --clean-install to uninstall ONLY {package!r} and {package + '.test'!r} before retrying. "
            f"ADB detail: {combined[:240]}",
        )
    raise QaGuardError(
        "ADB_INSTALL_FAILED",
        f"adb install failed for {package!r} (rc={proc.returncode}): {combined[:240]}",
    )


def install_qa_candidate(
    adb: Any,
    *,
    app_apk: Path,
    test_apk: Path,
    target: QaTargetSpec,
    apk_proof: dict[str, Any],
    device_alias: str,
    clean_install: bool = False,
) -> dict[str, Any]:
    """Install verified QA app+test APKs incrementally (default) or cleanly (exact QA targets only)."""
    installed_before = {
        "app": inspect_installed_qa_package(adb, target.app_package),
        "test": inspect_installed_qa_package(adb, target.test_package),
    }

    clean_report: dict[str, Any] | None = None
    if clean_install:
        clean_report = clean_uninstall_qa_target(
            adb,
            target.app_package,
            test_package=target.test_package,
        )

    try:
        _run_adb_install(adb, app_apk, package=target.app_package, timeout=300)
        _run_adb_install(adb, test_apk, package=target.test_package, timeout=120)
    except QaGuardError as exc:
        if clean_report is not None:
            raise QaGuardError(
                exc.code,
                f"{exc.detail} (device_alias={device_alias}, clean_uninstall_removed={clean_report['removed']})",
            ) from exc
        raise

    installed_after = {
        "app": inspect_installed_qa_package(adb, target.app_package),
        "test": inspect_installed_qa_package(adb, target.test_package),
    }
    return {
        "mode": "clean_qa_only" if clean_install else "incremental",
        "device_alias": device_alias,
        "qa_package": target.app_package,
        "test_package": target.test_package,
        "installed_before": installed_before,
        "target_candidate": {
            "app_sha256": apk_proof["app_apk"]["sha256"],
            "app_version_name": apk_proof["app_apk"].get("version_name"),
            "test_sha256": apk_proof["test_apk"]["sha256"],
        },
        "clean_uninstall": clean_report,
        "installed_after": installed_after,
    }


def _verify_installed_apk_entry(
    adb: Any,
    *,
    role: str,
    package: str,
    expected_sha256: str,
    device_alias: str,
    missing_code: str,
    unverifiable_code: str,
    mismatch_code: str,
    expected_version_name: str | None = None,
) -> dict[str, Any]:
    installed = inspect_installed_qa_package(adb, package)
    if not installed["installed"]:
        raise QaGuardError(
            missing_code,
            f"--skip-install requested on {device_alias}, but {package!r} is not installed; install candidate first",
        )
    if not installed["sha256"]:
        raise QaGuardError(
            unverifiable_code,
            f"Unable to verify SHA-256 of installed {role} {package!r}; re-install candidate before running",
        )
    if installed["sha256"] != expected_sha256:
        raise QaGuardError(
            mismatch_code,
            f"Installed {package!r} SHA-256 {installed['sha256'][:16]}... != candidate {expected_sha256[:16]}...; install matching candidate",
        )
    if expected_version_name and installed.get("version_name") and installed["version_name"] != expected_version_name:
        raise QaGuardError(
            "INSTALLED_APP_VERSION_MISMATCH",
            f"Installed versionName {installed['version_name']!r} != candidate {expected_version_name!r}",
        )
    return installed


def verify_installed_qa_candidate(
    adb: Any,
    *,
    target: QaTargetSpec,
    apk_proof: dict[str, Any],
    device_alias: str,
    expected_staged_files: dict[str, str],
) -> dict[str, Any]:
    """Read-only verification for ``--skip-install`` against device APKs, instrumentation, and staged inputs."""
    installed_app = _verify_installed_apk_entry(
        adb,
        role="app",
        package=target.app_package,
        expected_sha256=apk_proof["app_apk"]["sha256"],
        device_alias=device_alias,
        missing_code="INSTALLED_APP_MISSING",
        unverifiable_code="INSTALLED_APP_IDENTITY_UNVERIFIABLE",
        mismatch_code="INSTALLED_APP_APK_MISMATCH",
        expected_version_name=apk_proof["app_apk"].get("version_name"),
    )
    installed_test = _verify_installed_apk_entry(
        adb,
        role="test",
        package=target.test_package,
        expected_sha256=apk_proof["test_apk"]["sha256"],
        device_alias=device_alias,
        missing_code="INSTALLED_TEST_MISSING",
        unverifiable_code="INSTALLED_TEST_IDENTITY_UNVERIFIABLE",
        mismatch_code="INSTALLED_TEST_APK_MISMATCH",
    )

    inst_proc = adb.run("shell", "pm", "list", "instrumentation", check=False, timeout=30)
    expected_inst_line = f"instrumentation:{target.instrumentation_component} (target={target.app_package})"
    if expected_inst_line not in inst_proc.stdout:
        raise QaGuardError(
            "INSTALLED_INSTRUMENTATION_MISMATCH",
            f"Device instrumentation list missing exact registration {expected_inst_line!r}",
        )

    staged_verified: dict[str, str] = {}
    for rel_path, expected_digest in sorted(expected_staged_files.items()):
        sha_proc = adb.run("shell", "run-as", target.app_package, "sha256sum", rel_path, check=False, timeout=60)
        actual_digest = _parse_sha256sum_output(sha_proc.stdout) if sha_proc.returncode == 0 else None
        if not actual_digest:
            raise QaGuardError(
                "STAGED_INPUT_MISSING",
                f"Staged QA input {rel_path!r} is missing or unreadable inside {target.app_package!r}; run without --skip-install to stage inputs",
            )
        if actual_digest != expected_digest.lower():
            raise QaGuardError(
                "STAGED_INPUT_MISMATCH",
                f"Staged QA input {rel_path!r} SHA-256 {actual_digest[:16]}... != expected {expected_digest[:16]}...; re-stage inputs",
            )
        staged_verified[rel_path] = actual_digest

    return {
        "verified": True,
        "device_alias": device_alias,
        "installed_app": installed_app,
        "installed_test": installed_test,
        "instrumentation_component": target.instrumentation_component,
        "staged_inputs_verified_count": len(staged_verified),
    }
