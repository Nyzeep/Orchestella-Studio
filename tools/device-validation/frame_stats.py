#!/usr/bin/env python3
"""Parse `dumpsys gfxinfo <package> framestats` into frame-time percentiles.

Session 19 (T-D) needs a repeatable before/after read of what the user feels as
"animation smoothness". gfxinfo's summary block only reports Janky frames with
its own thresholds; the raw framestats CSV is the primary source, so this tool
reads that CSV and derives the numbers the report quotes:

  * frame_ms = FrameCompleted - IntendedVsync for every completed frame;
  * p50 / p90 / p99 over the collected window;
  * jank_count = frames whose frame_ms exceeds the display's frame budget;
  * jank_pct = jank_count / frames.

Usage:
  adb shell dumpsys gfxinfo <pkg> framestats > framestats.txt
  python tools/device-validation/frame_stats.py framestats.txt --refresh-hz 120

Exit codes: 0 ok; 1 usage/parse failure.
"""

from __future__ import annotations

import argparse
import statistics
import sys
from pathlib import Path

REQUIRED = (
    "Flags",
    "IntendedVsync",
    "FrameCompleted",
    "HandleInputStart",
    "AnimationStart",
    "PerformTraversalsStart",
    "DrawStart",
    "SyncQueued",
    "SyncStart",
    "IssueDrawCommandsStart",
    "SwapBuffers",
)


def parse_framestats(text: str) -> list[dict[str, int]]:
    """Return one dict per data row, keyed by the gfxinfo column names."""
    lines = [line.strip() for line in text.splitlines()]
    header: list[str] | None = None
    frames: list[dict[str, int]] = []
    for line in lines:
        if not line:
            continue
        cells = [cell.strip() for cell in line.split(",")]
        # gfxinfo emits a trailing comma on both the header and every data row;
        # drop only the trailing empty cell(s), never interior ones.
        while cells and cells[-1] == "":
            cells.pop()
        if not cells:
            continue
        if cells[0] == "Flags":
            if not all(name in cells for name in REQUIRED):
                raise ValueError("framestats header is missing required columns")
            header = cells
            continue
        if header is None or len(cells) != len(header):
            continue
        if not cells[0].isdigit():
            continue
        try:
            frames.append({name: int(cell) for name, cell in zip(header, cells)})
        except ValueError:
            # A partial row (for example a zeroed FrameCompleted) is not a
            # completed frame and carries no timing evidence.
            continue
    return frames


def frame_times_ms(frames: list[dict[str, int]]) -> list[float]:
    return [
        (frame["FrameCompleted"] - frame["IntendedVsync"]) / 1_000_000.0
        for frame in frames
        if frame.get("FrameCompleted") and frame["FrameCompleted"] > frame["IntendedVsync"]
    ]


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def summarize(text: str, refresh_hz: float) -> dict[str, float]:
    frames = parse_framestats(text)
    times = frame_times_ms(frames)
    budget_ms = 1000.0 / refresh_hz
    jank = [value for value in times if value > budget_ms]
    return {
        "frames": float(len(times)),
        "budget_ms": budget_ms,
        "p50_ms": percentile(times, 0.50),
        "p90_ms": percentile(times, 0.90),
        "p99_ms": percentile(times, 0.99),
        "max_ms": max(times) if times else 0.0,
        "mean_ms": statistics.fmean(times) if times else 0.0,
        "jank_count": float(len(jank)),
        "jank_pct": (100.0 * len(jank) / len(times)) if times else 0.0,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("framestats", type=Path, help="saved `dumpsys gfxinfo` output")
    parser.add_argument("--refresh-hz", type=float, default=120.0,
                        help="display refresh rate used for the jank budget (default 120)")
    parser.add_argument("--label", default="", help="plain-text scenario label for the table")
    args = parser.parse_args(argv)

    try:
        text = args.framestats.read_text(encoding="utf-8", errors="replace")
        stats = summarize(text, args.refresh_hz)
    except (OSError, ValueError) as failure:
        print(f"frame_stats: {failure}", file=sys.stderr)
        return 1

    label = args.label or args.framestats.stem
    print(f"{label}\tframes={stats['frames']:.0f}"
          f"\tp50={stats['p50_ms']:.2f}ms"
          f"\tp90={stats['p90_ms']:.2f}ms"
          f"\tp99={stats['p99_ms']:.2f}ms"
          f"\tmax={stats['max_ms']:.2f}ms"
          f"\tjank={stats['jank_pct']:.1f}%"
          f" ({stats['jank_count']:.0f}/{stats['frames']:.0f}, budget {stats['budget_ms']:.2f}ms)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
