#!/usr/bin/env python3
"""AX-07 collector: turn fixed-format samples into one accelerator-perf-v1 report.

The collector is deliberately dumb and total: it never invents a metric, never
drops a failure sample, and never writes free-form text into the output. Every
field it emits is either a fixed enum, a count, an integer duration or a hash,
so a report can be archived and diffed without leaking paths, tokens or device
identifiers.

Input: one TSV with a mandatory header line, one sample per row.
Output: JSON on stdout or to --out, byte-identical for identical input when
--generated-at is fixed.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import perfstats

SCHEMA = "accelerator-perf-v1"

COLUMNS = (
    "scenario",
    "mode",
    "status",
    "wall_ms",
    "pre_ms",
    "tx_ms",
    "cc_ms",
    "ld_ms",
    "offload_permille",
    "retries",
    "fallbacks",
    "peak_kb",
    "code",
)

# Fixed scenario and mode vocabularies. Adding one is a protocol revision.
SCENARIOS = (
    "cold_start",
    "small_project",
    "medium_project_j1",
    "medium_project_j2",
    "medium_project_j4",
)
MODES = ("QEMU_ONLY", "QEMU_PLUS_ACCEL")
STATUSES = ("ok", "fail")

# Every accepted failure code. ACCEL_* are the AX-02 policy codes; the rest are
# lane/transport codes that already exist in this repository.
ALLOWED_CODES = frozenset(
    {
        "NONE",
        "ACCEL_AVAILABLE",
        "ACCEL_UNAVAILABLE_NO_NON_PROTECTED",
        "ACCEL_IMAGE_MISSING",
        "ACCEL_VSOCK_UNAVAILABLE",
        "COMPILER_MISMATCH",
        "THERMAL_BUDGET",
        "ACCELERATOR_UNAVAILABLE",
        "LANE_LOST",
        "STOP_UNCONFIRMED",
        "DISTCC_FALLBACK",
        "TIMEOUT",
    }
)

SPEEDUP_THRESHOLD = 1.5
REGRESSION_PERMILLE = 50

_ALIAS = re.compile(r"^[A-Za-z0-9._-]{1,48}$")
_IPV4 = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")
_MAC = re.compile(r"^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$")


class SampleError(ValueError):
    """One malformed sample. Messages carry a line number and field, never text."""


def parse_samples(text: str) -> list:
    """Returns rows as dicts. Raises SampleError on the first defect."""
    lines = text.replace("\r\n", "\n").split("\n")
    rows = []
    header_seen = False
    for index, raw in enumerate(lines, start=1):
        line = raw.strip("\n").strip("\r")
        if not line.strip():
            continue
        fields = line.split("\t")
        if not header_seen:
            if tuple(fields) != COLUMNS:
                raise SampleError(
                    f"line {index}: header must be exactly {','.join(COLUMNS)}"
                )
            header_seen = True
            continue
        rows.append(_parse_row(index, fields))
    if not header_seen:
        raise SampleError("line 1: header is missing")
    if not rows:
        raise SampleError("no samples")
    return rows


def _parse_row(index: int, fields: list) -> dict:
    if len(fields) != len(COLUMNS):
        raise SampleError(
            f"line {index}: expected {len(COLUMNS)} tab-separated fields, "
            f"got {len(fields)}"
        )
    row = dict(zip(COLUMNS, (field.strip() for field in fields)))
    if row["scenario"] not in SCENARIOS:
        raise SampleError(f"line {index}: scenario is not in the fixed vocabulary")
    if row["mode"] not in MODES:
        raise SampleError(f"line {index}: mode is not in the fixed vocabulary")
    if row["status"] not in STATUSES:
        raise SampleError(f"line {index}: status must be ok or fail")
    if row["code"] not in ALLOWED_CODES:
        raise SampleError(f"line {index}: code is not a fixed reason code")
    if row["status"] == "ok" and row["code"] != "NONE":
        raise SampleError(f"line {index}: an ok sample must carry code NONE")
    for column in (
        "wall_ms",
        "pre_ms",
        "tx_ms",
        "cc_ms",
        "ld_ms",
        "offload_permille",
        "retries",
        "fallbacks",
        "peak_kb",
    ):
        row[column] = _non_negative_int(index, column, row[column])
    if row["offload_permille"] > 1000:
        raise SampleError(f"line {index}: offload_permille must be within [0, 1000]")
    return row


def _non_negative_int(index: int, column: str, value: str) -> int:
    try:
        parsed = int(value, 10)
    except ValueError:
        raise SampleError(f"line {index}: {column} must be an integer") from None
    if parsed < 0:
        raise SampleError(f"line {index}: {column} must not be negative")
    return parsed


def build_report(
    rows: list,
    build_id: str,
    device_alias: str,
    generated_at: str,
) -> dict:
    _require_alias("build_id", build_id)
    _require_alias("device_alias", device_alias)
    scenarios = [
        _summarise(rows, scenario, mode)
        for scenario in SCENARIOS
        for mode in MODES
        if any(row["scenario"] == scenario and row["mode"] == mode for row in rows)
    ]
    return {
        "schema": SCHEMA,
        "build_id": build_id,
        "device_alias": device_alias,
        "generated_at_utc": generated_at,
        "protocol": {
            "min_samples": perfstats.MIN_SAMPLES,
            "iqr_method": perfstats.IQR_METHOD,
            "speedup_threshold": SPEEDUP_THRESHOLD,
            "regression_permille": REGRESSION_PERMILLE,
        },
        "scenarios": scenarios,
    }


def _summarise(rows: list, scenario: str, mode: str) -> dict:
    selected = [
        row for row in rows if row["scenario"] == scenario and row["mode"] == mode
    ]
    ok = [row for row in selected if row["status"] == "ok"]
    failures = [row for row in selected if row["status"] == "fail"]
    failure_codes = {}
    for row in failures:
        failure_codes[row["code"]] = failure_codes.get(row["code"], 0) + 1
    summary = {
        "scenario": scenario,
        "mode": mode,
        "n": len(ok),
        "failures": len(failures),
        "failure_codes": dict(sorted(failure_codes.items())),
        "insufficient_samples": len(ok) < perfstats.MIN_SAMPLES,
    }
    if ok:
        summary.update(perfstats.summarise(row["wall_ms"] for row in ok))
        summary.update(
            {
                "phases_ms": {
                    "preprocess": _phase_median(ok, "pre_ms"),
                    "transfer": _phase_median(ok, "tx_ms"),
                    "compile": _phase_median(ok, "cc_ms"),
                    "link": _phase_median(ok, "ld_ms"),
                },
                "offload_permille": _median_int(ok, "offload_permille"),
                "retries": sum(row["retries"] for row in selected),
                "fallbacks": sum(row["fallbacks"] for row in selected),
                "peak_kb": max(row["peak_kb"] for row in selected),
            }
        )
    else:
        summary.update(
            {
                "median_ms": None,
                "iqr_ms": None,
                "min_ms": None,
                "max_ms": None,
                "phases_ms": None,
                "offload_permille": None,
                "retries": sum(row["retries"] for row in selected),
                "fallbacks": sum(row["fallbacks"] for row in selected),
                "peak_kb": max((row["peak_kb"] for row in selected), default=0),
            }
        )
    return summary


def _phase_median(rows: list, column: str) -> float:
    return perfstats.median([row[column] for row in rows])


def _median_int(rows: list, column: str) -> int:
    return int(round(perfstats.median([row[column] for row in rows])))


def _require_alias(field: str, value: str) -> None:
    """Aliases must be free of paths, addresses and identifiers."""
    if not _ALIAS.match(value):
        raise SampleError(f"{field} must match ^[A-Za-z0-9._-]{{1,48}}$")
    if _IPV4.match(value) or _MAC.match(value):
        raise SampleError(f"{field} must be an alias, not an address")


def render(report: dict) -> str:
    return json.dumps(report, indent=2, ensure_ascii=False) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", required=True, type=Path)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--device-alias", required=True)
    parser.add_argument(
        "--generated-at",
        default=None,
        help="fixed UTC timestamp; defaults to the current time",
    )
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)

    try:
        rows = parse_samples(args.samples.read_text(encoding="utf-8"))
    except OSError:
        # The path is a local input; it must not reach the report or the log.
        print("ACCELERATOR_PERF_SAMPLE_ERROR input could not be read", file=sys.stderr)
        return 2
    except SampleError as error:
        print(f"ACCELERATOR_PERF_SAMPLE_ERROR {error}", file=sys.stderr)
        return 2

    generated_at = args.generated_at or _utc_now()
    try:
        report = build_report(rows, args.build_id, args.device_alias, generated_at)
    except SampleError as error:
        print(f"ACCELERATOR_PERF_SAMPLE_ERROR {error}", file=sys.stderr)
        return 2

    text = render(report)
    if args.out is None:
        sys.stdout.write(text)
    else:
        args.out.write_text(text, encoding="utf-8")
    return 0


def _utc_now() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    sys.exit(main())
