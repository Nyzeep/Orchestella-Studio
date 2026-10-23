#!/usr/bin/env python3
"""AX-07 validator: check an accelerator-perf-v1 report and run the dry-run.

Two modes:

* ``--report path`` validates an existing report and prints every defect.
* ``--self-test`` re-collects the committed fixtures and compares them with the
  golden report byte for byte, then validates it and proves that tampered
  reports are rejected. This is the zero-device gate the CI runs.

The validator is stdlib-only on purpose: the CI image cannot install packages,
and a protocol nobody can run is not a protocol.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import collect
import perfstats

HERE = Path(__file__).resolve().parent
FIXTURE_SAMPLES = HERE / "fixtures" / "samples-dryrun.tsv"
GOLDEN_REPORT = HERE / "fixtures" / "expected-report.json"

_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_ALIAS = re.compile(r"^[A-Za-z0-9._-]{1,48}$")
REQUIRED_KEYS = {
    "schema",
    "build_id",
    "device_alias",
    "generated_at_utc",
    "protocol",
    "scenarios",
}


def validate_report(report: dict) -> list:
    """Returns a list of fixed, path-free defect strings; empty means valid."""
    errors = []
    if not isinstance(report, dict):
        return ["report must be a JSON object"]
    missing = REQUIRED_KEYS - set(report)
    if missing:
        return [f"missing keys: {','.join(sorted(missing))}"]
    if report["schema"] != collect.SCHEMA:
        errors.append("schema must be accelerator-perf-v1")
    for field in ("build_id", "device_alias"):
        if not isinstance(report[field], str) or not _ALIAS.match(report[field]):
            errors.append(f"{field} must be a safe alias")
    if not isinstance(report["generated_at_utc"], str) or not _TIMESTAMP.match(
        report["generated_at_utc"]
    ):
        errors.append("generated_at_utc must be an ISO-8601 UTC second")

    protocol = report["protocol"]
    if not isinstance(protocol, dict):
        errors.append("protocol must be an object")
    else:
        expected = {
            "min_samples": perfstats.MIN_SAMPLES,
            "iqr_method": perfstats.IQR_METHOD,
            "speedup_threshold": collect.SPEEDUP_THRESHOLD,
            "regression_permille": collect.REGRESSION_PERMILLE,
        }
        for key, value in expected.items():
            if protocol.get(key) != value:
                errors.append(f"protocol.{key} must be {value!r}")

    scenarios = report["scenarios"]
    if not isinstance(scenarios, list) or not scenarios:
        errors.append("scenarios must be a non-empty list")
        return errors

    seen = set()
    for entry in scenarios:
        errors.extend(_validate_scenario(entry, seen, protocol))
    return errors


def _validate_scenario(entry: dict, seen: set, protocol: dict) -> list:
    errors = []
    if not isinstance(entry, dict):
        return ["each scenario must be an object"]
    scenario = entry.get("scenario")
    mode = entry.get("mode")
    if scenario not in collect.SCENARIOS:
        errors.append(f"unknown scenario: {scenario!r}")
    if mode not in collect.MODES:
        errors.append(f"unknown mode: {mode!r}")
    key = (scenario, mode)
    if key in seen:
        errors.append(f"duplicate scenario/mode pair: {scenario}/{mode}")
    seen.add(key)

    n = entry.get("n")
    failures = entry.get("failures")
    if not isinstance(n, int) or n < 0:
        errors.append(f"{scenario}/{mode}: n must be a non-negative integer")
        return errors
    if not isinstance(failures, int) or failures < 0:
        errors.append(f"{scenario}/{mode}: failures must be a non-negative integer")
        return errors

    codes = entry.get("failure_codes")
    if not isinstance(codes, dict):
        errors.append(f"{scenario}/{mode}: failure_codes must be an object")
    else:
        for code, count in codes.items():
            if code not in collect.ALLOWED_CODES or code == "NONE":
                errors.append(f"{scenario}/{mode}: unknown failure code {code!r}")
            if not isinstance(count, int) or count <= 0:
                errors.append(f"{scenario}/{mode}: failure count must be positive")
        if sum(codes.values()) != failures:
            errors.append(f"{scenario}/{mode}: failure_codes must sum to failures")

    min_samples = protocol.get("min_samples", perfstats.MIN_SAMPLES)
    if entry.get("insufficient_samples") is not (n < min_samples):
        errors.append(f"{scenario}/{mode}: insufficient_samples disagrees with n")

    for field in ("retries", "fallbacks", "peak_kb"):
        value = entry.get(field)
        if not isinstance(value, int) or value < 0:
            errors.append(f"{scenario}/{mode}: {field} must be a non-negative integer")

    if n == 0:
        for field in ("median_ms", "iqr_ms", "min_ms", "max_ms", "phases_ms"):
            if entry.get(field) is not None:
                errors.append(f"{scenario}/{mode}: {field} must be null when n is 0")
        return errors

    for field in ("median_ms", "iqr_ms", "min_ms", "max_ms"):
        value = entry.get(field)
        if not isinstance(value, (int, float)):
            errors.append(f"{scenario}/{mode}: {field} must be a number")
            return errors
    low, med, high, spread = (
        entry["min_ms"],
        entry["median_ms"],
        entry["max_ms"],
        entry["iqr_ms"],
    )
    if low > med or med > high:
        errors.append(f"{scenario}/{mode}: min <= median <= max is violated")
    if high < low:
        errors.append(f"{scenario}/{mode}: max must not be below min")
    if spread < 0:
        errors.append(f"{scenario}/{mode}: iqr must not be negative")

    phases = entry.get("phases_ms")
    if not isinstance(phases, dict) or set(phases) != {
        "preprocess",
        "transfer",
        "compile",
        "link",
    }:
        errors.append(f"{scenario}/{mode}: phases_ms must have the four fixed phases")
    else:
        for name, value in phases.items():
            if not isinstance(value, (int, float)) or value < 0:
                errors.append(f"{scenario}/{mode}: phase {name} must be non-negative")

    offload = entry.get("offload_permille")
    if not isinstance(offload, int) or not 0 <= offload <= 1000:
        errors.append(f"{scenario}/{mode}: offload_permille must be within [0, 1000]")
    return errors


def self_test() -> int:
    """Fixed input in, fixed output out: the zero-device gate."""
    failures = []
    rows = collect.parse_samples(FIXTURE_SAMPLES.read_text(encoding="utf-8"))

    fixed_time = "2026-10-23T00:00:00Z"
    report = collect.build_report(rows, "dryrun-local", "bench-sim", fixed_time)
    rendered = collect.render(report)
    golden = GOLDEN_REPORT.read_text(encoding="utf-8")
    if rendered != golden:
        failures.append("collected report does not match fixtures/expected-report.json")

    errors = validate_report(json.loads(rendered))
    failures.extend(errors)

    # A protocol that accepts everything proves nothing: prove refusal works.
    for mutation, expected in (
        (lambda value: value["scenarios"][0].update({"median_ms": 10 ** 9}),
         "min <= median <= max is violated"),
        (lambda value: value.update({"schema": "accelerator-perf-v0"}),
         "schema must be accelerator-perf-v1"),
        (lambda value: value["scenarios"][0].update({"failure_codes": {"RAW_LOG": 1}}),
         "unknown failure code"),
    ):
        tampered = json.loads(rendered)
        mutation(tampered)
        defects = " | ".join(validate_report(tampered))
        if expected not in defects:
            failures.append(f"tampered report was not rejected: {expected}")

    if failures:
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        print(f"ACCELERATOR_PERF_SELFTEST_FAILED defects={len(failures)}")
        return 1
    print(f"ACCELERATOR_PERF_OK scenarios={len(report['scenarios'])}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=None)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if args.report is None:
        parser.error("either --report or --self-test is required")

    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
    except OSError:
        print("ACCELERATOR_PERF_VALIDATE_ERROR report could not be read", file=sys.stderr)
        return 2
    except json.JSONDecodeError as error:
        print(f"ACCELERATOR_PERF_VALIDATE_ERROR invalid JSON at line {error.lineno}",
              file=sys.stderr)
        return 2

    errors = validate_report(report)
    if errors:
        for error in errors:
            print(f"FAIL {error}", file=sys.stderr)
        print(f"ACCELERATOR_PERF_INVALID defects={len(errors)}")
        return 1
    print(f"ACCELERATOR_PERF_VALID scenarios={len(report['scenarios'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
