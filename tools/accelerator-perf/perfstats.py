"""Deterministic summary statistics for the AX-07 accelerator protocol.

Only the standard library is used: the CI image cannot install packages, and a
measurement protocol whose numbers depend on a third-party percentile mode is
not reproducible. The one interpolation rule is fixed here and published in the
report itself, so a reader can recompute every number by hand.
"""

from __future__ import annotations

from typing import Iterable, Sequence

MIN_SAMPLES = 10
IQR_METHOD = "linear-interpolation-on-sorted-values"


def percentile(sorted_values: Sequence[int], percent: float) -> float:
    """Linear interpolation between the two nearest ranks (the numpy default).

    ``sorted_values`` must be non-empty and already sorted ascending.
    """
    if not sorted_values:
        raise ValueError("percentile of an empty sample")
    if not 0.0 <= percent <= 100.0:
        raise ValueError("percent must be within [0, 100]")
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    position = (len(sorted_values) - 1) * (percent / 100.0)
    lower = int(position)
    upper = min(lower + 1, len(sorted_values) - 1)
    fraction = position - lower
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * fraction


def median(values: Sequence[int]) -> float:
    return percentile(sorted(values), 50.0)


def iqr(values: Sequence[int]) -> float:
    ordered = sorted(values)
    return percentile(ordered, 75.0) - percentile(ordered, 25.0)


def summarise(values: Iterable[int]) -> dict:
    """One scenario's distribution. Failures are excluded by the caller only."""
    ordered = sorted(int(value) for value in values)
    if not ordered:
        raise ValueError("no successful samples")
    if any(value < 0 for value in ordered):
        raise ValueError("durations must not be negative")
    return {
        "n": len(ordered),
        "median_ms": _round(median(ordered)),
        "iqr_ms": _round(iqr(ordered)),
        "min_ms": ordered[0],
        "max_ms": ordered[-1],
    }


def speedup(baseline_ms: float, candidate_ms: float) -> float:
    """How many times faster the candidate is; 1.0 means identical."""
    if candidate_ms <= 0:
        raise ValueError("candidate median must be positive")
    return baseline_ms / candidate_ms


def _round(value: float) -> float:
    """Two decimals, stable across platforms (no banker's rounding surprises)."""
    return float(f"{value:.2f}")
