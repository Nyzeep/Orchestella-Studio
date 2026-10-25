#!/usr/bin/env python3
"""AX-07 collector: turn fixed-format samples into one accelerator-perf report.

The collector is deliberately dumb and total: it never invents a metric, never
drops a failure sample, and never writes free-form text into the output. Every
field it emits is either a fixed enum, a count, an integer duration or a hash,
so a report can be archived and diffed without leaking paths, tokens or device
identifiers.

Input: one TSV with a mandatory header line, one sample per row. The header is
the 13 v1 columns, optionally followed by an ordered subset of the PVM-07
segment columns; a segment cell may be blank, which means "not measured" and
stays null in the report - it is never faked to 0. A header without segment
columns produces a byte-identical v1 report; any segment column produces v2.
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
SCHEMA_V2 = "accelerator-perf-v2"

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

# PVM-07 segment columns. Optional, always in this order after COLUMNS; the
# report field names are the column names.
SEGMENT_COLUMNS = (
    "preprocess_ms",
    "transport_ms",
    "transport_bytes",
    "payload_load_ms",
    "compile_ms",
    "link_ms",
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
        # PVM-03 compile service (spec §5.3). Without these a device run that
        # fails inside the payload cannot be written into the report at all.
        "PAYLOAD_LOAD_FAILED",
        "ARGV_REJECTED",
        "JOB_TOO_LARGE",
        "JOB_TIMEOUT",
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
    segment_columns: tuple = ()
    for index, raw in enumerate(lines, start=1):
        line = raw.strip("\n").strip("\r")
        if not line.strip():
            continue
        fields = line.split("\t")
        if not header_seen:
            segment_columns = _match_header(index, fields)
            header_seen = True
            continue
        rows.append(_parse_row(index, fields, segment_columns))
    if not header_seen:
        raise SampleError("line 1: header is missing")
    if not rows:
        raise SampleError("no samples")
    return rows


def _match_header(index: int, fields: list) -> tuple:
    """Returns the segment columns present in the header, in canonical order."""
    if tuple(fields) == COLUMNS:
        return ()
    if len(fields) < len(COLUMNS) or tuple(fields[: len(COLUMNS)]) != COLUMNS:
        raise SampleError(
            f"line {index}: header must be exactly {','.join(COLUMNS)} "
            f"optionally followed by an ordered subset of {','.join(SEGMENT_COLUMNS)}"
        )
    remaining = list(SEGMENT_COLUMNS)
    chosen = []
    for name in fields[len(COLUMNS):]:
        name = name.strip()
        if name not in remaining:
            raise SampleError(
                f"line {index}: segment columns must be an ordered subset of "
                f"{','.join(SEGMENT_COLUMNS)}"
            )
        chosen.append(name)
        remaining = remaining[remaining.index(name) + 1:]
    return tuple(chosen)


def _parse_row(index: int, fields: list, segment_columns: tuple) -> dict:
    if len(fields) != len(COLUMNS) + len(segment_columns):
        raise SampleError(
            f"line {index}: expected {len(COLUMNS) + len(segment_columns)} "
            f"tab-separated fields, got {len(fields)}"
        )
    names = list(COLUMNS) + list(segment_columns)
    row = dict(zip(names, (field.strip() for field in fields)))
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
    if segment_columns:
        for column in segment_columns:
            raw = row[column]
            row[column] = None if raw == "" else _non_negative_int(index, column, raw)
        for column in SEGMENT_COLUMNS:
            row.setdefault(column, None)
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
    segments = any(column in row for row in rows for column in SEGMENT_COLUMNS)
    scenarios = [
        _summarise(rows, scenario, mode, segments)
        for scenario in SCENARIOS
        for mode in MODES
        if any(row["scenario"] == scenario and row["mode"] == mode for row in rows)
    ]
    return {
        "schema": SCHEMA_V2 if segments else SCHEMA,
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


def _summarise(rows: list, scenario: str, mode: str, segments: bool) -> dict:
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
            }
        )
        if segments:
            summary.update(_segment_medians(ok))
        summary.update(
            {
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
            }
        )
        if segments:
            summary.update({column: None for column in SEGMENT_COLUMNS})
        summary.update(
            {
                "offload_permille": None,
                "retries": sum(row["retries"] for row in selected),
                "fallbacks": sum(row["fallbacks"] for row in selected),
                "peak_kb": max((row["peak_kb"] for row in selected), default=0),
            }
        )
    return summary


def _segment_medians(ok_rows: list) -> dict:
    """Median over the samples that measured the segment; blank means null."""
    medians = {}
    for column in SEGMENT_COLUMNS:
        values = [row[column] for row in ok_rows if row[column] is not None]
        medians[column] = perfstats.median(values) if values else None
    return medians


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
