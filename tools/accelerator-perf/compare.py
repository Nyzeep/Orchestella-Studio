#!/usr/bin/env python3
"""AX-07 comparison: apply the pre-registered stop conditions to two reports.

The thresholds are not negotiable at analysis time — they are read from the
reports' own ``protocol`` block, which the collector froze when the data was
produced. A verdict is one of three fixed codes:

* ``PRODUCTIZE``   — every scenario reaches the speedup threshold;
* ``REGRESSION``   — some scenario is slower than the baseline beyond the
  regression budget, so that variant is rolled back;
* ``BELOW_THRESHOLD`` — faster, but not enough: record the negative result and
  do not claim a product win.

Nothing here reads a device, and no path or identifier is written to the output.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import perfstats

VERDICT_SCHEMA = "accelerator-perf-verdict-v1"


class VerdictError(ValueError):
    """The two reports cannot be compared; the message names the defect only."""


def compare(baseline: dict, candidate: dict) -> dict:
    _require_same_world(baseline, candidate)
    protocol = baseline["protocol"]
    threshold = protocol["speedup_threshold"]
    regression = protocol["regression_permille"] / 1000.0

    baseline_index = _index(baseline)
    candidate_index = _index(candidate)
    shared = [key for key in baseline_index if key in candidate_index]
    if not shared:
        raise VerdictError("the two reports share no scenario/mode pair")

    entries = []
    for scenario, mode in shared:
        base_entry = baseline_index[(scenario, mode)]
        candidate_entry = candidate_index[(scenario, mode)]
        if not base_entry["n"] or not candidate_entry["n"]:
            entries.append(
                {
                    "scenario": scenario,
                    "mode": mode,
                    "baseline_median_ms": base_entry["median_ms"],
                    "candidate_median_ms": candidate_entry["median_ms"],
                    "speedup": None,
                    "verdict": "NO_DATA",
                }
            )
            continue
        ratio = perfstats.speedup(
            base_entry["median_ms"], candidate_entry["median_ms"]
        )
        entries.append(
            {
                "scenario": scenario,
                "mode": mode,
                "baseline_median_ms": base_entry["median_ms"],
                "candidate_median_ms": candidate_entry["median_ms"],
                "speedup": float(f"{ratio:.2f}"),
                "verdict": _verdict(ratio, threshold, regression),
            }
        )

    return {
        "schema": VERDICT_SCHEMA,
        "baseline_build_id": baseline["build_id"],
        "candidate_build_id": candidate["build_id"],
        "speedup_threshold": threshold,
        "regression_permille": protocol["regression_permille"],
        "overall": _overall(entries),
        "scenarios": entries,
    }


def _verdict(ratio: float, threshold: float, regression: float) -> str:
    if ratio < 1.0 - regression:
        return "REGRESSION"
    if ratio >= threshold:
        return "PRODUCTIZE"
    return "BELOW_THRESHOLD"


def _overall(entries: list) -> str:
    verdicts = {entry["verdict"] for entry in entries}
    if "NO_DATA" in verdicts:
        return "INCOMPLETE"
    if "REGRESSION" in verdicts:
        return "REGRESSION"
    if verdicts == {"PRODUCTIZE"}:
        return "PRODUCTIZE"
    return "BELOW_THRESHOLD"


def _index(report: dict) -> dict:
    return {(entry["scenario"], entry["mode"]): entry for entry in report["scenarios"]}


def _require_same_world(baseline: dict, candidate: dict) -> None:
    for name, report in (("baseline", baseline), ("candidate", candidate)):
        if not isinstance(report, dict) or "scenarios" not in report:
            raise VerdictError(f"{name} is not an accelerator-perf-v1 report")
    for key in ("min_samples", "iqr_method", "speedup_threshold", "regression_permille"):
        if baseline["protocol"].get(key) != candidate["protocol"].get(key):
            raise VerdictError(f"reports disagree on protocol.{key}")
    # Comparing two builds is only meaningful when it is the same device class.
    if baseline.get("device_alias") != candidate.get("device_alias"):
        raise VerdictError("reports come from different device aliases")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args(argv)

    try:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        candidate = json.loads(args.candidate.read_text(encoding="utf-8"))
    except OSError:
        print("ACCELERATOR_PERF_COMPARE_ERROR report could not be read", file=sys.stderr)
        return 2
    except json.JSONDecodeError as error:
        print(f"ACCELERATOR_PERF_COMPARE_ERROR invalid JSON at line {error.lineno}",
              file=sys.stderr)
        return 2

    try:
        verdict = compare(baseline, candidate)
    except VerdictError as error:
        print(f"ACCELERATOR_PERF_COMPARE_ERROR {error}", file=sys.stderr)
        return 2

    text = json.dumps(verdict, indent=2, ensure_ascii=False) + "\n"
    if args.out is None:
        sys.stdout.write(text)
    else:
        args.out.write_text(text, encoding="utf-8")
    return 0 if verdict["overall"] != "REGRESSION" else 1


if __name__ == "__main__":
    sys.exit(main())
