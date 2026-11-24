#!/usr/bin/env python3
"""Host paired-benchmark orchestrator for W3-05 (R-05) UML vs QEMU verification."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
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

DISTRO = "alpine"
DISTRO_VERSION = "3.22.2"
ROOTFS_TAR = "alpine-minirootfs-3.22.2-aarch64.tar.gz"
ROOTFS_SHA256 = "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489"
VCPU = 1
RAM_MB = 512
NETWORK_MODE = "offline"
PERCENTILE_FORMULA = "nearest_rank_ceil_p_times_n"

ENGINES: tuple[str, ...] = ("uml", "qemu")
BUCKETS: tuple[str, ...] = (
    "first_provision",
    "cold_provisioned",
    "warm_restart",
    "warm_reconnect",
)
GATE_BUCKETS: tuple[str, ...] = ("cold_provisioned", "warm_restart")

PAGE_SIZE_ALIASES: dict[int, str] = {
    4096: "device-4k",
    16384: "device-16k",
}
DEFAULT_MISSING_REASONS: dict[int, str] = {
    4096: "no-physical-4k-device-connected",
    16384: "no-physical-16k-device-connected",
}

DEFAULT_PACKAGE = "io.github.nyzeep.orchestella.qa.w305"
INSTRUMENTATION_COMPONENT_SUFFIX = ".test/io.github.nyzeep.orchestella.W305PerfBenchProbe"

DEFAULT_COLD_PAIRS = 30
DEFAULT_WARM_PAIRS = 30
DEFAULT_FIRST_PROVISION_PAIRS = 5
DEFAULT_WARM_RECONNECT_PAIRS = 30
DEFAULT_WARM_BATCH_SIZE = 5
DEFAULT_TIMEOUT_SEC = 120
DEFAULT_PREPARE_TIMEOUT_SEC = 300
DEFAULT_MAX_TEMP_C = 42.0

MIN_PAIRS = 30
COLD_P95_LIMIT_MS = 10000
WARM_P95_LIMIT_MS = 3000
MIN_SPEEDUP_RATIO = 0.20

PAGE_ALIGNMENT_16K = 16384
APK_PAYLOAD_MANIFESTS: tuple[str, ...] = (
    "uml-fast-profile-v1.sha256",
    "uml-net-helper-v1.sha256",
    "runtime-artifacts-v1.sha256",
)
RESIDUAL_PROCESS_TOKENS: tuple[str, ...] = (
    "libuml_kernel.so",
    "libuml_stub.so",
    "libuml_passt.so",
    "libuml_net.so",
    "libgvproxy.so",
    "libdroidlayer_qemu.so",
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
_DEVICE_ALIAS_RE = re.compile(r"^[A-Za-z0-9._-]{1,48}$")
_BATTERY_TEMP_RE = re.compile(r"(?im)^\s*temperature\s*:\s*(-?\d+)\s*$")
_WIN_PATH_RE = re.compile(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/][^\s\"'`]+")
_HOME_PATH_RE = re.compile(r"/(?:home|Users)/[A-Za-z0-9._-]+(?:/[^\s\"'`]*)?")
_CONTROL_WS_RE = re.compile(r"[\r\n\t]+")


def validate_device_alias(alias: str) -> str:
    cleaned = alias.strip()
    if not _DEVICE_ALIAS_RE.match(cleaned):
        raise ValueError(
            f"Unsafe device alias {alias!r}; expected 1..48 chars matching [A-Za-z0-9._-]"
        )
    return cleaned


def validate_host_page_size(raw: str) -> int:
    text = raw.strip()
    try:
        value = int(text)
    except ValueError as exc:
        raise ValueError(f"Unparseable PAGE_SIZE output: {text!r}") from exc
    if value not in PAGE_SIZE_ALIASES:
        raise ValueError(
            f"Unsupported host PAGE_SIZE {value}; expected one of {sorted(PAGE_SIZE_ALIASES)}"
        )
    return value


def utc_now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sanitize_text(text: str | None, max_len: int = 160) -> str | None:
    if text is None:
        return None
    cleaned = _CONTROL_WS_RE.sub(" ", str(text)).strip()
    if not cleaned:
        return None
    cleaned = _WIN_PATH_RE.sub("<redacted-path>", cleaned)
    cleaned = _HOME_PATH_RE.sub("<redacted-path>", cleaned)
    if len(cleaned) > max_len:
        cleaned = cleaned[:max_len]
    return cleaned


def pair_engine_order(pair_index: int) -> tuple[str, str]:
    if pair_index < 1:
        raise ValueError(f"pair_index must be >= 1, got {pair_index}")
    return ("uml", "qemu") if pair_index % 2 == 1 else ("qemu", "uml")


def pair_order_label(pair_index: int) -> str:
    if pair_index < 1:
        raise ValueError(f"pair_index must be >= 1, got {pair_index}")
    return "uml_first" if pair_index % 2 == 1 else "qemu_first"


def contiguous_pair_batches(pair_indices: list[int], batch_size: int) -> list[list[int]]:
    if batch_size < 1:
        raise ValueError("batch_size must be >= 1")
    ordered = sorted(set(pair_indices))
    if not ordered:
        return []
    batches: list[list[int]] = []
    current: list[int] = [ordered[0]]
    for idx in ordered[1:]:
        if idx == current[-1] + 1 and len(current) < batch_size:
            current.append(idx)
        else:
            batches.append(current)
            current = [idx]
    batches.append(current)
    return batches


def make_sample_id(host_page_size: int, bucket: str, pair_index: int, engine: str) -> str:
    return f"p{host_page_size}-{bucket}-pair{pair_index:03d}-{engine}"


def clean_uninstall_orchestella_packages(
    adb: Any,
    package_prefix: str = "io.github.nyzeep.orchestella",
) -> list[str]:
    listed = adb.run("shell", "pm", "list", "packages", package_prefix, check=False).stdout
    removed: list[str] = []
    for raw in listed.splitlines():
        line = raw.strip()
        if not line.startswith("package:"):
            continue
        pkg = line[len("package:"):].strip()
        if pkg and pkg.startswith(package_prefix):
            adb.run("uninstall", pkg, timeout=60, check=False)
            removed.append(pkg)
    return removed


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


def parse_battery_temperature_c(dumpsys_battery_text: str) -> float | None:
    if not dumpsys_battery_text:
        return None
    match = _BATTERY_TEMP_RE.search(dumpsys_battery_text)
    if not match:
        return None
    try:
        tenths = int(match.group(1))
    except ValueError:
        return None
    return round(tenths / 10.0, 1)


def read_battery_temperature_c(adb: Any) -> float | None:
    out = adb.run("shell", "dumpsys", "battery", timeout=15, check=False).stdout
    return parse_battery_temperature_c(out)


def wait_for_thermal_cooldown(
    adb: Any,
    max_temp_c: float | None,
    poll_sec: float = 10.0,
    max_wait_sec: float = 300.0,
    sleep_fn: Callable[[float], None] = time.sleep,
    monotonic_fn: Callable[[], float] = time.monotonic,
) -> tuple[float | None, bool]:
    temp = read_battery_temperature_c(adb)
    if max_temp_c is None or max_temp_c <= 0 or temp is None or temp <= max_temp_c:
        return temp, False

    waited = False
    deadline = monotonic_fn() + max(0.0, max_wait_sec)
    while temp is not None and temp > max_temp_c and monotonic_fn() < deadline:
        waited = True
        remaining = deadline - monotonic_fn()
        sleep_fn(min(max(0.01, poll_sec), max(0.0, remaining)))
        temp = read_battery_temperature_c(adb)
    return temp, waited


def find_residual_processes(ps_output: str) -> list[dict[str, Any]]:
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
                    zip_aligned = None
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


def extract_apk_payload_manifests(
    apk_path: Path | None,
    assets_dir: Path | None = None,
) -> dict[str, list[str]]:
    digests: dict[str, list[str]] = {}
    if apk_path and apk_path.is_file():
        with zipfile.ZipFile(apk_path, "r") as zf:
            names = set(zf.namelist())
            for name in APK_PAYLOAD_MANIFESTS:
                entry = f"assets/{name}"
                if entry in names:
                    text = zf.read(entry).decode("utf-8", errors="replace")
                    digests[name] = [line.strip() for line in text.splitlines() if line.strip()]
    if not digests and assets_dir and assets_dir.is_dir():
        for name in APK_PAYLOAD_MANIFESTS:
            fp = assets_dir / name
            if fp.is_file():
                digests[name] = [
                    line.strip()
                    for line in fp.read_text(encoding="utf-8", errors="replace").splitlines()
                    if line.strip()
                ]
    return digests


def _coerce_optional_int(val: Any) -> int | None:
    if val is None or isinstance(val, bool):
        return None
    if isinstance(val, int):
        return val
    if isinstance(val, float):
        return int(round(val))
    text = str(val).strip()
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        try:
            return int(round(float(text)))
        except ValueError:
            return None


def _pick_field(raw: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in raw and raw[name] is not None and raw[name] != "":
            return raw[name]
    return None


def normalize_sample_payload(
    raw: dict[str, Any],
    default_bucket: str | None = None,
    default_engine: str | None = None,
    default_pair_index: int | None = None,
) -> dict[str, Any]:
    bucket = _pick_field(raw, "bucket", "w305_bucket") or default_bucket
    engine_raw = _pick_field(raw, "engine", "w305_engine") or default_engine
    engine = str(engine_raw).strip().lower() if engine_raw is not None else None

    pair_index = _coerce_optional_int(
        _pick_field(raw, "pair_index", "w305_pair_index", "index", "iteration")
    )
    if pair_index is None:
        pair_index = default_pair_index

    order = _pick_field(raw, "order", "w305_order")
    if order is None and pair_index is not None and pair_index >= 1:
        order = pair_order_label(pair_index)

    result_raw = _pick_field(raw, "result", "status", "w305_result")
    result_str = str(result_raw).strip().upper() if result_raw is not None else "FAIL"
    if result_str not in ("PASS", "FAIL", "TIMEOUT"):
        result_str = "FAIL"

    return {
        "bucket": str(bucket).strip() if bucket is not None else None,
        "engine": engine,
        "pair_index": pair_index,
        "order": str(order).strip() if order is not None else None,
        "status": result_str,
        "t0_to_shell_ready_ms": _coerce_optional_int(
            _pick_field(raw, "t0_to_shell_ready_ms", "w305_t0_to_shell_ready_ms")
        ),
        "t0_to_bridge_ready_ms": _coerce_optional_int(
            _pick_field(raw, "t0_to_bridge_ready_ms", "w305_t0_to_bridge_ready_ms")
        ),
        "t0_to_assets_ms": _coerce_optional_int(
            _pick_field(raw, "t0_to_assets_ms", "w305_t0_to_assets_ms")
        ),
        "t0_to_engine_started_ms": _coerce_optional_int(
            _pick_field(raw, "t0_to_engine_started_ms", "w305_t0_to_engine_started_ms")
        ),
        "t0_to_session_registered_ms": _coerce_optional_int(
            _pick_field(raw, "t0_to_session_registered_ms", "w305_t0_to_session_registered_ms")
        ),
        "reattach_ms": _coerce_optional_int(
            _pick_field(raw, "reattach_ms", "w305_reattach_ms")
        ),
        "roundtrip_ms": _coerce_optional_int(
            _pick_field(raw, "roundtrip_ms", "w305_roundtrip_ms")
        ),
        "credentials_only_reason": sanitize_text(
            _pick_field(raw, "credentials_only_reason", "w305_credentials_only_reason"),
            max_len=80,
        ),
        "stop_result": sanitize_text(
            _pick_field(raw, "stop_result", "w305_stop_result"),
            max_len=80,
        ),
        "error": sanitize_text(_pick_field(raw, "error", "w305_error"), max_len=80),
        "detail": sanitize_text(_pick_field(raw, "detail", "w305_detail"), max_len=160),
    }


def parse_instrumentation_output(text: str) -> dict[str, Any]:
    code: int | None = None
    stages: list[str] = []
    samples: list[dict[str, Any]] = []
    sample_json_errors: list[str] = []
    result: dict[str, str] = {}
    failed_lines: list[str] = []

    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("INSTRUMENTATION_STATUS:"):
            kv = line.split(":", 1)[1].strip()
            if "=" in kv:
                key, val = kv.split("=", 1)
                key = key.strip()
                val = val.strip()
                if key == "w305_stage":
                    stages.append(val)
                elif key == "w305_sample_json":
                    try:
                        parsed_obj = json.loads(val)
                    except json.JSONDecodeError as exc:
                        sample_json_errors.append(f"json_decode_error:{exc.msg}")
                        continue
                    if not isinstance(parsed_obj, dict):
                        sample_json_errors.append("sample_json_not_object")
                        continue
                    samples.append(normalize_sample_payload(parsed_obj))
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
        elif line.startswith("INSTRUMENTATION_FAILED:"):
            failed_lines.append(sanitize_text(line.split(":", 1)[1].strip(), max_len=160) or "failed")

    return {
        "code": code,
        "status_stages": stages,
        "samples": samples,
        "sample_json_errors": sample_json_errors,
        "result": result,
        "failed_lines": failed_lines,
    }


def extract_warm_pair_samples(
    parsed: dict[str, Any],
    pair_index: int,
    order_label: str,
) -> list[dict[str, Any]]:
    if parsed.get("samples"):
        out: list[dict[str, Any]] = []
        for s in parsed["samples"]:
            copied = dict(s)
            copied["bucket"] = copied.get("bucket") or "warm_restart"
            if copied.get("pair_index") is None:
                copied["pair_index"] = pair_index
            if copied.get("order") is None:
                copied["order"] = order_label
            out.append(copied)
        return out

    result = parsed.get("result") or {}
    fallback: list[dict[str, Any]] = []
    for engine in pair_engine_order(pair_index):
        prefix = f"w305_{engine}_"
        eng_fields = {k[len(prefix):]: v for k, v in result.items() if k.startswith(prefix)}
        if eng_fields:
            fallback.append(
                normalize_sample_payload(
                    eng_fields,
                    default_bucket="warm_restart",
                    default_engine=engine,
                    default_pair_index=pair_index,
                )
            )
    return fallback


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


def passed_sample_keys(records: list[dict[str, Any]]) -> set[tuple[int, str, int, str]]:
    passed: set[tuple[int, str, int, str]] = set()
    for row in records:
        if row.get("status") != "PASS":
            continue
        ps = row.get("host_page_size")
        bucket = row.get("bucket")
        pair_index = row.get("pair_index")
        engine = row.get("engine")
        if (
            isinstance(ps, int)
            and isinstance(bucket, str)
            and isinstance(pair_index, int)
            and pair_index >= 1
            and isinstance(engine, str)
        ):
            passed.add((ps, bucket, pair_index, engine))
    return passed


def record_missing_device_bucket(
    ledger_path: Path,
    page_size: int,
    reason: str = "no-physical-16k-device-connected",
    run_id: str | None = None,
    timestamp_utc: str | None = None,
    buckets: tuple[str, ...] = BUCKETS,
) -> list[dict[str, Any]]:
    if page_size not in PAGE_SIZE_ALIASES:
        raise ValueError(f"Unsupported page_size: {page_size}")
    existing = load_ledger(ledger_path)
    assigned_run_id = run_id or next_run_id(existing)
    ts = timestamp_utc or utc_now_iso()
    alias = PAGE_SIZE_ALIASES[page_size]
    safe_reason = sanitize_text(reason, max_len=120) or DEFAULT_MISSING_REASONS[page_size]

    has_device_samples = any(
        row.get("host_page_size") == page_size and row.get("status") != "MISSING"
        for row in existing
    )
    if has_device_samples:
        return []

    to_append: list[dict[str, Any]] = []
    for bucket in buckets:
        to_append.append(
            {
                "run_id": assigned_run_id,
                "sample_id": f"p{page_size}-{bucket}-MISSING",
                "timestamp_utc": ts,
                "host_page_size": page_size,
                "device_alias": alias,
                "bucket": bucket,
                "pair_index": 0,
                "engine": None,
                "order": None,
                "status": "MISSING",
                "reason": safe_reason,
                "failure_reason": safe_reason,
                "timeout_explained": False,
                "t0_to_shell_ready_ms": None,
                "t0_to_bridge_ready_ms": None,
                "t0_to_assets_ms": None,
                "t0_to_engine_started_ms": None,
                "t0_to_session_registered_ms": None,
                "reattach_ms": None,
                "roundtrip_ms": None,
                "credentials_only_reason": None,
                "stop_result": None,
                "residual_process_count": 0,
                "temp_c_before": None,
                "temp_c_after": None,
                "host_duration_ms": 0,
                "detail": safe_reason,
            }
        )
    append_ledger_records(ledger_path, to_append)
    return to_append


def nearest_rank_percentile(values: list[int | float], q: float) -> int | float | None:
    if not values:
        return None
    if q <= 0.0 or q > 1.0:
        raise ValueError(f"Percentile q must be in (0, 1], got {q}")
    sorted_vals = sorted(values)
    n = len(sorted_vals)
    idx = min(n - 1, max(0, math.ceil(q * n) - 1))
    return sorted_vals[idx]


def compute_p95_speedup_ratio(
    uml_p95_ms: int | float | None,
    qemu_p95_ms: int | float | None,
) -> float | None:
    if uml_p95_ms is None or qemu_p95_ms is None or qemu_p95_ms <= 0:
        return None
    return (qemu_p95_ms - uml_p95_ms) / qemu_p95_ms


def _round_ratio(val: float | None) -> float | None:
    if val is None:
        return None
    return round(val, 8)


def _extract_primary_latency_ms(row: dict[str, Any], bucket: str) -> int | float | None:
    val = row.get("t0_to_shell_ready_ms")
    if isinstance(val, (int, float)) and not isinstance(val, bool):
        if bucket == "warm_reconnect":
            return val if val >= 0 else None
        return val if val > 0 else None
    if bucket == "warm_reconnect":
        for fallback_key in ("roundtrip_ms", "reattach_ms", "echo_ms", "latency_ms", "duration_ms"):
            fb = row.get(fallback_key)
            if isinstance(fb, (int, float)) and not isinstance(fb, bool) and fb >= 0:
                return fb
    return None


def compute_bucket_stats(
    records: list[dict[str, Any]],
    bucket: str,
    host_page_size: int | None = None,
) -> dict[str, Any]:
    if bucket not in BUCKETS:
        raise ValueError(f"Unknown bucket {bucket!r}; expected one of {BUCKETS}")

    scoped = [
        row
        for row in records
        if row.get("bucket") == bucket
        and (host_page_size is None or row.get("host_page_size") == host_page_size)
    ]

    missing_rows = [row for row in scoped if row.get("status") == "MISSING"]
    missing_reasons = sorted(
        {
            str(row.get("reason") or row.get("failure_reason") or "missing")
            for row in missing_rows
        }
    )

    engines_meta: dict[str, dict[str, Any]] = {}
    valid_by_engine_pair: dict[str, dict[int, int | float]] = {eng: {} for eng in ENGINES}
    bridge_ready_by_pair: dict[str, dict[int, int | float]] = {eng: {} for eng in ENGINES}
    combined_failure_reasons: dict[str, int] = {}

    for engine in ENGINES:
        eng_rows = [
            row
            for row in scoped
            if row.get("engine") == engine and row.get("status") != "MISSING"
        ]
        pass_count = 0
        fail_count = 0
        timeout_count = 0
        unexplained_timeout_count = 0
        eng_failure_reasons: dict[str, int] = {}
        latest_valid_by_key: dict[Any, int | float] = {}

        for idx, row in enumerate(eng_rows):
            status = row.get("status")
            pair_index = row.get("pair_index")
            dedup_key = pair_index if isinstance(pair_index, int) and pair_index >= 1 else f"_row_{idx}"

            if status == "PASS":
                latency = _extract_primary_latency_ms(row, bucket)
                if latency is None:
                    fail_count += 1
                    reason = "missing_metric_t0_to_shell_ready_ms"
                    eng_failure_reasons[reason] = eng_failure_reasons.get(reason, 0) + 1
                    combined_failure_reasons[reason] = combined_failure_reasons.get(reason, 0) + 1
                else:
                    pass_count += 1
                    latest_valid_by_key[dedup_key] = latency
                    if isinstance(pair_index, int) and pair_index >= 1:
                        valid_by_engine_pair[engine][pair_index] = latency
                        bridge_val = row.get("t0_to_bridge_ready_ms")
                        if (
                            isinstance(bridge_val, (int, float))
                            and not isinstance(bridge_val, bool)
                            and bridge_val >= 0
                        ):
                            bridge_ready_by_pair[engine][pair_index] = bridge_val
            elif status == "TIMEOUT":
                timeout_count += 1
                if not bool(row.get("timeout_explained", False)):
                    unexplained_timeout_count += 1
                reason = str(row.get("failure_reason") or "instrument_timeout")
                eng_failure_reasons[reason] = eng_failure_reasons.get(reason, 0) + 1
                combined_failure_reasons[reason] = combined_failure_reasons.get(reason, 0) + 1
            elif status == "FAIL":
                fail_count += 1
                reason = str(row.get("failure_reason") or row.get("error") or "unknown_failure")
                eng_failure_reasons[reason] = eng_failure_reasons.get(reason, 0) + 1
                combined_failure_reasons[reason] = combined_failure_reasons.get(reason, 0) + 1

        total_attempts = pass_count + fail_count + timeout_count
        engines_meta[engine] = {
            "raw_valid_count": len(latest_valid_by_key),
            "pass_count": pass_count,
            "fail_count": fail_count,
            "timeout_count": timeout_count,
            "unexplained_timeout_count": unexplained_timeout_count,
            "total_attempts": total_attempts,
            "timeout_rate": _round_ratio((timeout_count / total_attempts) if total_attempts > 0 else 0.0),
            "failure_reasons": dict(sorted(eng_failure_reasons.items())),
        }

    common_pairs = sorted(set(valid_by_engine_pair["uml"]) & set(valid_by_engine_pair["qemu"]))
    engines_stats: dict[str, dict[str, Any]] = {}
    for engine in ENGINES:
        vals = sorted(valid_by_engine_pair[engine][p] for p in common_pairs)
        n = len(vals)
        bridge_vals = sorted(
            bridge_ready_by_pair[engine][p]
            for p in common_pairs
            if p in bridge_ready_by_pair[engine]
        )
        meta = engines_meta[engine]
        engines_stats[engine] = {
            "sample_count": n,
            "unpaired_valid_count": meta["raw_valid_count"],
            "pass_count": meta["pass_count"],
            "fail_count": meta["fail_count"],
            "timeout_count": meta["timeout_count"],
            "unexplained_timeout_count": meta["unexplained_timeout_count"],
            "total_attempts": meta["total_attempts"],
            "timeout_rate": meta["timeout_rate"],
            "failure_reasons": meta["failure_reasons"],
            "min_ms": vals[0] if n > 0 else None,
            "max_ms": vals[-1] if n > 0 else None,
            "mean_ms": round(sum(vals) / n, 2) if n > 0 else None,
            "p50_ms": nearest_rank_percentile(vals, 0.50),
            "p95_ms": nearest_rank_percentile(vals, 0.95),
            "bridge_ready_p50_ms": nearest_rank_percentile(bridge_vals, 0.50),
            "bridge_ready_p95_ms": nearest_rank_percentile(bridge_vals, 0.95),
        }

    # Include any bucket-scoped non-engine FAIL/TIMEOUT rows (defensive)
    extra_fail = 0
    extra_timeout = 0
    extra_unexplained_timeout = 0
    for row in scoped:
        if row.get("engine") in ENGINES or row.get("status") == "MISSING":
            continue
        status = row.get("status")
        if status == "TIMEOUT":
            extra_timeout += 1
            if not bool(row.get("timeout_explained", False)):
                extra_unexplained_timeout += 1
            reason = str(row.get("failure_reason") or "instrument_timeout")
            combined_failure_reasons[reason] = combined_failure_reasons.get(reason, 0) + 1
        elif status == "FAIL":
            extra_fail += 1
            reason = str(row.get("failure_reason") or row.get("error") or "unknown_failure")
            combined_failure_reasons[reason] = combined_failure_reasons.get(reason, 0) + 1

    common_pairs = sorted(set(valid_by_engine_pair["uml"]) & set(valid_by_engine_pair["qemu"]))
    paired_valid_count = len(common_pairs)
    paired_uml_faster_count = sum(
        1 for p in common_pairs if valid_by_engine_pair["uml"][p] < valid_by_engine_pair["qemu"][p]
    )
    paired_uml_faster_rate = (
        paired_uml_faster_count / paired_valid_count if paired_valid_count > 0 else None
    )

    uml_p95 = engines_stats["uml"]["p95_ms"]
    qemu_p95 = engines_stats["qemu"]["p95_ms"]
    speedup_ratio = compute_p95_speedup_ratio(uml_p95, qemu_p95)

    total_pass = sum(engines_stats[e]["pass_count"] for e in ENGINES)
    total_fail = sum(engines_stats[e]["fail_count"] for e in ENGINES) + extra_fail
    total_timeout = sum(engines_stats[e]["timeout_count"] for e in ENGINES) + extra_timeout
    total_unexplained_timeout = (
        sum(engines_stats[e]["unexplained_timeout_count"] for e in ENGINES)
        + extra_unexplained_timeout
    )
    total_attempts = total_pass + total_fail + total_timeout

    temps: list[float] = []
    residual_events = 0
    for row in scoped:
        if (row.get("residual_process_count") or 0) > 0:
            residual_events += 1
        for tk in ("temp_c_before", "temp_c_after"):
            tv = row.get(tk)
            if isinstance(tv, (int, float)) and not isinstance(tv, bool):
                temps.append(float(tv))

    return {
        "bucket": bucket,
        "host_page_size": host_page_size,
        "percentile_formula": PERCENTILE_FORMULA,
        "engines": engines_stats,
        "pass_count": total_pass,
        "fail_count": total_fail,
        "timeout_count": total_timeout,
        "unexplained_timeout_count": total_unexplained_timeout,
        "total_attempts": total_attempts,
        "timeout_rate": _round_ratio((total_timeout / total_attempts) if total_attempts > 0 else 0.0),
        "failure_reasons": dict(sorted(combined_failure_reasons.items())),
        "paired_valid_count": paired_valid_count,
        "paired_uml_faster_count": paired_uml_faster_count,
        "paired_uml_faster_rate": _round_ratio(paired_uml_faster_rate),
        "p95_speedup_ratio": _round_ratio(speedup_ratio),
        "missing_count": len(missing_rows),
        "missing_reasons": missing_reasons,
        "residual_process_events": residual_events,
        "thermal": {
            "sample_count": len(temps),
            "min_c": min(temps) if temps else None,
            "max_c": max(temps) if temps else None,
        },
    }


def _evaluate_gate_bucket(
    stats: dict[str, Any],
    *,
    min_pairs: int,
    p95_limit_ms: int,
    min_speedup_ratio: float,
) -> dict[str, Any]:
    reasons: list[str] = []
    has_unexplained_timeout = stats["unexplained_timeout_count"] > 0
    has_insufficient_pairs = stats["paired_valid_count"] < min_pairs
    uml_p95 = stats["engines"]["uml"]["p95_ms"]
    qemu_p95 = stats["engines"]["qemu"]["p95_ms"]
    # Compare against exact unrounded ratio so values like 0.199999999 never round up to 0.20.
    exact_speedup = compute_p95_speedup_ratio(uml_p95, qemu_p95)
    speedup_rounded = _round_ratio(exact_speedup)

    if has_unexplained_timeout:
        reasons.append(
            f"{stats['bucket']}:unexplained_timeouts={stats['unexplained_timeout_count']}"
        )
    if has_insufficient_pairs:
        reasons.append(
            f"{stats['bucket']}:paired_valid_count={stats['paired_valid_count']}<{min_pairs}"
        )

    threshold_failed = False
    if uml_p95 is not None and uml_p95 > p95_limit_ms:
        threshold_failed = True
        reasons.append(f"{stats['bucket']}:uml_p95_ms={uml_p95}>{p95_limit_ms}")
    if exact_speedup is not None and exact_speedup < min_speedup_ratio:
        threshold_failed = True
        reasons.append(
            f"{stats['bucket']}:p95_speedup_ratio={exact_speedup:.4f}<{min_speedup_ratio:.2f}"
        )
    if not has_insufficient_pairs and (uml_p95 is None or exact_speedup is None):
        threshold_failed = True
        reasons.append(f"{stats['bucket']}:missing_p95_or_speedup")

    if has_unexplained_timeout:
        verdict = "FAIL_UNEXPLAINED_TIMEOUT"
    elif threshold_failed and not has_insufficient_pairs:
        verdict = "FAIL_THRESHOLD"
    elif has_insufficient_pairs:
        verdict = "INCOMPLETE"
    else:
        verdict = "PASS"

    return {
        "bucket": stats["bucket"],
        "verdict": verdict,
        "passed": verdict == "PASS",
        "min_pairs_required": min_pairs,
        "p95_limit_ms": p95_limit_ms,
        "min_speedup_ratio": min_speedup_ratio,
        "paired_valid_count": stats["paired_valid_count"],
        "uml_p95_ms": uml_p95,
        "qemu_p95_ms": qemu_p95,
        "p95_speedup_ratio": speedup_rounded,
        "unexplained_timeout_count": stats["unexplained_timeout_count"],
        "reasons": reasons,
    }


def evaluate_w305_gate(
    records: list[dict[str, Any]],
    *,
    min_pairs: int = MIN_PAIRS,
    cold_p95_limit_ms: int = COLD_P95_LIMIT_MS,
    warm_p95_limit_ms: int = WARM_P95_LIMIT_MS,
    min_speedup_ratio: float = MIN_SPEEDUP_RATIO,
    apk_verification: dict[str, Any] | None = None,
    compat_blocked_page_sizes: set[int] | None = None,
    is_emulator: bool = False,
    missing_reasons_by_page_size: dict[int, str] | None = None,
) -> dict[str, Any]:
    compat_set = compat_blocked_page_sizes or set()
    missing_reason_overrides = missing_reasons_by_page_size or {}

    by_page_size: dict[str, dict[str, Any]] = {}
    all_temps: list[float] = []
    historical_failures = 0
    historical_timeouts = 0
    historical_missing = 0
    residual_events = 0

    for row in records:
        st = row.get("status")
        if st == "FAIL":
            historical_failures += 1
        elif st == "TIMEOUT":
            historical_timeouts += 1
        elif st == "MISSING":
            historical_missing += 1
        if (row.get("residual_process_count") or 0) > 0:
            residual_events += 1
        for tk in ("temp_c_before", "temp_c_after"):
            tv = row.get(tk)
            if isinstance(tv, (int, float)) and not isinstance(tv, bool):
                all_temps.append(float(tv))

    page_verdicts: dict[int, str] = {}
    all_gate_reasons: list[str] = []

    for ps in (4096, 16384):
        ps_rows = [row for row in records if row.get("host_page_size") == ps]
        device_rows = [row for row in ps_rows if row.get("status") != "MISSING"]
        missing_rows = [row for row in ps_rows if row.get("status") == "MISSING"]
        compat_blocked = ps in compat_set

        bucket_stats = {
            bucket: compute_bucket_stats(records, bucket, host_page_size=ps)
            for bucket in BUCKETS
        }

        if not device_rows and not compat_blocked:
            recorded_reason = None
            for mr in missing_rows:
                cand = mr.get("reason") or mr.get("failure_reason")
                if cand:
                    recorded_reason = str(cand)
                    break
            reason = (
                missing_reason_overrides.get(ps)
                or recorded_reason
                or DEFAULT_MISSING_REASONS[ps]
            )
            page_verdicts[ps] = "MISSING"
            by_page_size[str(ps)] = {
                "host_page_size": ps,
                "device_alias": PAGE_SIZE_ALIASES[ps],
                "status": "MISSING",
                "verdict": "MISSING",
                "reason": reason,
                "compat_16kb_blocked": False,
                "gates": {},
                "buckets": bucket_stats,
                "gate_reasons": [f"p{ps}:MISSING:{reason}"],
            }
            continue

        cold_gate = _evaluate_gate_bucket(
            bucket_stats["cold_provisioned"],
            min_pairs=min_pairs,
            p95_limit_ms=cold_p95_limit_ms,
            min_speedup_ratio=min_speedup_ratio,
        )
        warm_gate = _evaluate_gate_bucket(
            bucket_stats["warm_restart"],
            min_pairs=min_pairs,
            p95_limit_ms=warm_p95_limit_ms,
            min_speedup_ratio=min_speedup_ratio,
        )

        ps_reasons: list[str] = []
        if compat_blocked:
            ps_reasons.append(f"p{ps}:compat_16kb_mode_blocked")
        ps_reasons.extend(f"p{ps}:{r}" for r in cold_gate["reasons"])
        ps_reasons.extend(f"p{ps}:{r}" for r in warm_gate["reasons"])

        sub_verdicts = {cold_gate["verdict"], warm_gate["verdict"]}
        if "FAIL_UNEXPLAINED_TIMEOUT" in sub_verdicts:
            ps_verdict = "FAIL_UNEXPLAINED_TIMEOUT"
        elif compat_blocked or "FAIL_THRESHOLD" in sub_verdicts:
            ps_verdict = "FAIL_THRESHOLD"
        elif "INCOMPLETE" in sub_verdicts:
            ps_verdict = "INCOMPLETE"
        else:
            ps_verdict = "PASS"

        page_verdicts[ps] = ps_verdict
        all_gate_reasons.extend(ps_reasons)

        by_page_size[str(ps)] = {
            "host_page_size": ps,
            "device_alias": PAGE_SIZE_ALIASES[ps],
            "status": "CONNECTED",
            "verdict": ps_verdict,
            "reason": None,
            "compat_16kb_blocked": compat_blocked,
            "gates": {
                "cold_provisioned": cold_gate,
                "warm_restart": warm_gate,
            },
            "buckets": bucket_stats,
            "gate_reasons": ps_reasons,
        }

    apk_failed = apk_verification is not None and not bool(apk_verification.get("passed"))
    if apk_failed:
        all_gate_reasons.append("apk_16kb_verification_failed")

    v4k = page_verdicts[4096]
    v16k = page_verdicts[16384]
    connected_verdicts = [v for v in (v4k, v16k) if v != "MISSING"]

    if "FAIL_UNEXPLAINED_TIMEOUT" in connected_verdicts:
        overall_verdict = "FAIL_UNEXPLAINED_TIMEOUT"
    elif apk_failed or "FAIL_THRESHOLD" in connected_verdicts:
        overall_verdict = "FAIL_THRESHOLD"
    elif "INCOMPLETE" in connected_verdicts:
        overall_verdict = "INCOMPLETE"
    elif v4k == "PASS" and v16k == "PASS":
        overall_verdict = "EMULATOR_PREFLIGHT_ONLY" if is_emulator else "PASS"
    elif v4k == "PASS" and v16k == "MISSING":
        overall_verdict = (
            "EMULATOR_PREFLIGHT_ONLY" if is_emulator else "PASS_4K_ONLY_PENDING_16K"
        )
    else:
        overall_verdict = "INCOMPLETE"

    signoff_allowed = (overall_verdict == "PASS") and (not is_emulator) and (not apk_failed)

    return {
        "schema_version": 1,
        "generated_at_utc": utc_now_iso(),
        "percentile_formula": PERCENTILE_FORMULA,
        "speedup_formula": "(qemu_p95_ms - uml_p95_ms) / qemu_p95_ms",
        "gate_thresholds": {
            "min_pairs": min_pairs,
            "cold_p95_limit_ms": cold_p95_limit_ms,
            "warm_p95_limit_ms": warm_p95_limit_ms,
            "min_speedup_ratio": min_speedup_ratio,
            "max_unexplained_timeouts": 0,
        },
        "verdict": overall_verdict,
        "signoff_allowed": signoff_allowed,
        "is_emulator": is_emulator,
        "apk_16kb_passed": bool(apk_verification.get("passed")) if apk_verification is not None else None,
        "gate_reasons": all_gate_reasons,
        "total_ledger_rows": len(records),
        "historical_failures_preserved": historical_failures,
        "historical_timeouts_preserved": historical_timeouts,
        "historical_missing_preserved": historical_missing,
        "residual_process_events": residual_events,
        "thermal_summary": {
            "sample_count": len(all_temps),
            "min_c": min(all_temps) if all_temps else None,
            "max_c": max(all_temps) if all_temps else None,
        },
        "by_page_size": by_page_size,
    }


def exit_code_for_summary(summary: dict[str, Any], allow_pending_16k: bool = False) -> int:
    verdict = summary.get("verdict")
    if verdict == "PASS":
        return 0
    if verdict == "PASS_4K_ONLY_PENDING_16K":
        return 0 if allow_pending_16k else 3
    return 1


def _fmt_ms(val: int | float | None) -> str:
    if val is None:
        return "N/A"
    if isinstance(val, float) and not val.is_integer():
        return f"{val:.2f}"
    return str(int(val))


def _fmt_pct(ratio: float | None) -> str:
    if ratio is None:
        return "N/A"
    return f"{ratio * 100.0:.2f}%"


def render_summary_markdown(
    summary: dict[str, Any],
    config: dict[str, Any] | None = None,
) -> str:
    lines: list[str] = []
    lines.append("# W3-05 (R-05) Paired Performance Benchmark Summary")
    lines.append("")
    lines.append(f"- **Verdict**: `{summary['verdict']}`")
    lines.append(f"- **Signoff Allowed**: `{summary['signoff_allowed']}`")
    lines.append(f"- **Generated (UTC)**: `{summary['generated_at_utc']}`")
    lines.append(
        f"- **Ledger Integrity**: `{summary['total_ledger_rows']}` rows "
        f"(failures preserved: `{summary['historical_failures_preserved']}`, "
        f"timeouts preserved: `{summary['historical_timeouts_preserved']}`, "
        f"residual process events: `{summary['residual_process_events']}`)"
    )

    p16 = summary["by_page_size"].get("16384", {})
    if p16.get("status") == "MISSING":
        lines.append(
            f"- **OPEN**: Physical 16 KB page-size device (`device-16k`) is `MISSING` "
            f"(`{p16.get('reason')}`); final W3-05 signoff remains blocked pending 16 KB device evidence."
        )
    if summary.get("gate_reasons"):
        lines.append("- **Gate Findings**:")
        for reason in summary["gate_reasons"]:
            lines.append(f"  - `{reason}`")

    lines.append("")
    lines.append("## Methodology & Gate Formulas")
    lines.append("")
    lines.append(f"- **Percentile Formula**: `{summary['percentile_formula']}` (`sorted_vals[max(0, ceil(q * n) - 1)]`)")
    lines.append(f"- **Speedup Formula**: `{summary['speedup_formula']}`")
    th = summary["gate_thresholds"]
    lines.append(
        f"- **Gate Thresholds**: `min_pairs >= {th['min_pairs']}`, "
        f"`cold_provisioned UML p95 <= {th['cold_p95_limit_ms']} ms`, "
        f"`warm_restart UML p95 <= {th['warm_p95_limit_ms']} ms`, "
        f"`p95_speedup_ratio >= {th['min_speedup_ratio'] * 100:.0f}%`, "
        f"`unexplained_timeouts == {th['max_unexplained_timeouts']}`"
    )
    lines.append(
        "- **Interleaving**: Odd pairs execute `uml -> qemu`; even pairs execute `qemu -> uml`. "
        "`cold_provisioned` runs `am force-stop` before every engine launch. "
        "`first_provision` and `warm_reconnect` are reported separately and never mixed into cold/warm gate counts."
    )

    if config:
        lines.append("")
        lines.append("## Frozen Benchmark Configuration")
        lines.append("")
        lines.append(f"- **Distro**: `{config.get('distro')}` `{config.get('distro_version')}`")
        lines.append(f"- **Rootfs Tarball**: `{config.get('rootfs_tar')}`")
        lines.append(f"- **Rootfs SHA-256**: `{config.get('rootfs_sha256')}`")
        lines.append(
            f"- **Guest Hardware**: `{config.get('vcpu')} vCPU`, `{config.get('ram_mb')} MB RAM`, "
            f"`network_mode={config.get('network_mode')}`"
        )
        lines.append(
            f"- **Connected Device Profile**: alias=`{config.get('device_alias')}`, "
            f"page_size=`{config.get('host_page_size')}`, "
            f"Android `{config.get('android_release')}` (API `{config.get('sdk_int')}`), "
            f"ABI=`{config.get('abi')}`, kernel=`{config.get('kernel_release')}`"
        )
        lines.append(
            f"- **Planned Pairs**: `first_provision={config.get('first_provision_pairs_planned')}`, "
            f"`cold_provisioned={config.get('cold_pairs_planned')}`, "
            f"`warm_restart={config.get('warm_pairs_planned')}`, "
            f"`warm_reconnect={config.get('warm_reconnect_pairs_planned')}`, "
            f"`timeout_sec={config.get('timeout_sec')}`"
        )
        manifests = config.get("apk_payload_manifests") or {}
        if manifests:
            lines.append(f"- **APK Payload Manifests Captured**: `{', '.join(sorted(manifests.keys()))}`")

    thermal = summary.get("thermal_summary") or {}
    lines.append("")
    lines.append("## Thermal Summary")
    lines.append("")
    if thermal.get("sample_count", 0) > 0:
        lines.append(
            f"- Battery temperature across `{thermal['sample_count']}` readings: "
            f"min `{thermal['min_c']} °C`, max `{thermal['max_c']} °C`"
        )
    else:
        lines.append("- No battery temperature readings recorded.")

    for ps_key in ("4096", "16384"):
        ps_data = summary["by_page_size"][ps_key]
        lines.append("")
        lines.append(f"## Host Page Size `{ps_key}` (`{ps_data['device_alias']}`)")
        lines.append("")
        if ps_data["status"] == "MISSING":
            lines.append(f"- **Status**: `MISSING` (`{ps_data['reason']}`)")
            continue

        lines.append(f"- **Page-Size Verdict**: `{ps_data['verdict']}`")
        lines.append("")
        lines.append("### Gate Buckets (`cold_provisioned`, `warm_restart`)")
        lines.append("")
        lines.append(
            "| Bucket | Valid Pairs | UML Faster | UML p50 (ms) | UML p95 (ms) | "
            "QEMU p50 (ms) | QEMU p95 (ms) | p95 Speedup | Timeouts | Gate |"
        )
        lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
        for bucket in GATE_BUCKETS:
            b = ps_data["buckets"][bucket]
            g = ps_data["gates"][bucket]
            uml = b["engines"]["uml"]
            qemu = b["engines"]["qemu"]
            lines.append(
                f"| `{bucket}` | {b['paired_valid_count']} | {b['paired_uml_faster_count']} | "
                f"{_fmt_ms(uml['p50_ms'])} | {_fmt_ms(uml['p95_ms'])} | "
                f"{_fmt_ms(qemu['p50_ms'])} | {_fmt_ms(qemu['p95_ms'])} | "
                f"{_fmt_pct(b['p95_speedup_ratio'])} | {b['timeout_count']} | `{g['verdict']}` |"
            )

        lines.append("")
        lines.append("### Separate Readiness Buckets (`first_provision`, `warm_reconnect`)")
        lines.append("")
        lines.append(
            "| Bucket | Valid Pairs | UML p50 (ms) | UML p95 (ms) | "
            "QEMU p50 (ms) | QEMU p95 (ms) | p95 Speedup | Fails | Timeouts |"
        )
        lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        for bucket in ("first_provision", "warm_reconnect"):
            b = ps_data["buckets"][bucket]
            uml = b["engines"]["uml"]
            qemu = b["engines"]["qemu"]
            lines.append(
                f"| `{bucket}` | {b['paired_valid_count']} | "
                f"{_fmt_ms(uml['p50_ms'])} | {_fmt_ms(uml['p95_ms'])} | "
                f"{_fmt_ms(qemu['p50_ms'])} | {_fmt_ms(qemu['p95_ms'])} | "
                f"{_fmt_pct(b['p95_speedup_ratio'])} | {b['fail_count']} | {b['timeout_count']} |"
            )

    lines.append("")
    return "\n".join(lines)


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
            raise RuntimeError(
                f"Expected exactly 1 connected Android device, found {len(serials)}."
            )
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
    adb: Any,
    package_name: str,
    assets_dir: Path | None = None,
    apk_path: Path | None = None,
    device_alias: str | None = None,
) -> dict[str, Any]:
    page_raw = adb.run("shell", "getconf", "PAGE_SIZE").stdout
    host_page_size = validate_host_page_size(page_raw)
    effective_alias = (
        validate_device_alias(device_alias)
        if device_alias is not None
        else PAGE_SIZE_ALIASES[host_page_size]
    )
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
        "device_alias": effective_alias,
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
        "apk_name": apk_path.name if apk_path else None,
        "apk_payload_manifests": extract_apk_payload_manifests(apk_path, assets_dir=assets_dir),
    }


def build_benchmark_config(
    device_meta: dict[str, Any],
    *,
    cold_pairs_planned: int = DEFAULT_COLD_PAIRS,
    warm_pairs_planned: int = DEFAULT_WARM_PAIRS,
    first_provision_pairs_planned: int = DEFAULT_FIRST_PROVISION_PAIRS,
    warm_reconnect_pairs_planned: int = DEFAULT_WARM_RECONNECT_PAIRS,
    warm_batch_size: int = DEFAULT_WARM_BATCH_SIZE,
    timeout_sec: int = DEFAULT_TIMEOUT_SEC,
    max_temp_c: float | None = DEFAULT_MAX_TEMP_C,
) -> dict[str, Any]:
    alias = validate_device_alias(str(device_meta["device_alias"]))
    return {
        "schema_version": 1,
        "generated_at_utc": utc_now_iso(),
        "distro": DISTRO,
        "distro_version": DISTRO_VERSION,
        "rootfs_tar": ROOTFS_TAR,
        "rootfs_sha256": ROOTFS_SHA256,
        "vcpu": VCPU,
        "ram_mb": RAM_MB,
        "network_mode": NETWORK_MODE,
        "apk_name": device_meta.get("apk_name"),
        "apk_payload_manifests": device_meta.get("apk_payload_manifests") or {},
        "host_page_size": int(device_meta["host_page_size"]),
        "sdk_int": int(device_meta["sdk_int"]),
        "android_release": str(device_meta["android_release"]),
        "abi": str(device_meta["abi"]),
        "kernel_release": str(device_meta["kernel_release"]),
        "device_alias": alias,
        "device_kind": device_meta.get("device_kind", "physical"),
        "is_emulator": bool(device_meta.get("is_emulator", False)),
        "package_name": device_meta.get("package_name", DEFAULT_PACKAGE),
        "package_version_name": device_meta.get("package_version_name"),
        "package_version_code": device_meta.get("package_version_code"),
        "compat_16kb_indicators": list(device_meta.get("compat_16kb_indicators") or []),
        "cold_pairs_planned": cold_pairs_planned,
        "warm_pairs_planned": warm_pairs_planned,
        "first_provision_pairs_planned": first_provision_pairs_planned,
        "warm_reconnect_pairs_planned": warm_reconnect_pairs_planned,
        "warm_batch_size": warm_batch_size,
        "timeout_sec": timeout_sec,
        "max_temp_c": max_temp_c,
        "percentile_formula": PERCENTILE_FORMULA,
        "gate_thresholds": {
            "min_pairs": MIN_PAIRS,
            "cold_p95_limit_ms": COLD_P95_LIMIT_MS,
            "warm_p95_limit_ms": WARM_P95_LIMIT_MS,
            "min_speedup_ratio": MIN_SPEEDUP_RATIO,
            "max_unexplained_timeouts": 0,
        },
    }


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
) -> dict[str, Any]:
    serial = adb.discover_device()
    cmd = [*adb.base_cmd(serial), "shell", "am", "instrument", "-r", "-w"]
    for key, val in extras.items():
        cmd += ["-e", key, val]
    cmd.append(f"{package_name}{INSTRUMENTATION_COMPONENT_SUFFIX}")

    raw_log_path.parent.mkdir(parents=True, exist_ok=True)
    captured_lines: list[str] = []
    timed_out = False
    error_text: str | None = None
    t0 = time.monotonic()

    try:
        _stream_command_lines(cmd, timeout_s=float(timeout_s), on_line=captured_lines.append)
    except TimeoutError as exc:
        timed_out = True
        error_text = sanitize_text(str(exc), max_len=160)
    except Exception as exc:  # noqa: BLE001
        error_text = sanitize_text(f"{exc.__class__.__name__}: {exc}", max_len=160)
    finally:
        raw_log_path.write_text("".join(captured_lines), encoding="utf-8", errors="replace")

    duration_ms = int((time.monotonic() - t0) * 1000)
    parsed = parse_instrumentation_output("".join(captured_lines))
    return {
        "parsed": parsed,
        "timed_out": timed_out,
        "error": error_text,
        "duration_ms": duration_ms,
    }


def _check_residual_processes(adb: Any) -> list[dict[str, Any]]:
    ps_out = adb.run("shell", "ps", "-A", check=False).stdout
    return find_residual_processes(ps_out)


def _build_ledger_row(
    *,
    run_id: str,
    host_page_size: int,
    device_alias: str,
    bucket: str,
    pair_index: int,
    engine: str,
    order: str | None,
    status: str,
    failure_reason: str | None,
    sample: dict[str, Any] | None,
    residual_process_count: int,
    temp_c_before: float | None,
    temp_c_after: float | None,
    host_duration_ms: int,
    detail: str | None = None,
) -> dict[str, Any]:
    s = sample or {}
    return {
        "run_id": run_id,
        "sample_id": make_sample_id(host_page_size, bucket, pair_index, engine),
        "timestamp_utc": utc_now_iso(),
        "host_page_size": host_page_size,
        "device_alias": device_alias,
        "bucket": bucket,
        "pair_index": pair_index,
        "engine": engine,
        "order": order or (pair_order_label(pair_index) if pair_index >= 1 else None),
        "status": status,
        "failure_reason": failure_reason,
        "timeout_explained": False,
        "t0_to_shell_ready_ms": s.get("t0_to_shell_ready_ms"),
        "t0_to_bridge_ready_ms": s.get("t0_to_bridge_ready_ms"),
        "t0_to_assets_ms": s.get("t0_to_assets_ms"),
        "t0_to_engine_started_ms": s.get("t0_to_engine_started_ms"),
        "t0_to_session_registered_ms": s.get("t0_to_session_registered_ms"),
        "reattach_ms": s.get("reattach_ms"),
        "roundtrip_ms": s.get("roundtrip_ms"),
        "credentials_only_reason": s.get("credentials_only_reason"),
        "stop_result": s.get("stop_result"),
        "residual_process_count": residual_process_count,
        "temp_c_before": temp_c_before,
        "temp_c_after": temp_c_after,
        "host_duration_ms": host_duration_ms,
        "detail": sanitize_text(detail if detail is not None else s.get("detail"), max_len=160),
    }


def _evaluate_single_sample_outcome(
    run_res: dict[str, Any],
    *,
    bucket: str,
    engine: str,
    pair_index: int,
    residuals: list[dict[str, Any]],
) -> tuple[str, str | None, dict[str, Any], str | None]:
    parsed = run_res["parsed"]
    res_dict = parsed["result"]
    norm = normalize_sample_payload(
        res_dict,
        default_bucket=bucket,
        default_engine=engine,
        default_pair_index=pair_index,
    )

    if run_res["timed_out"]:
        return "TIMEOUT", "instrument_timeout", norm, run_res.get("error") or "instrument_timeout"
    if run_res.get("error"):
        return "FAIL", "host_runner_error", norm, run_res["error"]
    if parsed.get("failed_lines"):
        return "FAIL", "instrumentation_failed", norm, parsed["failed_lines"][0]
    if parsed.get("code") != -1 or norm["status"] != "PASS":
        reason = norm.get("error") or "instrumentation_fail"
        detail = norm.get("detail") or f"code={parsed.get('code')}"
        return "FAIL", reason, norm, detail
    if _extract_primary_latency_ms(norm, bucket) is None:
        return "FAIL", "missing_metric_t0_to_shell_ready_ms", norm, "missing_primary_metric"
    if residuals:
        tokens = ",".join(r["token"] for r in residuals)
        return "FAIL", "residual_process_detected", norm, f"residuals={tokens}"
    return "PASS", None, norm, norm.get("detail") or "ok"


def _record_batch_results(
    *,
    run_res: dict[str, Any],
    expected_slots: list[tuple[int, str]],
    bucket: str,
    run_id: str,
    host_page_size: int,
    device_alias: str,
    temp_c_before: float | None,
    temp_c_after: float | None,
    residuals: list[dict[str, Any]],
    samples_override: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    parsed = run_res["parsed"]
    emitted = samples_override if samples_override is not None else list(parsed.get("samples") or [])
    rows: list[dict[str, Any]] = []
    seen_slots: set[tuple[int, str]] = set()
    residual_count = len(residuals)

    for idx, sample in enumerate(emitted):
        p_idx = sample.get("pair_index")
        eng = sample.get("engine")
        if not isinstance(p_idx, int) or p_idx < 1 or eng not in ENGINES:
            continue
        seen_slots.add((p_idx, eng))
        st = sample.get("status", "FAIL")
        failure_reason: str | None = None
        detail = sample.get("detail")
        if st == "TIMEOUT":
            failure_reason = sample.get("error") or "instrument_timeout"
        elif st != "PASS":
            st = "FAIL"
            failure_reason = sample.get("error") or "instrumentation_fail"
        elif _extract_primary_latency_ms(sample, bucket) is None:
            st = "FAIL"
            failure_reason = "missing_metric_t0_to_shell_ready_ms"
        elif residual_count > 0 and idx == len(emitted) - 1:
            st = "FAIL"
            failure_reason = "residual_process_detected"
            detail = f"residuals={','.join(r['token'] for r in residuals)}"

        rows.append(
            _build_ledger_row(
                run_id=run_id,
                host_page_size=host_page_size,
                device_alias=device_alias,
                bucket=bucket,
                pair_index=p_idx,
                engine=eng,
                order=sample.get("order") or pair_order_label(p_idx),
                status=st,
                failure_reason=failure_reason,
                sample=sample,
                residual_process_count=residual_count if idx == len(emitted) - 1 else 0,
                temp_c_before=temp_c_before,
                temp_c_after=temp_c_after,
                host_duration_ms=run_res["duration_ms"],
                detail=detail,
            )
        )

    missing_slots = [slot for slot in expected_slots if slot not in seen_slots]
    if run_res["timed_out"]:
        target_slot = missing_slots[0] if missing_slots else (expected_slots[0] if expected_slots else None)
        if target_slot is not None:
            p_idx, eng = target_slot
            rows.append(
                _build_ledger_row(
                    run_id=run_id,
                    host_page_size=host_page_size,
                    device_alias=device_alias,
                    bucket=bucket,
                    pair_index=p_idx,
                    engine=eng,
                    order=pair_order_label(p_idx),
                    status="TIMEOUT",
                    failure_reason="instrument_timeout",
                    sample=None,
                    residual_process_count=residual_count,
                    temp_c_before=temp_c_before,
                    temp_c_after=temp_c_after,
                    host_duration_ms=run_res["duration_ms"],
                    detail=run_res.get("error") or "instrument_timeout",
                )
            )
    elif (
        run_res.get("error")
        or parsed.get("failed_lines")
        or parsed.get("sample_json_errors")
        or parsed.get("code") != -1
        or (parsed.get("result") or {}).get("w305_result") != "PASS"
        or missing_slots
    ):
        if parsed.get("sample_json_errors"):
            fail_reason = parsed["sample_json_errors"][0]
        elif run_res.get("error"):
            fail_reason = "host_runner_error"
        elif parsed.get("failed_lines"):
            fail_reason = "instrumentation_failed"
        else:
            fail_reason = (
                sanitize_text((parsed.get("result") or {}).get("w305_error"), max_len=80)
                or "batch_incomplete_or_failed"
            )
        fail_detail = (
            run_res.get("error")
            or (parsed["failed_lines"][0] if parsed.get("failed_lines") else None)
            or sanitize_text((parsed.get("result") or {}).get("w305_detail"), max_len=160)
            or f"code={parsed.get('code')}"
        )
        targets = list(missing_slots) if missing_slots else []
        if not targets and not rows and expected_slots:
            targets = [expected_slots[0]]
        for p_idx, eng in targets:
            rows.append(
                _build_ledger_row(
                    run_id=run_id,
                    host_page_size=host_page_size,
                    device_alias=device_alias,
                    bucket=bucket,
                    pair_index=p_idx,
                    engine=eng,
                    order=pair_order_label(p_idx),
                    status="FAIL",
                    failure_reason=fail_reason,
                    sample=None,
                    residual_process_count=residual_count,
                    temp_c_before=temp_c_before,
                    temp_c_after=temp_c_after,
                    host_duration_ms=run_res["duration_ms"],
                    detail=fail_detail,
                )
            )

    return rows


def run_benchmark(
    *,
    adb: Any,
    out_dir: Path,
    package_name: str,
    selected_buckets: list[str],
    device_meta: dict[str, Any],
    config: dict[str, Any],
    apk_verification: dict[str, Any] | None = None,
    resume: bool = False,
    skip_prepare: bool = False,
    warm_mode: str = "batch",
    prepare_timeout_sec: int = DEFAULT_PREPARE_TIMEOUT_SEC,
    cooldown_poll_sec: float = 10.0,
    cooldown_max_wait_sec: float = 300.0,
    record_missing_page_sizes: list[int] | None = None,
    missing_reason: str = "no-physical-16k-device-connected",
    instrument_fn: Callable[[Any, str, dict[str, str], int, Path], dict[str, Any]] = _run_instrumentation,
    sleep_fn: Callable[[float], None] = time.sleep,
    monotonic_fn: Callable[[], float] = time.monotonic,
) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    raw_dir = out_dir / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    (out_dir / "benchmark-config.json").write_text(
        json.dumps(config, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )

    host_ps = int(device_meta["host_page_size"])
    device_alias = str(device_meta["device_alias"])
    is_emulator = bool(device_meta.get("is_emulator", False))
    compat_blocked = host_ps == 16384 and len(device_meta.get("compat_16kb_indicators") or []) > 0
    compat_set = {host_ps} if compat_blocked else set()

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
                    "device_alias": device_alias,
                    "bucket": "cold_provisioned",
                    "pair_index": 0,
                    "engine": None,
                    "order": None,
                    "status": "FAIL",
                    "failure_reason": "compat_16kb_mode_blocked",
                    "timeout_explained": False,
                    "residual_process_count": 0,
                    "temp_c_before": None,
                    "temp_c_after": None,
                    "host_duration_ms": 0,
                    "detail": ",".join(device_meta.get("compat_16kb_indicators") or []),
                }
            ],
        )
        summary = evaluate_w305_gate(
            load_ledger(ledger_path),
            apk_verification=apk_verification,
            compat_blocked_page_sizes=compat_set,
            is_emulator=is_emulator,
        )
        (out_dir / "summary.json").write_text(
            json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        (out_dir / "summary.md").write_text(
            render_summary_markdown(summary, config), encoding="utf-8"
        )
        return summary

    adb.run("shell", "am", "force-stop", package_name, check=False)

    if not skip_prepare:
        prep = instrument_fn(
            adb,
            package_name,
            {"action": "prepare"},
            prepare_timeout_sec,
            raw_dir / f"{run_id}-prepare.log",
        )
        prep_parsed = prep["parsed"]
        prep_res = prep_parsed["result"]
        if prep["timed_out"] or prep.get("error"):
            raise RuntimeError(f"W305 prepare failed: {prep.get('error') or 'timeout'}")
        if prep_parsed["code"] != -1 or prep_res.get("w305_result") != "PASS":
            raise RuntimeError(f"W305 prepare failed: code={prep_parsed['code']} result={prep_res}")
        emitted_sha = prep_res.get("w305_alpine_sha256")
        if emitted_sha and emitted_sha.strip().lower() != ROOTFS_SHA256:
            raise RuntimeError(
                f"W305 prepare rootfs SHA-256 mismatch: expected {ROOTFS_SHA256}, got {emitted_sha}"
            )
        residuals = _check_residual_processes(adb)
        if residuals:
            adb.run("shell", "am", "force-stop", package_name, check=False)
            raise RuntimeError(f"Post-prepare residual processes detected: {[r['token'] for r in residuals]}")

    already_passed = passed_sample_keys(existing_rows) if resume else set()
    timeout_sec = int(config["timeout_sec"])
    max_temp_c = config.get("max_temp_c")

    # 1 & 2. first_provision and cold_provisioned (per-sample force-stop isolation)
    for per_sample_bucket, planned_key in (
        ("first_provision", "first_provision_pairs_planned"),
        ("cold_provisioned", "cold_pairs_planned"),
    ):
        if per_sample_bucket not in selected_buckets:
            continue
        n_pairs = int(config[planned_key])
        for pair_idx in range(1, n_pairs + 1):
            order_engines = pair_engine_order(pair_idx)
            pending_engines = [
                eng for eng in order_engines
                if (host_ps, per_sample_bucket, pair_idx, eng) not in already_passed
            ]
            if not pending_engines:
                continue
            temp_before, _ = wait_for_thermal_cooldown(
                adb, max_temp_c, cooldown_poll_sec, cooldown_max_wait_sec, sleep_fn, monotonic_fn
            )
            for idx_in_pair, eng in enumerate(pending_engines):
                if idx_in_pair > 0:
                    temp_before = read_battery_temperature_c(adb)
                adb.run("shell", "am", "force-stop", package_name, check=False)
                run_res = instrument_fn(
                    adb,
                    package_name,
                    {"action": per_sample_bucket, "engine": eng, "pair_index": str(pair_idx)},
                    timeout_sec,
                    raw_dir / f"{run_id}-{per_sample_bucket}-pair{pair_idx:03d}-{eng}.log",
                )
                residuals = _check_residual_processes(adb)
                if residuals or run_res["timed_out"]:
                    adb.run("shell", "am", "force-stop", package_name, check=False)
                temp_after = read_battery_temperature_c(adb)
                status, failure_reason, norm, detail = _evaluate_single_sample_outcome(
                    run_res,
                    bucket=per_sample_bucket,
                    engine=eng,
                    pair_index=pair_idx,
                    residuals=residuals,
                )
                row = _build_ledger_row(
                    run_id=run_id,
                    host_page_size=host_ps,
                    device_alias=device_alias,
                    bucket=per_sample_bucket,
                    pair_index=pair_idx,
                    engine=eng,
                    order=pair_order_label(pair_idx),
                    status=status,
                    failure_reason=failure_reason,
                    sample=norm,
                    residual_process_count=len(residuals),
                    temp_c_before=temp_before,
                    temp_c_after=temp_after,
                    host_duration_ms=run_res["duration_ms"],
                    detail=detail,
                )
                append_ledger_records(ledger_path, [row])

    # 3. warm_restart
    if "warm_restart" in selected_buckets:
        n_pairs = int(config["warm_pairs_planned"])
        pending_warm_pairs = [
            p for p in range(1, n_pairs + 1)
            if not (
                (host_ps, "warm_restart", p, "uml") in already_passed
                and (host_ps, "warm_restart", p, "qemu") in already_passed
            )
        ]
        if warm_mode == "batch":
            batch_size = int(config.get("warm_batch_size") or DEFAULT_WARM_BATCH_SIZE)
            for batch in contiguous_pair_batches(pending_warm_pairs, batch_size):
                start_pair = batch[0]
                count = len(batch)
                temp_before, _ = wait_for_thermal_cooldown(
                    adb, max_temp_c, cooldown_poll_sec, cooldown_max_wait_sec, sleep_fn, monotonic_fn
                )
                adb.run("shell", "am", "force-stop", package_name, check=False)
                batch_timeout = max(timeout_sec, timeout_sec * (count + 1))
                run_res = instrument_fn(
                    adb,
                    package_name,
                    {
                        "action": "warm_restart_batch",
                        "start_pair": str(start_pair),
                        "count": str(count),
                    },
                    batch_timeout,
                    raw_dir / f"{run_id}-warm_restart-batch-{start_pair:03d}-{batch[-1]:03d}.log",
                )
                residuals = _check_residual_processes(adb)
                if residuals or run_res["timed_out"]:
                    adb.run("shell", "am", "force-stop", package_name, check=False)
                temp_after = read_battery_temperature_c(adb)
                expected_slots = [
                    (p_idx, eng)
                    for p_idx in batch
                    for eng in pair_engine_order(p_idx)
                ]
                rows = _record_batch_results(
                    run_res=run_res,
                    expected_slots=expected_slots,
                    bucket="warm_restart",
                    run_id=run_id,
                    host_page_size=host_ps,
                    device_alias=device_alias,
                    temp_c_before=temp_before,
                    temp_c_after=temp_after,
                    residuals=residuals,
                )
                append_ledger_records(ledger_path, rows)
        else:
            for pair_idx in pending_warm_pairs:
                order_label = pair_order_label(pair_idx)
                temp_before, _ = wait_for_thermal_cooldown(
                    adb, max_temp_c, cooldown_poll_sec, cooldown_max_wait_sec, sleep_fn, monotonic_fn
                )
                adb.run("shell", "am", "force-stop", package_name, check=False)
                run_res = instrument_fn(
                    adb,
                    package_name,
                    {
                        "action": "warm_restart_pair",
                        "pair_index": str(pair_idx),
                        "order": order_label,
                        "warmup": "true",
                    },
                    max(timeout_sec, timeout_sec * 2),
                    raw_dir / f"{run_id}-warm_restart-pair{pair_idx:03d}.log",
                )
                residuals = _check_residual_processes(adb)
                if residuals or run_res["timed_out"]:
                    adb.run("shell", "am", "force-stop", package_name, check=False)
                temp_after = read_battery_temperature_c(adb)
                samples = extract_warm_pair_samples(run_res["parsed"], pair_idx, order_label)
                expected_slots = [(pair_idx, eng) for eng in pair_engine_order(pair_idx)]
                rows = _record_batch_results(
                    run_res=run_res,
                    expected_slots=expected_slots,
                    bucket="warm_restart",
                    run_id=run_id,
                    host_page_size=host_ps,
                    device_alias=device_alias,
                    temp_c_before=temp_before,
                    temp_c_after=temp_after,
                    residuals=residuals,
                    samples_override=samples,
                )
                append_ledger_records(ledger_path, rows)

    # 4. warm_reconnect (separate readiness bucket; never mixed with cold/warm_restart)
    if "warm_reconnect" in selected_buckets:
        n_pairs = int(config["warm_reconnect_pairs_planned"])
        all_reconnect_done = n_pairs > 0 and all(
            (host_ps, "warm_reconnect", p, "uml") in already_passed
            and (host_ps, "warm_reconnect", p, "qemu") in already_passed
            for p in range(1, n_pairs + 1)
        )
        if n_pairs > 0 and not all_reconnect_done:
            temp_before, _ = wait_for_thermal_cooldown(
                adb, max_temp_c, cooldown_poll_sec, cooldown_max_wait_sec, sleep_fn, monotonic_fn
            )
            adb.run("shell", "am", "force-stop", package_name, check=False)
            reconnect_timeout = max(timeout_sec, timeout_sec * 2 + n_pairs * 5)
            run_res = instrument_fn(
                adb,
                package_name,
                {"action": "warm_reconnect_pair", "count": str(n_pairs)},
                reconnect_timeout,
                raw_dir / f"{run_id}-warm_reconnect-count{n_pairs:03d}.log",
            )
            residuals = _check_residual_processes(adb)
            if residuals or run_res["timed_out"]:
                adb.run("shell", "am", "force-stop", package_name, check=False)
            temp_after = read_battery_temperature_c(adb)
            expected_slots = [
                (p_idx, eng)
                for p_idx in range(1, n_pairs + 1)
                for eng in pair_engine_order(p_idx)
            ]
            rows = _record_batch_results(
                run_res=run_res,
                expected_slots=expected_slots,
                bucket="warm_reconnect",
                run_id=run_id,
                host_page_size=host_ps,
                device_alias=device_alias,
                temp_c_before=temp_before,
                temp_c_after=temp_after,
                residuals=residuals,
            )
            append_ledger_records(ledger_path, rows)

    for missing_ps in record_missing_page_sizes or []:
        record_missing_device_bucket(
            ledger_path=ledger_path,
            page_size=missing_ps,
            reason=missing_reason,
            run_id=run_id,
        )

    summary = evaluate_w305_gate(
        load_ledger(ledger_path),
        apk_verification=apk_verification,
        compat_blocked_page_sizes=compat_set,
        is_emulator=is_emulator,
    )
    (out_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (out_dir / "summary.md").write_text(
        render_summary_markdown(summary, config), encoding="utf-8"
    )
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=None, help="Path to adb executable")
    parser.add_argument("--serial", default=None, help="Optional target device serial")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--apk", default=None, help="Path to QA app debug APK")
    parser.add_argument("--test-apk", default=None, help="Path to QA androidTest APK")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument(
        "--clean-install",
        action="store_true",
        help="Uninstall any existing io.github.nyzeep.orchestella* packages before installing APKs",
    )
    parser.add_argument(
        "--device-alias",
        default=None,
        help="Override stable non-identifying device alias recorded in metadata and ledger",
    )
    parser.add_argument("--readelf", default=None, help="Path to llvm-readelf")
    parser.add_argument("--zipalign", default=None, help="Path to zipalign")
    parser.add_argument(
        "--assets-dir",
        default=None,
        help="Fallback staged assets directory with .sha256 manifests if --apk is omitted",
    )
    parser.add_argument("--output-dir", required=True, help="External evidence output directory")
    parser.add_argument("--cold-pairs", type=int, default=DEFAULT_COLD_PAIRS)
    parser.add_argument("--warm-pairs", type=int, default=DEFAULT_WARM_PAIRS)
    parser.add_argument("--first-provision-pairs", type=int, default=DEFAULT_FIRST_PROVISION_PAIRS)
    parser.add_argument("--warm-reconnect-pairs", type=int, default=DEFAULT_WARM_RECONNECT_PAIRS)
    parser.add_argument("--warm-batch-size", type=int, default=DEFAULT_WARM_BATCH_SIZE)
    parser.add_argument(
        "--warm-mode",
        choices=("batch", "pair"),
        default="batch",
        help="Use warm_restart_batch (default) or warm_restart_pair",
    )
    parser.add_argument(
        "--buckets",
        default=",".join(BUCKETS),
        help="Comma-separated subset of buckets to execute",
    )
    parser.add_argument("--timeout-sec", type=int, default=DEFAULT_TIMEOUT_SEC)
    parser.add_argument("--prepare-timeout-sec", type=int, default=DEFAULT_PREPARE_TIMEOUT_SEC)
    parser.add_argument(
        "--max-temp-c",
        type=float,
        default=DEFAULT_MAX_TEMP_C,
        help="Battery temperature cooldown threshold in Celsius (<= 0 disables cooldown wait)",
    )
    parser.add_argument("--cooldown-poll-sec", type=float, default=10.0)
    parser.add_argument("--cooldown-max-wait-sec", type=float, default=300.0)
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Skip samples already PASS in ledger.jsonl while preserving prior failures/timeouts",
    )
    parser.add_argument("--skip-prepare", action="store_true")
    parser.add_argument(
        "--record-missing-page-size",
        type=int,
        action="append",
        default=[],
        help="Append explicit MISSING rows for a host page size not connected (e.g. 16384)",
    )
    parser.add_argument("--missing-reason", default="no-physical-16k-device-connected")
    parser.add_argument(
        "--allow-pending-16k",
        action="store_true",
        help="Return exit code 0 when verdict is PASS_4K_ONLY_PENDING_16K",
    )
    parser.add_argument(
        "--summarize-only",
        action="store_true",
        help="Re-evaluate existing ledger.jsonl in --output-dir without connecting to ADB",
    )
    args = parser.parse_args(argv)

    if args.device_alias is not None:
        validate_device_alias(args.device_alias)

    selected_buckets = [b.strip() for b in args.buckets.split(",") if b.strip()]
    for bucket in selected_buckets:
        if bucket not in BUCKETS:
            raise ValueError(f"Unknown bucket {bucket!r}; expected one of {BUCKETS}")

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.summarize_only:
        ledger_path = out_dir / "ledger.jsonl"
        for missing_ps in args.record_missing_page_size:
            record_missing_device_bucket(
                ledger_path=ledger_path,
                page_size=missing_ps,
                reason=args.missing_reason,
            )
        config_path = out_dir / "benchmark-config.json"
        config_data = (
            json.loads(config_path.read_text(encoding="utf-8"))
            if config_path.is_file()
            else None
        )
        apk_json_path = out_dir / "apk-16kb.json"
        apk_ver = (
            json.loads(apk_json_path.read_text(encoding="utf-8"))
            if apk_json_path.is_file()
            else None
        )
        summary = evaluate_w305_gate(
            load_ledger(ledger_path),
            apk_verification=apk_ver,
            is_emulator=bool(config_data.get("is_emulator", False)) if config_data else False,
        )
        (out_dir / "summary.json").write_text(
            json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        (out_dir / "summary.md").write_text(
            render_summary_markdown(summary, config_data), encoding="utf-8"
        )
        print(json.dumps(summary, indent=2))
        return exit_code_for_summary(summary, allow_pending_16k=args.allow_pending_16k)

    readelf_path = _resolve_tool(args.readelf, ("llvm-readelf.exe", "llvm-readelf"))
    zipalign_path = _resolve_tool(args.zipalign, ("zipalign.exe", "zipalign"))
    apk_verification: dict[str, Any] | None = None
    if args.apk:
        apk_verification = verify_apk_16kb(
            args.apk, readelf_path=readelf_path, zipalign_path=zipalign_path
        )
        (out_dir / "apk-16kb.json").write_text(
            json.dumps(apk_verification, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        if not apk_verification["passed"]:
            print("APK 16 KB alignment verification FAILED", file=sys.stderr)
            return 2

    adb_path = _resolve_tool(args.adb, ("adb.exe", "adb"))
    if not adb_path:
        raise RuntimeError("Could not locate adb; pass --adb or set ANDROID_HOME")
    adb = AdbClient(adb_path, args.serial)
    adb.discover_device()

    if args.clean_install and not args.skip_install:
        clean_uninstall_orchestella_packages(adb)

    if not args.skip_install:
        if args.apk:
            adb.run("install", "-r", "-t", args.apk, timeout=300)
        if args.test_apk:
            adb.run("install", "-r", "-t", args.test_apk, timeout=300)

    assets_dir = Path(args.assets_dir) if args.assets_dir else None
    apk_path = Path(args.apk) if args.apk else None
    device_meta = probe_device_metadata(
        adb,
        args.package,
        assets_dir=assets_dir,
        apk_path=apk_path,
        device_alias=args.device_alias,
    )
    config = build_benchmark_config(
        device_meta,
        cold_pairs_planned=args.cold_pairs,
        warm_pairs_planned=args.warm_pairs,
        first_provision_pairs_planned=args.first_provision_pairs,
        warm_reconnect_pairs_planned=args.warm_reconnect_pairs,
        warm_batch_size=args.warm_batch_size,
        timeout_sec=args.timeout_sec,
        max_temp_c=args.max_temp_c if args.max_temp_c > 0 else None,
    )

    summary = run_benchmark(
        adb=adb,
        out_dir=out_dir,
        package_name=args.package,
        selected_buckets=selected_buckets,
        device_meta=device_meta,
        config=config,
        apk_verification=apk_verification,
        resume=args.resume,
        skip_prepare=args.skip_prepare,
        warm_mode=args.warm_mode,
        prepare_timeout_sec=args.prepare_timeout_sec,
        cooldown_poll_sec=args.cooldown_poll_sec,
        cooldown_max_wait_sec=args.cooldown_max_wait_sec,
        record_missing_page_sizes=args.record_missing_page_size,
        missing_reason=args.missing_reason,
    )
    print(json.dumps(summary, indent=2))
    return exit_code_for_summary(summary, allow_pending_16k=args.allow_pending_16k)


if __name__ == "__main__":
    sys.exit(main())
