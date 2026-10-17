# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only Android preflight prototype. It never starts or controls a VM."""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
sys.dont_write_bytecode = True
from pathlib import Path

from avf_evidence_io import EvidenceStore, Rejected, SafeParser, require_supported_runtime

PACKAGE = "io.github.nyzeep.orchestella"
# Device-side arguments are constants. No shell strings, paths, operators or user commands.
COMMANDS = {
    "rom_build_id": ("getprop", "ro.build.id"),
    "rom_release": ("getprop", "ro.build.version.release"),
    "rom_fingerprint_sha256": ("getprop", "ro.build.fingerprint"),
    "api": ("getprop", "ro.build.version.sdk"),
    "abi": ("getprop", "ro.product.cpu.abilist"),
    "page_bytes": ("getconf", "PAGE_SIZE"),
    "module_android": ("cmd", "package", "list", "packages", "--apex-only", "--show-versioncode", "com.android.virt"),
    "module_google": ("cmd", "package", "list", "packages", "--apex-only", "--show-versioncode", "com.google.android.virt"),
    "avf_feature": ("cmd", "package", "has-feature", "android.software.virtualization_framework"),
    "declared_nonprotected": ("getprop", "ro.boot.hypervisor.vm.supported"),
    "declared_protected": ("getprop", "ro.boot.hypervisor.protected_vm.supported"),
}
ABI = {"arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "riscv64"}


def alias(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-z][a-z0-9-]{2,39}", value):
        raise Rejected("ALIAS_REJECTED")
    return value


def serial_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", value):
        raise Rejected("SERIAL_REJECTED")
    return value


def adb_value(value):
    path = Path(value)
    if not path.is_absolute() or path.name.lower() not in ("adb", "adb.exe") or not path.is_file():
        raise Rejected("ADB_REJECTED")
    return str(path)


def device_argv(adb, serial, command_id):
    serial_value(serial)
    if command_id not in COMMANDS:
        raise Rejected("COMMAND_REJECTED")
    return [adb, "-s", serial, "shell", *COMMANDS[command_id]]


def run_fixed(argv, *, timeout_seconds=10):
    environment = os.environ.copy()
    environment["MSYS_NO_PATHCONV"] = "1"
    try:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=timeout_seconds, shell=False, env=environment)
    except subprocess.TimeoutExpired:
        raise Rejected("ADB_TIMEOUT") from None
    except OSError:
        raise Rejected("ADB_UNAVAILABLE") from None
    if result.returncode != 0 or len(result.stdout) > 65536:
        raise Rejected("ADB_QUERY_FAILED")
    try:
        return result.stdout.decode("utf-8", errors="strict")
    except UnicodeError:
        raise Rejected("ADB_ENCODING_REJECTED") from None


def parse_field(field, raw):
    text = raw.strip()
    if len(text) > 1024 or any(ord(c) < 32 for c in text if c != "\n"):
        return None
    if field in ("rom_build_id", "rom_release"):
        return text if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,95}", text) else None
    if field == "rom_fingerprint_sha256":
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_./:+-]{0,255}", text):
            return None
        return hashlib.sha256(text.encode("utf-8")).hexdigest()
    if field in ("api", "page_bytes"):
        if not re.fullmatch(r"[0-9]{1,6}", text):
            return None
        number = int(text)
        return number if (1 <= number <= 1000 if field == "api" else number in (4096, 16384, 65536)) else None
    if field == "abi":
        values = text.split(",")
        return values if len(values) <= len(ABI) and len(set(values)) == len(values) and all(v in ABI for v in values) else None
    if field.startswith("module_"):
        package = {"module_android": "com.android.virt", "module_google": "com.google.android.virt"}[field]
        match = re.fullmatch(r"package:" + re.escape(package) + r" versionCode:([0-9]{1,18})", text)
        return {"package": package, "version_code": int(match[1])} if match else None
    if field == "avf_feature":
        return {"true": True, "false": False}.get(text)
    if field.startswith("declared_"):
        return {"1": True, "0": False, "true": True, "false": False}.get(text)
    raise Rejected("FIELD_REJECTED")


def discover(adb, runner=run_fixed):
    raw = runner([adb, "devices", "-l"])
    counts = {"device": 0, "offline": 0, "unauthorized": 0, "other": 0}
    lines = raw.splitlines()
    if not lines or lines[0].strip() != "List of devices attached":
        raise Rejected("DISCOVERY_FORMAT_REJECTED")
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split()
        state = parts[1] if len(parts) >= 2 else "other"
        counts[state if state in counts else "other"] += 1
    return {"schema_version": 1, "operation": "discovery", "counts": counts,
            "status": "NO_DEVICE" if not any(counts.values()) else "EXPLICIT_SERIAL_REQUIRED",
            "serials_recorded": False, "raw_output_recorded": False,
            "avf_execution": "NOT_TESTED"}


def collect(adb, serial, device_alias, runner=run_fixed):
    serial_value(serial)
    alias(device_alias)
    report = {"schema_version": 1, "operation": "readonly_preflight", "device_alias": device_alias,
              "fields": {}, "queries_completed": 0, "status": "PREFLIGHT_ONLY",
              "permissions": {"MANAGE_VIRTUAL_MACHINE": "unknown", "USE_CUSTOM_VIRTUAL_MACHINE": "unknown"},
              "capabilities": {"nonprotected_api": "unknown", "protected_api": "unknown"},
              "unobserved_reason": "OWNED_TYPED_PROBE_REQUIRED", "avf_execution": "NOT_TESTED",
              "serials_recorded": False, "raw_output_recorded": False}
    for field in COMMANDS:
        try:
            value = parse_field(field, runner(device_argv(adb, serial, field)))
        except Rejected as error:
            report["status"] = "PARTIAL_PREFLIGHT"
            report["error"] = str(error)
            break  # no retry, no next query after a command failure
        report["fields"][field] = {"value": value, "state": "observed" if value is not None else "unknown"}
        report["queries_completed"] += 1
    return report


def main(argv=None):
    parser = SafeParser(prog="avf_preflight", description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    for operation in ("discover", "collect"):
        command = sub.add_parser(operation)
        command.add_argument("--adb", required=True)
        command.add_argument("--output-root", required=True, help="explicit absolute approved external output root")
        command.add_argument("--output", required=True, help="new relative evidence/*.json path inside the explicit output root")
        if operation == "collect":
            command.add_argument("--serial", required=True)
            command.add_argument("--device-alias", required=True)
    args = parser.parse_args(argv)
    try:
        require_supported_runtime()
        adb = adb_value(args.adb)
        store = EvidenceStore(args.output_root, args.output_root)
        if not args.output.endswith(".json"):
            raise Rejected("OUTPUT_REJECTED")
        store.assert_new_output(args.output)
        report = discover(adb) if args.operation == "discover" else collect(adb, args.serial, args.device_alias)
        store.save_json(args.output, report)
        print(json.dumps(report, sort_keys=True))
        return 2 if report["status"] == "PARTIAL_PREFLIGHT" else 0
    except (Rejected, OSError) as error:
        print(json.dumps({"error": str(error) if isinstance(error, Rejected) else "IO_ERROR"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
