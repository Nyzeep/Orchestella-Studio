#!/usr/bin/env python3
"""Host orchestrator for W3-04 multi-instance UML matrix & 4 KB / 16 KB verification."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
from pathlib import Path
from typing import Any, Callable

BUCKETS: tuple[str, ...] = (
    "C1_COLD_BOOT",
    "C2_WARM_RECONNECT",
    "C3_BG_KEEPALIVE",
    "C4_FAULT_CONTENTION",
    "C5_DUAL_AND_RIDEALONG",
)
BG_BUCKETS: frozenset[str] = frozenset({"C3_BG_KEEPALIVE", "C5_DUAL_AND_RIDEALONG"})
PAGE_SIZE_ALIASES: dict[int, str] = {
    4096: "device-4k",
    16384: "device-16k",
}
DEFAULT_CYCLES_PER_BUCKET = 30
DEFAULT_PACKAGE = "io.github.nyzeep.orchestella.qa.w304"
INSTRUMENTATION_COMPONENT_SUFFIX = ".test/io.github.nyzeep.orchestella.W304MatrixProbe"
PAGE_ALIGNMENT_16K = 16384
RESIDUAL_PROCESS_TOKENS: tuple[str, ...] = (
    "libuml_kernel.so",
    "libuml_stub.so",
    "libuml_passt.so",
    "libuml_net.so",
    "libgvproxy.so",
)

_COMPAT_DUMPSYS_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("dumpsys_pagesize_compat_true", re.compile(r"\bpagesizecompat\s*[=:]\s*true\b", re.IGNORECASE)),
    ("dumpsys_uses_pagesize_compat_true", re.compile(r"\busespagesizecompat\s*[=:]\s*true\b", re.IGNORECASE)),
    ("dumpsys_is_pagesize_compat_true", re.compile(r"\bispagesizecompatenabled\s*[=:]\s*true\b", re.IGNORECASE)),
    ("dumpsys_compat_16kb", re.compile(r"\b(?:16kb[_ ]compat|compat_16kb)\b", re.IGNORECASE)),
)
_COMPAT_PROP_KEYS: tuple[str, ...] = (
    "debug.bionic.page_size_compat",
    "persist.vendor.page_size_compat",
    "ro.product.build.16kb_compat",
)
_PT_LOAD_RE = re.compile(r"^\s*LOAD\b.*?\s(0x[0-9a-fA-F]+|\d+)\s*$")
_RUN_ID_RE = re.compile(r"^run-(\d+)$")


def utc_now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def validate_host_page_size(raw: str) -> int:
    text = raw.strip()
    try:
        value = int(text)
    except ValueError as exc:
        raise ValueError(f"Unparseable PAGE_SIZE output: {text!r}") from exc
    if value not in PAGE_SIZE_ALIASES:
        raise ValueError(f"Unsupported host PAGE_SIZE {value}; expected one of {sorted(PAGE_SIZE_ALIASES)}")
    return value


def build_frozen_sample_plan(
    cycles_per_bucket: int = DEFAULT_CYCLES_PER_BUCKET,
    page_sizes: tuple[int, ...] = (4096, 16384),
) -> dict[str, Any]:
    if cycles_per_bucket < 1:
        raise ValueError("cycles_per_bucket must be >= 1")
    for ps in page_sizes:
        if ps not in PAGE_SIZE_ALIASES:
            raise ValueError(f"Unsupported page size in sample plan: {ps}")

    entries: list[dict[str, Any]] = []
    cycles_per_page_size: dict[str, int] = {}
    for ps in page_sizes:
        alias = PAGE_SIZE_ALIASES[ps]
        count_for_ps = 0
        for bucket in BUCKETS:
            for idx in range(1, cycles_per_bucket + 1):
                entries.append(
                    {
                        "sample_id": f"p{ps}-{bucket}-{idx:03d}",
                        "host_page_size": ps,
                        "device_alias": alias,
                        "bucket": bucket,
                        "cycle_index": idx,
                    }
                )
                count_for_ps += 1
        cycles_per_page_size[str(ps)] = count_for_ps

    return {
        "schema_version": 1,
        "total_cycles": len(entries),
        "cycles_per_bucket_per_page_size": cycles_per_bucket,
        "cycles_per_page_size": cycles_per_page_size,
        "buckets": list(BUCKETS),
        "entries": entries,
    }


def detect_compat_16kb_indicators(
    dumpsys_package_text: str,
    props: dict[str, str] | None = None,
) -> list[str]:
    indicators: list[str] = []
    for label, pattern in _COMPAT_DUMPSYS_PATTERNS:
        if pattern.search(dumpsys_package_text):
            indicators.append(label)
    if props:
        for key in _COMPAT_PROP_KEYS:
            val = str(props.get(key, "")).strip().lower()
            if val in ("1", "true", "yes", "on", "enabled"):
                indicators.append(f"prop:{key}={val}")
    return sorted(set(indicators))


def parse_dumpsys_package_meta(
    dumpsys_text: str,
    props: dict[str, str] | None = None,
) -> dict[str, Any]:
    version_name = None
    version_code = None
    min_sdk = None
    target_sdk = None

    m = re.search(r"\bversionName=([^\s]+)", dumpsys_text)
    if m:
        version_name = m.group(1)
    m = re.search(r"\bversionCode=(\d+)", dumpsys_text)
    if m:
        version_code = int(m.group(1))
    m = re.search(r"\bminSdk=(\d+)", dumpsys_text)
    if m:
        min_sdk = int(m.group(1))
    m = re.search(r"\btargetSdk=(\d+)", dumpsys_text)
    if m:
        target_sdk = int(m.group(1))

    return {
        "version_name": version_name,
        "version_code": version_code,
        "min_sdk": min_sdk,
        "target_sdk": target_sdk,
        "compat_16kb_indicators": detect_compat_16kb_indicators(dumpsys_text, props),
    }


def parse_instrumentation_output(text: str) -> dict[str, Any]:
    code: int | None = None
    stages: list[str] = []
    result: dict[str, str] = {}

    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("INSTRUMENTATION_STATUS:"):
            kv = line.split(":", 1)[1].strip()
            if "=" in kv:
                key, val = kv.split("=", 1)
                if key.strip() == "w304_stage":
                    stages.append(val.strip())
        elif line.startswith("INSTRUMENTATION_RESULT:"):
            kv = line.split(":", 1)[1].strip()
            if "=" in kv:
                key, val = kv.split("=", 1)
                result[key.strip()] = val.strip()
        elif line.startswith("INSTRUMENTATION_CODE:"):
            raw_code = line.split(":", 1)[1].strip()
            try:
                code = int(raw_code)
            except ValueError:
                code = None

    return {
        "code": code,
        "status_stages": stages,
        "result": result,
    }


def parse_fgs_active_for_package(dumpsys_services_text: str, package_name: str) -> bool:
    if not dumpsys_services_text or package_name not in dumpsys_services_text:
        return False
    has_service = "OrchestellaForegroundService" in dumpsys_services_text
    has_fg = (
        "isForeground=true" in dumpsys_services_text
        or "foregroundId=" in dumpsys_services_text
    )
    return has_service and has_fg


def find_residual_uml_processes(ps_output: str) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    for raw in ps_output.splitlines():
        line = raw.strip()
        if not line:
            continue
        for token in RESIDUAL_PROCESS_TOKENS:
            if token in line:
                parts = line.split()
                pid = None
                for part in parts:
                    if part.isdigit():
                        pid = int(part)
                        break
                matches.append(
                    {
                        "pid": pid,
                        "token": token,
                        "summary": " ".join(parts[:4])[:120],
                    }
                )
                break
    return matches


def _parse_elf_pt_load_aligns(so_path: Path, readelf_path: str) -> list[int]:
    proc = subprocess.run(
        [readelf_path, "-l", "-W", str(so_path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"readelf failed for {so_path.name}: {proc.stderr.strip() or proc.stdout.strip()}"
        )
    aligns: list[int] = []
    for line in proc.stdout.splitlines():
        match = _PT_LOAD_RE.match(line)
        if not match:
            continue
        raw_align = match.group(1)
        aligns.append(int(raw_align, 16) if raw_align.startswith("0x") else int(raw_align))
    return aligns


def verify_apk_16kb(
    apk_path: str | Path,
    readelf_path: str | None = None,
    zipalign_path: str | None = None,
    require_external_tools: bool = True,
) -> dict[str, Any]:
    path = Path(apk_path)
    entries_report: list[dict[str, Any]] = []
    stored_count = 0
    deflated_count = 0
    unsupported_comp_count = 0
    all_stored_aligned = True
    all_pt_load_ok: bool | None = True if readelf_path else None

    with zipfile.ZipFile(path, "r") as zf, open(path, "rb") as raw_fp:
        so_infos = [
            zi for zi in zf.infolist()
            if zi.filename.startswith("lib/arm64-v8a/") and zi.filename.endswith(".so")
        ]
        with tempfile.TemporaryDirectory() as tmp_dir:
            for zi in sorted(so_infos, key=lambda item: item.filename):
                raw_fp.seek(zi.header_offset + 26)
                name_len, local_extra_len = struct.unpack("<HH", raw_fp.read(4))
                data_offset = zi.header_offset + 30 + name_len + local_extra_len
                if zi.compress_type == zipfile.ZIP_STORED:
                    stored_count += 1
                    comp_label = "STORED"
                    zip_aligned: bool | None = (data_offset % PAGE_ALIGNMENT_16K == 0)
                    if not zip_aligned:
                        all_stored_aligned = False
                elif zi.compress_type == zipfile.ZIP_DEFLATED:
                    deflated_count += 1
                    comp_label = "DEFLATED"
                    zip_aligned = None  # Extracted to disk by PackageManager when extractNativeLibs=true
                else:
                    unsupported_comp_count += 1
                    comp_label = f"UNSUPPORTED_{zi.compress_type}"
                    zip_aligned = False

                min_align: int | None = None
                pt_load_ok: bool | None = None
                if readelf_path:
                    extracted = Path(tmp_dir) / Path(zi.filename).name
                    extracted.write_bytes(zf.read(zi.filename))
                    aligns = _parse_elf_pt_load_aligns(extracted, readelf_path)
                    if not aligns:
                        min_align = 0
                        pt_load_ok = False
                    else:
                        min_align = min(aligns)
                        pt_load_ok = min_align >= PAGE_ALIGNMENT_16K
                    if not pt_load_ok:
                        all_pt_load_ok = False

                entries_report.append(
                    {
                        "name": zi.filename,
                        "compression": comp_label,
                        "compress_type": zi.compress_type,
                        "data_offset": data_offset,
                        "zip_offset_16k_aligned": zip_aligned,
                        "min_pt_load_align": min_align,
                        "pt_load_16k_ok": pt_load_ok,
                    }
                )

    zipalign_ok: bool | None = None
    if zipalign_path:
        proc = subprocess.run(
            [zipalign_path, "-c", "-P", "16", "-v", "4", str(path)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=60,
        )
        zipalign_ok = proc.returncode == 0

    if stored_count > 0 and deflated_count == 0:
        packaging_mode = "uncompressed_stored"
    elif deflated_count > 0 and stored_count == 0:
        packaging_mode = "extracted_deflated"
    else:
        packaging_mode = "mixed"

    tools_ok = (
        (all_pt_load_ok is True and zipalign_ok is True)
        if require_external_tools
        else ((all_pt_load_ok is not False) and (zipalign_ok is not False))
    )
    passed = (
        len(entries_report) > 0
        and unsupported_comp_count == 0
        and all_stored_aligned
        and tools_ok
    )
    return {
        "apk_name": path.name,
        "packaging_mode": packaging_mode,
        "require_external_tools": require_external_tools,
        "so_count": len(entries_report),
        "stored_so_count": stored_count,
        "deflated_so_count": deflated_count,
        "all_stored_zip_offset_16k_aligned": all_stored_aligned,
        "all_pt_load_16k_aligned": all_pt_load_ok,
        "zipalign_p16_ok": zipalign_ok,
        "entries": entries_report,
        "passed": passed,
    }


def load_ledger(ledger_path: Path) -> list[dict[str, Any]]:
    if not ledger_path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with open(ledger_path, "r", encoding="utf-8") as fh:
        for line in fh:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def next_run_id(records: list[dict[str, Any]]) -> str:
    max_num = 0
    for row in records:
        rid = str(row.get("run_id", ""))
        match = _RUN_ID_RE.match(rid)
        if match:
            max_num = max(max_num, int(match.group(1)))
    return f"run-{max_num + 1:04d}"


def append_ledger_records(ledger_path: Path, new_records: list[dict[str, Any]]) -> None:
    if not new_records:
        return
    ledger_path.parent.mkdir(parents=True, exist_ok=True)
    with open(ledger_path, "a", encoding="utf-8", newline="\n") as fh:
        for rec in new_records:
            fh.write(json.dumps(rec, sort_keys=True) + "\n")
        fh.flush()


def latest_status_by_sample(records: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    latest: dict[str, dict[str, Any]] = {}
    for row in records:
        sid = row.get("sample_id")
        if sid:
            latest[str(sid)] = row
    return latest


def record_missing_samples(
    plan: dict[str, Any],
    ledger_path: Path,
    page_size: int,
    reason: str,
    run_id: str | None = None,
    timestamp_utc: str | None = None,
) -> list[dict[str, Any]]:
    if page_size not in PAGE_SIZE_ALIASES:
        raise ValueError(f"Unsupported page_size: {page_size}")
    existing = load_ledger(ledger_path)
    assigned_run_id = run_id or next_run_id(existing)
    latest = latest_status_by_sample(existing)
    ts = timestamp_utc or utc_now_iso()

    to_append: list[dict[str, Any]] = []
    for entry in plan["entries"]:
        if entry["host_page_size"] != page_size:
            continue
        sid = entry["sample_id"]
        if sid in latest and latest[sid].get("status") == "PASS":
            continue
        to_append.append(
            {
                "run_id": assigned_run_id,
                "sample_id": sid,
                "timestamp_utc": ts,
                "host_page_size": page_size,
                "device_alias": PAGE_SIZE_ALIASES[page_size],
                "bucket": entry["bucket"],
                "cycle_index": entry["cycle_index"],
                "status": "MISSING",
                "failure_code": reason,
                "stage": "preflight",
                "duration_ms": 0,
                "fgs_verified_in_bg": None,
                "residual_process_count": 0,
                "bad_disk_count": 0,
                "summary": reason,
            }
        )
    append_ledger_records(ledger_path, to_append)
    return to_append


def summarize_ledger(
    plan: dict[str, Any],
    records: list[dict[str, Any]],
    apk_verification: dict[str, Any] | None = None,
    qemu_regression: dict[str, Any] | None = None,
    compat_blocked: bool = False,
    is_emulator: bool = False,
) -> dict[str, Any]:
    latest = latest_status_by_sample(records)
    historical_failures = sum(1 for row in records if row.get("status") == "FAIL")
    historical_missing = sum(1 for row in records if row.get("status") == "MISSING")
    residual_events = sum(1 for row in records if (row.get("residual_process_count") or 0) > 0)
    bad_disk_events = sum(1 for row in records if (row.get("bad_disk_count") or 0) > 0)

    by_page_size: dict[str, dict[str, Any]] = {}
    total_latest_passed = 0
    total_latest_failed = 0
    total_latest_missing = 0
    total_unrun = 0
    missing_4k = 0
    missing_16k = 0

    for ps_key in sorted(plan["cycles_per_page_size"].keys(), key=int):
        ps_int = int(ps_key)
        ps_entries = [e for e in plan["entries"] if e["host_page_size"] == ps_int]
        ps_bucket_stats: dict[str, dict[str, int]] = {
            b: {"planned": 0, "latest_passed": 0, "latest_failed": 0, "latest_missing": 0, "unrun": 0}
            for b in BUCKETS
        }
        ps_passed = 0
        ps_failed = 0
        ps_missing = 0
        ps_unrun = 0

        for entry in ps_entries:
            b = entry["bucket"]
            ps_bucket_stats[b]["planned"] += 1
            row = latest.get(entry["sample_id"])
            status = row.get("status") if row else "UNRUN"
            if status == "PASS":
                ps_passed += 1
                ps_bucket_stats[b]["latest_passed"] += 1
            elif status == "FAIL":
                ps_failed += 1
                ps_bucket_stats[b]["latest_failed"] += 1
            elif status == "MISSING":
                ps_missing += 1
                ps_bucket_stats[b]["latest_missing"] += 1
            else:
                ps_unrun += 1
                ps_bucket_stats[b]["unrun"] += 1

        total_latest_passed += ps_passed
        total_latest_failed += ps_failed
        total_latest_missing += ps_missing
        total_unrun += ps_unrun
        if ps_int == 4096:
            missing_4k = ps_missing + ps_unrun
        elif ps_int == 16384:
            missing_16k = ps_missing + ps_unrun

        by_page_size[ps_key] = {
            "device_alias": PAGE_SIZE_ALIASES[ps_int],
            "planned": len(ps_entries),
            "latest_passed": ps_passed,
            "latest_failed": ps_failed,
            "latest_missing": ps_missing,
            "unrun": ps_unrun,
            "by_bucket": ps_bucket_stats,
        }

    apk_ok = apk_verification is not None and bool(apk_verification.get("passed"))
    qemu_ok = qemu_regression is not None and bool(qemu_regression.get("passed"))
    apk_failed = apk_verification is not None and not apk_ok
    qemu_failed = qemu_regression is not None and not qemu_ok

    if compat_blocked or total_latest_failed > 0 or apk_failed or qemu_failed:
        verdict = "FAIL"
    elif (
        total_latest_missing == 0
        and total_unrun == 0
        and total_latest_passed == plan["total_cycles"]
        and apk_ok
        and qemu_ok
    ):
        verdict = "EMULATOR_PREFLIGHT_ONLY" if is_emulator else "PASS"
    elif missing_4k == 0 and missing_16k > 0 and apk_ok and qemu_ok and not is_emulator:
        verdict = "BLOCKED_PENDING_16K_DEVICE"
    else:
        verdict = "INCOMPLETE"

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now_iso(),
        "total_planned": plan["total_cycles"],
        "total_ledger_rows": len(records),
        "latest_passed": total_latest_passed,
        "latest_failed": total_latest_failed,
        "latest_missing": total_latest_missing,
        "unrun": total_unrun,
        "historical_failures_preserved": historical_failures,
        "historical_missing_preserved": historical_missing,
        "residual_process_events": residual_events,
        "bad_disk_events": bad_disk_events,
        "compat_16kb_blocked": compat_blocked,
        "is_emulator": is_emulator,
        "apk_16kb_passed": apk_ok if apk_verification is not None else False,
        "qemu_regression_passed": qemu_ok if qemu_regression is not None else False,
        "verdict": verdict,
        "signoff_allowed": verdict == "PASS" and not is_emulator,
        "by_page_size": by_page_size,
    }


def _parse_device_serials(devices_output: str) -> list[str]:
    serials: list[str] = []
    for raw in devices_output.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


class AdbClient:
    def __init__(self, adb_path: str, explicit_serial: str | None = None):
        self.adb_path = adb_path
        self.explicit_serial = explicit_serial
        self._resolved_serial: str | None = None

    def discover_device(self) -> str:
        if self._resolved_serial:
            return self._resolved_serial
        out = self.run_raw("devices", "-l")
        serials = _parse_device_serials(out)
        if self.explicit_serial:
            if self.explicit_serial not in serials:
                raise RuntimeError("Requested device is not connected in 'device' state.")
            self._resolved_serial = self.explicit_serial
            return self._resolved_serial
        if len(serials) != 1:
            raise RuntimeError(f"Expected exactly 1 connected Android device, found {len(serials)}.")
        self._resolved_serial = serials[0]
        return self._resolved_serial

    def base_cmd(self, serial: str | None = None) -> list[str]:
        target = serial or self._resolved_serial
        cmd = [self.adb_path]
        if target:
            cmd += ["-s", target]
        return cmd

    def run_raw(self, *args: str, timeout: int = 120) -> str:
        completed = subprocess.run(
            [self.adb_path, *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if completed.returncode != 0:
            raise RuntimeError(
                f"adb {' '.join(args)} failed: {completed.stderr.strip() or completed.stdout.strip()}"
            )
        return completed.stdout

    def run(self, *args: str, timeout: int = 120, check: bool = True) -> subprocess.CompletedProcess[str]:
        serial = self.discover_device()
        completed = subprocess.run(
            [*self.base_cmd(serial), *args],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if check and completed.returncode != 0:
            raise RuntimeError(
                f"adb {' '.join(args)} failed: {completed.stderr.strip() or completed.stdout.strip()}"
            )
        return completed


def _read_sha256_digests(assets_dir: Path | None, apk_path: Path | None = None) -> dict[str, list[str]]:
    manifest_names = (
        "uml-fast-profile-v1.sha256",
        "uml-net-helper-v1.sha256",
        "runtime-artifacts-v1.sha256",
    )
    digests: dict[str, list[str]] = {}
    if assets_dir and assets_dir.is_dir():
        for name in manifest_names:
            fp = assets_dir / name
            if fp.is_file():
                digests[name] = [
                    line.strip() for line in fp.read_text(encoding="utf-8").splitlines() if line.strip()
                ]
    if not digests and apk_path and apk_path.is_file():
        with zipfile.ZipFile(apk_path, "r") as zf:
            names = set(zf.namelist())
            for name in manifest_names:
                entry = f"assets/{name}"
                if entry in names:
                    text = zf.read(entry).decode("utf-8", errors="replace")
                    digests[name] = [line.strip() for line in text.splitlines() if line.strip()]
            if "assets/NOTICE.UML" in names:
                notice_lines = [
                    line.strip()
                    for line in zf.read("assets/NOTICE.UML").decode("utf-8", errors="replace").splitlines()
                    if line.strip()
                ]
                if notice_lines:
                    digests["NOTICE.UML_head"] = notice_lines[:3]
    return digests


def _resolve_tool(explicit: str | None, candidate_names: tuple[str, ...]) -> str | None:
    if explicit:
        return explicit
    for name in candidate_names:
        found = shutil.which(name)
        if found:
            return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        sdk_path = Path(sdk)
        if "adb" in candidate_names or "adb.exe" in candidate_names:
            for cand in (sdk_path / "platform-tools" / "adb.exe", sdk_path / "platform-tools" / "adb"):
                if cand.is_file():
                    return str(cand)
        if "zipalign" in candidate_names or "zipalign.exe" in candidate_names:
            bt = sdk_path / "build-tools"
            if bt.is_dir():
                for ver in sorted(bt.iterdir(), reverse=True):
                    for exe in ("zipalign.exe", "zipalign"):
                        cand = ver / exe
                        if cand.is_file():
                            return str(cand)
        if "llvm-readelf" in candidate_names or "llvm-readelf.exe" in candidate_names:
            ndk = sdk_path / "ndk"
            if ndk.is_dir():
                for ver in sorted(ndk.iterdir(), reverse=True):
                    for host in ("windows-x86_64", "linux-x86_64", "darwin-x86_64"):
                        for exe in ("llvm-readelf.exe", "llvm-readelf"):
                            cand = ver / "toolchains" / "llvm" / "prebuilt" / host / "bin" / exe
                            if cand.is_file():
                                return str(cand)
    return None


def probe_device_metadata(
    adb: AdbClient,
    package_name: str,
    assets_dir: Path | None = None,
    apk_path: Path | None = None,
) -> dict[str, Any]:
    page_raw = adb.run("shell", "getconf", "PAGE_SIZE").stdout
    host_page_size = validate_host_page_size(page_raw)
    sdk_int = int(adb.run("shell", "getprop", "ro.build.version.sdk").stdout.strip())
    android_release = adb.run("shell", "getprop", "ro.build.version.release").stdout.strip()
    abi = adb.run("shell", "getprop", "ro.product.cpu.abi").stdout.strip()
    kernel_release = adb.run("shell", "uname", "-r").stdout.strip()
    qemu_kernel = adb.run("shell", "getprop", "ro.kernel.qemu", check=False).stdout.strip()
    qemu_boot = adb.run("shell", "getprop", "ro.boot.qemu", check=False).stdout.strip()
    is_emulator = qemu_kernel == "1" or qemu_boot == "1"

    props: dict[str, str] = {}
    for prop_key in _COMPAT_PROP_KEYS:
        val = adb.run("shell", "getprop", prop_key, check=False).stdout.strip()
        if val:
            props[prop_key] = val

    dumpsys_pkg = adb.run("shell", "dumpsys", "package", package_name, check=False).stdout
    pkg_meta = parse_dumpsys_package_meta(dumpsys_pkg, props)

    return {
        "probed_at_utc": utc_now_iso(),
        "device_alias": PAGE_SIZE_ALIASES[host_page_size],
        "device_kind": "emulator" if is_emulator else "physical",
        "is_emulator": is_emulator,
        "host_page_size": host_page_size,
        "sdk_int": sdk_int,
        "android_release": android_release,
        "abi": abi,
        "kernel_release": kernel_release,
        "package_name": package_name,
        "package_version_name": pkg_meta["version_name"],
        "package_version_code": pkg_meta["version_code"],
        "compat_16kb_indicators": pkg_meta["compat_16kb_indicators"],
        "payload_digests": _read_sha256_digests(assets_dir, apk_path=apk_path),
    }


def _check_residual_and_bad_disks(adb: AdbClient, package_name: str) -> tuple[list[dict[str, Any]], int]:
    ps_out = adb.run("shell", "ps", "-A", check=False).stdout
    residuals = find_residual_uml_processes(ps_out)
    find_out = adb.run(
        "shell",
        "run-as",
        package_name,
        "find",
        "files/w304-matrix",
        "-maxdepth",
        "4",
        "(",
        "-name",
        "*bad*",
        "-o",
        "-name",
        "*.part*",
        "-o",
        "-name",
        "*corrupt*",
        ")",
        check=False,
    ).stdout
    bad_lines = [line.strip() for line in find_out.splitlines() if line.strip() and "No such file" not in line]
    return residuals, len(bad_lines)


def _stream_command_lines(
    cmd: list[str],
    timeout_s: float,
    on_line: Callable[[str], None] | None = None,
) -> tuple[int, list[str]]:
    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
    )
    assert proc.stdout is not None
    lines: list[str] = []
    timed_out = threading.Event()

    def _on_timeout() -> None:
        timed_out.set()
        try:
            proc.kill()
        except OSError:
            pass

    timer = threading.Timer(timeout_s, _on_timeout)
    timer.daemon = True
    timer.start()
    try:
        for raw_bytes in iter(proc.stdout.readline, b""):
            line = raw_bytes.decode("utf-8", errors="replace")
            lines.append(line)
            if on_line is not None:
                on_line(line)
        if timed_out.is_set():
            raise TimeoutError(f"Instrumentation timed out after {timeout_s}s")
        returncode = proc.wait(timeout=max(5.0, float(timeout_s)))
        if timed_out.is_set():
            raise TimeoutError(f"Instrumentation timed out after {timeout_s}s")
        return returncode, lines
    finally:
        timer.cancel()
        if proc.poll() is None:
            try:
                proc.kill()
                proc.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                pass
        try:
            proc.stdout.close()
        except OSError:
            pass


def _run_instrumentation(
    adb: AdbClient,
    package_name: str,
    extras: dict[str, str],
    timeout_s: int,
    raw_log_path: Path,
    trigger_bg_on_hold: bool = False,
) -> tuple[dict[str, Any], bool | None]:
    serial = adb.discover_device()
    cmd = [*adb.base_cmd(serial), "shell", "am", "instrument", "-r", "-w"]
    for key, val in extras.items():
        cmd += ["-e", key, val]
    cmd.append(f"{package_name}{INSTRUMENTATION_COMPONENT_SUFFIX}")

    raw_log_path.parent.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    fgs_verified: list[bool] = []
    bg_thread: threading.Thread | None = None
    bg_triggered = False

    def _on_bg_hold_ready() -> None:
        time.sleep(0.15)
        adb.run("shell", "input", "keyevent", "KEYCODE_HOME", timeout=15, check=False)
        time.sleep(0.45)
        svc_dump = adb.run(
            "shell", "dumpsys", "activity", "services", package_name, timeout=15, check=False
        ).stdout
        fgs_verified.append(parse_fgs_active_for_package(svc_dump, package_name))

    def _handle_line(line: str) -> None:
        nonlocal bg_thread, bg_triggered
        if trigger_bg_on_hold and not bg_triggered and "w304_stage=bg_hold_ready" in line:
            bg_triggered = True
            bg_thread = threading.Thread(target=_on_bg_hold_ready, daemon=True)
            bg_thread.start()

    try:
        _, lines = _stream_command_lines(cmd, timeout_s=float(timeout_s), on_line=_handle_line)
    finally:
        if bg_thread is not None:
            bg_thread.join(timeout=5)
        raw_log_path.write_text("".join(lines), encoding="utf-8", errors="replace")

    parsed = parse_instrumentation_output("".join(lines))
    fgs_state: bool | None = None
    if trigger_bg_on_hold:
        fgs_state = bool(bg_triggered and fgs_verified and all(fgs_verified))
    return parsed, fgs_state


def exit_code_for_summary(summary: dict[str, Any], allow_pending_16k: bool = False) -> int:
    verdict = summary.get("verdict")
    if verdict == "PASS":
        return 0
    if verdict == "BLOCKED_PENDING_16K_DEVICE":
        return 0 if allow_pending_16k else 3
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=None, help="Path to adb executable")
    parser.add_argument("--serial", default=None, help="Optional target device serial")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--apk", default=None, help="Path to QA app debug APK")
    parser.add_argument("--test-apk", default=None, help="Path to QA androidTest APK")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--readelf", default=None, help="Path to llvm-readelf")
    parser.add_argument("--zipalign", default=None, help="Path to zipalign")
    parser.add_argument("--assets-dir", default=None, help="Path to staged assets directory with .sha256 manifests")
    parser.add_argument("--output-dir", required=True, help="External evidence output directory")
    parser.add_argument("--cycles-per-bucket", type=int, default=DEFAULT_CYCLES_PER_BUCKET)
    parser.add_argument("--max-cycles-per-bucket", type=int, default=None,
                        help="Execute only the first N cycles per bucket (e.g. 1 for smoke runs)")
    parser.add_argument("--buckets", default=",".join(BUCKETS),
                        help="Comma-separated subset of buckets to execute")
    parser.add_argument("--bg-hold-ms", type=int, default=1500)
    parser.add_argument("--cycle-timeout-s", type=int, default=180)
    parser.add_argument("--prepare-timeout-s", type=int, default=180)
    parser.add_argument("--resume", action="store_true", help="Skip samples already PASS in ledger.jsonl")
    parser.add_argument("--skip-prepare", action="store_true")
    parser.add_argument("--skip-qemu-regression", action="store_true")
    parser.add_argument("--record-missing-page-size", type=int, action="append", default=[],
                        help="Append MISSING rows for all entries of a host page size not connected")
    parser.add_argument("--missing-reason", default="no-physical-device-connected")
    parser.add_argument("--allow-pending-16k", action="store_true",
                        help="Return exit code 0 when verdict is BLOCKED_PENDING_16K_DEVICE")
    args = parser.parse_args(argv)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = out_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    plan = build_frozen_sample_plan(cycles_per_bucket=args.cycles_per_bucket)
    (out_dir / "sample-plan.json").write_text(json.dumps(plan, indent=2, sort_keys=True), encoding="utf-8")

    readelf_path = _resolve_tool(args.readelf, ("llvm-readelf.exe", "llvm-readelf"))
    zipalign_path = _resolve_tool(args.zipalign, ("zipalign.exe", "zipalign"))
    apk_verification: dict[str, Any] | None = None
    if args.apk:
        apk_verification = verify_apk_16kb(args.apk, readelf_path=readelf_path, zipalign_path=zipalign_path)
        (out_dir / "apk-16kb.json").write_text(
            json.dumps(apk_verification, indent=2, sort_keys=True), encoding="utf-8"
        )
        if not apk_verification["passed"]:
            print("APK 16 KB alignment verification FAILED", file=sys.stderr)
            return 2

    adb_path = _resolve_tool(args.adb, ("adb.exe", "adb"))
    if not adb_path:
        raise RuntimeError("Could not locate adb; pass --adb or set ANDROID_HOME")
    adb = AdbClient(adb_path, args.serial)
    adb.discover_device()

    if not args.skip_install:
        if args.apk:
            adb.run("install", "-r", "-t", args.apk, timeout=300)
        if args.test_apk:
            adb.run("install", "-r", "-t", args.test_apk, timeout=300)

    assets_dir = Path(args.assets_dir) if args.assets_dir else None
    apk_path = Path(args.apk) if args.apk else None
    meta = probe_device_metadata(adb, args.package, assets_dir=assets_dir, apk_path=apk_path)
    (out_dir / "device-meta.json").write_text(json.dumps(meta, indent=2, sort_keys=True), encoding="utf-8")

    host_ps = meta["host_page_size"]
    is_emulator = bool(meta.get("is_emulator"))
    compat_blocked = host_ps == 16384 and len(meta["compat_16kb_indicators"]) > 0
    ledger_path = out_dir / "ledger.jsonl"
    existing_rows = load_ledger(ledger_path)
    run_id = next_run_id(existing_rows)

    if compat_blocked:
        append_ledger_records(
            ledger_path,
            [
                {
                    "run_id": run_id,
                    "sample_id": f"p{host_ps}-COMPAT-CHECK",
                    "timestamp_utc": utc_now_iso(),
                    "host_page_size": host_ps,
                    "device_alias": meta["device_alias"],
                    "bucket": "PREFLIGHT",
                    "cycle_index": 0,
                    "status": "FAIL",
                    "failure_code": "COMPAT_16KB_MODE_BLOCKED",
                    "stage": "preflight",
                    "duration_ms": 0,
                    "fgs_verified_in_bg": None,
                    "residual_process_count": 0,
                    "bad_disk_count": 0,
                    "summary": ",".join(meta["compat_16kb_indicators"]),
                }
            ],
        )
        summary = summarize_ledger(
            plan,
            load_ledger(ledger_path),
            apk_verification,
            None,
            compat_blocked=True,
            is_emulator=is_emulator,
        )
        (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
        print(json.dumps(summary, indent=2))
        return 1

    adb.run("shell", "am", "force-stop", args.package, check=False)

    if not args.skip_prepare:
        prep_parsed, _ = _run_instrumentation(
            adb,
            args.package,
            {"action": "prepare"},
            timeout_s=args.prepare_timeout_s,
            raw_log_path=raw_dir / f"{run_id}-prepare.log",
        )
        prep_res = prep_parsed["result"]
        if prep_parsed["code"] != -1 or prep_res.get("w304_result") != "PASS":
            raise RuntimeError(f"W304 prepare failed: {prep_parsed}")
        residuals, bad_disks = _check_residual_and_bad_disks(adb, args.package)
        if residuals or bad_disks > 0:
            raise RuntimeError(f"Post-prepare hygiene failed: residuals={residuals}, bad_disks={bad_disks}")

    selected_buckets = [b.strip() for b in args.buckets.split(",") if b.strip()]
    for b in selected_buckets:
        if b not in BUCKETS:
            raise ValueError(f"Unknown bucket {b!r}")

    latest_map = latest_status_by_sample(existing_rows) if args.resume else {}
    max_cycles = args.max_cycles_per_bucket or args.cycles_per_bucket

    for bucket in selected_buckets:
        for cycle_idx in range(1, max_cycles + 1):
            sample_id = f"p{host_ps}-{bucket}-{cycle_idx:03d}"
            if args.resume and latest_map.get(sample_id, {}).get("status") == "PASS":
                continue

            trigger_bg = bucket in BG_BUCKETS
            t0 = time.monotonic()
            failure_code: str | None = None
            stage = "unknown"
            detail = ""
            fgs_state: bool | None = None
            try:
                parsed, fgs_state = _run_instrumentation(
                    adb,
                    args.package,
                    {
                        "action": "cycle",
                        "bucket": bucket,
                        "cycle_index": str(cycle_idx),
                        "bg_hold_ms": str(args.bg_hold_ms),
                    },
                    timeout_s=args.cycle_timeout_s,
                    raw_log_path=raw_dir / f"{run_id}-{sample_id}.log",
                    trigger_bg_on_hold=trigger_bg,
                )
                res = parsed["result"]
                if parsed["code"] != -1 or res.get("w304_result") != "PASS":
                    failure_code = res.get("w304_error") or "INSTRUMENTATION_FAIL"
                    stage = res.get("w304_stage") or "instrument"
                    detail = res.get("w304_detail") or f"code={parsed['code']}"
                elif trigger_bg and not fgs_state:
                    failure_code = "BG_FGS_NOT_VERIFIED"
                    stage = "host-bg-fgs-check"
                    detail = "OrchestellaForegroundService missing or not foreground after KEYCODE_HOME"
                else:
                    stage = "done"
                    detail = res.get("w304_summary", "ok")
            except Exception as exc:  # noqa: BLE001
                failure_code = exc.__class__.__name__
                stage = "host-runner"
                detail = str(exc)[:160]

            residuals, bad_disks = _check_residual_and_bad_disks(adb, args.package)
            if residuals:
                failure_code = failure_code or "RESIDUAL_UML_PROCESS"
                detail = f"residual_pids={[r['token'] for r in residuals]}"
                adb.run("shell", "am", "force-stop", args.package, check=False)
            if bad_disks > 0:
                failure_code = failure_code or "BAD_DISK_DETECTED"
                detail = f"bad_disk_count={bad_disks}"

            duration_ms = int((time.monotonic() - t0) * 1000)
            row = {
                "run_id": run_id,
                "sample_id": sample_id,
                "timestamp_utc": utc_now_iso(),
                "host_page_size": host_ps,
                "device_alias": meta["device_alias"],
                "bucket": bucket,
                "cycle_index": cycle_idx,
                "status": "PASS" if failure_code is None else "FAIL",
                "failure_code": failure_code,
                "stage": stage,
                "duration_ms": duration_ms,
                "fgs_verified_in_bg": fgs_state,
                "residual_process_count": len(residuals),
                "bad_disk_count": bad_disks,
                "summary": detail,
            }
            append_ledger_records(ledger_path, [row])
            print(f"[{run_id}] {sample_id}: {row['status']} ({duration_ms}ms) {detail}", flush=True)

    qemu_regression: dict[str, Any] | None = None
    if not args.skip_qemu_regression:
        q_parsed, _ = _run_instrumentation(
            adb,
            args.package,
            {"action": "qemu_regression"},
            timeout_s=args.cycle_timeout_s,
            raw_log_path=raw_dir / f"{run_id}-qemu-regression.log",
        )
        q_res = q_parsed["result"]
        q_residuals, q_bad_disks = _check_residual_and_bad_disks(adb, args.package)
        q_passed = (
            q_parsed["code"] == -1
            and q_res.get("w304_result") == "PASS"
            and len(q_residuals) == 0
            and q_bad_disks == 0
        )
        qemu_regression = {
            "run_id": run_id,
            "passed": q_passed,
            "result": q_res,
            "residual_process_count": len(q_residuals),
            "bad_disk_count": q_bad_disks,
        }
        (out_dir / "qemu-regression.json").write_text(
            json.dumps(qemu_regression, indent=2, sort_keys=True), encoding="utf-8"
        )

    for missing_ps in args.record_missing_page_size:
        record_missing_samples(
            plan=plan,
            ledger_path=ledger_path,
            page_size=missing_ps,
            reason=args.missing_reason,
            run_id=run_id,
        )

    summary = summarize_ledger(
        plan=plan,
        records=load_ledger(ledger_path),
        apk_verification=apk_verification,
        qemu_regression=qemu_regression,
        compat_blocked=compat_blocked,
        is_emulator=is_emulator,
    )
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return exit_code_for_summary(summary, allow_pending_16k=args.allow_pending_16k)


if __name__ == "__main__":
    sys.exit(main())
