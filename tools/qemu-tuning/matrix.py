#!/usr/bin/env python3
"""T7 QEMU single-variable tuning matrix helper.

This tool only generates the experiment matrix and records device metadata. It
does not fabricate measurements: a device run is reported as ``not-tested``
until an instrumented build actually executes the workloads. Every variant
changes exactly one knob relative to the baseline, and ``cache=unsafe`` is rejected.
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

BASELINE = {
    "cpu_model": "cortex-a57",
    "tb_size_kb": 0,
    "block_aio": "",
    "discard_unmap": False,
    "detect_zeroes_unmap": False,
    "vcpu_count": 0,
    "passt_unix_socket": "",
}

VARIANTS = [
    ("baseline", "none", {}),
    ("cpu-neoverse-n1", "cpu_model", {"cpu_model": "neoverse-n1"}),
    ("cpu-max-sve-off", "cpu_model", {"cpu_model": "max,sve=off"}),
    ("tb-size-32", "tb_size_kb", {"tb_size_kb": 32}),
    ("tb-size-64", "tb_size_kb", {"tb_size_kb": 64}),
    ("tb-size-128", "tb_size_kb", {"tb_size_kb": 128}),
    ("aio-io-uring", "block_aio", {"block_aio": "io_uring"}),
    ("discard-unmap", "discard_unmap", {"discard_unmap": True}),
    ("detect-zeroes-unmap", "detect_zeroes_unmap", {"detect_zeroes_unmap": True}),
    ("vcpu-4", "vcpu_count", {"vcpu_count": 4}),
    ("net-unix-stream", "passt_unix_socket", {"passt_unix_socket": "/data/local/tmp/orchestella-passt.sock"}),
]

WORKLOADS = ["boot-to-prompt", "apt-update", "tar-xzf", "gcc-compile", "dd", "iperf3"]


def validate_variants() -> None:
    for variant_id, variable, overrides in VARIANTS:
        if not variant_id or not variable:
            raise SystemExit("variant id and variable are required")
        if variable == "none":
            if overrides:
                raise SystemExit("baseline must not override any tuning value")
            continue
        changed = [key for key, value in overrides.items() if BASELINE.get(key) != value]
        if changed != [variable]:
            raise SystemExit(f"variant {variant_id} must change exactly {variable}, changed {changed}")
        for value in overrides.values():
            if "cache=unsafe" in str(value):
                raise SystemExit("cache=unsafe is forbidden")


def tuning_row(overrides: dict) -> dict:
    row = dict(BASELINE)
    row.update(overrides)
    return row


def adb(serial: str | None, *args: str) -> str:
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += list(args)
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT).strip()


def device_metadata(serial: str | None) -> dict:
    if shutil.which("adb") is None:
        return {"available": False, "reason": "adb-not-installed"}
    try:
        return {
            "available": True,
            "serial": serial or "default",
            "model": adb(serial, "shell", "getprop", "ro.product.model"),
            "android": adb(serial, "shell", "getprop", "ro.build.version.release"),
            "fingerprint": adb(serial, "shell", "getprop", "ro.build.fingerprint"),
            "battery": adb(serial, "shell", "dumpsys", "battery"),
            "thermal": adb(serial, "shell", "dumpsys", "thermalservice"),
        }
    except (subprocess.CalledProcessError, OSError) as failure:
        return {"available": False, "reason": "adb-failed", "detail": str(failure)}


def write_tsv(path: Path, metadata: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "variant",
        "variable",
        *BASELINE.keys(),
        "workload",
        "samples",
        "status",
        "device_model",
        "android",
        "battery_temp_c",
    ]
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for variant_id, variable, overrides in VARIANTS:
            tuning = tuning_row(overrides)
            for workload in WORKLOADS:
                writer.writerow({
                    "variant": variant_id,
                    "variable": variable,
                    **tuning,
                    "workload": workload,
                    "samples": 0,
                    "status": "not-tested",
                    "device_model": metadata.get("model", ""),
                    "android": metadata.get("android", ""),
                    "battery_temp_c": "",
                })


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="validate and emit the matrix without measurement")
    parser.add_argument("--device", metavar="SERIAL", help="record adb device metadata for this serial")
    parser.add_argument("--output", type=Path, default=Path("docs/reports/tickets/runtime-performance-07/matrix.tsv"))
    parser.add_argument("--json", action="store_true", help="emit a JSON summary instead of TSV")
    args = parser.parse_args()

    validate_variants()
    metadata = device_metadata(args.device) if args.device else {"available": False, "reason": "no-device-requested"}

    if args.json:
        payload = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "device": metadata,
            "workloads": WORKLOADS,
            "variants": [
                {"id": variant_id, "variable": variable, "tuning": tuning_row(overrides)}
                for variant_id, variable, overrides in VARIANTS
            ],
            "status": "not-tested" if not metadata.get("available") else "metadata-only",
            "note": "No measurements are fabricated; run an instrumented build for timed samples.",
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    else:
        write_tsv(args.output, metadata)

    print(f"variants={len(VARIANTS)} workloads={len(WORKLOADS)} rows={len(VARIANTS) * len(WORKLOADS)}")
    print(f"device={metadata.get('model', 'not-tested')} status=not-tested")
    print(f"output={args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
